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
    @Test
    @DisplayName("A bigger Training Gain wins over a smaller one, even for a lower-priority stat")
    fun prefersBiggerTrainingGain() {
        // Reported on device: the bot bought "Training Guts Gain +1" over "Training Wit Gain +2". Both cards match the same categories, so the
        // ranks tied and the pick fell through to screen position. Guts also outranks Wit in this priority, so only the magnitude can decide.
        val options =
            listOf(
                song("Training Guts Gain +1 Support Chain Event Frequency Lvl +1", 0),
                song("Training Wit Gain +2 Support Chain Event Frequency Lvl +1", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)

        // Swapping the rows must not change the answer, which is what proves the choice no longer rides on screen position.
        val swapped =
            listOf(
                song("Training Wit Gain +2 Support Chain Event Frequency Lvl +1", 0),
                song("Training Guts Gain +1 Support Chain Event Frequency Lvl +1", 1),
            )
        assertEquals(0, chooseLessonPurchase(swapped, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }

    @Test
    @DisplayName("A +10% Training Effectiveness song beats a +5% one carrying a better stat")
    fun prefersBiggerTrainingEffectiveness() {
        // "Full Speed Ahead! Umadol Power" (Speed +22, +5%) used to beat "Fanfare for the Future!" (Guts +26, +10%) because the percentage was
        // never read, so the tie fell to the raw stat riding along on the card and Speed outranks Guts.
        val options =
            listOf(
                song("Speed +22 Friendship Training Effectiveness +5%", 0),
                song("Guts +26 Friendship Training Effectiveness +10%", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }

    @Test
    @DisplayName("A bigger gain on a Song outranks the Technique preference")
    fun magnitudeBeatsTechniquePreference() {
        val options =
            listOf(
                tech("Training Speed Gain +1", 0),
                song("Training Speed Gain +2", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }

    @Test
    @DisplayName("Equal Training Gain magnitudes fall through to the stat prioritization")
    fun equalTrainingGainFallsToStat() {
        val options =
            listOf(
                song("Training Wit Gain +2", 0),
                song("Training Speed Gain +2", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }

    @Test
    @DisplayName("A named training gain is parsed without becoming a raw stat gain")
    fun parsesTrainingStatGain() {
        assertEquals(StatName.WIT to 2, parseTrainingStatGain("Training Wit Gain +2"))
        assertNull(parseTrainingStatGain("Speed +22"))
        // The named gain must never leak into Stat Gains, or the category ranks would shift for every Training Gain card.
        assertFalse(detectLessonCategories("Training Wit Gain +2").contains(LessonEffectCategory.STAT_GAINS))
        assertTrue(detectLessonCategories("Training Wit Gain +2").contains(LessonEffectCategory.TRAINING_GAIN))
    }

    @Test
    @DisplayName("Magnitude survives OCR losing a leading word, so a card never scores zero for a category it matched")
    fun magnitudeToleratesLostLabel() {
        // Detection accepts a bare "hint" or a bare "training" plus "gain", so measurement has to accept the same. When it did not, a card read as
        // "Hint Lvl +3" counted as a Skill Hint, scored zero, and fell back to screen position - the very failure this ordering exists to prevent.
        val hints =
            listOf(
                tech("Hint Lvl +1", 0),
                tech("Hint Lvl +3", 1),
            )
        assertEquals(1, chooseLessonPurchase(hints, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)

        val gains =
            listOf(
                song("Training Gain +1", 0),
                song("Training Gain +2", 1),
            )
        assertEquals(1, chooseLessonPurchase(gains, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }

    @Test
    @DisplayName("Effect text captured from a real run scores the way the log shows it should")
    fun scoresRealDeviceEffectText() {
        // Every string here is verbatim from a Grand Live run, OCR noise included. The bare "+10%" is a card whose Friendship Training Effectiveness
        // label was lost entirely, and it still has to outrank a Training Gain song.
        val options =
            listOf(
                song("Training Skill Pt Gain +2 Specialty Priority +5", 0),
                song("Guts +26 +10%", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)

        // The post-concert overlay trails the real effect and must not mask it.
        val expired = "Training Skill Pt Gain +3 Support Chain Event Frequency Lvl +1 O This bonus won't take effect, as the concert is over."
        assertTrue(detectLessonCategories(expired).contains(LessonEffectCategory.TRAINING_GAIN))
        assertTrue(detectLessonCategories(expired).contains(LessonEffectCategory.SUPPORT_EVENTS))
    }
    @Test
    @DisplayName("Skill point cards are recognized through the abbreviated wording the game actually prints")
    fun recognizesAbbreviatedSkillPoints() {
        // Observed on device: the game only ever prints "Skill Pts +12" or "Training Skill Pt Gain +3", never "Skill Point". The old keyword looked for
        // the spelled-out form, so a card granting only skill points matched no category at all and fell through to screen position.
        assertTrue(detectLessonCategories("Skill Pts +12").contains(LessonEffectCategory.SKILL_HINTS))
        assertTrue(detectLessonCategories("Training Skill Pt Gain +3").contains(LessonEffectCategory.SKILL_HINTS))

        val options =
            listOf(
                tech("Skill Pts +5", 0),
                tech("Skill Pts +12", 1),
            )
        assertEquals(1, chooseLessonPurchase(options, priority, forceMaxHype = false, hypeMaxed = true)?.rowIndex)
    }
}
