package com.mattintech.securepass.locker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.mattintech.securepass.R
import com.mattintech.securepass.crypto.BiometricAuthHelper
import com.mattintech.securepass.databinding.ActivityAddDocumentBinding
import com.mattintech.securepass.databinding.ItemAttachmentChipBinding
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import com.mattintech.securepass.locker.repository.SecureLockerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddDocumentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_ID = "extra_document_id"

        fun start(context: Context, documentId: String? = null) {
            val intent = Intent(context, AddDocumentActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityAddDocumentBinding
    private lateinit var repository: SecureLockerRepository

    private var editingDocumentId: String? = null
    private var existingDocument: SecureDocument? = null

    // Pending attachments to be encrypted on save: Triple(fileName, mimeType, byteData)
    private val pendingAttachments = mutableListOf<Triple<String, String, ByteArray>>()
    // Retained existing attachments if editing
    private val retainedAttachments = mutableListOf<DocumentAttachment>()

    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null

    // Photo picker launcher (Gallery)
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handleSelectedUri(uri)
        }

    // PDF / File picker launcher
    private val pickDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleSelectedUri(uri)
        }

    // Camera launcher
    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && tempPhotoFile != null && tempPhotoFile!!.exists()) {
                val bytes = tempPhotoFile!!.readBytes()
                val fileName = "Photo_${System.currentTimeMillis()}.jpg"
                pendingAttachments.add(Triple(fileName, "image/jpeg", bytes))
                renderAttachmentsUI()
                tempPhotoFile?.delete()
                tempPhotoFile = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots, task switcher previews, and screen recording of sensitive documents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityAddDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SecureLockerRepository(this)
        editingDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)

        setupUI()
        setupListeners()

        if (editingDocumentId != null) {
            loadExistingDocument(editingDocumentId!!)
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Date picker for expiry date
        binding.etExpiry.setOnClickListener { showDatePicker() }
        binding.tilExpiry.setEndIconOnClickListener { showDatePicker() }
    }

    private fun setupListeners() {
        // Camera button
        binding.btnTakePhoto.setOnClickListener {
            try {
                val cacheDir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
                tempPhotoFile = File(cacheDir, "temp_${System.currentTimeMillis()}.jpg")
                tempPhotoUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    tempPhotoFile!!
                )
                takePhotoLauncher.launch(tempPhotoUri!!)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Gallery pick button
        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Document / PDF pick button
        binding.btnPickPdf.setOnClickListener {
            pickDocumentLauncher.launch("*/*")
        }

        // Save Document button
        binding.btnSaveDocument.setOnClickListener {
            validateAndSave()
        }
    }

    private fun loadExistingDocument(docId: String) {
        lifecycleScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            existingDocument = doc
            binding.toolbar.title = "Edit Document"
            binding.etTitle.setText(doc.title)
            binding.etNumber.setText(doc.documentNumber)
            binding.etExpiry.setText(doc.expirationDate)
            binding.etCountry.setText(doc.issuingCountry)
            binding.etNotes.setText(doc.notes)

            when (doc.type) {
                DocumentType.DRIVERS_LICENSE -> binding.chipDriversLicense.isChecked = true
                DocumentType.PASSPORT -> binding.chipPassport.isChecked = true
                DocumentType.ID_CARD -> binding.chipIdCard.isChecked = true
                DocumentType.INSURANCE -> binding.chipInsurance.isChecked = true
                DocumentType.PDF_DOCUMENT -> binding.chipPdf.isChecked = true
                DocumentType.OTHER -> binding.chipOther.isChecked = true
            }

            retainedAttachments.clear()
            retainedAttachments.addAll(doc.attachments)
            renderAttachmentsUI()
        }
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Expiration Date")
            .build()

        picker.addOnPositiveButtonClickListener { selectionMs ->
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val formattedDate = formatter.format(Date(selectionMs))
            binding.etExpiry.setText(formattedDate)
        }

        picker.show(supportFragmentManager, "EXPIRY_PICKER")
    }

    private fun handleSelectedUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = applicationContext.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                var fileName = "document_${System.currentTimeMillis()}"

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        pendingAttachments.add(Triple(fileName, mimeType, bytes))
                        renderAttachmentsUI()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddDocumentActivity, "Failed to load file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderAttachmentsUI() {
        binding.layoutAttachmentsList.removeAllViews()

        // Render existing saved attachments
        retainedAttachments.forEachIndexed { index, att ->
            val itemBinding = ItemAttachmentChipBinding.inflate(layoutInflater, binding.layoutAttachmentsList, false)
            itemBinding.tvFileName.text = att.fileName
            itemBinding.tvFileSize.text = formatFileSize(att.sizeBytes)

            if (att.mimeType.contains("pdf", ignoreCase = true)) {
                itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_description)
            } else {
                itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_image)
            }

            itemBinding.btnRemoveAttachment.setOnClickListener {
                retainedAttachments.removeAt(index)
                renderAttachmentsUI()
            }
            binding.layoutAttachmentsList.addView(itemBinding.root)
        }

        // Render newly selected pending attachments
        pendingAttachments.forEachIndexed { index, pending ->
            val itemBinding = ItemAttachmentChipBinding.inflate(layoutInflater, binding.layoutAttachmentsList, false)
            itemBinding.tvFileName.text = pending.first
            itemBinding.tvFileSize.text = formatFileSize(pending.third.size.toLong())

            if (pending.second.contains("image", ignoreCase = true)) {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(pending.third, 0, pending.third.size)
                    if (bitmap != null) {
                        itemBinding.ivAttachmentPreview.setImageBitmap(bitmap)
                    } else {
                        itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_image)
                    }
                } catch (e: Exception) {
                    itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_image)
                }
            } else {
                itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_description)
            }

            itemBinding.btnRemoveAttachment.setOnClickListener {
                pendingAttachments.removeAt(index)
                renderAttachmentsUI()
            }
            binding.layoutAttachmentsList.addView(itemBinding.root)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun getSelectedDocumentType(): DocumentType {
        return when (binding.chipGroupDocType.checkedChipId) {
            R.id.chipPassport -> DocumentType.PASSPORT
            R.id.chipDriversLicense -> DocumentType.DRIVERS_LICENSE
            R.id.chipIdCard -> DocumentType.ID_CARD
            R.id.chipInsurance -> DocumentType.INSURANCE
            R.id.chipPdf -> DocumentType.PDF_DOCUMENT
            else -> DocumentType.OTHER
        }
    }

    private fun validateAndSave() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            binding.tilTitle.error = "Title is required"
            return
        }
        binding.tilTitle.error = null

        val docNumber = binding.etNumber.text?.toString()?.trim().orEmpty()
        val expiry = binding.etExpiry.text?.toString()?.trim().orEmpty()
        val country = binding.etCountry.text?.toString()?.trim().orEmpty()
        val notes = binding.etNotes.text?.toString()?.trim().orEmpty()
        val docType = getSelectedDocumentType()

        binding.btnSaveDocument.isEnabled = false

        lifecycleScope.launch {
            try {
                val finalAttachments = retainedAttachments.toMutableList()

                // Encrypt and persist each pending attachment
                pendingAttachments.forEach { pending ->
                    val savedAttachment = repository.saveAttachmentBytes(
                        fileName = pending.first,
                        mimeType = pending.second,
                        data = pending.third
                    )
                    finalAttachments.add(savedAttachment)
                }

                val docToSave = existingDocument?.copy(
                    title = title,
                    type = docType,
                    documentNumber = docNumber,
                    expirationDate = expiry,
                    issuingCountry = country,
                    notes = notes,
                    attachments = finalAttachments,
                    updatedAt = System.currentTimeMillis()
                ) ?: SecureDocument(
                    title = title,
                    type = docType,
                    documentNumber = docNumber,
                    expirationDate = expiry,
                    issuingCountry = country,
                    notes = notes,
                    attachments = finalAttachments
                )

                val success = repository.saveDocument(docToSave)
                if (success) {
                    Toast.makeText(this@AddDocumentActivity, "Document encrypted & saved!", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@AddDocumentActivity, "Failed to save document", Toast.LENGTH_LONG).show()
                    binding.btnSaveDocument.isEnabled = true
                }
            } catch (e: Exception) {
                Toast.makeText(this@AddDocumentActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                binding.btnSaveDocument.isEnabled = true
            }
        }
    }
}
