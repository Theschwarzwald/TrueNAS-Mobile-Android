package com.example.truenasapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everything the app remembers between launches.
 *
 * Secrets (the API key, or the password when using plain http) are encrypted with an
 * AES key that lives inside the Android Keystore, so they can't be read by copying the
 * app's files off the phone.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("truenas_store", Context.MODE_PRIVATE)

    // ---------- connection ----------
    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(v) = prefs.edit().putString("username", v).apply()

    var allowSelfSigned: Boolean
        get() = prefs.getBoolean("allow_self_signed", false)
        set(v) = prefs.edit().putBoolean("allow_self_signed", v).apply()

    /** SHA-256 fingerprint of a self-signed certificate we trusted on first connect. */
    var certPin: String?
        get() = prefs.getString("cert_pin", null)
        set(v) = prefs.edit().putString("cert_pin", v).apply()

    // ---------- secrets ----------
    var apiKey: String?
        get() = getSecret("api_key")
        set(v) = putSecret("api_key", v)

    var apiKeyId: Int
        get() = prefs.getInt("api_key_id", -1)
        set(v) = prefs.edit().putInt("api_key_id", v).apply()

    var password: String?
        get() = getSecret("password")
        set(v) = putSecret("password", v)

    // ---------- app lock ----------
    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(v) = prefs.edit().putBoolean("lock_enabled", v).apply()

    var lockTimeoutMs: Long
        get() = prefs.getLong("lock_timeout_ms", 60_000L)
        set(v) = prefs.edit().putLong("lock_timeout_ms", v).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(v) = prefs.edit().putBoolean("biometric_enabled", v).apply()

    var pinHash: String?
        get() = prefs.getString("pin_hash", null)
        set(v) = prefs.edit().putString("pin_hash", v).apply()

    var pinSalt: String?
        get() = prefs.getString("pin_salt", null)
        set(v) = prefs.edit().putString("pin_salt", v).apply()

    var failedAttempts: Int
        get() = prefs.getInt("failed_attempts", 0)
        set(v) = prefs.edit().putInt("failed_attempts", v).apply()

    var lockoutUntil: Long
        get() = prefs.getLong("lockout_until", 0L)
        set(v) = prefs.edit().putLong("lockout_until", v).apply()

    // ---------- performance & display (enum names from perf/PerformanceManager.kt) ----------
    var perfMode: String?
        get() = prefs.getString("perf_mode", null)
        set(v) = prefs.edit().putString("perf_mode", v).apply()

    var refreshMode: String?
        get() = prefs.getString("refresh_mode", null)
        set(v) = prefs.edit().putString("refresh_mode", v).apply()

    var themeMode: String?
        get() = prefs.getString("theme_mode", null)
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    var oledMode: String?
        get() = prefs.getString("oled_mode", null)
        set(v) = prefs.edit().putString("oled_mode", v).apply()

    var backgroundMode: String?
        get() = prefs.getString("background_mode", null)
        set(v) = prefs.edit().putString("background_mode", v).apply()

    fun hasCredentials(): Boolean = username != null && (apiKey != null || password != null)

    /** Forget the login and the app-lock passcode, but keep the server address + username for next time. */
    fun clearSession() {
        prefs.edit()
            .remove("api_key").remove("api_key_id").remove("password")
            .remove("lock_enabled").remove("biometric_enabled")
            .remove("pin_hash").remove("pin_salt")
            .remove("failed_attempts").remove("lockout_until")
            .apply()
    }

    // ---------- encryption helpers ----------
    private val keyAlias = "truenas_app_secret_key"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun putSecret(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getSecret(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
