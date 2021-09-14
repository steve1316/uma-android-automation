package com.steve1316.uma_android_automation.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.Image.Plane
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

/**
 * The MediaProjection service that will control taking screenshots.
 *
 * Source is from https://github.com/mtsahakis/MediaProjectionDemo where the Java code was converted to Kotlin and additional logic was
 * added to suit this application's purposes.
 */
class MediaProjectionService : Service() {
	private var appName: String = ""
	private lateinit var myContext: Context
	
	companion object {
		private const val TAG: String = "[${MainActivity.loggerTag}]MediaProjectionService"
		
		private var mediaProjection: MediaProjection? = null
		private var orientationChangeCallback: OrientationEventListener? = null
		private lateinit var tempDirectory: String
		private lateinit var threadHandler: Handler
		
		var displayWidth: Int = 0
		var displayHeight: Int = 0
		var displayDPI: Int = 0
		
		private lateinit var virtualDisplay: VirtualDisplay
		private lateinit var defaultDisplay: Display
		private lateinit var windowManager: WindowManager
		private var oldRotation: Int = 0
		private lateinit var imageReader: ImageReader
		private var SCREENSHOT_NUM: Int = 0
		var isRunning: Boolean = false
		
		/**
		 * Tell the ImageReader to grab the latest acquired screenshot and process it into a Bitmap.
		 *
		 * @param isException Saves the screenshot as part of Exception logging or not. Defaults to false.
		 * @return Bitmap of the latest acquired screenshot.
		 */
		fun takeScreenshotNow(isException: Boolean = false): Bitmap? {
			var sourceBitmap: Bitmap? = null
			
			val image: Image? = imageReader.acquireLatestImage()
			
			if (image != null) {
				val planes: Array<Plane> = image.planes
				val buffer = planes[0].buffer
				val pixelStride = planes[0].pixelStride
				val rowStride = planes[0].rowStride
				val rowPadding: Int = rowStride - pixelStride * displayWidth
				
				// Create the Bitmap.
				sourceBitmap = Bitmap.createBitmap(displayWidth + rowPadding / pixelStride, displayHeight, Bitmap.Config.ARGB_8888)
				sourceBitmap.copyPixelsFromBuffer(buffer)
				
				// Now write the Bitmap to the specified file inside the /files/temp/ folder.
				SCREENSHOT_NUM++
				val fos = if (isException) {
					FileOutputStream("$tempDirectory/exception.png")
				} else {
					FileOutputStream("$tempDirectory/source.png")
				}
				sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
				
				// Perform cleanup by closing streams and freeing up memory.
				try {
					fos.close()
				} catch (ioe: IOException) {
					ioe.printStackTrace()
				}
				
				image.close()
			}
			
			return sourceBitmap
		}
		
		/**
		 * Create a new Intent to start this service.
		 *
		 * @param context The application's context.
		 * @param resultCode The output of this service.
		 * @param data The data of this service.
		 * @return A new Intent.
		 */
		fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
			return Intent(context, MediaProjectionService::class.java).apply {
				putExtra("ACTION", "START")
				putExtra("RESULT_CODE", resultCode)
				putExtra("DATA", data)
			}
		}
		
		/**
		 * Create a new Intent to stop this service.
		 *
		 * @param context The application's context.
		 * @return A new Intent.
		 */
		fun getStopIntent(context: Context): Intent {
			return Intent(context, MediaProjectionService::class.java).apply {
				putExtra("ACTION", "STOP")
			}
		}
		
