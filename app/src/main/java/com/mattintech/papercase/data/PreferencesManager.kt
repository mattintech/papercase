package com.mattintech.papercase.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.mattintech.papercase.crypto.KeystoreHelper
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "papercase_preferences"
        private const val KEY_CIPHERTEXT = "encrypted_ciphertext"
        private const val KEY_IV = "encryption_iv"
        private const val KEY_HAS_PASSPHRASE = "has_passphrase"
        private const val KEY_AUTO_COPY = "auto_copy_on_open"
        private const val KEY_AUTO_MINIMIZE = "auto_minimize_after_copy"
        private const val KEY_AUTO_CLEAR = "auto_clear_clipboard"
        private const val KEY_CLEAR_TIMEOUT_SECONDS = "clear_timeout_seconds"
        private const val KEY_LAST_COPIED_TIME = "last_copied_time"
        private const val KEY_LOCKER_TIMEOUT_MINUTES = "locker_timeout_minutes"
        private const val KEY_CUSTOM_AUTHORITIES = "custom_issuing_authorities"
        private const val KEY_EXPIRY_REMINDERS = "expiry_reminders_enabled"
        private const val KEY_EXPIRY_REMINDERS_ASKED = "expiry_reminders_asked"
        private const val KEY_LOCKER_LAYOUT = "locker_layout"
        private const val KEY_LOCKER_SORT = "locker_sort"
        const val KEY_THEME = "theme"
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

        const val NAME = PREF_NAME

        /** Settings that go into backups (and so mark the backup out of date when they change). */
        private val BACKED_UP_KEYS = setOf(
            KEY_AUTO_COPY, KEY_AUTO_MINIMIZE, KEY_AUTO_CLEAR, KEY_CLEAR_TIMEOUT_SECONDS,
            KEY_LOCKER_TIMEOUT_MINUTES, KEY_CUSTOM_AUTHORITIES, KEY_EXPIRY_REMINDERS,
            KEY_LOCKER_LAYOUT, KEY_LOCKER_SORT, KEY_THEME
        )

        fun isBackedUp(key: String?) = key in BACKED_UP_KEYS
    }

    /** Settings for a backup file. The stored passphrase is handled separately. */
    fun exportSettings(): JSONObject = JSONObject().apply {
        put(KEY_AUTO_COPY, isAutoCopyEnabled)
        put(KEY_AUTO_MINIMIZE, isAutoMinimizeEnabled)
        put(KEY_AUTO_CLEAR, isAutoClearEnabled)
        put(KEY_CLEAR_TIMEOUT_SECONDS, clearTimeoutSeconds)
        put(KEY_LOCKER_TIMEOUT_MINUTES, lockerTimeoutMinutes)
        put(KEY_EXPIRY_REMINDERS, isExpiryRemindersEnabled)
        put(KEY_CUSTOM_AUTHORITIES, JSONArray(getCustomAuthorities().sorted()))
        put(KEY_LOCKER_LAYOUT, lockerLayout.name)
        put(KEY_LOCKER_SORT, lockerSort.name)
        put(KEY_THEME, theme.name)
    }

    /**
     * Restores settings from a backup. Issuing authorities are always merged in; the other settings
     * are only taken when [replaceSettings] is set (restoring onto a new phone or replacing the locker),
     * so merging a backup doesn't undo choices made on this phone.
     */
    fun importSettings(json: JSONObject, replaceSettings: Boolean) {
        val authorities = json.optJSONArray(KEY_CUSTOM_AUTHORITIES)
        val merged = getCustomAuthorities().toMutableSet()
        for (i in 0 until (authorities?.length() ?: 0)) {
            authorities!!.optString(i).trim().takeIf { it.isNotEmpty() }?.let(merged::add)
        }
        val edit = prefs.edit().putStringSet(KEY_CUSTOM_AUTHORITIES, merged)
        if (replaceSettings) {
            if (json.has(KEY_AUTO_COPY)) edit.putBoolean(KEY_AUTO_COPY, json.optBoolean(KEY_AUTO_COPY))
            if (json.has(KEY_AUTO_MINIMIZE)) edit.putBoolean(KEY_AUTO_MINIMIZE, json.optBoolean(KEY_AUTO_MINIMIZE))
            if (json.has(KEY_AUTO_CLEAR)) edit.putBoolean(KEY_AUTO_CLEAR, json.optBoolean(KEY_AUTO_CLEAR))
            if (json.has(KEY_CLEAR_TIMEOUT_SECONDS)) edit.putInt(KEY_CLEAR_TIMEOUT_SECONDS, json.optInt(KEY_CLEAR_TIMEOUT_SECONDS))
            if (json.has(KEY_LOCKER_TIMEOUT_MINUTES)) edit.putInt(KEY_LOCKER_TIMEOUT_MINUTES, json.optInt(KEY_LOCKER_TIMEOUT_MINUTES))
            json.optString(KEY_LOCKER_LAYOUT).takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_LOCKER_LAYOUT, it) }
            json.optString(KEY_LOCKER_SORT).takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_LOCKER_SORT, it) }
            json.optString(KEY_THEME).takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_THEME, it) }
            if (json.has(KEY_EXPIRY_REMINDERS)) {
                edit.putBoolean(KEY_EXPIRY_REMINDERS, json.optBoolean(KEY_EXPIRY_REMINDERS))
                edit.putBoolean(KEY_EXPIRY_REMINDERS_ASKED, true)
            }
        }
        edit.apply()
    }

    /** Whether expiry reminder notifications are on (each document sets how early). */
    var isExpiryRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPIRY_REMINDERS, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPIRY_REMINDERS, value).putBoolean(KEY_EXPIRY_REMINDERS_ASKED, true).apply()

    /** Set once the user has been asked about expiry reminders, so we only ask once. */
    var hasAskedAboutExpiryReminders: Boolean
        get() = prefs.getBoolean(KEY_EXPIRY_REMINDERS_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPIRY_REMINDERS_ASKED, value).apply()

    enum class LockerLayout(val label: String) { LIST("List"), CARDS("Cards") }

    enum class LockerSort(val label: String) {
        ADDED("Date added"),
        MODIFIED("Last modified"),
        EXPIRY("Next to expire"),
        NAME("Name (A–Z)")
    }

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    /** Light, dark, or following the system setting; [mode] is the AppCompatDelegate night mode. */
    enum class Theme(val label: String, val mode: Int) {
        SYSTEM("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
    }

    var theme: Theme
        get() = runCatching { Theme.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(Theme.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var lockerLayout: LockerLayout
        get() = runCatching { LockerLayout.valueOf(prefs.getString(KEY_LOCKER_LAYOUT, null)!!) }.getOrDefault(LockerLayout.LIST)
        set(value) = prefs.edit().putString(KEY_LOCKER_LAYOUT, value.name).apply()

    var lockerSort: LockerSort
        get() = runCatching { LockerSort.valueOf(prefs.getString(KEY_LOCKER_SORT, null)!!) }.getOrDefault(LockerSort.ADDED)
        set(value) = prefs.edit().putString(KEY_LOCKER_SORT, value.name).apply()

    fun getCustomAuthorities(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_AUTHORITIES, emptySet()) ?: emptySet()
    }

    fun addCustomAuthority(authority: String) {
        val trimmed = authority.trim()
        if (trimmed.isBlank()) return
        val current = getCustomAuthorities().toMutableSet()
        if (current.add(trimmed)) {
            prefs.edit().putStringSet(KEY_CUSTOM_AUTHORITIES, current).apply()
        }
    }

    var lockerTimeoutMinutes: Int
        get() = prefs.getInt(KEY_LOCKER_TIMEOUT_MINUTES, 5)
        set(value) = prefs.edit().putInt(KEY_LOCKER_TIMEOUT_MINUTES, value).apply()

    var isSamsungClipboardWarningDismissed: Boolean
        get() = prefs.getBoolean("samsung_clipboard_warning_dismissed", false)
        set(value) = prefs.edit().putBoolean("samsung_clipboard_warning_dismissed", value).apply()

    var isAutoCopyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COPY, false)
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
