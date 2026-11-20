package com.example.truenasapp.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.data.PinningTrustManager
import com.example.truenasapp.lock.AppLockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

const val MOBILE_WARNING = "This mode is not optimized for mobile"

/** Opens Expert mode (the full TrueNAS web interface) from anywhere in the app. */
object ExpertController {
    internal val requested = MutableStateFlow<String?>(null)
    internal val open = MutableStateFlow<String?>(null)

    /** [path] lets other screens jump straight to a page, e.g. an app's install form. */
    fun ask(path: String = "/ui/") {
        requested.value = path
    }

    fun close() {
        requested.value = null
        open.value = null
    }
}

/** The button shown at the bottom of Home and Settings. */
@Composable
fun ExpertButton(modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { ExpertController.ask() }, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AdminPanelSettings, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Expert mode")
    }
}

/** Put once near the top of the UI: shows the warning, the passcode check, then the web UI. */
@Composable
fun ExpertModeHost() {
    val requested by ExpertController.requested.collectAsStateWithLifecycle()
    val open by ExpertController.open.collectAsStateWithLifecycle()

    open?.let { path -> ExpertScreen(path, onClose = { ExpertController.close() }) }
    requested?.let { path ->
        ExpertGate(
            onCancel = { ExpertController.requested.value = null },
            onGranted = {
                ExpertController.requested.value = null
                ExpertController.open.value = path
            },
        )
    }
}

private enum class GateStage { Warning, CreatePin, Auth }

@Composable
private fun ExpertGate(onCancel: () -> Unit, onGranted: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val lock = Graph.lock
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(GateStage.Warning) }

    when (stage) {
        GateStage.Warning -> AlertDialog(
            onDismissRequest = onCancel,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Expert mode") },
            text = {
                Column {
                    Text(MOBILE_WARNING, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "It opens the full TrueNAS web interface with every setting. " +
                            "Changes you make there take effect immediately."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { stage = if (lock.hasPin) GateStage.Auth else GateStage.CreatePin }) {
                    Text("Continue")
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )

        GateStage.CreatePin -> SetPinDialog(
            title = "Create a passcode for Expert mode",
            onDismiss = onCancel,
            onSet = { pin -> scope.launch { lock.setPin(pin); onGranted() } },
        )

        GateStage.Auth -> PasscodeCheckDialog(activity, onCancel, onGranted)
    }
}

@Composable
private fun PasscodeCheckDialog(activity: FragmentActivity, onCancel: () -> Unit, onGranted: () -> Unit) {
    val lock = Graph.lock
    val scope = rememberCoroutineScope()
    val canUseBiometrics = remember { biometricAvailable(activity) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (canUseBiometrics) showBiometricPrompt(activity, "Open Expert mode") { onGranted() }
    }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true
        scope.launch {
            when (val r = lock.checkPin(pin)) {
                AppLockManager.UnlockResult.Ok -> onGranted()
                AppLockManager.UnlockResult.Wrong -> error = "Wrong passcode"
                is AppLockManager.UnlockResult.LockedOut -> error = "Too many attempts. Try again in ${r.seconds}s."
            }
            pin = ""
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Confirm it's you") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pin = it },
                    label = { Text("Passcode") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (canUseBiometrics) {
                    TextButton(onClick = { showBiometricPrompt(activity, "Open Expert mode") { onGranted() } }) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Use biometrics")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = pin.length >= 4 && !busy) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Full-screen TrueNAS web UI. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ExpertScreen(path: String, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(true) }

    // Lets "Upload" / "Choose file" buttons in the web UI open the phone's file picker.
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Surface(Modifier.fillMaxSize()) {
        // imePadding keeps text boxes visible above the keyboard.
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Leave Expert mode") }
                Text("Expert mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
            }
            if (showBanner) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        MOBILE_WARNING,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBanner = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Hide", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true // keeps you signed in to the web UI
                        settings.databaseEnabled = true

                        // Lay the page out like the phone's browser does: honour the page's
                        // "width=device-width" and start at 100% instead of a zoomed-out desktop view.
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                        settings.textZoom = 100
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true // pinch to zoom still works
                        settings.displayZoomControls = false

                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false) // "open in new tab" links load here
                        CookieManager.getInstance().setAcceptCookie(true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onShowFileChooser(
                                view: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?,
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                return try {
                                    fileLauncher.launch(params!!.createIntent())
                                    true
                                } catch (e: Exception) {
                                    fileCallback = null
                                    false
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {

                            // The TrueNAS pages stay here. An app's "Web UI" link opens in the in-app
                            // app viewer. Anything else (docs, forums, the internet) is blocked.
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return true
                                if (ContainerAccess.isNasUi(url)) return false
                                if (ContainerAccess.isAllowed(url)) {
                                    if (!request.isForMainFrame) return false
                                    AppWebController.open(ContainerAccess.titleFor(url), url)
                                    return true
                                }
                                if (request.isForMainFrame) blockedToast(ctx)
                                return true
                            }

                            // Same rule as the rest of the app: only the certificate you trusted at sign-in.
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                val cert = error?.certificate?.x509Certificate
                                val fp = cert?.let { PinningTrustManager.fingerprint(it) }
                                if (fp != null && fp == Graph.store.certPin) handler?.proceed() else handler?.cancel()
                            }
                        }
                        // Downloads from the web UI (config backups, logs) save straight to Downloads.
                        setDownloadListener { url, ua, disposition, mime, _ ->
                            downloadInApp(ctx, url, ua, disposition, mime)
                        }
                        loadUrl((Graph.store.serverUrl ?: "") + path)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
