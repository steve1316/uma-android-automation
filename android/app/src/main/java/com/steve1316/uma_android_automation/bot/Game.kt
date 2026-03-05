package com.steve1316.uma_android_automation.bot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.campaigns.UnityCup
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.automation_library.utils.ImageUtils.ScaleConfidenceResult
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.utils.GameDate
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.bot.Trainee
import com.steve1316.uma_android_automation.bot.SkillPlan
import com.steve1316.uma_android_automation.bot.SkillDatabase

import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.Mood

import com.steve1316.uma_android_automation.components.DialogUtils
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.ButtonHomeFullStats
import com.steve1316.uma_android_automation.components.ButtonHomeFansInfo
import com.steve1316.uma_android_automation.components.ButtonCraneGame
import com.steve1316.uma_android_automation.components.ButtonCraneGameOk
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.IconTazuna
import com.steve1316.uma_android_automation.components.IconRaceDayRibbon
import com.steve1316.uma_android_automation.components.IconGoalRibbon
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonUnityCupRace
import com.steve1316.uma_android_automation.components.ButtonCompleteCareer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.core.Point
import java.text.DecimalFormat
import kotlin.intArrayOf

/**
 * Main driver for bot activity and navigation.
 */
class Game(val myContext: Context) {
	private val TAG: String = "[${MainActivity.loggerTag}]Game"
	var notificationMessage: String = ""

	val imageUtils: CustomImageUtils = CustomImageUtils(myContext, this)
	val gestureUtils: MyAccessibilityService = MyAccessibilityService.getInstance()
    val skillDatabase: SkillDatabase = SkillDatabase(this)

	val decimalFormat = DecimalFormat("#.##")

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SQLite Settings
	val scenario: String = SettingsHelper.getStringSetting("general", "scenario")
	val debugMode: Boolean = SettingsHelper.getBooleanSetting("debug", "enableDebugMode")
    val enableSkillPointCheck: Boolean = SettingsHelper.getBooleanSetting("skills", "enableSkillPointCheck")
	val skillPointsRequired: Int = SettingsHelper.getIntSetting("skills", "skillPointCheck")
	private val enablePopupCheck: Boolean = SettingsHelper.getBooleanSetting("general", "enablePopupCheck")
    private val enableCraneGameAttempt: Boolean = SettingsHelper.getBooleanSetting("general", "enableCraneGameAttempt")
    private val enableStopBeforeFinals: Boolean = SettingsHelper.getBooleanSetting("general", "enableStopBeforeFinals")
    private val enableStopAtDate: Boolean = SettingsHelper.getBooleanSetting("general", "enableStopAtDate")
    private val stopAtDate: String = SettingsHelper.getStringSetting("general", "stopAtDate")
    private val waitDelay: Double = SettingsHelper.getDoubleSetting("general", "waitDelay")

