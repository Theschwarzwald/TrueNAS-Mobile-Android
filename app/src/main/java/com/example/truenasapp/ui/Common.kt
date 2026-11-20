package com.example.truenasapp.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.perf.ThemeMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

val TrueNasBlue = Color(0xFF0095D5)
val TrueNasGreen = Color(0xFF71BF44)

@Composable
fun TrueNasTheme(content: @Composable () -> Unit) {
    val perf by Graph.perf.state.collectAsStateWithLifecycle()
    val dark = when (perf.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val black = dark && perf.pureBlackWhenDark

    val scheme = when {
        // True black: OLED pixels switch off, which saves battery. Cards stay a touch lighter so they're visible.
        black -> darkColorScheme(
            primary = Color(0xFF4FC3F7), secondary = TrueNasGreen,
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHigh = Color(0xFF161616),
            surfaceContainerHighest = Color(0xFF1C1C1C),
            surfaceVariant = Color(0xFF1C1C1C),
        )
        dark -> darkColorScheme(primary = Color(0xFF4FC3F7), secondary = TrueNasGreen)
        else -> lightColorScheme(primary = TrueNasBlue, secondary = TrueNasGreen)
    }

    // Status/navigation bar icons follow the app's theme, not just the phone's.
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark, activity) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

fun formatUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    return if (d > 0) "${d}d ${h}h" else "${h}h ${m}m"
}

/** Network speed in bits per second, like the TrueNAS dashboard. */
fun formatRate(bytesPerSec: Double?): String {
    if (bytesPerSec == null) return "—"
    val bits = bytesPerSec * 8
    return when {
        bits >= 1e9 -> String.format(Locale.US, "%.2f Gb/s", bits / 1e9)
        bits >= 1e6 -> String.format(Locale.US, "%.1f Mb/s", bits / 1e6)
        else -> String.format(Locale.US, "%.0f kb/s", bits / 1e3)
    }
}

/** Lets a Row scroll sideways (used for the category chips). */
@Composable
fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
