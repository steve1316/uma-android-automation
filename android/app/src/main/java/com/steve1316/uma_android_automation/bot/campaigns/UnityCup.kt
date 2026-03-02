package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog

import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.ButtonRace
import com.steve1316.uma_android_automation.components.ButtonNextRaceEnd
import com.steve1316.uma_android_automation.components.ButtonSelectOpponent
import com.steve1316.uma_android_automation.components.ButtonViewResultsLocked
import com.steve1316.uma_android_automation.components.ButtonUnityCupSeeAllRaceResults
import com.steve1316.uma_android_automation.components.DialogUtils
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.LabelUnityCupOpponentSelectionLaurel
import com.steve1316.uma_android_automation.components.IconDoubleCircle
import com.steve1316.uma_android_automation.components.IconUnityCupRaceEndLogo

import org.opencv.core.Point

class UnityCup(game: Game) : Campaign(game) {
    override val TAG: String = "[${MainActivity.loggerTag}]UnityCup"
	private var tutorialDisabled = false
    private var bIsFinals: Boolean = false
    private var selectedOpponentIndex: Int = 0
    private var bOverrideOpponentSelection: Boolean = false

    /**
     * Detects and handles any dialog popups.
     *
     * To prevent the bot moving too fast, we add a 500ms delay to the
     * exit of this function whenever we close the dialog.
     * This gives the dialog time to close since there is a very short
     * animation that plays when a dialog closes.
     *
     * @param dialog An optional dialog to evaluate. This allows chaining
     * dialog handler calls for improved performance.
     *
     * @return A pair of a boolean and a nullable DialogInterface.
     * The boolean is true when a dialog has been handled by this function.
     * The DialogInterface is the detected dialog, or NULL if no dialogs were found.
     */
    override fun handleDialogs(dialog: DialogInterface?, args: Map<String, Any>): Pair<Boolean, DialogInterface?> {
        val (bDialogHandled, dialog) = super.handleDialogs(dialog, args)
        if (bDialogHandled) {
            return Pair(bDialogHandled, dialog)
        }
        if (dialog == null) {
            return Pair(false, null)
        }

        when (dialog.name) {
            "auto_fill" -> dialog.close(imageUtils = game.imageUtils)
            "unity_cup_confirmation" -> {
                if (bIsFinals) {
                    dialog.ok(imageUtils = game.imageUtils)
                } else if (bOverrideOpponentSelection || analyzeOpponentRacePrediction()) {
                    dialog.ok(imageUtils = game.imageUtils)
                } else {
                    dialog.close(imageUtils = game.imageUtils)
                    if (selectedOpponentIndex >= 2) {
                        MessageLog.w(TAG, "[UNITY_CUP] Could not determine any opponent with sufficient double circle predictions. Selecting the 2nd opponent as a fallback.")
                        selectedOpponentIndex = 1
                        bOverrideOpponentSelection = true
                    } else {
                        selectedOpponentIndex++
                    }
                }
                // Since we're incrementing a value in here, we want to delay before
                // returning to ensure that we do not accidentally call handleDialogs()
                // and end up right back in here and instantly increment again before
                // this dialog has a chance to close.
                game.wait(0.5, skipWaitingForLoading = true)
                return Pair(true, dialog)
            }
            else -> return Pair(false, dialog)
        }
        return Pair(true, dialog)
    }

    private fun analyzeOpponentRacePrediction(): Boolean {
        val doubleCircles = IconDoubleCircle.findAll(imageUtils = game.imageUtils, region = game.imageUtils.regionMiddle, confidence = 0.0)
        if (doubleCircles.size >= 3) {
            MessageLog.i(TAG, "[UNITY_CUP] Race #${selectedOpponentIndex + 1} has sufficient double circle predictions. Selecting it now...")
            return true
        } else {
            MessageLog.i(TAG, "[UNITY_CUP] Race #${selectedOpponentIndex + 1} only had ${doubleCircles.size} double predictions and falls short. Skipping this opponent.")
            return false
        }
    }

	override fun handleTrainingEvent() {
        MessageLog.i(TAG, "\n[UNITY_CUP] Running handleTrainingEvent() for Unity Cup.")
        if (!tutorialDisabled) {
            tutorialDisabled = if (game.imageUtils.findImage("unitycup_tutorial_header", tries = 1, region = game.imageUtils.regionTopHalf).first != null) {
                // If the tutorial is detected, select the second option to close it.
                MessageLog.i(TAG, "\n[UNITY_CUP] Detected tutorial for Unity Cup. Closing it now...")
                val trainingOptionLocations: ArrayList<Point> = game.imageUtils.findAll("training_event_active")
                game.gestureUtils.tap(trainingOptionLocations[1].x, trainingOptionLocations[1].y, "training_event_active")
                true
            } else {
                MessageLog.i(TAG, "\n[UNITY_CUP] Tutorial must have already been dismissed.")
                super.handleTrainingEvent()
                true
            }
        } else {
            super.handleTrainingEvent()
        }
	}

