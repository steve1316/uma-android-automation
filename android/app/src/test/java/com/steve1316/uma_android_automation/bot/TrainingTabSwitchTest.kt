package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for `switchTrainingTab`. A second tap on the training that is already selected starts it, so a tap is retried only after its header had 1 s to show. */
@DisplayName("Training tab switch")
class TrainingTabSwitchTest {
    /** A fake clock that moves 200 ms on every pause, like `game.wait(0.2)`. */
    private class Clock {
        /** The current time in ms. */
        var now = 0L

        /** How many pauses ran. */
        var pauses = 0

        /** Pause for 200 ms. */
        fun pause() {
            pauses++
            now += 200
        }
    }

    /**
     * A training button that is found from the `foundOnLook`th look on.
     *
     * @param foundOnLook The first look that finds the button.
     */
    private class FakeButton(private val foundOnLook: Int = 1) {
        /** How many times the button was looked for. */
        var looks = 0

        /** How many taps went through. */
        var taps = 0

        /**
         * Look for the button and tap it when found.
         *
         * @return True if the button was found and tapped.
         */
        fun tap(): Boolean {
            looks++
            if (looks < foundOnLook) return false
            taps++
            return true
        }
    }

    @Test
    @DisplayName("A header that shows within 1 s is waited for, with one tap")
    fun testSlowHeaderGetsOneTap() {
        val clock = Clock()
        val button = FakeButton()
        val switched = switchTrainingTab(button::tap, { clock.now >= 800 }, clock::pause, nowMs = { clock.now })
        assertTrue(switched, "the header showed within the wait")
        assertEquals(1, button.taps, "the old loop tapped again after 0.2 s, here it must not")
    }

    @Test
    @DisplayName("A tap whose header never shows is retried after each full wait, then fails")
    fun testMissingHeaderFailsAfterThreeTaps() {
        val clock = Clock()
        val button = FakeButton()
        var misses = 0
        val switched = switchTrainingTab(button::tap, { false }, clock::pause, onMiss = { misses++ }, nowMs = { clock.now })
        assertFalse(switched, "no header, no switch")
        assertEquals(3, button.taps, "one tap per attempt")
        assertEquals(3, misses, "every failed attempt is a miss")
        assertTrue(clock.now >= 3000, "each tap got its full 1 s wait")
    }

    @Test
    @DisplayName("A tap that did not land is retried once its wait runs out")
    fun testMissedTapIsRetried() {
        val clock = Clock()
        val button = FakeButton()
        var misses = 0
        val switched = switchTrainingTab(button::tap, { button.taps >= 2 }, clock::pause, onMiss = { misses++ }, nowMs = { clock.now })
        assertTrue(switched, "the second tap landed")
        assertEquals(2, button.taps)
        assertEquals(1, misses, "the miss hook ran once, before the second tap")
        assertTrue(clock.now >= 1000, "the first tap got its full wait before the retry")
    }

    @Test
    @DisplayName("A button that was not found is looked for again, since nothing was tapped")
    fun testButtonNotFoundIsRetried() {
        val clock = Clock()
        val button = FakeButton(foundOnLook = 2)
        val switched = switchTrainingTab(button::tap, { true }, clock::pause, nowMs = { clock.now })
        assertTrue(switched, "the second look found the button")
        assertEquals(2, button.looks, "it stops looking once a tap went through")
        assertEquals(1, button.taps)
    }

    @Test
    @DisplayName("A button that is never found taps nothing and fails")
    fun testButtonNeverFound() {
        val clock = Clock()
        val button = FakeButton(foundOnLook = 4)
        val switched = switchTrainingTab(button::tap, { error("no tap, so no header wait") }, clock::pause, nowMs = { clock.now })
        assertFalse(switched, "nothing was tapped")
        assertEquals(3, button.looks, "three looks, like the old loop")
    }

    @Test
    @DisplayName("A header that shows right away returns without pausing")
    fun testFastHeader() {
        val clock = Clock()
        val button = FakeButton()
        val switched = switchTrainingTab(button::tap, { true }, clock::pause, nowMs = { clock.now })
        assertTrue(switched)
        assertEquals(1, button.taps)
        assertEquals(0, clock.pauses, "the header check comes before the first pause")
    }
}
