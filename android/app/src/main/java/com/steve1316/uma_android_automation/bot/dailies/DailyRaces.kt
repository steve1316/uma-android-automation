package com.steve1316.uma_android_automation.bot.dailies

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonComplete
import com.steve1316.uma_android_automation.components.ButtonDailyRacesCard
import com.steve1316.uma_android_automation.components.ButtonDailyRacesJupiterCup
import com.steve1316.uma_android_automation.components.ButtonDailyRacesMoonlightSho
import com.steve1316.uma_android_automation.components.ButtonMultiRaceOff
import com.steve1316.uma_android_automation.components.ButtonRaceExclamation
import com.steve1316.uma_android_automation.components.ButtonRaceExclamationShiftedUp
import com.steve1316.uma_android_automation.components.LabelDailyRacesHeader
import com.steve1316.uma_android_automation.components.LabelRunnerSelection

/**
 * Spends every Daily Race ticket on the chosen race at its hardest difficulty.
 *
 * @property game The bot's [Game] instance.
 * @property flags Flags raised by the Dailies dialog handler.
 * @property enabled Whether the user turned this part on.
 * @property raceType "Moonlight Sho" or "Jupiter Cup".
 */
class DailyRaces(private val game: Game, private val flags: DailiesFlags, override val enabled: Boolean, private val raceType: String) : DailyPart {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]DailyRaces"
    }

    override val name: String = "Daily Races"

    override fun start(): PartOutcome {
        flags.ticketsExhausted = false
        val tickets = (enterDailyProgram(game, flags) ?: return PartOutcome.Skipped("done for today")).dailyRaces
        MessageLog.i(TAG, "[DAILIES] Daily Race tickets: ${tickets ?: "unreadable"}. Legend Race tickets: ${flags.legendTickets ?: "unreadable"}.")
        if (tickets == 0) return PartOutcome.Skipped("no tickets")

        openRaceList()
        tapTopRaceRow(game)
        if (!startMultiRace()) return PartOutcome.Skipped("no tickets")
        pickTopTrainee(game, ButtonRaceExclamationShiftedUp)
        runMultiRace()
        return PartOutcome.Done(if (tickets != null) "$tickets races" else "races run")
    }

    /**
     * Opens Daily Races from the Daily Programs screen, then the chosen race's difficulty list.
     *
     * @throws DailyStepFailed When a card or screen on the way never appears.
     */
    private fun openRaceList() {
        if (!ButtonDailyRacesCard.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Daily Races card not found")
        game.wait(1.0)
        game.waitForAny(LabelDailyRacesHeader, timeoutSec = 15.0) ?: throw DailyStepFailed("Daily Races screen did not appear")
        if (!(if (raceType == "Moonlight Sho") ButtonDailyRacesMoonlightSho else ButtonDailyRacesJupiterCup).click(game.imageUtils, tries = 30)) throw DailyStepFailed("$raceType card not found")
        game.wait(1.0)
    }

    /**
     * In Race Details, turns Multi-Race on, taps Race!, and waits for Runner Selection.
     *
     * @return False when the tickets ran out and the ticket purchase was declined.
     * @throws DailyStepFailed When Race! or Runner Selection never appears.
     */
    private fun startMultiRace(): Boolean {
        // Ensure Multi-Race is turned on.
        ButtonMultiRaceOff.click(game.imageUtils, tries = 1)
        if (!ButtonRaceExclamation.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Race Details Race! not found")
        game.wait(1.0)
        game.waitForAny(LabelRunnerSelection, timeoutSec = 20.0, stopIf = { flags.ticketsExhausted })
            ?: if (flags.ticketsExhausted) return false else throw DailyStepFailed("Runner Selection did not appear")
        return true
    }

    /**
     * Starts the Multi-Race and closes its results with Complete, then Close.
     *
     * @throws DailyStepFailed When a button on the way never appears.
     */
    private fun runMultiRace() {
        if (!ButtonRaceExclamationShiftedUp.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Multi-Race Race! not found")
        game.wait(1.0)
        game.waitForLoading()
        // The results can finish before the first check, so Close may already be showing in place of Complete.
        val (found, _) = game.tapUntil(ButtonComplete, ButtonClose) ?: throw DailyStepFailed("Daily Race results did not appear")
        if (found == ButtonComplete) {
            if (!ButtonComplete.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Complete not found")
            game.wait(1.0)
            game.tapUntil(ButtonClose) ?: throw DailyStepFailed("Daily Race results Close did not appear")
        }
        if (!ButtonClose.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Close not found")
        game.wait(1.0)
    }
}
