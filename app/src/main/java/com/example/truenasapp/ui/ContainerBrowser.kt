package com.example.truenasapp.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.data.PinningTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * What the in-app browsers may open: your NAS and the web pages of the apps (containers)
 * running on it. Everything else, the regular internet included, is blocked.
 */
object ContainerAccess {
    /** "host:port" of each app's web page, mapped to the app's name (for the title bar). */
    private val portals = ConcurrentHashMap<String, String>()
    private val portalHosts = ConcurrentHashMap.newKeySet<String>()

    fun rememberPortals(appName: String, urls: List<String>) {
        urls.forEach { raw ->
            val u = Uri.parse(raw)
            val host = u.host?.lowercase() ?: return@forEach
            portalHosts += host
            portals["$host:${port(u)}"] = appName
        }
    }

    private fun nas(): Uri? = Graph.store.serverUrl?.let(Uri::parse)

    private fun port(u: Uri): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("https", true) -> 443
        else -> 80
    }

    /** True for your NAS (any port, so every app on it) and the addresses of your apps. */
    fun isAllowed(url: String?): Boolean {
        val u = url?.let(Uri::parse) ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host?.lowercase() ?: return false
        return host == nas()?.host?.lowercase() || host in portalHosts
    }

    /** True for the TrueNAS web interface itself (same address and port you signed in with). */
    fun isNasUi(url: String?): Boolean {
        val u = url?.let(Uri::parse) ?: return false
        val n = nas() ?: return false
        return u.host.equals(n.host, true) && port(u) == port(n)
    }

    /** True once we know which app this address belongs to (its name for the title). */
    fun knowsApp(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host?.lowercase() ?: return false
        return portals.containsKey("$host:${port(u)}")
    }

    /** The app name for a page, or its address when we don't know it. */
    fun titleFor(url: String): String {
        val u = Uri.parse(url)
        val host = u.host?.lowercase() ?: return url
        return portals["$host:${port(u)}"] ?: "$host:${port(u)}"
    }

    // Certificates you chose to trust for app pages (self-signed ones), host + fingerprint.
    internal val trustedCerts = ConcurrentHashMap.newKeySet<String>()
}

/** Opens an app's web page inside TrueNAS Mobile instead of the phone's browser. */
object AppWebController {
    data class Page(val title: String, val url: String)

    internal val page = MutableStateFlow<Page?>(null)

    fun open(title: String, url: String) {
        page.value = Page(title, url)
    }

    fun close() {
        page.value = null
    }
}

internal fun blockedToast(context: Context) = Toast.makeText(
    context.applicationContext,
    "Blocked: the in-app browser only opens your NAS and its apps",
    Toast.LENGTH_LONG,
).show()

/**
 * Saves a file a web page offers for download into the phone's Downloads folder.
 * Only from your NAS or its apps. Uses the app's own connection, so self-signed
 * certificates you trusted work too.
 */
internal fun downloadInApp(context: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
    val app = context.applicationContext
    if (!ContainerAccess.isAllowed(url)) {
        blockedToast(app)
        return
    }
    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
    Toast.makeText(app, "Downloading $name…", Toast.LENGTH_SHORT).show()
    Graph.appScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply {
                    CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) }
                    userAgent?.let { header("User-Agent", it) }
                }.build()
                // The NAS connection trusts the certificate you approved at sign-in. App pages on
                // plain http (the usual case) go through the same client without any certificate.
                Graph.client.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty download")
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        mimeType?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("couldn't create the file")
                    try {
                        resolver.openOutputStream(uri)!!.use { out -> body.byteStream().copyTo(out) }
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }
            }
        }
        val msg = if (result.isSuccess) "Saved $name to Downloads" else "Couldn't download $name (${result.exceptionOrNull()?.message})"
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }
}

/** Put once near the top of the UI, above Expert mode, so app pages open over everything. */
@Composable
fun AppWebHost() {
    val page by AppWebController.page.collectAsStateWithLifecycle()
    page?.let { p -> key(p) { AppWebScreen(p, onClose = { AppWebController.close() }) } }
}

