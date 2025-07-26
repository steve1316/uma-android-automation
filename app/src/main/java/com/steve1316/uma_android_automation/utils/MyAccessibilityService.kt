package com.steve1316.uma_android_automation.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Contains the Accessibility service that will allow the bot to programmatically perform gestures on the screen.
 */
class MyAccessibilityService : AccessibilityService() {
	private var appName: String = ""
	private val tag: String = "[${MainActivity.loggerTag}]MyAccessibilityService"
	private lateinit var myContext: Context
	
	companion object {
		// Other classes need this static reference to this service as calling dispatchGesture() would not work.
		@SuppressLint("StaticFieldLeak")
		private lateinit var instance: MyAccessibilityService
		
		/**
		 * Returns a static reference to this class.
		 *
		 * @return Static reference to MyAccessibilityService.
		 */
		fun getInstance(): MyAccessibilityService {
			return instance
		}
	}
	
	override fun onServiceConnected() {
		instance = this
		myContext = this
		appName = myContext.getString(R.string.app_name)
		
		Log.d(tag, "Accessibility Service for $appName is now running.")
		Toast.makeText(myContext, "Accessibility Service for $appName now running.", Toast.LENGTH_SHORT).show()
	}
	
	override fun onInterrupt() {
		return
	}
	
	override fun onDestroy() {
		super.onDestroy()
		
		Log.d(tag, "Accessibility Service for $appName is now stopped.")
		Toast.makeText(myContext, "Accessibility Service for $appName is now stopped.", Toast.LENGTH_SHORT).show()
	}
	
	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		return
	}
	
	/**
	 * This receiver will wait the specified seconds to account for ping or loading.
	 */
	private fun Double.wait() {
		runBlocking {
			delay((this@wait * 1000).toLong())
		}
	}
	
	/**
	 * Randomizes the tap location to be within the dimensions of the specified image.
	 *
	 * @param x The original x location for the tap gesture.
	 * @param y The original y location for the tap gesture.
	 * @param imageName The name of the image to acquire its dimensions for tap location randomization.
	 * @return Pair of integers that represent the newly randomized tap location.
	 */
	private fun randomizeTapLocation(x: Double, y: Double, imageName: String): Pair<Int, Int> {
		// Get the Bitmap from the template image file inside the specified folder.
		val templateBitmap: Bitmap
		myContext.assets?.open("images/$imageName.png").use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}
		
		val width = templateBitmap.width
		val height = templateBitmap.height
		
		// Randomize the tapping location.
		val x0: Int = (x - (width / 2)).toInt()
		val x1: Int = (x + (width / 2)).toInt()
		val y0: Int = (y - (height / 2)).toInt()
		val y1: Int = (y + (height / 2)).toInt()
		
		var newX: Int
		var newY: Int
		
		while (true) {
			// Start acquiring randomized coordinates at least 20% and at most 80% of the width and height until a valid set of coordinates has been acquired.
			val newWidth: Int = ((width * 0.2).toInt()..(width * 0.8).toInt()).random()
			val newHeight: Int = ((height * 0.2).toInt()..(height * 0.8).toInt()).random()
			
			newX = x0 + newWidth
			newY = y0 + newHeight
			
			// If the new coordinates are within the bounds of the template image, break out of the loop.
			if (newX > x0 || newX < x1 || newY > y0 || newY < y1) {
				break
			}
		}
		
		return Pair(newX, newY)
	}
	
	/**
	 * Creates a tap gesture on the specified point on the screen.
	 *
	 * @param x The x coordinate of the point.
	 * @param y The y coordinate of the point.
	 * @param imageName The name of the image to acquire its dimensions for tap location randomization.
	 * @param ignoreWait Whether or not to not wait 0.5 seconds after dispatching the gesture.
	 * @param longPress Whether or not to long press.
	 * @param taps How many taps to execute.
	 * @return True if the tap gesture was executed successfully. False otherwise.
	 */
	fun tap(x: Double, y: Double, imageName: String, ignoreWait: Boolean = false, longPress: Boolean = false, taps: Int = 1): Boolean {
		// Randomize the tapping location.
		val (newX, newY) = randomizeTapLocation(x, y, imageName)
		
		// Construct the tap gesture.
		val tapPath = Path().apply {
			moveTo(newX.toFloat(), newY.toFloat())
		}
		
		val gesture: GestureDescription = if (longPress) {
			// Long press for 1000ms.
			GestureDescription.Builder().apply {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1000, true))
				} else {
					addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1000))
				}
			}.build()
		} else {
			GestureDescription.Builder().apply {
				addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1))
			}.build()
		}
		
		val dispatchResult = dispatchGesture(gesture, null, null)
		var tries = taps - 1
		
		while (tries > 0) {
			dispatchGesture(gesture, null, null)
			if (!ignoreWait) {
				0.5.wait()
			}
			
			tries -= 1
		}
		
		if (!ignoreWait) {
			0.5.wait()
		}
		
		return dispatchResult
	}
	
	/**
	 * Creates a scroll gesture either scrolling up or down the screen depending on the given action.
	 *
	 * @param scrollDown The scrolling action, either up or down the screen. Defaults to true which is scrolling down.
	 * @param duration How long the scroll should take. Defaults to 100L.
	 * @param ignoreWait Whether or not to not wait 0.5 seconds after dispatching the gesture.
	 * @return True if the scroll gesture was executed successfully. False otherwise.
	 */
	fun scroll(scrollDown: Boolean = true, duration: Long = 500L, ignoreWait: Boolean = false): Boolean {
		val scrollPath = Path()
		
		// Get certain portions of the screen's dimensions.
		val displayMetrics = Resources.getSystem().displayMetrics
		
		// Set different scroll paths for different screen sizes.
		val top: Float
		val middle: Float
		val bottom: Float
		when (displayMetrics.widthPixels) {
			1600 -> {
				top = (displayMetrics.heightPixels * 0.60).toFloat()
				middle = (displayMetrics.widthPixels * 0.20).toFloat()
				bottom = (displayMetrics.heightPixels * 0.40).toFloat()
			}
			2650 -> {
				top = (displayMetrics.heightPixels * 0.60).toFloat()
				middle = (displayMetrics.widthPixels * 0.20).toFloat()
				bottom = (displayMetrics.heightPixels * 0.40).toFloat()
			}
			else -> {
				top = (displayMetrics.heightPixels * 0.75).toFloat()
				middle = (displayMetrics.widthPixels / 2).toFloat()
				bottom = (displayMetrics.heightPixels * 0.25).toFloat()
			}
		}
		
		if (scrollDown) {
			// Create a Path to scroll the screen down starting from the top and swiping to the bottom.
			scrollPath.apply {
				moveTo(middle, top)
				lineTo(middle, bottom)
			}
		} else {
			// Create a Path to scroll the screen up starting from the bottom and swiping to the top.
			scrollPath.apply {
				moveTo(middle, bottom)
				lineTo(middle, top)
			}
		}
		
		val gesture = GestureDescription.Builder().apply {
			addStroke(GestureDescription.StrokeDescription(scrollPath, 0, duration))
		}.build()
		
		val dispatchResult = dispatchGesture(gesture, null, null)
		if (!ignoreWait) {
			0.5.wait()
		}
		
		if (!dispatchResult) {
			Log.e(tag, "Failed to dispatch scroll gesture.")
		} else {
			val direction: String = if (scrollDown) {
				"down"
			} else {
				"up"
			}
			Log.d(tag, "Scrolling $direction.")
		}
		
		return dispatchResult
	}
	
	/**
	 * Creates a swipe gesture from the old coordinates to the new coordinates on the screen.
	 *
	 * @param oldX The x coordinate of the old position.
	 * @param oldY The y coordinate of the old position.
	 * @param newX The x coordinate of the new position.
	 * @param newY The y coordinate of the new position.
	 * @param duration How long the swipe should take. Defaults to 500L.
	 * @param ignoreWait Whether or not to not wait 0.5 seconds after dispatching the gesture.
	 * @return True if the swipe gesture was executed successfully. False otherwise.
	 */
	fun swipe(oldX: Float, oldY: Float, newX: Float, newY: Float, duration: Long = 500L, ignoreWait: Boolean = false): Boolean {
		// Set up the Path by swiping from the old position coordinates to the new position coordinates.
		val swipePath = Path().apply {
			moveTo(oldX, oldY)
			lineTo(newX, newY)
		}
		
		val gesture = GestureDescription.Builder().apply {
			addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
		}.build()
		
		val dispatchResult = dispatchGesture(gesture, null, null)
		if (!ignoreWait) {
			0.5.wait()
		}
		
		return dispatchResult
	}
}