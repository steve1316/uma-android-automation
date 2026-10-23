package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.bot.Training.Companion.SKIP_HIGH_FAILURE_CHANCE
import com.steve1316.uma_android_automation.bot.Training.Companion.SKIP_POOR_VALUE_FOR_RISK
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the low-energy training policy: the minimum-energy rest floor in Campaign, plus the Wit failure-ceiling exemption and the lone low-value training rule in Training.
 * All are pure, so they are verified here without a live Campaign or the Android-coupled analysis pipeline. The shipped defaults are the rest floor off (0), the Wit exemption off,
 * its ceiling 35%, its minimum gain 25, and the lone-training rule on with a minimum gain of 25.
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

    /** Builds a surviving Wit training with only the fields the lone-training rule reads. */
    private fun option(
        statGains: Map<StatName, Int> = mapOf(StatName.SPEED to 4, StatName.WIT to 15),
        numBars: Int = 0,
        numMaxedBars: Int = 0,
        numSkillHints: Int = 0,
        numRainbow: Int = 0,
        readyToBurst: Int = 0,
    ) = Training.TrainingOption(
        name = StatName.WIT,
        statGains = statGains,
        failureChance = 5,
        relationshipBars =
            ArrayList(List(numBars) { CustomImageUtils.BarFillResult(StatName.WIT, 20.0, 1, "blue") } + List(numMaxedBars) { CustomImageUtils.BarFillResult(StatName.WIT, 100.0, 5, "orange") }),
        numRainbow = numRainbow,
        numSkillHints = numSkillHints,
        extras = mapOf("spiritGaugesReadyToBurst" to readyToBurst),
    )

    /** The four other trainings from the turn 58 log, all skipped for failure chance. */
    private val tiredSkips = listOf(SKIP_HIGH_FAILURE_CHANCE, SKIP_HIGH_FAILURE_CHANCE, SKIP_HIGH_FAILURE_CHANCE, SKIP_POOR_VALUE_FOR_RISK)

    /** Calls the lone-training rule as `processAnalysisResults` does, at the turn 58 energy of 29%, with the feature on and the shipped 25 gain minimum by default. */
    private fun restsOverLone(
        survivor: Training.TrainingOption = option(),
        otherSkipReasons: List<String?> = tiredSkips,
        energy: Int = 29,
        isFinale: Boolean = false,
        enabled: Boolean = true,
        minStatGain: Int = 25,
    ) = Training.restsOverLoneTraining(survivor, otherSkipReasons, energy, isFinale, enabled, minStatGain)

    @Test
    @DisplayName("Turn 58: a lone bare Wit (SPD 4 / WIT 15) left after the rest were too risky is dropped so the bot recovers energy")
    fun testLoneLowValueTrainingDropped() {
        assertTrue(restsOverLone(), "Every other training was skipped for risk and Wit has no supports and only 15 Wit, so the pal date or a rest is worth more")
    }

    @Test
    @DisplayName("The lone training is kept once its main stat gain reaches the minimum")
    fun testLoneTrainingKeptAtMinGain() {
        assertFalse(restsOverLone(option(statGains = mapOf(StatName.WIT to 25))), "A Wit gain exactly at the 25 minimum is worth the turn")
        assertTrue(restsOverLone(option(statGains = mapOf(StatName.WIT to 24))), "A gain just under the minimum is still dropped")
    }

    @Test
    @DisplayName("The lone training is kept when it builds a bond, holds a hint, is a rainbow, or has a burst ready")
    fun testLoneTrainingKeptWithExtraValue() {
        assertFalse(restsOverLone(option(numBars = 1)), "One support bar means the turn also builds a bond")
        assertFalse(restsOverLone(option(numSkillHints = 1)), "A skill hint is value the stat gain does not show")
        assertFalse(restsOverLone(option(numRainbow = 1)), "A rainbow training is never thrown away")
        assertFalse(restsOverLone(option(readyToBurst = 1)), "A ready Spirit Explosion burst is never thrown away")
    }

    @Test
    @DisplayName("Turn 42: a lone Wit (SPD 4 / WIT 14) whose only supports are already maxed is dropped")
    fun testLoneTrainingWithMaxedBarsDropped() {
        assertTrue(restsOverLone(option(statGains = mapOf(StatName.SPEED to 4, StatName.WIT to 14), numMaxedBars = 2)), "Maxed bonds build nothing, so two orange bars add no value")
        assertFalse(restsOverLone(option(numBars = 1, numMaxedBars = 2)), "One bond still growing next to the maxed ones keeps the turn")
    }

    @Test
    @DisplayName("The rule only fires below 50% energy")
    fun testLoneTrainingKeptAtMidEnergy() {
        assertTrue(restsOverLone(energy = 49), "Just under the cap the trainee is tired enough that a rest or the pal date is worth more")
        assertFalse(restsOverLone(energy = 50), "At half energy a rest recovers too little to beat even a small gain")
    }

    @Test
    @DisplayName("The rule only fires when every other training was skipped for risk")
    fun testNonRiskSkipsKept() {
        assertFalse(restsOverLone(otherSkipReasons = emptyList()), "With every other training blacklisted or restricted, the lone one is all that is allowed, not a sign of low energy")
        assertFalse(restsOverLone(otherSkipReasons = listOf(SKIP_HIGH_FAILURE_CHANCE, "low irregular gain")), "One skip was not about risk, so the trainee is not shown to be tired")
        assertTrue(restsOverLone(otherSkipReasons = listOf("$SKIP_HIGH_FAILURE_CHANCE (wit)", "$SKIP_HIGH_FAILURE_CHANCE (risky)")), "The raised-ceiling variants still count as risk skips")
    }

    @Test
    @DisplayName("The Finale and the setting being off keep the lone training")
    fun testFinaleAndDisabledKept() {
        assertFalse(restsOverLone(isFinale = true), "The Finale has its own forced-Wit handling, so this rule stays out of it")
        assertFalse(restsOverLone(enabled = false), "Turning the setting off restores the old behavior")
    }
}
