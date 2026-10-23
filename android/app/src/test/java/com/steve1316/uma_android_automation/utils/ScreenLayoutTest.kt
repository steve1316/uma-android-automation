package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for mapping 1080x1920 baseline coordinates onto the screen the game is actually laid out on. */
@DisplayName("Screen layout")
class ScreenLayoutTest {
    @AfterEach
    fun reset() {
        ScreenLayout.refresh(1080, 1920, 0)
    }

    @Test
    @DisplayName("The 1080x1920 baseline maps every anchor onto itself")
    fun baselineIsIdentity() {
        Anchor.entries.forEach { assertEquals(1500, ScreenLayout.y(1500, it)) }
    }

    @Test
    @DisplayName("A 1080x2340 emulator moves centered UI by half the extra height and bottom-pinned UI by all of it")
    fun tallEmulator() {
        ScreenLayout.refresh(1080, 2340, 0)
        assertEquals(243, ScreenLayout.y(243, Anchor.TOP))
        assertEquals(531, ScreenLayout.y(321, Anchor.CENTER))
        assertEquals(1957, ScreenLayout.y(1537, Anchor.BOTTOM))
    }

    @Test
    @DisplayName("A black cutout band pushes top-pinned UI down but leaves bottom-pinned UI where it was")
    fun tallPhoneWithCutout() {
        ScreenLayout.refresh(1080, 2340, 84)
        assertEquals(327, ScreenLayout.y(243, Anchor.TOP))
        assertEquals(573, ScreenLayout.y(321, Anchor.CENTER))
        assertEquals(1957, ScreenLayout.y(1537, Anchor.BOTTOM))
    }

    @Test
    @DisplayName("Screens that are not 1080 wide keep their proportional scaling untouched")
    fun otherWidthsAreIdentity() {
        ScreenLayout.refresh(1600, 2560, 84)
        Anchor.entries.forEach { assertEquals(1500, ScreenLayout.y(1500, it)) }
    }

    @Test
    @DisplayName("A region keeps its x, width and height and only moves its top edge")
    fun regionMovesOnlyY() {
        ScreenLayout.refresh(1080, 2340, 0)
        val baseline = intArrayOf(-53, 321, 420, 32)
        assertEquals(listOf(-53, 531, 420, 32), ScreenLayout.region(baseline, Anchor.CENTER).toList())
        assertEquals(listOf(-53, 321, 420, 32), baseline.toList())
    }

    @Test
    @DisplayName("Offsets from a pinned anchor to centered UI move by the difference between the two shifts")
    fun relativeShift() {
        ScreenLayout.refresh(1080, 2340, 0)
        assertEquals(listOf(-53, 111, 420, 32), ScreenLayout.region(intArrayOf(-53, 321, 420, 32), Anchor.CENTER, relativeTo = Anchor.BOTTOM).toList())
        assertEquals(531, ScreenLayout.region(intArrayOf(0, 321, 0, 0), Anchor.CENTER, relativeTo = Anchor.TOP)[1])
        ScreenLayout.refresh(1080, 2340, 84)
        assertEquals(153, ScreenLayout.region(intArrayOf(0, 321, 0, 0), Anchor.CENTER, relativeTo = Anchor.BOTTOM)[1])
        assertEquals(489, ScreenLayout.region(intArrayOf(0, 321, 0, 0), Anchor.CENTER, relativeTo = Anchor.TOP)[1])
    }
}
