package com.mattintech.papercase.locker.ui

import com.mattintech.papercase.locker.reminders.ReminderOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mattintech.papercase.R
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.crypto.BiometricAuthHelper
import com.mattintech.papercase.crypto.KeystoreHelper
import com.mattintech.papercase.databinding.ActivityDocumentViewerBinding
import com.mattintech.papercase.databinding.ItemViewerAttachmentBinding
import com.mattintech.papercase.locker.crypto.DocumentPasswordCrypto
import com.mattintech.papercase.locker.model.DocumentAttachment
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import javax.crypto.SecretKey

class DocumentViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_ID = "extra_document_id"

        fun start(context: Context, documentId: String) {
            val intent = Intent(context, DocumentViewerActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityDocumentViewerBinding
    private lateinit var repository: SecureLockerRepository
    private var documentId: String? = null
    private var currentDocument: SecureDocument? = null
    private var unlockedDek: SecretKey? = null
    private var hasPromptedPassword = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots in release builds; allow in debug builds for layout inspection
        if (!com.mattintech.papercase.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SecureLockerRepository(this)
        documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)

        if (documentId == null) {
            finish()
            return
        }

        setupWindowInsets()
        setupToolbar()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBarLayout.updatePadding(
                top = statusBarInsets.top,
                left = statusBarInsets.left,
                right = statusBarInsets.right
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.nestedScrollView.updatePadding(
                bottom = navBarInsets.bottom + 24,
                left = navBarInsets.left,
                right = navBarInsets.right
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isLockerUnlocked) {
            finish()
            return
        }
        documentId?.let { loadDocument(it) }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnShare.setOnClickListener {
            currentDocument?.let { promptBiometricForShare(it) }
        }

        binding.btnEdit.setOnClickListener {
            documentId?.let { AddDocumentActivity.start(this, it) }
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }

        binding.btnCopyNumber.setOnClickListener {
            val number = currentDocument?.documentNumber.orEmpty()
            if (number.isNotBlank()) {
                val prefs = com.mattintech.papercase.data.PreferencesManager(this)
                val timeout = if (prefs.isAutoClearEnabled) prefs.clearTimeoutSeconds else 0
                com.mattintech.papercase.clipboard.ClipboardSecurityManager.copyToClipboard(
                    context = this,
                    passphrase = number,
                    timeoutSeconds = timeout,
                    label = "Document Number"
                )
                Toast.makeText(this, "Document number copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadDocument(id: String) {
        lifecycleScope.launch {
            val doc = try {
                repository.getDocumentById(id)
            } catch (e: Exception) {
                // Locker key needs a fresh unlock (or was lost): go back to the locker screen
                Toast.makeText(this@DocumentViewerActivity, "Locker locked. Unlock again to continue.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            if (doc == null) {
                finish()
                return@launch
            }
            currentDocument = doc

            if (doc.isPasswordProtected && unlockedDek == null) {
                if (!hasPromptedPassword) {
                    hasPromptedPassword = true
                    promptPasswordToView(doc)
                }
            } else {
                renderDocumentDetails(doc)
                decryptAndRenderAttachments(doc.attachments, unlockedDek)
            }
        }
    }

    private fun promptPasswordToView(doc: SecureDocument) {
        val input = DialogInput(this, hint = "Document password", password = true)

        MaterialAlertDialogBuilder(this)
            .setTitle("Password Protected")
            .setMessage("This document is protected with a custom password. Enter it to view details:")
            .setView(input.view)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                val password = input.text
                val salt = try {
                    Base64.getDecoder().decode(doc.passwordSaltBase64)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val passwordKey = DocumentPasswordCrypto.deriveKeyFromPassword(password.toCharArray(), salt)
                val dek = DocumentPasswordCrypto.decryptDek(doc.encryptedDekBase64, doc.dekIvBase64, passwordKey)

                if (dek != null) {
                    unlockedDek = dek
                    renderDocumentDetails(doc)
                    decryptAndRenderAttachments(doc.attachments, dek)
                } else {
                    Toast.makeText(this, "Incorrect document password", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .show()
    }

    private fun renderDocumentDetails(doc: SecureDocument) {
        binding.toolbar.title = doc.title
        binding.tvDocTitle.text = doc.title
        binding.ivDocTypeIcon.setImageResource(doc.type.iconRes)

        val typeText = if (doc.issuingCountry.isNotBlank()) {
            "${doc.type.displayName} • ${doc.issuingCountry}"
        } else {
            doc.type.displayName
        }
        binding.tvDocType.text = typeText

        if (doc.documentNumber.isNotBlank()) {
            binding.tvDocNumber.text = doc.documentNumber
            binding.btnCopyNumber.visibility = View.VISIBLE
        } else {
            binding.tvDocNumber.text = "None recorded"
            binding.btnCopyNumber.visibility = View.GONE
        }

        if (doc.expirationDate.isNotBlank()) {
            binding.layoutExpiry.visibility = View.VISIBLE
            binding.tvExpiry.text = if (doc.reminderDays > 0) {
                "${doc.expirationDate}  ·  reminder ${ReminderOptions.label(doc.reminderDays)}"
            } else {
                doc.expirationDate
            }
        } else {
            binding.layoutExpiry.visibility = View.GONE
        }

        if (doc.issuingCountry.isNotBlank()) {
            binding.layoutCountry.visibility = View.VISIBLE
            binding.tvCountry.text = doc.issuingCountry
        } else {
            binding.layoutCountry.visibility = View.GONE
        }

        if (doc.notes.isNotBlank()) {
            binding.layoutNotes.visibility = View.VISIBLE
            binding.tvNotes.text = doc.notes
        } else {
            binding.layoutNotes.visibility = View.GONE
        }
    }

    private fun decryptAndRenderAttachments(attachments: List<DocumentAttachment>, dek: SecretKey? = null) {
        binding.layoutDecryptedAttachments.removeAllViews()

        if (attachments.isEmpty()) {
            binding.tvAttachmentsHeader.visibility = View.GONE
            return
        }
        binding.tvAttachmentsHeader.visibility = View.VISIBLE

        attachments.forEach { attachment ->
            val itemBinding = ItemViewerAttachmentBinding.inflate(
                layoutInflater,
                binding.layoutDecryptedAttachments,
                false
            )
            itemBinding.tvAttachmentSlotBadge.text = attachment.slot.name
            itemBinding.tvAttachmentTitle.text = attachment.getDisplayTitle()
            itemBinding.tvAttachmentSub.text = "${attachment.fileName} • ${formatFileSize(attachment.sizeBytes)} • Tap to enlarge"
            itemBinding.progressDecrypt.visibility = View.VISIBLE

            itemBinding.btnShareAttachment.setOnClickListener {
                promptBiometricForSingleAttachmentShare(attachment)
            }

            binding.layoutDecryptedAttachments.addView(itemBinding.root)

            // Decrypt attachment in memory on background thread
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val decryptedBytes = repository.readAttachmentBytes(attachment, dek)
                    if (decryptedBytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            itemBinding.progressDecrypt.visibility = View.GONE
                        }
                        return@launch
                    }

                    val isPdf = attachment.mimeType.contains("pdf", ignoreCase = true) ||
                            attachment.fileName.endsWith(".pdf", ignoreCase = true)

                    if (isPdf) {
                        renderPdfPages(decryptedBytes, itemBinding, attachment)
                    } else {
                        val bitmap = OrientedBitmap.decode(decryptedBytes)
                        withContext(Dispatchers.Main) {
                            itemBinding.progressDecrypt.visibility = View.GONE
                            if (bitmap != null) {
                                itemBinding.ivAttachmentImage.setImageBitmap(bitmap)
                                itemBinding.ivAttachmentImage.setOnClickListener {
                                    showFullScreenImage(bitmap, attachment.getDisplayTitle())
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        itemBinding.progressDecrypt.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun showFullScreenImage(bitmap: Bitmap, title: String) {
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /** Shows the first page of a PDF attachment. Always hides the item's spinner when done. */
    private suspend fun renderPdfPages(
        pdfBytes: ByteArray,
        itemBinding: ItemViewerAttachmentBinding,
        attachment: DocumentAttachment
    ) = withContext(Dispatchers.IO) {
        // PdfRenderer needs a file; it's deleted as soon as the page is drawn
        val tempPdfFile = File(cacheDir, "temp_view_${System.currentTimeMillis()}.pdf")
        var bitmap: Bitmap? = null
        var pageCount = 0
        try {
            FileOutputStream(tempPdfFile).use { it.write(pdfBytes) }
            ParcelFileDescriptor.open(tempPdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also {
                                // PDF pages have no background of their own; without this they render transparent
                                it.eraseColor(android.graphics.Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempPdfFile.delete()
        }

        withContext(Dispatchers.Main) {
            itemBinding.progressDecrypt.visibility = View.GONE
            val page = bitmap
            if (page != null) {
                itemBinding.ivAttachmentImage.setImageBitmap(page)
                itemBinding.ivAttachmentImage.setOnClickListener {
                    showFullScreenImage(page, attachment.getDisplayTitle())
                }
                if (pageCount > 1) {
                    itemBinding.tvAttachmentSub.text = "${attachment.fileName} • page 1 of $pageCount • Share to see all pages"
                }
            } else {
                itemBinding.tvAttachmentSub.text = "${attachment.fileName} • Can't preview this PDF. Use Share to open it."
            }
        }
    }

    private fun promptBiometricForShare(doc: SecureDocument) {
        if (doc.attachments.isEmpty()) {
            // Share text info
            val shareText = buildString {
                append("${doc.title}\n")
                if (doc.documentNumber.isNotBlank()) append("Number: ${doc.documentNumber}\n")
                if (doc.expirationDate.isNotBlank()) append("Expires: ${doc.expirationDate}\n")
                if (doc.issuingCountry.isNotBlank()) append("Authority: ${doc.issuingCountry}\n")
                if (doc.notes.isNotBlank()) append("Notes: ${doc.notes}\n")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, "Share Document Info"))
            return
        }

        // Prompt biometric before sharing sensitive file attachment
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            shareAttachmentDirectly(doc.attachments.first())
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Authorize Document Export",
                subtitle = "Confirm fingerprint to export/share sensitive document",
                cipher = encryptCipher,
                onSuccess = {
                    shareAttachmentDirectly(doc.attachments.first())
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            shareAttachmentDirectly(doc.attachments.first())
        }
    }

    private fun promptBiometricForSingleAttachmentShare(attachment: DocumentAttachment) {
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            shareAttachmentDirectly(attachment)
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Authorize File Export",
                subtitle = "Confirm fingerprint to share ${attachment.fileName}",
                cipher = encryptCipher,
                onSuccess = {
                    shareAttachmentDirectly(attachment)
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            shareAttachmentDirectly(attachment)
        }
    }

    private fun shareAttachmentDirectly(attachment: DocumentAttachment) {
        lifecycleScope.launch {
            try {
                val uri = repository.exportAttachmentForShare(attachment, unlockedDek)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = attachment.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share ${attachment.fileName}"))
            } catch (e: Exception) {
                Toast.makeText(this@DocumentViewerActivity, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Document?")
            .setMessage("This will permanently erase this document and all its encrypted attachments from your locker. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                documentId?.let { id ->
                    lifecycleScope.launch {
                        val deleted = repository.deleteDocument(id)
                        if (deleted) {
                            Toast.makeText(this@DocumentViewerActivity, "Document deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
