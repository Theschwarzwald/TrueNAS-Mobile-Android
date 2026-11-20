package com.example.truenasapp.lock

import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.truenasapp.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Lock-after choices shown in Settings (milliseconds to label). */
val LockTimeouts = listOf(
    0L to "Immediately",
    30_000L to "After 30 seconds",
    60_000L to "After 1 minute",
    5 * 60_000L to "After 5 minutes",
    15 * 60_000L to "After 15 minutes",
    30 * 60_000L to "After 30 minutes",
)

/**
 * Nextcloud-style app lock.
 *  - Locks when the app has been in the background longer than the chosen time.
 *  - Also locks if the app is left open on screen but untouched for that long.
 *  - Always locks on a fresh start.
 * The passcode is stored only as a salted PBKDF2 hash.
 */
class AppLockManager(private val store: SecureStore) : DefaultLifecycleObserver {

    sealed interface UnlockResult {
        data object Ok : UnlockResult
        data object Wrong : UnlockResult
        data class LockedOut(val seconds: Long) : UnlockResult
    }

    private val _locked = MutableStateFlow(store.lockEnabled)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt = 0L
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()

    val isEnabled: Boolean get() = store.lockEnabled

    /** A passcode exists (used by both the app lock and Expert mode). */
    val hasPin: Boolean get() = store.pinHash != null

    var timeoutMs: Long
        get() = store.lockTimeoutMs
        set(v) { store.lockTimeoutMs = v }

    var biometricEnabled: Boolean
        get() = store.biometricEnabled
        set(v) { store.biometricEnabled = v }

    // ---- process lifecycle (whole app went to background / came back) ----
    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (store.lockEnabled && backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt >= store.lockTimeoutMs
        ) {
            _locked.value = true
        }
        lastInteraction = SystemClock.elapsedRealtime()
    }

    // ---- idle while open on screen ----
    fun onUserInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    fun checkIdle() {
        val t = store.lockTimeoutMs
        if (store.lockEnabled && t > 0 && !_locked.value &&
            SystemClock.elapsedRealtime() - lastInteraction >= t
        ) {
            _locked.value = true
        }
    }

    // ---- setup ----
    suspend fun enable(pin: String) {
        setPin(pin)
        store.lockEnabled = true
    }

    fun disable() {
        store.lockEnabled = false
        store.biometricEnabled = false
        store.pinHash = null
        store.pinSalt = null
        _locked.value = false
    }

    /** Called after logout / rejected login. */
    fun reset() {
        _locked.value = false
    }

    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        store.pinHash = Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)
        store.failedAttempts = 0
        store.lockoutUntil = 0
    }

    // ---- unlocking ----
    fun lockoutRemainingSeconds(): Long =
        ((store.lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    /** Unlocks the app if the passcode is right. */
    suspend fun tryPin(pin: String): UnlockResult =
        checkPin(pin).also { if (it == UnlockResult.Ok) _locked.value = false }

    /** Checks the passcode (with the same too-many-attempts protection) without unlocking anything. */
    suspend fun checkPin(pin: String): UnlockResult = withContext(Dispatchers.Default) {
        val remaining = lockoutRemainingSeconds()
        if (remaining > 0) return@withContext UnlockResult.LockedOut(remaining)

        if (verify(pin)) {
            store.failedAttempts = 0
            return@withContext UnlockResult.Ok
        }
        val fails = store.failedAttempts + 1
        store.failedAttempts = fails
        if (fails >= 5) {
            val waitMs = 30_000L * (fails - 4) // 30s, 60s, 90s...
            store.lockoutUntil = System.currentTimeMillis() + waitMs
            UnlockResult.LockedOut(waitMs / 1000)
        } else {
            UnlockResult.Wrong
        }
    }

    fun unlockWithBiometrics() {
        store.failedAttempts = 0
        _locked.value = false
    }

    private fun verify(pin: String): Boolean {
        val salt = store.pinSalt?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = store.pinHash?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
