package com.steve1316.uma_android_automation.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the running-style stamina-target adjustment. Required stamina varies sharply by running style, so the bot scales the stamina target accordingly.
 */
@DisplayName("Running-style stamina targets")
class RunningStyleStaminaTest {
    private val base =
        mapOf(
            StatName.SPEED to 1000,
            StatName.STAMINA to 600,
            StatName.POWER to 800,
            StatName.GUTS to 300,
            StatName.WIT to 300,
        )

    @Test
    @DisplayName("Front Runners get a higher stamina target, End Closers a lower one, other stats unchanged")
    fun testStyleScaling() {
        val front = applyRunningStyleStaminaFactor(base, RunningStyle.FRONT_RUNNER)
        val end = applyRunningStyleStaminaFactor(base, RunningStyle.END_CLOSER)
        assertTrue(front[StatName.STAMINA]!! > 600, "Front Runner stamina target should rise above the base")
        assertTrue(end[StatName.STAMINA]!! < 600, "End Closer stamina target should fall below the base")
        assertEquals(1000, front[StatName.SPEED], "Non-stamina stats must be untouched")
    }

    @Test
    @DisplayName("Unknown style or a neutral factor leaves the targets unchanged")
    fun testNeutral() {
        assertEquals(600, applyRunningStyleStaminaFactor(base, null)[StatName.STAMINA], "Undetected style must not change targets")
        assertEquals(600, applyRunningStyleStaminaFactor(base, RunningStyle.PACE_CHASER)[StatName.STAMINA], "A 1.0 factor must not change targets")
    }
}
