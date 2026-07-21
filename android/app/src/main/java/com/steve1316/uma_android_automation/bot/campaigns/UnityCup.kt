package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonNextRaceEnd
import com.steve1316.uma_android_automation.components.ButtonSelectOpponent
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.ButtonTrainingGuts
import com.steve1316.uma_android_automation.components.ButtonTrainingPower
import com.steve1316.uma_android_automation.components.ButtonTrainingSpeed
import com.steve1316.uma_android_automation.components.ButtonTrainingStamina
import com.steve1316.uma_android_automation.components.ButtonTrainingWit
import com.steve1316.uma_android_automation.components.ButtonTryAgainAlt
import com.steve1316.uma_android_automation.components.ButtonUnityCupRace
import com.steve1316.uma_android_automation.components.ButtonUnityCupRaceFinal
import com.steve1316.uma_android_automation.components.ButtonUnityCupSeeAllRaceResults
import com.steve1316.uma_android_automation.components.ButtonUnityCupWatchMainRace
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.IconDoubleCircle
import com.steve1316.uma_android_automation.components.IconTrainingEventHorseshoe
import com.steve1316.uma_android_automation.components.IconTrainingHeaderGuts
import com.steve1316.uma_android_automation.components.IconTrainingHeaderPower
import com.steve1316.uma_android_automation.components.IconTrainingHeaderSpeed
import com.steve1316.uma_android_automation.components.IconTrainingHeaderStamina
import com.steve1316.uma_android_automation.components.IconTrainingHeaderWit
import com.steve1316.uma_android_automation.components.IconUnityCupRaceEndLogo
import com.steve1316.uma_android_automation.components.IconUnityCupTutorialHeader
import com.steve1316.uma_android_automation.components.LabelUnityCupOpponentSelectionLaurel
import com.steve1316.uma_android_automation.types.StatName
import org.opencv.core.Point

