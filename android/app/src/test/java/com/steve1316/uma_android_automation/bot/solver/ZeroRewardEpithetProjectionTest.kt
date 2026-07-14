package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.race
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.state
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression cover for epithets that grant no reward. 200 of the 236 entries in `epithets.json` list no reward bullet at all (every reward-bearing
 * epithet happens to be Trackblazer-only), so `ScoringFunctions.epithetContribution` values them at 0.0. The MILP backend used to read completions
 * straight off its `y` variables, which only leave 0 when the objective pays for them - so on a scenario like Unity Cup the solver reported zero
 * completed epithets even when it had scheduled and won every race they required.
 *
 * The shared `TestFixtures.epithet` helper always attaches a reward bullet, which is why nothing caught this. These build the epithets by hand.
 */
@DisplayName("Zero-reward epithet projection")
class ZeroRewardEpithetProjectionTest {
    /** Builds an epithet with no reward bullet, mirroring the shape of a real entry like "Tenno Sweep". */
    private fun rewardlessEpithet(name: String, matchers: List<EpithetMatcher>, conditions: List<String>): Epithet = Epithet(name = name, bullets = conditions, matchers = matchers)

    @Test
    fun rewardlessEpithetIsProjectedWhenItsRacesAreWon() {
        // "Tenno Sweep": win both Tenno Sho races. No reward bullet, so its objective weight is 0.
        //
        // Deliberately NOT forced: `wireForcedEpithets` pins y = 1 outright, which masks the bug. The solver schedules both G1s on their own
        // race value, completing the epithet incidentally - exactly the reported scenario, where the races were scheduled but the count read 0.
        val spring = race("Tenno Sho (Spring)", 60)
        val autumn = race("Tenno Sho (Autumn)", 68)
        val tennoSweep =
            rewardlessEpithet(
                "Tenno Sweep",
                listOf(EpithetMatcher.WinRace("Tenno Sho (Spring)"), EpithetMatcher.WinRace("Tenno Sho (Autumn)")),
                listOf("Win the Tenno Sho (Spring)", "Win the Tenno Sho (Autumn)"),
            )

        val st = state(currentTurn = 58, races = listOf(spring, autumn), epithets = listOf(tennoSweep))

        val schedule = SmartRaceSolver.solve(st)
        val raced = schedule.raceTurns().map { it.second }.toSet()
        assertTrue(raced.containsAll(listOf(spring.key, autumn.key)), "both Tenno Sho races should be scheduled on race value alone, got $raced")
        assertTrue("Tenno Sweep" in schedule.projectedEpithets, "a rewardless epithet whose races are all won must still be reported as projected")
    }

    @Test
    fun rewardlessEpithetIsNotProjectedWhenItsRacesAreNotWon() {
        // Only one of the two required races exists, so the epithet cannot complete and must not be reported.
        val spring = race("Tenno Sho (Spring)", 60)
        val tennoSweep =
            rewardlessEpithet(
                "Tenno Sweep",
                listOf(EpithetMatcher.WinRace("Tenno Sho (Spring)"), EpithetMatcher.WinRace("Tenno Sho (Autumn)")),
                listOf("Win the Tenno Sho (Spring)", "Win the Tenno Sho (Autumn)"),
            )

        val st = state(currentTurn = 58, races = listOf(spring), epithets = listOf(tennoSweep))

        val schedule = SmartRaceSolver.solve(st)
        assertTrue("Tenno Sweep" !in schedule.projectedEpithets, "an epithet missing a required race must not be reported as projected")
    }
}
