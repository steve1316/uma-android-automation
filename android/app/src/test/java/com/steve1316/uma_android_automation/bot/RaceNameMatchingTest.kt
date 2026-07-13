package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.TrackDistance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the race name normalization and distance sanity check in `Racing`.
 * OCR read Hanshin Daishoten as "Hanshin Turf 300Om (Long) Right / Inner", with a letter O in place of the zero.
 * The whole-string fuzzy match then picked an 1800m Mile race at 0.9 similarity, so the bot raced a 3000m Long race with the Mile running style.
 */
@DisplayName("Race name matching")
class RaceNameMatchingTest {
    @Test
    @DisplayName("The letter O inside a distance token is corrected back to a zero")
    fun testNormalizesLetterOInDistanceToken() {
        assertEquals("Hanshin Turf 3000m (Long) Right / Inner", Racing.normalizeRaceName("Hanshin Turf 300Om (Long) Right / Inner"))
        assertEquals("Kyoto Turf 3000m (Long) Right / Outer", Racing.normalizeRaceName("Kyoto Turf 300Om (Long) Right / Outer"))
        assertEquals("Nakayama Turf 1200m (Sprint) Right / Outer", Racing.normalizeRaceName("Nakayama Turf 12OOm (Sprint) Right / Outer"))
    }

    @Test
    @DisplayName("A cleanly read name is left untouched")
    fun testLeavesCleanNamesUnchanged() {
        // The distance token is already correct, and no letter elsewhere in the name may be rewritten.
        assertEquals("Tokyo Turf 2400m (Med) Left", Racing.normalizeRaceName("Tokyo Turf 2400m (Med) Left"))
        assertEquals("Osakajo Stakes", Racing.normalizeRaceName("Osakajo Stakes"))
    }

    @Test
    @DisplayName("The distance category is parsed out of the formatted name")
    fun testParsesDistanceCategory() {
        assertEquals(TrackDistance.LONG, Racing.distanceCategoryFromRaceName("Hanshin Turf 3000m (Long) Right / Inner"))
        assertEquals(TrackDistance.MILE, Racing.distanceCategoryFromRaceName("Hanshin Turf 1800m (Mile) Right / Outer"))
        assertEquals(TrackDistance.MEDIUM, Racing.distanceCategoryFromRaceName("Tokyo Turf 2400m (Med) Left"))
        assertEquals(TrackDistance.SPRINT, Racing.distanceCategoryFromRaceName("Nakayama Turf 1200m (Sprint) Right / Outer"))
    }

    @Test
    @DisplayName("A name with no category token yields no category")
    fun testNoCategoryTokenYieldsNull() {
        assertNull(Racing.distanceCategoryFromRaceName("Hanshin Turf Right / Inner"))
    }

    @Test
    @DisplayName("A candidate whose distance contradicts the detected name is rejected")
    fun testRejectsContradictingDistance() {
        // The exact reported failure: a "(Long)" name must never match the Mile race that Jaro-Winkler scored at 0.9.
        val detected = "Hanshin Turf 3000m (Long) Right / Inner"
        assertFalse(Racing.matchesDetectedDistance(detected, TrackDistance.MILE), "An 1800m Mile candidate must be rejected for a (Long) name")
        assertTrue(Racing.matchesDetectedDistance(detected, TrackDistance.LONG), "The genuine Long candidate must still be accepted")
    }

    @Test
    @DisplayName("Every candidate is accepted when the category could not be parsed")
    fun testAcceptsAllWhenCategoryUnknown() {
        // Without a category token there is nothing to contradict, so the check must not filter anything out.
        for (distance in TrackDistance.entries) {
            assertTrue(Racing.matchesDetectedDistance("Hanshin Turf Right / Inner", distance), "$distance must be accepted when the name has no category")
        }
    }
}