	// Initialize Discord settings from SQLite.
	init {
		DiscordUtils.enableDiscordNotifications = SettingsHelper.getBooleanSetting("discord", "enableDiscordNotifications", false)
		if (DiscordUtils.enableDiscordNotifications) {
			try {
				DiscordUtils.discordToken = SettingsHelper.getStringSetting("discord", "discordToken")
				DiscordUtils.discordUserID = SettingsHelper.getStringSetting("discord", "discordUserID").toString()
			} catch (e: Exception) {
				Log.w(TAG, "Failed to read Discord settings: ${e.message}")
				DiscordUtils.enableDiscordNotifications = false
			}
		}
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
    val trainee: Trainee = Trainee()
	val training: Training = Training(this)
	val racing: Racing = Racing(this)
    val skillPlan: SkillPlan = SkillPlan(this)
	val trainingEvent: TrainingEvent = TrainingEvent(this)
    val campaign: Campaign = if (scenario == "Unity Cup") {
        UnityCup(this)
    } else {
        Campaign(this)
    }

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

    // Tracks the number of connection error retries. After hitting max, bot stops.
    internal val maxConnectionErrorRetryAttempts: Int = 3
    internal var connectionErrorRetryAttempts: Int = 0
    internal var lastConnectionErrorRetryTimeMs: Long = 0
    internal val connectionErrorRetryCooldownTimeMs: Long = 10000 // 10 seconds

	// Misc
    var currentDate: GameDate = GameDate(day = 1)

    private var recreationDateCompleted: Boolean = false
    private var isFinals: Boolean = false
    private var stopBeforeFinalsInitialTurnNumber: Int = -1
    private var stopAtDateInitialTurnNumber: Int = -1
    private var scenarioCheckPerformed: Boolean = false

    /** Attempts to handle dialogs.
     *
     * @return Whether a dialog was successfully handled.
     * If no dialog is detected at all, then False is returned.
     * If an unhandled dialog was detected, throws an InterruptedException.
     */
    fun tryHandleAllDialogs(): Boolean {
        return campaign.handleDialogs().first
    }

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Helper functions for bot interaction.

	/**
	 * Wait the specified seconds to account for ping or loading.
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
	 * Wait for the game to finish loading. Note that this function is responsible for dictating how fast the bot will run so adjusting this should be done with caution.
	 */
	fun waitForLoading() {
        var loadingCounter = 0
		while (checkLoading(suppressLogging = loadingCounter % 10 != 0)) {
			// Avoid an infinite loop by setting the flag to true.
			wait(waitDelay, skipWaitingForLoading = true)
            loadingCounter++
            if (loadingCounter >= 20) {
                loadingCounter = 0
            }
		}
	}

	/**
	 * Find and tap the specified image.
	 *
	 * @param imageName Name of the button image file in the /assets/images/ folder.
     * @param sourceBitmap The source bitmap to find the image on. This is optional and defaults to null which will fetch its own source bitmap.
	 * @param tries Number of tries to find the specified button. Defaults to 3.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param taps Specify the number of taps on the specified image. Defaults to 1.
	 * @param suppressError Whether or not to suppress saving error messages to the log in failing to find the button. Defaults to false.
	 * @return True if the button was found and clicked. False otherwise.
	 */
	fun findAndTapImage(imageName: String, sourceBitmap: Bitmap? = null, tries: Int = 3, region: IntArray = intArrayOf(0, 0, 0, 0), taps: Int = 1, suppressError: Boolean = false): Boolean {
		if (debugMode) {
			MessageLog.d(TAG, "Now attempting to find and click the \"$imageName\" button.")
		}

		val tempLocation: Point? = if (sourceBitmap == null) {
            imageUtils.findImage(imageName, tries = tries, region = region, suppressError = suppressError).first
        } else {
            imageUtils.findImageWithBitmap(imageName, sourceBitmap, region = region, suppressError = suppressError)
        }

		return if (tempLocation != null) {
			Log.d(TAG, "Found and going to tap: $imageName")
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
			// Now check if the game is waiting for a server response from the tap and wait if necessary.
			wait(0.20)
			waitForLoading()
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
    // Helper functions to test behavior and results of various workflows.

	/**
	 * Handles the test to perform template matching to determine what the best scale will be for the device.
	 */
	fun startTemplateMatchingTest() {
		MessageLog.i(TAG, "\n[TEST] Now beginning basic template match test on the Home screen.")
		MessageLog.i(TAG, "[TEST] Template match confidence setting will be overridden for the test.\n")
		var results = mutableMapOf<String, MutableList<ScaleConfidenceResult>>(
			"energy" to mutableListOf(),
			"tazuna" to mutableListOf(),
			"skill_points" to mutableListOf()
		)
		results = imageUtils.startTemplateMatchingTest(results)
		MessageLog.i(TAG, "\n[TEST] Basic template match test complete.")

		// Print all scale/confidence combinations that worked for each template.
		for ((templateName, scaleConfidenceResults) in results) {
			if (scaleConfidenceResults.isNotEmpty()) {
				MessageLog.i(TAG, "[TEST] All working scale/confidence combinations for $templateName:")
				for (result in scaleConfidenceResults) {
					MessageLog.i(TAG, "[TEST]	Scale: ${result.scale}, Confidence: ${result.confidence}")
				}
			} else {
				MessageLog.w(TAG, "No working scale/confidence combinations found for $templateName")
			}
		}

		// Then print the median scales and confidences.
		val medianScales = mutableListOf<Double>()
		val medianConfidences = mutableListOf<Double>()
		for ((templateName, scaleConfidenceResults) in results) {
			if (scaleConfidenceResults.isNotEmpty()) {
				val sortedScales = scaleConfidenceResults.map { it.scale }.sorted()
				val sortedConfidences = scaleConfidenceResults.map { it.confidence }.sorted()
				val medianScale = sortedScales[sortedScales.size / 2]
				val medianConfidence = sortedConfidences[sortedConfidences.size / 2]
				medianScales.add(medianScale)
				medianConfidences.add(medianConfidence)
				MessageLog.i(TAG, "[TEST] Median scale for $templateName: $medianScale")
				MessageLog.i(TAG, "[TEST] Median confidence for $templateName: $medianConfidence")
			}
		}

		if (medianScales.isNotEmpty()) {
			MessageLog.i(TAG, "\n[TEST] The following are the recommended scales to set: $medianScales.")
			MessageLog.i(TAG, "[TEST] The following are the recommended confidences to set: $medianConfidences.")
		} else {
			MessageLog.e(TAG, "\nNo median scale/confidence can be found.")
		}
	}

    /**
	 * Handles the test to perform OCR on the current date and elapsed turn number.
	 */
	fun startDateOCRTest() {
		MessageLog.i(TAG, "\n[TEST] Now beginning the Date OCR test on the Main screen.")
		MessageLog.i(TAG, "[TEST] Note that this test is dependent on having the correct scale.")
        wait(5.0)
        updateDate()
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////
	// Helper functions to check what screen the bot is at.

	/**
	 * Checks if the bot is at the Main screen or the screen with available options to undertake.
	 * This will also make sure that the Main screen does not contain the option to select a race.
	 *
	 * @return True if the bot is at the Main screen. Otherwise false.
	 */
	fun checkMainScreen(): Boolean {
        return ButtonHomeFullStats.check(imageUtils) && IconTazuna.check(imageUtils)
	}

	/**
	 * Checks if the bot is at the Training Event screen with an active event with options to select on screen.
	 *
	 * @return True if the bot is at the Training Event screen. Otherwise false.
	 */
	fun checkTrainingEventScreen(): Boolean {
		MessageLog.i(TAG, "\nChecking if the bot is sitting on the Training Event screen.")
		return if (imageUtils.findImage("training_event_active", tries = 1, region = imageUtils.regionMiddle).first != null) {
			MessageLog.i(TAG, "Bot is at the Training Event screen.")
			true
		} else {
			MessageLog.i(TAG, "Bot is not at the Training Event screen.")
			false
		}
	}

	/**
	 * Checks if the bot is at the preparation screen with a mandatory race needing to be completed.
	 *
	 * @return True if the bot is at the Main screen with a mandatory race. Otherwise false.
	 */
	fun checkMandatoryRacePrepScreen(): Boolean {
		MessageLog.i(TAG, "\nChecking if the bot is sitting on the Race Preparation screen for a mandatory race.")
        val sourceBitmap = imageUtils.getSourceBitmap()
		return if (IconRaceDayRibbon.check(imageUtils = imageUtils, sourceBitmap = sourceBitmap)) {
			MessageLog.i(TAG, "Bot is at the preparation screen with a mandatory race ready to be completed.")
            if (scenario == "Unity Cup") wait(1.0)
			true
		} else if (IconGoalRibbon.check(imageUtils = imageUtils, sourceBitmap = sourceBitmap)) {
			// Most likely the user started the bot here so a delay will need to be placed to allow the start banner of the Service to disappear.
			wait(2.0)
			MessageLog.i(TAG, "Bot is at the Race Selection screen with a mandatory race needing to be selected.")
			// Walk back to the preparation screen.
            ButtonBack.click(imageUtils = imageUtils, sourceBitmap = sourceBitmap)
			wait(1.0)
			true
		} else if (scenario == "Unity Cup" && ButtonUnityCupRace.check(imageUtils = imageUtils, sourceBitmap = sourceBitmap)) {
            MessageLog.i(TAG, "Bot is awaiting opponent selection for a Unity Cup race.")
            true
        } else {
			MessageLog.i(TAG, "Bot is not at the Race Preparation screen for a mandatory race.")
			false
		}
	}

	/**
	 * Checks if the bot is at the Racing screen waiting to be skipped or done manually.
	 *
	 * @return True if the bot is at the Racing screen. Otherwise, false.
	 */
	fun checkRacingScreen(): Boolean {
		MessageLog.i(TAG, "\nChecking if the bot is sitting on the Racing screen.")
		return if (imageUtils.findImage("race_change_strategy", tries = 1, region = imageUtils.regionBottomHalf).first != null) {
			MessageLog.i(TAG, "Bot is at the Racing screen waiting to be skipped or done manually.")
			true
		} else {
			MessageLog.i(TAG, "Bot is not at the Racing screen.")
			false
		}
	}

	/**
	 * Checks if the bot is at the Ending screen detailing the overall results of the run.
	 *
	 * @return True if the bot is at the Ending screen. Otherwise false.
	 */
	fun checkEndScreen(): Boolean {
		MessageLog.i(TAG, "\nChecking if the bot is sitting on the End screen.")
		return if (ButtonCompleteCareer.check(imageUtils)) {
			true
		} else {
			MessageLog.i(TAG, "Bot is not at the End screen and can keep going.")
			false
		}
	}

	/**
	 * Checks if the bot should stop before the finals on turn 72.
	 *
	 * @return True if the bot should stop. Otherwise false.
	 */
	fun checkFinalsStop(): Boolean {
		if (!enableStopBeforeFinals) {
            Log.d(TAG, "\n[FINALS] Flag is false so skipping Finals check.")
			return false
		} else if (currentDate.day > 72) {
            // If already past turn 72, skip the check to prevent re-checking.
            Log.d(TAG, "\n[FINALS] Turn is greater than 72 so skipping Finals check.")
			return false
		}

		MessageLog.i(TAG, "\n[FINALS] Checking if bot should stop before the finals.")

		// Check if turn is 72, but only stop if we progressed to turn 72 during this run.
		if (currentDate.day == 72 && stopBeforeFinalsInitialTurnNumber != -1) {
			MessageLog.i(TAG, "[FINALS] Detected turn 72. Stopping bot before the finals.")
			notificationMessage = "Stopping bot before the finals on turn 72."
			return true
		}

        // Track initial turn number on first check to avoid stopping if bot starts on turn 72.
		if (stopBeforeFinalsInitialTurnNumber == -1) {
			stopBeforeFinalsInitialTurnNumber = currentDate.day
		}

		return false
	}

	/**
	 * Checks if the bot should stop at the user specified date.
	 *
	 * @return True if the bot should stop. Otherwise false.
	 */
	fun checkStopAtDate(): Boolean {
		if (!enableStopAtDate) {
			Log.d(TAG, "\n[DATE] Flag is false so skipping Stop at Date check.")
			return false
		}
		
		MessageLog.i(TAG, "\n[DATE] Checking if bot should stop at the specified date: $stopAtDate with current date ${currentDate}.")
		
		val parts = stopAtDate.split(" ")
		if (parts.size != 3) {
			MessageLog.e(TAG, "[DATE] Invalid Stop at Date format. Expected 'YEAR MONTH PHASE'")
			return false
		}
		
		val targetYear = try { DateYear.valueOf(parts[0].uppercase()) } catch (e: IllegalArgumentException) { null }
		val targetMonth = try { DateMonth.valueOf(parts[1].uppercase()) } catch (e: IllegalArgumentException) { null }
		val targetPhase = try { DatePhase.valueOf(parts[2].uppercase()) } catch (e: IllegalArgumentException) { null }
		
		if (targetYear == null || targetMonth == null || targetPhase == null) {
			MessageLog.e(TAG, "[DATE] Invalid Stop at Date components.")
			return false
		}
		
		val targetDay = GameDate.toDay(targetYear, targetMonth, targetPhase)
		
		// Track initial turn number on first check to avoid stopping immediately if bot starts after the target date
		if (stopAtDateInitialTurnNumber == -1) {
			stopAtDateInitialTurnNumber = currentDate.day
		}
		
		if (currentDate.day >= targetDay && stopAtDateInitialTurnNumber <= targetDay) {
			MessageLog.i(TAG, "[DATE] Reached target date: $stopAtDate (Turn $targetDay). Stopping bot.")
			notificationMessage = "Stopping bot at the specified date: $stopAtDate (Turn $targetDay)"
			return true
		}
		
		return false
	}

	/**
	 * Checks if the bot has a injury.
	 *
	 * @return True if the bot has a injury. Otherwise false.
	 */
	fun checkInjury(sourceBitmap: Bitmap? = null): Boolean {
		MessageLog.i(TAG, "\n[INJURY] Checking if there is an injury that needs healing on ${currentDate}.")
        val sourceBitmap = sourceBitmap ?: imageUtils.getSourceBitmap()
		val recoverInjuryLocation = imageUtils.findImageWithBitmap("recover_injury", sourceBitmap, region = imageUtils.regionBottomHalf)
		return if (recoverInjuryLocation != null && imageUtils.checkColorAtCoordinates(
				recoverInjuryLocation.x.toInt(),
				recoverInjuryLocation.y.toInt() + 15,
				intArrayOf(151, 105, 243),
				10
			)) {
			if (findAndTapImage("recover_injury", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf)) {
                // Tap OK for the possibility of a scheduled race warning popup.
                wait(0.25)
                findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)

				wait(0.3)
				if (imageUtils.findImage("recover_injury_header", tries = 1, region = imageUtils.regionMiddle).first != null) {
					MessageLog.i(TAG, "[INJURY] Injury detected and attempted to heal.")
					true
				} else {
					false
				}
			} else {
				MessageLog.w(TAG, "Injury detected but attempt to rest failed.")
				false
			}
		} else {
			MessageLog.i(TAG, "[INJURY] No injury detected.")
			false
		}
	}

	/**
	 * Checks if the bot is at a "Now Loading..." screen or if the game is awaiting for a server response. This may cause significant delays in normal bot processes.
	 *
	 * @param suppressLogging Whether or not to suppress logging for this function. Defaults to false.
	 * @return True if the game is still loading or is awaiting for a server response. Otherwise, false.
	 */
	fun checkLoading(suppressLogging: Boolean = false): Boolean {
		if (!suppressLogging) MessageLog.i(TAG, "[LOADING] Now checking if the game is still loading...")
        val sourceBitmap = imageUtils.getSourceBitmap()
		return if (imageUtils.findImageWithBitmap("connecting", sourceBitmap, region = imageUtils.regionTopHalf, suppressError = true) != null) {
			MessageLog.i(TAG, "[LOADING] Detected that the game is awaiting a response from the server from the \"Connecting\" text at the top of the screen. Waiting...")
			true
		} else if (imageUtils.findImageWithBitmap("now_loading", sourceBitmap, region = imageUtils.regionBottomHalf, suppressError = true) != null) {
			MessageLog.i(TAG, "[LOADING] Detected that the game is still loading from the \"Now Loading\" text at the bottom of the screen. Waiting...")
			true
		} else {
			false
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////////

    /** Returns whether we are currently in the finale season. */
    fun checkFinals(): Boolean {
        return currentDate.bIsFinaleSeason ?: false
    }

    /**
     * Updates the currentDate GameDate object by detecting the date on screen.
     *
     * @param isOnMainScreen If true, it can go straight to checking the Main screen for the date. Defaults to true.
     * @return Whether the date changed.
     */
	fun updateDate(isOnMainScreen: Boolean = true): Boolean {
		MessageLog.i(TAG, "[DATE] Attempting to update the current date.")
        val prevDay: Int = currentDate.day
        if (!currentDate.update(imageUtils = imageUtils, isOnMainScreen = isOnMainScreen)) {
            MessageLog.e(TAG, "[DATE] currentDate.update() failed to update date.")
            return false
        }

        if (currentDate.day == prevDay) {
            Log.d(TAG, "[DATE] Date did not change.")
            return false
        } else {
            MessageLog.i(TAG, "[DATE] New date: ${currentDate}")
            return true
        }
	}

	/**
	 * Handles the Inheritance event if detected on the screen.
	 *
	 * @return True if the Inheritance event happened and was accepted. Otherwise false.
	 */
	fun handleInheritanceEvent(): Boolean {
        // Stop checking after Senior Year Early Apr.
		return if (currentDate.day <= 56) {
			if (findAndTapImage("inheritance", tries = 1, region = imageUtils.regionBottomHalf)) {
				MessageLog.i(TAG, "\nClaimed an inheritance on ${currentDate}.")
                trainee.bHasUpdatedAptitudes = false
				true
			} else {
				false
			}
		} else {
			false
		}
	}

	/**
	 * Attempt to recover energy.
	 *
	 * @return True if the bot successfully recovered energy. Otherwise false.
	 */
    fun recoverEnergy(sourceBitmap: Bitmap? = null): Boolean {
		MessageLog.i(TAG, "\n[ENERGY] Now starting attempt to recover energy on ${currentDate}.")
        val sourceBitmap: Bitmap = sourceBitmap ?: imageUtils.getSourceBitmap()
		
		// First, try to handle recreation date which also recovers energy if a date is available.
		// Skip recreation date if it's already completed (will only be used for mood recovery).
		if (
            !recreationDateCompleted &&
            imageUtils.findImageWithBitmap("recreation_date", sourceBitmap = sourceBitmap, region = imageUtils.regionBottomHalf) != null &&
            handleRecreationDate(recoverMoodIfCompleted = false)) {
			MessageLog.i(TAG, "[ENERGY] Successfully recovered energy via recreation date.")
			return true
		}
		
		// Otherwise, fall back to the regular energy recovery logic.
		return when {
			findAndTapImage("recover_energy", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok", region = imageUtils.regionMiddle, suppressError = true)
                // Another OK tap for the possibility of a scheduled race warning popup.
                wait(0.25)
                findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)
				MessageLog.i(TAG, "[ENERGY] Successfully recovered energy.")
				true
			}
			findAndTapImage("recover_energy_summer", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf) -> {
				findAndTapImage("ok", region = imageUtils.regionMiddle, suppressError = true)
                // Another OK tap for the possibility of a scheduled race warning popup.
                wait(0.25)
                findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)
				MessageLog.i(TAG, "[ENERGY] Successfully recovered energy for the Summer.")
				true
			}
			else -> {
				MessageLog.i(TAG, "[ENERGY] Failed to recover energy. Moving on...")
				false
			}
		}
	}

	/**
	 * Attempt to recover mood to always maintain at least Above Normal mood.
	 *
	 * @return True if the bot successfully recovered mood. Otherwise false.
	 */
	fun recoverMood(sourceBitmap: Bitmap? = null): Boolean {
		MessageLog.i(TAG, "\n[MOOD] Detecting current mood on ${currentDate}.")

        val sourceBitmap = sourceBitmap ?: imageUtils.getSourceBitmap()

        // Make sure the trainee's mood is up to date.
        trainee.updateMood(imageUtils, sourceBitmap)

		MessageLog.i(TAG, "[MOOD] Detected mood to be ${trainee.mood}.")

		// Only recover mood if its below Good mood and its not Summer.
		return if (training.firstTrainingCheck && trainee.mood == Mood.NORMAL && imageUtils.findImageWithBitmap("recover_energy_summer", sourceBitmap, region = imageUtils.regionBottomHalf, suppressError = true) == null) {
			MessageLog.i(TAG, "[MOOD] Current mood is Normal. Not recovering mood due to firstTrainingCheck flag being active. Will need to complete a training first before being allowed to recover mood.")
			false
		} else if ((trainee.mood < Mood.GOOD) && imageUtils.findImageWithBitmap("recover_energy_summer", sourceBitmap, region = imageUtils.regionBottomHalf, suppressError = true) == null) {
			MessageLog.i(TAG, "[MOOD] Current mood is not good (${trainee.mood}). Recovering mood now.")

            // Check if a date is available.
            if (!recreationDateCompleted && imageUtils.findImageWithBitmap("recreation_date", sourceBitmap = sourceBitmap, region = imageUtils.regionBottomHalf) != null) {
                handleRecreationDate(recoverMoodIfCompleted = true)
            } else {
                // Otherwise, recover mood as normal.
                // Note that if a date was already completed, the Recreation popup will still show so it will require an additional step to recover mood.
                recreationDateCompleted = true
                if (!findAndTapImage("recover_mood", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
                    findAndTapImage("recover_energy_summer", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)
                }

                // Tap OK for the possibility of a scheduled race warning popup.
                wait(0.25)
                findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)

                if (imageUtils.findImage("recreation_umamusume", region = imageUtils.regionMiddle, suppressError = true).first != null) {
                    // The Recreation popup is now open so an additional step is required to recover mood.
                    MessageLog.i(TAG, "[MOOD] Recreation date is already completed. Recovering mood with the Umamusume now...")
                    findAndTapImage("recreation_umamusume", region = imageUtils.regionMiddle)
                } else {
                    // Otherwise, dismiss the popup that says to confirm recreation if the user has not set it to skip the confirmation in their in-game settings.
                    findAndTapImage("ok", region = imageUtils.regionMiddle, suppressError = true)
                }
            }
			true
		} else {
			MessageLog.i(TAG, "[MOOD] Current mood is good enough or its the Summer event. Moving on...")
			false
		}
	}

    /**
     * Handles the Recreation date event if detected on the screen.
     *
     * @param recoverMoodIfCompleted If true, recover mood if the date was already completed. Otherwise, close the recreation popup.
     * @return True if the Recreation date event was successfully completed. False otherwise.
     */
    fun handleRecreationDate(recoverMoodIfCompleted: Boolean = false): Boolean {
        return if (findAndTapImage("recover_mood", tries = 1, region = imageUtils.regionBottomHalf)) {
            // Tap OK for the possibility of a scheduled race warning popup.
            wait(0.25)
            findAndTapImage("ok", tries = 1, region = imageUtils.regionMiddle, suppressError = true)

            MessageLog.i(TAG, "\n[RECREATION_DATE] Recreation has a possible date available.")
            wait(1.0)
            // Check if the date is already done.
            if (imageUtils.findImage("recreation_date_complete", tries = 1, region = imageUtils.regionMiddle).first != null) {
                MessageLog.i(TAG, "[RECREATION_DATE] Recreation date is already completed.")
                recreationDateCompleted = true
                if (recoverMoodIfCompleted) {
                    MessageLog.i(TAG, "[RECREATION_DATE] Mood requires recovery. Recovering mood with the Umamusume now...")
                    findAndTapImage("recreation_umamusume", region = imageUtils.regionMiddle)
                    true
                } else {
                    MessageLog.i(TAG, "[RECREATION_DATE] Mood does not require recovery. Moving on...")
                    findAndTapImage("cancel", region = imageUtils.regionBottomHalf)
                    true
                }
            } else if (findAndTapImage("recreation_dating_progress", region = imageUtils.regionMiddle)) {
                MessageLog.i(TAG, "[RECREATION_DATE] Recreation date can be done.")
                true
            } else {
                false
            }
        } else {
            false
        }
    }

	/**
	 * Handles the Crane Game event by attempting to complete it with three long-press attempts.
	 *
	 * @return True if the crane game was successfully completed. False otherwise.
	 */
	fun handleCraneGame(): Boolean {
		MessageLog.i(TAG, "\n[CRANE GAME] Starting crane game attempt...")

		// Find the crane game button location.
		val buttonLocation = ButtonCraneGame.find(imageUtils = imageUtils)
		val buttonPoint = buttonLocation.first
		if (buttonPoint == null) {
			MessageLog.w(TAG, "\n[CRANE_GAME] Could not find the crane game button. Aborting.")
			return false
		}

		val imageName = ButtonCraneGame.template.path
		val pressDurations = listOf(1.90, 1.00, 0.65)

		// Perform three attempts with different press durations.
		for (attempt in 1..3) {
			val pressDuration = pressDurations[attempt - 1]
			MessageLog.i(TAG, "[CRANE_GAME] Attempt $attempt: Long pressing for ${pressDuration}s...")

			// Perform long press on the button.
			gestureUtils.tap(buttonPoint.x, buttonPoint.y, imageName, longPress = true, pressDuration = pressDuration)

			if (attempt < 3) {
				// After attempts 1 and 2, wait for the button to reappear.
				MessageLog.i(TAG, "[CRANE_GAME] Waiting for the crane game button to reappear after attempt $attempt...")
				var buttonReappeared = false
				val maxWaitTime = 30.0
				val checkInterval = 1.0
				var elapsedTime = 0.0

				while (elapsedTime < maxWaitTime) {
					if (ButtonCraneGame.check(imageUtils = imageUtils)) {
						buttonReappeared = true
						break
					}
					wait(checkInterval, skipWaitingForLoading = true)
					elapsedTime += checkInterval
				}

				if (!buttonReappeared) {
					MessageLog.w(TAG, "[CRANE_GAME] The crane game button did not reappear within ${maxWaitTime} seconds after attempt $attempt.")
				}

				wait(1.0)
			} else {
				MessageLog.i(TAG, "[CRANE_GAME] Final attempt completed.")
                return true
			}
		}

		return false
	}

	/**
	 * Perform misc checks to potentially fix instances where the bot is stuck.
	 *
	 * @return True if the checks passed. Otherwise false if the bot encountered a warning popup and needs to exit.
	 */
	fun performMiscChecks(): Boolean {
		MessageLog.i(TAG, "\n[MISC] Beginning check for misc cases...")

        val sourceBitmap = imageUtils.getSourceBitmap()

		if (enablePopupCheck && imageUtils.findImageWithBitmap("cancel", sourceBitmap, region = imageUtils.regionBottomHalf) != null) {
			MessageLog.i(TAG, "\n[END] Bot may have encountered a warning popup. Exiting now...")
			notificationMessage = "Bot may have encountered a warning popup"
			if (DiscordUtils.enableDiscordNotifications) {
				DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Bot may have encountered a warning popup. Exiting now...\n```")
			}
			return false
		} else if (findAndTapImage("next", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf)) {
			// Now confirm the completion of a Training Goal popup.
			MessageLog.i(TAG, "[MISC] Popup detected that needs to be dismissed with the \"Next\" button.")
			wait(2.0)
			findAndTapImage("next", tries = 1, region = imageUtils.regionBottomHalf)
			wait(1.0)
        } else if (imageUtils.findImageWithBitmap("race_repeat_warning", sourceBitmap, region = imageUtils.regionTopHalf) != null) {
            MessageLog.i(TAG, "[MISC] Consecutive race warning detected on the screen so dismissing the popup.")
            findAndTapImage("cancel", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf)
            wait(1.0)
        } else if (ButtonCraneGame.check(imageUtils = imageUtils, sourceBitmap = sourceBitmap)) {
            if (enableCraneGameAttempt) {
                handleCraneGame()
            } else {
                // Stop when the bot has reached the Crane Game Event.
                MessageLog.i(TAG, "\n[END] Bot will stop due to the detection of the Crane Game Event.")
                notificationMessage = "Bot will stop due to the detection of the Crane Game Event."
                if (DiscordUtils.enableDiscordNotifications) {
                    DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Bot will stop due to the detection of the Crane Game Event.\n```")
                }
                return false
            }
        } else if (
            imageUtils.findImageWithBitmap("ordinary_cuties", region = imageUtils.regionMiddle, sourceBitmap = sourceBitmap) != null &&
            ButtonCraneGameOk.check(imageUtils = imageUtils, sourceBitmap = sourceBitmap)
        ) {
            ButtonCraneGameOk.click(imageUtils = imageUtils, sourceBitmap = sourceBitmap)
            MessageLog.i(TAG, "[CRANE GAME] Event exited.")
		} else if (findAndTapImage("race_end", sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
			MessageLog.i(TAG, "[MISC] Ended a leftover race.")
		} else if (imageUtils.findImageWithBitmap("connection_error", sourceBitmap, region = imageUtils.regionMiddle, suppressError = true) != null) {
			MessageLog.i(TAG, "\n[END] Bot will stop due to detecting a connection error.")
			notificationMessage = "Bot will stop due to detecting a connection error."
			if (DiscordUtils.enableDiscordNotifications) {
				DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Bot will stop due to detecting a connection error.\n```")
			}
			return false
		} else if (imageUtils.findImageWithBitmap("race_not_enough_fans", sourceBitmap, region = imageUtils.regionMiddle, suppressError = true) != null) {
			MessageLog.i(TAG, "[MISC] There was a popup about insufficient fans.")
			racing.encounteredRacingPopup = true
			findAndTapImage("cancel", region = imageUtils.regionBottomHalf)
		} else if (findAndTapImage("back", sourceBitmap = sourceBitmap, tries = 1, region = imageUtils.regionBottomHalf, suppressError = true)) {
			MessageLog.i(TAG, "[MISC] Navigating back a screen since all the other misc checks have been completed.")
			wait(1.0)
		} else if (ButtonSkip.click(imageUtils = imageUtils, sourceBitmap = sourceBitmap)) {
            MessageLog.d(TAG, "[MISC] Clicked skip button.")
        } else if (!BotService.isRunning) {
			MessageLog.i(TAG, "\n[END] BotService is not running. Exiting now...")
			throw InterruptedException()
		} else {
			MessageLog.i(TAG, "[MISC] Did not detect any popups or the Crane Game on the screen. Moving on...")
		}

		return true
	}

	/**
	 * Bot will begin automation here.
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
			MessageLog.w(TAG, "Failed to load formatted settings from SQLite: ${e.message}")
			MessageLog.i(TAG, "Using fallback settings display...")
			// Fallback to basic settings display if formatted string is not available.
			MessageLog.i(TAG, "Scenario: $scenario")
			MessageLog.i(TAG, "Debug Mode: $debugMode")
		}

		// Print device and version information.
		MessageLog.i(TAG, "Device Information: ${SharedData.displayWidth}x${SharedData.displayHeight}, DPI ${SharedData.displayDPI}")
		if (SharedData.displayWidth != 1080) MessageLog.w(TAG, "⚠️ Bot performance will be severely degraded since display width is not 1080p unless an appropriate scale is set for your device.")
		if (debugMode) MessageLog.w(TAG, "⚠️ Debug Mode is enabled. All bot operations will be significantly slower as a result.")
		if (SettingsHelper.getStringSetting("debug", "templateMatchCustomScale").toDouble() != 1.0) MessageLog.i(TAG, "Manual scale has been set to ${SettingsHelper.getStringSetting("debug", "templateMatchCustomScale").toDouble()}")
		MessageLog.w(TAG, "⚠️ Note that certain Android notification styles (like banners) are big enough that they cover the area that contains the Mood which will interfere with mood recovery logic in the beginning.")
		val packageInfo = myContext.packageManager.getPackageInfo(myContext.packageName, 0)
		MessageLog.i(TAG, "Bot version: ${packageInfo.versionName} (${packageInfo.versionCode})\n\n")

		// Start debug tests here if enabled. Otherwise, proceed with regular bot operations.
		if (SettingsHelper.getBooleanSetting("debug", "debugMode_startTemplateMatchingTest")) {
			startTemplateMatchingTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startSingleTrainingOCRTest")) {
			training.startSingleTrainingOCRTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startComprehensiveTrainingOCRTest")) {
			training.startComprehensiveTrainingOCRTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startDateOCRTest")) {
			startDateOCRTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startRaceListDetectionTest")) {
			racing.startRaceListDetectionTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startAptitudesDetectionTest")) {
			campaign.startAptitudesDetectionTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startMainScreenOCRTest")) {
			campaign.startMainScreenOCRTest()
		} else if (SettingsHelper.getBooleanSetting("debug", "debugMode_startTrainingScreenOCRTest")) {
			campaign.startTrainingScreenOCRTest()
		} else {
            // Send Discord notification that the run has started.
			if (DiscordUtils.enableDiscordNotifications) {
				val enableRemoteLogViewer = SettingsHelper.getBooleanSetting("debug", "enableRemoteLogViewer", false)
				var logViewerString = ""
				if (enableRemoteLogViewer) {
                    // Notify the user that the Remote Log Viewer is enabled and is viewable at the indicated address.
					val port = SettingsHelper.getIntSetting("debug", "remoteLogViewerPort", 9000)
					val ipAddress = com.steve1316.uma_android_automation.utils.LogStreamServer.getDeviceIpAddress(myContext)
					val finalIpAddress = if (ipAddress == "10.0.2.15") "localhost" else ipAddress
					logViewerString = "\n+ Remote Log Viewer is enabled at http://$finalIpAddress:$port"
				}
				DiscordUtils.queue.add("```diff\n+ ${MessageLog.getSystemTimeString()} Bot run started! Scenario: $scenario$logViewerString\n```")
			}
			wait(5.0)
            campaign.start()
		}

		MessageLog.i(TAG, "Total runtime of ${MessageLog.formatElapsedTime(startTime, System.currentTimeMillis())} and stopped at ${MessageLog.getSystemTimeString()}.")

		return true
	}
}