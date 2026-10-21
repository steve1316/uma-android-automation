package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.ALL_G_APTITUDES
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.race
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A race the user picks by hand reaches the solver as a race lock. It must run even when the trainee's aptitudes are below the threshold.
 */
@DisplayName("Hand-picked race locks")
class PickedRaceLockTest {
    /** A G1 on turn 50 that an all-G trainee fails the aptitude filter for. */
    private val poorRace = race("Poor Fit G1", 50)

    /**
     * Builds a state with the all-G trainee and the poor-fit race.
     *
     * @param locks The locked decisions to apply.
     * @return The solver state.
     */
    private fun poorFitState(locks: Map<TurnNumber, Decision>): SolverState =
        state(currentTurn = 49, races = listOf(poorRace), aptitudes = ALL_G_APTITUDES, lockedDecisions = locks)

    @Test
    fun milpRunsALockedRaceBelowTheAptitudeThreshold() {
        val schedule = MilpSolver.solve(poorFitState(mapOf(50 to Decision.RaceDecision(poorRace.key))))
        assertEquals(Decision.RaceDecision(poorRace.key), schedule.decisions[50], "MILP must keep a locked race even when the aptitude filter would drop it")
    }

    @Test
    fun beamSearchRunsALockedRaceBelowTheAptitudeThreshold() {
        val schedule = Heuristic.search(poorFitState(mapOf(50 to Decision.RaceDecision(poorRace.key))))
        assertEquals(Decision.RaceDecision(poorRace.key), schedule.decisions[50], "Beam search must keep a locked race even when the aptitude filter would drop it")
    }

    @Test
    fun facadeRunsALockedRaceBelowTheAptitudeThreshold() {
        val schedule = SmartRaceSolver.solve(poorFitState(mapOf(50 to Decision.RaceDecision(poorRace.key))))
        assertEquals(Decision.RaceDecision(poorRace.key), schedule.decisions[50], "The solver facade must keep a locked race even when the aptitude filter would drop it")
    }

    @Test
    fun milpStillFiltersTheSameRaceWithoutALock() {
        val schedule = MilpSolver.solve(poorFitState(emptyMap()))
        assertFalse(schedule.decisions[50] is Decision.RaceDecision, "Without a lock the aptitude filter must still drop a poor-fit race")
    }
}
