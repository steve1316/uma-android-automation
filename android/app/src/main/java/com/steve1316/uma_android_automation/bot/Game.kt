package com.steve1316.uma_android_automation.bot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.BotHold
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.BotStatus
import com.steve1316.automation_library.utils.DebugCapture
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.SkillDatabase
import com.steve1316.uma_android_automation.bot.Task
import com.steve1316.uma_android_automation.bot.campaigns.GrandLive
import com.steve1316.uma_android_automation.bot.campaigns.Trackblazer
import com.steve1316.uma_android_automation.bot.campaigns.UnityCup
import com.steve1316.uma_android_automation.bot.campaigns.UraFinale
import com.steve1316.uma_android_automation.bot.dailies.DAILIES_DEBUG_TEST_KEYS
import com.steve1316.uma_android_automation.bot.dailies.Dailies
import com.steve1316.uma_android_automation.components.BaseComponentInterface
import com.steve1316.uma_android_automation.components.LabelConnecting
import com.steve1316.uma_android_automation.components.LabelNowLoading
import com.steve1316.uma_android_automation.components.Region
import com.steve1316.uma_android_automation.utils.Anchor
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.uma_android_automation.utils.ScreenLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.core.Point
import java.text.DecimalFormat
import kotlin.intArrayOf

/**
 * Main driver for bot activity and navigation.
 *
 * @property myContext The Android [Context] for the application.
 */
class Game(val myContext: Context) {
    /** The current Android notification message to display. */
    var notificationMessage: String = ""

    /** The utility class for image processing and template matching. */
    val imageUtils: CustomImageUtils = CustomImageUtils(myContext, this)

    /** The Accessibility Service for performing screen gestures. Read on every access, since a restarted service is a new instance. */
    val gestureUtils: MyAccessibilityService get() = MyAccessibilityService.getInstance()

    /** The database for skill-related information. */
    val skillDatabase: SkillDatabase = SkillDatabase(this)

    /** The formatter for decimal values. */
    val decimalFormat = DecimalFormat("#.##")

    /** The current campaign scenario (e.g., "URA Finale", "Unity Cup", "Trackblazer"). */
    val scenario: String = SettingsHelper.getStringSetting("general", "scenario")

    /** Whether this run plays a career ("career") or clears the dailies ("dailies"). */
    val runMode: String = SettingsHelper.getStringSetting("general", "runMode", "career")

    /** Whether debug mode is enabled for additional logging and saving debugging images to storage. */
    val debugMode: Boolean = SettingsHelper.getBooleanSetting("debug", "enableDebugMode")

    /** JPEG quality of the saved debug images, handed to the library so every image in the bundle uses it. */
    val debugImageQuality: Int = SettingsHelper.getIntSetting("debug", "debugImageQuality", 50).also { DebugCapture.jpegQuality = it }

    /** The default wait delay between common actions. */
    val waitDelay: Double = SettingsHelper.getDoubleSetting("general", "waitDelay")

    /** The wait delay specifically for dialog interactions. */
    val dialogWaitDelay: Double = SettingsHelper.getDoubleSetting("general", "dialogWaitDelay")

    /** Holds the task instance corresponding to the selected scenario. Swapped for the career task when Dailies hands off to a new career. */
    var task: Task = if (runMode == "dailies") Dailies(this) else createCareerTask()
        private set

    /** The maximum number of connection error retry attempts allowed. */
    internal val maxConnectionErrorRetryAttempts: Int = 3

    /** The current number of connection error retry attempts. */
    internal var connectionErrorRetryAttempts: Int = 0

    /** The timestamp of the last connection error retry. */
    internal var lastConnectionErrorRetryTimeMs: Long = 0

