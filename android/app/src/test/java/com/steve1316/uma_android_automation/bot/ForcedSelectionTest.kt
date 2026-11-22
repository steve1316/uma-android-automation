package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the rejected training that `forceSelection` falls back to when analysis rejected every training.
 */
@DisplayName("Forced selection from rejected trainings")
class ForcedSelectionTest {
    private fun option(stat: StatName, failureChance: Int) =
        Training.TrainingOption(
            name = stat,
            statGains = mapOf(stat to 20),
            failureChance = failureChance,
            relationshipBars = arrayListOf(),
            numRainbow = 0,
            skipReason = if (failureChance < 0) Training.SKIP_UNREADABLE_FAILURE_CHANCE else Training.SKIP_HIGH_FAILURE_CHANCE,
        )

    @Test
    @DisplayName("The highest scored rejected training is picked when every failure chance was read")
    fun picksHighestScored() {
        val skipped = mapOf(option(StatName.SPEED, 45) to 10.0, option(StatName.POWER, 60) to 30.0)
        assertEquals(StatName.POWER, Training.highestScoredReadableSkip(skipped)?.key?.name)
    }

    @Test
    @DisplayName("A rejected training with an unread failure chance is never picked, even when it scores highest")
    fun skipsUnreadFailureChance() {
        val skipped = mapOf(option(StatName.SPEED, 45) to 10.0, option(StatName.GUTS, -1) to 50.0)
        assertEquals(StatName.SPEED, Training.highestScoredReadableSkip(skipped)?.key?.name)
    }

    @Test
    @DisplayName("Nothing is picked when no rejected training has a read failure chance")
    fun nothingWhenAllUnread() {
        assertNull(Training.highestScoredReadableSkip(mapOf(option(StatName.GUTS, -1) to 50.0)))
        assertNull(Training.highestScoredReadableSkip(emptyMap()))
    }
}
