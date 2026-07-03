package com.steve1316.uma_android_automation.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [stripTrailingGlyphNoise], the pure helper that removes a trailing rank glyph
 * that OCR misread as a letter ("O"/"x") or left as stray noise. Used by `getSkillListEntryTitle`
 * to recover the base skill name before re-appending the correct " <glyph>" suffix.
 */
@DisplayName("stripTrailingGlyphNoise Tests")
class StripTrailingGlyphNoiseTest {
    @Nested
    @DisplayName("Recovers misread glyphs")
    inner class RecoversMisreadGlyphs {
        @Test
        fun `single-circle misread as spaced O is stripped`() {
            assertEquals("Standard Distance", stripTrailingGlyphNoise("Standard Distance O"))
            assertEquals("Medium Corners", stripTrailingGlyphNoise("Medium Corners O"))
            assertEquals("Medium Straightaways", stripTrailingGlyphNoise("Medium Straightaways O"))
        }

        @Test
        fun `cross misread as spaced x is stripped`() {
            assertEquals("Standard Distance", stripTrailingGlyphNoise("Standard Distance x"))
        }

        @Test
        fun `spaced zero is stripped`() {
            assertEquals("Standard Distance", stripTrailingGlyphNoise("Standard Distance 0"))
        }

        @Test
        fun `glued O is stripped`() {
            assertEquals("Foo", stripTrailingGlyphNoise("FooO"))
        }

        @Test
        fun `glued x is stripped`() {
            assertEquals("Foo", stripTrailingGlyphNoise("Foox"))
        }

        @Test
        fun `standalone spaced letter is stripped`() {
            assertEquals("Foo", stripTrailingGlyphNoise("Foo O"))
        }

        @Test
        fun `trailing whitespace is trimmed before stripping`() {
            assertEquals("Standard Distance", stripTrailingGlyphNoise("Standard Distance O   "))
        }
    }

    @Nested
    @DisplayName("Leaves normal names untouched")
    inner class LeavesNormalNames {
        @Test
        fun `names not ending in a stray letter are unchanged`() {
            assertEquals("Extra Tank", stripTrailingGlyphNoise("Extra Tank"))
            assertEquals("Lucky Seven", stripTrailingGlyphNoise("Lucky Seven"))
            assertEquals("Racing Spirit: Speed", stripTrailingGlyphNoise("Racing Spirit: Speed"))
        }

        @Test
        fun `name ending in a glued non-glyph letter is unchanged`() {
            assertEquals("1,500,000 CC", stripTrailingGlyphNoise("1,500,000 CC"))
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {
        @Test
        fun `empty string stays empty`() {
            assertEquals("", stripTrailingGlyphNoise(""))
        }

        @Test
        fun `single stray letter collapses to empty`() {
            assertEquals("", stripTrailingGlyphNoise("O"))
        }
    }
}
