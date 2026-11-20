package com.example.truenasapp

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.truenasapp.ui.AppRoot
import com.example.truenasapp.ui.LocalLayoutInfo
import com.example.truenasapp.ui.SharedLinks
import com.example.truenasapp.ui.TrueNasTheme
import com.example.truenasapp.ui.rememberLayoutInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

// FragmentActivity (not ComponentActivity) because the fingerprint/face prompt needs it.
class MainActivity : FragmentActivity() {

    @Volatile private var targetHz = 60f

    // Frame timing is measured on its own background thread so it never slows the UI.
    private val frameThread = HandlerThread("frame-metrics").apply { start() }
    private var frames = 0
    private var lateFrames = 0
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val budget = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE)
        else (1_000_000_000L / targetHz).toLong()
        frames++
        if (budget > 0 && total > budget * 3 / 2) lateFrames++
        if (frames >= 120) {
            Graph.perf.reportFrames(frames, lateFrames)
            frames = 0
            lateFrames = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) SharedLinks.accept(this, intent)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutInfo provides rememberLayoutInfo(this)) {
                TrueNasTheme { AppRoot() }
            }
        }

        // Follow the chosen refresh rate (e.g. 120 Hz normally, 60 Hz in battery saver).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.perf.state.map { it.refreshHz }.distinctUntilChanged().collect { hz ->
                    targetHz = hz
                    applyRefreshRate(hz)
                }
            }
        }

        // Lock the app if it's left open but untouched for the chosen time.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(5_000)
                    Graph.lock.checkIdle()
                }
            }
        }
    }

    // The app is already open (singleTask) and a page was shared to it.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedLinks.accept(this, intent)
    }

    override fun onResume() {
        super.onResume()
        window.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper))
    }

    override fun onPause() {
        window.removeOnFrameMetricsAvailableListener(frameListener)
        super.onPause()
    }

    override fun onDestroy() {
        frameThread.quitSafely()
        super.onDestroy()
    }

    // Folding/unfolding or resizing doesn't restart the app (see the manifest); just re-apply.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRefreshRate(targetHz)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Graph.lock.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    /** Picks the screen mode closest to [hz] at the current resolution. */
    private fun applyRefreshRate(hz: Float) {
        val d: Display = (if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay)
            ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { abs(it.refreshRate - hz) } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }
}
