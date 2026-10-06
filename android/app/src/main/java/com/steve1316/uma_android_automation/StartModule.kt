package com.steve1316.uma_android_automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.steve1316.automation_library.events.ExceptionEvent
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import com.steve1316.automation_library.utils.AppUpdater
import com.steve1316.automation_library.utils.BatteryOptimizationUtils
import com.steve1316.automation_library.utils.MediaProjectionService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.utils.LogStreamServer
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.SubscriberExceptionEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Takes care of setting up internal processes such as the Accessibility and MediaProjection services, receiving and sending messages over to the Javascript frontend, and handle tests involving
 * Discord and Twitter API integrations if needed.
 *
 * Loaded into the React PackageList via MainApplication's instantiation of the StartPackage.
 */
class StartModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    companion object {
        private val TAG = "[${MainActivity.loggerTag}]StartModule"
        private var reactContext: ReactApplicationContext? = null
    }

    private val context: Context = reactContext.applicationContext
    private var messageId = 1

    init {
        StartModule.reactContext = reactContext
        StartModule.reactContext?.addActivityEventListener(this)

        // Listen for bot events for this module's whole life, so a React context recreated mid-run still gets them. `invalidate()` ends it.
        EventBus.getDefault().register(this)
        Log.d(TAG, "StartModule is now initialized.")
    }

    /**
     * Stops listening for bot events when React Native tears this module down, so a recreated context's module is the only listener.
     */
    override fun invalidate() {
        EventBus.getDefault().unregister(this)
        super.invalidate()
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
                MediaProjectionService.getStartIntent(reactContext!!, resultCode, data!!),
            )
            sendEvent("MediaProjectionService", "Running")
            Log.d(TAG, "MediaProjectionService is now running.")
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Interaction with the Start / Stop button.

    /** This is called when the Start button is pressed back at the Javascript frontend and starts up the MediaProjection service along with the BotService attached to it. */
    @ReactMethod
    fun start() {
        if (readyCheck()) {
            // Initialize SQLite settings.
            Log.d(TAG, "Starting SQLite settings initialization...")

            // Check if the database file exists.
            val dbFile = File(context.filesDir, "SQLite/settings.db")
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
            val sqliteDir = File(context.filesDir, "SQLite")
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

            // Initialize the SettingsHelper's connection to the SQLite database.
            // This is required to correctly fetch the flag for enabling the Remote Log Viewer.
            if (!SettingsHelper.isAvailable()) {
                SettingsHelper.initialize(context)
            }

            // Start the remote log stream server if enabled in settings.
            val enableRemoteLogViewer = SettingsHelper.getBooleanSetting("debug", "enableRemoteLogViewer", false)
            Log.d(TAG, "Able to start Remote Log Viewer in start(): $enableRemoteLogViewer")
            if (enableRemoteLogViewer) {
                val port = SettingsHelper.getIntSetting("debug", "remoteLogViewerPort", 9000)
                LogStreamServer.start(context, port)
            }

            startProjection()
        }
    }

    /** Register this module with EventBus in order to allow listening to certain events and then begin starting up the MediaProjection service. */
    private fun startProjection() {
        Log.d(TAG, "Event Bus registered for StartModule")

        // Use the library's helper which applies MediaProjectionConfig on Android 14+ to prefer full screen capture.
        val screenCaptureIntent = MediaProjectionService.getScreenCaptureIntent(reactContext!!)
        reactContext?.startActivityForResult(screenCaptureIntent, 100, null)
    }

    /**
     * Stops the MediaProjection service. This module stays registered with EventBus for its whole life, so the run-end report and the final log
     * lines sent while the service shuts down still reach the app.
     */
    private fun stopProjection() {
        reactContext?.startService(MediaProjectionService.getStopIntent(reactContext!!))
        sendEvent("MediaProjectionService", "Not Running")
    }

    /** This is called when the Stop button is pressed and will begin stopping the MediaProjection service. */
    @ReactMethod
    fun stop() {
        stopProjection()
    }

    /** Opens the system Accessibility settings page to allow the user to toggle the service off and on. */
    @ReactMethod
    fun openAccessibilitySettings() {
        Log.d(TAG, "Opening Accessibility Settings...")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        this.reactApplicationContext.currentActivity?.startActivity(intent)
    }

    /**
     * Checks the status of the Accessibility Service, checking both if it is enabled in settings and if it is actually initialized.
     *
     * @param promise The React Native promise that resolves the WritableMap of metrics.
     */
    @ReactMethod
    fun getAccessibilityStatus(promise: Promise) {
        try {
            val map = Arguments.createMap()
            val context = reactApplicationContext

            // Method 1: Check Settings.Secure
            val prefString = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val serviceName = context.packageName + "/" + MyAccessibilityService::class.java.name
            val enabledInSettings = prefString?.contains(serviceName) == true
            Log.d(TAG, "Accessibility enabled in Settings: $enabledInSettings")

            // Method 2: Check AccessibilityManager
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            var enabledInManager = false
            for (info in enabledServices) {
                if (info.resolveInfo.serviceInfo.packageName == context.packageName &&
                    info.resolveInfo.serviceInfo.name == MyAccessibilityService::class.java.name
                ) {
                    enabledInManager = true
                    break
                }
            }
            Log.d(TAG, "Accessibility enabled in Manager: $enabledInManager")

            map.putBoolean("enabled", enabledInSettings || enabledInManager)

            // Check if active (initialized).
            var active = false
            try {
                MyAccessibilityService.getInstance()
                active = true
            } catch (e: IllegalStateException) {
                // If the message is "not running" but initialized, it means it is actually ready.
                if (e.message?.contains("not running") == true) {
                    active = true
                } else {
                    Log.d(TAG, "Accessibility Service is not initialized: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility Service instance check failed: ${e.message}")
            }
            map.putBoolean("active", active)

            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve accessibility status: ${e.message}")
            promise.reject("ACCESSIBILITY_STATUS_ERROR", "Failed to retrieve accessibility status: ${e.message}")
        }
    }

    /**
     * Opens this app's "App Info" page in system settings. Needed on recent Android versions where the Accessibility Service toggle is greyed out
     * until the user taps the 3-dot menu in App Info and enables "Allow restricted settings".
     */
    @ReactMethod
    fun openAppInfoSettings() {
        Log.d(TAG, "Opening App Info Settings...")
        val uri = "package:${reactApplicationContext.packageName}".toUri()
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        this.reactApplicationContext.currentActivity?.startActivity(intent)
    }

    /** Opens the system "Display over other apps" settings page for this app so the user can toggle the overlay permission. */
    @ReactMethod
    fun openOverlaySettings() {
        Log.d(TAG, "Opening Overlay Settings...")
        val uri = "package:${reactApplicationContext.packageName}".toUri()
        val intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        this.reactApplicationContext.currentActivity?.startActivity(intent)
    }

    /**
     * Checks whether the app currently has the overlay (SYSTEM_ALERT_WINDOW) permission.
     *
     * @param promise The React Native promise that resolves a WritableMap with an `enabled` boolean.
     */
    @ReactMethod
    fun getOverlayStatus(promise: Promise) {
        try {
            val enabled = Settings.canDrawOverlays(reactApplicationContext)
            Log.d(TAG, "Overlay permission enabled: $enabled")
            val map = Arguments.createMap()
            map.putBoolean("enabled", enabled)
            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve overlay status: ${e.message}")
            promise.reject("OVERLAY_STATUS_ERROR", "Failed to retrieve overlay status: ${e.message}")
        }
    }

    /** Opens the system battery optimization settings page so the user can exempt this app. */
    @ReactMethod
    fun openBatteryOptimizationSettings() {
        Log.d(TAG, "Opening Battery Optimization Settings...")
        BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context)
    }

    /**
     * Checks whether the app is currently ignoring battery optimizations.
     *
     * @param promise The React Native promise that resolves a WritableMap with an `enabled` boolean (true means battery optimization is disabled for this app).
     */
    @ReactMethod
    fun getBatteryOptimizationStatus(promise: Promise) {
        try {
            val enabled = BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)
            Log.d(TAG, "Battery optimization disabled (ignoring): $enabled")
            val map = Arguments.createMap()
            map.putBoolean("enabled", enabled)
            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve battery optimization status: ${e.message}")
            promise.reject("BATTERY_OPTIMIZATION_STATUS_ERROR", "Failed to retrieve battery optimization status: ${e.message}")
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
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
                """.trimIndent(),
            )
            setPositiveButton("Go to App Info") { _, _ ->
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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
     * This ensures the app can run reliably in the background without being killed by Android's battery optimization features during long-running automation tasks.
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

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Event interaction

    /**
     * Listener function to start this module's entry point.
     *
     * @param event The StartEvent object to parse its message.
     */
    @Subscribe
    fun onStartEvent(event: StartEvent) {
        if (event.message == "Entry Point ON") {
            // Reset the log stream mute to ensure logs for the new run are broadcasted.
            LogStreamServer.resetMute()

            val entryPoint = Game(context)

            val botThread =
                Thread {
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
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    /**
     * Tests the Discord connection by creating a temporary Kord client, looking up the user, opening a DM channel, and sending a test message.
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

                    val user =
                        try {
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

                    val dmChannel =
                        try {
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
            val metrics = android.util.DisplayMetrics()

            @Suppress("DEPRECATION")
            val display = reactApplicationContext.getSystemService(android.view.WindowManager::class.java).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
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
     * Shows the changelog dialog for the currently installed app version. Reuses the library's update dialog so it matches the updater UI.
     *
     * @param promise Resolves once the dialog has been dispatched on the UI thread. The dialog fetch may still happen asynchronously.
     */
    @ReactMethod
    fun showChangelog(promise: Promise) {
        try {
            val activity = this.reactApplicationContext.currentActivity
            if (activity == null) {
                promise.reject("NO_ACTIVITY", "Cannot show the changelog because there is no current Activity.")
                return
            }
            appUpdater(activity).showCurrentChangelog()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("CHANGELOG_ERROR", "Failed to show changelog: ${e.message}")
        }
    }

    /**
     * Builds the library's app updater for this app's GitHub releases.
     *
     * @param activity The [Activity] the update dialog is shown on.
     * @return An [AppUpdater] for this repository and installed version.
     */
    private fun appUpdater(activity: Activity): AppUpdater = AppUpdater(activity, AppUpdater.Config("steve1316", "uma-android-automation", BuildConfig.VERSION_NAME))

    /**
     * Checks GitHub for a newer app release and shows the update dialog when one exists.
     *
     * @param promise Resolves `{ status, version }` where status is "available" (the update dialog was shown), "held" (the bot is running), or
     *   "upToDate". Rejects when GitHub cannot be reached.
     */
    @ReactMethod
    fun checkForAppUpdate(promise: Promise) {
        val activity = this.reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "Cannot check for app updates because there is no current Activity.")
            return
        }
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                val result = appUpdater(activity).checkForUpdate()
                val status =
                    when (result.decision) {
                        AppUpdater.Decision.SHOW -> "available"
                        AppUpdater.Decision.HOLD -> "held"
                        AppUpdater.Decision.UP_TO_DATE -> "upToDate"
                    }
                promise.resolve(
                    Arguments.createMap().apply {
                        putString("status", status)
                        putString("version", result.version)
                    },
                )
            } catch (e: Exception) {
                promise.reject("APP_UPDATE_ERROR", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Reports whether the overlay service is on, so Home can catch up after its screen is recreated.
     *
     * @param promise Resolves true when the overlay service is running.
     */
    @ReactMethod
    fun isOverlayOn(promise: Promise) {
        promise.resolve(MediaProjectionService.isRunning)
    }

    /**
     * Sends the message back to the Javascript frontend along with its event name to be listened on.
     *
     * @param eventName The name of the event to be picked up on as defined in the developer's JS frontend.
     * @param message The message string to pass on.
     */
    fun sendEvent(eventName: String, message: String) {
        val params = Arguments.createMap()
        params.putString("message", message)
        params.putInt("id", messageId++)
        // React Native caches the emitter per context, so fetching it each time always targets the live context.
        reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, params)
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
            MessageLog.e(MainActivity.loggerTag, "\t$line", skipPrintTime = true)
        }
        MessageLog.d(MainActivity.loggerTag, "", skipPrintTime = true)
    }
}
