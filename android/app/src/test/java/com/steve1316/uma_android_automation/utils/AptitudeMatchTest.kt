package com.steve1316.uma_android_automation.utils

import com.steve1316.uma_android_automation.types.Aptitude
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure argmax decision behind aptitude letter detection. The OpenCV pixel matching in `findBestTemplateMatch` needs native OpenCV and is validated on-device, but the
 * "highest score wins, not the first to clear the floor" rule that fixes the E/D-read-as-B misreads is pure and covered here.
 */
@DisplayName("Aptitude argmax decision")
class AptitudeMatchTest {
    @Test
    @DisplayName("The highest-scoring letter wins even when a lower-ranked letter also clears the floor")
    fun testHighestScoreWins() {
        // Sprint is truly E: E scores highest, but B also clears 0.8. The old first-match-wins loop returned B; argmax must return E.
        assertEquals(Aptitude.E, argMaxAboveFloor(mapOf(Aptitude.B to 0.83, Aptitude.E to 0.91), 0.8), "E outscores B on an E cell")
        // End is truly D: same shape of failure, D must win over B.
        assertEquals(Aptitude.D, argMaxAboveFloor(mapOf(Aptitude.B to 0.84, Aptitude.D to 0.90), 0.8), "D outscores B on a D cell")
    }

    @Test
    @DisplayName("A clear single winner is returned; a score exactly at the floor is accepted")
    fun testWinnerSelection() {
        assertEquals(Aptitude.A, argMaxAboveFloor(mapOf(Aptitude.A to 0.95), 0.8), "The only candidate wins")
        assertEquals(Aptitude.C, argMaxAboveFloor(mapOf(Aptitude.C to 0.8, Aptitude.B to 0.79), 0.8), "A score exactly at the floor is accepted")
    }

    @Test
    @DisplayName("Nothing is returned when no candidate reaches the floor or the map is empty")
    fun testNoMatch() {
        assertNull(argMaxAboveFloor(mapOf(Aptitude.B to 0.6, Aptitude.E to 0.7), 0.8), "All below the floor means no match, so the cell keeps its default")
        assertNull(argMaxAboveFloor(emptyMap<Aptitude, Double>(), 0.8), "An empty score map means no match")
    }
}
