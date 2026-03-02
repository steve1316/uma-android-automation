package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.data.SharedData

import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.FanCountClass
import com.steve1316.uma_android_automation.types.BoundingBox

import com.steve1316.uma_android_automation.components.*

import android.util.Log
import android.graphics.Bitmap
import org.opencv.core.Point
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Base campaign class that contains all shared logic for campaign automation.
 * Campaign-specific logic should be implemented in subclasses by overriding the appropriate methods.
 * By default, URA Finale is handled by this base class.
 */
open class Campaign(game: Game) : DialogHandler(game) {
	protected open val TAG: String = "[${MainActivity.loggerTag}]Normal"

    private val mustRestBeforeSummer: Boolean = SettingsHelper.getBooleanSetting("training", "mustRestBeforeSummer")
    private val enableFarmingFans: Boolean = SettingsHelper.getBooleanSetting("racing", "enableFarmingFans")

    // Should always check fan count at bot start unless in pre-debut.
    internal var bNeedToCheckFans: Boolean = true
    // Flag used to prevent us from attempting to check fans multiple times in a day.
    // This helps us avoid infinite loops.
    protected var bHasTriedCheckingFansToday: Boolean = false
    // Flag for whether we have handled the skill point check conditions
    // during this run.
    // This is necessary since the user may have enabled the skill point check
    // skill spending plan. If their plan ends up not purchasing many skills,
    // then it is possible that we could get stuck in a loop of hitting the
    // skill point threshold and attempting to buy skills every single turn.
    // To resolve this, we only allow the skill point check to be handled
    // once per run.
    protected var bHasHandledSkillPointCheck: Boolean = false

    // Flag for only checking for maiden races once per day.
    var bHasCheckedForMaidenRaceToday: Boolean = false

    // Flag to skip redundant date checks when no game-advancing action was taken.
    // Reset to false when training, resting, racing, or other game-advancing actions complete.
    protected var bHasCheckedDateThisTurn: Boolean = false

	/**
	 * Handles the skill list screen to purchase skills.
	 *
	 * This function initiates the skill purchasing process using the specified
	 * skill plan. If no plan name is provided, the default skill plan is used.
	 *
	 * @param skillPlanName The optional name of the skill plan to use.
	 * @return True if the skill purchasing process was successful, false otherwise.
	 */
	fun handleSkillListScreen(skillPlanName: String? = null): Boolean {
		MessageLog.i(TAG, "Beginning process to purchase skills...")
		return game.skillPlan.start(skillPlanName)
	}

    /**
     * Opens the Umamusume Details dialog to update trainee aptitudes.
     *
     * This function only opens the dialog - the actual aptitude update is performed
     * by [handleDialogs] when it processes the "umamusume_details" dialog.
     */
    fun openAptitudesDialog() {
        MessageLog.d(TAG, "Opening aptitudes dialog...")
        ButtonHomeFullStats.click(imageUtils = game.imageUtils)
        game.wait(0.25, skipWaitingForLoading = true)
    }

