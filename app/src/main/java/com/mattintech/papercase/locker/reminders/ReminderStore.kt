package com.mattintech.papercase.locker.reminders

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mattintech.papercase.locker.model.SecureDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The list of upcoming expiry reminders (document title and dates only), kept outside the locker so
 * the daily background check can read it without the user unlocking. Encrypted with its own
 * hardware-backed key that doesn't require authentication; the documents themselves stay in the
 * auth-bound locker.
 */
object ReminderStore {

    private const val KEY_ALIAS = "papercase_reminders_key_v1"
    private const val FILE_NAME = "reminders.enc"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<ReminderEntry> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val bytes = f.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ReminderEntry(
                    docId = o.getString("docId"),
                    title = o.optString("title"),
                    expiresOn = o.getString("expiresOn"),
                    remindOn = o.getString("remindOn"),
                    notifiedEarly = o.optBoolean("notifiedEarly"),
                    lastExpiredNotice = o.optString("lastExpiredNotice").ifEmpty { null },
                    stopped = o.optBoolean("stopped"),
                )
            }
        } catch (e: Exception) {
            // Unreadable (e.g. key lost): it's rebuilt from the locker on the next save
            e.printStackTrace()
            emptyList()
        }
    }

    fun save(context: Context, entries: List<ReminderEntry>) = synchronized(lock) {
        val f = file(context)
        if (entries.isEmpty()) {
            f.delete()
            return
        }
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("docId", e.docId)
                put("title", e.title)
                put("expiresOn", e.expiresOn)
                put("remindOn", e.remindOn)
                put("notifiedEarly", e.notifiedEarly)
                e.lastExpiredNotice?.let { put("lastExpiredNotice", it) }
                put("stopped", e.stopped)
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /** Called whenever the locker catalog is written (the locker is unlocked at that point). */
    fun sync(context: Context, documents: List<SecureDocument>) = synchronized(lock) {
        save(context, ExpiryDates.buildEntries(documents, load(context)))
    }

    /** "Stop reminders" from a notification. Kept until the document gets a new expiry date. */
    fun stop(context: Context, docId: String) = synchronized(lock) {
        save(context, load(context).map { if (it.docId == docId) it.copy(stopped = true) else it })
    }

    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
