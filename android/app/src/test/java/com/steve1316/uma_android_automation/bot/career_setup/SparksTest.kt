package com.steve1316.uma_android_automation.bot.career_setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit tests for legacy parent spark scoring and the Smart spark wish list. */
@DisplayName("Sparks")
class SparksTest {
    private val wishList = listOf("SPD", "STA", "MILE", "MED")

    private fun spd(stars: Int) = Spark(SparkType.STAT, "SPD", stars)

    private fun sta(stars: Int) = Spark(SparkType.STAT, "STA", stars)

    private fun mile(stars: Int) = Spark(SparkType.APTITUDE, "MILE", stars)

    private fun uniq(stars: Int) = Spark(SparkType.UNIQUE, "UNIQ", stars)

    private fun candidate(index: Int, sparks: List<Spark>, isGuest: Boolean = false, affinity: Affinity? = null, isFree: Boolean = true) =
        LegacyCandidate(index, isGuest, sparks, affinity, isFree)

    @Test
    @DisplayName("Each wish-list spark scores its rank weight times its stars")
    fun testRankWeightTimesStars() {
        // SPD is rank 1 of 4 so weight 4, MILE is rank 3 so weight 2.
        assertEquals(4 * 3 + 2 * 2, SparkScoring.sparkScore(listOf(spd(3), mile(2)), wishList))
    }

    @Test
    @DisplayName("Sparks missing from the wish list score nothing")
    fun testUnlistedSparksIgnored() {
        assertEquals(0, SparkScoring.sparkScore(listOf(Spark(SparkType.STAT, "GUTS", 3), Spark(SparkType.APTITUDE, "DIRT", 3)), wishList))
    }

    @Test
    @DisplayName("The unique spark adds nothing to the score")
    fun testUniqueIgnored() {
        assertEquals(0, SparkScoring.sparkScore(listOf(uniq(3)), wishList))
    }

    @Test
    @DisplayName("Unique stars break a tie between equal spark scores")
    fun testUniqueTieBreak() {
        val candidates = listOf(candidate(0, listOf(spd(3), uniq(1))), candidate(1, listOf(spd(3), uniq(3))))
        assertEquals(listOf(1, 0), SparkScoring.shortlist(candidates, wishList, size = 2).map { it.index })
        assertEquals(1, SparkScoring.pick(candidates, wishList)?.index)
    }

    @Test
    @DisplayName("Labels match regardless of case and stray spaces")
    fun testLabelMatchingIsLoose() {
        assertEquals(4 * 2, SparkScoring.sparkScore(listOf(Spark(SparkType.STAT, " spd ", 2)), wishList))
    }

    @Test
    @DisplayName("Star counts outside 0-3 are clamped")
    fun testStarsClamped() {
        assertEquals(4 * 3, SparkScoring.sparkScore(listOf(spd(7)), wishList))
        assertEquals(0, SparkScoring.sparkScore(listOf(spd(-1)), wishList))
    }

    @Test
    @DisplayName("Affinity adds its bonus on top of the spark score")
    fun testAffinityBonus() {
        assertEquals(4 * 3 + 6, SparkScoring.totalScore(candidate(0, listOf(spd(3)), affinity = Affinity.DOUBLE_CIRCLE), wishList))
        assertEquals(4 * 3 + 3, SparkScoring.totalScore(candidate(0, listOf(spd(3)), affinity = Affinity.CIRCLE), wishList))
        assertEquals(4 * 3 + 1, SparkScoring.totalScore(candidate(0, listOf(spd(3)), affinity = Affinity.TRIANGLE), wishList))
        assertEquals(4 * 3, SparkScoring.totalScore(candidate(0, listOf(spd(3)), affinity = Affinity.NONE), wishList))
        assertEquals(4 * 3, SparkScoring.totalScore(candidate(0, listOf(spd(3)), affinity = null), wishList))
    }

    @Test
    @DisplayName("The shortlist keeps the top N by spark score, ties kept in scan order")
    fun testShortlist() {
        val candidates =
            listOf(
                candidate(0, listOf(sta(1))),
                candidate(1, listOf(spd(3))),
                candidate(2, listOf(sta(3))),
                candidate(3, listOf(spd(3))),
            )
        assertEquals(listOf(1, 3), SparkScoring.shortlist(candidates, wishList, size = 2).map { it.index })
    }

    @Test
    @DisplayName("The pick is the highest total, with affinity able to flip the order")
    fun testPickUsesAffinity() {
        val candidates =
            listOf(
                candidate(0, listOf(spd(3)), affinity = Affinity.NONE),
                candidate(1, listOf(spd(2)), affinity = Affinity.DOUBLE_CIRCLE),
            )
        assertEquals(1, SparkScoring.pick(candidates, wishList)?.index)
    }

