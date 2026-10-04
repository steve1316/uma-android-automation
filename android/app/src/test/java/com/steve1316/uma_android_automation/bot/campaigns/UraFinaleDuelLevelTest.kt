package com.steve1316.uma_android_automation.bot.campaigns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the pure Happy Meek duel level helpers behind the "Avoid Maxing Happy Meek" setting. The label OCR is validated on-device. */
@DisplayName("Happy Meek duel level")
class UraFinaleDuelLevelTest {
    @Test
    @DisplayName("Parses the numbered level next to Lvl")
    fun parsesNumberedLevel() {
        assertEquals(2, parseDuelLevel("Duel Lvl 2"))
        assertEquals(5, parseDuelLevel("Lvl5"))
        assertEquals(1, parseDuelLevel("DUEL LVL 1"))
    }

    @Test
    @DisplayName("Parses the maxed level")
    fun parsesMaxLevel() {
        assertEquals(DUEL_LEVEL_MAX, parseDuelLevel("Duel Lvl MAX"))
        assertEquals(DUEL_LEVEL_MAX, parseDuelLevel("lvl max"))
    }

    @Test
    @DisplayName("Tolerates common OCR misreads of Lvl")
    fun toleratesMisreads() {
        assertEquals(3, parseDuelLevel("LvI 3"))
        assertEquals(4, parseDuelLevel("Lv1 4"))
        assertEquals(5, parseDuelLevel("Lv 5"))
    }

    @Test
    @DisplayName("Reads a bare level, since the crop holds nothing but her label")
    fun parsesBareLevel() {
        assertEquals(1, parseDuelLevel("1"), "ML Kit can return the number without the Lvl prefix")
        assertEquals(5, parseDuelLevel(" |5 "))
        assertEquals(DUEL_LEVEL_MAX, parseDuelLevel("MAX"))
    }

    @Test
    @DisplayName("Ignores support friendship MAX badges and unrelated text")
    fun ignoresUnrelatedText() {
        assertNull(parseDuelLevel(""))
        assertNull(parseDuelLevel("MAX MAX"))
        assertNull(parseDuelLevel("Duel"))
        assertNull(parseDuelLevel("Duel 1"), "a bare level must be the whole text")
        assertNull(parseDuelLevel("7"), "levels only run 1-5")
        assertEquals(DUEL_LEVEL_MAX, parseDuelLevel("MAX MAX Duel Lvl MAX"), "the support MAX badges must not hide Meek's own level")
    }

    @Test
    @DisplayName("Avoids the facility only when one more win would max her")
    fun avoidsAtLevelFive() {
        assertTrue(shouldAvoidDuelFacility(DUEL_LEVEL_BEFORE_MAX))
        for (level in 1..4) {
            assertFalse(shouldAvoidDuelFacility(level), "level $level is still safe to win")
        }
        assertFalse(shouldAvoidDuelFacility(DUEL_LEVEL_MAX), "already maxed, nothing left to protect")
    }

    @Test
    @DisplayName("Advances the tracked level per duel entered, never claiming MAX on its own")
    fun advancesLevel() {
        assertEquals(3, advanceDuelLevel(2))
        assertEquals(DUEL_LEVEL_BEFORE_MAX, advanceDuelLevel(4))
        assertEquals(DUEL_LEVEL_BEFORE_MAX, advanceDuelLevel(DUEL_LEVEL_BEFORE_MAX), "dead reckoning stops one short of MAX")
        assertEquals(DUEL_LEVEL_MAX, advanceDuelLevel(DUEL_LEVEL_MAX), "already maxed stays maxed")
        assertNull(advanceDuelLevel(null), "an unknown level stays unknown")
    }

    @Test
    @DisplayName("Fails safe by avoiding when her level is unknown")
    fun avoidsWhenUnreadable() {
        assertTrue(shouldAvoidDuelFacility(null))
    }
}
