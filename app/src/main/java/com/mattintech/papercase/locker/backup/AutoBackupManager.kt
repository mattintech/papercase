package com.mattintech.papercase.locker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.mattintech.papercase.locker.crypto.LockerKeystoreHelper
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import com.mattintech.papercase.locker.transfer.KdbxBackupFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily / weekly backups to a folder the user picks, as .kdbx files protected by a backup password
 * the user chooses (so they also open in KeePassXC).
 *
 * The locker can only be read right after a fingerprint / PIN unlock, so backups are prepared then:
 * every change refreshes an encrypted snapshot in private storage, and the scheduled job only copies
 * that snapshot to the folder. The backup password is stored encrypted with the locker key, so it's
 * likewise only usable while the locker is unlocked.
 */
object AutoBackupManager {

    enum class Frequency(val days: Long, val label: String) { DAILY(1, "Daily"), WEEKLY(7, "Weekly") }

    sealed class Result {
        data object Done : Result()
        data object NotDue : Result()
        data object Unchanged : Result()
        data class Failed(val message: String) : Result()
    }

    /** Automatic backups kept in the folder; older ones are deleted. Manual exports are never touched. */
    const val KEEP = 5
    private const val FILE_PREFIX = "Papercase_Auto_"
    private const val SNAPSHOT_DELAY_MS = 4_000L

    private const val PREFS = "papercase_auto_backup"
    private const val K_ENABLED = "enabled"
    private const val K_FREQUENCY = "frequency"
    private const val K_FOLDER = "folder_uri"
    private const val K_PW_CIPHER = "password_cipher"
    private const val K_PW_IV = "password_iv"
    private const val K_PW_KEY_VERSION = "password_key_version"
    private const val K_DIRTY = "snapshot_dirty"
    private const val K_LAST_AT = "last_backup_at"
    private const val K_LAST_HASH = "last_backup_hash"
    private const val K_LAST_ERROR = "last_error"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSnapshot: Job? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(K_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(K_ENABLED, enabled) }

    fun frequency(context: Context): Frequency =
        runCatching { Frequency.valueOf(prefs(context).getString(K_FREQUENCY, null)!!) }.getOrDefault(Frequency.WEEKLY)
    fun setFrequency(context: Context, f: Frequency) = prefs(context).edit { putString(K_FREQUENCY, f.name) }

    fun folder(context: Context): Uri? = prefs(context).getString(K_FOLDER, null)?.let(Uri::parse)

