package com.steve1316.uma_android_automation.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the Intent to stop the MediaProjection Service via the Notification  action button.
 *
 * Source is from https://stackoverflow.com/questions/41359337/android-notification-pendingintent-to-stop-service
 */
class StopServiceReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent?) {
		val service = Intent(context, MediaProjectionService::class.java)
		context.stopService(service)
	}
}