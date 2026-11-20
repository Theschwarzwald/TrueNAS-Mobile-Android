package com.example.truenasapp.perf

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import com.example.truenasapp.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- choices shown in Settings

enum class PerfTier(val label: String) { MAX("Maximum"), BALANCED("Balanced"), SAVER("Battery saver") }

enum class PerfMode(val label: String) {
    AUTO("Automatic"), MAX("Always maximum"), BALANCED("Balanced"), SAVER("Battery saver")
}

enum class RefreshMode(val label: String) { AUTO("Automatic"), HIGHEST("Highest available"), STANDARD("60 Hz") }

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

enum class OledMode(val label: String) { AUTO("Automatic"), ON("On"), OFF("Off") }

enum class BackgroundMode(val label: String, val keepMs: Long?) {
    AUTO("Automatic", null),
    SHORT("30 seconds", 30_000L),
    MEDIUM("2 minutes", 120_000L),
    LONG("10 minutes", 600_000L),
}

// ---------------------------------------------------------------- what we know and decided

data class DeviceInfo(
    val cores: Int,
    val performanceCores: Int?,
    val efficiencyCores: Int?,
    val totalRamBytes: Long,
    val lowRamDevice: Boolean,
    val maxRefreshHz: Float,
    val likelyOled: Boolean,
) {
    val ramGb: Int get() = ceil(totalRamBytes / 1_000_000_000.0).toInt()

    val summary: String
        get() {
            val coreText = if (performanceCores != null && efficiencyCores != null)
                "$cores cores ($performanceCores performance + $efficiencyCores efficiency)" else "$cores cores"
            return "$coreText · $ramGb GB RAM · up to ${maxRefreshHz.roundToInt()} Hz"
        }
}

data class PerfState(
    val tier: PerfTier,
    val reason: String,
    /** Refresh rate to ask the screen for. */
    val refreshHz: Float,
    /** How many points the live charts keep. */
    val chartPoints: Int,
    /** Use every Nth live update (2 = half as much drawing). */
    val chartSampleEvery: Int,
    /** Shaded area under chart lines. */
    val chartFill: Boolean,
    /** How long to stay connected after leaving the app. */
    val backgroundKeepMs: Long,
    val pingSeconds: Long,
    val maxBackoffMs: Long,
    /** Use true black (instead of dark grey) when the dark theme is on. */
    val pureBlackWhenDark: Boolean,
    val perfMode: PerfMode,
    val refreshMode: RefreshMode,
    val themeMode: ThemeMode,
    val oledMode: OledMode,
    val backgroundMode: BackgroundMode,
    val device: DeviceInfo,
)

/**
 * Starts at full power, then steps down (and back up) based on what the phone reports:
 * Battery Saver, heat, memory pressure and dropped frames. All the watching happens off the
 * main thread and pauses while the app is in the background.
 */
class PerformanceManager(private val app: Application, private val store: SecureStore) : ComponentCallbacks2 {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager = app.getSystemService(PowerManager::class.java)
    private val activityManager = app.getSystemService(ActivityManager::class.java)

    @Volatile var device: DeviceInfo = detectDevice()
        private set

    // Live "feedback" from the device.
    @Volatile private var powerSave = powerManager.isPowerSaveMode
    @Volatile private var thermal = powerManager.currentThermalStatus
    @Volatile private var memoryPressure = 0 // 0 fine, 1 low, 2 critical
    @Volatile private var jankLevel = 0 // 0..2 steps down because of dropped frames
    private var lastJankChange = 0L
    private var smoothWindows = 0
    private var monitorJob: Job? = null

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<PerfState> = _state

