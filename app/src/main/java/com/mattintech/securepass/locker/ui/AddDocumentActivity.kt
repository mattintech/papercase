package com.mattintech.securepass.locker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mattintech.securepass.R
import com.mattintech.securepass.auth.SessionManager
import com.mattintech.securepass.databinding.ActivityAddDocumentBinding
import com.mattintech.securepass.databinding.ItemAttachmentChipBinding
import com.mattintech.securepass.locker.crypto.DocumentPasswordCrypto
import com.mattintech.securepass.locker.data.IssuingAuthorityHelper
import com.mattintech.securepass.locker.model.AttachmentSlot
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import com.mattintech.securepass.locker.repository.SecureLockerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey

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

    sealed class AttachmentItem {
        abstract val slot: AttachmentSlot
        abstract var customTitle: String
        abstract val fileName: String
        abstract val mimeType: String
        abstract val sizeBytes: Long

        data class Existing(
            val docAttachment: DocumentAttachment,
            override var customTitle: String = docAttachment.customTitle
        ) : AttachmentItem() {
            override val slot: AttachmentSlot get() = docAttachment.slot
            override val fileName: String get() = docAttachment.fileName
            override val mimeType: String get() = docAttachment.mimeType
            override val sizeBytes: Long get() = docAttachment.sizeBytes
        }

        data class Pending(
            override val slot: AttachmentSlot,
            override var customTitle: String,
            override val fileName: String,
            override val mimeType: String,
            val bytes: ByteArray
        ) : AttachmentItem() {
            override val sizeBytes: Long get() = bytes.size.toLong()
        }
    }

    private lateinit var binding: ActivityAddDocumentBinding
    private lateinit var repository: SecureLockerRepository

    private var editingDocumentId: String? = null
    private var existingDocument: SecureDocument? = null

    // Attachment Slots
    private var frontAttachment: AttachmentItem? = null
    private var backAttachment: AttachmentItem? = null
    private val visaAttachments = mutableListOf<AttachmentItem>()
    private val otherAttachments = mutableListOf<AttachmentItem>()

    // Current capture routing
    private var activeCaptureSlot: AttachmentSlot = AttachmentSlot.FRONT
    private var activeVisaTitle: String = ""

    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null

    // Gallery picker
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handleSelectedUri(uri)
        }

    // PDF / generic file picker
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
                val item = AttachmentItem.Pending(
                    slot = activeCaptureSlot,
                    customTitle = activeVisaTitle,
                    fileName = fileName,
                    mimeType = "image/jpeg",
                    bytes = bytes
                )
                addAttachmentItem(item)
                tempPhotoFile?.delete()
                tempPhotoFile = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots in release builds; allow in debug builds for layout inspection
        if (!com.mattintech.securepass.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        binding = ActivityAddDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SecureLockerRepository(this)
        editingDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)

        setupWindowInsets()
        setupUI()
        setupListeners()

        if (editingDocumentId != null) {
            loadExistingDocument(editingDocumentId!!)
        } else {
            updateDocumentTypeUI(getSelectedDocumentType())
            renderAttachmentsUI()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isLockerUnlocked) {
            finish()
        }
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
            val navBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            )
            binding.nestedScrollView.updatePadding(
                bottom = navBarInsets.bottom + 32,
                left = navBarInsets.left,
                right = navBarInsets.right
            )
            insets
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Date picker for expiry date
        binding.etExpiry.setOnClickListener { showDatePicker() }
        binding.tilExpiry.setEndIconOnClickListener { showDatePicker() }

        // Searchable Country / Authority autocomplete adapter
        val lookupList = IssuingAuthorityHelper.getLookupList(this)
        val countryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, lookupList)
        binding.actvCountry.setAdapter(countryAdapter)
    }

    private fun setupListeners() {
        // Password protection switch toggle
        binding.switchPasswordProtect.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutPasswordFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Document Type Chip change
        binding.chipGroupDocType.setOnCheckedStateChangeListener { _, _ ->
            updateDocumentTypeUI(getSelectedDocumentType())
        }

        // Front Card buttons
        binding.btnCaptureFrontCamera.setOnClickListener {
            captureFromCamera(AttachmentSlot.FRONT)
        }
        binding.btnCaptureFrontGallery.setOnClickListener {
            pickFromGallery(AttachmentSlot.FRONT)
        }
        binding.btnRemoveFront.setOnClickListener {
            frontAttachment = null
            renderAttachmentsUI()
        }

        // Back Card buttons
        binding.btnCaptureBackCamera.setOnClickListener {
            captureFromCamera(AttachmentSlot.BACK)
        }
        binding.btnCaptureBackGallery.setOnClickListener {
            pickFromGallery(AttachmentSlot.BACK)
        }
        binding.btnRemoveBack.setOnClickListener {
            backAttachment = null
            renderAttachmentsUI()
        }

        // Visas: Add Visa Button
        binding.btnAddVisa.setOnClickListener {
            showAddVisaDialog()
        }

        // Other: Add Photo and PDF
        binding.btnAddOtherPhoto.setOnClickListener {
            showAddOtherPhotoDialog()
        }
        binding.btnAddOtherPdf.setOnClickListener {
            pickPdf(AttachmentSlot.OTHER)
        }

        // Save Document button
        binding.btnSaveDocument.setOnClickListener {
            validateAndSave()
        }
    }

    private fun updateDocumentTypeUI(type: DocumentType) {
        if (type == DocumentType.PASSPORT) {
            binding.tvFrontHeader.text = "Photo & Details Page"
            binding.tvBackHeader.text = "Back Cover / Signature"
            binding.layoutVisasSection.visibility = View.VISIBLE
        } else {
            binding.tvFrontHeader.text = "Front Side"
            binding.tvBackHeader.text = "Back Side (Barcode)"
            binding.layoutVisasSection.visibility = if (visaAttachments.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private var unlockedDek: SecretKey? = null

    private fun loadExistingDocument(docId: String) {
        lifecycleScope.launch {
            val doc = repository.getDocumentById(docId) ?: return@launch
            existingDocument = doc

            if (doc.isPasswordProtected) {
                promptPasswordToEdit(doc)
            } else {
                populateDocumentFields(doc)
            }
        }
    }

    private fun promptPasswordToEdit(doc: SecureDocument) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Enter document password"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Password Protected")
            .setMessage("This document is protected with a custom password. Enter it to edit:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                val password = input.text.toString()
                val salt = try {
                    Base64.getDecoder().decode(doc.passwordSaltBase64)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val passwordKey = DocumentPasswordCrypto.deriveKeyFromPassword(password.toCharArray(), salt)
                val dek = DocumentPasswordCrypto.decryptDek(doc.encryptedDekBase64, doc.dekIvBase64, passwordKey)

                if (dek != null) {
                    unlockedDek = dek
                    populateDocumentFields(doc)
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

    private fun populateDocumentFields(doc: SecureDocument) {
        binding.toolbar.title = "Edit Document"
        binding.etTitle.setText(doc.title)
        binding.etNumber.setText(doc.documentNumber)
        binding.etExpiry.setText(doc.expirationDate)
        binding.actvCountry.setText(doc.issuingCountry, false)
        binding.etNotes.setText(doc.notes)

        if (doc.isPasswordProtected) {
            binding.switchPasswordProtect.isChecked = true
            binding.layoutPasswordFields.visibility = View.VISIBLE
            binding.tilDocumentPassword.hint = "Change Password (leave blank to keep)"
            binding.tilConfirmDocumentPassword.hint = "Confirm New Password"
        }

        when (doc.type) {
            DocumentType.DRIVERS_LICENSE -> binding.chipDriversLicense.isChecked = true
            DocumentType.PASSPORT -> binding.chipPassport.isChecked = true
            DocumentType.ID_CARD -> binding.chipIdCard.isChecked = true
            DocumentType.INSURANCE -> binding.chipInsurance.isChecked = true
            DocumentType.PDF_DOCUMENT -> binding.chipPdf.isChecked = true
            DocumentType.OTHER -> binding.chipOther.isChecked = true
        }

        frontAttachment = null
        backAttachment = null
        visaAttachments.clear()
        otherAttachments.clear()

        doc.attachments.forEach { att ->
            val item = AttachmentItem.Existing(att)
            when (att.slot) {
                AttachmentSlot.FRONT -> {
                    if (frontAttachment == null) frontAttachment = item
                    else otherAttachments.add(item)
                }
                AttachmentSlot.BACK -> {
                    if (backAttachment == null) backAttachment = item
                    else otherAttachments.add(item)
                }
                AttachmentSlot.VISA -> visaAttachments.add(item)
                AttachmentSlot.OTHER -> otherAttachments.add(item)
            }
        }

        updateDocumentTypeUI(doc.type)
        renderAttachmentsUI()
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

    private fun captureFromCamera(slot: AttachmentSlot, visaTitle: String = "") {
        activeCaptureSlot = slot
        activeVisaTitle = visaTitle
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

    private fun pickFromGallery(slot: AttachmentSlot, visaTitle: String = "") {
        activeCaptureSlot = slot
        activeVisaTitle = visaTitle
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun pickPdf(slot: AttachmentSlot = AttachmentSlot.OTHER) {
        activeCaptureSlot = slot
        activeVisaTitle = ""
        pickDocumentLauncher.launch("*/*")
    }

    private fun showAddVisaDialog() {
        val input = EditText(this).apply {
            hint = "e.g. US Visa B1/B2, Schengen, Japan"
            setText("Visa ${visaAttachments.size + 1}")
            setSelectAllOnFocus(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Passport Visa")
            .setMessage("Enter a label for this visa or travel permit:")
            .setView(input)
            .setPositiveButton("Camera") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Visa ${visaAttachments.size + 1}" }
                captureFromCamera(AttachmentSlot.VISA, title)
            }
            .setNeutralButton("Gallery") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Visa ${visaAttachments.size + 1}" }
                pickFromGallery(AttachmentSlot.VISA, title)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddOtherPhotoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Additional Photo")
            .setItems(arrayOf("Take Photo with Camera", "Choose from Gallery")) { _, which ->
                if (which == 0) {
                    captureFromCamera(AttachmentSlot.OTHER)
                } else {
                    pickFromGallery(AttachmentSlot.OTHER)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameVisaDialog(item: AttachmentItem) {
        val input = EditText(this).apply {
            setText(item.customTitle.ifBlank { "Visa" })
            setSelectAllOnFocus(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Visa")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    item.customTitle = newTitle
                    renderAttachmentsUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                        val item = AttachmentItem.Pending(
                            slot = activeCaptureSlot,
                            customTitle = activeVisaTitle,
                            fileName = fileName,
                            mimeType = mimeType,
                            bytes = bytes
                        )
                        addAttachmentItem(item)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddDocumentActivity, "Failed to load file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addAttachmentItem(item: AttachmentItem) {
        when (item.slot) {
            AttachmentSlot.FRONT -> frontAttachment = item
            AttachmentSlot.BACK -> backAttachment = item
            AttachmentSlot.VISA -> visaAttachments.add(item)
            AttachmentSlot.OTHER -> otherAttachments.add(item)
        }
        renderAttachmentsUI()
    }

    private fun renderAttachmentsUI() {
        // 1. Render Front Side
        val front = frontAttachment
        if (front == null) {
            binding.layoutFrontEmpty.visibility = View.VISIBLE
            binding.layoutFrontFilled.visibility = View.GONE
        } else {
            binding.layoutFrontEmpty.visibility = View.GONE
            binding.layoutFrontFilled.visibility = View.VISIBLE
            binding.tvFrontFileName.text = "${front.fileName} (${formatFileSize(front.sizeBytes)})"
            displayThumbnail(front, binding.ivFrontPreview)
        }

        // 2. Render Back Side
        val back = backAttachment
        if (back == null) {
            binding.layoutBackEmpty.visibility = View.VISIBLE
            binding.layoutBackFilled.visibility = View.GONE
        } else {
            binding.layoutBackEmpty.visibility = View.GONE
            binding.layoutBackFilled.visibility = View.VISIBLE
            binding.tvBackFileName.text = "${back.fileName} (${formatFileSize(back.sizeBytes)})"
            displayThumbnail(back, binding.ivBackPreview)
        }

        // 3. Render Visas List
        binding.layoutVisasList.removeAllViews()
        visaAttachments.forEachIndexed { index, visa ->
            val itemBinding = ItemAttachmentChipBinding.inflate(layoutInflater, binding.layoutVisasList, false)
            itemBinding.tvAttachmentSlotBadge.text = "VISA ${index + 1}"
            itemBinding.tvFileName.text = visa.customTitle.ifBlank { "Visa ${index + 1}" }
            itemBinding.tvFileSize.text = "${formatFileSize(visa.sizeBytes)} • ${visa.fileName}"

            displayThumbnail(visa, itemBinding.ivAttachmentPreview)

            itemBinding.btnEditLabel.visibility = View.VISIBLE
            itemBinding.btnEditLabel.setOnClickListener {
                showRenameVisaDialog(visa)
            }

            itemBinding.btnRemoveAttachment.setOnClickListener {
                visaAttachments.removeAt(index)
                renderAttachmentsUI()
            }
            binding.layoutVisasList.addView(itemBinding.root)
        }

        // 4. Render Other Attachments List
        binding.layoutOtherList.removeAllViews()
        otherAttachments.forEachIndexed { index, other ->
            val itemBinding = ItemAttachmentChipBinding.inflate(layoutInflater, binding.layoutOtherList, false)
            val isPdf = other.mimeType.contains("pdf", ignoreCase = true) || other.fileName.endsWith(".pdf", ignoreCase = true)
            itemBinding.tvAttachmentSlotBadge.text = if (isPdf) "PDF" else "EXTRA"
            itemBinding.tvFileName.text = other.fileName
            itemBinding.tvFileSize.text = formatFileSize(other.sizeBytes)

            if (isPdf) {
                itemBinding.ivAttachmentPreview.setImageResource(R.drawable.ic_description)
            } else {
                displayThumbnail(other, itemBinding.ivAttachmentPreview)
            }

            itemBinding.btnEditLabel.visibility = View.GONE
            itemBinding.btnRemoveAttachment.setOnClickListener {
                otherAttachments.removeAt(index)
                renderAttachmentsUI()
            }
            binding.layoutOtherList.addView(itemBinding.root)
        }
    }

    private fun displayThumbnail(item: AttachmentItem, imageView: android.widget.ImageView) {
        when (item) {
            is AttachmentItem.Pending -> {
                if (item.mimeType.contains("image", ignoreCase = true) ||
                    item.fileName.endsWith(".jpg", ignoreCase = true) ||
                    item.fileName.endsWith(".png", ignoreCase = true)
                ) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                        } else {
                            imageView.setImageResource(R.drawable.ic_image)
                        }
                    } catch (e: Exception) {
                        imageView.setImageResource(R.drawable.ic_image)
                    }
                } else {
                    imageView.setImageResource(R.drawable.ic_description)
                }
            }
            is AttachmentItem.Existing -> {
                // Decrypt async
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = repository.readAttachmentBytes(item.docAttachment, unlockedDek)
                        if (bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            withContext(Dispatchers.Main) {
                                if (bitmap != null) {
                                    imageView.setImageBitmap(bitmap)
                                } else {
                                    imageView.setImageResource(R.drawable.ic_image)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
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

        val isPasswordProtect = binding.switchPasswordProtect.isChecked
        val enteredPassword = binding.etDocumentPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmDocumentPassword.text?.toString().orEmpty()

        if (isPasswordProtect) {
            val isKeepingExisting = existingDocument?.isPasswordProtected == true &&
                    enteredPassword.isBlank() &&
                    unlockedDek != null

            if (!isKeepingExisting) {
                if (enteredPassword.isBlank()) {
                    binding.tilDocumentPassword.error = "Password required"
                    return
                }
                if (enteredPassword != confirmPassword) {
                    binding.tilConfirmDocumentPassword.error = "Passwords do not match"
                    return
                }
            }
        }
        binding.tilDocumentPassword.error = null
        binding.tilConfirmDocumentPassword.error = null

        val docNumber = binding.etNumber.text?.toString()?.trim().orEmpty()
        val expiry = binding.etExpiry.text?.toString()?.trim().orEmpty()
        val country = binding.actvCountry.text?.toString()?.trim().orEmpty()
        val notes = binding.etNotes.text?.toString()?.trim().orEmpty()
        val docType = getSelectedDocumentType()

        binding.btnSaveDocument.isEnabled = false

        lifecycleScope.launch {
            try {
                var activeDek: SecretKey? = null
                var passwordSaltBase64 = ""
                var encryptedDekBase64 = ""
                var dekIvBase64 = ""

                if (isPasswordProtect) {
                    val isKeepingExisting = existingDocument?.isPasswordProtected == true &&
                            enteredPassword.isBlank() &&
                            unlockedDek != null

                    if (isKeepingExisting) {
                        activeDek = unlockedDek
                        passwordSaltBase64 = existingDocument!!.passwordSaltBase64
                        encryptedDekBase64 = existingDocument!!.encryptedDekBase64
                        dekIvBase64 = existingDocument!!.dekIvBase64
                    } else {
                        val salt = DocumentPasswordCrypto.generateSalt()
                        val passKey = DocumentPasswordCrypto.deriveKeyFromPassword(enteredPassword.toCharArray(), salt)
                        val newDek = DocumentPasswordCrypto.generateDek()
                        val (encDek, iv) = DocumentPasswordCrypto.encryptDek(newDek, passKey)

                        activeDek = newDek
                        passwordSaltBase64 = Base64.getEncoder().encodeToString(salt)
                        encryptedDekBase64 = encDek
                        dekIvBase64 = iv
                    }
                }

                val finalAttachments = mutableListOf<DocumentAttachment>()

                val allItems = mutableListOf<AttachmentItem>()
                frontAttachment?.let { allItems.add(it) }
                backAttachment?.let { allItems.add(it) }
                allItems.addAll(visaAttachments)
                allItems.addAll(otherAttachments)

                allItems.forEach { item ->
                    when (item) {
                        is AttachmentItem.Existing -> {
                            finalAttachments.add(
                                item.docAttachment.copy(
                                    slot = item.slot,
                                    customTitle = item.customTitle
                                )
                            )
                        }
                        is AttachmentItem.Pending -> {
                            val saved = repository.saveAttachmentBytes(
                                fileName = item.fileName,
                                mimeType = item.mimeType,
                                data = item.bytes,
                                slot = item.slot,
                                customTitle = item.customTitle,
                                customDek = activeDek
                            )
                            finalAttachments.add(saved)
                        }
                    }
                }

                val docToSave = existingDocument?.copy(
                    title = title,
                    type = docType,
                    documentNumber = docNumber,
                    expirationDate = expiry,
                    issuingCountry = country,
                    notes = notes,
                    attachments = finalAttachments,
                    isPasswordProtected = isPasswordProtect,
                    passwordSaltBase64 = passwordSaltBase64,
                    encryptedDekBase64 = encryptedDekBase64,
                    dekIvBase64 = dekIvBase64,
                    updatedAt = System.currentTimeMillis()
                ) ?: SecureDocument(
                    title = title,
                    type = docType,
                    documentNumber = docNumber,
                    expirationDate = expiry,
                    issuingCountry = country,
                    notes = notes,
                    attachments = finalAttachments,
                    isPasswordProtected = isPasswordProtect,
                    passwordSaltBase64 = passwordSaltBase64,
                    encryptedDekBase64 = encryptedDekBase64,
                    dekIvBase64 = dekIvBase64
                )

                val success = repository.saveDocument(docToSave)
                if (success) {
                    IssuingAuthorityHelper.recordNewAuthority(this@AddDocumentActivity, country)
                    Toast.makeText(this@AddDocumentActivity, "Document & photos securely saved!", Toast.LENGTH_SHORT).show()
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