    @Test
    @DisplayName("Paid guests are skipped")
    fun testGuestRules() {
        val candidates =
            listOf(
                candidate(0, listOf(spd(3)), isGuest = true),
                candidate(1, listOf(spd(3), sta(3)), isGuest = true, isFree = false),
                candidate(2, listOf(spd(1))),
            )
        assertEquals(0, SparkScoring.pick(candidates, wishList)?.index)
        assertEquals(2, SparkScoring.pick(candidates.drop(1), wishList)?.index)
    }

    @Test
    @DisplayName("No candidates means no pick")
    fun testEmpty() {
        assertNull(SparkScoring.pick(emptyList(), wishList))
    }

    /** Unit tests for the Smart Auto-Select spark wish list derived from a trainee outfit's game data. */
    @DisplayName("Smart sparks")
    @Nested
    inner class SmartSparksTest {
        /**
         * Builds aptitudes from the game's ten-grade string.
         *
         * @param grades Turf, Dirt, Sprint, Mile, Medium, Long, Front, Pace, Late, End as one letter each.
         * @returns The aptitudes keyed like the catalog.
         */
        private fun aptitudes(grades: String): Map<String, String> =
            listOf("turf", "dirt", "sprint", "mile", "medium", "long", "front", "pace", "late", "end").zip(grades.map { it.toString() }).toMap()

        /**
         * Builds growth bonuses.
         *
         * @param values Speed, Stamina, Power, Guts and Wit bonuses in percent.
         * @returns The growth keyed like the catalog.
         */
        private fun growth(vararg values: Int): Map<String, Int> = listOf("speed", "stamina", "power", "guts", "wit").zip(values.toList()).toMap()

        @Test
        @DisplayName("Inari One [Edomurasaki] by her grades alone wants Mile, then Speed, Stamina and Power")
        fun testInariOne() {
            assertEquals(listOf("MILE", "SPD", "STA", "PWR"), SmartSparks.wishList(aptitudes("AAFBAAGBBA"), growth(0, 10, 20, 0, 0)))
        }

        @Test
        @DisplayName("A trainee with a Long goal race wants Stamina ahead of Speed")
        fun testLongGoalPutsStaminaFirst() {
            // Inari One must win Tenno Sho (Spring) and Arima Kinen, both Long. Lost on 388 Stamina on device, and Game8 recommends all Stamina sparks for her.
            assertEquals(listOf("MILE", "STA", "SPD", "PWR"), SmartSparks.wishList(aptitudes("AAFBAAGBBA"), growth(0, 10, 20, 0, 0), hasLongGoal = true))
        }

        @Test
        @DisplayName("A Long goal does not move Stamina first for a trainee too weak at Long to win it")
        fun testLongGoalIgnoredWhenLongIsWeak() {
            // Haru Urara's goals include a Long race, but her Long grade is G, so Stamina sparks would not change the result.
            assertEquals(listOf("MILE", "SPD", "GUTS", "PWR"), SmartSparks.wishList(aptitudes("GAABGGGGAB"), growth(0, 0, 10, 20, 0), hasLongGoal = true))
        }

        @Test
        @DisplayName("A dirt sprinter skips Stamina and follows its growth bonuses")
        fun testDirtSprinter() {
            // Haru Urara [Bestest Prize]: Turf G is out of reach, Mile B is worth raising, and the best distance is Sprint.
            assertEquals(listOf("MILE", "SPD", "GUTS", "PWR"), SmartSparks.wishList(aptitudes("GAABGGGGAB"), growth(0, 0, 10, 20, 0)))
        }

        @Test
        @DisplayName("A stayer whose best style is below A also wants that style")
        fun testStayerStyle() {
            assertEquals(listOf("LONG", "PACE", "SPD", "STA", "WIT", "PWR"), SmartSparks.wishList(aptitudes("AGGCABCBGG"), growth(0, 20, 0, 0, 10)))
        }

        @Test
        @DisplayName("A surface graded B is worth raising, one graded C or lower is not")
        fun testSurface() {
            assertEquals(listOf("DIRT", "SPD", "PWR"), SmartSparks.wishList(aptitudes("ABGAGGAGGG"), growth(0, 0, 0, 0, 0)))
            assertEquals(listOf("SPD", "PWR"), SmartSparks.wishList(aptitudes("ACGAGGAGGG"), growth(0, 0, 0, 0, 0)))
        }
    }
}
