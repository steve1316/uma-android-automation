package com.steve1316.uma_android_automation.types

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [fuzzyMatchesAny], the pure Jaro-Winkler helper that both dedups raw condition reads
 * (0.9) and answers canonical-name membership queries (0.8) without any hardcoded condition list.
 */
@DisplayName("fuzzyMatchesAny Tests")
class FuzzyStatusMatchTest {
    @Nested
    @DisplayName("Query threshold (0.8)")
    inner class QueryMatch {
        @Test
        fun `identical name matches`() {
            assertTrue(fuzzyMatchesAny("Night Owl", listOf("Night Owl"), STATUS_QUERY_THRESHOLD))
        }

        @Test
        fun `one-character OCR error still matches`() {
            assertTrue(fuzzyMatchesAny("Night Owl", listOf("Nlght Owl"), STATUS_QUERY_THRESHOLD))
        }

        @Test
        fun `unrelated name does not match`() {
            assertFalse(fuzzyMatchesAny("Charming", listOf("Night Owl", "Migraine"), STATUS_QUERY_THRESHOLD))
        }

        @Test
        fun `blank candidate never matches`() {
            assertFalse(fuzzyMatchesAny("   ", listOf("Night Owl"), STATUS_QUERY_THRESHOLD))
        }

        @Test
        fun `empty list never matches`() {
            assertFalse(fuzzyMatchesAny("Night Owl", emptyList(), STATUS_QUERY_THRESHOLD))
        }
    }

    @Nested
    @DisplayName("Dedup threshold (0.9)")
    inner class DedupMatch {
        @Test
        fun `OCR jitter of the same pill collapses`() {
            assertTrue(fuzzyMatchesAny("Nlght Owl", listOf("Night Owl"), STATUS_DEDUP_THRESHOLD))
        }

        @Test
        fun `two distinct conditions stay separate`() {
            assertFalse(fuzzyMatchesAny("Migraine", listOf("Slacker", "Night Owl"), STATUS_DEDUP_THRESHOLD))
        }
    }
}
