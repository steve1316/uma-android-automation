package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.race
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.state
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression cover for the model-wide infeasibility caused by a mandatory race on a summer turn. A career-objective race can land on a Classic/Senior
 * summer training-camp turn (e.g. Elm Stakes on turn 63). It is immovable, so `wireManualLocks` forces its turn to run - but with Summer racing
 * disabled, `wireSummerHardBlock` used to force that same turn off, a direct contradiction that ojAlgo's presolve flags INFEASIBLE. The whole MILP
 * was then discarded and every such preview silently fell back to the greedy heuristic, so the exact solver never ran for those configs.
 */
@DisplayName("Summer mandatory race feasibility")
class SummerMandatoryRaceFeasibilityTest {
    @Test
    fun aMandatoryRaceOnASummerTurnKeepsTheModelFeasible() {
        // Turn 63 is a Senior summer turn. Lock a mandatory race there and keep Summer racing disabled (the default).
        val summerRace = race("Elm Stakes", 63, terrain = com.steve1316.uma_android_automation.types.TrackSurface.DIRT)
        val st =
            state(
                currentTurn = 58,
                races = listOf(summerRace),
                lockedDecisions = mapOf(63 to Decision.RaceDecision(summerRace.key)),
                weights = Weights(allowSummerRacing = false),
            )

        val schedule = MilpSolver.solve(st)

        assertTrue(schedule.decisions.isNotEmpty(), "the MILP must stay feasible: a mandatory summer race overrides the summer block, it does not contradict it")
        assertTrue(schedule.decisions[63] is Decision.RaceDecision, "the mandatory race on the summer turn must still be scheduled")
    }
}
