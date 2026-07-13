package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Happy Meek duel training-bias helpers. Badge detection is validated on-device (the badge column mapping is checked against the 2026-07-01 training-screen
 * captures); the setting parse, score bias, and badge-X-to-facility math are pure and covered here.
 */
@DisplayName("Happy Meek duel training bias")
class UraFinaleDuelBiasTest {
    @Test
    @DisplayName("Parses the bias setting, defaulting unknown or empty values to moderate")
    fun parsesBiasLevel() {
        assertEquals(DuelBiasLevel.OFF, parseDuelBiasLevel("Off"))
        assertEquals(DuelBiasLevel.OFF, parseDuelBiasLevel(" off "))
        assertEquals(DuelBiasLevel.MODERATE, parseDuelBiasLevel("Moderate"))
        assertEquals(DuelBiasLevel.AGGRESSIVE, parseDuelBiasLevel("AGGRESSIVE"))
        assertEquals(DuelBiasLevel.MODERATE, parseDuelBiasLevel(""), "empty defaults to moderate")
        assertEquals(DuelBiasLevel.MODERATE, parseDuelBiasLevel("nonsense"), "unknown defaults to moderate")
    }

    @Test
    @DisplayName("Biases a duel facility with an acceptable failure chance by the level multiplier")
    fun biasesAcceptableDuel() {
        assertEquals(12.5, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = 5, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
        assertEquals(16.0, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = 5, level = DuelBiasLevel.AGGRESSIVE, maxFailureChance = 20), 1e-9)
        // Failure chance exactly at the acceptable ceiling still biases.
        assertEquals(12.5, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = 20, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
    }

    @Test
    @DisplayName("Leaves the score unchanged when there is no duel, bias is off, failure is risky, or the score is non-positive")
    fun leavesScoreUnchanged() {
        assertEquals(10.0, applyDuelTrainingBias(10.0, hasDuel = false, failureChance = 5, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
        assertEquals(10.0, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = 5, level = DuelBiasLevel.OFF, maxFailureChance = 20), 1e-9)
        assertEquals(10.0, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = 30, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
        assertEquals(10.0, applyDuelTrainingBias(10.0, hasDuel = true, failureChance = -1, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
        assertEquals(-5.0, applyDuelTrainingBias(-5.0, hasDuel = true, failureChance = 5, level = DuelBiasLevel.MODERATE, maxFailureChance = 20), 1e-9)
    }

    @Test
    @DisplayName("Maps the duel badge X to its facility column")
    fun mapsBadgeColumn() {
        // Validated badge centers from the 2026-07-01 training-screen captures at 1080px width.
        assertEquals(StatName.SPEED, duelFacilityForBadgeX(101, 1080))
        assertEquals(StatName.POWER, duelFacilityForBadgeX(483, 1080))
        assertEquals(StatName.GUTS, duelFacilityForBadgeX(674, 1080))
        // The remaining two columns.
        assertEquals(StatName.STAMINA, duelFacilityForBadgeX(324, 1080))
        assertEquals(StatName.WIT, duelFacilityForBadgeX(972, 1080))
    }
}
