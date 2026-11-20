package com.example.truenasapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.truenasapp.perf.BackgroundMode
import com.example.truenasapp.perf.OledMode
import com.example.truenasapp.perf.PerfMode
import com.example.truenasapp.perf.RefreshMode
import com.example.truenasapp.perf.ThemeMode
import kotlin.math.roundToInt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.data.ConnState
import com.example.truenasapp.lock.LockTimeouts
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val activity = LocalContext.current as FragmentActivity
    val lock = Graph.lock
    val store = Graph.store
    val scope = rememberCoroutineScope()
    val conn by Graph.client.state.collectAsStateWithLifecycle()

    var lockEnabled by remember { mutableStateOf(lock.isEnabled) }
    var timeout by remember { mutableLongStateOf(lock.timeoutMs) }
    var bio by remember { mutableStateOf(lock.biometricEnabled) }
    val bioAvailable = remember { biometricAvailable(activity) }

    var showPinDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
            .readableWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) { ScreenTitle("Settings") }

        Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Connection") }
        ListItem(
            headlineContent = { Text(store.serverUrl ?: "") },
            supportingContent = {
                val status = when (conn) {
                    ConnState.ONLINE -> "Connected"
                    ConnState.CONNECTING -> "Connecting…"
                    ConnState.OFFLINE -> "Offline (reconnects automatically)"
                }
                val method = if (store.apiKey != null) "API key" else "saved password"
                Text("Signed in as ${store.username} with $method · $status")
            },
        )
        HorizontalDivider()

        Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Security") }
        ListItem(
            headlineContent = { Text("App lock") },
            supportingContent = { Text("Ask for a passcode when opening the app. Turning this off also removes the Expert mode passcode.") },
            trailingContent = {
                Switch(checked = lockEnabled, onCheckedChange = { on ->
                    if (on) showPinDialog = true
                    else {
                        lock.disable()
                        lockEnabled = false
                        bio = false
                    }
                })
            },
        )
        if (lockEnabled) {
            ListItem(
                headlineContent = { Text("Lock after") },
                supportingContent = { Text(LockTimeouts.firstOrNull { it.first == timeout }?.second ?: "") },
                modifier = Modifier.clickable { showTimeoutDialog = true },
            )
            ListItem(
                headlineContent = { Text("Unlock with biometrics") },
                supportingContent = {
                    Text(if (bioAvailable) "Fingerprint or face unlock" else "No fingerprint or face unlock set up on this phone")
                },
                trailingContent = {
                    Switch(checked = bio, enabled = bioAvailable, onCheckedChange = { on ->
                        if (on) {
                            // Confirm the biometric works before turning it on.
                            showBiometricPrompt(activity, "Enable biometric unlock") {
                                lock.biometricEnabled = true
                                bio = true
                            }
                        } else {
                            lock.biometricEnabled = false
                            bio = false
                        }
                    })
                },
            )
            ListItem(
                headlineContent = { Text("Change passcode") },
                modifier = Modifier.clickable { showPinDialog = true },
            )
        }
        HorizontalDivider()

        PerformanceSection()
        HorizontalDivider()

        Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Account") }
        ListItem(
            headlineContent = { Text("Log out", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("Removes the saved login and this phone's API key from the NAS") },
            modifier = Modifier.clickable { confirmLogout = true },
        )
        HorizontalDivider()
        ExpertButton(Modifier.padding(16.dp))
    }

    if (showPinDialog) {
        SetPinDialog(
            onDismiss = { showPinDialog = false },
            onSet = { pin ->
                showPinDialog = false
                scope.launch {
                    lock.enable(pin)
                    lockEnabled = true
                }
            },
        )
    }

    if (showTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            title = { Text("Lock after") },
            text = {
                Column {
                    LockTimeouts.forEach { (ms, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    lock.timeoutMs = ms
                                    timeout = ms
                                    showTimeoutDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = timeout == ms, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Close") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You'll need your TrueNAS username and password to sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    Graph.appScope.launch { Graph.session.logout() }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SetPinDialog(title: String = "Set a passcode", onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("4 to 12 digits.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = first,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) first = it },
                    label = { Text("Passcode") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) second = it },
                    label = { Text("Confirm passcode") }, singleLine = true, isError = mismatch,
                    supportingText = { if (mismatch) Text("Passcodes don't match") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(first) }, enabled = first.length >= 4 && first == second) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ performance & display

@Composable
private fun PerformanceSection() {
    val pm = Graph.perf
    val perf by pm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    val maxHz = perf.device.maxRefreshHz.roundToInt()
    val nowHz = perf.refreshHz.roundToInt()

    Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Performance & display") }

    // What the app is doing right now, and why.
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Running at ${perf.tier.label} · $nowHz Hz", fontWeight = FontWeight.SemiBold)
            Text(perf.reason, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(6.dp))
            Text(perf.device.summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ListItem(
        headlineContent = { Text("Performance") },
        supportingContent = {
            Text(
                if (perf.perfMode == PerfMode.AUTO) "Automatic: starts at maximum and eases off when the " +
                    "phone is hot, low on memory, struggling, or in Battery Saver"
                else perf.perfMode.label
            )
        },
        modifier = Modifier.clickable { dialog = "perf" },
    )
    if (maxHz > 61) {
        ListItem(
            headlineContent = { Text("Refresh rate") },
            supportingContent = {
                Text(
                    when (perf.refreshMode) {
                        RefreshMode.AUTO -> "Automatic: $maxHz Hz, 60 Hz in battery saver"
                        RefreshMode.HIGHEST -> "Always $maxHz Hz"
                        RefreshMode.STANDARD -> "Always 60 Hz"
                    }
                )
            },
            modifier = Modifier.clickable { dialog = "refresh" },
        )
    }
    ListItem(
        headlineContent = { Text("Theme") },
        supportingContent = { Text(perf.themeMode.label) },
        modifier = Modifier.clickable { dialog = "theme" },
    )
    ListItem(
        headlineContent = { Text("Pure black dark theme (OLED)") },
        supportingContent = {
            Text(
                when (perf.oledMode) {
                    OledMode.AUTO -> "Automatic: " + (if (perf.pureBlackWhenDark) "on" else "off") +
                        ". Used on screens that look like OLED and in battery saver"
                    else -> perf.oledMode.label
                }
            )
        },
        modifier = Modifier.clickable { dialog = "oled" },
    )
    ListItem(
        headlineContent = { Text("Stay connected after leaving the app") },
        supportingContent = {
            Text(
                if (perf.backgroundMode == BackgroundMode.AUTO)
                    "Automatic: ${formatDuration(perf.backgroundKeepMs)} right now (5 min at full power, 30 s in battery saver)"
                else perf.backgroundMode.label
            )
        },
        modifier = Modifier.clickable { dialog = "background" },
    )

    when (dialog) {
        "perf" -> OptionDialog("Performance", PerfMode.entries.map { it to it.label }, perf.perfMode,
            { pm.setPerfMode(it) }, { dialog = null })
        "refresh" -> OptionDialog(
            "Refresh rate",
            listOf(
                RefreshMode.AUTO to "Automatic ($maxHz Hz, 60 Hz in battery saver)",
                RefreshMode.HIGHEST to "Always $maxHz Hz",
                RefreshMode.STANDARD to "Always 60 Hz (saves battery)",
            ),
            perf.refreshMode, { pm.setRefreshMode(it) }, { dialog = null },
        )
        "theme" -> OptionDialog("Theme", ThemeMode.entries.map { it to it.label }, perf.themeMode,
            { pm.setThemeMode(it) }, { dialog = null })
        "oled" -> OptionDialog("Pure black dark theme", OledMode.entries.map { it to it.label }, perf.oledMode,
            { pm.setOledMode(it) }, { dialog = null })
        "background" -> OptionDialog("Stay connected", BackgroundMode.entries.map { it to it.label }, perf.backgroundMode,
            { pm.setBackgroundMode(it) }, { dialog = null })
    }
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatDuration(ms: Long): String =
    if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1000} s"
