package com.steve1316.uma_android_automation.bot.dailies

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonNextWithImage
import com.steve1316.uma_android_automation.components.ButtonQuickModeOff
import com.steve1316.uma_android_automation.components.ButtonRaceAgain
import com.steve1316.uma_android_automation.components.ButtonRaceExclamationShiftedUp
import com.steve1316.uma_android_automation.components.ButtonSeeAllRaceResults
import com.steve1316.uma_android_automation.components.ButtonTeamRace
import com.steve1316.uma_android_automation.components.ButtonTeamTrials
import com.steve1316.uma_android_automation.components.LabelSelectOpponent
import com.steve1316.uma_android_automation.components.LabelTeamRankBadge
import com.steve1316.uma_android_automation.components.LabelTopBarRp
import org.opencv.core.Point

/** From the Items Selected Race! button: the RP count before the race, the "x/5" left of the arrow. The "y/5" after it is left out. */
private val ITEMS_SELECTED_RP = intArrayOf(-257, -113, 85, 38)

/** From the top bar's "RP" logo: the "x/5" RP count, leaving out the recharge timer between them. */
private val TOP_BAR_RP = intArrayOf(140, -20, 65, 34)

/** RP tops out at 5, so 6 races is a safe ceiling. */
private const val MAX_RACES = 6

/**
 * Spends all RP on Team Trials against the weakest opponent.
 *
 * @property game The bot's [Game] instance.
 * @property flags Flags raised by the Dailies dialog handler.
 * @property enabled Whether the user turned this part on.
 * @property useParfait Whether to use a Pleasing Parfait each race.
 */
