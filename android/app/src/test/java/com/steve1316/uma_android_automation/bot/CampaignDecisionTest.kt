package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for pure Campaign decision helpers - the turn-by-turn choices extracted out of the Android-coupled Campaign class so they can be verified directly.
 */
@DisplayName("Campaign decision helpers")
class CampaignDecisionTest {
    @Test
    @DisplayName("Mood recovery is skipped once the finale (day 73+) is underway")
    fun testFinaleMoodRecoverySkip() {
        assertFalse(shouldSkipMoodRecoveryForFinale(72), "Day 72 is pre-finale; mood recovery is still allowed")
        assertTrue(shouldSkipMoodRecoveryForFinale(73), "Day 73 is the first finale turn; mood recovery wastes a turn")
        assertTrue(shouldSkipMoodRecoveryForFinale(75), "Day 75 is the final turn")
    }
}
