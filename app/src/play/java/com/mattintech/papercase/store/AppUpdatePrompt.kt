package com.mattintech.papercase.store

import android.content.Context
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Suggests updating when Google Play has a newer version. Never blocks: the update downloads in the
 * background (Play's "flexible" update), and dismissing the suggestion hides it until the next version.
 *
 * Create in onCreate (it registers an activity result) and call [check] from onResume.
 */
class AppUpdatePrompt(
    private val activity: AppCompatActivity,
    private val root: View,
    private val anchor: View?
) {
    private val manager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences("papercase_updates", Context.MODE_PRIVATE)
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    private var shownThisSession = false

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) showRestart()
    }

    fun check() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.installStatus() == InstallStatus.DOWNLOADED -> showRestart()
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> suggest(info)
                }
            }
            // Not installed from Play (debug builds, sideloads) or Play unavailable: nothing to suggest
            .addOnFailureListener { }
    }

    private fun suggest(info: AppUpdateInfo) {
        val version = info.availableVersionCode()
        if (shownThisSession || prefs.getInt(KEY_DISMISSED, 0) == version) return
        shownThisSession = true
        Snackbar.make(root, "A new version of Papercase is available", Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(anchor)
            .setAction("Update") { start(info) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) prefs.edit { putInt(KEY_DISMISSED, version) }
                }
            })
            .show()
    }

    private fun start(info: AppUpdateInfo) {
        manager.registerListener(listener)
        manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
    }

    private fun showRestart() {
        manager.unregisterListener(listener)
        Snackbar.make(root, "Update downloaded", Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(anchor)
            .setAction("Restart") { manager.completeUpdate() }
            .show()
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_version_code"
    }
}
