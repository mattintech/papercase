package com.mattintech.securepass.data

import android.content.Context
import android.content.SharedPreferences
import com.mattintech.securepass.crypto.KeystoreHelper

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "secure_pass_preferences"
        private const val KEY_CIPHERTEXT = "encrypted_ciphertext"
        private const val KEY_IV = "encryption_iv"
        private const val KEY_HAS_PASSPHRASE = "has_passphrase"
        private const val KEY_AUTO_COPY = "auto_copy_on_open"
        private const val KEY_AUTO_MINIMIZE = "auto_minimize_after_copy"
        private const val KEY_AUTO_CLEAR = "auto_clear_clipboard"
        private const val KEY_CLEAR_TIMEOUT_SECONDS = "clear_timeout_seconds"
        private const val KEY_LAST_COPIED_TIME = "last_copied_time"
    }

    var isAutoCopyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COPY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COPY, value).apply()

    var isAutoMinimizeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MINIMIZE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MINIMIZE, value).apply()

    var isAutoClearEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR, value).apply()

    var clearTimeoutSeconds: Int
        get() = prefs.getInt(KEY_CLEAR_TIMEOUT_SECONDS, 10)
        set(value) = prefs.edit().putInt(KEY_CLEAR_TIMEOUT_SECONDS, value).apply()

    val hasStoredPassphrase: Boolean
        get() = prefs.getBoolean(KEY_HAS_PASSPHRASE, false) &&
                prefs.getString(KEY_CIPHERTEXT, null) != null &&
                prefs.getString(KEY_IV, null) != null

    var lastCopiedTimestamp: Long
        get() = prefs.getLong(KEY_LAST_COPIED_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_COPIED_TIME, value).apply()

    fun saveEncryptedPassphrase(payload: KeystoreHelper.EncryptedPayload) {
        prefs.edit()
            .putString(KEY_CIPHERTEXT, payload.ciphertextBase64)
            .putString(KEY_IV, payload.ivBase64)
            .putBoolean(KEY_HAS_PASSPHRASE, true)
            .apply()
    }

    fun getEncryptedPayload(): KeystoreHelper.EncryptedPayload? {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        return if (ciphertext != null && iv != null) {
            KeystoreHelper.EncryptedPayload(ciphertext, iv)
        } else {
            null
        }
    }

    fun clearStoredPassphrase() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .putBoolean(KEY_HAS_PASSPHRASE, false)
            .apply()
        KeystoreHelper.deleteKey()
    }
}
