package com.steve1316.uma_android_automation.bot.career_setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit tests for matching OCR'd outfits and support cards against the Career Setup catalogs. */
@DisplayName("Career catalog matching")
class CareerCatalogTest {
    private val trainees =
        listOf(
            TraineeCard("105201", "Haru Urara", "[Bestest Prize ♪]", emptyMap(), emptyMap()),
            TraineeCard("105202", "Haru Urara", "[New Year ♪ New Urara!]", emptyMap(), emptyMap()),
            TraineeCard("104301", "Inari One", "[Edomurasaki]", emptyMap(), emptyMap()),
        )

    private val supports =
        listOf(
            SupportCard("30107", "Maruzensky", "[Sentimental Flare ♪]", "Speed", "SSR"),
            SupportCard("30011", "Maruzensky", "[Princess Sprint]", "Speed", "SSR"),
            SupportCard("30028", "Kitasan Black", "[Fire at My Heels]", "Speed", "SSR"),
        )

    @Test
    @DisplayName("An outfit is told apart from another outfit of the same trainee by its title")
    fun testTraineeOutfit() {
        assertEquals("105202", CareerCatalog.match("[New Year New Urara!]", "Haru Urara", trainees)?.id)
        assertEquals("105201", CareerCatalog.match("[Bestest Prize]", "Haru Urara", trainees)?.id)
    }

    @Test
    @DisplayName("An OCR slip in the title or name still finds the outfit")
    fun testTraineeOcrSlip() {
        assertEquals("104301", CareerCatalog.match("[Edomurasakl]", "lnari One", trainees)?.id)
    }

    @Test
    @DisplayName("A trainee not in the catalog matches nothing")
    fun testTraineeMissing() {
        assertNull(CareerCatalog.match("[Precise Chocolatier]", "Eishin Flash", trainees))
    }

    @Test
    @DisplayName("A support row is matched to the card with the same title and name")
    fun testSupport() {
        assertEquals("30107", CareerCatalog.match("[Sentimental Flare]", "Maruzensky", supports)?.id)
        assertEquals("30011", CareerCatalog.match("[Princess Sprint]", "Maruzensky", supports)?.id)
        assertNull(CareerCatalog.match("[Wave of Gratitude]", "Fine Motion", supports))
    }

    @Test
    @DisplayName("The deck's gap is the scenario's wanted type it holds the fewest of, ties going to the more wanted type")
    fun testDeckGap() {
        assertEquals("Wit", CareerCatalog.deckGapType(listOf("Speed", "Wit"), mapOf("Speed" to 3, "Power" to 2)))
        assertEquals("Speed", CareerCatalog.deckGapType(listOf("Speed", "Wit"), mapOf("Speed" to 1, "Wit" to 1)))
        assertEquals("Stamina", CareerCatalog.deckGapType(listOf("Speed", "Wit", "Stamina"), mapOf("Speed" to 3, "Wit" to 2)))
        assertNull(CareerCatalog.deckGapType(emptyList(), mapOf("Speed" to 3)))
    }

    @Test
    @DisplayName("Names compare in the same order as the game's Name sort")
    fun testGameNameOrder() {
        // Read off the device with the Name sort set to descending.
        val descending = listOf("Winning Ticket", "Vodka", "Tokai Teio", "Tamamo Cross", "Taiki Shuttle", "T.M. Opera O", "Symboli Rudolf", "Super Creek", "Special Week")
        assertEquals(descending, descending.sortedWith(CareerCatalog.gameNameOrder).reversed())
    }

    @Test
    @DisplayName("The search starts from the end of the list nearer the trainee's name")
    fun testSearchDirection() {
        val names = listOf("Agnes Digital", "Daiwa Scarlet", "Inari One", "Mejiro Palmer", "Special Week", "Tokai Teio", "Zenno Rob Roy")
        assertEquals(false, CareerCatalog.searchDescending("Agnes Digital", names))
        assertEquals(false, CareerCatalog.searchDescending("Inari One", names))
        assertEquals(true, CareerCatalog.searchDescending("Tokai Teio", names))
        assertEquals(true, CareerCatalog.searchDescending("Zenno Rob Roy", names))
    }

    @Test
    @DisplayName("A page decides whether the wanted name is on it, above it or below it")
    fun testPageMove() {
        // Ascending, as in the failed run: the page shows Mayano Top Gun to Matikanetannhauser while looking for Inari One.
        assertEquals(PageMove.UP, CareerCatalog.pageMove("Mayano Top Gun", "Matikanetannhauser", "Inari One", descending = false))
        assertEquals(PageMove.DOWN, CareerCatalog.pageMove("Agnes Digital", "Haru Urara", "Inari One", descending = false))
        assertEquals(PageMove.HERE, CareerCatalog.pageMove("Haru Urara", "King Halo", "Inari One", descending = false))
        // Descending flips which way is up.
        assertEquals(PageMove.DOWN, CareerCatalog.pageMove("Matikanetannhauser", "Mayano Top Gun", "Inari One", descending = true))
        assertEquals(PageMove.UP, CareerCatalog.pageMove("Haru Urara", "Agnes Digital", "Inari One", descending = true))
        // An edge card with the wanted name may have the wanted outfit's siblings beside it, so the page is searched.
        assertEquals(PageMove.HERE, CareerCatalog.pageMove("Inari One", "King Halo", "Inari One", descending = false))
        assertEquals(PageMove.HERE, CareerCatalog.pageMove("Agnes Digital", "Inari One", "Inari One", descending = false))
    }

    @Test
    @DisplayName("The run stops only when the solver is on and plans for a different trainee")
    fun testSolverMismatch() {
        val inari = trainees[2]
        assertEquals("Special Week", CareerCatalog.solverMismatch(inari, "Special Week"))
        assertNull(CareerCatalog.solverMismatch(inari, "Inari One"))
        // The solver is off, or the outfit could not be read, so there is nothing to compare.
        assertNull(CareerCatalog.solverMismatch(inari, null))
        assertNull(CareerCatalog.solverMismatch(null, "Special Week"))
    }

    /** Unit tests for matching OCR'd trainee and support card names during Career Setup. */
    @DisplayName("Career Setup name matching")
    @Nested
    inner class NameMatchingTest {
        @Test
        @DisplayName("An exact name matches regardless of case and punctuation")
        fun testExactName() {
            assertTrue(NameMatching.matches("Agnes Digital", "agnes digital"))
            assertTrue(NameMatching.matches("T.M. Opera O", "TM Opera O"))
        }

        @Test
        @DisplayName("A name with an OCR slip still matches")
        fun testOcrSlip() {
            assertTrue(NameMatching.matches("Maruzenskv", "Maruzensky"))
            assertTrue(NameMatching.matches("El Condor Pasa", "El Condor Pasa."))
        }

        @Test
        @DisplayName("A different name does not match")
        fun testDifferentName() {
            assertFalse(NameMatching.matches("Agnes Tachyon", "Agnes Digital"))
            assertFalse(NameMatching.matches("Super Creek", "Kitasan Black"))
        }

        @Test
        @DisplayName("An empty wanted text matches anything")
        fun testEmptyWanted() {
            assertTrue(NameMatching.matches("Anything", ""))
        }

        @Test
        @DisplayName("Lvl text is parsed to a number")
        fun testParseLevel() {
            assertEquals(50, NameMatching.parseLevel("Lvl 50"))
            assertEquals(45, NameMatching.parseLevel("Lv1 45"))
            assertNull(NameMatching.parseLevel("Lvl"))
        }
    }
}
