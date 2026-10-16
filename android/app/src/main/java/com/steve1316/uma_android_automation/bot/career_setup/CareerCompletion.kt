package com.steve1316.uma_android_automation.bot.career_setup

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.CampaignBreakpointException
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonCompleteCareer
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonMenuBarHome
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.DialogUtils

/** How long finishing a career may take before the bot gives up, in seconds. The screens take about a minute with every animation. */
private const val COMPLETION_TIMEOUT_SECONDS = 180

/** Dialogs that are confirmed to move the finish forward. Every other dialog on the way, such as Career Complete or Event Home, is closed or cancelled. */
private val CONFIRMED_DIALOGS = setOf("complete_career", "sparks_confirmation")

/** Buttons that end the tapping between screens: Home, or a button on the next screen or dialog to act on. */
private val WAYPOINTS = arrayOf(ButtonMenuBarHome, ButtonCompleteCareer, ButtonNext, ButtonConfirm, ButtonOk, ButtonClose, ButtonCancel)

/** The one tap to make on a screen while finishing a career. */
internal enum class CompletionStep { DONE, COMPLETE_CAREER, NEXT, CONFIRM, OK, CLOSE, WAIT }

/**
 * The buttons showing on a screen while finishing a career, with no dialog open.
 *
 * @property home Whether the Home menu bar is showing.
 * @property completeCareer Whether the end-of-career screen's Complete Career button is showing.
 * @property next Whether a Next button is showing.
 * @property confirm Whether a Confirm button is showing, as on the Sparks screen.
 * @property ok Whether an OK button is showing.
 * @property close Whether a Close button is showing.
 */
internal data class CompletionScreen(val home: Boolean, val completeCareer: Boolean, val next: Boolean, val confirm: Boolean, val ok: Boolean, val close: Boolean)

/**
 * Picks the tap that moves the finish forward. Only forward buttons are ever tapped, so Reroll Sparks, which costs TP, never is.
 *
 * @param screen The buttons showing.
 * @returns The step to take.
 */
internal fun completionStep(screen: CompletionScreen): CompletionStep =
    when {
        screen.home -> CompletionStep.DONE
        screen.completeCareer -> CompletionStep.COMPLETE_CAREER
        screen.next -> CompletionStep.NEXT
        screen.confirm -> CompletionStep.CONFIRM
        screen.ok -> CompletionStep.OK
        screen.close -> CompletionStep.CLOSE
        else -> CompletionStep.WAIT
    }

/**
 * Whether a dialog met while finishing a career is confirmed rather than closed.
 *
 * @param dialogName The dialog's name.
 * @returns True for Complete Career's Finish and the "Keep this set of Sparks?" confirmation.
 */
internal fun confirmsCompletionDialog(dialogName: String): Boolean = dialogName in CONFIRMED_DIALOGS

/**
 * Finishes a career from the end-of-career screen back to Home: Complete Career, Finish, the rank and Rating Record screens, the Sparks
 * confirmation, the trainee's details, the rewards, and the closing dialogs.
 *
 * @property game Reference to the bot's [Game] instance.
 */
class CareerCompletion(private val game: Game) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]CareerCompletion"
    }

    /**
     * Taps through the finish until Home shows.
     *
     * @throws CampaignBreakpointException When Home does not show in time, so the run stops with the reason.
     */
    fun run() {
        MessageLog.i(TAG, "[CAREER COMPLETION] Completing the career.")
        val deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_SECONDS * 1000L
        while (true) {
            // Taps through the animations between screens until there is a button to act on.
            val secondsLeft = (deadline - System.currentTimeMillis()) / 1000.0
            if (secondsLeft <= 0 || game.tapUntil(*WAYPOINTS, timeoutSec = secondsLeft, bHandleDialogs = false) == null) break
            val bitmap = game.imageUtils.getSourceBitmap()
            val dialog = DialogUtils.getDialogOrNull(game.imageUtils, bitmap)
            if (dialog != null) {
                if (confirmsCompletionDialog(dialog.name)) dialog.ok(game.imageUtils, tries = 3) else dialog.close(game.imageUtils, tries = 3)
                game.wait(1.0)
                continue
            }
            val screen =
                CompletionScreen(
                    home = ButtonMenuBarHome.check(game.imageUtils, sourceBitmap = bitmap),
                    completeCareer = ButtonCompleteCareer.check(game.imageUtils, sourceBitmap = bitmap),
                    next = ButtonNext.check(game.imageUtils, sourceBitmap = bitmap),
                    confirm = ButtonConfirm.check(game.imageUtils, sourceBitmap = bitmap),
                    ok = ButtonOk.check(game.imageUtils, sourceBitmap = bitmap),
                    close = ButtonClose.check(game.imageUtils, sourceBitmap = bitmap),
                )
            when (completionStep(screen)) {
                CompletionStep.DONE -> {
                    MessageLog.i(TAG, "[CAREER COMPLETION] The career is complete and the game is back on Home.")
                    return
                }

                CompletionStep.COMPLETE_CAREER -> ButtonCompleteCareer.click(game.imageUtils, sourceBitmap = bitmap)
                CompletionStep.NEXT -> ButtonNext.click(game.imageUtils, sourceBitmap = bitmap)
                CompletionStep.CONFIRM -> ButtonConfirm.click(game.imageUtils, sourceBitmap = bitmap)
                CompletionStep.OK -> ButtonOk.click(game.imageUtils, sourceBitmap = bitmap)
                CompletionStep.CLOSE -> ButtonClose.click(game.imageUtils, sourceBitmap = bitmap)
                CompletionStep.WAIT -> {}
            }
            // Gives the tapped button time to go away so the same one is not found and tapped again.
            game.wait(1.0)
        }
        throw CampaignBreakpointException("Could not get back to Home within ${COMPLETION_TIMEOUT_SECONDS}s of completing the career. Stopping bot...")
    }
}
