package com.mattintech.papercase.locker.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mattintech.papercase.R
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.crypto.BiometricAuthHelper
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.databinding.ActivityAddDocumentBinding
import com.mattintech.papercase.databinding.ItemAttachmentChipBinding
import com.mattintech.papercase.locker.crypto.DocumentPasswordCrypto
import com.mattintech.papercase.locker.crypto.LockerLockedException
import com.mattintech.papercase.locker.data.IssuingAuthorityHelper
import com.mattintech.papercase.locker.model.AttachmentSlot
import com.mattintech.papercase.locker.model.DocumentAttachment
import com.mattintech.papercase.locker.model.DocumentIcons
import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.reminders.ExpiryDates
import com.mattintech.papercase.locker.reminders.ReminderOptions
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import com.mattintech.papercase.locker.ui.crop.CropActivity
import java.io.File
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddDocumentActivity : AppCompatActivity() {

    companion object {
        /** Longest edge for photos shown in the crop screen (keeps memory use reasonable). */
        private const val MAX_PHOTO_EDGE = 3000
        const val EXTRA_DOCUMENT_ID = "extra_document_id"

        const val EXTRA_FOLDER_ID = "extra_folder_id"

        /** [folderId]: where a new document goes (the folder the user is in). */
        fun start(context: Context, documentId: String? = null, folderId: String = "") {
            val intent = Intent(context, AddDocumentActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ID, documentId)
                putExtra(EXTRA_FOLDER_ID, folderId)
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
    private lateinit var prefs: PreferencesManager

    /** Days before expiry to remind; follows the document type until the user picks one. */
    private var reminderDays = 0
    private var iconKey = ""
    private var iconColor = ""
    private var reminderChosen = false
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

    // Gallery picker: photos go through the crop screen
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (bytes != null) openCrop(bytes) else handleSelectedUri(uri)
                    }
                }
            }
        }

    // Crop screen: trims a photo to just the document
    private val cropLauncher =
        registerForActivityResult(CropActivity.Contract()) { bitmap ->
            SessionManager.keepAlive()
            if (bitmap != null) addPhotoBitmap(bitmap)
        }

    // PDF / generic file picker
    private val pickDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            if (uri != null) handleSelectedUri(uri)
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications are off, so reminders can't be shown. You can allow them in system settings.", Toast.LENGTH_LONG).show()
            }
            validateAndSave()
        }

    // Camera launcher
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCameraCapture()
            } else {
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
            }
        }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            val photoFile = tempPhotoFile
            tempPhotoFile = null
            if (photoFile == null) return@registerForActivityResult
            if (!success || !photoFile.exists()) {
                photoFile.delete()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val bytes = try {
                    photoFile.readBytes()
                } finally {
                    photoFile.delete()
                }
                withContext(Dispatchers.Main) { openCrop(bytes) }
            }
        }

    /** Decodes a photo (upright, at a manageable size) and opens the crop screen. */
    private fun openCrop(bytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = OrientedBitmap.decode(bytes)?.let(::limitSize)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    cropLauncher.launch(bitmap)
                } else {
                    Toast.makeText(this@AddDocumentActivity, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun limitSize(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= MAX_PHOTO_EDGE) return b
        val f = MAX_PHOTO_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true).also { if (it !== b) b.recycle() }
    }

    /** Adds a cropped photo. Re-encoding from pixels means no metadata (GPS, camera details) survives. */
    private fun addPhotoBitmap(bitmap: Bitmap) {
        val slot = activeCaptureSlot
        val title = activeVisaTitle
        lifecycleScope.launch(Dispatchers.IO) {
            val out = java.io.ByteArrayOutputStream()
            val photo = ImageMetadataStripper.shrink(bitmap)
            photo.compress(Bitmap.CompressFormat.JPEG, ImageMetadataStripper.JPEG_QUALITY, out)
            withContext(Dispatchers.Main) {
                addAttachmentItem(
                    AttachmentItem.Pending(
                        slot = slot,
                        customTitle = title,
                        fileName = "Photo_${System.currentTimeMillis()}.jpg",
                        mimeType = "image/jpeg",
                        bytes = out.toByteArray()
                    )
                )
                if (slot == AttachmentSlot.OTHER) promptNextPage()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots in release builds; allow in debug builds for layout inspection
        if (!com.mattintech.papercase.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        binding = ActivityAddDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SecureLockerRepository(this)
        prefs = PreferencesManager(this)
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
        setupReminderField()
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

        binding.btnChangeIcon.setOnClickListener {
            IconPickerSheet(this, getSelectedDocumentType().iconRes, iconKey, iconColor) { key, colour ->
                iconKey = key
                iconColor = colour
                renderIcon()
            }.show()
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
            if (shouldAskAboutReminders()) askAboutReminders() else validateAndSave()
        }
    }

    private fun setupReminderField() {
        val labels = ReminderOptions.ALL.map { it.label }
        binding.actvReminder.setSimpleItems(labels.toTypedArray())
        binding.actvReminder.setOnItemClickListener { _, _, position, _ ->
            reminderDays = ReminderOptions.ALL[position].days
            reminderChosen = true
            updateReminderHelper()
        }
        setReminder(ReminderOptions.defaultFor(getSelectedDocumentType()))
    }

    private fun setReminder(days: Int) {
        reminderDays = days
        binding.actvReminder.setText(ReminderOptions.label(days), false)
        updateReminderHelper()
    }

    private fun updateReminderHelper() {
        val expiry = ExpiryDates.parse(binding.etExpiry.text?.toString().orEmpty())
        binding.tilReminder.helperText = when {
            reminderDays == 0 -> null
            expiry == null -> "Add an expiration date to get a reminder"
            else -> {
                val on = expiry.minusDays(reminderDays.toLong())
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                if (prefs.isExpiryRemindersEnabled) "You'll be reminded on $on" else "Reminder on $on (reminders are off in Settings)"
            }
        }
    }

    /** Ask once, the first time someone saves a document with a reminder. */
    private fun shouldAskAboutReminders(): Boolean =
        reminderDays > 0 &&
            ExpiryDates.parse(binding.etExpiry.text?.toString().orEmpty()) != null &&
            !prefs.isExpiryRemindersEnabled &&
            !prefs.hasAskedAboutExpiryReminders &&
            binding.etTitle.text?.isNotBlank() == true

    private fun askAboutReminders() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Get expiry reminders?")
            .setMessage(
                "Papercase can send you a notification before your documents expire, so you have time to renew them. " +
                    "You choose how early on each document.\n\n" +
                    "On the lock screen the notification only says that a document expires soon."
            )
            .setPositiveButton("Turn on") { _, _ ->
                prefs.isExpiryRemindersEnabled = true
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    validateAndSave()
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.hasAskedAboutExpiryReminders = true
                validateAndSave()
            }
            .show()
    }

    /** Until an icon is chosen, the icon follows the document type. */
    private fun renderIcon() {
        DocumentIcons.apply(binding.ivDocIcon, DocumentIcons.iconRes(iconKey, getSelectedDocumentType()), iconColor)
    }

    private fun updateDocumentTypeUI(type: DocumentType) {
        renderIcon()
        if (!reminderChosen && editingDocumentId == null && ::prefs.isInitialized && binding.actvReminder.adapter != null) {
            setReminder(ReminderOptions.defaultFor(type))
        }
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
            val doc = try {
                repository.getDocumentById(docId)
            } catch (e: Exception) {
                // Locker key needs a fresh unlock (or was lost): go back to the locker screen
                Toast.makeText(this@AddDocumentActivity, "Locker locked. Unlock again to continue.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            } ?: return@launch
            existingDocument = doc

            if (doc.isPasswordProtected) {
                promptPasswordToEdit(doc)
            } else {
                populateDocumentFields(doc)
            }
        }
    }

    private fun promptPasswordToEdit(doc: SecureDocument) {
        val input = DialogInput(this, hint = "Document password", password = true)

        MaterialAlertDialogBuilder(this)
            .setTitle("Password Protected")
            .setMessage("This document is protected with a custom password. Enter it to edit:")
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
        iconKey = doc.iconKey
        iconColor = doc.iconColor
        binding.etNumber.setText(doc.documentNumber)
        binding.etExpiry.setText(doc.expirationDate)
        reminderChosen = true
        setReminder(doc.reminderDays)
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
        // MaterialDatePicker works in UTC: the selection is midnight UTC of the picked day.
        // Formatting it in local time would shift the date back a day west of UTC.
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val current = binding.etExpiry.text?.toString()?.trim().orEmpty()
        val currentMs = runCatching { utcFormatter.parse(current)?.time }.getOrNull()

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Expiration Date")
            .apply { if (currentMs != null) setSelection(currentMs) }
            .build()

        picker.addOnPositiveButtonClickListener { selectionMs ->
            val formattedDate = utcFormatter.format(Date(selectionMs))
            binding.etExpiry.setText(formattedDate)
            updateReminderHelper()
        }

        picker.show(supportFragmentManager, "EXPIRY_PICKER")
    }

    private fun captureFromCamera(slot: AttachmentSlot, visaTitle: String = "") {
        activeCaptureSlot = slot
        activeVisaTitle = visaTitle
        // The photo then opens in the crop screen
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCapture() {
        try {
            val cameraDir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
            tempPhotoFile = File(cameraDir, "doc_${System.currentTimeMillis()}.jpg")
            tempPhotoUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                tempPhotoFile!!
            )
            SessionManager.isExternalOperationActive = true
            takePhotoLauncher.launch(tempPhotoUri!!)
        } catch (e: Exception) {
            SessionManager.isExternalOperationActive = false
            Toast.makeText(this, "Could not open camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFromGallery(slot: AttachmentSlot, visaTitle: String = "") {
        activeCaptureSlot = slot
        activeVisaTitle = visaTitle
        SessionManager.isExternalOperationActive = true
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun pickPdf(slot: AttachmentSlot = AttachmentSlot.OTHER) {
        activeCaptureSlot = slot
        activeVisaTitle = ""
        SessionManager.isExternalOperationActive = true
        pickDocumentLauncher.launch("*/*")
    }

    private fun showAddVisaDialog() {
        val input = DialogInput(this, hint = "e.g. US Visa B1/B2, Schengen, Japan")
        input.editText.setText("Visa ${visaAttachments.size + 1}")
        input.editText.setSelectAllOnFocus(true)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Passport Visa")
            .setMessage("Enter a label for this visa or travel permit:")
            .setView(input.view)
            .setPositiveButton("Camera") { _, _ ->
                val title = input.text.trim().ifBlank { "Visa ${visaAttachments.size + 1}" }
                captureFromCamera(AttachmentSlot.VISA, title)
            }
            .setNeutralButton("Gallery") { _, _ ->
                val title = input.text.trim().ifBlank { "Visa ${visaAttachments.size + 1}" }
                pickFromGallery(AttachmentSlot.VISA, title)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddOtherPhotoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add page ${pageCount() + 1}")
            .setItems(arrayOf("Take photo", "Choose from gallery")) { _, which ->
                if (which == 0) {
                    captureFromCamera(AttachmentSlot.OTHER)
                } else {
                    pickFromGallery(AttachmentSlot.OTHER)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** After each page: keep going for multi-page documents. */
    private fun promptNextPage() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Page ${pageCount()} added")
            .setMessage("Add the next page of this document?")
            .setPositiveButton("Take photo") { _, _ -> captureFromCamera(AttachmentSlot.OTHER) }
            .setNeutralButton("Gallery") { _, _ -> pickFromGallery(AttachmentSlot.OTHER) }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun AttachmentItem.isPdfItem() =
        mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)

    private fun pageCount() = otherAttachments.count { !it.isPdfItem() }

    private fun showRenameVisaDialog(item: AttachmentItem) {
        val input = DialogInput(this, hint = "Visa label")
        input.editText.setText(item.customTitle.ifBlank { "Visa" })
        input.editText.setSelectAllOnFocus(true)

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Visa")
            .setView(input.view)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.trim()
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
                    // Strip GPS & other EXIF metadata from picked images (PDFs pass through unchanged)
                    val clean = ImageMetadataStripper.strip(bytes, mimeType, fileName)
                    withContext(Dispatchers.Main) {
                        val item = AttachmentItem.Pending(
                            slot = activeCaptureSlot,
                            customTitle = activeVisaTitle,
                            fileName = clean.fileName,
                            mimeType = clean.mimeType,
                            bytes = clean.bytes
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
        var pageNumber = 0
        otherAttachments.forEachIndexed { index, other ->
            val itemBinding = ItemAttachmentChipBinding.inflate(layoutInflater, binding.layoutOtherList, false)
            val isPdf = other.isPdfItem()
            if (isPdf) {
                itemBinding.tvAttachmentSlotBadge.text = "PDF"
                itemBinding.tvFileName.text = other.fileName
            } else {
                pageNumber++
                itemBinding.tvAttachmentSlotBadge.text = "PAGE $pageNumber"
                itemBinding.tvFileName.text = "Page $pageNumber"
            }
            itemBinding.tvFileSize.text = formatFileSize(other.sizeBytes)

            // Reorder pages (and files) within the document
            if (index > 0) {
                itemBinding.btnMoveUp.visibility = View.VISIBLE
                itemBinding.btnMoveUp.setOnClickListener {
                    otherAttachments.add(index - 1, otherAttachments.removeAt(index))
                    renderAttachmentsUI()
                }
            }

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
                        val bitmap = OrientedBitmap.decode(item.bytes)
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
                            val bitmap = OrientedBitmap.decode(bytes)
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
                val replacedAttachments = mutableListOf<DocumentAttachment>()

                // Key the existing attachments are encrypted with (null = locker key)
                val previousDek = if (existingDocument?.isPasswordProtected == true) unlockedDek else null

                val allItems = mutableListOf<AttachmentItem>()
                frontAttachment?.let { allItems.add(it) }
                backAttachment?.let { allItems.add(it) }
                allItems.addAll(visaAttachments)
                allItems.addAll(otherAttachments)

                allItems.forEach { item ->
                    when (item) {
                        is AttachmentItem.Existing -> {
                            if (activeDek === previousDek) {
                                finalAttachments.add(
                                    item.docAttachment.copy(
                                        slot = item.slot,
                                        customTitle = item.customTitle
                                    )
                                )
                            } else {
                                // Password added, changed, or removed: re-encrypt so every attachment
                                // uses the document's current key
                                val plain = repository.readAttachmentBytes(item.docAttachment, previousDek)
                                finalAttachments.add(
                                    repository.saveAttachmentBytes(
                                        fileName = item.docAttachment.fileName,
                                        mimeType = item.docAttachment.mimeType,
                                        data = plain,
                                        slot = item.slot,
                                        customTitle = item.customTitle,
                                        customDek = activeDek
                                    )
                                )
                                replacedAttachments.add(item.docAttachment)
                            }
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
                    reminderDays = reminderDays,
                    iconKey = iconKey,
                    iconColor = iconColor,
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
                    dekIvBase64 = dekIvBase64,
                    reminderDays = reminderDays,
                    iconKey = iconKey,
                    iconColor = iconColor,
                    folderId = intent.getStringExtra(EXTRA_FOLDER_ID).orEmpty()
                )

                val success = repository.saveDocument(docToSave)
                if (success) {
                    replacedAttachments.forEach { repository.deleteAttachmentFile(it) }
                    IssuingAuthorityHelper.recordNewAuthority(this@AddDocumentActivity, country)
                    Toast.makeText(this@AddDocumentActivity, "Document & photos securely saved!", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@AddDocumentActivity, "Failed to save document", Toast.LENGTH_LONG).show()
                    binding.btnSaveDocument.isEnabled = true
                }
            } catch (e: LockerLockedException) {
                // The locker key only works for a while after the last unlock, even while editing.
                // Ask again and retry, so nothing typed or captured is lost.
                binding.btnSaveDocument.isEnabled = true
                unlockAndRetrySave()
            } catch (e: Exception) {
                Toast.makeText(this@AddDocumentActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                binding.btnSaveDocument.isEnabled = true
            }
        }
    }

    private fun unlockAndRetrySave() {
        BiometricAuthHelper.promptGeneralUnlock(
            activity = this,
            title = "Unlock to save",
            subtitle = "The locker locked while you were editing. Your changes are kept.",
            onSuccess = {
                SessionManager.markAuthenticated()
                validateAndSave()
            },
            onError = { _, _ ->
                Toast.makeText(this, "Not saved. Tap Save to try again.", Toast.LENGTH_LONG).show()
            }
        )
    }
}
