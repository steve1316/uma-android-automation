package com.steve1316.uma_android_automation.bot.solver

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for dropping a staged race after a pause, so a result the bot never saw is never recorded. */
@DisplayName("Pending race discard")
class PendingRaceDiscardTest {
    @BeforeEach
    fun setUp() {
        SmartRaceSolverIntegration.discardPendingRace()
    }

    @Test
    fun discardDropsAStagedRace() {
        SmartRaceSolverIntegration.markPendingRace("satsuki_sho", "Satsuki Sho", "CLASSIC", 31)
        assertTrue(SmartRaceSolverIntegration.discardPendingRace())
        assertFalse(SmartRaceSolverIntegration.discardPendingRace())
    }

    @Test
    fun discardWithNothingStagedDoesNothing() {
        assertFalse(SmartRaceSolverIntegration.discardPendingRace())
    }
}
