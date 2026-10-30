package com.mattintech.papercase

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlin.coroutines.resume
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.clipboard.ClipboardSecurityManager
import com.mattintech.papercase.crypto.BiometricAuthHelper
import com.mattintech.papercase.crypto.KeystoreHelper
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.databinding.ActivityMainBinding
import com.mattintech.papercase.databinding.DialogP2pSenderBinding
import com.mattintech.papercase.databinding.DialogSetPassphraseBinding
import com.mattintech.papercase.databinding.DialogSyncConflictsBinding
import com.mattintech.papercase.databinding.DialogVaultTransferBottomSheetBinding
import com.mattintech.papercase.databinding.DialogViewPassphraseBinding
import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import com.mattintech.papercase.locker.transfer.ConflictResolutionStrategy
import com.mattintech.papercase.locker.crypto.LockerKeyInvalidatedException
import com.mattintech.papercase.locker.crypto.LockerLockedException
import com.mattintech.papercase.locker.transfer.P2pNetworkHelper
import com.mattintech.papercase.locker.transfer.P2pSessionKeys
import com.mattintech.papercase.locker.transfer.P2pTransferClient
import com.mattintech.papercase.locker.transfer.P2pTransferServer
import com.mattintech.papercase.locker.transfer.QrCodeHelper
import com.mattintech.papercase.locker.transfer.VaultConflictReport
import com.mattintech.papercase.locker.transfer.VaultExportImportManager
import com.mattintech.papercase.locker.ui.AddDocumentActivity
import com.mattintech.papercase.locker.ui.DocumentViewerActivity
import com.mattintech.papercase.locker.ui.SecureDocumentsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_TRIGGER_COPY = "com.mattintech.papercase.ACTION_TRIGGER_COPY"
        private const val STATE_SELECTED_TAB = "selected_tab"
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

        selectInitialTab(savedInstanceState)
        if (savedInstanceState == null) {
            handleIncomingIntent()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, binding.bottomNavigation.selectedItemId)
    }

    /**
     * The locker is the main screen. The passphrase tab opens first only when the launch is about
     * the passphrase (quick tile, or Auto-Copy on launch).
     */
    private fun selectInitialTab(savedInstanceState: Bundle?) {
        val tab = savedInstanceState?.getInt(STATE_SELECTED_TAB)?.takeIf { it != 0 } ?: run {
            val wantsPassphrase = intent?.action == ACTION_TRIGGER_COPY ||
                (prefs.hasStoredPassphrase && prefs.isAutoCopyEnabled)
            if (wantsPassphrase) R.id.nav_passphrase else R.id.nav_locker
        }
        // Check the item directly so the selection listener (which prompts) doesn't fire
        binding.bottomNavigation.menu.findItem(tab)?.isChecked = true
        if (tab == R.id.nav_passphrase) {
            showPassphraseVaultTab()
        } else {
            // Prompt on a fresh launch, but not when opened by a transfer link (that shows its own dialog)
            val openedByLink = intent?.action == Intent.ACTION_VIEW && intent?.data != null
            showLockerTab(promptIfLocked = savedInstanceState == null && !openedByLink)
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardSecurityManager.checkAndClearIfExpired(this)
        updateVaultStatusUI()

        if (!SessionManager.isSessionValid(getSessionTimeoutMs())) {
            lockLockerInPlace()
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
        val currentIntent = intent ?: return

        // 1. Check Deep Link (e.g. from Samsung Camera, Google Lens, or browser)
        val uri = currentIntent.data
        if (currentIntent.action == Intent.ACTION_VIEW && uri != null) {
            if (uri.scheme.equals("papercase", ignoreCase = true)) {
                intent = null // Clear so configuration change won't re-trigger
                val target = parseP2pTarget(uri)
                if (target != null) {
                    confirmReceiveFromLink(target)
                } else {
                    showUnsupportedTransferCodeDialog()
                }
                return
            }
        }

        // 2. Check Passphrase Tile & Auto-Copy Actions
        if (!prefs.hasStoredPassphrase) return

        val isTileAction = currentIntent.action == ACTION_TRIGGER_COPY
        if (isTileAction) {
            triggerUnlockAndCopy(forcedFromTile = true)
        } else if (prefs.isAutoCopyEnabled && !hasHandledAutoLaunch) {
            hasHandledAutoLaunch = true
            triggerUnlockAndCopy(forcedFromTile = false)
        }
    }

    private data class P2pTarget(val ip: String, val port: Int, val transferKey: ByteArray)

    /** Parses a v2 transfer code: papercase://p2p?v=2&ip=...&port=...&key=... */
    private fun parseP2pTarget(uri: Uri): P2pTarget? {
        if (!uri.scheme.equals("papercase", ignoreCase = true) || uri.host != "p2p") return null
        if (uri.getQueryParameter("v") != "2") return null
        val ip = uri.getQueryParameter("ip")?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val key = uri.getQueryParameter("key")?.let { QrCodeHelper.decodeTransferKey(it) } ?: return null
        return P2pTarget(ip, port, key)
    }

    private fun showUnsupportedTransferCodeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Can't use this code")
            .setMessage("This transfer code isn't supported. Make sure Papercase is up to date on both devices, then start a new transfer.")
            .setPositiveButton("OK", null)
            .show()
    }

    /** Receiving is only allowed on devices with a screen lock, so the imported vault is never left open. */
    private fun ensureDeviceProtectedForReceive(): Boolean {
        if (BiometricAuthHelper.getDeviceSecurityLevel(this).isProtected) return true
        MaterialAlertDialogBuilder(this)
            .setTitle("Screen lock required")
            .setMessage("This device has no PIN, pattern, password, or fingerprint. Set up a screen lock before receiving documents, so they can't be opened by anyone who picks up the device.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    /**
     * A link can come from any app or web page, so it never starts a transfer by itself: the user
     * confirms, the sender approves, and the receiver authenticates before anything is imported.
     */
    private fun confirmReceiveFromLink(target: P2pTarget) {
        if (!ensureDeviceProtectedForReceive()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Receive documents?")
            .setMessage("Receive a vault from the device at ${target.ip}.\n\nOnly continue if you just scanned the QR code on your own device.")
            .setPositiveButton("Continue") { _, _ -> startP2pReceiverFlow(target) }
            .setNegativeButton("Cancel", null)
            .show()
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
                "Samsung One UI maintains an internal clipboard history tray in Samsung Keyboard that retains copied text even after Papercase wipes the Android clipboard.\n\n" +
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

        binding.btnSettings.setOnClickListener { showSettingsMenu(it) }

        binding.btnUnlockLocker.setOnClickListener {
            promptLockerUnlock()
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
                    showLockerTab(promptIfLocked = true)
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
    }

    /** Shows the locker tab: the documents if unlocked, otherwise the locked screen. */
    private fun showLockerTab(promptIfLocked: Boolean) {
        binding.nestedScrollView.visibility = View.GONE
        binding.layoutSecureLocker.visibility = View.VISIBLE
        binding.ivToolbarLogo.setImageResource(R.drawable.ic_folder_lock)
        binding.tvToolbarTitle.text = "Locker"

        if (isLockerUnlocked || SessionManager.isSessionValid(getSessionTimeoutMs())) {
            isLockerUnlocked = true
            displayLockerTab()
            return
        }

        // No biometric / screen lock on this device: nothing to unlock with
        if (BiometricAuthHelper.checkBiometricAvailability(this) !is BiometricAuthHelper.BiometricStatus.Ready) {
            SessionManager.markAuthenticated()
            isLockerUnlocked = true
            displayLockerTab()
            return
        }

        showLockedLocker()
        if (promptIfLocked) promptLockerUnlock()
    }

    private val isLockerOpenOnScreen: Boolean
        get() = binding.layoutSecureLocker.visibility == View.VISIBLE &&
            binding.layoutLockerContent.visibility == View.VISIBLE

    /** Gear menu: locker actions only while the locker is open, About always. */
    private fun showSettingsMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_toolbar_settings, popup.menu)
        popup.menu.findItem(R.id.action_transfer).isVisible = isLockerOpenOnScreen
        popup.menu.findItem(R.id.action_lock).isVisible = isLockerOpenOnScreen
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_transfer -> showVaultTransferOptions()
                R.id.action_lock -> {
                    SessionManager.lock()
                    lockLockerInPlace()
                    Toast.makeText(this, "Locker locked", Toast.LENGTH_SHORT).show()
                }
                R.id.action_about -> showAboutDialog()
            }
            true
        }
        popup.show()
    }

    private fun showLockedLocker() {
        binding.layoutLockerContent.visibility = View.GONE
        binding.layoutLockerLocked.visibility = View.VISIBLE
        binding.fabAddDocument.hide()
    }

    /** Locks the locker and, if it's on screen, swaps the documents for the locked screen. */
    private fun lockLockerInPlace() {
        isLockerUnlocked = false
        allDocuments = emptyList()
        documentsAdapter.submitList(emptyList())
        if (binding.layoutSecureLocker.visibility == View.VISIBLE) showLockedLocker()
    }

    private fun promptLockerUnlock() {
        BiometricAuthHelper.promptGeneralUnlock(
            activity = this,
            title = "Unlock Locker",
            subtitle = "Authenticate with biometric or device PIN/password",
            onSuccess = {
                SessionManager.markAuthenticated()
                isLockerUnlocked = true
                if (binding.layoutSecureLocker.visibility == View.VISIBLE) displayLockerTab()
            },
            onError = { _, _ ->
                // Stay on the locked screen; the Unlock button tries again
            }
        )
    }

    private fun displayLockerTab() {
        binding.nestedScrollView.visibility = View.GONE
        binding.layoutSecureLocker.visibility = View.VISIBLE
        binding.layoutLockerLocked.visibility = View.GONE
        binding.layoutLockerContent.visibility = View.VISIBLE
        binding.fabAddDocument.show()
        binding.ivToolbarLogo.setImageResource(R.drawable.ic_folder_lock)
        binding.tvToolbarTitle.text = "Locker"
        migrateLockerKeyIfNeeded()
    }

    /** Moves the locker onto the auth-bound key the first time it's opened on a device with a screen lock. */
    private fun migrateLockerKeyIfNeeded() {
        lifecycleScope.launch {
            val migrated = try {
                lockerRepository.migrateToAuthBoundKey()
            } catch (e: LockerLockedException) {
                handleLockerLocked()
                return@launch
            } catch (e: LockerKeyInvalidatedException) {
                showLockerKeyLostDialog()
                return@launch
            } catch (e: Exception) {
                // Keep using the existing key; retried on the next unlock
                e.printStackTrace()
                false
            }
            loadSecureLockerDocuments()
            if (migrated) showLockerHardwareBoundDialog()
        }
    }

    private fun showLockerHardwareBoundDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Locker upgraded")
            .setMessage(
                "Your documents can now only be decrypted after you unlock with your fingerprint or PIN.\n\n" +
                "Important: if you ever remove this device's screen lock, Android permanently deletes the locker key " +
                "and your documents can't be recovered. Keep an exported backup somewhere safe."
            )
            .setPositiveButton("Export Backup") { _, _ -> promptBiometricForExport() }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun handleLockerLocked() {
        lockLockerInPlace()
        Toast.makeText(this, "Locker locked. Unlock again to continue.", Toast.LENGTH_SHORT).show()
    }

    private fun showLockerKeyLostDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Locker can't be opened")
            .setMessage(
                "Android deleted the locker key, usually because the screen lock was removed or reset. " +
                "The stored documents can't be decrypted any more.\n\n" +
                "Reset the locker to start fresh, then import a backup if you have one."
            )
            .setCancelable(false)
            .setPositiveButton("Reset Locker") { _, _ ->
                lifecycleScope.launch {
                    lockerRepository.resetAfterKeyLoss()
                    loadSecureLockerDocuments()
                }
            }
            .setNegativeButton("Not Now") { _, _ ->
                lockLockerInPlace()
            }
            .show()
    }

    private fun loadSecureLockerDocuments() {
        lifecycleScope.launch {
            allDocuments = try {
                lockerRepository.getAllDocuments()
            } catch (e: LockerLockedException) {
                handleLockerLocked()
                return@launch
            } catch (e: LockerKeyInvalidatedException) {
                showLockerKeyLostDialog()
                return@launch
            }
            filterDocuments(null)
        }
    }

    /** Ensures the locker key is usable, prompting for fingerprint / PIN if the session has expired. */
    private suspend fun awaitLockerUnlock(subtitle: String): Boolean {
        if (SessionManager.isSessionValid(getSessionTimeoutMs())) return true
        if (!BiometricAuthHelper.getDeviceSecurityLevel(this).isProtected) {
            SessionManager.markAuthenticated()
            return true
        }
        return suspendCancellableCoroutine { cont ->
            BiometricAuthHelper.promptGeneralUnlock(
                activity = this,
                title = "Unlock Locker",
                subtitle = subtitle,
                onSuccess = {
                    SessionManager.markAuthenticated()
                    if (cont.isActive) cont.resume(true)
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (cont.isActive) cont.resume(false)
                }
            )
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
        updateSecurityStatusUI()
    }

    private fun updateSecurityStatusUI() {
        when (BiometricAuthHelper.getDeviceSecurityLevel(this)) {
            is BiometricAuthHelper.SecurityLevel.Biometric -> {
                binding.tvSecurityPill.setBackgroundResource(R.drawable.bg_status_pill)
                binding.tvSecurityPill.setTextColor(ContextCompat.getColor(this, R.color.status_protected))
                binding.tvSecurityPill.text = "BIOMETRIC ENCRYPTED"
                binding.tvSecuritySubtitle.text = "Hardware Keystore (TEE)"
                binding.tvSecurityPill.isClickable = false
            }
            is BiometricAuthHelper.SecurityLevel.DeviceCredential -> {
                binding.tvSecurityPill.setBackgroundResource(R.drawable.bg_status_pill)
                binding.tvSecurityPill.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.tvSecurityPill.text = "PIN / PASSWORD PROTECTED"
                binding.tvSecuritySubtitle.text = "Device Credential"
                binding.tvSecurityPill.isClickable = false
            }
            else -> {
                binding.tvSecurityPill.setBackgroundResource(R.drawable.bg_status_warning_pill)
                binding.tvSecurityPill.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
                binding.tvSecurityPill.text = "UNPROTECTED DEVICE"
                binding.tvSecuritySubtitle.text = "No Screen Lock Set"
                binding.tvSecurityPill.isClickable = true
                binding.tvSecurityPill.setOnClickListener {
                    showUnprotectedDeviceDialog()
                }
            }
        }
    }

    private fun showUnprotectedDeviceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Device Not Protected")
            .setMessage("This device has no PIN, pattern, password, or biometric lock set up.\n\nWithout a screen lock, Android Keystore cannot enforce hardware user authentication. To protect your vault, please configure a screen lock in Android Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
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
                "No screen lock (PIN, Pattern, Password) or Biometric credentials are set up on this device. Please set up a screen lock in Android Settings to protect your vault."
            is BiometricAuthHelper.BiometricStatus.NoHardware ->
                "This device does not have biometric hardware, and no screen lock credentials are set."
            else ->
                "Device security authentication is currently unavailable."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Screen Lock Required")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Cancel", null)
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
        val sheetBinding = DialogVaultTransferBottomSheetBinding.inflate(layoutInflater)
        val bottomSheet = BottomSheetDialog(this)
        bottomSheet.setContentView(sheetBinding.root)
        bottomSheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        sheetBinding.cardSendP2p.setOnClickListener {
            bottomSheet.dismiss()
            startP2pSendFlow()
        }

        sheetBinding.cardReceiveP2p.setOnClickListener {
            bottomSheet.dismiss()
            checkCameraAndLaunchScanner()
        }

        sheetBinding.cardExportFile.setOnClickListener {
            bottomSheet.dismiss()
            promptBiometricForExport()
        }

        sheetBinding.cardImportFile.setOnClickListener {
            bottomSheet.dismiss()
            promptBiometricForImport()
        }

        bottomSheet.show()
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

        executeP2pSend(ip)
    }

    /** Asks the sender to approve a connected receiver, then authenticate (fingerprint or PIN). */
    private suspend fun approveP2pReceiver(receiverName: String, receiverAddress: String): Boolean {
        val approved = suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Send your vault?")
                .setMessage("$receiverName ($receiverAddress) wants to receive your locker.\n\nOnly allow this if it's your device.")
                .setCancelable(false)
                .setPositiveButton("Allow") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Deny") { _, _ -> if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
        if (!approved) return false
        if (!BiometricAuthHelper.getDeviceSecurityLevel(this).isProtected) return true

        return suspendCancellableCoroutine { cont ->
            BiometricAuthHelper.promptGeneralUnlock(
                activity = this,
                title = "Authorize Transfer",
                subtitle = "Send your vault to $receiverName",
                onSuccess = {
                    SessionManager.markAuthenticated()
                    if (cont.isActive) cont.resume(true)
                },
                onError = { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (cont.isActive) cont.resume(false)
                }
            )
        }
    }

    private fun executeP2pSend(localIp: String) {
        lifecycleScope.launch {
            try {
                val transferKey = QrCodeHelper.generateTransferKey()
                val sessionKeys = P2pSessionKeys(transferKey)
                // Built only after the sender approves a connected receiver
                var vaultFile: File? = null

                activeP2pServer?.stop()

                val dialogBinding = DialogP2pSenderBinding.inflate(layoutInflater)
                val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Send Vault to Nearby Device")
                    .setView(dialogBinding.root)
                    .setNegativeButton("Cancel") { d, _ ->
                        activeP2pServer?.stop()
                        d.dismiss()
                    }
                    .setOnDismissListener {
                        activeP2pServer?.stop()
                        vaultFile?.delete()
                    }
                    .create()

                val server = P2pTransferServer(
                    expectedSessionId = sessionKeys.sessionId,
                    timeoutSeconds = 90,
                    onReceiverConnected = { receiverName, receiverAddress ->
                        withContext(Dispatchers.Main) {
                            if (approveP2pReceiver(receiverName, receiverAddress)) {
                                VaultExportImportManager.exportVaultWithKey(
                                    this@MainActivity, lockerRepository, sessionKeys.encryptionKey
                                ).also { vaultFile = it }
                            } else {
                                null
                            }
                        }
                    },
                    onStatusUpdate = { status ->
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = status
                        }
                    },
                    onTransferComplete = {
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = "Transfer complete"
                            dialogBinding.progressBarP2p.visibility = View.GONE
                            dialogBinding.root.postDelayed({
                                if (dialog.isShowing) dialog.dismiss()
                                Toast.makeText(this@MainActivity, "Vault sent", Toast.LENGTH_SHORT).show()
                            }, 1200)
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            dialogBinding.tvP2pStatus.text = error
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

                val p2pUri = "papercase://p2p?v=2&ip=$localIp&port=$port&key=${QrCodeHelper.encodeTransferKey(transferKey)}"
                val qrBitmap = QrCodeHelper.generateQrCodeBitmap(p2pUri, 512)

                dialogBinding.ivP2pQr.setImageBitmap(qrBitmap)
                dialogBinding.tvP2pAddress.text = "Wi-Fi: $localIp:$port"
                // The QR carries the decryption key, so nothing secret is printed on screen
                dialogBinding.tvP2pToken.visibility = View.GONE

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
        if (!ensureDeviceProtectedForReceive()) return
        // No fingerprint yet: the sender approves the connection, and the locker unlock happens
        // before anything incoming is compared or imported
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
            setPrompt("Scan Papercase QR code on the sending device")
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
        if (clean.startsWith("papercase://", ignoreCase = true)) {
            val target = parseP2pTarget(Uri.parse(clean))
            if (target == null) {
                showUnsupportedTransferCodeDialog()
                return
            }
            if (ensureDeviceProtectedForReceive()) startP2pReceiverFlow(target)
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

    private fun startP2pReceiverFlow(target: P2pTarget) {
        val (ip, port) = target.ip to target.port
        val sessionKeys = P2pSessionKeys(target.transferKey)
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Receiving Encrypted Vault")
            .setMessage("Connecting to $ip:$port...\n\nApprove the transfer on the sending device.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val destFile = File(cacheDir, "p2p_incoming_${System.currentTimeMillis()}.spvault")
            try {
                P2pTransferClient.downloadVault(
                    host = ip,
                    port = port,
                    sessionId = sessionKeys.sessionId,
                    deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
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

                if (!awaitLockerUnlock("Unlock to review the incoming documents")) return@launch

                // Analyze conflicts
                val report = VaultExportImportManager.prepareVaultImportFromFileWithKey(
                    lockerRepository, destFile, sessionKeys.encryptionKey
                )
                showConflictResolutionOrCommit(report)
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Transfer Failed")
                    .setMessage(e.message ?: "Failed to receive vault from nearby device.")
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                destFile.delete()
            }
        }
    }

    // ==========================================
    // SYNC CONFLICT RESOLUTION & IMPORT ENGINE
    // ==========================================

    private fun showConflictResolutionOrCommit(report: VaultConflictReport) {
        if (!report.hasChanges) {
            Toast.makeText(this, "Already up to date. Nothing to import.", Toast.LENGTH_SHORT).show()
            return
        }

        val deletedTitles = report.deletedDocuments.take(4).joinToString("\n") { "   – ${it.title}" }
        val deletedMore = if (report.deletedDocuments.size > 4) "\n   – ... and ${report.deletedDocuments.size - 4} more" else ""
        val deletedLine = if (report.deletedDocuments.isEmpty()) "" else
            "• ${report.deletedDocuments.size} Deleted on the other device (will be removed here)\n$deletedTitles$deletedMore\n"

        if (!report.hasConflicts) {
            // No conflicts! Direct import
            MaterialAlertDialogBuilder(this)
                .setTitle("Import Vault")
                .setMessage("Incoming vault contains:\n\n• ${report.newDocuments.size} New documents\n$deletedLine• ${report.identicalDocuments.size} Already up-to-date\n\nApply these changes?")
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
            deletedLine +
            "• ${report.identicalDocuments.size} Identical documents\n\n" +
            "Conflicting Documents:\n$conflictDocTitles$moreText"

        MaterialAlertDialogBuilder(this)
            .setTitle("Sync Conflicts Detected")
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
                val noun = if (count == 1) "document" else "documents"
                val msg = when (strategy) {
                    ConflictResolutionStrategy.SMART_MERGE -> "Import complete: $count $noun updated"
                    ConflictResolutionStrategy.KEEP_BOTH -> "Import complete: $count $noun added or copied"
                    ConflictResolutionStrategy.REPLACE_ALL -> "Locker replaced: $count $noun restored"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
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

        BiometricAuthHelper.promptGeneralUnlock(
            activity = this,
            title = "Authorize Vault Export",
            subtitle = "Authenticate to generate encrypted backup",
            onSuccess = {
                executeVaultExport()
            },
            onError = { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
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
                startActivity(Intent.createChooser(shareIntent, "Share Encrypted Vault Backup (.kdbx)"))

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
            text = "1. Transfer the .kdbx file to your other device.\n2. In Papercase on the other device, tap Import Backup File and scan this QR code or enter the token above.\n\nThe backup is a standard KeePass database: KeePassXC or KeePassDX can also open it with this token as the password. Keep the token somewhere safe, separate from the file."
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
                    label = "Papercase Token"
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
        if (!ensureDeviceProtectedForReceive()) return
        val status = BiometricAuthHelper.checkBiometricAvailability(this)
        if (status !is BiometricAuthHelper.BiometricStatus.Ready) {
            launchVaultFilePicker()
            return
        }

        BiometricAuthHelper.promptGeneralUnlock(
            activity = this,
            title = "Authorize Vault Import",
            subtitle = "Authenticate to import vault",
            onSuccess = {
                launchVaultFilePicker()
            },
            onError = { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showImportTokenDialog(fileUri: Uri) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val tokenInput = android.widget.EditText(this).apply {
            hint = "PCASE-XXXX-XXXX-XXXX-XXXX-XXXX"
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        activeTokenInput = tokenInput
        layout.addView(tokenInput)

        val btnScanQr = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "Scan QR Code Image"
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
