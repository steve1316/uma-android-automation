package com.steve1316.uma_android_automation.bot

/**
 * Pure helpers for the support-card recreation ("dating") schedule. The bot performs a recreation outing on user-pinned turns and holds the final
 * outing until a designated Pure Passion turn. These functions are side-effect free so they can be unit tested without the OCR / settings machinery.
 */
object DatingSchedule {
    /**
     * Whether [turn] is one the user pinned for a recreation outing - either a regular recreation turn or the single Pure Passion turn.
     *
     * @param turn The current 1-indexed career turn (1-72).
     * @param recreationTurns The set of turns pinned for regular recreation outings.
     * @param purePassionTurn The single turn pinned for the final outing / Pure Passion activation, or a non-positive value when unset.
     * @return True if the bot should consider doing a recreation outing on this turn.
     */
    fun isPinnedRecreationTurn(turn: Int, recreationTurns: Set<Int>, purePassionTurn: Int): Boolean {
        return turn in recreationTurns || turn == purePassionTurn
    }

    /**
     * Whether the final outing in the chain may start this turn. The final is held back only when a Pure Passion turn is configured and today is not it.
     * With no Pure Passion turn (e.g. Team Sirius, where Pure Passion is not timed for summer) every outing proceeds, so the chain completes on its pinned turns.
     *
     * @param enableDatingSchedule Whether the recreation schedule feature is on.
     * @param purePassionTurn The single turn pinned for the final outing / Pure Passion activation, or a non-positive value when unset.
     * @param currentTurn The current 1-indexed career turn (1-72).
     * @return True if the final outing is allowed to start now, so it is not held back.
     */
    fun allowFinalOuting(enableDatingSchedule: Boolean, purePassionTurn: Int, currentTurn: Int): Boolean {
        return !enableDatingSchedule || purePassionTurn <= 0 || currentTurn == purePassionTurn
    }

    /**
     * Whether the final recreation outing should be held back rather than started now. On a regular pinned turn the final outing is reserved so that
     * completing it - and triggering Pure Passion - happens on the Pure Passion turn instead.
     *
     * @param outingsStarted The number of outings already started this run.
     * @param totalOutings The total outings in the active card's recreation chain (Team Sirius 7, Heirs to the Throne 5).
     * @param allowFinalOuting True on the Pure Passion turn, where the final outing is allowed to start.
     * @return True if the only remaining outing is the final one and it is not yet allowed - i.e. back out without starting.
     */
    fun shouldHoldFinalOuting(outingsStarted: Int, totalOutings: Int, allowFinalOuting: Boolean): Boolean {
        return !allowFinalOuting && outingsStarted >= totalOutings - 1
    }

    /**
     * Whether the bot has fallen behind the schedule - fewer outings started than the number of regular pinned turns already due. Drives the optional catch-up
     * behavior, where a missed outing (a race pre-empted its pinned turn, or recreation was unavailable) is made up on the next available turn.
     *
     * @param currentTurn The current 1-indexed career turn (1-72).
     * @param recreationTurns The set of turns pinned for regular recreation outings (excludes the Pure Passion turn).
     * @param outingsStarted The number of outings already started this run, kept in sync with the in-game progress.
     * @return True if fewer outings have started than the number of pinned turns whose turn has passed or is today.
     */
    fun isBehindSchedule(currentTurn: Int, recreationTurns: Set<Int>, outingsStarted: Int): Boolean {
        return outingsStarted < recreationTurns.count { it <= currentTurn }
    }

    /**
     * Whether the schedule should be abandoned because the Pure Passion window has passed with the chain unfinished. Once abandoned, recreation drops back to the
     * regular opportunistic logic (finish the chain during rest / mood recovery) instead of holding or forcing outings. Only applies when a Pure Passion turn is set.
     *
     * @param purePassionTurn The single turn pinned for the final outing / Pure Passion activation, or a non-positive value when unset (e.g. Team Sirius).
     * @param currentTurn The current 1-indexed career turn (1-72).
     * @param chainComplete Whether the recreation chain has already been completed.
     * @return True once the current turn is past the Pure Passion turn and the chain is still incomplete.
     */
    fun isScheduleAbandoned(purePassionTurn: Int, currentTurn: Int, chainComplete: Boolean): Boolean {
        return purePassionTurn > 0 && currentTurn > purePassionTurn && !chainComplete
    }

    /**
     * Renders a short, honest progress phrase for the recreation chain. The authoritative "X/Y" only exists once the in-game partner dialog has been read,
     * so before then the phrase avoids a fabricated total - it reports just the outings done this run, or that none have started yet.
     *
     * @param outingsStarted The number of outings started this run, kept in sync with the in-game progress.
     * @param totalOutingsKnown The chain length as last read from the game's "X/Y" progress. Only meaningful when [progressRead] is true.
     * @param progressRead Whether the authoritative progress has been read from the game (via the Event Progress chevrons, the partner dialog OCR, or the chain-complete label).
     * @param completed Whether the recreation chain is already complete.
     * @return A phrase such as "complete", "3/7 completed", "1 done (chain length not yet read)", or "not started (no date done yet)".
     */
    fun formatRecreationProgress(outingsStarted: Int, totalOutingsKnown: Int, progressRead: Boolean, completed: Boolean): String {
        return when {
            completed -> "complete"
            progressRead -> "$outingsStarted/$totalOutingsKnown completed"
            outingsStarted > 0 -> "$outingsStarted done (chain length not yet read)"
            else -> "not started (no date done yet)"
        }
    }

    /**
     * The soonest pinned turn strictly after [currentTurn] - a regular recreation turn or the Pure Passion turn, whichever comes first - used to preview the
     * next scheduled outing in the per-turn log.
     *
     * @param currentTurn The current 1-indexed career turn (1-72).
     * @param recreationTurns The set of turns pinned for regular recreation outings.
     * @param purePassionTurn The single turn pinned for the final outing / Pure Passion activation, or a non-positive value when unset.
     * @return The next pinned turn after the current one, or null when none remain.
     */
    fun nextScheduledTurn(currentTurn: Int, recreationTurns: Set<Int>, purePassionTurn: Int): Int? {
        // A non-positive (unset) purePassionTurn can never beat the always-positive currentTurn, so the filter drops it without special-casing.
        return (recreationTurns + purePassionTurn).filter { it > currentTurn }.minOrNull()
    }
}
