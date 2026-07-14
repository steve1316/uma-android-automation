package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the retry budget check shared by the dialog-based and button-based race retry paths in `Racing`.
 * A lost Tenno Sho Spring was accepted as a career-ending failure because the dialog path applied the per-race cap of 1 to a mandatory race,
 * even though that race is meant to be retried until it is won.
 */
@DisplayName("Race retry gating")
class RaceRetryGatingTest {
    @Test
    @DisplayName("A retry-until-1st race is not bound by the per-race cap")
    fun testRetryUntilFirstIgnoresPerRaceCap() {
        // The exact reported failure: one retry had already been used on a mandatory race
        // (retriesThisRace == maxRetriesPerRace == 1) while 3 pool retries remained.
        assertTrue(Racing.canRetryRace(raceRetries = 3, retriesThisRace = 1, maxRetriesPerRace = 1, retryUntilFirst = true))
        assertTrue(Racing.canRetryRace(raceRetries = 1, retriesThisRace = 5, maxRetriesPerRace = 1, retryUntilFirst = true))
    }

    @Test
    @DisplayName("A normal race is still bound by the per-race cap")
    fun testNormalRaceHonorsPerRaceCap() {
        assertTrue(Racing.canRetryRace(raceRetries = 3, retriesThisRace = 0, maxRetriesPerRace = 1, retryUntilFirst = false))
        assertFalse(Racing.canRetryRace(raceRetries = 3, retriesThisRace = 1, maxRetriesPerRace = 1, retryUntilFirst = false))
    }

    @Test
    @DisplayName("The run-wide retry pool still bounds a retry-until-1st race")
    fun testPoolStillBoundsRetryUntilFirst() {
        // Exempting mandatory races from the per-race cap must not make retries unbounded.
        assertFalse(Racing.canRetryRace(raceRetries = 0, retriesThisRace = 0, maxRetriesPerRace = 1, retryUntilFirst = true))
        assertFalse(Racing.canRetryRace(raceRetries = -1, retriesThisRace = 0, maxRetriesPerRace = 1, retryUntilFirst = true))
    }
}
