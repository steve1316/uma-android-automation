package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.DateYear
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for pure Racing decision helpers - the extra-race gates extracted out of the Android-coupled Racing class so they can be verified directly.
 */
@DisplayName("Racing decision helpers")
class RacingDecisionTest {
    @Test
    @DisplayName("Extra-racing energy gate blocks below the floor, allows at or above, and is disabled at 0")
    fun testHasEnoughEnergyForExtraRacing() {
        assertFalse(hasEnoughEnergyForExtraRacing(energy = 25, minEnergy = 30), "25% energy is below the 30% floor, so the fan-farming race should be skipped")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 30, minEnergy = 30), "Exactly the floor is enough energy to race")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 45, minEnergy = 30), "Above the floor is enough energy to race")
        assertTrue(hasEnoughEnergyForExtraRacing(energy = 0, minEnergy = 0), "A floor of 0 disables the check entirely")
    }

    @Test
    @DisplayName("Any mandatory career goal always uses standard racing across every settings configuration")
    fun testMandatoryRequirementAlwaysUsesStandardRacing() {
        // Regression guard for the Senior-year trophy failure: a fan, trophy, or goal-pts requirement must never
        // route to the Smart Race Solver (which optimizes fans/epithets and drops the requirement). Exhaustively
        // sweep year x solver-enabled x force-racing for each requirement type.
        val requirements = listOf("fan", "trophy", "goalPts")
        for (requirement in requirements) {
            for (year in DateYear.entries) {
                for (solverEnabled in listOf(true, false)) {
                    for (forceRacing in listOf(true, false)) {
                        val useSmart =
                            shouldUseSmartRacing(
                                hasFanRequirement = requirement == "fan",
                                hasTrophyRequirement = requirement == "trophy",
                                hasInsufficientGoalRacePtsRequirement = requirement == "goalPts",
                                year = year,
                                enableSmartRaceSolver = solverEnabled,
                                enableForceRacing = forceRacing,
                            )
                        assertFalse(useSmart, "A $requirement requirement in $year (solver=$solverEnabled, force=$forceRacing) must use standard racing, not the solver")
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Without a requirement, smart racing follows the solver, year, and force-racing settings")
    fun testNoRequirementRoutingFollowsSettings() {
        fun run(year: DateYear = DateYear.SENIOR, solverEnabled: Boolean = true, forceRacing: Boolean = false) =
            shouldUseSmartRacing(
                hasFanRequirement = false,
                hasTrophyRequirement = false,
                hasInsufficientGoalRacePtsRequirement = false,
                year = year,
                enableSmartRaceSolver = solverEnabled,
                enableForceRacing = forceRacing,
            )

        assertTrue(run(year = DateYear.CLASSIC), "Solver on, Year 2+, force off should use smart racing")
        assertTrue(run(year = DateYear.SENIOR), "Solver on, Year 3, force off should use smart racing")
        assertFalse(run(forceRacing = true), "Force racing off-loads to standard racing even with the solver on")
        assertFalse(run(solverEnabled = false), "A disabled solver never uses smart racing")
        assertFalse(run(year = DateYear.JUNIOR), "Junior year never uses smart racing")
    }

    @Test
    @DisplayName("A G1-only trophy skips racing only when the races DB holds no G1; other requirements always force racing")
    fun testRequirementForcesRacing() {
        fun forces(
            fan: Boolean = false,
            trophy: Boolean = false,
            preOp: Boolean = false,
            g3: Boolean = false,
            goalPts: Boolean = false,
            hasG1: Boolean,
        ) = requirementForcesRacing(
            hasFanRequirement = fan,
            hasTrophyRequirement = trophy,
            hasPreOpOrAboveRequirement = preOp,
            hasG3OrAboveRequirement = g3,
            hasInsufficientGoalRacePtsRequirement = goalPts,
            hasG1ThisTurn = hasG1,
        )

        // The one skip case: a G1-only trophy on a turn the races DB holds no G1 for - navigating would just cancel.
        assertFalse(forces(trophy = true, hasG1 = false), "A G1-only trophy with no G1 in the races DB this turn must skip the race-screen round-trip")
        assertTrue(forces(trophy = true, hasG1 = true), "A G1-only trophy with a G1 in the races DB this turn must force racing")

        // Trophies that accept other grades always force racing, even when the DB holds no G1.
        assertTrue(forces(trophy = true, preOp = true, hasG1 = false), "A Pre-OP-or-above trophy accepts other grades, so it must force racing even with no G1")
        assertTrue(forces(trophy = true, g3 = true, hasG1 = false), "A G3-or-above trophy accepts other grades, so it must force racing even with no G1")

        // Fan and goal-point requirements are met by any race, so they always force racing regardless of G1 availability.
        assertTrue(forces(fan = true, hasG1 = false), "A fan requirement is met by any race, so it must force racing even with no G1")
        assertTrue(forces(goalPts = true, hasG1 = false), "A goal-race-points requirement is met by any race, so it must force racing even with no G1")

        // No requirement active: this gate never forces racing (the caller uses the normal eligibility path instead).
        assertFalse(forces(hasG1 = true), "With no requirement active, this gate must not force racing")
    }
}
