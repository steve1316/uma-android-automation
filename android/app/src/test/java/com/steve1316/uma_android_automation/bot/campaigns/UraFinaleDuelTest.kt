package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Happy Meek duel pick policy. The on-screen detection (badge match, prediction-icon read, OCR of the "Contest of X" labels) is validated on-device; the
 * "best odds among target stats, else best odds overall" decision is pure and covered here.
 */
@DisplayName("Happy Meek duel pick policy")
class UraFinaleDuelTest {
    private val targets = listOf(StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.WIT, StatName.GUTS)

    @Test
    @DisplayName("Picks the double-circle contest when it is a target stat")
    fun picksBestOddsTarget() {
        // Screenshot 122551: guts triangle, speed double-circle, stamina triangle.
        val options =
            listOf(
                DuelContestOption(StatName.GUTS, DuelPrediction.BAD),
                DuelContestOption(StatName.SPEED, DuelPrediction.GREAT),
                DuelContestOption(StatName.STAMINA, DuelPrediction.BAD),
            )
        assertEquals(1, chooseDuelContest(options, targets), "should pick the speed double-circle")
    }

    @Test
    @DisplayName("Ignores the energy option (not a trainable stat) and picks the best-odds target")
    fun ignoresEnergyOption() {
        // Screenshot 124526: power double-circle, stamina X, energy triangle.
        val options =
            listOf(
                DuelContestOption(StatName.POWER, DuelPrediction.GREAT),
                DuelContestOption(StatName.STAMINA, DuelPrediction.WORST),
                DuelContestOption(null, DuelPrediction.BAD),
            )
        assertEquals(0, chooseDuelContest(options, targets), "should pick the power double-circle")
    }

    @Test
    @DisplayName("Prefers a target stat with good odds over a non-target with better odds")
    fun prefersTargetWithGoodOdds() {
        val options =
            listOf(
                DuelContestOption(StatName.GUTS, DuelPrediction.GREAT),
                DuelContestOption(StatName.SPEED, DuelPrediction.GOOD),
            )
        // Guts is the lowest-priority target here but still a target; Speed (higher priority) has GOOD, which counts as good odds, so Speed wins over the higher-odds Guts.
        assertEquals(1, chooseDuelContest(options, listOf(StatName.SPEED)), "target Speed with GOOD beats non-target Guts with GREAT")
    }

    @Test
    @DisplayName("Falls back to best odds overall when no target stat has good odds")
    fun fallsBackToBestOverall() {
        val options =
            listOf(
                DuelContestOption(StatName.GUTS, DuelPrediction.GREAT),
                DuelContestOption(StatName.WIT, DuelPrediction.BAD),
            )
        // Neither Guts nor Wit is a target here, so pick the best-odds option overall (Guts double-circle).
        assertEquals(0, chooseDuelContest(options, listOf(StatName.SPEED, StatName.STAMINA)), "no good-odds target -> best overall")
    }

    @Test
    @DisplayName("Breaks a prediction tie toward the higher-priority target stat")
    fun tieBreaksByPriority() {
        val options =
            listOf(
                DuelContestOption(StatName.POWER, DuelPrediction.GREAT),
                DuelContestOption(StatName.SPEED, DuelPrediction.GREAT),
            )
        // Both double-circle targets; Speed is higher priority than Power, so it wins the tie.
        assertEquals(1, chooseDuelContest(options, listOf(StatName.SPEED, StatName.POWER)), "tie -> higher-priority Speed")
    }

    @Test
    @DisplayName("Empty option list defaults to the first index")
    fun emptyDefaultsToZero() {
        assertEquals(0, chooseDuelContest(emptyList(), targets), "no options -> index 0")
    }
}
