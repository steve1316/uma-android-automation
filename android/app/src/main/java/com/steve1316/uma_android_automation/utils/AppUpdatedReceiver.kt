package com.steve1316.uma_android_automation.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steve1316.uma_android_automation.MainActivity

/** Reopens the app after an update replaced it, and deletes the downloaded APK. */
class AppUpdatedReceiver : BroadcastReceiver() {
    /**
     * Handles `ACTION_MY_PACKAGE_REPLACED`, which Android sends only to the app that was just updated.
     *
     * @param context The receiver's context.
     * @param intent The broadcast intent.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppUpdateInstaller.clearLeftovers(context)
        try {
            // Android blocks background activity starts unless the app holds the overlay permission, which this bot needs anyway.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("AppUpdatedReceiver", "Could not reopen the app after the update: ${e.message}")
        }
    }
}