    fun folderName(context: Context): String? =
        folder(context)?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /** Stores a folder picked with ACTION_OPEN_DOCUMENT_TREE and keeps access to it across reboots. */
    fun setFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        folder(context)?.takeIf { it != uri }?.let {
            runCatching { context.contentResolver.releasePersistableUriPermission(it, flags) }
        }
        prefs(context).edit {
            putString(K_FOLDER, uri.toString())
            remove(K_LAST_HASH) // new folder: the next run writes a backup even if nothing changed
        }
    }

    fun hasPassword(context: Context) = prefs(context).contains(K_PW_CIPHER)
    fun lastBackupAt(context: Context) = prefs(context).getLong(K_LAST_AT, 0L)
    fun lastError(context: Context): String? = prefs(context).getString(K_LAST_ERROR, null)

    /** True when everything needed to run is set up. */
    fun isReady(context: Context) = folder(context) != null && hasPassword(context)

    /** Must be called while the locker is unlocked (the password is encrypted with the locker key). */
    suspend fun setPassword(context: Context, password: String) = withContext(Dispatchers.IO) {
        val version = SecureLockerRepository(context).currentKeyVersion()
        val (cipher, iv) = LockerKeystoreHelper.encryptString(password, version)
        prefs(context).edit {
            putString(K_PW_CIPHER, cipher)
            putString(K_PW_IV, iv)
            putInt(K_PW_KEY_VERSION, version)
            putBoolean(K_DIRTY, true)
            remove(K_LAST_HASH)
        }
    }

    private fun readPassword(context: Context): String? {
        val p = prefs(context)
        val cipher = p.getString(K_PW_CIPHER, null) ?: return null
        val iv = p.getString(K_PW_IV, null) ?: return null
        return LockerKeystoreHelper.decryptString(cipher, iv, p.getInt(K_PW_KEY_VERSION, LockerKeystoreHelper.KEY_VERSION_LEGACY))
    }

    /** Re-encrypts the stored password when the locker moves to a new key. */
    fun migratePassword(context: Context, fromVersion: Int, toVersion: Int) {
        val p = prefs(context)
        if (p.getInt(K_PW_KEY_VERSION, LockerKeystoreHelper.KEY_VERSION_LEGACY) != fromVersion) return
        val password = readPassword(context) ?: return
        val (cipher, iv) = LockerKeystoreHelper.encryptString(password, toVersion)
        p.edit {
            putString(K_PW_CIPHER, cipher)
            putString(K_PW_IV, iv)
            putInt(K_PW_KEY_VERSION, toVersion)
        }
    }

    /** The locker key was destroyed, so the stored password can't be decrypted any more. */
    fun onLockerKeyLost(context: Context) = prefs(context).edit {
        remove(K_PW_CIPHER); remove(K_PW_IV); remove(K_PW_KEY_VERSION)
        putString(K_LAST_ERROR, "Set your backup password again in Settings.")
    }

    private fun snapshotFile(context: Context) = File(File(context.filesDir, "backup").apply { mkdirs() }, "snapshot.kdbx")

    /** Called after every locker change; refreshes the snapshot shortly after, while the key is usable. */
    fun onLockerChanged(context: Context) {
        if (!isEnabled(context)) return
        prefs(context).edit { putBoolean(K_DIRTY, true) }
        val app = context.applicationContext
        pendingSnapshot?.cancel()
        pendingSnapshot = scope.launch {
            delay(SNAPSHOT_DELAY_MS)
            refreshIfNeeded(app)
        }
    }

    /** Rewrites the snapshot if the locker changed since it was made. Returns false if the locker is locked. */
    suspend fun refreshIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled(context) || !hasPassword(context)) return@withContext true
        if (prefs(context).getBoolean(K_DIRTY, true).not() && snapshotFile(context).exists()) return@withContext true
        try {
            writeSnapshot(context)
            true
        } catch (e: Exception) {
            // Usually the locker key timed out; the next unlock refreshes it
            false
        }
    }

    private suspend fun writeSnapshot(context: Context) {
        val password = readPassword(context) ?: return
        val repository = SecureLockerRepository(context)
        val documents = repository.getAllDocuments()
        val deletions = repository.getDeletedDocuments()
        val target = snapshotFile(context)
        val tmp = File(target.parentFile, "snapshot.kdbx.tmp")
        tmp.outputStream().use { out ->
            KdbxBackupFormat.write(out, password, documents, deletions, exactPassword = true) { doc, att ->
                if (doc.isPasswordProtected) repository.readStoredAttachmentBytes(att) else repository.readAttachmentBytes(att)
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Could not save backup snapshot")
        }
        prefs(context).edit { putBoolean(K_DIRTY, false) }
    }

    /**
     * Copies the snapshot to the backup folder if a backup is due ([force] skips the schedule check).
     * Skips the copy when nothing changed since the last backup, so the kept files are all different.
     */
    suspend fun runBackup(context: Context, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (!isEnabled(context) && !force) return@withContext Result.NotDue
        val folderUri = folder(context) ?: return@withContext fail(context, "Choose a backup folder in Settings.")
        if (!hasPassword(context)) return@withContext fail(context, "Set a backup password in Settings.")

        if (!force) {
            val dueAt = lastBackupAt(context) + frequency(context).days * DAY_MS - SLACK_MS
            if (System.currentTimeMillis() < dueAt) return@withContext Result.NotDue
        }

        refreshIfNeeded(context) // best effort; if locked, the last snapshot is still a valid backup
        val snapshot = snapshotFile(context)
        if (!snapshot.exists()) return@withContext fail(context, "Open Papercase once so the first backup can be prepared.")

        val bytes = snapshot.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash == prefs(context).getString(K_LAST_HASH, null)) {
            prefs(context).edit { putLong(K_LAST_AT, System.currentTimeMillis()); remove(K_LAST_ERROR) }
            return@withContext Result.Unchanged
        }

        val dir = DocumentFile.fromTreeUri(context, folderUri)
        if (dir == null || !dir.canWrite()) return@withContext fail(context, "Can't write to the backup folder. Choose it again in Settings.")

        try {
            val name = FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".kdbx"
            val file = dir.createFile("application/octet-stream", name)
                ?: return@withContext fail(context, "Couldn't create a file in the backup folder.")
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(bytes) }
                ?: return@withContext fail(context, "Couldn't write to the backup folder.")

            val existing = dir.listFiles().mapNotNull { f -> f.name?.let { it to f } }.toMap()
            for (old in filesToPrune(existing.keys, KEEP)) existing[old]?.delete()

            prefs(context).edit {
                putLong(K_LAST_AT, System.currentTimeMillis())
                putString(K_LAST_HASH, hash)
                remove(K_LAST_ERROR)
            }
            Result.Done
        } catch (e: Exception) {
            fail(context, "Backup failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Automatic backup names (timestamped, so they sort by age) beyond the newest [keep]. */
    fun filesToPrune(names: Collection<String>, keep: Int): List<String> =
        names.filter { it.startsWith(FILE_PREFIX) && it.endsWith(".kdbx") }
            .sortedDescending()
            .drop(keep)

    private fun fail(context: Context, message: String): Result {
        prefs(context).edit { putString(K_LAST_ERROR, message) }
        return Result.Failed(message)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    // The daily job doesn't run at exactly the same minute each day
    private const val SLACK_MS = 2 * 60 * 60 * 1000L
}
