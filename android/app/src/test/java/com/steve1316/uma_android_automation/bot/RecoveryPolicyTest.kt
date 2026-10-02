package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Feeds the same outcome to the policy several times.
 *
 * @param policy The policy under test.
 * @param outcome The outcome of each tick.
 * @param count How many ticks to feed.
 * @param bRecovering Whether the bot is recovering after a pause.
 * @return The action for each tick, in order.
 */
private fun ticks(policy: RecoveryPolicy, outcome: TickOutcome, count: Int, bRecovering: Boolean): List<RecoveryAction> = List(count) { policy.onTick(outcome, bRecovering) }

/** Unit tests for when the bot backs out to the main screen or stops while looking for a known screen, and which dialogs recovery closes. */
@DisplayName("Recovery policy")
class RecoveryPolicyTest {
    @Test
    fun knownScreensNeverTripTheLimit() {
        val policy = RecoveryPolicy()
        assertTrue(ticks(policy, TickOutcome.KNOWN, 200, bRecovering = true).all { it == RecoveryAction.CONTINUE })
    }

    @Test
    fun recoveringBacksOutAfterFifteenBlindTicks() {
        val policy = RecoveryPolicy()
        assertTrue(ticks(policy, TickOutcome.BLIND, 14, bRecovering = true).all { it == RecoveryAction.CONTINUE })
        assertEquals(RecoveryAction.RETURN_TO_MAIN, policy.onTick(TickOutcome.BLIND, bRecovering = true))
    }

    @Test
    fun aNormalRunWaitsSixtyTicksForLongCutscenes() {
        val policy = RecoveryPolicy()
        assertTrue(ticks(policy, TickOutcome.BLIND, 59, bRecovering = false).all { it == RecoveryAction.CONTINUE })
        assertEquals(RecoveryAction.RETURN_TO_MAIN, policy.onTick(TickOutcome.BLIND, bRecovering = false))
    }

    @Test
    fun miscTicksCountTowardTheLimit() {
        val policy = RecoveryPolicy()
        assertTrue(ticks(policy, TickOutcome.MISC, 14, bRecovering = true).all { it == RecoveryAction.CONTINUE })
        assertEquals(RecoveryAction.RETURN_TO_MAIN, policy.onTick(TickOutcome.MISC, bRecovering = true))
    }

    @Test
    fun stillLostAfterBackingOutStops() {
        val policy = RecoveryPolicy()
        assertEquals(RecoveryAction.RETURN_TO_MAIN, ticks(policy, TickOutcome.BLIND, 15, bRecovering = true).last())
        assertTrue(ticks(policy, TickOutcome.BLIND, 14, bRecovering = true).all { it == RecoveryAction.CONTINUE })
        assertEquals(RecoveryAction.STOP, policy.onTick(TickOutcome.BLIND, bRecovering = true))
    }

    @Test
    fun aKnownScreenAfterBackingOutStartsOver() {
        val policy = RecoveryPolicy()
        ticks(policy, TickOutcome.BLIND, 15, bRecovering = true)
        assertEquals(RecoveryAction.CONTINUE, policy.onTick(TickOutcome.KNOWN, bRecovering = false))
        assertEquals(RecoveryAction.RETURN_TO_MAIN, ticks(policy, TickOutcome.BLIND, 15, bRecovering = true).last())
    }

    @Test
    fun aHandledDialogCountsAsProgress() {
        val policy = RecoveryPolicy()
        val actions = ticks(policy, TickOutcome.BLIND, 14, bRecovering = true) + policy.onTick(TickOutcome.DIALOG, bRecovering = true) + ticks(policy, TickOutcome.BLIND, 14, bRecovering = true)
        assertTrue(actions.all { it == RecoveryAction.CONTINUE })
    }

    @Test
    fun resetForgetsEverything() {
        val policy = RecoveryPolicy()
        ticks(policy, TickOutcome.BLIND, 15, bRecovering = true)
        ticks(policy, TickOutcome.BLIND, 3, bRecovering = true)
        policy.reset()
        assertEquals(0, policy.stuckTicks)
        assertEquals(RecoveryAction.RETURN_TO_MAIN, ticks(policy, TickOutcome.BLIND, 15, bRecovering = true).last())
    }

    @Test
    fun recoveryClosesOnlyCommitDialogs() {
        val commitDialogs =
            listOf("rest", "rest_and_recreation", "recreation", "infirmary", "race_details", "skill_list_confirmation", "consecutive_race_warning", "confirm_use", "shop", "exchange_complete")
        commitDialogs.forEach { assertTrue(closesDuringRecovery(it, bRecovering = true), it) }
        commitDialogs.forEach { assertFalse(closesDuringRecovery(it, bRecovering = false), it) }
        listOf("trophy_won", "connection_error", "race_playback", "skills_learned", "strategy").forEach { assertFalse(closesDuringRecovery(it, bRecovering = true), it) }
    }
}
