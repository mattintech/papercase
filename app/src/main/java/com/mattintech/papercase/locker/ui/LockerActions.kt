package com.mattintech.papercase.locker.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.mattintech.papercase.R
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.crypto.BiometricAuthHelper
import com.mattintech.papercase.locker.crypto.DocumentPasswordCrypto
import com.mattintech.papercase.locker.model.AttachmentSlot
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.FolderTree
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import com.mattintech.papercase.ui.SecureAlertDialogBuilder
import com.mattintech.papercase.ui.SecureBottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.crypto.SecretKey

/**
 * Actions on documents and folders from the locker list: the document ⋮ sheet (share, save to
 * device, move, edit, delete), folder options, and the folder picker.
 *
 * [pickSaveFolder] opens the system folder picker and calls back with the chosen folder.
 */
class LockerActions(
    private val activity: AppCompatActivity,
    private val repository: SecureLockerRepository,
    private val pickSaveFolder: (onPicked: (Uri) -> Unit) -> Unit,
    private val onChanged: () -> Unit
) {

    // ---------- Documents ----------

    fun showDocumentSheet(doc: SecureDocument, folders: List<Folder>) {
        val sheet = SecureBottomSheetDialog(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        list.addView(TextView(activity).apply {
            text = doc.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(dp(24), dp(8), dp(24), dp(12))
        })
        fun row(icon: Int, label: String, destructive: Boolean = false, action: () -> Unit) {
            list.addView(sheetRow(icon, label, destructive) {
                sheet.dismiss()
                action()
            })
        }
        if (doc.attachments.isNotEmpty()) {
            row(R.drawable.ic_share, "Share") { confirmDecrypted { unlockFor(doc) { dek -> share(doc, dek) } } }
            row(R.drawable.ic_download, "Save to device") {
                confirmDecrypted { pickSaveFolder { tree -> unlockFor(doc) { dek -> saveTo(doc, dek, tree) } } }
            }
        }
        row(R.drawable.ic_drive_file_move, "Move to folder") {
            pickFolder(folders, title = "Move to", exclude = emptySet(), current = FolderTree.resolve(folders, doc.folderId)) { target ->
                launch { repository.moveDocument(doc.id, target) }
            }
        }
        row(R.drawable.ic_edit, "Edit") { AddDocumentActivity.start(activity, doc.id) }
        row(R.drawable.ic_delete, "Delete", destructive = true) {
            SecureAlertDialogBuilder(activity)
                .setTitle("Delete ${doc.title}?")
                .setMessage("This permanently erases the document and its files from your locker.")
                .setPositiveButton("Delete") { _, _ -> launch { repository.deleteDocument(doc.id) } }
                .setNegativeButton("Cancel", null)
                .show()
        }
        sheet.setContentView(list)
        sheet.show()
    }

    private fun sheetRow(icon: Int, label: String, destructive: Boolean, onClick: () -> Unit): View {
        val colour = MaterialColors.getColor(
            activity.window.decorView,
            if (destructive) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurface
        )
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(24), 0, dp(24), 0)
            isClickable = true
            isFocusable = true
            background = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(colour)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(activity).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(colour)
                setPadding(dp(20), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    /** Sharing and saving hand over unencrypted copies; say so first. */
    private fun confirmDecrypted(onConfirm: () -> Unit) {
        SecureAlertDialogBuilder(activity)
            .setTitle("Share a decrypted copy?")
            .setMessage(
                "Sharing sends an unencrypted copy. The app you share to, for example WhatsApp, email or " +
                    "Files, keeps it outside your locker, where anyone with access to that app or device can " +
                    "open it. Papercase can't delete it afterwards."
            )
            .setPositiveButton("Continue") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fingerprint / PIN, then the document's own password if it has one. */
    private fun unlockFor(doc: SecureDocument, then: (SecretKey?) -> Unit) {
        val afterAuth = {
            if (doc.isPasswordProtected) askDocumentPassword(doc, then) else then(null)
        }
        if (!BiometricAuthHelper.getDeviceSecurityLevel(activity).isProtected) return afterAuth()
        BiometricAuthHelper.promptGeneralUnlock(
            activity = activity,
            title = "Confirm it's you",
            subtitle = "Unlock to export ${doc.title}",
            onSuccess = {
                SessionManager.markAuthenticated()
                afterAuth()
            },
            onError = { _, _ -> }
        )
    }

    private fun askDocumentPassword(doc: SecureDocument, then: (SecretKey?) -> Unit) {
        val input = DialogInput(activity, hint = "Document password", password = true)
        SecureAlertDialogBuilder(activity)
            .setTitle("Password protected")
            .setMessage("Enter the password for ${doc.title}.")
            .setView(input.view)
            .setPositiveButton("Unlock") { _, _ ->
                val salt = runCatching { Base64.getDecoder().decode(doc.passwordSaltBase64) }.getOrDefault(ByteArray(0))
                val key = DocumentPasswordCrypto.deriveKeyFromPassword(input.text.toCharArray(), salt)
                val dek = DocumentPasswordCrypto.decryptDek(doc.encryptedDekBase64, doc.dekIvBase64, key)
                if (dek == null) {
                    Toast.makeText(activity, "Incorrect document password", Toast.LENGTH_SHORT).show()
                } else {
                    then(dek)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Readable file names: "Passport - Front.jpg", "Insurance - Page 2.jpg". */
    private fun exportNames(doc: SecureDocument): Map<String, String> {
        val used = mutableSetOf<String>()
        var page = 0
        return doc.attachments.associate { att ->
            val ext = att.fileName.substringAfterLast('.', if (att.isPdf) "pdf" else "jpg")
            val label = when {
                att.customTitle.isNotBlank() -> att.customTitle
                att.slot == AttachmentSlot.FRONT -> "Front"
                att.slot == AttachmentSlot.BACK -> "Back"
                att.slot == AttachmentSlot.OTHER && !att.isPdf -> "Page ${++page}"
                else -> att.fileName.substringBeforeLast('.')
            }
            var name = "${doc.title} - $label.$ext"
            var n = 2
            while (!used.add(name)) name = "${doc.title} - $label (${n++}).$ext"
            att.id to name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }
    }

    private fun share(doc: SecureDocument, dek: SecretKey?) = activity.lifecycleScope.launch {
        try {
            val names = exportNames(doc)
            val uris = ArrayList(doc.attachments.map { repository.exportAttachmentForShare(it, dek, names.getValue(it.id)) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = doc.attachments.first().mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = if (doc.attachments.all { it.mimeType.startsWith("image/") }) "image/*" else "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, "Share ${doc.title}"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveTo(doc: SecureDocument, dek: SecretKey?, tree: Uri) = activity.lifecycleScope.launch {
        val saved = try {
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(activity, tree) ?: error("Can't open that folder")
                val names = exportNames(doc)
                doc.attachments.count { att ->
                    val bytes = repository.readAttachmentBytes(att, dek)
                    val file = dir.createFile(att.mimeType, names.getValue(att.id)) ?: return@count false
                    activity.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } != null
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
            return@launch
        }
        Toast.makeText(activity, "Saved $saved ${if (saved == 1) "file" else "files"}", Toast.LENGTH_SHORT).show()
    }

    // ---------- Folders ----------

    fun showFolderMenu(folder: Folder, anchor: View, folders: List<Folder>) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 4, 1, "Change icon")
        popup.menu.add(0, 2, 2, "Move")
        popup.menu.add(0, 3, 3, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptFolderName("Rename folder", folder.name) { name -> launch { repository.saveFolder(folder.copy(name = name)) } }
                4 -> IconPickerSheet(activity, R.drawable.ic_folder, folder.iconKey, folder.iconColor, onDone = { key, colour ->
                    if (key != folder.iconKey || colour != folder.iconColor) {
                        launch { repository.saveFolder(folder.copy(iconKey = key, iconColor = colour)) }
                    }
                }).show()
                2 -> pickFolder(
                    folders, title = "Move ${folder.name} to",
                    exclude = FolderTree.subtree(folders, folder.id),
                    current = FolderTree.resolve(folders, folder.parentId)
                ) { target -> launch { repository.moveFolder(folder.id, target) } }
                3 -> {
                    val parentName = FolderTree.path(folders, folder.parentId).lastOrNull()?.name ?: "Locker"
                    SecureAlertDialogBuilder(activity)
                        .setTitle("Delete ${folder.name}?")
                        .setMessage("Nothing inside is deleted. Its documents and folders move to $parentName.")
                        .setPositiveButton("Delete folder") { _, _ -> launch { repository.deleteFolder(folder.id) } }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    fun promptNewFolder(parentId: String, onCreated: (Folder) -> Unit = {}) {
        promptFolderName("New folder", "") { name ->
            val folder = Folder(name = name, parentId = parentId)
            // Saved before anything (like a move) uses it, so the two writes don't overlap
            launch {
                repository.saveFolder(folder)
                onCreated(folder)
            }
        }
    }

    private fun promptFolderName(title: String, initial: String, onName: (String) -> Unit) {
        val input = DialogInput(activity, hint = "Folder name")
        input.editText.setText(initial)
        input.editText.setSelectAllOnFocus(true)
        SecureAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(input.view)
            .setPositiveButton("Save") { _, _ -> input.text.trim().takeIf { it.isNotEmpty() }?.let(onName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lists the locker's folders as a tree; [exclude] can't be chosen (a folder can't go inside itself). */
    private fun pickFolder(
        folders: List<Folder>,
        title: String,
        exclude: Set<String>,
        current: String,
        onPicked: (String) -> Unit
    ) {
        val options = listOf<Pair<String, String>>("" to "Locker (top level)") +
            FolderTree.flatten(folders).filter { it.first.id !in exclude }
                .map { (f, depth) -> f.id to "    ".repeat(depth + 1) + f.name }
        val labels = options.map { (id, label) -> if (id == current) "$label  (current)" else label } + "New folder…"
        SecureAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == options.size) {
                    promptNewFolder(current) { created -> onPicked(created.id) }
                } else if (options[which].first != current) {
                    onPicked(options[which].first)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launch(block: suspend () -> Unit) = activity.lifecycleScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't save the change. Unlock the locker and try again.", Toast.LENGTH_LONG).show()
        }
        onChanged()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
