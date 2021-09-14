package com.steve1316.uma_android_automation.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R
import com.steve1316.uma_android_automation.bot.Game
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * This Service will allow starting and stopping the automation workflow on a Thread based on the chosen preference settings.
 *
 * Source for being able to send custom Intents to BroadcastReceiver to notify users of bot state changes is from:
 * https://www.tutorialspoint.com/in-android-how-to-register-a-custom-intent-filter-to-a-broadcast-receiver
 */
class BotService : Service() {
	private val tag: String = "[${MainActivity.loggerTag}]BotService"
	private var appName: String = ""
	private lateinit var myContext: Context
	private lateinit var overlayView: View
	private lateinit var overlayButton: ImageButton
	
	companion object {
		private lateinit var thread: Thread
		private lateinit var windowManager: WindowManager
		
		// Create the LayoutParams for the floating overlay START/STOP button.
		private val overlayLayoutParams = WindowManager.LayoutParams().apply {
			type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			} else {
				WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
			}
			flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
			format = PixelFormat.TRANSLUCENT
			width = WindowManager.LayoutParams.WRAP_CONTENT
			height = WindowManager.LayoutParams.WRAP_CONTENT
			windowAnimations = android.R.style.Animation_Toast
		}
		
		var isRunning = false
	}
	
	@SuppressLint("ClickableViewAccessibility", "InflateParams")
	override fun onCreate() {
		super.onCreate()
		
		myContext = this
		appName = myContext.getString(R.string.app_name)
		
		// Display the overlay view layout on the screen.
		overlayView = LayoutInflater.from(this).inflate(R.layout.bot_actions, null)
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		windowManager.addView(overlayView, overlayLayoutParams)
		
		// This button is able to be moved around the screen and clicking it will start/stop the game automation.
		overlayButton = overlayView.findViewById(R.id.bot_actions_overlay_button)
		overlayButton.setOnTouchListener(object : View.OnTouchListener {
			private var initialX: Int = 0
			private var initialY: Int = 0
			private var initialTouchX: Float = 0F
			private var initialTouchY: Float = 0F
			
			override fun onTouch(v: View?, event: MotionEvent?): Boolean {
				val action = event?.action
				
				if (action == MotionEvent.ACTION_DOWN) {
					// Get the initial position.
					initialX = overlayLayoutParams.x
					initialY = overlayLayoutParams.y
					
					// Now get the new position.
					initialTouchX = event.rawX
					initialTouchY = event.rawY
					
					return false
				} else if (action == MotionEvent.ACTION_UP) {
					val elapsedTime: Long = event.eventTime - event.downTime
					if (elapsedTime < 100L) {
						// Update both the Notification and the overlay button to reflect the current bot status.
						if (!isRunning) {
							Log.d(tag, "Bot Service for $appName is now running.")
							Toast.makeText(myContext, "Bot Service for $appName is now running.", Toast.LENGTH_SHORT).show()
							isRunning = true
							NotificationUtils.updateNotification(myContext, isRunning)
							overlayButton.setImageResource(R.drawable.stop_circle_filled)
							
							val game = Game(myContext)
							
							thread = thread {
								try {
									// Clear the Message Log.
									MessageLog.messageLog.clear()
									MessageLog.saveCheck = false
									
									// Start with the provided settings from SharedPreferences.
									game.start()
									
									NotificationUtils.updateNotification(myContext, false, "Bot has completed successfully with no errors.")
									
									performCleanUp()
								} catch (e: Exception) {
									if (e.toString() == "java.lang.InterruptedException") {
										NotificationUtils.updateNotification(myContext, false, "Bot has completed successfully with no errors.")
									} else {
										NotificationUtils.updateNotification(myContext, false, "Encountered an Exception: $e.\nTap me to see more details.")
										game.printToLog("$appName encountered an Exception: ${e.stackTraceToString()}", tag = tag, isError = true)
									}
									
									performCleanUp(isException = true)
								}
							}
						} else {
							thread.interrupt()
							performCleanUp()
						}
						
						// Returning true here freezes the animation of the click on the button.
						return false
					}
				} else if (action == MotionEvent.ACTION_MOVE) {
					val xDiff = (event.rawX - initialTouchX).roundToInt()
					val yDiff = (event.rawY - initialTouchY).roundToInt()
					
					// Calculate the X and Y coordinates of the view.
					overlayLayoutParams.x = initialX + xDiff
					overlayLayoutParams.y = initialY + yDiff
					
					// Now update the layout.
					windowManager.updateViewLayout(overlayView, overlayLayoutParams)
					return false
				}
				
				return false
			}
		})
	}
	
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Do not attempt to restart the bot service if it crashes.
		return START_NOT_STICKY
	}
	
	override fun onBind(intent: Intent?): IBinder? {
		return null
	}
	
	override fun onDestroy() {
		super.onDestroy()
		
		// Remove the overlay View that holds the overlay button.
		windowManager.removeView(overlayView)
		
		val service = Intent(myContext, MyAccessibilityService::class.java)
		myContext.stopService(service)
	}
	
	/**
	 * Perform cleanup upon app completion or encountering an Exception.
	 *
	 * @param isException Prevents updating the Notification again if the bot stopped due to an Exception.
	 */
	private fun performCleanUp(isException: Boolean = false) {
		// Save the message log.
		MessageLog.saveLogToFile(myContext)
		
		Log.d(tag, "Bot Service for $appName is now stopped.")
		isRunning = false
		
		// Update the app's notification with the status.
		if (!isException) {
			NotificationUtils.updateNotification(myContext, false, "Bot has completed successfully with no errors.")
		}
		
		// Reset the overlay button's image on a separate UI thread.
		Handler(Looper.getMainLooper()).post {
			overlayButton.setImageResource(R.drawable.play_circle_filled)
		}
	}
}