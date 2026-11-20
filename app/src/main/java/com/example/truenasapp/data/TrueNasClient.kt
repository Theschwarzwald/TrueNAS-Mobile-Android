package com.example.truenasapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.example.truenasapp.perf.PerformanceManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TrueNasException(message: String, val errname: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

class AuthFailedException : Exception("Authentication failed")

enum class ConnState { OFFLINE, CONNECTING, ONLINE }

/**
 * Talks to TrueNAS 25.04+ using its JSON-RPC 2.0 WebSocket API (wss://host/api/current).
 *
 * The key idea for "never logs me out": the connection runs in a loop. If Wi-Fi drops,
 * the NAS reboots or the phone sleeps, the socket is reopened and [authenticator] logs in
 * again with the saved API key — the user never sees a login screen. The only time the
 * loop gives up is when the NAS actually rejects the saved credentials ([onAuthFailed]).
 */
class TrueNasClient(private val store: SecureStore, private val perf: PerformanceManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trustManager = PinningTrustManager(store)

    /** Shared HTTP client (also used for file downloads). */
    val http: OkHttpClient = run {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { host, session ->
                defaultVerifier.verify(host, session) || trustManager.isPinned(session)
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Pings keep NAT/firewalls from silently dropping the socket and notice dead connections.
    // Every 20 s at full power, less often when saving battery.
    private fun wsClient() = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(perf.state.value.pingSeconds, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    /** Live "collection_update" events from subscriptions (e.g. reporting.realtime). */
    val events: SharedFlow<JSONObject> = _events

    /** Logs in on a freshly opened socket. Set by SessionManager. */
    var authenticator: (suspend () -> Unit)? = null
    /** Called when the NAS refuses the saved credentials. */
    var onAuthFailed: (() -> Unit)? = null
    /** Called when an https NAS starts presenting a different certificate than the one we trusted. */
    var onCertChanged: (() -> Unit)? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val subscriptions = ConcurrentHashMap<String, String>()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketClosed: CompletableDeferred<Unit>? = null
    private var loopJob: Job? = null
    /** True right after login: reuse that already-signed-in socket instead of opening a new one. */
    @Volatile private var adoptSocket = false
    private var healthJob: Job? = null

    // ------------------------------------------------------------------ lifecycle

    /** Keep a logged-in connection open, reconnecting forever until [stop] is called. */
    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) {
            wake(); return
        }
        loopJob = scope.launch { connectionLoop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        closeSocket()
        _state.value = ConnState.OFFLINE
    }

    /** Start the loop using the socket the login screen just signed in on (saves a second login). */
    @Synchronized
    fun startWithCurrentSocket() {
        adoptSocket = socket != null
        start()
    }

    /** Skip the back-off wait and retry now (network came back, app opened...). */
    fun wake() {
        wakeSignal.trySend(Unit)
    }

    /**
     * After the phone sleeps or switches networks (Wi-Fi <-> mobile <-> VPN), the old socket
     * often looks open but is actually dead, and every request would hang until it timed out.
     * A quick ping finds out in ~3 s and reconnects straight away.
     */
    fun checkHealth() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            if (_state.value == ConnState.ONLINE) {
                val alive = runCatching { callNow("core.ping", timeoutMs = 3_000) }.isSuccess
                if (!alive) closeSocket() // loop notices and reconnects immediately
            } else {
                wake()
            }
        }
    }

    private suspend fun connectionLoop() {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var wasOnline = false
            try {
                if (adoptSocket && socket != null) {
                    adoptSocket = false
                } else {
                    adoptSocket = false
                    _state.value = ConnState.CONNECTING
                    openSocket()
                    authenticator?.invoke()
                }
                _state.value = ConnState.ONLINE
                wasOnline = true
                backoff = 1_000L
                resubscribeAll()
                socketClosed?.await() // sit here until the connection drops
            } catch (e: AuthFailedException) {
                closeSocket()
                _state.value = ConnState.OFFLINE
                onAuthFailed?.invoke()
                return
            } catch (e: TimeoutCancellationException) {
                // treat like a network problem, retry below
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("CERT_CHANGED") == true }) {
                    closeSocket()
                    _state.value = ConnState.OFFLINE
                    onCertChanged?.invoke()
                    return
                }
                // otherwise a network problem, retry below
            }
            closeSocket()
            _state.value = ConnState.OFFLINE
            if (wasOnline) {
                // A working connection just dropped: retry almost immediately instead of backing off.
                withTimeoutOrNull(300) { wakeSignal.receive() }
                continue
            }
            withTimeoutOrNull(backoff) { wakeSignal.receive() }
            backoff = (backoff * 2).coerceAtMost(perf.state.value.maxBackoffMs)
        }
    }

    /** Opens a raw (not yet logged-in) socket. Used by the login screen. */
    suspend fun openForLogin() {
        stop()
        openSocket()
    }

    private suspend fun openSocket() {
        redirectTarget = null
        try {
            openSocketOnce()
        } catch (e: TrueNasException) {
            // NAS has "HTTP -> HTTPS redirect" on: switch to https automatically instead of failing forever.
            val target = redirectTarget ?: throw e
            redirectTarget = null
            store.serverUrl = target
            openSocketOnce()
        }
    }

    @Volatile private var redirectTarget: String? = null

    private suspend fun openSocketOnce() {
        closeSocket()
        val server = store.serverUrl ?: throw TrueNasException("No server configured")
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        socketClosed = closed

        val ws = wsClient().newWebSocket(
            Request.Builder().url(wsUrl(server)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    onDisconnected(webSocket, opened, closed, null)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val location = response?.header("Location")
                    if (code in 300..399 && location != null && location.startsWith("https://")) {
                        redirectTarget = "https://" + Uri.parse(location).encodedAuthority
                    } else if (code == 404) {
                        opened.completeExceptionally(
                            TrueNasException("This NAS doesn't have the new API. The app needs TrueNAS 25.04 or newer.")
                        )
                    }
                    onDisconnected(webSocket, opened, closed, t)
                }
            }
        )
        socket = ws
        try {
            withTimeout(12_000) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            closeSocket()
            throw TrueNasException("Couldn't reach your NAS (timed out). Check the address, Wi-Fi or VPN.")
        }
    }

    private fun onDisconnected(
        ws: WebSocket,
        opened: CompletableDeferred<Unit>,
        closed: CompletableDeferred<Unit>,
        t: Throwable?,
    ) {
        opened.completeExceptionally(TrueNasException(t?.message ?: "Connection closed", cause = t))
        closed.complete(Unit)
        if (socket === ws) {
            socket = null
            failPending(TrueNasException("Connection lost", cause = t))
        }
    }

    fun closeSocket() {
        val ws = socket
        socket = null
        ws?.cancel()
        failPending(TrueNasException("Disconnected"))
        socketClosed?.complete(Unit)
    }

    private fun failPending(e: Exception) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(e) }
    }

    // ------------------------------------------------------------------ messages

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }

        if (msg.has("id") && !msg.isNull("id")) {
            val waiter = pending.remove(msg.optInt("id")) ?: return
            val error = msg.optJSONObject("error")
            if (error != null) {
                val data = error.optJSONObject("data")
                val reason = data?.optString("reason").takeUnless { it.isNullOrBlank() }
                    ?: error.optString("message", "Unknown error")
                waiter.completeExceptionally(TrueNasException(reason.trim(), data?.optString("errname")))
            } else {
                val result = msg.opt("result")
                waiter.complete(if (result == JSONObject.NULL) null else result)
            }
        } else if (msg.optString("method") == "collection_update") {
            msg.optJSONObject("params")?.let { _events.tryEmit(it) }
        }
    }

    /** Send a call on the socket that is open right now (no waiting for login). */
    suspend fun callNow(method: String, params: List<Any?> = emptyList(), timeoutMs: Long = 30_000): Any? {
        val ws = socket ?: throw TrueNasException("Not connected")
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<Any?>()
        pending[id] = result

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", toJson(params))
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw TrueNasException("Connection lost")
        }
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw TrueNasException("Your NAS took too long to answer")
        } finally {
            pending.remove(id)
        }
    }

    /** Normal API call: waits for the connection to be (re)established first. */
    suspend fun call(method: String, params: List<Any?> = emptyList(), timeoutMs: Long = 30_000): Any? {
        if (_state.value != ConnState.ONLINE) {
            start()
            try {
                withTimeout(timeoutMs) { state.first { it == ConnState.ONLINE } }
            } catch (e: TimeoutCancellationException) {
                throw TrueNasException("Can't reach your NAS right now")
            }
        }
        return callNow(method, params, timeoutMs)
    }

    // ------------------------------------------------------------------ subscriptions

    fun subscribe(collection: String) {
        subscriptions[collection] = ""
        if (_state.value == ConnState.ONLINE) scope.launch { subscribeNow(collection) }
    }

    fun unsubscribe(collection: String) {
        val id = subscriptions.remove(collection)
        if (!id.isNullOrEmpty() && _state.value == ConnState.ONLINE) {
            scope.launch { runCatching { callNow("core.unsubscribe", listOf(id)) } }
        }
    }

    private suspend fun subscribeNow(collection: String) {
        val id = runCatching { callNow("core.subscribe", listOf(collection)) }.getOrNull() ?: return
        if (subscriptions.containsKey(collection)) subscriptions[collection] = id.toString()
    }

    private suspend fun resubscribeAll() {
        subscriptions.keys.toList().forEach { subscribeNow(it) }
    }

    // ------------------------------------------------------------------ helpers

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        else -> value
    }

    companion object {
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            // No scheme typed (e.g. "192.168.1.10")? Use plain http.
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }

        fun wsUrl(server: String): String =
            server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/current"
    }
}

