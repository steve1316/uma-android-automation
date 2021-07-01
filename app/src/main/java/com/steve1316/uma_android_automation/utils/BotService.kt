package com.steve1316.uma_android_automation.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
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
	private val TAG: String = "UATH_BotService"
	private lateinit var myContext: Context
	private lateinit var overlayView: View
	private lateinit var overlayButton: ImageButton
	
	companion object {
		private lateinit var thread: Thread
		private lateinit var windowManager: WindowManager
		
		// Create the LayoutParams for the floating overlay START/STOP button.
		private val overlayLayoutParams = WindowManager.LayoutParams().apply {
			type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
							Log.d(TAG, "Service for Uma Android Training Helper is now running.")
							Toast.makeText(myContext, "Service for Uma Android Training Helper is now running.", Toast.LENGTH_SHORT).show()
							isRunning = true
							overlayButton.setImageResource(R.drawable.ic_baseline_stop_circle_24)
							
							val game = Game(myContext)
							
							thread = thread {
								try {
									// Clear the Message Log.
									MessageLog.messageLog.clear()
									MessageLog.saveCheck = false
									
									// Start with the provided settings from SharedPreferences.
									game.start()
									
									val newIntent = Intent("CUSTOM_INTENT")
									newIntent.putExtra("SUCCESS", "Bot has completed successfully with no errors.")
									sendBroadcast(newIntent)
									
									MessageLog.saveLogToFile(myContext)
									Log.d(TAG, "Service for Uma Android Training Helper is now stopped.")
									isRunning = false
									overlayButton.setImageResource(R.drawable.ic_baseline_play_circle_outline_24)
								} catch (e: Exception) {
									game.printToLog("Uma Android Training Helper encountered an Exception: $e", MESSAGE_TAG = TAG, isError = true)
									
									val newIntent = Intent("CUSTOM_INTENT")
									if (e.toString() == "java.lang.InterruptedException") {
										newIntent.putExtra("EXCEPTION", "Bot stopped successfully.")
									} else {
										newIntent.putExtra("EXCEPTION", e.toString())
									}
									
									sendBroadcast(newIntent)
									
									MessageLog.saveLogToFile(myContext)
								}
							}
						} else {
							thread.interrupt()
							MessageLog.saveLogToFile(myContext)
							
							Log.d(TAG, "Service for Uma Android Training Helper is now stopped.")
							Toast.makeText(myContext, "Service for Uma Android Training Helper is now stopped.", Toast.LENGTH_SHORT).show()
							isRunning = false
							overlayButton.setImageResource(R.drawable.ic_baseline_play_circle_outline_24)
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
	}
}