/**
 * Handles the Unity Cup scenario with scenario-specific logic and handling.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class UnityCup(game: Game) : Campaign(game) {
    override val training = UnityCupTraining(game, this)

    /** Flag indicating if the tutorial has been disabled. */
    private var tutorialDisabled = false

    /** Flag indicating if the bot is currently in the finals. */
    private var bIsFinals: Boolean = false

    /** The index of the currently selected opponent. */
    private var selectedOpponentIndex: Int = 0

    /** Flag indicating if the opponent selection should be overridden. */
    private var bOverrideOpponentSelection: Boolean = false

    /** Whether to retry a lost Unity Cup race. Read once per bot-run from the Scenario Overrides settings. */
    private val retryRaces: Boolean = SettingsHelper.getBooleanSetting("scenarioOverrides", "unityCupRetryRaces", true)

    /**
     * Flag indicating that the Try Again button was just clicked on the skip-results screen, so the confirmation dialog that follows belongs to a Unity Cup race rather than a
     * mandatory one. Scopes the retry overrides to that dialog only, leaving mandatory races on this scenario with the shared retry semantics.
     */
    private var bAwaitingSkipRetryConfirm: Boolean = false

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    override fun handleDialogs(dialog: DialogInterface?, args: Map<String, Any>): DialogHandlerResult {
        val result: DialogHandlerResult = super.handleDialogs(dialog, args)
        if (result !is DialogHandlerResult.Unhandled) {
            return result
        }

        when (result.dialog.name) {
            "auto_fill" -> {
                result.dialog.close(game.imageUtils)
            }

            "unity_cup_confirmation" -> {
                if (bIsFinals) {
                    result.dialog.ok(game.imageUtils)
                } else if (bOverrideOpponentSelection || analyzeOpponentRacePrediction()) {
                    result.dialog.ok(game.imageUtils)
                } else {
                    result.dialog.close(game.imageUtils)
                    if (selectedOpponentIndex >= 2) {
                        MessageLog.w(TAG, "[WARN] handleDialogs:: Could not determine any opponent with sufficient double circle predictions. Selecting the 2nd opponent as a fallback.")
                        selectedOpponentIndex = 1
                        bOverrideOpponentSelection = true
                    } else {
                        selectedOpponentIndex++
                    }
                }
                game.wait(0.5)
                return DialogHandlerResult.Handled(result.dialog)
            }

            else -> {
                Log.w(TAG, "[WARN] handleDialogs:: Unknown dialog \"${result.dialog.name}\" detected so it will not be handled.")
                return DialogHandlerResult.Unhandled(result.dialog)
            }
        }
        game.wait(0.5)
        return DialogHandlerResult.Handled(result.dialog)
    }

    override fun handleTrainingEvent() {
        if (!tutorialDisabled) {
            tutorialDisabled =
                if (IconUnityCupTutorialHeader.check(game.imageUtils)) {
                    // If the tutorial is detected, select the second option to close it.
                    MessageLog.i(TAG, "\n[UNITY_CUP] Detected tutorial for Unity Cup. Closing it now...")
                    val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
                    game.gestureUtils.tap(trainingOptionLocations[1].x, trainingOptionLocations[1].y, IconTrainingEventHorseshoe.template.path)
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
        if (ButtonUnityCupRace.check(game.imageUtils)) {
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
     * Losing a Unity Cup race never ends the career - its Try Again dialog states the career continues - so the mandatory-race-failure path must not apply to that dialog.
     * Mandatory races run on this scenario still fall through to the shared handling, hence the check on [bAwaitingSkipRetryConfirm] rather than a blanket false.
     *
     * @return False only while confirming a Unity Cup skip-path retry.
     */
    override fun isRaceLossCareerEnding(): Boolean = !bAwaitingSkipRetryConfirm

    /**
     * Confirms the Try Again dialog that opens after clicking Try Again on a lost, skipped Unity Cup race.
     *
     * The game caps how many times such a race can be retried and greys out its own Try Again button once exhausted, so the shared mandatory-race retry pool is deliberately
     * left untouched here. Any other Try Again dialog on this scenario (i.e. a mandatory race) is delegated to the base implementation so it keeps the shared budget semantics.
     *
     * @param dialog The Try Again dialog.
     * @param args Additional arguments from dialog handling.
     * @return True if the retry was confirmed, false to close the dialog without retrying.
     */
    override fun shouldRetryRace(dialog: DialogInterface, args: Map<String, Any>): Boolean {
        if (!bAwaitingSkipRetryConfirm) {
            return super.shouldRetryRace(dialog, args)
        }
        bAwaitingSkipRetryConfirm = false

        MessageLog.i(TAG, "[UNITY_CUP] Confirming the Try Again dialog to retry the lost race...")
        if (dialog.ok(game.imageUtils)) {
            game.wait(1.0)
        }
        return true
    }

    /**
     * Analyzes the opponent race prediction images to determine if they are favorable.
     *
     * @return True if there are sufficient double circle predictions, false otherwise.
     */
    private fun analyzeOpponentRacePrediction(): Boolean {
        val doubleCircles = IconDoubleCircle.findAll(game.imageUtils, region = game.imageUtils.regionMiddle, confidence = 0.0)
        if (doubleCircles.size >= 3) {
            MessageLog.i(TAG, "[UNITY_CUP] Race #${selectedOpponentIndex + 1} has sufficient double circle predictions. Selecting it now...")
            return true
        } else {
            MessageLog.i(TAG, "[UNITY_CUP] Race #${selectedOpponentIndex + 1} only had ${doubleCircles.size} double predictions and falls short. Skipping this opponent.")
            return false
        }
    }

    /**
     * Handles the scenario-specific process for Unity Cup races.
     *
     * @return True if the race sequence was completed, false otherwise.
     */
    private fun handleRaceEventsUnityCup(): Boolean {
        MessageLog.i(TAG, "[UNITY_CUP] Starting process for handling the Unity Cup racing process.")

        // If none of these exist then we aren't in any Unity Cup screens at the moment. Abort.
        if (!ButtonUnityCupRace.check(game.imageUtils) && !ButtonUnityCupRaceFinal.check(game.imageUtils) && !ButtonUnityCupWatchMainRace.check(game.imageUtils)) {
            return false
        }

        // We use this as a means of exiting the loop if it runs too long.
        val executionTimeThresholdMs = 30000 // 30 seconds.
        var startTime = System.currentTimeMillis()

        // Tracks how many times a lost, skipped race was retried so the outcome can be logged.
        var raceRetryCount = 0

        // Clear any flag left over from a retry whose confirmation dialog never appeared, so it cannot leak into a later mandatory race.
        bAwaitingSkipRetryConfirm = false

        while (true) {
            val sourceBitmap: Bitmap = game.imageUtils.getSourceBitmap()
            when {
                handleDialogs() is DialogHandlerResult.Handled -> {}

                // Go to opponent selection screen.
                ButtonUnityCupRace.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    selectedOpponentIndex = 0
                    bOverrideOpponentSelection = false
                    game.waitForLoading()
                }

                ButtonUnityCupRaceFinal.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    MessageLog.i(TAG, "[UNITY_CUP] Final race detected with Team Zenith.")
                    bIsFinals = true
                    game.waitForLoading()
                }

                // Handle opponent selection.
                ButtonSelectOpponent.check(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    val opponents: ArrayList<Point> = LabelUnityCupOpponentSelectionLaurel.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
                    if (opponents.size != 3) {
                        // A high-rank team's entrance animation (e.g. the S-rank team on Senior Late June, ~4-5s) can still be playing and briefly hide the opponent laurels. Wait and let
                        // the loop re-scan a fresh screenshot rather than bailing the whole handler; the loop's 30s timeout still bounds a genuinely stuck screen.
                        MessageLog.d(TAG, "[DEBUG] handleRaceEventsUnityCup:: Detected ${opponents.size}/3 opponents (an entrance animation may still be playing). Waiting to retry...")
                        game.wait(1.5)
                        continue
                    }

                    selectedOpponentIndex = selectedOpponentIndex.coerceIn(0, opponents.lastIndex)
                    val opponent = opponents[selectedOpponentIndex]
                    game.gestureUtils.tap(opponent.x, opponent.y, LabelUnityCupOpponentSelectionLaurel.template.path)
                    // Tiny delay to allow the opponent selection click to register fully.
                    game.wait(0.1, skipWaitingForLoading = true)
                    MessageLog.i(TAG, "[UNITY_CUP] Selecting opponent #${selectedOpponentIndex + 1} at $opponent.")
                    ButtonSelectOpponent.click(game.imageUtils, sourceBitmap = sourceBitmap)
                    // Clicking SelectOpponent requires connect to server. Don't skip waiting for loading otherwise we might miss handling a dialog.
                    game.wait(game.dialogWaitDelay)
                }

                // If the skip button is locked, need to manually run the race.
                ButtonUnityCupSeeAllRaceResults.check(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    when (ButtonUnityCupSeeAllRaceResults.checkDisabled(game.imageUtils, sourceBitmap)) {
                        // Manually run the race.
                        true -> {
                            MessageLog.d(TAG, "[DEBUG] handleRaceEventsUnityCup:: See All Race Results button is locked. Manually running race...")
                            if (ButtonUnityCupWatchMainRace.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                                MessageLog.i(TAG, "[INFO] Clicked Watch Main Race button.")
                                game.waitForLoading()
                                racing.runRaceWithRetries(retryUntilFirst = retryRaces)
                            } else {
                                MessageLog.w(TAG, "[WARN] handleRaceEventsUnityCup:: Failed to click the Watch Main Race button.")
                            }
                        }

                        // Skip the race.
                        false -> {
                            if (ButtonUnityCupSeeAllRaceResults.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                                MessageLog.i(TAG, "[INFO] Clicked the See All Race Results button to skip the race.")
                                game.waitForLoading()
                            } else {
                                MessageLog.w(TAG, "[WARN] handleRaceEventsUnityCup:: Failed to click the See All Race Results button.")
                            }
                        }

                        // Shouldn't ever fail this since we already detected it once.
                        null -> {
                            MessageLog.e(TAG, "[ERROR] handleRaceEventsUnityCup:: Detected See All Race Results button, but then failed to check its disabled state.")
                        }
                    }
                }

                // On the skip-results screen the Try Again button is only enabled after a loss (it greys out on a win), so its enabled state alone gates the retry.
                retryRaces && ButtonTryAgainAlt.checkDisabled(game.imageUtils, sourceBitmap = sourceBitmap) == false -> {
                    if (ButtonTryAgainAlt.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                        raceRetryCount++
                        // Mark the confirmation dialog that follows as this scenario's so the retry overrides only apply to it.
                        bAwaitingSkipRetryConfirm = true
                        MessageLog.i(TAG, "[UNITY_CUP] Lost the skipped race. Opening the Try Again dialog to retry (attempt #$raceRetryCount)...")
                        // Reset the abort timer so re-running the race does not trip the execution-time threshold mid-retry.
                        startTime = System.currentTimeMillis()
                        game.wait(3.0)
                    } else {
                        MessageLog.w(TAG, "[WARN] handleRaceEventsUnityCup:: Detected the Try Again button but failed to click it. Cannot retry the lost race.")
                    }
                }

                // This is our only natural exit point from this function.
                IconUnityCupRaceEndLogo.check(game.imageUtils, sourceBitmap = sourceBitmap) && ButtonNext.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    val retrySuffix = if (raceRetryCount > 0) " after $raceRetryCount retry attempt(s)" else ""
                    MessageLog.i(TAG, "[UNITY_CUP] Race event completed$retrySuffix.")
                    return true
                }

                ButtonNext.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {}

                ButtonSkip.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {}

                ButtonNextRaceEnd.click(game.imageUtils, sourceBitmap = sourceBitmap) -> {
                    // Clicking this button triggers connection to server.
                    game.waitForLoading()
                }

                // Exit from function if it runs too long.
                System.currentTimeMillis() - startTime > executionTimeThresholdMs -> {
                    MessageLog.w(TAG, "[WARN] handleRaceEventsUnityCup:: Race event took too long to complete. Aborting...")
                    return false
                }

                // Tap on the screen to skip past any intermediate screens.
                else -> {
                    game.tap(350.0, 750.0, taps = 3)
                }
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug tests

    override fun startTests(): Boolean {
        var bDidAnyTestsRun = super.startTests()

        val fnMap: Map<String, () -> Unit> =
            mapOf(
                "debugMode_startSpiritGaugeDetectionTest" to ::startSpiritGaugeDetectionTest,
            )

        for ((settingName, fn) in fnMap) {
            if (SettingsHelper.getBooleanSetting("debug", settingName)) {
                fn()
                bDidAnyTestsRun = true
            }
        }

        return bDidAnyTestsRun
    }

    /**
     * Debug test for Unity Cup Spirit Gauge detection. Start on the Training screen (any facility). It walks all five facilities
     * beginning at Speed - selecting each, capturing the screen, and running the gauge analysis - then reports
     * each facility's fillable / normal-burst / extreme-burst counts. With Debug Mode on the gauge crops save to
     * filesDir/temp for pulling, so gauge detection can be verified without playing a full career.
     */
    fun startSpiritGaugeDetectionTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning the Unity Cup Spirit Gauge Detection test. Open the Training screen so the support gauges are visible.")

        if (!ButtonTrainingSpeed.check(game.imageUtils)) {
            MessageLog.w(TAG, "[TEST] Not on the Training screen (Speed training button not found). Open the Training screen and retry.")
            return
        }

        val trainingButtons: Map<StatName, ComponentInterface> =
            mapOf(
                StatName.SPEED to ButtonTrainingSpeed,
                StatName.STAMINA to ButtonTrainingStamina,
                StatName.POWER to ButtonTrainingPower,
                StatName.GUTS to ButtonTrainingGuts,
                StatName.WIT to ButtonTrainingWit,
            )
        val iconTrainingHeaders: Map<StatName, ComponentInterface> =
            mapOf(
                StatName.SPEED to IconTrainingHeaderSpeed,
                StatName.STAMINA to IconTrainingHeaderStamina,
                StatName.POWER to IconTrainingHeaderPower,
                StatName.GUTS to IconTrainingHeaderGuts,
                StatName.WIT to IconTrainingHeaderWit,
            )

        for (statName in StatName.entries) {
            val header = iconTrainingHeaders.getValue(statName)
            val button = trainingButtons.getValue(statName)

            // Select the facility if it is not already the active one.
            if (!header.check(game.imageUtils)) {
                var selected = false
                for (attempt in 0..2) {
                    button.click(game.imageUtils)
                    game.wait(0.3, skipWaitingForLoading = true)
                    if (header.check(game.imageUtils)) {
                        selected = true
                        break
                    }
                }
                if (!selected) {
                    MessageLog.w(TAG, "[TEST] Could not select $statName training after 3 attempts. Skipping it.")
                    continue
                }
            }

            val sourceBitmap = game.imageUtils.getSourceBitmap()
            game.imageUtils.saveDebugScreenshot(sourceBitmap, "spiritScreen_$statName")
            val result = game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap)
            MessageLog.i(
                TAG,
                "[TEST] $statName -> fillable=${result?.numGaugesCanFill ?: 0}, ready to burst=${result?.numGaugesReadyToBurst ?: 0}, ready to extreme burst=${result?.numGaugesReadyToExtremeBurst ?: 0}",
            )
        }

        MessageLog.i(TAG, "[TEST] Spirit Gauge Detection test complete. Check the per-facility counts above (and the debug_spiritExplosionGauge*.png crops if Debug Mode is on).")
    }
}