    /** The cooldown time between connection error retries. */
    internal val connectionErrorRetryCooldownTimeMs: Long = 10000 // 10 seconds

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]Game"
    }

    // Initialize Discord settings from SQLite.
    init {
        DiscordUtils.enableDiscordNotifications = SettingsHelper.getBooleanSetting("discord", "enableDiscordNotifications", false)
        if (DiscordUtils.enableDiscordNotifications) {
            try {
                DiscordUtils.discordToken = SettingsHelper.getStringSetting("discord", "discordToken")
                DiscordUtils.discordUserID = SettingsHelper.getStringSetting("discord", "discordUserID")
            } catch (e: Exception) {
                Log.w(TAG, "[WARN] Failed to read Discord settings: ${e.message}")
                DiscordUtils.enableDiscordNotifications = false
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Waits the specified seconds to account for ping or loading.
     *
     * It also checks for interruption every 100ms to allow faster interruption and checks if the game is still in the middle of loading.
     *
     * @param seconds Number of seconds to pause execution.
     * @param skipWaitingForLoading If true, then it will skip the loading check. Defaults to false.
     */
    fun wait(seconds: Double, skipWaitingForLoading: Boolean = false) {
        val totalMillis = (seconds * 1000).toLong()
        // Check for interruption every 100ms.
        val checkInterval = 100L

        var remainingMillis = totalMillis
        while (remainingMillis > 0) {
            if (!BotService.isRunning) {
                throw InterruptedException()
            }

            // Let a pause abort the step here, so a long wait never delays it by more than one interval.
            BotHold.checkpoint()

            val sleepTime = minOf(checkInterval, remainingMillis)
            runBlocking {
                delay(sleepTime)
            }
            remainingMillis -= sleepTime
        }

        if (!skipWaitingForLoading) {
            // Check if the game is still loading as well.
            waitForLoading()
        }
    }

    /**
     * Waits for the game to finish loading.
     *
     * Note that this function is responsible for dictating how fast the bot will run so adjusting this should be done with caution.
     * It logs once when a load starts and once when it ends, not on every check.
     */
    fun waitForLoading() {
        var reason = loadingReason() ?: return
        MessageLog.i(TAG, "[LOADING] $reason Waiting...")
        val startTime = System.currentTimeMillis()
        while (true) {
            // Avoid an infinite loop by skipping the loading check inside wait().
            wait(waitDelay, skipWaitingForLoading = true)
            reason = loadingReason() ?: break
        }
        MessageLog.i(TAG, "[LOADING] Finished loading after ${"%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)}s.")
    }

    /**
     * Finds and taps the specified image.
     *
     * @param imageName Name of the button image file in the /assets/images/ folder.
     * @param sourceBitmap The source bitmap to find the image on. This is optional and defaults to null which will fetch its own source bitmap.
     * @param tries Number of tries to find the specified button. Defaults to 3.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param taps Specify the number of taps on the specified image. Defaults to 1.
     * @param suppressError Whether to suppress saving error messages to the log in failing to find the button. Defaults to false.
     * @return True if the button was found and clicked. False otherwise.
     */
    fun findAndTapImage(imageName: String, sourceBitmap: Bitmap? = null, tries: Int = 3, region: IntArray = intArrayOf(0, 0, 0, 0), taps: Int = 1, suppressError: Boolean = false): Boolean {
        if (debugMode) {
            MessageLog.d(TAG, "[DEBUG] findAndTapImage:: Now attempting to find and click the \"$imageName\" button.")
        }

        val tempLocation: Point? =
            if (sourceBitmap == null) {
                imageUtils.findImage(imageName, tries = tries, region = region, suppressError = suppressError).first
            } else {
                imageUtils.findImageWithBitmap(imageName, sourceBitmap, region = region, suppressError = suppressError)
            }

        return if (tempLocation != null) {
            Log.d(TAG, "[DEBUG] findAndTapImage:: Found and going to tap: $imageName")
            tap(tempLocation.x, tempLocation.y, imageName, taps = taps)
            true
        } else {
            false
        }
    }

    /**
     * Performs a tap on the screen at the coordinates and then will wait until the game processes the server request and gets a response back.
     *
     * @param x The x-coordinate.
     * @param y The y-coordinate.
     * @param imageName The template image name to use for tap location randomization.
     * @param taps The number of taps.
     * @param ignoreWaiting Flag to ignore checking if the game is busy loading.
     */
    fun tap(x: Double, y: Double, imageName: String? = null, taps: Int = 1, ignoreWaiting: Boolean = false) {
        // Perform the tap.
        gestureUtils.tap(x, y, imageName, taps = taps)

        if (!ignoreWaiting) {
            // Now check if the game is waiting for a server response from the tap and wait if necessary. wait() already runs waitForLoading() after the delay.
            wait(0.20)
        }
    }

    /**
     * Checks if the bot is at a "Now Loading..." screen or if the game is awaiting a server response.
     *
     * This may cause significant delays in normal bot processes.
     *
     * @return A short description of what the game is waiting on, or null if it is not loading.
     */
    private fun loadingReason(): String? {
        val sourceBitmap = imageUtils.getSourceBitmap()
        return when {
            LabelConnecting.check(imageUtils, sourceBitmap = sourceBitmap) -> "The game is awaiting a response from the server."
            LabelNowLoading.check(imageUtils, sourceBitmap = sourceBitmap) -> "The game is on a Now Loading screen."
            else -> null
        }
    }

    /**
     * Waits until one of the components appears, handling any dialog that pops up in the meantime.
     *
     * @param components The components to look for, in priority order.
     * @param timeoutSec How long to wait before giving up.
     * @param bHandleDialogs Whether to let the task handle dialogs between checks. Turn off while a dialog the caller handles itself is open.
     * @param stopIf Checked after each miss. When it turns true the wait ends early.
     * @param onMiss Runs after each miss, before the next check.
     * @return The component that appeared and its location, or null on timeout or when `stopIf` ended the wait.
     */
    fun waitForAny(
        vararg components: BaseComponentInterface,
        timeoutSec: Double = 10.0,
        bHandleDialogs: Boolean = true,
        stopIf: () -> Boolean = { false },
        onMiss: () -> Unit = {},
    ): Pair<BaseComponentInterface, Point>? {
        val deadline = System.currentTimeMillis() + (timeoutSec * 1000).toLong()
        while (true) {
            val sourceBitmap = imageUtils.getSourceBitmap()
            components.firstNotNullOfOrNull { component -> component.findImageWithBitmap(imageUtils, sourceBitmap)?.let { component to it } }?.let { return it }
            if (System.currentTimeMillis() > deadline) return null
            if (bHandleDialogs) task.tryHandleAllDialogs(timeoutMs = 3000, sourceBitmap = sourceBitmap)
            if (stopIf()) return null
            onMiss()
            wait(0.5)
        }
    }

    /**
     * Taps a neutral spot on the screen until one of the components appears. Used to skip race and reward animations.
     *
     * @param components The components that end the tapping, in priority order.
     * @param timeoutSec How long to keep tapping before giving up.
     * @param bHandleDialogs Whether the task's dialog handler runs between taps. Off when the caller handles its own dialogs.
     * @return The component that appeared and its location, or null on timeout.
     */
    fun tapUntil(vararg components: BaseComponentInterface, timeoutSec: Double = 45.0, bHandleDialogs: Boolean = true): Pair<BaseComponentInterface, Point>? =
        waitForAny(*components, timeoutSec = timeoutSec, bHandleDialogs = bHandleDialogs, onMiss = { tap(SharedData.displayWidth / 2.0, SharedData.displayHeight * 0.4, ignoreWaiting = true) })

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Builds the career task for the selected scenario.
     *
     * @return The scenario's career task.
     * @throws InterruptedException When the scenario setting is not a known scenario.
     */
    private fun createCareerTask(): Task =
        when (scenario) {
            "URA Finale" -> UraFinale(this)
            "Unity Cup" -> UnityCup(this)
            "Trackblazer" -> Trackblazer(this)
            "Grand Live" -> GrandLive(this)
            else -> throw InterruptedException("Invalid scenario: $scenario")
        }

    /**
     * Whether the run that just ended hands over to a new career: dailies that finished with "Start a Career When Done" on, or a career that was completed
     * back to Home to start the next one.
     *
     * @param result The result the run ended with.
     * @return True if a new career task should start.
     */
    private fun startsCareerNext(result: TaskResult): Boolean =
        (task as? Campaign)?.bStartNextCareer == true ||
            (task !is Campaign && runMode == "dailies" && result.code == TaskResultCode.TASK_RESULT_COMPLETE && SettingsHelper.getBooleanSetting("dailies", "startCareerWhenDone", false))

    /**
     * Begins automation here.
     *
     * @return True if all automation goals have been met. False otherwise.
     */
    fun start(): Boolean {
        MessageLog.i(TAG, "Started at ${MessageLog.getSystemTimeString()}.")
        val startTime: Long = System.currentTimeMillis()

        // Print current app settings at the start of the run.
        try {
            val formattedSettingsString = SettingsHelper.getStringSetting("misc", "formattedSettingsString")
            MessageLog.i(TAG, "\n[SETTINGS] Current Bot Configuration:")
            MessageLog.i(TAG, "=====================================")
            formattedSettingsString.split("\n").forEach { line ->
                if (line.isNotEmpty()) {
                    MessageLog.i(TAG, line)
                }
            }
            MessageLog.i(TAG, "=====================================\n")
        } catch (e: Exception) {
            MessageLog.w(TAG, "[WARN] start:: Failed to load formatted settings from SQLite: ${e.message}")
            MessageLog.i(TAG, "[INFO] Using fallback settings display...")

            // Fallback to basic settings display if formatted string is not available.
            MessageLog.i(TAG, "[INFO] Scenario: $scenario")
            MessageLog.i(TAG, "[INFO] Debug Mode: $debugMode")
        }

        // Print device and version information.
        MessageLog.i(TAG, "[INFO] Device Information: ${SharedData.displayWidth}x${SharedData.displayHeight}, DPI ${SharedData.displayDPI}")
        Region.refresh()
        val topInset = ScreenLayout.detectTopInset(myContext) { imageUtils.getSourceBitmap() }
        ScreenLayout.refresh(SharedData.displayWidth, SharedData.displayHeight, topInset)
        MessageLog.i(TAG, "[INFO] Screen layout: ${topInset}px cutout band, centered UI shifted ${ScreenLayout.shift(Anchor.CENTER)}px, bottom UI ${ScreenLayout.shift(Anchor.BOTTOM)}px.")
        val isConfig1 = SharedData.displayWidth == 1080 && SharedData.displayHeight == 1920 && SharedData.displayDPI == 240
        val isConfig2 = SharedData.displayWidth == 1080 && SharedData.displayHeight == 2340 && SharedData.displayDPI == 450
        if (!isConfig1 && !isConfig2) {
            MessageLog.w(
                TAG,
                "[WARN] ⚠️ Bot performance will be severely degraded since display configuration is not 1080x1920 @ 240 DPI or 1080x2340 @ 450 DPI unless an appropriate scale is set for your device.",
            )
        }
        if (debugMode) MessageLog.w(TAG, "[WARN] ⚠️ Debug Mode is enabled. All bot operations will be significantly slower as a result.")
        if (SettingsHelper.getDoubleSetting("debug", "templateMatchCustomScale", 1.0) != 1.0) {
            MessageLog.w(
                TAG,
                "[WARN] Manual scale has been set to ${SettingsHelper.getDoubleSetting("debug", "templateMatchCustomScale", 1.0)}",
            )
        }
        MessageLog.w(
            TAG,
            "[WARN] ⚠️ Note that certain Android notification styles (like banners) are big enough that they cover the area that contains the Mood which will interfere with mood recovery logic in the beginning.",
        )
        val packageInfo = myContext.packageManager.getPackageInfo(myContext.packageName, 0)
        MessageLog.i(TAG, "[INFO] Bot version: ${packageInfo.versionName} (${packageInfo.versionCode})\n\n")

        task.onRunStart(startTime, myContext)

        // A debug test armed for the other run mode would be skipped and a full run would start in its place, so stop with the reason instead.
        val otherModeTest = otherModeDebugTest()
        if (otherModeTest != null) {
            val (mode, otherMode) = if (runMode == "dailies") "Dailies" to "Career" else "Career" to "Dailies"
            val reason = "$otherModeTest is armed, but Run Mode is $mode. Switch to $otherMode or turn the test off"
            MessageLog.w(TAG, "[WARN] $reason.")
            BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_BOT, reason)
            task.onRunEnd()
            return true
        }

        // Start debug tests here if enabled. Otherwise, proceed with regular bot operations.
        // A small delay here to ensure any notifications are out of the way.
        wait(3.0)
        if (!task.startTests()) {
            // Send Discord notification that the run has started.
            if (DiscordUtils.enableDiscordNotifications) {
                val enableRemoteLogViewer = SettingsHelper.getBooleanSetting("debug", "enableRemoteLogViewer", false)
                var logViewerString = ""
                if (enableRemoteLogViewer) {
                    // Notify the user that the Remote Log Viewer is enabled and is viewable at the indicated address.
                    val port = SettingsHelper.getIntSetting("debug", "remoteLogViewerPort", 9000)
                    val ipAddress = com.steve1316.uma_android_automation.utils.LogStreamServer.getDeviceIpAddress(myContext)
                    val finalIpAddress = if (ipAddress == "10.0.2.15") "localhost" else ipAddress
                    logViewerString = "Remote Log Viewer is enabled at http://$finalIpAddress:$port"
                }
                val runName = if (runMode == "dailies") "Dailies" else "Scenario: $scenario"
                DiscordUtils.queue.add("```diff\n+ ${MessageLog.getSystemTimeString()} Bot run started! $runName```$logViewerString")
            }
            var result = task.start()
            // Finished dailies or a completed career hand over to a fresh career, which Career Setup starts from Home. The chain ends once a run stops for any
            // other reason, such as running out of TP.
            while (startsCareerNext(result)) {
                MessageLog.i(TAG, "[INFO] ${if (task is Campaign) "Career completed" else "Dailies done"}. Starting a new $scenario career.")
                task.onRunEnd()
                // The run that just ended recorded its outcome, which the task loop reads as a stop request, so the new career starts from a clean status.
                BotStatus.reset()
                task = createCareerTask()
                task.onRunStart(System.currentTimeMillis(), myContext)
                result = task.start()
            }
        }

        task.onRunEnd()
        MessageLog.i(TAG, "[INFO] Total runtime of ${MessageLog.formatElapsedTime(startTime, System.currentTimeMillis())} and stopped at ${MessageLog.getSystemTimeString()}.")

        // Wait to make sure Discord webhook message queue gets fully processed before terminating Bot Thread.
        if (DiscordUtils.enableDiscordNotifications) {
            wait(1.0, skipWaitingForLoading = true)
        }

        return true
    }

    /**
     * Finds an armed debug test that belongs to the other run mode.
     *
     * @return The test's readable name, such as "Dailies Presents Test", or null when none is armed.
     */
    private fun otherModeDebugTest(): String? {
        val otherModeKeys = if (runMode == "dailies") CAREER_DEBUG_TEST_KEYS else DAILIES_DEBUG_TEST_KEYS.keys
        val key = otherModeKeys.firstOrNull { SettingsHelper.getBooleanSetting("debug", it) } ?: return null
        return key.removePrefix("debugMode_start").replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
    }
}
