package com.steve1316.uma_android_automation.bot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Builds a picture with every pixel at one gray level.
 *
 * @param level The gray level, from 0 to 255.
 * @return The gray pixels.
 */
private fun flat(level: Int): IntArray = IntArray(1000) { level }

/** Unit tests for telling a frozen game from a long stretch of moving unknown screens. */
@DisplayName("Frozen screen watch")
class FrozenScreenWatchTest {
    @Test
    fun sameScreenForTheWholeLimitIsFrozen() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        assertFalse(watch.onFrame(flat(50), 0))
        assertFalse(watch.onFrame(flat(50), 999))
        assertTrue(watch.onFrame(flat(50), 1000))
    }

    @Test
    fun movingScreenNeverFreezes() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        assertFalse((0..20).any { watch.onFrame(flat(it * 30 % 256), it * 500L) })
    }

    @Test
    fun smallNoiseStillCountsAsTheSameScreen() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        watch.onFrame(flat(50), 0)
        assertTrue(watch.onFrame(flat(60), 1000))
    }

    @Test
    fun aSmallMovingSpotKeepsTheScreenAlive() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        watch.onFrame(flat(50), 0)
        val spinner = flat(50).also { for (i in 0 until 10) it[i] = 200 }
        assertFalse(watch.onFrame(spinner, 1000))
    }

    @Test
    fun slowDriftIsMeasuredFromTheStartOfTheStillStretch() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        watch.onFrame(flat(50), 0)
        watch.onFrame(flat(65), 400)
        // 80 is 30 levels from where the stretch started, so the stretch starts over here.
        assertFalse(watch.onFrame(flat(80), 800))
        assertFalse(watch.onFrame(flat(80), 1500))
        assertTrue(watch.onFrame(flat(80), 1800))
    }

    @Test
    fun resetStartsTheStillStretchOver() {
        val watch = FrozenScreenWatch(limitMs = 1000)
        watch.onFrame(flat(50), 0)
        watch.reset()
        assertFalse(watch.onFrame(flat(50), 1500))
        assertTrue(watch.onFrame(flat(50), 2500))
    }
}
