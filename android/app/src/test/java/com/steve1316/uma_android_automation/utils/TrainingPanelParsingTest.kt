package com.steve1316.uma_android_automation.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the pure failure chance and training level OCR parsers. */
@DisplayName("Training panel OCR parsing")
class TrainingPanelParsingTest {
    @Test
    @DisplayName("parseFailureChance takes the digits in front of the percent sign")
    fun testParseFailureChance() {
        assertEquals(10, parseFailureChance("Failure 10%"), "label and value")
        assertEquals(0, parseFailureChance("0%"), "zero")
        assertEquals(5, parseFailureChance("1 5%"), "stray digit before the value")
        assertEquals(20, parseFailureChance("2o%"), "o read in place of 0")
        assertEquals(37, parseFailureChance("37"), "no percent sign")
        assertNull(parseFailureChance("120%"), "a read above 100 is an impossible misread")
    }

    @Test
    @DisplayName("parseFailureChance returns null when there are no digits")
    fun testParseFailureChanceRejects() {
        assertNull(parseFailureChance(""), "empty text")
        assertNull(parseFailureChance("Failure %"), "no digits")
    }

    @Test
    @DisplayName("parseTrainingLevel reads the digit after Lvl")
    fun testParseTrainingLevel() {
        assertEquals(4, parseTrainingLevel("Speed Lvl 4"), "facility name and level")
        assertEquals(1, parseTrainingLevel("lvl1"), "lower case with no space")
        assertNull(parseTrainingLevel("Lvl 7"), "levels only go to 5")
        assertNull(parseTrainingLevel("Speed"), "no level")
        assertNull(parseTrainingLevel(""), "empty text")
    }
}
