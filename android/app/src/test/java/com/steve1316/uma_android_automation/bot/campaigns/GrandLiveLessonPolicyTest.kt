package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Grand Live Lessons purchase policy (priority-weighted stat techniques, meaningful passives, force-max-Hype).
 * Card detection and the buy tap are verified on device. The ribbon-to-card window and the "what to buy next" ordering are covered here.
 * Every option passed in is already purchasable - the policy only orders them.
 */
@DisplayName("Grand Live Lessons purchase policy")
class GrandLiveLessonPolicyTest {
    private val priority = listOf(StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.GUTS, StatName.WIT)

    private fun tech(effect: String, row: Int) = LessonOption(LessonKind.TECHNIQUE, name = "", effectText = effect, purchasable = true, rowIndex = row)

    private fun song(effect: String, row: Int) = LessonOption(LessonKind.SONG, name = "", effectText = effect, purchasable = true, rowIndex = row)

    @Test
    @DisplayName("Buys a basic stat technique for the top-priority stat")
    fun buysTopPriorityStatTechnique() {
        val options =
            listOf(
                tech("Guts +5", 0),
                tech("Speed +5", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(1, chosen?.rowIndex)
        assertEquals(LessonKind.TECHNIQUE, chosen?.kind)
    }

    @Test
    @DisplayName("Prefers the higher-priority stat when two stat techniques are learnable")
    fun prefersHigherPriorityStat() {
        val options =
            listOf(
                tech("Stamina +5", 0),
                tech("Speed +5", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(1, chosen?.rowIndex)
    }

    @Test
    @DisplayName("Buys any learnable card (tokens are not hoarded), even a low-value one")
    fun buysAnyLearnableCard() {
        // A technique for a stat outside the priority list still spends tokens and refreshes the list, so it is bought when it is the only option.
        val options = listOf(tech("Wit +5", 0))
        val chosen = chooseLessonPurchase(options, listOf(StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.GUTS), forceMaxHype = false, hypeMaxed = true)
        assertEquals(0, chosen?.rowIndex)
    }

    @Test
    @DisplayName("A meaningful-effect song is bought even when Hype is maxed")
    fun buysMeaningfulSong() {
        val options = listOf(song("Friendship Training Effectiveness +5%", 0))
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(0, chosen?.rowIndex)
    }

    @Test
    @DisplayName("A Technique is preferred over a Song of equal passive value")
    fun prefersTechniqueOnTie() {
        val options =
            listOf(
                song("Training Speed Gain +1", 0),
                tech("Training Power Gain +1", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(1, chosen?.rowIndex)
        assertEquals(LessonKind.TECHNIQUE, chosen?.kind)
    }

    @Test
    @DisplayName("Force-max-Hype buys a song first, ahead of a technique")
    fun forcesHypeSongFirst() {
        val options =
            listOf(
                tech("Training Power Gain +1", 0),
                song("Support Chain Event Frequency Lvl +1", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = true, hypeMaxed = false)
        assertEquals(1, chosen?.rowIndex)
        assertEquals(LessonKind.SONG, chosen?.kind)
    }

    @Test
    @DisplayName("Empty list returns null")
    fun emptyReturnsNull() {
        assertNull(chooseLessonPurchase(emptyList(), priority, forceMaxHype = true, hypeMaxed = false))
    }

    @Test
    @DisplayName("Energy gain is parsed only from an Energy effect")
    fun parsesEnergyGain() {
        assertEquals(20, parseEnergyGain("Energy +20 Skill Hint Lvl +3 (Medium)"))
        assertNull(parseEnergyGain("Speed +5"))
    }

    @Test
    @DisplayName("Projected energy is parsed from the dialog's '<new> / <cap>' readout")
    fun parsesProjectedEnergy() {
        assertEquals(78 to 100, parseProjectedEnergy("78 / 100"))
        assertEquals(100 to 100, parseProjectedEnergy("100/100"))
        assertNull(parseProjectedEnergy("no number here"))
    }

    @Test
    @DisplayName("A misread energy cap still parses, so the overflow guard is not silently disabled")
    fun parsesProjectedEnergyWithMisreadCap() {
        // Observed on device: the cap OCR'd as "104", which the old hardcoded "/100" pattern could not match at all.
        assertEquals(87 to 104, parseProjectedEnergy("87 /104"))
    }

    @Test
    @DisplayName("Reordering categories flips the choice: Skill Hints ranked above Stat Gains buys the hint")
    fun reorderingFlipsChoice() {
        val options =
            listOf(
                tech("Speed +5", 0),
                tech("Skill Hint Lvl +1 (Medium)", 1),
            )
        val defaultChosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(0, defaultChosen?.rowIndex)

        val hintFirst = listOf(LessonEffectCategory.SKILL_HINTS, LessonEffectCategory.STAT_GAINS)
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true, categoryOrder = hintFirst)
        assertEquals(1, chosen?.rowIndex)
    }

    @Test
    @DisplayName("Unranked Energy loses to any ranked card but is bought when it is the only option")
    fun unrankedEnergyIsLastResort() {
        val energy = tech("Energy +20", 0)
        val weakStat = tech("Wit +5", 1)
        val chosen = chooseLessonPurchase(listOf(energy, weakStat), priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(1, chosen?.rowIndex)

        val onlyEnergy = chooseLessonPurchase(listOf(energy), priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(0, onlyEnergy?.rowIndex)
    }

    @Test
    @DisplayName("Ranking Energy first makes an Energy card outrank a passive")
    fun energyRankedFirstWins() {
        val options =
            listOf(
                tech("Energy +20", 0),
                tech("Training Power Gain +1", 1),
            )
        val energyFirst = listOf(LessonEffectCategory.ENERGY, LessonEffectCategory.TRAINING_GAIN)
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true, categoryOrder = energyFirst)
        assertEquals(0, chosen?.rowIndex)
    }

    @Test
    @DisplayName("Force-max-Hype still buys a song even when its category ranks below the technique's")
    fun hypeForcingOverridesOrder() {
        val options =
            listOf(
                tech("Training Power Gain +1", 0),
                song("Speed +5", 1),
            )
        val gainFirst = listOf(LessonEffectCategory.TRAINING_GAIN, LessonEffectCategory.STAT_GAINS)
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = true, hypeMaxed = false, categoryOrder = gainFirst)
        assertEquals(LessonKind.SONG, chosen?.kind)
    }

    @Test
    @DisplayName("The top-ranked category beats two lower-ranked ones stacked on one card")
    fun topRankBeatsStackedLowerRanks() {
        // Observed on device: a Training Gain + Support Events song used to outscore a Training Effectiveness song because the weights summed.
        val options =
            listOf(
                song("Power +22 Friendship Training Effectiveness +5%", 0),
                song("Training Stamina Gain +1 Support Chain Event Frequency Lvl +1", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(0, chosen?.rowIndex)
    }

    @Test
    @DisplayName("A card matching more categories wins when the better-ranked ones tie")
    fun moreCategoriesWinsOnEqualPrefix() {
        val options =
            listOf(
                song("Training Speed Gain +1", 0),
                song("Training Speed Gain +1 Support Chain Event Frequency Lvl +1", 1),
            )
        val chosen = chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)
        assertEquals(1, chosen?.rowIndex)
    }

    @Test
    @DisplayName("Training Effectiveness is inferred when OCR truncated the label to a bare percentage")
    fun inferTrainingEffectivenessFromBarePercent() {
        // Observed on device: "Precious Treasure Box" reads as "Speed +26" / "+10%", losing the label entirely.
        assertTrue(detectLessonCategories("Speed +26 +10%").contains(LessonEffectCategory.TRAINING_EFFECTIVENESS))
        assertTrue(detectLessonCategories("Guts +26 +10%").contains(LessonEffectCategory.TRAINING_EFFECTIVENESS))
        // A small stat gain with no percentage is an ordinary technique, not a Training Effectiveness song.
        assertFalse(detectLessonCategories("Speed +5").contains(LessonEffectCategory.TRAINING_EFFECTIVENESS))
    }

    @Test
    @DisplayName("The post-concert overlay is stripped instead of masking the real effect")
    fun stripsConcertOverOverlay() {
        val effect = "Wit +22 O This bonus won't take effect, as the concert is over."
        assertEquals(StatName.WIT to 22, parseStatGain(effect))
        assertTrue(detectLessonCategories(effect).contains(LessonEffectCategory.TRAINING_EFFECTIVENESS))
        assertTrue(detectLessonCategories(effect).contains(LessonEffectCategory.STAT_GAINS))
    }

    @Test
    @DisplayName("Sought-after means matching a category in the top 2 ranks")
    fun soughtAfterFollowsTopRanks() {
        assertTrue(isSoughtAfter("Friendship Training Effectiveness +10%", DEFAULT_LESSON_EFFECT_PRIORITY))
        assertFalse(isSoughtAfter("Speed +5", DEFAULT_LESSON_EFFECT_PRIORITY))

        val statFirst = listOf(LessonEffectCategory.STAT_GAINS, LessonEffectCategory.SKILL_HINTS, LessonEffectCategory.TRAINING_GAIN)
        assertTrue(isSoughtAfter("Speed +5", statFirst))
        assertFalse(isSoughtAfter("Training Power Gain +1", statFirst))
    }

    @Test
    @DisplayName("Each Learnable ribbon maps to the one card whose cost pill sits below it")
    fun ribbonMapsToItsOwnCard() {
        // Measured on a 1080x1920 device: cost pills at 673 / 1081 / 1489, ribbons 338px above each.
        val ribbonYs = listOf(335.0, 743.0, 1152.0)
        val anchors = listOf(673.0, 1081.0, 1489.0)
        anchors.forEach { assertTrue(hasLearnableRibbon(ribbonYs, it, LESSON_CARD_HEIGHT)) }
        // A card whose ribbon is missing stays locked even though its neighbours have one.
        assertFalse(hasLearnableRibbon(listOf(335.0), 1081.0, LESSON_CARD_HEIGHT))
        assertFalse(hasLearnableRibbon(emptyList(), 673.0, LESSON_CARD_HEIGHT))
    }

    @Test
    @DisplayName("The shipped look-back window gives every card exactly one ribbon")
    fun ribbonWindowClaimsExactlyOneRibbonPerCard() {
        val ribbonYs = listOf(335.0, 743.0, 1152.0)
        val anchors = listOf(673.0, 1081.0, 1489.0)
        anchors.forEach { anchor -> assertEquals(1, ribbonYs.count { it in (anchor - LESSON_CARD_HEIGHT)..anchor }) }
        // Short of the 338px ribbon offset a card misses its own ribbon, and from 338 + the 408px pitch it also claims the ribbon of the card above.
        assertFalse(hasLearnableRibbon(ribbonYs, 673.0, 337))
        assertEquals(2, ribbonYs.count { it in (1081.0 - 746)..1081.0 })
    }
}
