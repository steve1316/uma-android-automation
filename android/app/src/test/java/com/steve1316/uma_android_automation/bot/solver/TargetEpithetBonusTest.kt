package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.race
import com.steve1316.uma_android_automation.bot.solver.TestFixtures.state
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Cover for [Weights.targetEpithetBonus]. Picking an epithet as a target used to be inert: `targetEpithets` was plumbed into [SolverState] but never
 * read by the objective, and most epithets grant no reward, so a target scored 0 and the solver had no reason to chase it.
 */
@DisplayName("Target epithet bonus")
class TargetEpithetBonusTest {
    /** An epithet with no reward bullet, so its only possible value to the solver is the target bonus. */
    private fun rewardlessEpithet(name: String, raceName: String): Epithet =
        Epithet(name = name, bullets = listOf("Win the $raceName"), matchers = listOf(EpithetMatcher.WinRace(raceName)))

    /** A race the solver would normally decline: an OP race is worth little and the default race cost makes it a net negative pick. */
    private fun unattractiveRace(name: String, turn: TurnNumber) = race(name, turn, grade = com.steve1316.uma_android_automation.types.RaceGrade.OP, fans = 0)

    @Test
    fun targetingARewardlessEpithetMakesTheSolverPursueIt() {
        val chore = unattractiveRace("Chore Stakes", 60)
        val epithet = rewardlessEpithet("Chore Master", "Chore Stakes")

        // Untargeted: the epithet is worth 0 and the race is not worth running on its own, so the solver skips it.
        val ignored =
            SmartRaceSolver.solve(
                state(currentTurn = 58, races = listOf(chore), epithets = listOf(epithet), weights = Weights(includeOpAndPreOp = true, targetEpithetBonus = 0.0)),
            )
        assertFalse("Chore Master" in ignored.projectedEpithets, "with no bonus a rewardless epithet is worth 0, so the solver should not chase it")

        // Targeted: the bonus makes completing it worth more than the race costs, so the solver now schedules the race.
        val pursued =
            SmartRaceSolver.solve(
                state(
                    currentTurn = 58,
                    races = listOf(chore),
                    epithets = listOf(epithet),
                    targetEpithets = setOf("Chore Master"),
                    weights = Weights(includeOpAndPreOp = true, targetEpithetBonus = 25.0),
                ),
            )
        assertTrue("Chore Master" in pursued.projectedEpithets, "a targeted epithet should be pursued once the target bonus outweighs the race cost")
        assertTrue(chore.key in pursued.raceTurns().map { it.second }, "the solver should schedule the race the target epithet needs")
    }

    @Test
    fun targetingAWinCountEpithetSchedulesTheExtraRacesItNeeds() {
        // Mirror of the reported Dirt Demon case: a winCount matcher (win N of a filtered kind), not a single named race. The solver would
        // normally schedule fewer than N such races; a large target bonus should make it schedule the extra races to complete the epithet.
        val dirtRaces = (30..44).map { race("Dirt Race $it", it, terrain = com.steve1316.uma_android_automation.types.TrackSurface.DIRT, fans = 0) }
        val demon =
            Epithet(
                name = "Dirt Demon",
                bullets = listOf("Win 6 dirt races"),
                matchers = listOf(EpithetMatcher.WinCount(count = 6, filter = EpithetFilter(terrain = com.steve1316.uma_android_automation.types.TrackSurface.DIRT))),
            )

        val untargeted =
            SmartRaceSolver.solve(state(currentTurn = 28, races = dirtRaces, epithets = listOf(demon), weights = Weights(raceCostPct = 100.0, targetEpithetBonus = 0.0)))
        val untargetedDirt = untargeted.raceTurns().size

        val targeted =
            SmartRaceSolver.solve(
                state(
                    currentTurn = 28,
                    races = dirtRaces,
                    epithets = listOf(demon),
                    targetEpithets = setOf("Dirt Demon"),
                    weights = Weights(raceCostPct = 100.0, targetEpithetBonus = 500.0),
                ),
            )
        assertTrue("Dirt Demon" in targeted.projectedEpithets, "a targeted winCount epithet should be completed once the bonus outweighs the extra races' cost (untargeted raced $untargetedDirt)")
        assertTrue(targeted.raceTurns().size >= 6, "the solver should schedule at least the 6 dirt races the epithet needs, got ${targeted.raceTurns().size}")
    }

    @Test
    fun aZeroBonusLeavesTargetSelectionInformationalOnly() {
        val chore = unattractiveRace("Chore Stakes", 60)
        val epithet = rewardlessEpithet("Chore Master", "Chore Stakes")

        val schedule =
            SmartRaceSolver.solve(
                state(
                    currentTurn = 58,
                    races = listOf(chore),
                    epithets = listOf(epithet),
                    targetEpithets = setOf("Chore Master"),
                    weights = Weights(includeOpAndPreOp = true, targetEpithetBonus = 0.0),
                ),
            )
        assertFalse("Chore Master" in schedule.projectedEpithets, "a zero bonus should opt out of target pursuit entirely")
    }
}
