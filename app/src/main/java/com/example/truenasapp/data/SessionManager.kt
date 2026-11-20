package com.example.truenasapp.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.truenasapp.perf.PerformanceManager
import org.json.JSONObject

sealed interface LoginResult {
    data object Success : LoginResult
    data object NeedsOtp : LoginResult
    data class Error(val message: String) : LoginResult
}

/**
 * How the app stays signed in:
 *  1. You sign in once with username + password.
 *  2. Over https, the app asks TrueNAS to create an API key just for this phone
 *     (it shows up under Credentials → API Keys) and stores it encrypted. The password
 *     is thrown away.
 *  3. Every reconnect logs in silently with that key, so there is no session to expire.
 *
 * Over plain http TrueNAS revokes any API key that's used, so there the app stores the
 * password instead (encrypted with the Android Keystore) and logs in with it on every
 * reconnect. Both ways keep you signed in until you log out.
 */
class SessionManager(
    private val store: SecureStore,
    private val client: TrueNasClient,
    private val perf: PerformanceManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _loggedIn = MutableStateFlow(store.hasCredentials())
    val loggedIn: StateFlow<Boolean> = _loggedIn

    private val _loginMessage = MutableStateFlow<String?>(null)
    val loginMessage: StateFlow<String?> = _loginMessage

    /** Called whenever the session ends (logout or rejected credentials). */
    var onSessionEnded: (() -> Unit)? = null

    private var pauseJob: Job? = null
    private var pendingPassword: String? = null

    init {
        client.authenticator = { authenticateStored() }
        client.onCertChanged = {
            val oldKeyId = store.apiKeyId
            store.clearSession()
            store.apiKeyId = oldKeyId // so the old key gets cleaned up on the next sign-in
            _loginMessage.value =
                "Your NAS is using a new certificate (this happens when it's renewed). Sign in again to trust it."
            _loggedIn.value = false
            onSessionEnded?.invoke()
        }
        client.onAuthFailed = {
            store.clearSession()
            _loginMessage.value =
                "Your NAS no longer accepts this phone's saved login (the API key may have been deleted). Please sign in again."
            _loggedIn.value = false
            onSessionEnded?.invoke()
        }
    }

    // ---------------------------------------------------------------- app lifecycle

    fun resumeIfLoggedIn() {
        if (_loggedIn.value) client.start()
    }

    fun onAppForeground() {
        pauseJob?.cancel()
        if (_loggedIn.value) {
            client.start()
            client.checkHealth()
        }
    }

    fun onNetworkChanged() {
        if (_loggedIn.value) client.checkHealth()
    }

    /**
     * Close the socket a while after leaving the app to save battery (5 min at full power,
     * 30 s in battery saver, or whatever you picked in Settings). Reconnects silently on return.
     */
    fun onAppBackground() {
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(perf.state.value.backgroundKeepMs)
            if (_loggedIn.value) client.stop()
        }
    }

    // ---------------------------------------------------------------- login

    suspend fun login(
        server: String,
        user: String,
        password: String,
        otp: String?,
    ): LoginResult = withContext(Dispatchers.IO) {
        try {
            val url = TrueNasClient.normalizeServer(server)
            val usedPassword: String

            if (otp.isNullOrBlank() || pendingPassword == null) {
                // Signing in = trusting whatever certificate the NAS has right now.
                store.certPin = null
                store.serverUrl = url
                client.openForLogin()

                when (loginEx(mapOf("mechanism" to "PASSWORD_PLAIN", "username" to user, "password" to password))) {
                    "SUCCESS" -> Unit
                    "OTP_REQUIRED" -> {
                        pendingPassword = password
                        return@withContext LoginResult.NeedsOtp
                    }
                    else -> {
                        client.stop()
                        return@withContext LoginResult.Error("Wrong username or password.")
                    }
                }
                usedPassword = password
            } else {
                usedPassword = pendingPassword!!
                if (loginEx(mapOf("mechanism" to "OTP_TOKEN", "otp_token" to otp.trim())) != "SUCCESS") {
                    pendingPassword = null
                    client.stop()
                    return@withContext LoginResult.Error("That code didn't work. Please sign in again.")
                }
            }
            pendingPassword = null
            store.username = user

            val oldKeyId = store.apiKeyId
            // (store.serverUrl may have switched to https if the NAS redirects http -> https)
            val created = if (store.serverUrl?.startsWith("https://") == true) createApiKey(user) else null
            if (oldKeyId > 0 && created?.second != oldKeyId) {
                runCatching { client.callNow("api_key.delete", listOf(oldKeyId), 5_000) }
            }
            if (created != null) {
                store.apiKey = created.first
                store.apiKeyId = created.second
                store.password = null
            } else {
                store.apiKey = null
                store.apiKeyId = -1
                store.password = usedPassword
            }

            _loginMessage.value = null
            _loggedIn.value = true
            client.startWithCurrentSocket() // keep the socket we just signed in on
            LoginResult.Success
        } catch (e: Exception) {
            pendingPassword = null
            client.stop()
            LoginResult.Error(friendlyError(e))
        }
    }

    suspend fun logout() {
        val keyId = store.apiKeyId
        if (keyId > 0) {
            // Best effort: remove this phone's API key from the NAS.
            withTimeoutOrNull(5_000) { runCatching { client.call("api_key.delete", listOf(keyId), 5_000) } }
        }
        client.stop()
        store.clearSession()
        _loggedIn.value = false
        onSessionEnded?.invoke()
    }

    // ---------------------------------------------------------------- auth helpers

    /** Runs on every (re)connect. */
    private suspend fun authenticateStored() {
        val user = store.username ?: throw AuthFailedException()
        val key = store.apiKey
        val pass = store.password
        val ok = when {
            key != null -> loginWithApiKey(user, key)
            pass != null -> loginEx(
                mapOf("mechanism" to "PASSWORD_PLAIN", "username" to user, "password" to pass)
            ) == "SUCCESS"
            else -> false
        }
        if (!ok) throw AuthFailedException()
    }

    private suspend fun loginEx(data: Map<String, Any?>): String {
        val result = client.callNow("auth.login_ex", listOf(data)) as? JSONObject
        return result?.optString("response_type") ?: ""
    }

    private suspend fun loginWithApiKey(user: String, key: String): Boolean = try {
        loginEx(mapOf("mechanism" to "API_KEY_PLAIN", "username" to user, "api_key" to key)) == "SUCCESS"
    } catch (e: TrueNasException) {
        // Fallback for builds without auth.login_ex
        client.callNow("auth.login_with_api_key", listOf(key)) == true
    }

    private suspend fun createApiKey(user: String): Pair<String, Int>? = try {
        val name = "TrueNAS Mobile - ${Build.MANUFACTURER} ${Build.MODEL} - ${System.currentTimeMillis() / 1000}"
        val r = client.callNow("api_key.create", listOf(mapOf("name" to name, "username" to user))) as JSONObject
        r.getString("key") to r.optInt("id", -1)
    } catch (e: Exception) {
        null // e.g. the account isn't allowed to make keys; fall back to the encrypted password
    }
}
