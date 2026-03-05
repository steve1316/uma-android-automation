package com.steve1316.uma_android_automation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.steve1316.automation_library.events.ExceptionEvent
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import com.steve1316.automation_library.utils.MediaProjectionService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.automation_library.utils.BatteryOptimizationUtils
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.utils.LogStreamServer
import com.steve1316.automation_library.utils.SettingsHelper
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.SubscriberExceptionEvent
import androidx.core.net.toUri
import com.facebook.react.bridge.Promise
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Takes care of setting up internal processes such as the Accessibility and MediaProjection services, receiving and sending messages over to the
 * Javascript frontend, and handle tests involving Discord and Twitter API integrations if needed.
 * <p>
 * Loaded into the React PackageList via MainApplication's instantiation of the StartPackage.
 */
class StartModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    private val TAG = "[${MainActivity.loggerTag}]StartModule"
    
    companion object {
        private var reactContext: ReactApplicationContext? = null
        private var emitter: DeviceEventManagerModule.RCTDeviceEventEmitter? = null
    }
    
    private val context: Context = reactContext.applicationContext
    private var messageId = 1

    init {
        StartModule.reactContext = reactContext
        StartModule.reactContext?.addActivityEventListener(this)
        Log.d(TAG, "StartModule is now initialized.")
    }

    override fun getName(): String {
        return "StartModule"
    }

    override fun onNewIntent(intent: Intent) {
        // Empty implementation
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            // Start up the MediaProjection service after the user accepts the onscreen prompt.
            reactContext?.startService(
                MediaProjectionService.getStartIntent(reactContext!!, resultCode, data!!)
            )
            sendEvent("MediaProjectionService", "Running")
            Log.d(TAG, "MediaProjectionService is now running.")
        }
    }

    ////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////
    // Interaction with the Start / Stop button.

    /**
     * This is called when the Start button is pressed back at the Javascript frontend and starts up the MediaProjection service along with the
     * BotService attached to it.
     */
    @ReactMethod
    fun start() {
        if (readyCheck()) {
            startProjection()
        }
    }

    /**
     * Register this module with EventBus in order to allow listening to certain events and then begin starting up the MediaProjection service.
     */
    private fun startProjection() {
        // This extra call to unregister is to account for the user stopping the service from the notification which bypasses the call to
        // unregister in stopProjection().
        EventBus.getDefault().unregister(this)
        EventBus.getDefault().register(this)
        Log.d(TAG, "Event Bus registered for StartModule")

        // Use the library's helper which applies MediaProjectionConfig on Android 14+ to prefer full screen capture.
        val screenCaptureIntent = MediaProjectionService.getScreenCaptureIntent(reactContext!!)
        reactContext?.startActivityForResult(screenCaptureIntent, 100, null)
    }

    /**
     * Unregister this module with EventBus and then stops the MediaProjection service.
     */
    private fun stopProjection() {
        // Stop the remote log stream server if it is running.
        LogStreamServer.stop()

        EventBus.getDefault().unregister(this)
        Log.d(TAG, "Event Bus unregistered for StartModule")
        reactContext?.startService(MediaProjectionService.getStopIntent(reactContext!!))
        sendEvent("MediaProjectionService", "Not Running")
    }

    /**
     * This is called when the Stop button is pressed and will begin stopping the MediaProjection service.
     */
    @ReactMethod
    fun stop() {
        stopProjection()
    }

    ////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////
    // Permissions

    /**
     * Checks the permissions for both overlay and accessibility for this app.
     *
     * @return True if both permissions were already granted and false otherwise.
     */
    private fun readyCheck(): Boolean {
        return checkForOverlayPermission() && checkForAccessibilityPermission() && checkForBatteryOptimization()
    }

    /**
     * Checks for overlay permission and guides the user to enable it if it has not been granted yet.
     *
     * @return True if the overlay permission has already been granted.
     */
    private fun checkForOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this.reactApplicationContext.currentActivity)) {
            Log.d(TAG, "Application is missing overlay permission.")

            val builder = AlertDialog.Builder(this.reactApplicationContext.currentActivity)
            builder.setTitle(R.string.overlay_disabled)
            builder.setMessage(R.string.overlay_disabled_message)

            builder.setPositiveButton(R.string.go_to_settings) { _, _ ->
                // Send the user to the Overlay Settings.
                val uri = "package:${reactContext?.packageName}"
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri.toUri())
                this.reactApplicationContext.currentActivity?.startActivity(intent)
            }

            builder.setNegativeButton(android.R.string.cancel, null)

            builder.show()
            return false
        }

        Log.d(TAG, "Application has permission to draw overlay.")
        return true
    }

    /**
     * Checks for accessibility permission and guides the user to enable it if it has not been granted yet.
     *
     * @return True if the accessibility permission has already been granted.
     */
    private fun checkForAccessibilityPermission(): Boolean {
        val prefString = Settings.Secure.getString(reactContext?.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

        if (prefString != null && prefString.isNotEmpty()) {
            // Check the string of enabled accessibility services to see if this application's accessibility service is there.
            val enabled = prefString.contains(reactContext?.packageName.toString() + "/" + MyAccessibilityService::class.java.name)

            if (enabled) {
                Log.d(TAG, "This application's Accessibility Service is currently turned on.")
                return true
            }
        }

        // Shows a dialog explaining how to enable Accessibility Service when restricted settings are detected.
        // The dialog provides options to navigate to App Info or Accessibility Settings to complete the setup.
        AlertDialog.Builder(this.reactApplicationContext.currentActivity).apply {
            setTitle(R.string.accessibility_disabled)
            setMessage(
                """
            To enable Accessibility Service:
            
            1. Tap "Go to App Info".
            2. Tap the 3-dot menu in the top right. If not available, you can skip to step 4.
            3. Tap "Allow restricted settings".
            4. Return to Accessibility Settings and enable the service.
            """.trimIndent()
            )
            setPositiveButton("Go to App Info") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${reactContext?.packageName}".toUri()
                }
                this@StartModule.reactApplicationContext.currentActivity?.startActivity(intent)
            }
            setNeutralButton("Accessibility Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                this@StartModule.reactApplicationContext.currentActivity?.startActivity(intent)
            }
            setNegativeButton(android.R.string.cancel, null)
        }.show()

        return false
    }

    /**
     * Checks if battery optimization is disabled for this app and guides the user to enable it if needed.
     *
     * This ensures the app can run reliably in the background without being killed by Android's
     * battery optimization features during long-running automation tasks.
     *
     * @return True if battery optimization is already disabled for this app.
     */
    private fun checkForBatteryOptimization(): Boolean {
        if (BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)) {
            Log.d(TAG, "Application is already ignoring battery optimizations.")
            return true
        }

        Log.d(TAG, "Application is not ignoring battery optimizations.")

        AlertDialog.Builder(this.reactApplicationContext.currentActivity).apply {
            setTitle(R.string.battery_optimization_title)
            setMessage(R.string.battery_optimization_message)
            setPositiveButton(R.string.go_to_settings) { _, _ ->
                BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context)
            }
            setNegativeButton(android.R.string.cancel, null)
        }.show()

        return false
    }

    ////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////
    // Event interaction

    /**
     * Listener function to start this module's entry point.
     *
     * @param event The StartEvent object to parse its message.
     */
    @Subscribe
    fun onStartEvent(event: StartEvent) {
        if (event.message == "Entry Point ON") {
            // Initialize SQLite settings with detailed debugging.
            Log.d(TAG, "Starting SQLite settings initialization...")
            
            // Check if the database file exists.
            val dbFile = java.io.File(context.filesDir, "SQLite/settings.db")
            Log.d(TAG, "Database file path: ${dbFile.absolutePath}")
            Log.d(TAG, "Database file exists: ${dbFile.exists()}")
            Log.d(TAG, "Database file can read: ${dbFile.canRead()}")
            Log.d(TAG, "Database file size: ${if (dbFile.exists()) dbFile.length() else "N/A"} bytes")
            
            // List the contents of the files directory to see what's actually there.
            val filesDir = context.filesDir
            Log.d(TAG, "Files directory: ${filesDir.absolutePath}")
            val files = filesDir.listFiles()
            if (files != null) {
                Log.d(TAG, "Files in files directory:")
                for (file in files) {
                    Log.d(TAG, "  - ${file.name} (${if (file.isDirectory) "dir" else "file"})")
                }
            }
            
            // Check if SQLite subdirectory exists.
            val sqliteDir = java.io.File(context.filesDir, "SQLite")
            Log.d(TAG, "SQLite directory exists: ${sqliteDir.exists()}")
            if (sqliteDir.exists()) {
                val sqliteFiles = sqliteDir.listFiles()
                if (sqliteFiles != null) {
                    Log.d(TAG, "Files in SQLite directory:")
                    for (file in sqliteFiles) {
                        Log.d(TAG, "  - ${file.name} (${file.length()} bytes)")
                    }
                }
            }

            // Start the remote log stream server if enabled in settings.
            val enableRemoteLogViewer = SettingsHelper.getBooleanSetting("debug", "enableRemoteLogViewer", false)
            if (enableRemoteLogViewer) {
                val port = SettingsHelper.getIntSetting("debug", "remoteLogViewerPort", 9000)
                LogStreamServer.start(context, port)
            }

            val entryPoint = Game(context)

            val botThread = Thread {
                try {
                    entryPoint.start()
                } catch (e: Exception) {
                    EventBus.getDefault().postSticky(ExceptionEvent(e))
                }
            }
            
            botThread.start()
            
            try {
                botThread.join()
            } catch (e: InterruptedException) {
                Log.d(TAG, "EventBus StartEvent subscriber was interrupted. Propagating to Bot Thread...")
                botThread.interrupt()
                try {
                    botThread.join()
                } catch (_: InterruptedException) {}
            } finally {
                // Stop the remote log stream server when the bot finishes.
                LogStreamServer.stop()
            }
        }
    }

    /**
     * Tests the Discord connection by creating a temporary Kord client,
     * looking up the user, opening a DM channel, and sending a test message.
     *
     * @param token The Discord bot token.
     * @param userID The Discord user ID to send the test message to.
     * @param promise The React Native promise to resolve or reject.
     */
    @ReactMethod
    fun testDiscordConnection(token: String, userID: String, promise: Promise) {
        Log.d(TAG, "testDiscordConnection called - token length: ${token.length}, userID: '$userID'")
        Thread {
            runBlocking {
                try {
                    val client = Kord(token)

                    val user = try {
                        client.getUser(Snowflake(userID.toLong()))
                    } catch (e: Exception) {
                        client.shutdown()
                        promise.reject("DISCORD_USER_ERROR", "Failed to find user with the provided user ID.")
                        return@runBlocking
                    }

                    if (user == null) {
                        client.shutdown()
                        promise.reject("DISCORD_USER_ERROR", "Failed to find user with the provided user ID.")
                        return@runBlocking
                    }

                    val dmChannel = try {
                        user.getDmChannel()
                    } catch (e: Exception) {
                        client.shutdown()
                        promise.reject("DISCORD_DM_ERROR", "Failed to open DM channel with user.")
                        return@runBlocking
                    }

                    // Prepend a timestamp to the test message.
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    dmChannel.createMessage("[$timestamp] \u2705 Test message from Uma Android Automation! Discord integration is working.")
                    client.shutdown()
                    promise.resolve("Test message sent successfully!")
                } catch (e: Exception) {
                    Log.e(TAG, "Discord connection test failed: ${e.message}")
                    promise.reject("DISCORD_ERROR", "Failed to connect to Discord: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Retrieves the device's exact width, height, and DPI metrics.
     *
     * @param promise The React Native promise that resolves the WritableMap of metrics.
     */
    @ReactMethod
    fun getDeviceDimensions(promise: Promise) {
        try {
            val metrics = reactApplicationContext.resources.displayMetrics
            val map = Arguments.createMap()
            map.putInt("width", metrics.widthPixels)
            map.putInt("height", metrics.heightPixels)
            map.putInt("dpi", metrics.densityDpi)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("DEVICE_INFO_ERROR", "Failed to retrieve device dimensions: ${e.message}")
        }
    }

    /**
     * Retrieves the device's WiFi IP address for the Remote Log Viewer.
     *
     * @param promise The React Native promise that resolves with the IP address string.
     */
    @ReactMethod
    fun getDeviceIpAddress(promise: Promise) {
        try {
            val ipAddress = LogStreamServer.getDeviceIpAddress(context)
            promise.resolve(ipAddress)
        } catch (e: Exception) {
            promise.reject("IP_ADDRESS_ERROR", "Failed to retrieve device IP address: ${e.message}")
        }
    }

    /**
     * Sends the message back to the Javascript frontend along with its event name to be listened on.
     *
     * @param eventName The name of the event to be picked up on as defined in the developer's JS frontend.
     * @param message   The message string to pass on.
     */
    fun sendEvent(eventName: String, message: String) {
        val params = Arguments.createMap()
        params.putString("message", message)
        params.putInt("id", messageId++)
        if (emitter == null) {
            // Register the event emitter to send messages to JS.
            Log.d(TAG, "Event emitter not found to be able to send messages to the frontend. Registering now.")
            emitter = reactContext?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        }

        emitter?.emit(eventName, params)
    }

    /**
     * Listener function to call the inner event sending function in order to send the message back to the Javascript frontend.
     *
     * @param event The JSEvent object to parse its event name and message.
     */
    @Subscribe
    fun onJSEvent(event: JSEvent) {
        // Only send the event to the React Native frontend if it's not internal.
        // This prevents flooding the bridge during parallel operations where disableOutput is true.
        if (!event.isInternal) {
            sendEvent(event.eventName, event.message)
        }
    }

    /**
     * Listener function to send Exception messages back to the Javascript frontend.
     *
     * @param event The SubscriberExceptionEvent object to parse its event name and message.
     */
    @Subscribe
    fun onSubscriberExceptionEvent(event: SubscriberExceptionEvent) {
        Log.e(TAG, "Received exception event to send: ${event.throwable}")
        MessageLog.e(MainActivity.loggerTag, event.throwable.toString())
        for (line in event.throwable.stackTrace) {
            MessageLog.e(MainActivity.loggerTag, "\t${line}", skipPrintTime = true)
        }
        MessageLog.d(MainActivity.loggerTag, "", skipPrintTime = true)
    }
}