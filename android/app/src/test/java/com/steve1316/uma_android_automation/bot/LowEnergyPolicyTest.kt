package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the low-energy training policy: the minimum-energy rest floor in Campaign and the Wit failure-ceiling exemption in Training. Both are pure, so they are verified here
 * without a live Campaign or the Android-coupled analysis pipeline. The shipped defaults are the rest floor off (0), the Wit exemption off, its ceiling 35%, and its minimum gain 25.
 */
@DisplayName("Low-energy training policy")
class LowEnergyPolicyTest {
    /** Calls the rest floor with it disabled by default, so each test only states the values it actually cares about. */
    private fun restForLowEnergy(energy: Int = 50, minEnergyToTrain: Int = 0, isSummer: Boolean = false, isFinals: Boolean = false) =
        shouldRestForLowEnergy(energy = energy, minEnergyToTrain = minEnergyToTrain, isSummer = isSummer, isFinals = isFinals)

    /** Calls the Wit exemption with the feature on and the shipped 25 gain minimum by default, so gain-path tests only state what they vary. */
    private fun witExempt(stat: StatName = StatName.WIT, mainStatGain: Int = 30, enableWitOverRest: Boolean = true, witOverRestMinStatGain: Int = 25) =
        Training.witExemptFromFailureChance(stat, mainStatGain, enableWitOverRest, witOverRestMinStatGain)

    /** Resolves the failure ceiling a training must clear, wiring the two production helpers together exactly as `processAnalysisResults` does. */
    private fun ceilingFor(
        stat: StatName,
        mainStatGain: Int,
        maximumFailureChance: Int = 20,
        enableRiskyTraining: Boolean = false,
        riskyTrainingMinStatGain: Int = 50,
        riskyTrainingMaxFailureChance: Int = 30,
        enableWitOverRest: Boolean = false,
        witOverRestMinStatGain: Int = 25,
        witOverRestMaxFailureChance: Int = 35,
    ): Int {
        val riskyExempt = Training.riskyExemptFromFailureChance(mainStatGain, enableRiskyTraining, riskyTrainingMinStatGain)
        val witExempt = Training.witExemptFromFailureChance(stat, mainStatGain, enableWitOverRest, witOverRestMinStatGain)
        return Training.baseFailureChanceFor(maximumFailureChance, riskyExempt, riskyTrainingMaxFailureChance, witExempt, witOverRestMaxFailureChance)
    }

    @Test
    @DisplayName("The rest floor is disabled by default, so nothing changes on upgrade")
    fun testFloorDisabledByDefault() {
        assertFalse(restForLowEnergy(energy = 5), "With the floor at 0, even 5% energy keeps training as long as a training survived the failure-chance gate")
    }

    @Test
    @DisplayName("The rest floor fires below the threshold and never at or above it")
    fun testFloorBoundary() {
        assertTrue(restForLowEnergy(energy = 29, minEnergyToTrain = 30), "Energy below the floor rests even though a training was viable")
        assertFalse(restForLowEnergy(energy = 30, minEnergyToTrain = 30), "Energy exactly at the floor is allowed to train")
        assertFalse(restForLowEnergy(energy = 31, minEnergyToTrain = 30), "Energy above the floor trains")
    }

    @Test
    @DisplayName("Summer camp and the finale are exempt from the rest floor")
    fun testFloorExemptions() {
        assertTrue(restForLowEnergy(energy = 10, minEnergyToTrain = 40), "A normal turn below the floor rests")
        assertFalse(restForLowEnergy(energy = 10, minEnergyToTrain = 40, isSummer = true), "Summer turns are worth more than the energy they cost, so the floor never rests through one")
        assertFalse(restForLowEnergy(energy = 10, minEnergyToTrain = 40, isFinals = true), "Energy does not affect race performance, so resting during the finale throws the turn away")
    }

    @Test
    @DisplayName("The Wit exemption opts in only Wit, and only when its gain clears the minimum")
    fun testWitExemptionQualifies() {
        assertTrue(witExempt(mainStatGain = 25), "Gain exactly at the 25 minimum qualifies")
        assertFalse(witExempt(mainStatGain = 24), "A gain just under the minimum does not qualify, so a lower-value Wit turn is not worth the risk")
        assertFalse(witExempt(stat = StatName.SPEED, mainStatGain = 99), "The exemption is Wit-only; Speed never qualifies")
        assertFalse(witExempt(mainStatGain = 99, enableWitOverRest = false), "Off by default, so nothing qualifies")
    }

    @Test
    @DisplayName("The Wit exemption raises only Wit's failure ceiling")
    fun testWitCeiling() {
        assertEquals(20, ceilingFor(StatName.WIT, mainStatGain = 30), "With the exemption off, Wit is held to the same ceiling as everything else")
        assertEquals(35, ceilingFor(StatName.WIT, mainStatGain = 30, enableWitOverRest = true), "A high-gain Wit turn gets its own, more lenient ceiling")
        assertEquals(20, ceilingFor(StatName.WIT, mainStatGain = 24, enableWitOverRest = true), "A Wit turn under the 25 minimum keeps the shared ceiling")
        assertEquals(20, ceilingFor(StatName.SPEED, mainStatGain = 30, enableWitOverRest = true), "The exemption is Wit-only; Speed keeps the shared ceiling")
    }

    @Test
    @DisplayName("The Wit exemption never lowers a ceiling, and risky training still outranks it")
    fun testWitCeilingPrecedence() {
        assertEquals(
            50,
            ceilingFor(StatName.WIT, mainStatGain = 30, maximumFailureChance = 50, enableWitOverRest = true),
            "A user whose shared ceiling already exceeds the Wit ceiling must not have it lowered by turning the exemption on",
        )
        assertEquals(
            30,
            ceilingFor(StatName.WIT, mainStatGain = 55, enableRiskyTraining = true, enableWitOverRest = true),
            "Risky training is checked first, so its ceiling wins when a Wit turn qualifies for both",
        )
    }

    @Test
    @DisplayName("Issue #394: a high-value Wit turn survives the shared failure ceiling once the user tunes the threshold to reach it")
    fun testIssue394HighValueWit() {
        // Wit fails far less often at low energy than the other stats, so at low energy the map often empties with only Wit close to viable. The exemption lets a Wit turn the user
        // considers high-value survive the gate instead of forcing a rest. A user targeting, say, a 20-gain Wit turn at 30% failure sets the minimum to 20 and the ceiling to 35.
        val witFailureChance = 30
        val witGain = 20

        assertTrue(witFailureChance > ceilingFor(StatName.WIT, mainStatGain = witGain), "With the exemption off, Wit's 30% blows past the shared 20% ceiling and is skipped")
        assertTrue(
            witFailureChance <= ceilingFor(StatName.WIT, mainStatGain = witGain, enableWitOverRest = true, witOverRestMinStatGain = 20),
            "With the exemption on and the minimum tuned to 20, the 20-gain Wit turn fits under its own 35% ceiling and is trained instead of rested",
        )
    }
}
