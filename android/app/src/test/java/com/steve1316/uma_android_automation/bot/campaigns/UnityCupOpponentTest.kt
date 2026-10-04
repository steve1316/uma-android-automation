package com.steve1316.uma_android_automation.bot.campaigns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.opencv.core.Point

/**
 * Unit tests for the pure Unity Cup opponent-row helpers. Coordinates come from a 1080x1920 capture of the 4th race: laurel centers at y 479 / 886 /
 * 1294 in column x 267, the Elite badge at x 210, and the Elite Team on the top row.
 */
@DisplayName("Unity Cup opponent rows")
class UnityCupOpponentTest {
    @Test
    @DisplayName("Adds the Elite badge as its own row when its laurel fell short, ordered top to bottom")
    fun addsEliteRow() {
        val rows = buildUnityOpponentRows(laurels = listOf(Point(267.0, 886.0), Point(267.0, 1294.0)), eliteBadge = Point(210.0, 477.0), rowTolerance = 80)
        assertEquals(3, rows.size)
        assertEquals(listOf(477.0, 886.0, 1294.0), rows.map { it.point.y })
        assertEquals(listOf(true, false, false), rows.map { it.isElite })
    }

    @Test
    @DisplayName("Marks the laurel the Elite badge sits on rather than adding a second row for it")
    fun doesNotDoubleCount() {
        val laurels = listOf(Point(267.0, 479.0), Point(267.0, 886.0), Point(267.0, 1294.0))
        val rows = buildUnityOpponentRows(laurels, eliteBadge = Point(210.0, 477.0), rowTolerance = 80)
        assertEquals(3, rows.size)
        assertEquals(listOf(true, false, false), rows.map { it.isElite })
        assertEquals(267.0, rows[0].point.x, "the laurel's own column is kept for the tap")
    }

    @Test
    @DisplayName("Leaves rows unflagged when the Elite badge was not searched for or did not match")
    fun noEliteFound() {
        val laurels = listOf(Point(267.0, 479.0), Point(267.0, 886.0), Point(267.0, 1294.0))
        val rows = buildUnityOpponentRows(laurels, eliteBadge = null, rowTolerance = 80)
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.isElite })
    }

    @Test
    @DisplayName("Reports only the rows it actually found, so a short screen is rescanned rather than guessed at")
    fun doesNotInventRows() {
        val rows = buildUnityOpponentRows(laurels = listOf(Point(267.0, 886.0), Point(267.0, 1294.0)), eliteBadge = null, rowTolerance = 80)
        assertEquals(2, rows.size, "an entrance animation may still be hiding a row; the caller waits and rescans")
    }

    @Test
    @DisplayName("Skips the Elite row only when the setting is on")
    fun candidatesSkipElite() {
        val rows = listOf(UnityOpponentRow(Point(210.0, 477.0), true), UnityOpponentRow(Point(267.0, 886.0), false), UnityOpponentRow(Point(267.0, 1294.0), false))
        assertEquals(listOf(1, 2), unityOpponentCandidates(rows, avoidElite = true))
        assertEquals(listOf(0, 1, 2), unityOpponentCandidates(rows, avoidElite = false))
    }

    @Test
    @DisplayName("Falls back to every row when they are all Elite, so the race can still run")
    fun candidatesNeverEmpty() {
        val rows = listOf(UnityOpponentRow(Point(210.0, 477.0), true))
        assertEquals(listOf(0), unityOpponentCandidates(rows, avoidElite = true))
    }
}
