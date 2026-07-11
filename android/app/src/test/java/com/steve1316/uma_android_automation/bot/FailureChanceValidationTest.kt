package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the failure-chance sanity clamp in `Training.crossValidateFailureChances`. It rejects an OCR read that sits far above the energy-based estimate (e.g. 99% at
 * 100% energy), which was stranding the bot in energy recovery on full-energy turns after a bogus read got cached.
 */
@DisplayName("Failure-chance cross-validation")
class FailureChanceValidationTest {
    @Test
    @DisplayName("Impossible high reads at full energy are clamped to the energy estimate (0%)")
    fun testClampsImplausibleReadsAtFullEnergy() {
        // The exact reported failure: at 100% energy the estimate is 0% for every stat, so a read of 99% (physicals) / 62% (Wit) is an OCR misread and must clamp to 0.
        val input =
            listOf(
                StatName.SPEED to 99,
                StatName.STAMINA to 99,
                StatName.POWER to 99,
                StatName.GUTS to 99,
                StatName.WIT to 62,
            )
        val corrected = Training.crossValidateFailureChances(input, currentEnergy = 100)
        for (stat in StatName.entries) {
            assertEquals(0, corrected[stat], "$stat must clamp to 0% at 100% energy (impossible 99%/62% read)")
        }
    }

    @Test
    @DisplayName("Genuinely high reads at low energy are left unchanged")
    fun testLeavesPlausibleHighReadsAtLowEnergy() {
        // At 20% energy the physical estimate is (50-20)*2 = 60% and Wit ~24%, so these monotonic reads are within tolerance and must NOT be clamped down.
        val input =
            listOf(
                StatName.SPEED to 60,
                StatName.STAMINA to 62,
                StatName.POWER to 64,
                StatName.GUTS to 66,
                StatName.WIT to 30,
            )
        val corrected = Training.crossValidateFailureChances(input, currentEnergy = 20)
        assertEquals(60, corrected[StatName.SPEED])
        assertEquals(62, corrected[StatName.STAMINA])
        assertEquals(64, corrected[StatName.POWER])
        assertEquals(66, corrected[StatName.GUTS])
        assertEquals(30, corrected[StatName.WIT])
    }
}