    /**
     * Opens the Umamusume Class dialog to update trainee fan count.
     *
     * This function only opens the dialog - the actual fan count update is performed
     * by [handleDialogs] when it processes the "umamusume_class" dialog.
     */
    fun openFansDialog() {
        MessageLog.d(TAG, "Opening fans dialog...")
        if (game.scenario == "Unity Cup") {
            ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionBottomHalf, tries = 10)
        } else {
            ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionTopHalf, tries = 10)
        }

        bHasTriedCheckingFansToday = true
        game.wait(0.25, skipWaitingForLoading = true)
    }

    /**
     * Waits for the current dialog to be processed.
     *
     * This loops calling [handleDialogs] until no dialog is detected,
     * allowing dialogs to be processed within the same function call
     * instead of returning early and re-entering [handleMainScreen].
     */
    private fun waitForDialogProcessed() {
        while (true) {
            if (!handleDialogs().first) {
                break
            }
        }
    }

    /**
     * Detects the trainee's current fan count class from the main screen.
     *
     * This reads the fan count class label directly from the screen using OCR
     * without opening any dialogs.
     *
     * @param bitmap Optional pre-captured bitmap to analyze.
     * @return The detected [FanCountClass], or NULL if detection failed.
     */
    fun getFanCountClass(bitmap: Bitmap? = null): FanCountClass? {
        val bitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()
        val templateBitmap: Bitmap? = ButtonHomeFansInfo.template.getBitmap(game.imageUtils)
        if (templateBitmap == null) {
            MessageLog.e(TAG, "getFanCountClass: Could not get template bitmap for ButtonHomeFansInfo: ${ButtonHomeFansInfo.template.path}.")
            return null
        }
        val point: Point? = ButtonHomeFansInfo.findImageWithBitmap(imageUtils = game.imageUtils, sourceBitmap = bitmap)
        if (point == null) {
            MessageLog.w(TAG, "getFanCountClass: Could not find ButtonHomeFansInfo.")
            return null
        }

        val bbox = BoundingBox(
            x = game.imageUtils.relX(0.0, (point.x - (templateBitmap.width / 2)).toInt() - 180),
            // Add a small buffer to vertical component.
            y = game.imageUtils.relY(0.0, (point.y - 16).toInt()),
            w = game.imageUtils.relWidth(180),
            // 32px minimum for google ML kit.
            h = game.imageUtils.relHeight(32),
        )

        val text: String = game.imageUtils.performOCROnRegion(
            bitmap,
            bbox.x,
            bbox.y,
            bbox.w,
            bbox.h,
			useThreshold = false,
            useGrayscale = true,
            scale = 1.0,
            ocrEngine = "tesseract",
            debugName = "getFanCountClass",
        )
        val fanCountClass: FanCountClass? = FanCountClass.fromName(text.replace(" ", "_"))
        if (fanCountClass == null) {
            MessageLog.w(TAG, "getFanCountClass:: Failed to match text to a FanCountClass: $text")
        }
        return fanCountClass
    }

	/**
	 * Campaign-specific training event handling.
	 */
	open fun handleTrainingEvent() {
		game.trainingEvent.handleTrainingEvent()
	}

	/**
	 * Campaign-specific race event handling.
	 * 
     * @param isScheduledRace True if the race is scheduled, false otherwise.
     * @return True if the race was handled successfully, false otherwise.
	 */
	open fun handleRaceEvents(isScheduledRace: Boolean = false): Boolean {
        val bDidRace: Boolean = game.racing.handleRaceEvents(isScheduledRace)
        bNeedToCheckFans = bDidRace

		return bDidRace
	}

	/**
	 * Campaign-specific checks for special screens or conditions.
	 * 
     * @return True if the conditions are met, false otherwise.
	 */
	open fun checkCampaignSpecificConditions(): Boolean {
		return false
	}

	/**
	 * Test function to verify aptitude detection on the Main screen.
	 *
	 * Opens the aptitudes dialog and processes it to test OCR accuracy.
	 * Note: This test is dependent on having the correct scale.
	 */
	fun startAptitudesDetectionTest() {
		MessageLog.i(TAG, "\n[TEST] Now beginning the Aptitudes Detection test on the Main screen.")
		MessageLog.i(TAG, "[TEST] Note that this test is dependent on having the correct scale.")
        openAptitudesDialog()
        handleDialogs()
	}

    /**
     * Test function to verify OCR detection on the Training screen.
     */
    fun startTrainingScreenOCRTest() {
        MessageLog.i(TAG, "---- startTrainingScreenOCRTest START ----")

        var numPass = 0
        var numFail = 0

        // Simple components to test.
        val componentsToTest: List<ComponentInterface> = listOf(
            LabelEnergy,
            LabelStatTableHeaderSkillPoints,
            LabelTrainingFailureChance,
            ButtonTrainingSpeed,
            ButtonTrainingStamina,
            ButtonTrainingPower,
            ButtonTrainingGuts,
            ButtonTrainingWit,
            ButtonBack,
            ButtonLog,
            ButtonBurger,
        )
        for (componentToTest in componentsToTest) {
            if (componentToTest.check(game.imageUtils)) {
                MessageLog.i(TAG, "[PASS] ${componentToTest.template.path}")
                numPass++
            } else {
                MessageLog.e(TAG, "[FAIL] ${componentToTest.template.path}")
                numFail++
            }
        }

        when {
            IconTrainingHeaderSpeed.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconTrainingHeaderSpeed.template.path}")
                numPass++
            }
            IconTrainingHeaderStamina.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconTrainingHeaderStamina.template.path}")
                numPass++
            }
            IconTrainingHeaderPower.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconTrainingHeaderPower.template.path}")
                numPass++
            }
            IconTrainingHeaderGuts.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconTrainingHeaderGuts.template.path}")
                numPass++
            }
            IconTrainingHeaderWit.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconTrainingHeaderWit.template.path}")
                numPass++
            }
            else -> {
                MessageLog.e(TAG, "[FAIL] Could not detect any training header icons.")
                numFail++
            }
        }

        MessageLog.i(TAG, "---- startTrainingScreenOCRTest END: PASS=$numPass, FAIL=$numFail ----")
    }

    /**
     * Test function to verify OCR detection on the Main screen.
     */
    fun startMainScreenOCRTest() {
        MessageLog.i(TAG, "---- startMainScreenOCRTest START ----")

        var numPass = 0
        var numFail = 0

        // Simple components to test.
        val componentsToTest: List<ComponentInterface> = listOf(
            ButtonHomeFansInfo,
            IconTazuna,
            LabelEnergy,
            LabelStatTableHeaderSkillPoints,
            ButtonHomeFullStats,
            ButtonRest,
            ButtonTraining,
            ButtonSkills,
            ButtonInfirmary,
            ButtonRecreation,
            ButtonLog,
            ButtonBurger,
        )
        for (componentToTest in componentsToTest) {
            if (componentToTest.check(game.imageUtils)) {
                MessageLog.i(TAG, "[PASS] ${componentToTest.template.path}")
                numPass++
            } else {
                MessageLog.e(TAG, "[FAIL] ${componentToTest.template.path}")
                numFail++
            }
        }

        // More complex components to test that have multiple different states.

        if (ButtonRaceSelectExtra.check(game.imageUtils)) {
            MessageLog.i(TAG, "[PASS] ${ButtonRaceSelectExtra.template.path}")
            numPass++
        } else if (ButtonRaceSelectExtraLocked.check(game.imageUtils)) {
            MessageLog.i(TAG, "[PASS] ${ButtonRaceSelectExtraLocked.template.path}")
            numPass++
        } else {
            MessageLog.e(TAG, "[FAIL] ${ButtonRaceSelectExtra.template.path}, ${ButtonRaceSelectExtraLocked.template.path}")
            numFail++
        }

        MessageLog.i(TAG, "Testing mood components...")
        when {
            IconMoodGreat.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconMoodGreat.template.path}")
                numPass++
            }
            IconMoodGood.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconMoodGood.template.path}")
                numPass++
            }
            IconMoodNormal.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconMoodNormal.template.path}")
                numPass++
            }
            IconMoodBad.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconMoodBad.template.path}")
                numPass++
            }
            IconMoodAwful.check(game.imageUtils) -> {
                MessageLog.i(TAG, "[PASS] ${IconMoodAwful.template.path}")
                numPass++
            }
            else -> {
                MessageLog.e(TAG, "[FAIL] Could not detect any mood icons.")
                numFail++
            }
        }

        MessageLog.i(TAG, "---- startMainScreenOCRTest END: PASS=$numPass, FAIL=$numFail ----")
    }

    /**
     * Handles all main screen logic including daily updates, racing decisions, and training.
     *
     * This is the primary decision-making function that determines what action the bot
     * should take when at the main screen. It handles date changes, aptitude/fan updates,
     * race detection, mood recovery, and training.
     *
     * @return True if the main screen was detected and handled, false otherwise.
     */
    fun handleMainScreen(): Boolean {
        if (!game.checkMainScreen()) {
            return false
        }

        // Perform first-time setup of loading the user's race agenda if needed.
        game.racing.loadUserRaceAgenda()

        val sourceBitmap = game.imageUtils.getSourceBitmap()

        // Operations to be done every time the date changes.
        // Skip if we've already checked the date this turn and no game-advancing action was taken.
        if (!bHasCheckedDateThisTurn) {
            if (game.updateDate() || !game.trainee.bHasUpdatedStats) {

                // Reset flags on date change.
                game.racing.encounteredRacingPopup = false
                game.racing.raceRepeatWarningCheck = false
                bHasTriedCheckingFansToday = false
                bHasCheckedForMaidenRaceToday = false

                // Update the fan count class every time we're at the main screen.
                val fanCountClass: FanCountClass? = getFanCountClass(sourceBitmap)
                if (fanCountClass != null) {
                    game.trainee.fanCountClass = fanCountClass
                }

                // Update trainee information using parallel processing with shared screenshot.
                val skillPointsLocation = game.imageUtils.findImageWithBitmap("skill_points", sourceBitmap, suppressError = true)

                if (!BotService.isRunning) {
                    return false
                }
                
                // Use CountDownLatch to run the operations in parallel.
                // 1 racingRequirements (skipped during summer) + 5 stats + 1 skill points + 1 mood = 8 (or 7) threads.
                val latch = if (game.currentDate.isSummer()) CountDownLatch(7) else CountDownLatch(8)

                MessageLog.disableOutput = true
                
                // Threads 1-5: Update stats (one thread per stat, created inside updateStats).
                // Pass the external latch so updateStats can count down for each stat thread.
                game.trainee.updateStats(game.imageUtils, sourceBitmap, skillPointsLocation, latch)
                
                // Thread 6: Update skill points.
                Thread {
                    try {
                        game.trainee.updateSkillPoints(game.imageUtils, sourceBitmap, skillPointsLocation)
                    } catch (e: Exception) {
                        MessageLog.e(TAG, "Error in updateSkillPoints thread: ${e.stackTraceToString()}")
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true }.start()
                
                // Thread 7: Update mood.
                Thread {
                    try {
                        game.trainee.updateMood(game.imageUtils, sourceBitmap)
                    } catch (e: Exception) {
                        MessageLog.e(TAG, "Error in updateMood thread: ${e.stackTraceToString()}")
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true }.start()

                // Thread 8: Update racing requirements.
                if (!game.currentDate.isSummer()) {
                    Thread {
                        try {
                            game.racing.checkRacingRequirements(sourceBitmap)
                        } catch (e: Exception) {
                            MessageLog.e(TAG, "Error in checkRacingRequirements thread: ${e.stackTraceToString()}")
                        } finally {
                            latch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }
                
                // Wait for all threads to complete.
                try {
                    latch.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    MessageLog.e(TAG, "Date change operations threads timed out.")
                } finally {
                    MessageLog.disableOutput = false
                }
                MessageLog.i(TAG, "[TRAINEE] Skills Updated: ${game.trainee.getStatsString()}")
                MessageLog.i(TAG, "[TRAINEE] Mood Updated: ${game.trainee.mood}")
                if (game.trainee.bHasUpdatedAptitudes) game.trainee.logInfo()

                // Now check if we need to handle skills before finals.
                if (game.currentDate.day == 72 && game.skillPlan.skillPlans["preFinals"]?.bIsEnabled ?: false) {
                    ButtonSkills.click(game.imageUtils)
                    game.wait(1.0)
                    if (!handleSkillListScreen()) {
                        MessageLog.w(TAG, "handleMainScreen:: handleSkillList() for Pre-Finals failed.")
                    }
                }
            }

            // Mark that we've checked the date this turn.
            bHasCheckedDateThisTurn = true
        }

        // If we haven't already handled the skill point check this run and
        // if the required skill points has been reached,
        // stop the bot or run the skill plan if it is enabled.
        if (
            !bHasHandledSkillPointCheck &&
            game.enableSkillPointCheck &&
            game.trainee.skillPoints >= game.skillPointsRequired
        ) {
            if (game.skillPlan.skillPlans["skillPointCheck"]?.bIsEnabled ?: false) {
                ButtonSkills.click(game.imageUtils)
                game.wait(1.0)
                if (!handleSkillListScreen("skillPointCheck")) {
                    MessageLog.w(TAG, "handleMainScreen:: handleSkillList() for Skill Point Check failed.")
                    throw InterruptedException("handleMainScreen:: handleSkillList() for Skill Point Check failed. Stopping bot...")
                }
                bHasHandledSkillPointCheck = true
            } else {
                throw InterruptedException("Bot reached skill point check threshold. Stopping bot...")
            }
        }

        // Since we're at the main screen, we don't need to worry about this
        // flag anymore since we will update our aptitudes here if needed.
        game.trainee.bTemporaryRunningStyleAptitudesUpdated = false

        if (!game.trainee.bHasUpdatedAptitudes) {
            openAptitudesDialog()
            waitForDialogProcessed()
        }

        val bIsScheduledRaceDay = LabelScheduledRace.check(game.imageUtils, sourceBitmap = sourceBitmap)
        val bIsMandatoryRaceDay = IconRaceDayRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap)
        var needToRace = bIsMandatoryRaceDay || bIsScheduledRaceDay

        // We don't need to bother checking fans on a mandatory race day.
        if (
            !game.currentDate.bIsFinaleSeason &&
            !bIsMandatoryRaceDay &&
            !bIsScheduledRaceDay &&
            bNeedToCheckFans &&
            !bHasTriedCheckingFansToday
        ) {
            openFansDialog()
            waitForDialogProcessed()
        }

        // Check if bot should stop before the finals.
        if (game.checkFinalsStop()) {
            throw InterruptedException("Reached finals. Stopping bot...")
        }

        if (!needToRace && !game.racing.encounteredRacingPopup) {
            if (game.racing.enableForceRacing) {
                // If force racing is enabled, skip all other activities and go straight to racing.
                MessageLog.i(TAG, "Force racing enabled - skipping all other activities and going straight to racing.")
                needToRace = true
            } else if (
                !bHasCheckedForMaidenRaceToday &&
                !game.currentDate.bIsPreDebut &&
                !game.trainee.bHasCompletedMaidenRace
            ) {
                // Need to check for maiden race.
                MessageLog.i(TAG, "[INFO] Bot has not yet completed maiden race. Checking for valid maiden race...")
                needToRace = true
            } else if (
                mustRestBeforeSummer &&
                (   game.currentDate.year == DateYear.CLASSIC ||
                    game.currentDate.year == DateYear.SENIOR
                ) &&
                game.currentDate.month == DateMonth.JUNE &&
                game.currentDate.phase == DatePhase.LATE
            ) {
                // Check if we need to rest before Summer Training (June Early/Late in Classic/Senior Year).
                MessageLog.i(TAG, "Forcing rest during ${game.currentDate} in preparation for Summer Training.")
                game.recoverEnergy(sourceBitmap)
                bHasCheckedDateThisTurn = false
            } else {
                val isRacingRequirementActive = game.racing.hasFanRequirement || game.racing.hasTrophyRequirement
                val isFinals = game.checkFinals()

                if (isRacingRequirementActive) {
                    MessageLog.i(TAG, "[INFO] Racing requirement is active. Bypassing health and mood checks.")
                    needToRace = true
                } else {
                    val hasInjury = if (isFinals) {
                        MessageLog.i(TAG, "[INFO] Skipping injury check due to it being the Finals.")
                        false
                    } else {
                        game.checkInjury(sourceBitmap)
                    }

                    if (hasInjury) {
                        game.findAndTapImage("ok", sourceBitmap = sourceBitmap, region = game.imageUtils.regionMiddle)
                        game.wait(3.0)
                        bHasCheckedDateThisTurn = false
                    } else {
                        val didRecoverMood = if (isFinals) {
                            MessageLog.i(TAG, "[INFO] Skipping mood recovery due to it being the Finals.")
                            false
                        } else {
                            game.recoverMood(sourceBitmap)
                        }

                        if (didRecoverMood) {
                            bHasCheckedDateThisTurn = false
                        } else {
                            val eligibleForExtraRace = game.racing.checkEligibilityToStartExtraRacingProcess()
                            
                            if (eligibleForExtraRace) {
                                MessageLog.i(TAG, "[INFO] Bot has no injuries, mood is sufficient and extra races can be run today. Setting the needToRace flag to true.")
                                needToRace = true
                            } else if (game.currentDate.day >= 16) {
                                // If not eligible for an extra race but we passed the day threshold, we train.
                                MessageLog.i(TAG, "[INFO] Training due to it not being an extra race day.")
                                game.training.handleTraining()
                                bHasCheckedDateThisTurn = false
                            } else {
                                // Fallback to training for early game.
                                MessageLog.i(TAG, "[INFO] Training due to it not being an extra race day.")
                                game.training.handleTraining()
                                bHasCheckedDateThisTurn = false
                            }
                        }
                    }
                }
            }
        }

        if (game.racing.encounteredRacingPopup || needToRace) {
            MessageLog.i(TAG, "[INFO] All checks are cleared for racing.")
            if (!handleRaceEvents(bIsScheduledRaceDay) && handleRaceEventFallback()) {
                throw InterruptedException("Mandatory race detected. Stopping bot...")
            }
            bHasCheckedDateThisTurn = false
        }
        return true
    }

	/**
	 * Handles the fallback logic when racing fails.
	 * This includes checking for mandatory race detection and falling back to training.
	 *
	 * @return True if the bot should break out of the main loop, false otherwise.
	 */
	internal fun handleRaceEventFallback(): Boolean {
		if (game.racing.detectedMandatoryRaceCheck) {
			MessageLog.i(TAG, "\n[END] Stopping bot due to detection of Mandatory Race.")
			game.notificationMessage = "Stopping bot due to detection of Mandatory Race."
			if (DiscordUtils.enableDiscordNotifications) {
				DiscordUtils.queue.add("```diff\n- ${MessageLog.getSystemTimeString()} Stopping bot due to detection of Mandatory Race.\n```")
			}
			return true
		}
        ButtonBack.click(game.imageUtils)
        ButtonCancel.click(game.imageUtils)
        ButtonClose.click(game.imageUtils)
		game.wait(1.0)
		game.training.handleTraining()
		return false
	}

	/**
	 * Main automation loop that handles all shared logic.
	 */
	fun start() {
		while (true) {
            try {
                // We always check for dialogs first.
                if (game.tryHandleAllDialogs()) {
                    continue
                }

                if (handleMainScreen()) {
                    continue
                } else if (game.checkTrainingEventScreen()) {
                    // If the bot is at the Training Event screen, that means there are selectable options for rewards.
                    handleTrainingEvent()
                } else if (game.checkMandatoryRacePrepScreen()) {
                    // If the bot is at the Main screen with the button to select a race visible,
                    // that means the bot needs to handle a mandatory race.
                    if (!handleRaceEvents() && game.racing.detectedMandatoryRaceCheck) {
                        throw InterruptedException("Mandatory race detected. Stopping bot...")
                    }
                } else if (game.checkRacingScreen()) {
                    // If the bot is already at the Racing screen, then complete this standalone race.
                    game.racing.handleStandaloneRace()
                } else if (game.checkEndScreen()) {
                    // Stop when the bot has reached the screen where it details the overall result of the run.
                    if (game.skillPlan.skillPlans["careerComplete"]?.bIsEnabled ?: false) {
                        ButtonCareerEndSkills.click(game.imageUtils)
                        game.wait(1.0)
                        if (!handleSkillListScreen()) {
                            MessageLog.w(TAG, "Career End Screen: handleSkillList() failed.")
                        }
                    }
                    throw InterruptedException("Bot had reached end of run. Stopping bot...")
                } else if (checkCampaignSpecificConditions()) {
                    MessageLog.i(TAG, "Campaign-specific checks complete.")
                } else if (game.handleInheritanceEvent()) {
                    // If the bot is at the Inheritance screen, then accept the inheritance.
                } else if (game.performMiscChecks()) {
                    MessageLog.d(TAG, "Misc checks complete.")
                } else {
                    MessageLog.v(TAG, "Did not detect the bot being at the following screens: Main, Training Event, Inheritance, Mandatory Race Preparation, Racing and Career End.")
                    // Tap to progress any intermediate screens.
                    game.tap(350.0, 450.0, "ok", taps = 1)
                }
            } catch (e: InterruptedException) {
                val stopReason = e.message ?: "Bot was manually stopped."
                game.notificationMessage = "Campaign main loop exiting: $stopReason"
                // Send Discord notification with the stop reason.
                if (DiscordUtils.enableDiscordNotifications) {
                    val isSuccess = stopReason.contains("Bot had reached end of run") == true
                    val colorChar = if (isSuccess) "+" else "-"
                    DiscordUtils.queue.add("```diff\n$colorChar ${MessageLog.getSystemTimeString()} $stopReason\n```")
                }
                if (stopReason.contains("Bot had reached end of run") == true) {
                    MessageLog.i(TAG, "Campaign main loop exiting: $stopReason")
                } else {
                    MessageLog.e(TAG, "Campaign main loop exiting: $stopReason")
                }
                break
            }
		}
	}
}
