package com.steve1316.uma_android_automation.bot.campaigns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MegaphoneSelection per-tier thresholds")
class TrackblazerMegaphoneTest {
    private val allInInventory = mapOf("Empowering Megaphone" to 1, "Motivating Megaphone" to 1, "Coaching Megaphone" to 1)
    private val zeroThresholds = mapOf("Empowering Megaphone" to 0, "Motivating Megaphone" to 0, "Coaching Megaphone" to 0)

    @Test
    fun `zero thresholds pick the best available tier`() {
        assertEquals("Empowering Megaphone", Trackblazer.MegaphoneSelection.bestEligibleMegaphone(15, allInInventory, zeroThresholds))
    }

    @Test
    fun `gain below empowering threshold falls through to motivating`() {
        val thresholds = mapOf("Empowering Megaphone" to 30, "Motivating Megaphone" to 10, "Coaching Megaphone" to 0)
        assertEquals("Motivating Megaphone", Trackblazer.MegaphoneSelection.bestEligibleMegaphone(15, allInInventory, thresholds))
    }

    @Test
    fun `gain below all thresholds yields no megaphone`() {
        val thresholds = mapOf("Empowering Megaphone" to 30, "Motivating Megaphone" to 25, "Coaching Megaphone" to 20)
        assertNull(Trackblazer.MegaphoneSelection.bestEligibleMegaphone(15, allInInventory, thresholds))
    }

    @Test
    fun `only lower tier in inventory is used when eligible`() {
        assertEquals("Coaching Megaphone", Trackblazer.MegaphoneSelection.bestEligibleMegaphone(15, mapOf("Coaching Megaphone" to 2), zeroThresholds))
    }

    @Test
    fun `gain at exactly the threshold is eligible`() {
        val thresholds = mapOf("Empowering Megaphone" to 15, "Motivating Megaphone" to 0, "Coaching Megaphone" to 0)
        assertEquals("Empowering Megaphone", Trackblazer.MegaphoneSelection.bestEligibleMegaphone(15, allInInventory, thresholds))
    }

    @Test
    fun `durationFor returns per-tier turn counts`() {
        assertEquals(2, Trackblazer.MegaphoneSelection.durationFor("Empowering Megaphone"))
        assertEquals(3, Trackblazer.MegaphoneSelection.durationFor("Motivating Megaphone"))
        assertEquals(4, Trackblazer.MegaphoneSelection.durationFor("Coaching Megaphone"))
        assertEquals(0, Trackblazer.MegaphoneSelection.durationFor("Not A Megaphone"))
    }
}
