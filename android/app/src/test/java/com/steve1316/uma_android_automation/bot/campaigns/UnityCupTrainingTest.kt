package com.steve1316.uma_android_automation.bot.campaigns

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Unity Cup scheduling helper. The five scenario team races fall on fixed turns (days 24, 36, 48, 60, 72 - Junior Late Dec, Classic Late Jun/Dec, Senior Late
 * Jun/Dec), so the pre-race window can be computed without a live campaign.
 */
@DisplayName("Unity Cup pre-race window")
class UnityCupTrainingTest {
    @Test
    @DisplayName("A turn at or within the lookahead of a scenario race day is a pre-race turn")
    fun testIsUnityPreRaceTurn() {
        // Day 24 is the Junior Late December race; days 22-24 are within a 2-turn lookahead.
        assertTrue(isUnityPreRaceTurn(currentDay = 24, lookahead = 2), "The race-day training itself is the last training before the race")
        assertTrue(isUnityPreRaceTurn(currentDay = 22, lookahead = 2), "Two turns out is inside the window")
        assertFalse(isUnityPreRaceTurn(currentDay = 21, lookahead = 2), "Three turns out is outside a 2-turn window")

        // The window is forward-looking only - a turn just after a race is not pre-race for that race.
        assertFalse(isUnityPreRaceTurn(currentDay = 25, lookahead = 2), "The turn after a race day is not that race's pre-race window")

        // The Senior Late June race is day 60; days 58-60 qualify.
        assertTrue(isUnityPreRaceTurn(currentDay = 59, lookahead = 2), "One turn before the Senior Late June race is pre-race")

        // A quiet mid-year turn is not near any of the five race days.
        assertFalse(isUnityPreRaceTurn(currentDay = 30, lookahead = 2), "A turn far from every scenario race day is not pre-race")
    }
}
