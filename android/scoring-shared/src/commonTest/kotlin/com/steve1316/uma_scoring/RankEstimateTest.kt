package com.steve1316.uma_scoring

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the estimated-rank math against reference values computed independently from the UmaTools calculator source (rating-shared.js / calculator.js). If these drift, the
 * port no longer matches the reference calculator.
 */
class RankEstimateTest {
    // Aptitudes matching the reference trainee used to compute the expected values.
    private val referenceAptitudes =
        RankAptitudes(
            turf = "A",
            dirt = "G",
            sprint = "E",
            mile = "A",
            medium = "A",
            long = "B",
            front = "F",
            pace = "A",
            late = "A",
            end = "A",
        )

    @Test
    fun statScoreMatchesReferenceCurve() {
        assertEquals(0, statScore(0))
        assertEquals(0, statScore(-50))
        assertEquals(66, statScore(100))
        assertEquals(181, statScore(200))
        assertEquals(352, statScore(300))
        assertEquals(847, statScore(500))
        assertEquals(1773, statScore(790))
        assertEquals(1957, statScore(838))
        assertEquals(2635, statScore(1000))
        assertEquals(3841, statScore(1200))
        assertEquals(3849, statScore(1201))
        assertEquals(6773, statScore(1500))
        assertEquals(14280, statScore(2000))
        assertEquals(23905, statScore(2500))
        // Values above the max clamp down to the 2500 score.
        assertEquals(23905, statScore(3000))
    }

    @Test
    fun oguriStatSumMatchesReference() {
        val sum = statScore(790) + statScore(378) + statScore(838) + statScore(312) + statScore(752)
        assertEquals(6271, sum)
    }

    @Test
    fun uniqueBonusUsesStarBasedMultiplier() {
        assertEquals(850, uniqueBonus(3, 5))
        assertEquals(600, uniqueBonus(2, 5))
        assertEquals(360, uniqueBonus(1, 3))
        assertEquals(0, uniqueBonus(5, 0))
        // Unknown star (0) defaults to the x170 multiplier.
        assertEquals(680, uniqueBonus(0, 4))
    }

    @Test
    fun scoreToRankLabelHitsTierBoundaries() {
        assertEquals("G", scoreToRankLabel(0))
        assertEquals("G", scoreToRankLabel(299))
        assertEquals("G+", scoreToRankLabel(300))
        assertEquals("B", scoreToRankLabel(6500))
        assertEquals("B", scoreToRankLabel(8199))
        assertEquals("B+", scoreToRankLabel(8200))
        assertEquals("A", scoreToRankLabel(10000))
        assertEquals("SS", scoreToRankLabel(19199))
        assertEquals("SS+", scoreToRankLabel(19200))
        assertEquals("UG", scoreToRankLabel(19600))
        assertEquals("UG1", scoreToRankLabel(20000))
        // Above the top tier clamps to the highest label.
        assertEquals("LS24", scoreToRankLabel(500000))
    }

    @Test
    fun rankLabelToImageIndexMatchesLadderPosition() {
        assertEquals(0, rankLabelToImageIndex("G"))
        assertEquals(1, rankLabelToImageIndex("G+"))
        assertEquals(7, rankLabelToImageIndex("D+"))
        assertEquals(11, rankLabelToImageIndex("B+"))
        assertEquals(12, rankLabelToImageIndex("A"))
        assertEquals(14, rankLabelToImageIndex("S"))
        assertEquals(16, rankLabelToImageIndex("SS"))
        assertEquals(17, rankLabelToImageIndex("SS+"))
        assertEquals(18, rankLabelToImageIndex("UG"))
        assertEquals(19, rankLabelToImageIndex("UG1"))
        assertEquals(88, rankLabelToImageIndex("US"))
        assertEquals(-1, rankLabelToImageIndex("nonsense"))
    }

    @Test
    fun evaluateSkillScoreAppliesBucketMultipliers() {
        // No checkType -> flat base.
        assertEquals(200, evaluateSkillScore(200, "", referenceAptitudes))
        assertEquals(217, evaluateSkillScore(217, "", referenceAptitudes))
        // Single role scales by the trainee's aptitude bucket.
        assertEquals(220, evaluateSkillScore(200, "Late", referenceAptitudes))
        assertEquals(180, evaluateSkillScore(200, "Late", referenceAptitudes.copy(late = "C")))
        assertEquals(140, evaluateSkillScore(200, "Late", referenceAptitudes.copy(late = "G")))
        // Multi-role in the same group takes the best multiplier (Medium A = 1.1 beats Long B = 0.9).
        assertEquals(220, evaluateSkillScore(200, "Medium/Long", referenceAptitudes))
        // Multi-role across groups multiplies the best per group (Mile A 1.1 x Turf A 1.1 = 1.21).
        assertEquals(242, evaluateSkillScore(200, "Mile/Turf", referenceAptitudes))
    }

    @Test
    fun estimateRankMatchesReferenceTrainee() {
        val skills =
            listOf(
                SkillScoreInput(200, "Late"),
                SkillScoreInput(217, ""),
                SkillScoreInput(508, "Late"),
            )
        val result = estimateRank(790, 378, 838, 312, 752, skills, referenceAptitudes, starLevel = 3, uniqueLevel = 3)
        assertEquals(6271, result.statScore)
        assertEquals(996, result.skillScore)
        assertEquals(510, result.uniqueBonus)
        assertEquals(7777, result.totalScore)
        assertEquals("B", result.rankLabel)
        assertEquals(10, result.rankImageIndex)
    }
}