/**
 * No certificate setup needed. Over https the app accepts whatever certificate the NAS
 * has (self-signed, expired, made for another name — all fine) and remembers it when you
 * sign in. If a *different* certificate ever shows up, the app asks you to sign in again
 * rather than silently trusting it.
 */
class PinningTrustManager(private val store: SecureStore) : X509TrustManager {

    private val system: X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val fp = fingerprint(chain[0])
            when (store.certPin) {
                null -> store.certPin = fp
                fp -> Unit
                else -> throw CertificateException("CERT_CHANGED")
            }
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun isPinned(session: SSLSession): Boolean = runCatching {
        fingerprint(session.peerCertificates[0] as X509Certificate) == store.certPin
    }.getOrDefault(false)

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}

/** Turns low-level errors into something a person can act on. */
fun friendlyError(e: Throwable?): String {
    val chain = generateSequence(e) { it.cause }.toList()
    return when {
        chain.any { it.message?.contains("CERT_CHANGED") == true } ->
            "Your NAS's certificate changed. Sign in again to trust the new one."
        chain.any { it is SSLHandshakeException || it is CertificateException } ->
            "Couldn't set up a secure connection. Try the http:// address instead."
        chain.any { it is UnknownHostException } -> "Couldn't find that server address."
        chain.any { it is ConnectException || it is SocketTimeoutException } ->
            "Couldn't reach your NAS. Check the address and that you're on the right network (or VPN)."
        else -> e?.message ?: "Something went wrong"
    }
}