	override fun handleRaceEvents(isScheduledRace: Boolean): Boolean {
        MessageLog.i(TAG, "\n[UNITY_CUP] Running handleRaceEvents() for Unity Cup.")
		if (game.imageUtils.findImage("unitycup_race", tries = 1, region = game.imageUtils.regionBottomHalf).first != null) {
            // Handle the Unity Cup race.
            MessageLog.i(TAG, "[UNITY_CUP] Will start the process for Unity Cup race handling.")
			handleRaceEventsUnityCup()
			return true
		}

		// Fall back to the regular race handling logic.
		return super.handleRaceEvents(isScheduledRace)
	}

	override fun checkCampaignSpecificConditions(): Boolean {
        return handleRaceEventsUnityCup()
	}
	
	/**
	 * Handles the Unity Cup race event.
	 */
	private fun handleRaceEventsUnityCup(): Boolean {
		MessageLog.i(TAG, "[UNITY_CUP] Starting process for handling the Unity Cup racing process.")
		
        // If none of these exist then we aren't in any unity cup screens at the moment. Abort.
        if (
            game.imageUtils.findImage("unitycup_race", region = game.imageUtils.regionBottomHalf).first == null &&
            game.imageUtils.findImage("unitycup_final_race", region = game.imageUtils.regionBottomHalf).first == null &&
            game.imageUtils.findImage("unitycup_race_manual", region = game.imageUtils.regionBottomHalf).first == null
        ) {
            return false
        }

        // We use this as a means of exiting the loop if it runs too long.
        val executionTimeThresholdMs = 30000 // 30 seconds
        val startTime = System.currentTimeMillis()

        while (true) {
            val sourceBitmap: Bitmap = game.imageUtils.getSourceBitmap()
            when {
                handleDialogs().first -> {}
                // Go to opponent selection screen.
                game.findAndTapImage("unitycup_race", sourceBitmap = sourceBitmap) -> {
                    MessageLog.d(TAG, "[UNITY_CUP] Going to opponent selection screen...")
                    selectedOpponentIndex = 0
                    bOverrideOpponentSelection = false
                }
                game.findAndTapImage("unitycup_final_race", sourceBitmap = sourceBitmap) -> {
                    MessageLog.i(TAG, "[UNITY_CUP] Final race detected with Team Zenith.")
                    bIsFinals = true
                }
                // Handle opponent selection.
                ButtonSelectOpponent.check(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    val opponents: ArrayList<Point> = LabelUnityCupOpponentSelectionLaurel.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
                    if (opponents.size != 3) {
                        MessageLog.e(TAG, "[UNITY_CUP] Failed to detect all three opponents on opponent selection screen.")
                        return false
                    }

                    selectedOpponentIndex = selectedOpponentIndex.coerceIn(0, opponents.lastIndex)
                    val opponent = opponents[selectedOpponentIndex]
                    game.gestureUtils.tap(opponent.x, opponent.y, LabelUnityCupOpponentSelectionLaurel.template.path)
                    // Tiny delay to allow the opponent selection click to register fully.
                    game.wait(0.1, skipWaitingForLoading = true)
                    MessageLog.d(TAG, "[UNITY_CUP] Selecting opponent #${selectedOpponentIndex + 1} at ${opponent}")
                    ButtonSelectOpponent.click(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap)
                    // Clicking SelectOpponent requires connect to server. Don't skip
                    // waiting for loading otherwise we might miss handling a dialog.
                    game.wait(0.5)
                }
                // If the skip button is locked, need to manually run the race.
                ButtonViewResultsLocked.check(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    MessageLog.d(TAG, "[UNITY_CUP] Race skip is locked. Manually running race...")
                    game.findAndTapImage("unitycup_race_manual", region = game.imageUtils.regionBottomHalf)
                    game.racing.runRaceWithRetries()
                }
                // Skip the race if possible.
                ButtonUnityCupSeeAllRaceResults.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    MessageLog.d(TAG, "[UNITY_CUP] Skipping to race results.")
                }
                // This is our only natural exit point from this function.
                IconUnityCupRaceEndLogo.check(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) && ButtonNext.click(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    MessageLog.i(TAG, "[UNITY_CUP] Race event completed.")
                    return true
                }
                ButtonNext.click(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) -> {}
                ButtonSkip.click(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) -> {}
                ButtonNextRaceEnd.click(imageUtils = game.imageUtils, sourceBitmap = sourceBitmap) -> {}
                // Exit from function if it runs too long.
                System.currentTimeMillis() - startTime > executionTimeThresholdMs -> {
                    MessageLog.i(TAG, "[UNITY_CUP] Race event took too long to complete. Aborting...")
                    return false
                }
                // Tap on the screen to skip past any intermediate screens.
                else -> game.tap(350.0, 750.0, "ok", taps = 3)
            }
        }
	}
}