		/**
		 * Checks whether the Intent is a START command.
		 *
		 * @param intent The Intent to be checked.
		 * @return True if it is a START command. Otherwise, it is False.
		 */
		private fun isStartCommand(intent: Intent): Boolean {
			isRunning = true
			return (intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA") && intent.hasExtra("ACTION") && Objects.equals(
				intent.getStringExtra("ACTION"), "START"
			))
		}
		
		/**
		 * Checks whether the Intent is a STOP command.
		 *
		 * @param intent The Intent to be checked.
		 * @return True if it is a STOP command. Otherwise, it is False.
		 */
		private fun isStopCommand(intent: Intent): Boolean {
			isRunning = false
			return (intent.hasExtra("ACTION") && Objects.equals(intent.getStringExtra("ACTION"), "STOP"))
		}
		
		/**
		 * Gets the public flag for the initialization of the VirtualDisplay, whether or not it can allow applications to open their own displays on
		 * it.
		 *
		 * @return An Integer representing the flag.
		 */
		private fun getVirtualDisplayFlags(): Int {
			return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
		}
	}
	
	override fun onCreate() {
		super.onCreate()
		
		// Creates a temporary folder if it does not already exist to store source images.
		val externalFilesDir: File? = getExternalFilesDir(null)
		if (externalFilesDir != null) {
			tempDirectory = externalFilesDir.absolutePath + "/temp/"
			val newTempDirectory = File(tempDirectory)
			
			// If the /files/temp/ folder does not exist, create it.
			if (!newTempDirectory.exists()) {
				val successfullyCreated: Boolean = newTempDirectory.mkdirs()
				
				// If the folder was not able to be created for some reason, log the error and stop the MediaProjection Service.
				if (!successfullyCreated) {
					Log.e(TAG, "Failed to create the /files/temp/ folder.")
					stopSelf()
				} else {
					Log.d(TAG, "Successfully created /files/temp/ folder.")
				}
			} else {
				Log.d(TAG, "/files/temp/ folder already exists.")
			}
		}
		
		// Now, start a new Thread to handle processing new screenshots.
		object : Thread() {
			override fun run() {
				Log.d(TAG, "Thread running for MediaProjection service.")
				threadHandler = Handler(Looper.getMainLooper())
				Looper.prepare()
				Looper.loop()
			}
		}.start()
	}
	
	override fun onBind(intent: Intent?): IBinder? {
		return null
	}
	
	@RequiresApi(Build.VERSION_CODES.R)
	@SuppressLint("ClickableViewAccessibility", "InflateParams")
	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		// Save a reference to the context.
		myContext = this
		appName = myContext.getString(R.string.app_name)
		
		if (isStartCommand(intent)) {
			// Create a new Notification in the foreground telling users that the MediaProjection Service is now active.
			val (notification, notificationID) = NotificationUtils.getNewNotification(this)
			startForeground(notificationID, notification)
			
			val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
			val data: Intent? = intent.getParcelableExtra("DATA")
			if (data != null) {
				// Start the MediaProjection service.
				startMediaProjection(resultCode, data)
				
				// Finally, start the Bot Service.
				val botStartIntent = Intent(this, BotService::class.java)
				startService(botStartIntent)
			}
		} else if (isStopCommand(intent)) {
			// Perform cleanup on the MediaProjection service and then stop itself.
			Log.d(TAG, "Received STOP Intent for MediaProjection. Stopping MediaProjection service.")
			stopMediaProjection()
			stopSelf()
		} else {
			Log.e(TAG, "Encountered unexpected Intent. Shutting down service.")
			stopSelf()
		}
		
		return START_NOT_STICKY
	}
	
	/**
	 * Custom Callback for when the user rotates their device from horizontal to vertical and vice-versa.
	 */
	private inner class OrientationChangeCallback(context: Context) : OrientationEventListener(context) {
		private val tagOrientationChangeCallback: String = "[${MainActivity.loggerTag}]OrientationChangeCallback"
		
		override fun onOrientationChanged(orientation: Int) {
			val newRotation: Int = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
			if (newRotation != oldRotation) {
				Log.d(tagOrientationChangeCallback, "Device was rotated. Reconstructing the Virtual Display now...")
				oldRotation = newRotation
				try {
					// Perform cleanup.
					virtualDisplay.release()
					
					// Now re-create the VirtualDisplay based on the new width and height of the rotated screen.
					createVirtualDisplay()
				} catch (e: Exception) {
					Log.e(tagOrientationChangeCallback, "Failed to perform cleanup and recreating the VirtualDisplay after device rotation.")
					Toast.makeText(
						myContext, "Failed to perform cleanup and recreating the VirtualDisplay after device rotation.",
						Toast.LENGTH_SHORT
					).show()
				}
			}
		}
	}
	
	/**
	 * Custom Callback for when it is necessary to stop the MediaProjection.
	 */
	private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
		private val tagMediaProjectionStopCallback = "[${MainActivity.loggerTag}]MediaProjectionStopCallback"
		
		override fun onStop() {
			threadHandler.post {
				isRunning = false
				
				// Destroy the VirtualDisplay.
				virtualDisplay.release()
				
				// Disable the OrientationChangeCallback.
				orientationChangeCallback?.disable()
				
				// Then remove this listener from the MediaProjection object.
				mediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
				
				// Finally, stop the Bot Service.
				val botStopIntent = Intent(myContext, BotService::class.java)
				stopService(botStopIntent)
				
				// Now set the MediaProjection object to null to eliminate the "Invalid media projection" error.
				mediaProjection = null
				
				Log.d(tagMediaProjectionStopCallback, "MediaProjection Service for $appName has stopped.")
				Toast.makeText(myContext, "MediaProjection Service for $appName has stopped.", Toast.LENGTH_SHORT).show()
			}
		}
	}
	
	/**
	 * Creates and starts the MediaProjection.
	 *
	 * @param resultCode The output of this service.
	 * @param data The data of this service.
	 */
	private fun startMediaProjection(resultCode: Int, data: Intent) {
		// Retrieve the MediaProjection object.
		if (mediaProjection == null) {
			mediaProjection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
		}
		
		// Get the WindowManager object.
		windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
		
		// Get the DefaultDisplay object.
		defaultDisplay = windowManager.defaultDisplay
		
		// Create the VirtualDisplay and start reading in screenshots.
		createVirtualDisplay()
		
		// Attach the OrientationChangeCallback.
		orientationChangeCallback = OrientationChangeCallback(this)
		if ((orientationChangeCallback as OrientationChangeCallback).canDetectOrientation()) {
			(orientationChangeCallback as OrientationChangeCallback).enable()
		}
		
		// Attach the MediaProjectionStopCallback to the MediaProjection object.
		mediaProjection?.registerCallback(MediaProjectionStopCallback(), threadHandler)
		
		Log.d(TAG, "MediaProjection Service for $appName is now running.")
		Toast.makeText(myContext, "MediaProjection Service for $appName is now running.", Toast.LENGTH_SHORT).show()
	}
	
	/**
	 * Stops the MediaProjection.
	 */
	private fun stopMediaProjection() {
		threadHandler.post {
			mediaProjection?.stop()
		}
	}
	
	/**
	 * Creates the VirtualDisplay and the ImageReader to start reading in screenshots.
	 */
	@SuppressLint("WrongConstant")
	private fun createVirtualDisplay() {
		// Get the full width and height of the device screen such that making a screenshot would not scale it down and creating black bars that
		// would offset the screen coordinates of matches by the difference.
		val metrics = DisplayMetrics()
		defaultDisplay.getRealMetrics(metrics)
		displayWidth = metrics.widthPixels
		displayHeight = metrics.heightPixels
		displayDPI = metrics.densityDpi
		
		Log.d(TAG, "Screen Width: $displayWidth, Screen Height: $displayHeight, Screen DPI: $displayDPI")
		
		// Start the ImageReader.
		imageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
		
		// Now create the VirtualDisplay.
		virtualDisplay = mediaProjection?.createVirtualDisplay(
			"$appName's Virtual Display", displayWidth, displayHeight,
			displayDPI, getVirtualDisplayFlags(), imageReader.surface, null, threadHandler
		)!!
	}
}