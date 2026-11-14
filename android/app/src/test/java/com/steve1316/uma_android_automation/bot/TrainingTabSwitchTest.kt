package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for `tapTrainingOnce`. A second tap on the training that is already selected starts it, so a tap that went through is never repeated. */
@DisplayName("Training tab switch")
class TrainingTabSwitchTest {
    /** A fake clock that moves 200 ms on every pause, like `game.wait(0.2)`. */
    private class Clock {
        var now = 0L
        var pauses = 0

        fun pause() {
            pauses++
            now += 200
        }
    }

    /** A training button that is found from the [foundOnLook]th look on. */
    private class FakeButton(private val foundOnLook: Int = 1) {
        var looks = 0

        fun tap(): Boolean {
            looks++
            return looks >= foundOnLook
        }
    }

    @Test
    @DisplayName("A header that shows late is waited for, with one tap")
    fun testSlowHeaderGetsOneTap() {
        val clock = Clock()
        val button = FakeButton()
        val switched = tapTrainingOnce(button::tap, { clock.now >= 1000 }, clock::pause, nowMs = { clock.now })
        assertTrue(switched, "the header showed within the timeout")
        assertEquals(1, button.looks, "the old loop tapped again after 0.2 s, here it must not")
    }

    @Test
    @DisplayName("A header that never shows fails after one tap")
    fun testMissingHeaderFailsAfterOneTap() {
        val clock = Clock()
        val button = FakeButton()
        val switched = tapTrainingOnce(button::tap, { false }, clock::pause, nowMs = { clock.now })
        assertFalse(switched, "no header, no switch")
        assertEquals(1, button.looks, "a tap that went through is never repeated")
        assertTrue(clock.now >= 3000, "it waited the full timeout before giving up")
    }

    @Test
    @DisplayName("A button that was not found is looked for again, since nothing was tapped")
    fun testButtonNotFoundIsRetried() {
        val clock = Clock()
        val button = FakeButton(foundOnLook = 2)
        val switched = tapTrainingOnce(button::tap, { true }, clock::pause, nowMs = { clock.now })
        assertTrue(switched, "the second look found the button")
        assertEquals(2, button.looks, "it stops looking once a tap went through")
    }

    @Test
    @DisplayName("A button that is never found taps nothing and fails")
    fun testButtonNeverFound() {
        val clock = Clock()
        val button = FakeButton(foundOnLook = 4)
        val switched = tapTrainingOnce(button::tap, { error("no tap, so no header wait") }, clock::pause, nowMs = { clock.now })
        assertFalse(switched, "nothing was tapped")
        assertEquals(3, button.looks, "three looks, like the old loop")
    }

    @Test
    @DisplayName("A header that shows right away returns without pausing")
    fun testFastHeader() {
        val clock = Clock()
        val button = FakeButton()
        val switched = tapTrainingOnce(button::tap, { true }, clock::pause, nowMs = { clock.now })
        assertTrue(switched)
        assertEquals(1, button.looks)
        assertEquals(0, clock.pauses, "the header check comes before the first pause")
    }
}
