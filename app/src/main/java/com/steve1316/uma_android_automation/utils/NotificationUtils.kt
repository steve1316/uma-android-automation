package com.steve1316.uma_android_automation.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

/**
 * Contains the utility functions for creating a Notification.
 *
 * Source is from https://github.com/mtsahakis/MediaProjectionDemo where the Java code was converted to Kotlin and additional logic was added to
 * suit this application's purposes.
 */
class NotificationUtils {
	companion object {
		private lateinit var notificationManager: NotificationManager
		private const val NOTIFICATION_ID: Int = 1
		private const val CHANNEL_ID: String = "STATUS"
		private const val CHANNEL_NAME: String = "ウマ娘 Training Helper"
		private const val CONTENT_TITLE: String = "ウマ娘 Training Helper"
		private const val CHANNEL_DESCRIPTION: String = "Displays status of ウマ娘 Training Helper, whether it is running or not."
		
		/**
		 * Creates the NotificationChannel and the Notification object.
		 *
		 * @param context The application context.
		 * @return Pair object containing the Notification object and its ID string.
		 */
		fun getNewNotification(context: Context): Pair<Notification, Int> {
			// Create the NotificationChannel.
			createNewNotificationChannel(context)
			
			// Create the Notification.
			val newNotification = createNewNotification(context)
			
			// Get the NotificationManager and then send the new Notification to it.
			notificationManager.notify(NOTIFICATION_ID, newNotification)
			
			return Pair(newNotification, NOTIFICATION_ID)
		}
		
		/**
		 * Create a new NotificationChannel.
		 *
		 * https://developer.android.com/training/notify-user/channels
		 *
		 * @param context The application context.
		 */
		private fun createNewNotificationChannel(context: Context) {
			// Create the NotificationChannel.
			val mChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
			mChannel.description = CHANNEL_DESCRIPTION
			
			// Register the channel with the system; you can't change the importance or other notification behaviors after this.
			notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(mChannel)
		}
		
		/**
		 * Create a new Notification.
		 *
		 * @param context The application context.
		 * @return A new Notification object.
		 */
		private fun createNewNotification(context: Context): Notification {
			// Create a STOP Intent for the MediaProjection service.
			val stopIntent = Intent(context, StopServiceReceiver::class.java)
			
			// Create a PendingIntent in order to add a action button to stop the MediaProjection service in the notification.
			val stopPendingIntent: PendingIntent = PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), stopIntent, PendingIntent.FLAG_CANCEL_CURRENT)
			
			// Create a PendingIntent to send the user back to the application if they tap the notification itself.
			val contentIntent = Intent(context, MainActivity::class.java)
			val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT)
			
			return NotificationCompat.Builder(context, CHANNEL_ID).apply {
				setSmallIcon(R.drawable.ic_baseline_control_camera_24)
				setContentTitle(CONTENT_TITLE)
				setContentText("Awaiting user input...")
				setContentIntent(contentPendingIntent)
				addAction(R.drawable.ic_baseline_stop_circle_24, context.getString(R.string.stop_process), stopPendingIntent)
				priority = NotificationManager.IMPORTANCE_HIGH
				setCategory(Notification.CATEGORY_SERVICE)
				setOngoing(true)
				setShowWhen(true)
			}.build()
		}
		
		/**
		 * Updates the Notification content text.
		 *
		 * @param context The application context.
		 * @param eventName The resulting event as determined by OCR text detection.
		 * @param options The options associated with the event.
		 * @param confidence The confidence from the OCR text detection
		 */
		fun updateNotification(context: Context, eventName: String, options: String, confidence: Double) {
			// Create a STOP Intent for the MediaProjection service.
			val stopIntent = Intent(context, StopServiceReceiver::class.java)
			
			// Create a PendingIntent in order to add a action button to stop the MediaProjection service in the notification.
			val stopPendingIntent: PendingIntent = PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), stopIntent, PendingIntent.FLAG_CANCEL_CURRENT)
			
			// Create a PendingIntent to send the user back to the application if they tap the notification itself.
			val contentIntent = Intent(context, MainActivity::class.java)
			val contentPendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT)
			
			val newNotification = if (eventName == "OCR Failed") {
				NotificationCompat.Builder(context, CHANNEL_ID).apply {
					setSmallIcon(R.drawable.ic_baseline_control_camera_24)
					setContentTitle("$eventName | Confidence: $confidence")
					setContentText(options)
					setStyle(NotificationCompat.BigTextStyle().bigText(options))
					setContentIntent(contentPendingIntent)
					addAction(R.drawable.ic_baseline_stop_circle_24, context.getString(R.string.stop_process), stopPendingIntent)
					priority = NotificationManager.IMPORTANCE_HIGH
					setCategory(Notification.CATEGORY_SERVICE)
					setOngoing(true)
					setShowWhen(true)
				}.build()
			} else {
				NotificationCompat.Builder(context, CHANNEL_ID).apply {
					setSmallIcon(R.drawable.ic_baseline_control_camera_24)
					setContentTitle("$eventName | Confidence: $confidence")
					setContentText("To view event rewards, Swipe or Tap me to view in full!")
					setStyle(NotificationCompat.BigTextStyle().bigText(options))
					setContentIntent(contentPendingIntent)
					addAction(R.drawable.ic_baseline_stop_circle_24, context.getString(R.string.stop_process), stopPendingIntent)
					priority = NotificationManager.IMPORTANCE_HIGH
					setCategory(Notification.CATEGORY_SERVICE)
					setOngoing(true)
					setShowWhen(true)
				}.build()
			}
			
			if (!this::notificationManager.isInitialized) {
				notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			}
			
			notificationManager.notify(NOTIFICATION_ID, newNotification)
		}

//		/**
//		 * Displays a separate Notification indicating the user of bot state changes, like Success or Exception.
//		 * @param context The application context.
//		 * @param contentTitle The title of the Notification.
//		 * @param contentText The text of the Notification.
//		 */
//		fun createBotStateChangedNotification(context: Context, contentTitle: String, contentText: String) {
//			val notificationID = 2
//			val channelID = "STATE_CHANGED"
//			val channelName = "ウマ娘 Training Helper"
//
//			// Create the NotificationChannel.
//			val mChannel = NotificationChannel(channelID, channelName, NotificationManager.IMPORTANCE_HIGH)
//			mChannel.description =
//				"This Channel is for notifications that will inform users on ウマ娘 Training Helper state changes, like completing its goal successfully or if it encountered an Exception."
//
//			// Register the channel with the system; you can't change the importance or other notification behaviors after this.
//			notificationManager.createNotificationChannel(mChannel)
//
//			val contentIntent = Intent(context, MainActivity::class.java)
//			val contentPendingIntent = PendingIntent.getActivity(context, notificationID, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT)
//
//			val newNotification = NotificationCompat.Builder(context, channelID).apply {
//				setSmallIcon(R.drawable.ic_baseline_error_outline_24)
//				setContentTitle(contentTitle)
//				setContentText(contentText)
//				setContentIntent(contentPendingIntent)
//				setAutoCancel(true)
//				priority = NotificationManager.IMPORTANCE_LOW
//				setCategory(Notification.CATEGORY_SERVICE)
//				setOngoing(false)
//				setShowWhen(true)
//			}.build()
//
//			notificationManager.notify(notificationID, newNotification)
//		}
	}
}