private class SslAsk(val host: String, val key: String, val handler: SslErrorHandler)
private class AuthAsk(val host: String, val realm: String, val handler: HttpAuthHandler)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AppWebScreen(page: AppWebController.Page, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(page.url) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var sslAsk by remember { mutableStateOf<SslAsk?>(null) }
    var authAsk by remember { mutableStateOf<AuthAsk?>(null) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }

    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    BackHandler {
        val fs = fullscreen
        val w = webView
        when {
            fs != null -> fs.second.onCustomViewHidden()
            w != null && w.canGoBack() -> w.goBack()
            else -> onClose()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(page.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Uri.parse(currentUrl).let { "${it.host ?: ""}${if (it.port != -1) ":${it.port}" else ""}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { webView?.goBack() }, enabled = canBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { webView?.goForward() }, enabled = canForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
            }
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false) // "new tab" links open here
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

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

                                // Full-screen video (Jellyfin, Plex, ...).
                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    if (view != null && callback != null) fullscreen = view to callback
                                }

                                override fun onHideCustomView() {
                                    fullscreen = null
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return true
                                    if (ContainerAccess.isAllowed(url)) return false
                                    if (request.isForMainFrame) blockedToast(ctx)
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    // Backstop for anything that slipped past the check above.
                                    if (url != null && url != "about:blank" && !ContainerAccess.isAllowed(url)) {
                                        view?.stopLoading()
                                        blockedToast(ctx)
                                        if (view?.canGoBack() == true) view.goBack()
                                        return
                                    }
                                    url?.let { currentUrl = it }
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    url?.let { currentUrl = it }
                                    canBack = view?.canGoBack() == true
                                    canForward = view?.canGoForward() == true
                                }

                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler ?: return
                                    val host = Uri.parse(error?.url ?: "").host ?: ""
                                    val cert = error?.certificate?.x509Certificate
                                    val fp = cert?.let { PinningTrustManager.fingerprint(it) }
                                    val key = "$host|$fp"
                                    when {
                                        !ContainerAccess.isAllowed(error?.url) -> handler.cancel()
                                        fp != null && fp == Graph.store.certPin -> handler.proceed()
                                        key in ContainerAccess.trustedCerts -> handler.proceed()
                                        fp == null -> handler.cancel()
                                        else -> sslAsk = SslAsk(host, key, handler)
                                    }
                                }

                                override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                                    if (handler != null) authAsk = AuthAsk(host ?: "", realm ?: "", handler)
                                }
                            }
                            setDownloadListener { url, ua, disposition, mime, _ ->
                                if (url.startsWith("blob:") || url.startsWith("data:")) {
                                    Toast.makeText(ctx, "This download isn't supported in the app", Toast.LENGTH_LONG).show()
                                } else {
                                    downloadInApp(ctx, url, ua, disposition, mime)
                                }
                            }
                            if (ContainerAccess.isAllowed(page.url)) loadUrl(page.url) else blockedToast(ctx)
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )

                fullscreen?.let { (view, _) ->
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }

    sslAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { ask.handler.cancel(); sslAsk = null },
            title = { Text("Untrusted certificate") },
            text = {
                Text(
                    "${ask.host} uses a certificate your phone doesn't trust (often a self-signed one). " +
                        "Continue only if this is your own app."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ContainerAccess.trustedCerts += ask.key
                    ask.handler.proceed()
                    sslAsk = null
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { ask.handler.cancel(); sslAsk = null }) { Text("Cancel") } },
        )
    }

    authAsk?.let { ask ->
        var user by remember(ask) { mutableStateOf("") }
        var pass by remember(ask) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ask.handler.cancel(); authAsk = null },
            title = { Text("Sign in to ${ask.host}") },
            text = {
                Column {
                    if (ask.realm.isNotBlank()) Text(ask.realm, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { ask.handler.proceed(user, pass); authAsk = null }) { Text("Sign in") } },
            dismissButton = { TextButton(onClick = { ask.handler.cancel(); authAsk = null }) { Text("Cancel") } },
        )
    }
}
