package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.race
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.state
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression cover for the model-wide infeasibility caused by an unreachable dependency epithet. An `epithetAll` / `epithetAnyOf` matcher whose
 * prerequisite epithets are not in the active pool (e.g. filtered out by the scenario or character) used to emit a `y <= -1` bound, which is
 * infeasible for a binary variable and marked the ENTIRE MILP infeasible. Every preview then silently fell back to the greedy heuristic, so the
 * exact solver - and anything that depends on it, like the target-epithet bonus - never actually ran on real device configs.
 */
@DisplayName("Unreachable epithet infeasibility")
class UnreachableEpithetInfeasibilityTest {
    @Test
    fun anUnreachableDependencyEpithetDoesNotBreakTheWholeModel() {
        val stakes = race("Winnable Stakes", 60)
        // A normal, achievable epithet.
        val winnable = Epithet(name = "Winnable", bullets = listOf("Win the Winnable Stakes"), matchers = listOf(EpithetMatcher.WinRace("Winnable Stakes")))
        // A dependency epithet whose prerequisite ("Ghost") is NOT in the pool - it can never complete.
        val dependent = Epithet(name = "Dependent", bullets = listOf("Complete Ghost"), matchers = listOf(EpithetMatcher.EpithetAll(names = listOf("Ghost"))))

        // Call MilpSolver directly, not SmartRaceSolver.solve: the latter silently falls back to the heuristic on an infeasible model, which
        // still returns a valid schedule and would mask the bug (exactly as it did on device). The MILP itself must stay feasible.
        val schedule = MilpSolver.solve(state(currentTurn = 58, races = listOf(stakes), epithets = listOf(winnable, dependent)))

        assertTrue(schedule.decisions.isNotEmpty(), "the MILP must stay feasible and return a real schedule, not an empty one from an infeasible bound")
        assertTrue(stakes.key in schedule.raceTurns().map { it.second }, "the achievable epithet's race should still be scheduled")
        assertTrue("Winnable" in schedule.projectedEpithets, "the achievable epithet should complete")
        assertFalse("Dependent" in schedule.projectedEpithets, "the unreachable epithet must not complete")
    }
}
