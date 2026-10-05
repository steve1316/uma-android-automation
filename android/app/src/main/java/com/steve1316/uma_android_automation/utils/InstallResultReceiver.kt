package com.steve1316.uma_android_automation.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Receives the result of an app update install from Android's [PackageInstaller] and passes it to the open [AppUpdateDialog]. */
class InstallResultReceiver : BroadcastReceiver() {
    /**
     * Opens Android's Install screen when the user has to confirm, or reports a cancelled or failed install.
     *
     * @param context The receiver's context.
     * @param intent The installer's status intent.
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Android ends the app to finish the install, and AppUpdatedReceiver reopens it.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdateDialog.current?.onInstallResult(AppUpdateInstaller.InstallResult.Aborted)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed with status $status."
                Log.e("InstallResultReceiver", "App update install failed: $message")
                AppUpdateDialog.current?.onInstallResult(AppUpdateInstaller.InstallResult.Failed(message))
            }
        }
    }
}
