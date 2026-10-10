package com.steve1316.uma_android_automation.bot

/** Dialogs that commit a turn's action. While recovering after a pause they are closed instead of confirmed, so the bot decides again. */
internal val RECOVERY_CLOSED_DIALOGS: Set<String> =
    setOf(
        "rest",
        "rest_and_recreation",
        "recreation",
        "infirmary",
        "race_details",
        "skill_list_confirmation",
        "consecutive_race_warning",
        "confirm_use",
        "shop",
        "exchange_complete",
    )

/**
 * Consecutive ticks without a known screen allowed before backing out to the main screen. A Grand Live post-concert stretch was measured at
 * 41 such ticks, and the bot gets a second full limit after backing out before it stops.
 */
internal const val RECOVERING_STUCK_LIMIT = 40

/** What one tick of `Campaign.process()` found. */
enum class TickOutcome {
    /** A screen the campaign has a handler for, such as the main screen or a training event. */
    KNOWN,

    /** A dialog that the dialog handler dealt with. */
    DIALOG,

    /** Only a misc check matched, such as a Next, Back, or Skip button. */
    MISC,

    /** Nothing matched, so the campaign taps blindly to move on. */
    BLIND,
}

/** What the campaign does after a tick. */
enum class RecoveryAction {
    /** Carry on as usual. */
    CONTINUE,

    /** Back out to the main screen once. */
    RETURN_TO_MAIN,

    /** Stop the run with a clear reason. */
    STOP,
}

/**
 * Decides when the bot is lost. It counts consecutive ticks that found no known screen. At the limit the bot backs out to the main screen
 * once. If it is still lost at the limit again, it stops. A known screen or a handled dialog starts the count over.
 *
 * @param limit The tick limit.
 */
class RecoveryPolicy(private val limit: Int = RECOVERING_STUCK_LIMIT) {
    /** Consecutive ticks without a known screen since the last progress or back-out. */
    var stuckTicks: Int = 0
        private set

    /** True once the bot has backed out to the main screen without reaching a known screen since. */
    private var bTriedReturnToMain: Boolean = false

    /**
     * Records one tick and says what to do next.
     *
     * @param outcome What the tick found.
     * @return What the campaign should do now.
     */
    fun onTick(outcome: TickOutcome): RecoveryAction {
        if (outcome == TickOutcome.KNOWN || outcome == TickOutcome.DIALOG) {
            reset()
            return RecoveryAction.CONTINUE
        }
        stuckTicks++
        if (stuckTicks < limit) return RecoveryAction.CONTINUE
        stuckTicks = 0
        if (bTriedReturnToMain) return RecoveryAction.STOP
        bTriedReturnToMain = true
        return RecoveryAction.RETURN_TO_MAIN
    }

    /**
     * Forgets the count and the back-out, as after a resume.
     */
    fun reset() {
        stuckTicks = 0
        bTriedReturnToMain = false
    }
}

/**
 * Whether the dialog handler closes this dialog instead of handling it as usual.
 *
 * @param dialogName The dialog's name.
 * @param bRecovering Whether the bot is recovering after a pause.
 * @return True for a commit dialog while recovering.
 */
internal fun closesDuringRecovery(dialogName: String, bRecovering: Boolean): Boolean = bRecovering && dialogName in RECOVERY_CLOSED_DIALOGS