class TeamTrials(private val game: Game, private val flags: DailiesFlags, override val enabled: Boolean, private val useParfait: Boolean) : DailyPart {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]TeamTrials"
    }

    override val name: String = "Team Trials"

    override fun start(): PartOutcome {
        flags.rpExhausted = false
        // The top bar shows RP on Home, so an empty bar skips the whole trip to Team Trials.
        if (readTopBarRp() == 0) return PartOutcome.Skipped("no RP")
        openTeamRace()
        var races = 0

        repeat(MAX_RACES) {
            if (flags.rpExhausted) return finish(races)
            if (!selectWeakestOpponent()) return leave(races)
            val raceButton = openItemsSelected()
            val rp = readRp(raceButton, ITEMS_SELECTED_RP, "team_trials_rp")
            MessageLog.i(TAG, "[DAILIES] RP before this race: ${rp ?: "unreadable"}.")
            if (rp == 0) {
                ButtonCancel.click(game.imageUtils)
                return finish(races)
            }
            if (useParfait) useParfait(game)
            if (!startRace(raceButton)) return leave(races)
            watchResults()
            races++

            val raceAgain = tapThroughToRaceAgain()
            if (rp == 1) return leave(races)
            game.tap(raceAgain.x, raceAgain.y)
            game.wait(2.0)
        }
        return finish(races)
    }

    /**
     * Opens Team Trials from the Race tab and taps Team Race.
     *
     * @throws DailyStepFailed When a button or screen on the way never appears.
     */
    private fun openTeamRace() {
        openRaceTab(game)
        if (!ButtonTeamTrials.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Team Trials button not found")
        game.wait(2.0)
        game.waitForLoading()
        game.waitForAny(ButtonTeamRace, timeoutSec = 15.0) ?: throw DailyStepFailed("Team Trials screen did not appear")
        if (!ButtonTeamRace.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Team Race button not found")
        game.wait(1.0)
        game.waitForLoading()
    }

    /**
     * Waits for opponent selection and taps the weakest opponent. Each card has a Team Rank badge and the cards run strongest to weakest, so
     * the lowest badge is the weakest opponent.
     *
     * @return False when RP ran out before opponent selection appeared.
     * @throws DailyStepFailed When opponent selection or its cards never appear.
     */
    private fun selectWeakestOpponent(): Boolean {
        val header =
            game.waitForAny(LabelSelectOpponent, timeoutSec = 20.0, stopIf = { flags.rpExhausted })?.second
                ?: if (flags.rpExhausted) return false else throw DailyStepFailed("Opponent selection did not appear")
        game.wait(1.0)
        val weakest = LabelTeamRankBadge.findAll(game.imageUtils).filter { it.y > header.y }.maxByOrNull { it.y } ?: throw DailyStepFailed("Opponent cards not found")
        game.tap(weakest.x, weakest.y)
        game.wait(2.0)
        game.waitForLoading()
        return true
    }

    /**
     * Taps the line-up's Next and waits for Items Selected.
     *
     * @return The centre of the Items Selected Race! button.
     * @throws DailyStepFailed When Next or Items Selected never appears.
     */
    private fun openItemsSelected(): Point {
        if (!ButtonNext.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Team line-up Next not found")
        game.wait(1.0)
        val raceButton = game.waitForAny(ButtonRaceExclamationShiftedUp)?.second ?: throw DailyStepFailed("Items Selected did not appear")
        game.wait(0.5)
        return raceButton
    }

    /**
     * Reads the RP left on Home from the top bar.
     *
     * @return The RP, or null when the top bar or its count is unreadable.
     */
    private fun readTopBarRp(): Int? {
        val logo = game.waitForAny(LabelTopBarRp)?.second ?: return null
        val rp = readRp(logo, TOP_BAR_RP, "top_bar_rp")
        MessageLog.i(TAG, "[DAILIES] RP on Home: ${rp ?: "unreadable"}.")
        return rp
    }

    /**
     * Reads an "x/5" RP count in a box offset from an anchor.
     *
     * @param anchor The centre of the anchor template.
     * @param box Left, top, width and height of the count, relative to the anchor.
     * @param debugName Debug name for the OCR crop.
     * @return The RP, or null when unreadable.
     */
    private fun readRp(anchor: Point, box: IntArray, debugName: String): Int? {
        val text =
            game.imageUtils.performOCRFromReference(
                anchor,
                box[0],
                box[1],
                game.imageUtils.relWidth(box[2]),
                game.imageUtils.relHeight(box[3]),
                useThreshold = false,
                debugName = debugName,
            )
        return parseCounter(text)?.first
    }

    /**
     * Taps Race! and waits for the results screen, turning Quick Mode on when it is off.
     *
     * @param raceButton The centre of the Items Selected Race! button.
     * @return False when RP ran out before the results screen appeared.
     * @throws DailyStepFailed When the results screen never appears.
     */
    private fun startRace(raceButton: Point): Boolean {
        game.tap(raceButton.x, raceButton.y)
        game.wait(2.0)
        game.waitForLoading()
        val (results, _) =
            game.waitForAny(ButtonQuickModeOff, ButtonSeeAllRaceResults, timeoutSec = 30.0, stopIf = { flags.rpExhausted })
                ?: if (flags.rpExhausted) return false else throw DailyStepFailed("Team Trials results screen did not appear")
        if (results == ButtonQuickModeOff) {
            MessageLog.i(TAG, "[DAILIES] Turning Quick Mode on.")
            ButtonQuickModeOff.click(game.imageUtils)
            game.wait(1.0)
        }
        game.waitForLoading()
        return true
    }

    /**
     * Taps See All Race Results and skips the race.
     *
     * @throws DailyStepFailed When See All Race Results or the end of the race never appears.
     */
    private fun watchResults() {
        game.waitForLoading()
        game.wait(2.0)

        if (!ButtonSeeAllRaceResults.click(game.imageUtils, tries = 30)) throw DailyStepFailed("See All Race Results not found")
        game.wait(2.0)
        skipRace(game, ButtonNext, ButtonNextWithImage, ButtonRaceAgain)
    }

    /**
     * Taps through results, rewards and winnings until the final screen with Race Again.
     *
     * @return The centre of the Race Again button.
     * @throws DailyStepFailed When Race Again never appears.
     */
    private fun tapThroughToRaceAgain(): Point {
        repeat(5) {
            val (found, at) = game.tapUntil(ButtonRaceAgain, ButtonNextWithImage, ButtonNext) ?: throw DailyStepFailed("Team Trials final screen did not appear")
            if (found == ButtonRaceAgain) return at
            game.tap(at.x, at.y)
            game.wait(1.0)
            game.waitForLoading()
        }
        throw DailyStepFailed("Race Again did not appear")
    }

    /**
     * Leaves the final results screen with Next and reports the races run.
     *
     * @param races Number of races run.
     * @return The outcome from `finish`.
     */
    private fun leave(races: Int): PartOutcome {
        ButtonNextWithImage.click(game.imageUtils)
        return finish(races)
    }

    /**
     * Builds the outcome for the races run.
     *
     * @param races Number of races run.
     * @return Done with the count, or Skipped when RP was already out.
     */
    private fun finish(races: Int): PartOutcome = if (races > 0) PartOutcome.Done("$races races") else PartOutcome.Skipped("no RP")
}
