package com.steve1316.uma_android_automation.bot.career_setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit tests for Career Setup: finishing a career back to Home, and keeping the last legacy parents. */
@DisplayName("Career Setup")
class CareerSetupTest {
    /**
     * Picks the step for a screen showing only the given buttons.
     *
     * @returns The step.
     */
    private fun step(home: Boolean = false, completeCareer: Boolean = false, next: Boolean = false, confirm: Boolean = false, ok: Boolean = false, close: Boolean = false) =
        completionStep(CompletionScreen(home, completeCareer, next, confirm, ok, close))

    @Test
    @DisplayName("Each screen gets its one forward tap")
    fun forwardTaps() {
        assertEquals(CompletionStep.COMPLETE_CAREER, step(completeCareer = true))
        assertEquals(CompletionStep.NEXT, step(next = true))
        // The Sparks screen shows Confirm beside Reroll Sparks, which costs TP, so only Confirm is ever tapped.
        assertEquals(CompletionStep.CONFIRM, step(confirm = true))
        assertEquals(CompletionStep.OK, step(ok = true))
        assertEquals(CompletionStep.CLOSE, step(close = true))
    }

    @Test
    @DisplayName("Home ends the flow, and a screen with no known button waits for its animation")
    fun doneAndWait() {
        assertEquals(CompletionStep.DONE, step(home = true, close = true))
        assertEquals(CompletionStep.WAIT, step())
    }

    @Test
    @DisplayName("Finish and the Sparks confirmation go forward, every other dialog is closed or cancelled")
    fun dialogs() {
        assertTrue(confirmsCompletionDialog("complete_career"))
        assertTrue(confirmsCompletionDialog("sparks_confirmation"))
        assertFalse(confirmsCompletionDialog("career_complete"))
        assertFalse(confirmsCompletionDialog("event_home"))
        assertFalse(confirmsCompletionDialog("umamusume_details"))
    }

    /** Unit tests for Keep Last, which keeps the game's remembered parents and fills only an empty slot with a Smart pick. */
    @DisplayName("Keep Last legacy parents")
    @Nested
    inner class KeepLastTest {
        @Test
        @DisplayName("An empty slot is told apart by which half of the screen its plus button is on")
        fun emptySlots() {
            assertEquals(listOf(2), emptyLegacySlots(listOf(810.0), screenWidth = 1080))
            assertEquals(listOf(1), emptyLegacySlots(listOf(270.0), screenWidth = 1080))
            assertEquals(listOf(1, 2), emptyLegacySlots(listOf(810.0, 270.0), screenWidth = 1080))
            assertEquals(emptyList<Int>(), emptyLegacySlots(emptyList(), screenWidth = 1080))
        }
    }
}
