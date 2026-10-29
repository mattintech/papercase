package com.mattintech.securepass

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mattintech.securepass.auth.SessionManager
import com.mattintech.securepass.clipboard.ClipboardSecurityManager
import com.mattintech.securepass.crypto.BiometricAuthHelper
import com.mattintech.securepass.crypto.KeystoreHelper
import com.mattintech.securepass.data.PreferencesManager
import com.mattintech.securepass.databinding.ActivityMainBinding
import com.mattintech.securepass.databinding.DialogP2pSenderBinding
import com.mattintech.securepass.databinding.DialogSetPassphraseBinding
import com.mattintech.securepass.databinding.DialogSyncConflictsBinding
import com.mattintech.securepass.databinding.DialogViewPassphraseBinding
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import com.mattintech.securepass.locker.repository.SecureLockerRepository
import com.mattintech.securepass.locker.transfer.ConflictResolutionStrategy
import com.mattintech.securepass.locker.transfer.P2pNetworkHelper
import com.mattintech.securepass.locker.transfer.P2pTransferClient
import com.mattintech.securepass.locker.transfer.P2pTransferServer
import com.mattintech.securepass.locker.transfer.QrCodeHelper
import com.mattintech.securepass.locker.transfer.VaultConflictReport
import com.mattintech.securepass.locker.transfer.VaultExportImportManager
import com.mattintech.securepass.locker.ui.AddDocumentActivity
import com.mattintech.securepass.locker.ui.DocumentViewerActivity
import com.mattintech.securepass.locker.ui.SecureDocumentsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_TRIGGER_COPY = "com.mattintech.securepass.ACTION_TRIGGER_COPY"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var lockerRepository: SecureLockerRepository
    private lateinit var documentsAdapter: SecureDocumentsAdapter

    private var allDocuments: List<SecureDocument> = emptyList()
    private var isLockerUnlocked: Boolean
        get() = SessionManager.isLockerUnlocked
        set(value) {
            SessionManager.isLockerUnlocked = value
        }
    private var hasHandledAutoLaunch = false
    private var activeTokenInput: android.widget.EditText? = null
    private var activeP2pServer: P2pTransferServer? = null

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Notification permission handled
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchQrScanner()
            } else {
                Toast.makeText(this, "Camera permission required to scan QR code", Toast.LENGTH_SHORT).show()
            }
        }

    private val barcodeLauncher =
        registerForActivityResult(ScanContract()) { result ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            if (result.contents != null) {
                handleScannedQrContent(result.contents)
            }
        }

    private val pickVaultFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            if (uri != null) {
                showImportTokenDialog(uri)
            }
        }

    private val pickQrImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            SessionManager.isExternalOperationActive = false
            SessionManager.keepAlive()
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        val scannedContent = if (bitmap != null) QrCodeHelper.decodeQrCodeFromBitmap(bitmap) else null
                        withContext(Dispatchers.Main) {
                            if (scannedContent != null) {
                                handleScannedQrContent(scannedContent)
                            } else {
                                Toast.makeText(this@MainActivity, "Could not find a valid QR code in selected image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to read QR image: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        lockerRepository = SecureLockerRepository(this)

        setupWindowInsets()
        setupUI()
        setupListeners()
        setupLocker()
        observeClipboardState()
        checkNotificationPermission()

        if (savedInstanceState == null) {
            handleIncomingIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardSecurityManager.checkAndClearIfExpired(this)
        updateVaultStatusUI()

        if (!SessionManager.isSessionValid(getSessionTimeoutMs())) {
            isLockerUnlocked = false
            if (binding.layoutSecureLocker.visibility == View.VISIBLE) {
                showPassphraseVaultTab()
                binding.bottomNavigation.selectedItemId = R.id.nav_passphrase
            }
        } else {
            isLockerUnlocked = true
            if (binding.layoutSecureLocker.visibility == View.VISIBLE) {
                loadSecureLockerDocuments()
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.keepAlive()
    }

    private fun getSessionTimeoutMs(): Long = prefs.lockerTimeoutMinutes * 60 * 1000L

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent()
    }

    private fun handleIncomingIntent() {
        if (!prefs.hasStoredPassphrase) return

        val isTileAction = intent?.action == ACTION_TRIGGER_COPY
        if (isTileAction) {
            triggerUnlockAndCopy(forcedFromTile = true)
        } else if (prefs.isAutoCopyEnabled && !hasHandledAutoLaunch) {
            hasHandledAutoLaunch = true
            triggerUnlockAndCopy(forcedFromTile = false)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.bottomNavigation.updatePadding(
                bottom = navBarInsets.bottom,
                left = navBarInsets.left,
                right = navBarInsets.right
            )
            binding.bottomNavigation.post {
                val navHeight = binding.bottomNavigation.height
                binding.contentFrame.updatePadding(bottom = navHeight)
                val extraMargin = (16 * resources.displayMetrics.density).toInt()
                binding.fabAddDocument.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = navHeight + extraMargin
                    rightMargin = navBarInsets.right + extraMargin
                }
            }
            insets
        }
    }

    private fun setupUI() {
        // Initialize switches with saved preferences
        binding.switchAutoCopy.isChecked = prefs.isAutoCopyEnabled
        binding.switchAutoMinimize.isChecked = prefs.isAutoMinimizeEnabled
        binding.switchAutoClear.isChecked = prefs.isAutoClearEnabled

        // Initialize timeout chips
        when (prefs.clearTimeoutSeconds) {
            5 -> binding.chip5s.isChecked = true
            15 -> binding.chip15s.isChecked = true
            30 -> binding.chip30s.isChecked = true
            else -> binding.chip10s.isChecked = true
        }
        binding.layoutTimeoutSelection.visibility =
            if (prefs.isAutoClearEnabled) View.VISIBLE else View.GONE

        // Samsung Clipboard Advisory
        val isSamsung = isSamsungDevice()
        if (isSamsung) {
            binding.cardSamsungAlert.visibility =
                if (!prefs.isSamsungClipboardWarningDismissed) View.VISIBLE else View.GONE
            binding.layoutSamsungClipboardRow.visibility = View.VISIBLE
            binding.dividerSamsungRow.visibility = View.VISIBLE
        } else {
            binding.cardSamsungAlert.visibility = View.GONE
            binding.layoutSamsungClipboardRow.visibility = View.GONE
            binding.dividerSamsungRow.visibility = View.GONE
        }

        binding.btnDismissSamsungAlert.setOnClickListener {
            prefs.isSamsungClipboardWarningDismissed = true
            binding.cardSamsungAlert.visibility = View.GONE
        }

        binding.btnFixSamsungSettings.setOnClickListener {
            showSamsungClipboardDialog()
        }

        binding.layoutSamsungClipboardRow.setOnClickListener {
            showSamsungClipboardDialog()
        }

        updateVaultStatusUI()
    }

    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
                Build.BRAND.equals("samsung", ignoreCase = true)
    }

    private fun showSamsungClipboardDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Samsung Clipboard History")
            .setMessage(
                "Samsung One UI maintains an internal clipboard history tray in Samsung Keyboard that retains copied text even after SecurePass wipes the Android clipboard.\n\n" +
                "To ensure complete privacy for your credentials:\n\n" +
                "1. Tap 'Open Keyboard Settings' below.\n" +
                "2. Tap 'Samsung Keyboard'.\n" +
                "3. Tap 'Clipboard' (or 'Suggest text replacements').\n" +
                "4. Turn off 'Clipboard history'.\n\n" +
                "Tip: You can also set Gboard or an open-source keyboard as your default."
            )
            .setPositiveButton("Open Keyboard Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Got It", null)
            .show()
    }

    private fun setupListeners() {
        // Unlock & Copy Button
        binding.btnUnlockCopy.setOnClickListener {
            triggerUnlockAndCopy(forcedFromTile = false)
        }

        // Initial Setup Button (when no passphrase set)
        binding.btnInitialSetup.setOnClickListener {
            showSetPassphraseDialog()
        }

        // View Passphrase Button
        binding.btnViewPassphrase.setOnClickListener {
            triggerViewPassphrase()
        }

        // Edit Passphrase Button
        binding.btnEditPassphrase.setOnClickListener {
            showSetPassphraseDialog()
        }

        // Delete Passphrase Button
        binding.btnDeletePassphrase.setOnClickListener {
            showDeleteConfirmDialog()
        }

        // Clear Clipboard Now Button
        binding.btnClearClipboardNow.setOnClickListener {
            ClipboardSecurityManager.clearClipboard(this)
            Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        }

        // Help / Info Button
        binding.btnHelp.setOnClickListener {
            showAboutDialog()
        }

        // Lock Locker Immediately Button
        binding.btnLockLocker.setOnClickListener {
            SessionManager.lock()
            isLockerUnlocked = false
            showPassphraseVaultTab()
            binding.bottomNavigation.selectedItemId = R.id.nav_passphrase
            Toast.makeText(this, "Secure Locker locked", Toast.LENGTH_SHORT).show()
        }

        // Vault Transfer (Export / Import) Button
        binding.btnVaultTransfer.setOnClickListener {
            showVaultTransferOptions()
        }

        // Settings Switches
        binding.switchAutoCopy.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoCopyEnabled = isChecked
        }

        binding.switchAutoMinimize.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoMinimizeEnabled = isChecked
        }

        binding.switchAutoClear.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoClearEnabled = isChecked
            binding.layoutTimeoutSelection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Timeout Chips
        binding.chipGroupTimeout.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val seconds = when (checkedIds.first()) {
                R.id.chip5s -> 5
                R.id.chip15s -> 15
                R.id.chip30s -> 30
                else -> 10
            }
            prefs.clearTimeoutSeconds = seconds
        }

        // Bottom Navigation
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_passphrase -> {
                    showPassphraseVaultTab()
                    true
                }
                R.id.nav_locker -> {
                    checkAndShowLockerTab()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupLocker() {
        documentsAdapter = SecureDocumentsAdapter(
            onItemClick = { doc ->
                DocumentViewerActivity.start(this, doc.id)
            },
            onCopyNumber = { doc ->
                val timeout = if (prefs.isAutoClearEnabled) prefs.clearTimeoutSeconds else 0
                ClipboardSecurityManager.copyToClipboard(
                    context = this,
                    passphrase = doc.documentNumber,
                    timeoutSeconds = timeout,
                    label = "Document Number"
                )
                Toast.makeText(this, "Copied ${doc.documentNumber}", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvSecureDocuments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = documentsAdapter
        }

        binding.fabAddDocument.setOnClickListener {
            AddDocumentActivity.start(this)
        }

        binding.btnAddFirstDocument.setOnClickListener {
            AddDocumentActivity.start(this)
        }

        // Filter chips
        binding.chipGroupLockerFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filterType: DocumentType? = when (checkedIds.firstOrNull()) {
                R.id.filterLicense -> DocumentType.DRIVERS_LICENSE
                R.id.filterPassport -> DocumentType.PASSPORT
                R.id.filterIdCards -> DocumentType.ID_CARD
                R.id.filterDocs -> DocumentType.PDF_DOCUMENT
                else -> null
            }
            filterDocuments(filterType)
        }
    }

    private fun showPassphraseVaultTab() {
        binding.nestedScrollView.visibility = View.VISIBLE
        binding.layoutSecureLocker.visibility = View.GONE
        binding.fabAddDocument.hide()
        binding.ivToolbarLogo.setImageResource(R.drawable.ic_shield_check)
        binding.tvToolbarTitle.text = getString(R.string.app_name)
        binding.btnLockLocker.visibility = View.GONE
        binding.btnVaultTransfer.visibility = View.GONE
    }

    private fun checkAndShowLockerTab() {
        if (isLockerUnlocked || SessionManager.isSessionValid(getSessionTimeoutMs())) {
            isLockerUnlocked = true
            displayLockerTab()
            return
        }

        // Biometric challenge to unlock personal vault (OneDrive Personal Vault style)
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            SessionManager.markAuthenticated()
            isLockerUnlocked = true
            displayLockerTab()
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Unlock Secure Locker",
                subtitle = "Confirm fingerprint to access your passport, license & documents",
                cipher = encryptCipher,
                onSuccess = {
                    SessionManager.markAuthenticated()
                    isLockerUnlocked = true
                    displayLockerTab()
                },
                onError = { _, _ ->
                    // Revert tab back to passphrase
                    binding.bottomNavigation.selectedItemId = R.id.nav_passphrase
                }
            )
        } catch (e: Exception) {
            SessionManager.markAuthenticated()
            isLockerUnlocked = true
            displayLockerTab()
        }
    }

    private fun displayLockerTab() {
        binding.nestedScrollView.visibility = View.GONE
        binding.layoutSecureLocker.visibility = View.VISIBLE
        binding.fabAddDocument.show()
        binding.ivToolbarLogo.setImageResource(R.drawable.ic_folder_lock)
        binding.tvToolbarTitle.text = "Secure Locker"
        binding.btnLockLocker.visibility = View.VISIBLE
        binding.btnVaultTransfer.visibility = View.VISIBLE
        loadSecureLockerDocuments()
    }

    private fun loadSecureLockerDocuments() {
        lifecycleScope.launch {
            allDocuments = lockerRepository.getAllDocuments()
            filterDocuments(null)
        }
    }

    private fun filterDocuments(type: DocumentType?) {
        val filtered = if (type == null) {
            allDocuments
        } else {
            allDocuments.filter { it.type == type }
        }

        documentsAdapter.submitList(filtered)
        if (filtered.isEmpty()) {
            binding.cardEmptyLocker.visibility = View.VISIBLE
            binding.rvSecureDocuments.visibility = View.GONE
        } else {
            binding.cardEmptyLocker.visibility = View.GONE
            binding.rvSecureDocuments.visibility = View.VISIBLE
        }
    }

    private fun updateVaultStatusUI() {
        val hasPass = prefs.hasStoredPassphrase
        if (hasPass) {
            binding.tvVaultTitle.text = getString(R.string.status_protected)
            binding.tvVaultSubtitle.text = getString(R.string.status_protected_desc)
            binding.ivVaultIcon.setImageResource(R.drawable.ic_shield_lock)
            binding.btnUnlockCopy.visibility = View.VISIBLE
            binding.layoutVaultActions.visibility = View.VISIBLE
            binding.btnInitialSetup.visibility = View.GONE
        } else {
            binding.tvVaultTitle.text = getString(R.string.status_not_set)
            binding.tvVaultSubtitle.text = getString(R.string.status_not_set_desc)
            binding.ivVaultIcon.setImageResource(R.drawable.ic_lock)
            binding.btnUnlockCopy.visibility = View.GONE
            binding.layoutVaultActions.visibility = View.GONE
            binding.btnInitialSetup.visibility = View.VISIBLE
        }
    }

    private fun observeClipboardState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClipboardSecurityManager.countdownRemaining.collect { remaining ->
                    if (remaining != null && remaining > 0) {
                        binding.cardClipboardActive.visibility = View.VISIBLE
                        binding.tvClipboardCountdown.text =
                            getString(R.string.clipboard_active_desc, remaining)
                        val total = ClipboardSecurityManager.countdownTotal.value.coerceAtLeast(1)
                        val progressPercent = (remaining * 100) / total
                        binding.progressCountdown.setProgressCompat(progressPercent, true)
                    } else {
                        binding.cardClipboardActive.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun triggerUnlockAndCopy(forcedFromTile: Boolean) {
        val payload = prefs.getEncryptedPayload()
        if (payload == null) {
            Toast.makeText(this, "No passphrase stored. Please set one first.", Toast.LENGTH_SHORT).show()
            showSetPassphraseDialog()
            return
        }

        val biometricStatus = BiometricAuthHelper.checkBiometricAvailability(this)
        if (biometricStatus !is BiometricAuthHelper.BiometricStatus.Ready) {
            showBiometricUnavailableDialog(biometricStatus)
            return
        }

        try {
            val decryptCipher = KeystoreHelper.initDecryptCipher(payload.iv)
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = getString(R.string.biometric_copy_title),
                subtitle = getString(R.string.biometric_copy_subtitle),
                cipher = decryptCipher,
                onSuccess = { authenticatedCipher ->
                    try {
                        SessionManager.markAuthenticated()
                        isLockerUnlocked = true
                        val decryptedPassphrase =
                            KeystoreHelper.decryptWithCipher(authenticatedCipher, payload.ciphertext)

                        val timeout = if (prefs.isAutoClearEnabled) prefs.clearTimeoutSeconds else 0
                        ClipboardSecurityManager.copyToClipboard(
                            context = this@MainActivity,
                            passphrase = decryptedPassphrase,
                            timeoutSeconds = timeout
                        )

                        Toast.makeText(this@MainActivity, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()

                        if (forcedFromTile || prefs.isAutoMinimizeEnabled) {
                            moveTaskToBack(true)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Decryption error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                onError = { errorCode, errorMsg ->
                    if (errorCode != 13) {
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            showKeyInvalidatedDialog()
        } catch (e: Exception) {
            Toast.makeText(this, "Keystore error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerViewPassphrase() {
        val payload = prefs.getEncryptedPayload() ?: return

        try {
            val decryptCipher = KeystoreHelper.initDecryptCipher(payload.iv)
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = getString(R.string.biometric_view_title),
                subtitle = getString(R.string.biometric_view_subtitle),
                cipher = decryptCipher,
                onSuccess = { authenticatedCipher ->
                    SessionManager.markAuthenticated()
                    isLockerUnlocked = true
                    val decryptedPassphrase =
                        KeystoreHelper.decryptWithCipher(authenticatedCipher, payload.ciphertext)
                    showDecryptedPassphraseDialog(decryptedPassphrase)
                },
                onError = { _, errorMsg ->
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            showKeyInvalidatedDialog()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSetPassphraseDialog() {
        val dialogBinding = DialogSetPassphraseBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_set_passphrase)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val entered = dialogBinding.etPassphrase.text?.toString().orEmpty()
                if (entered.isBlank()) {
                    Toast.makeText(this, "Passphrase cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                savePassphraseWithBiometrics(entered)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun savePassphraseWithBiometrics(passphrase: String) {
        val biometricStatus = BiometricAuthHelper.checkBiometricAvailability(this)
        if (biometricStatus !is BiometricAuthHelper.BiometricStatus.Ready) {
            showBiometricUnavailableDialog(biometricStatus)
            return
        }

        try {
            KeystoreHelper.generateKey()
            val encryptCipher = KeystoreHelper.initEncryptCipher()

            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = getString(R.string.biometric_save_title),
                subtitle = getString(R.string.biometric_save_subtitle),
                cipher = encryptCipher,
                onSuccess = { authenticatedCipher ->
                    try {
                        SessionManager.markAuthenticated()
                        isLockerUnlocked = true
                        val payload = KeystoreHelper.encryptWithCipher(authenticatedCipher, passphrase)
                        prefs.saveEncryptedPassphrase(payload)
                        updateVaultStatusUI()
                        Toast.makeText(this, "Passphrase securely stored in keystore!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                onError = { _, errorMsg ->
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing encryption key: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDecryptedPassphraseDialog(passphrase: String) {
        val dialogBinding = DialogViewPassphraseBinding.inflate(layoutInflater)
        dialogBinding.tvDecryptedPassphrase.text = passphrase

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.biometric_view_title)
            .setView(dialogBinding.root)
            .setPositiveButton("Copy") { _, _ ->
                val timeout = if (prefs.isAutoClearEnabled) prefs.clearTimeoutSeconds else 0
                ClipboardSecurityManager.copyToClipboard(this, passphrase, timeout)
                Toast.makeText(this, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeleteConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Passphrase?")
            .setMessage("This will erase the stored passphrase and destroy the hardware encryption key from Android Keystore. This cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                prefs.clearStoredPassphrase()
                ClipboardSecurityManager.clearClipboard(this)
                updateVaultStatusUI()
                Toast.makeText(this, "Passphrase deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeyInvalidatedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Biometric Key Invalidated")
            .setMessage("Android detected a change to your enrolled fingerprints. For your security, the hardware key was invalidated. Please re-enter and save your passphrase.")
            .setPositiveButton("Re-enter Passphrase") { _, _ ->
                showSetPassphraseDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBiometricUnavailableDialog(status: BiometricAuthHelper.BiometricStatus) {
        val message = when (status) {
            is BiometricAuthHelper.BiometricStatus.NoneEnrolled ->
                "No fingerprints are currently enrolled on this device. Please enroll a fingerprint in Android Settings to use SecurePass."
            is BiometricAuthHelper.BiometricStatus.NoHardware ->
                "This device does not have biometric hardware."
            else ->
                "Biometric authentication is currently unavailable."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Fingerprint Required")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAboutDialog() {
        val aboutView = layoutInflater.inflate(R.layout.dialog_about, null)
        MaterialAlertDialogBuilder(this)
            .setView(aboutView)
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showVaultTransferOptions() {
        val options = arrayOf(
            "📡 Send to Nearby Device (Local Wi-Fi P2P)\n    Direct device-to-device stream over Wi-Fi",
            "📥 Receive from Nearby Device (Scan QR)\n    Scan sender's QR code to pull & sync vault",
            "📤 Export Vault to File (.spvault)\n    Save encrypted backup file or share",
            "📂 Import Vault from File (.spvault)\n    Restore or merge a .spvault backup file"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Vault Transfer & Sync")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startP2pSendFlow()
                    1 -> checkCameraAndLaunchScanner()
                    2 -> promptBiometricForExport()
                    3 -> promptBiometricForImport()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==========================================
    // P2P LOCAL WI-FI SENDER FLOW
    // ==========================================

    private fun startP2pSendFlow() {
        val ip = P2pNetworkHelper.getLocalWifiIpAddress()
        if (ip == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Wi-Fi Connection Required")
                .setMessage("Could not detect a local Wi-Fi IP address. Please ensure your device is connected to Wi-Fi so the receiving device can reach it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        promptBiometricForP2pSend(ip)
    }

    private fun promptBiometricForP2pSend(localIp: String) {
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            executeP2pSend(localIp)
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Authorize Nearby Transfer",
                subtitle = "Confirm fingerprint to send encrypted vault",
                cipher = encryptCipher,
                onSuccess = {
                    executeP2pSend(localIp)
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (_: Exception) {
            executeP2pSend(localIp)
        }
    }

    private fun executeP2pSend(localIp: String) {
        lifecycleScope.launch {
            try {
                val token = QrCodeHelper.generateSecureToken()
                val vaultFile = VaultExportImportManager.exportVault(this@MainActivity, lockerRepository, token)

                activeP2pServer?.stop()

                val dialogBinding = DialogP2pSenderBinding.inflate(layoutInflater)
                val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📡 Send Vault to Nearby Device")
                    .setView(dialogBinding.root)
                    .setNegativeButton("Cancel") { d, _ ->
                        activeP2pServer?.stop()
                        d.dismiss()
                    }
                    .setOnDismissListener {
                        activeP2pServer?.stop()
                    }
                    .create()

                val server = P2pTransferServer(
                    vaultFile = vaultFile,
                    expectedToken = token,
                    timeoutSeconds = 90,
                    onStatusUpdate = { status ->
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = status
                        }
                    },
                    onTransferComplete = {
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = "✅ Transfer complete! Vault delivered."
                            dialogBinding.progressBarP2p.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "Vault transferred successfully!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = "⚠️ $error"
                        }
                    }
                )
                activeP2pServer = server
                server.start(lifecycleScope)

                // Wait up to 1 second for socket port to bind
                var attempts = 0
                while (server.localPort <= 0 && attempts++ < 20) {
                    delay(50)
                }
                val port = server.localPort
                if (port <= 0) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Failed to bind transfer port", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val p2pUri = "securepass://p2p?ip=$localIp&port=$port&token=$token"
                val qrBitmap = QrCodeHelper.generateQrCodeBitmap(p2pUri, 512)

                dialogBinding.ivP2pQr.setImageBitmap(qrBitmap)
                dialogBinding.tvP2pAddress.text = "Wi-Fi: $localIp:$port"
                dialogBinding.tvP2pToken.text = "Token: $token"

                dialog.show()

                // Countdown timer
                launch {
                    for (remaining in 90 downTo 0) {
                        if (!dialog.isShowing) break
                        dialogBinding.progressBarP2p.progress = remaining
                        val curText = dialogBinding.tvP2pStatus.text.toString()
                        if (curText.startsWith("Waiting") || curText.startsWith("Ready")) {
                            dialogBinding.tvP2pStatus.text = "Waiting for receiver to connect... (${remaining}s)"
                        }
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to start transfer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==========================================
    // P2P LOCAL WI-FI RECEIVER & SCANNER FLOW
    // ==========================================

    private fun checkCameraAndLaunchScanner() {
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status is BiometricAuthHelper.BiometricStatus.Ready) {
            try {
                val encryptCipher = KeystoreHelper.initEncryptCipher()
                BiometricAuthHelper.promptBiometricWithCrypto(
                    activity = this,
                    title = "Authorize Vault Receive",
                    subtitle = "Confirm fingerprint to receive vault",
                    cipher = encryptCipher,
                    onSuccess = {
                        proceedWithCameraCheck()
                    },
                    onError = { _, msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
                return
            } catch (_: Exception) {}
        }
        proceedWithCameraCheck()
    }

    private fun proceedWithCameraCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan SecurePass QR code on the sending device")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        SessionManager.isExternalOperationActive = true
        barcodeLauncher.launch(options)
    }

    private fun handleScannedQrContent(scanned: String) {
        val clean = scanned.trim()
        if (clean.startsWith("securepass://p2p", ignoreCase = true)) {
            val uri = Uri.parse(clean)
            val ip = uri.getQueryParameter("ip")
            val port = uri.getQueryParameter("port")?.toIntOrNull()
            val token = uri.getQueryParameter("token")

            if (ip.isNullOrBlank() || port == null || token.isNullOrBlank()) {
                Toast.makeText(this, "Invalid P2P QR code format", Toast.LENGTH_SHORT).show()
                return
            }

            startP2pReceiverFlow(ip, port, token)
        } else {
            // Standard manual token
            if (activeTokenInput != null) {
                activeTokenInput?.setText(clean)
                Toast.makeText(this, "Token scanned successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Scanned: $clean", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startP2pReceiverFlow(ip: String, port: Int, token: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Receiving Encrypted Vault")
            .setMessage("Connecting to $ip:$port over local Wi-Fi...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val destFile = File(cacheDir, "p2p_incoming_${System.currentTimeMillis()}.spvault")
                P2pTransferClient.downloadVault(
                    host = ip,
                    port = port,
                    token = token,
                    destinationFile = destFile,
                    onProgress = { downloaded, total ->
                        runOnUiThread {
                            val progressText = if (total > 0) {
                                "Downloading encrypted vault: ${downloaded / 1024} KB / ${total / 1024} KB"
                            } else {
                                "Downloading encrypted vault: ${downloaded / 1024} KB"
                            }
                            progressDialog.setMessage(progressText)
                        }
                    }
                )

                progressDialog.dismiss()

                // Analyze conflicts
                val report = VaultExportImportManager.prepareVaultImportFromFile(lockerRepository, destFile, token)
                showConflictResolutionOrCommit(report)
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Transfer Failed")
                    .setMessage(e.message ?: "Failed to receive vault from nearby device.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ==========================================
    // SYNC CONFLICT RESOLUTION & IMPORT ENGINE
    // ==========================================

    private fun showConflictResolutionOrCommit(report: VaultConflictReport) {
        if (report.totalIncoming == 0) {
            Toast.makeText(this, "Incoming vault contains 0 documents.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!report.hasConflicts) {
            // No conflicts! Direct import
            MaterialAlertDialogBuilder(this)
                .setTitle("Import Vault")
                .setMessage("Incoming vault contains:\n\n• ${report.newDocuments.size} New documents\n• ${report.identicalDocuments.size} Already up-to-date\n\nWould you like to import these documents?")
                .setPositiveButton("Import") { _, _ ->
                    commitVaultImport(report, ConflictResolutionStrategy.SMART_MERGE)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // Conflicts exist! Show Conflict Resolution Dialog
        val dialogBinding = DialogSyncConflictsBinding.inflate(layoutInflater)

        val conflictDocTitles = report.conflictingDocuments.take(4).joinToString("\n") { "• ${it.incoming.title}" }
        val moreText = if (report.conflictingDocuments.size > 4) "\n• ... and ${report.conflictingDocuments.size - 4} more" else ""

        dialogBinding.tvConflictSummary.text =
            "The incoming vault contains documents that already exist locally with different modification timestamps:\n\n" +
            "• ${report.newDocuments.size} New documents\n" +
            "• ${report.conflictingDocuments.size} Conflicting / modified documents\n" +
            "• ${report.identicalDocuments.size} Identical documents\n\n" +
            "Conflicting Documents:\n$conflictDocTitles$moreText"

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Sync Conflicts Detected")
            .setView(dialogBinding.root)
            .setPositiveButton("Apply") { _, _ ->
                val strategy = when {
                    dialogBinding.rbKeepBoth.isChecked -> ConflictResolutionStrategy.KEEP_BOTH
                    dialogBinding.rbReplaceAll.isChecked -> ConflictResolutionStrategy.REPLACE_ALL
                    else -> ConflictResolutionStrategy.SMART_MERGE
                }
                commitVaultImport(report, strategy)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun commitVaultImport(report: VaultConflictReport, strategy: ConflictResolutionStrategy) {
        lifecycleScope.launch {
            try {
                val count = VaultExportImportManager.applyVaultImport(lockerRepository, report, strategy)
                val strategyMsg = when (strategy) {
                    ConflictResolutionStrategy.SMART_MERGE -> "Merged (kept newest)"
                    ConflictResolutionStrategy.KEEP_BOTH -> "Imported with copies"
                    ConflictResolutionStrategy.REPLACE_ALL -> "Replaced locker with backup"
                }
                Toast.makeText(this@MainActivity, "✅ Successfully processed $count documents ($strategyMsg)", Toast.LENGTH_LONG).show()
                loadSecureLockerDocuments()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Import Error")
                    .setMessage(e.message ?: "Failed to commit vault documents.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ==========================================
    // MANUAL FILE EXPORT & IMPORT FLOWS
    // ==========================================

    private fun promptBiometricForExport() {
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            executeVaultExport()
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Authorize Vault Export",
                subtitle = "Confirm fingerprint to generate encrypted backup",
                cipher = encryptCipher,
                onSuccess = {
                    executeVaultExport()
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (_: Exception) {
            executeVaultExport()
        }
    }

    private fun executeVaultExport() {
        lifecycleScope.launch {
            try {
                val token = QrCodeHelper.generateSecureToken()
                val qrBitmap = QrCodeHelper.generateQrCodeBitmap(token, 512)
                val exportFile = VaultExportImportManager.exportVault(this@MainActivity, lockerRepository, token)

                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    exportFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Encrypted Vault Backup (.spvault)"))

                showExportTokenAndQrDialog(token, qrBitmap)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showExportTokenAndQrDialog(token: String, qrBitmap: android.graphics.Bitmap) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(32, 16, 32, 16)
        }

        val qrImageView = android.widget.ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = android.widget.LinearLayout.LayoutParams(420, 420).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            adjustViewBounds = true
        }
        layout.addView(qrImageView)

        val tokenTextView = android.widget.TextView(this).apply {
            text = token
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 15f
            setTextColor(MaterialColors.getColor(this@MainActivity, androidx.appcompat.R.attr.colorPrimary, android.graphics.Color.BLUE))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }
        layout.addView(tokenTextView)

        val helpTextView = android.widget.TextView(this).apply {
            text = "1. Transfer the .spvault file to your other device.\n2. In SecurePass on the other device, tap Import Vault and scan this QR code or enter the token above."
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(helpTextView)

        MaterialAlertDialogBuilder(this)
            .setTitle("Encrypted Vault Exported")
            .setView(layout)
            .setPositiveButton("Done", null)
            .setNeutralButton("Copy Token") { _, _ ->
                val timeout = if (prefs.isAutoClearEnabled) prefs.clearTimeoutSeconds else 0
                ClipboardSecurityManager.copyToClipboard(
                    context = this@MainActivity,
                    passphrase = token,
                    timeoutSeconds = timeout,
                    label = "SecurePass Token"
                )
                Toast.makeText(this@MainActivity, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun launchVaultFilePicker() {
        SessionManager.isExternalOperationActive = true
        pickVaultFileLauncher.launch("*/*")
    }

    private fun promptBiometricForImport() {
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            launchVaultFilePicker()
            return
        }

        try {
            val encryptCipher = KeystoreHelper.initEncryptCipher()
            BiometricAuthHelper.promptBiometricWithCrypto(
                activity = this,
                title = "Authorize Vault Import",
                subtitle = "Confirm fingerprint to import vault",
                cipher = encryptCipher,
                onSuccess = {
                    launchVaultFilePicker()
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } catch (_: Exception) {
            launchVaultFilePicker()
        }
    }

    private fun showImportTokenDialog(fileUri: Uri) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val tokenInput = android.widget.EditText(this).apply {
            hint = "SPVT-XXXX-XXXX-XXXX-XXXX-XXXX"
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        activeTokenInput = tokenInput
        layout.addView(tokenInput)

        val btnScanQr = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "📷 Scan QR Code Image"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            setOnClickListener {
                pickQrImageLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        layout.addView(btnScanQr)

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Secure Token")
            .setMessage("Enter the Secure Token or scan the QR code from the exporting device to decrypt the vault:")
            .setView(layout)
            .setPositiveButton("Decrypt & Import") { _, _ ->
                val token = tokenInput.text.toString().trim()
                if (token.isBlank()) {
                    Toast.makeText(this, "Token cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executeVaultImport(fileUri, token)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeVaultImport(fileUri: Uri, token: String) {
        lifecycleScope.launch {
            try {
                val report = VaultExportImportManager.prepareVaultImport(this@MainActivity, lockerRepository, fileUri, token)
                showConflictResolutionOrCommit(report)
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Import Failed")
                    .setMessage(e.message ?: "Could not decrypt vault backup.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeP2pServer?.stop()
        activeP2pServer = null
    }
}
