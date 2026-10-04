package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalServiceSettings(
    val rutrackerLogin: String = "",
    val rutrackerPasswordSaved: Boolean = false,
    val torrServeUrl: String = "",
    val torrServeLogin: String = "",
    val torrServePasswordSaved: Boolean = false,
)

data class ServiceCredentials(
    val login: String = "",
    val password: String = "",
)

/**
 * Device-local credentials for external services.
 *
 * Passwords are encrypted with an Android Keystore AES key before they are
 * written to SharedPreferences. The preference file is excluded from both
 * cloud backup and device-transfer backup rules.
 */
class ExternalServiceCredentialsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _state = MutableStateFlow(readPublicState())
    val state: StateFlow<ExternalServiceSettings> = _state.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = readPublicState()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun saveRuTracker(
        login: String,
        newPassword: String?,
    ) {
        val editor = prefs.edit().putString(KEY_RUTRACKER_LOGIN, login.trim())
        if (newPassword != null) {
            if (newPassword.isBlank()) {
                editor.remove(KEY_RUTRACKER_PASSWORD)
            } else {
                editor.putString(KEY_RUTRACKER_PASSWORD, encrypt(newPassword))
            }
        }
        editor.apply()
        _state.value = readPublicState()
    }

    fun clearRuTrackerPassword() {
        prefs.edit().remove(KEY_RUTRACKER_PASSWORD).apply()
        _state.value = readPublicState()
    }

    fun ruTrackerCredentials(): ServiceCredentials = ServiceCredentials(
        login = prefs.getString(KEY_RUTRACKER_LOGIN, "").orEmpty().trim(),
        password = decrypt(prefs.getString(KEY_RUTRACKER_PASSWORD, null)).orEmpty(),
    )

    fun saveTorrServe(
        url: String,
        login: String,
        newPassword: String?,
    ) {
        val editor = prefs.edit()
            .putString(KEY_TORRSERVE_URL, normalizeServerUrl(url))
            .putString(KEY_TORRSERVE_LOGIN, login.trim())

        if (newPassword != null) {
            if (newPassword.isBlank()) {
                editor.remove(KEY_TORRSERVE_PASSWORD)
            } else {
                editor.putString(KEY_TORRSERVE_PASSWORD, encrypt(newPassword))
            }
        }
        editor.apply()
        _state.value = readPublicState()
    }

    fun clearTorrServePassword() {
        prefs.edit().remove(KEY_TORRSERVE_PASSWORD).apply()
        _state.value = readPublicState()
    }

    fun torrServeCredentials(): ServiceCredentials = ServiceCredentials(
        login = prefs.getString(KEY_TORRSERVE_LOGIN, "").orEmpty().trim(),
        password = decrypt(prefs.getString(KEY_TORRSERVE_PASSWORD, null)).orEmpty(),
    )

    fun torrServeUrl(): String =
        prefs.getString(KEY_TORRSERVE_URL, "").orEmpty().trim()

    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readPublicState(): ExternalServiceSettings = ExternalServiceSettings(
        rutrackerLogin = prefs.getString(KEY_RUTRACKER_LOGIN, "").orEmpty().trim(),
        rutrackerPasswordSaved = decrypt(prefs.getString(KEY_RUTRACKER_PASSWORD, null)) != null,
        torrServeUrl = prefs.getString(KEY_TORRSERVE_URL, "").orEmpty().trim(),
        torrServeLogin = prefs.getString(KEY_TORRSERVE_LOGIN, "").orEmpty().trim(),
        torrServePasswordSaved = decrypt(prefs.getString(KEY_TORRSERVE_PASSWORD, null)) != null,
    )

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(payload, Base64.NO_WRAP))
        }
    }

    private fun decrypt(stored: String?): String? {
        val value = stored?.trim().orEmpty()
        if (value.isBlank()) return null
        val separator = value.indexOf(':')
        if (separator <= 0 || separator >= value.lastIndex) return null
        return runCatching {
            val iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
            val payload = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS_NAME = "abred_external_service_credentials"

        private const val KEY_RUTRACKER_LOGIN = "rutracker_login"
        private const val KEY_RUTRACKER_PASSWORD = "rutracker_password"
        private const val KEY_TORRSERVE_URL = "torrserve_url"
        private const val KEY_TORRSERVE_LOGIN = "torrserve_login"
        private const val KEY_TORRSERVE_PASSWORD = "torrserve_password"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "abred_external_service_credentials_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

        internal fun normalizeServerUrl(raw: String): String {
            val clean = raw.trim().trimEnd('/')
            if (clean.isBlank()) return ""
            return if ("://" in clean) clean else "http://$clean"
        }
    }
}