    fun start() {
        powerManager.addThermalStatusListener(app.mainExecutor) { status ->
            thermal = status
            recompute()
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    powerSave = powerManager.isPowerSaveMode
                    recompute()
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.registerComponentCallbacks(this)

        // Reading CPU core layout touches files, so do it in the background.
        scope.launch {
            readCoreClusters(device.cores)?.let { (perf, eff) ->
                device = device.copy(performanceCores = perf, efficiencyCores = eff)
                recompute()
            }
        }
    }

    fun onForeground() {
        powerSave = powerManager.isPowerSaveMode
        thermal = powerManager.currentThermalStatus
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                checkMemory()
                delay(20_000)
            }
        }
        recompute()
    }

    fun onBackground() {
        monitorJob?.cancel() // no polling while you're not using the app
    }

    /** The screen reports roughly every 120 drawn frames how many were late. */
    @Synchronized
    fun reportFrames(total: Int, janky: Int) {
        if (total <= 0) return
        val ratio = janky.toFloat() / total
        val now = SystemClock.elapsedRealtime()
        when {
            ratio > 0.15f && jankLevel < 2 && now - lastJankChange > 10_000 -> {
                jankLevel++
                lastJankChange = now
                smoothWindows = 0
                recompute()
            }
            ratio < 0.03f -> {
                smoothWindows++
                // Smooth for a while? Try stepping back up.
                if (jankLevel > 0 && smoothWindows >= 5 && now - lastJankChange > 60_000) {
                    jankLevel--
                    lastJankChange = now
                    smoothWindows = 0
                    recompute()
                }
            }
            else -> smoothWindows = 0
        }
    }

    // ---------------------------------------------------------------- user choices

    fun setPerfMode(m: PerfMode) { store.perfMode = m.name; recompute() }
    fun setRefreshMode(m: RefreshMode) { store.refreshMode = m.name; recompute() }
    fun setThemeMode(m: ThemeMode) { store.themeMode = m.name; recompute() }
    fun setOledMode(m: OledMode) { store.oledMode = m.name; recompute() }
    fun setBackgroundMode(m: BackgroundMode) { store.backgroundMode = m.name; recompute() }

    // ---------------------------------------------------------------- the decision

    private fun recompute() {
        _state.value = compute()
    }

    private fun compute(): PerfState {
        val perfMode = enumOr(store.perfMode, PerfMode.AUTO)
        val refreshMode = enumOr(store.refreshMode, RefreshMode.AUTO)
        val themeMode = enumOr(store.themeMode, ThemeMode.SYSTEM)
        val oledMode = enumOr(store.oledMode, OledMode.AUTO)
        val backgroundMode = enumOr(store.backgroundMode, BackgroundMode.AUTO)

        // Start at maximum and work down from what the device tells us.
        var level = 0
        val why = mutableListOf<String>()
        if (device.lowRamDevice || device.totalRamBytes < 3_500_000_000L) {
            level = 1; why += "this device has limited memory"
        }
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            level = 2; why += "the device is hot"
        } else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            level = max(level, 1); why += "the device is warm"
        }
        if (memoryPressure == 2) {
            level = 2; why += "memory is very low"
        } else if (memoryPressure == 1) {
            level = max(level, 1); why += "memory is getting low"
        }
        if (jankLevel > 0) {
            level = max(level, jankLevel); why += "keeping things smooth"
        }
        if (powerSave) {
            level = 2; why += "Battery Saver is on"
        }

        val tier = when (perfMode) {
            PerfMode.AUTO -> PerfTier.entries[level]
            PerfMode.MAX -> PerfTier.MAX
            PerfMode.BALANCED -> PerfTier.BALANCED
            PerfMode.SAVER -> PerfTier.SAVER
        }
        val reason = when {
            perfMode != PerfMode.AUTO -> "Set by you"
            why.isEmpty() -> "Full performance: nothing is holding it back"
            else -> "Lowered because " + why.joinToString(" and ")
        }

        val maxHz = device.maxRefreshHz
        val refreshHz = when (refreshMode) {
            RefreshMode.AUTO -> if (tier == PerfTier.SAVER) 60f else maxHz
            RefreshMode.HIGHEST -> maxHz
            RefreshMode.STANDARD -> 60f
        }.coerceAtMost(maxHz)

        val keepMs = backgroundMode.keepMs ?: when (tier) {
            PerfTier.MAX -> 5 * 60_000L
            PerfTier.BALANCED -> 2 * 60_000L
            PerfTier.SAVER -> 30_000L
        }

        return PerfState(
            tier = tier,
            reason = reason,
            refreshHz = refreshHz,
            chartPoints = when (tier) { PerfTier.MAX -> 90; PerfTier.BALANCED -> 60; PerfTier.SAVER -> 30 },
            chartSampleEvery = if (tier == PerfTier.SAVER) 2 else 1,
            chartFill = tier != PerfTier.SAVER,
            backgroundKeepMs = keepMs,
            pingSeconds = when (tier) { PerfTier.MAX -> 20L; PerfTier.BALANCED -> 30L; PerfTier.SAVER -> 45L },
            maxBackoffMs = when (tier) { PerfTier.MAX -> 15_000L; PerfTier.BALANCED -> 30_000L; PerfTier.SAVER -> 60_000L },
            pureBlackWhenDark = when (oledMode) {
                OledMode.ON -> true
                OledMode.OFF -> false
                OledMode.AUTO -> device.likelyOled || tier == PerfTier.SAVER
            },
            perfMode = perfMode,
            refreshMode = refreshMode,
            themeMode = themeMode,
            oledMode = oledMode,
            backgroundMode = backgroundMode,
            device = device,
        )
    }

    // ---------------------------------------------------------------- device detection

    private fun detectDevice(): DeviceInfo {
        val mem = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val (maxHz, oled) = readDisplay()
        return DeviceInfo(
            cores = Runtime.getRuntime().availableProcessors(),
            performanceCores = null,
            efficiencyCores = null,
            totalRamBytes = mem.totalMem,
            lowRamDevice = activityManager.isLowRamDevice,
            maxRefreshHz = maxHz,
            likelyOled = oled,
        )
    }

    /**
     * Android doesn't say whether a screen is OLED, so this is a best guess:
     * screens that support HDR video are almost always OLED on phones and tablets.
     */
    @Suppress("DEPRECATION")
    private fun readDisplay(): Pair<Float, Boolean> {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val maxHz = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        return maxHz to hdr
    }

    /** Splits cores into performance vs efficiency by their top clock speed. */
    private fun readCoreClusters(cores: Int): Pair<Int, Int>? = runCatching {
        val freqs = (0 until cores).map {
            File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }
        val slowest = freqs.min()
        val fast = freqs.count { it > slowest }
        if (fast == 0) null else fast to (cores - fast)
    }.getOrNull()

    private fun checkMemory() {
        val mem = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val level = when {
            mem.lowMemory -> 2
            mem.availMem < mem.threshold * 2 -> 1
            else -> 0
        }
        if (level != memoryPressure) {
            memoryPressure = level
            recompute()
        }
    }

    // ---------------------------------------------------------------- system callbacks

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        val newLevel = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 2
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 1
            else -> return
        }
        if (newLevel > memoryPressure) {
            memoryPressure = newLevel
            recompute()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Foldables can switch between screens with different refresh rates.
        val (maxHz, oled) = readDisplay()
        if (maxHz != device.maxRefreshHz || oled != device.likelyOled) {
            device = device.copy(maxRefreshHz = maxHz, likelyOled = oled)
            recompute()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        memoryPressure = 2
        recompute()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(default)
}
