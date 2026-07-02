package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.Mood
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    @DisplayName("Mood recovery floor parses the setting and falls back to GOOD on anything unexpected")
    fun testParseMoodRecoveryFloor() {
        assertEquals(Mood.GOOD, parseMoodRecoveryFloor("GOOD"), "GOOD keeps the base recover-below-Good behavior")
        assertEquals(Mood.NORMAL, parseMoodRecoveryFloor("NORMAL"), "NORMAL recovers only below Normal, skipping Normal-mood recovery")
        assertEquals(Mood.BAD, parseMoodRecoveryFloor("BAD"), "BAD recovers only when Awful, the most training-aggressive floor")
        assertEquals(Mood.GOOD, parseMoodRecoveryFloor("nonsense"), "An unknown value falls back to the base Good floor")
    }

    @Test
    @DisplayName("Pre-summer prep rests on low energy, recovers mood if not Great, else trains Wit")
    fun testResolvePreSummerAction() {
        assertEquals(MainScreenAction.REST, resolvePreSummerAction(energy = 60, mood = Mood.GREAT, firstTrainingCheck = false), "Energy below 70% must rest to prepare for summer")
        assertEquals(MainScreenAction.RECOVER_MOOD, resolvePreSummerAction(energy = 80, mood = Mood.GOOD, firstTrainingCheck = false), "Enough energy but mood below Great must recover mood")
        assertEquals(MainScreenAction.TRAIN, resolvePreSummerAction(energy = 80, mood = Mood.GREAT, firstTrainingCheck = false), "Enough energy and Great mood trains Wit")
        assertEquals(MainScreenAction.TRAIN, resolvePreSummerAction(energy = 80, mood = Mood.GOOD, firstTrainingCheck = true), "The first training check defers mood recovery, so it trains instead")
    }
}
