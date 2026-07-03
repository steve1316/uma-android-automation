package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.bot.Training.Companion.calculateMiscScore
import com.steve1316.uma_android_automation.bot.Training.Companion.calculateRawTrainingScore
import com.steve1316.uma_android_automation.bot.Training.Companion.calculateRelationshipScore
import com.steve1316.uma_android_automation.bot.Training.Companion.calculateStatEfficiencyScore
import com.steve1316.uma_android_automation.bot.Training.Companion.computeEffectiveBlacklist
import com.steve1316.uma_android_automation.bot.Training.Companion.getCurrentStatCap
import com.steve1316.uma_android_automation.bot.Training.Companion.getFinaleStatBonus
import com.steve1316.uma_android_automation.bot.Training.Companion.getScenarioStatCap
import com.steve1316.uma_android_automation.bot.Training.Companion.getRemainingFinaleRaces
import com.steve1316.uma_android_automation.bot.Training.Companion.levelBoostMultiplier
import com.steve1316.uma_android_automation.bot.Training.Companion.scoreFriendshipTraining
import com.steve1316.uma_android_automation.bot.Training.Companion.scoreUnityCupTraining
import com.steve1316.uma_android_automation.bot.Training.Companion.scoringConstantsFromMap
import com.steve1316.uma_android_automation.bot.Training.Companion.softCapEffectivenessMultiplier
import com.steve1316.uma_android_automation.bot.Training.TrainingConfig
import com.steve1316.uma_android_automation.bot.Training.TrainingOption
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.utils.CustomImageUtils.BarFillResult
import com.steve1316.uma_scoring.TrainingScoringConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Unit tests for the Training scoring functions.
 *
 * These tests verify the correctness of the scoring algorithms used to determine
 * the best training option based on various game state configurations.
 */
@DisplayName("Training Scoring Tests")
class TrainingScoringTest {
    /**
     * Returns the stat targets Map for the given distance.
     * Order: Speed, Stamina, Power, Guts, Wit
     *
     * @param distance The distance string: "Sprint", "Mile", "Medium", or "Long".
     *
     * @return Map<StatName, Int> of stat targets for that distance.
     */
    private fun getStatTargetsForDistance(distance: String): Map<StatName, Int> {
        val targets =
            when (distance) {
                "Sprint" -> intArrayOf(900, 300, 600, 300, 300)
                "Mile" -> intArrayOf(900, 300, 600, 300, 300)
                "Medium" -> intArrayOf(800, 450, 550, 300, 300)
                "Long" -> intArrayOf(700, 600, 450, 300, 300)
                else -> intArrayOf(600, 600, 600, 300, 300)
            }
        return mapOf(
            StatName.SPEED to targets[0],
            StatName.STAMINA to targets[1],
            StatName.POWER to targets[2],
            StatName.GUTS to targets[3],
            StatName.WIT to targets[4],
        )
    }

    /**
     * Converts an IntArray of stat gains (Speed, Stamina, Power, Guts, Wit) to a Map<StatName, Int>.
     *
     * @param gains The stat gains as an IntArray.
     *
     * @return Map<StatName, Int> of stat gains.
     */
    private fun statGainsToMap(gains: IntArray): Map<StatName, Int> {
        return mapOf(
            StatName.SPEED to gains[0],
            StatName.STAMINA to gains[1],
            StatName.POWER to gains[2],
            StatName.GUTS to gains[3],
            StatName.WIT to gains[4],
        )
    }

    @Test
    @DisplayName("Effective blacklist is empty in the Pre-Debut / Junior friendship window and the configured blacklist otherwise")
    fun computeEffectiveBlacklistWindow() {
        val blacklist = listOf<StatName?>(StatName.STAMINA, StatName.GUTS)
        // Friendship window (Pre-Debut / Junior): blacklist ignored so bonds can still be built on blacklisted facilities.
        assertTrue(computeEffectiveBlacklist(blacklist, bIsPreDebut = true, year = DateYear.JUNIOR).isEmpty(), "pre-debut -> ignore blacklist")
        assertTrue(computeEffectiveBlacklist(blacklist, bIsPreDebut = false, year = DateYear.JUNIOR).isEmpty(), "junior -> ignore blacklist")
        // Outside the window: blacklist respected.
        assertEquals(blacklist, computeEffectiveBlacklist(blacklist, bIsPreDebut = false, year = DateYear.CLASSIC), "classic -> respect blacklist")
        assertEquals(blacklist, computeEffectiveBlacklist(blacklist, bIsPreDebut = false, year = DateYear.SENIOR), "senior -> respect blacklist")
    }

    /**
     * Converts a Map<String, Int> of stats to a Map<StatName, Int>.
     *
     * @param stats The stats as a Map<String, Int>.
     *
     * @return Map<StatName, Int> of stats.
     */
    private fun statsToMap(stats: Map<String, Int>): Map<StatName, Int> {
        return mapOf(
            StatName.SPEED to (stats["Speed"] ?: 0),
            StatName.STAMINA to (stats["Stamina"] ?: 0),
            StatName.POWER to (stats["Power"] ?: 0),
            StatName.GUTS to (stats["Guts"] ?: 0),
            StatName.WIT to (stats["Wit"] ?: 0),
        )
    }

    // Helper function to create a default TrainingOption for testing.
    private fun createDefaultTrainingOption(
        name: StatName = StatName.SPEED,
        statGains: Map<StatName, Int> = statGainsToMap(intArrayOf(15, 0, 5, 0, 0)),
        failureChance: Int = 5,
        relationshipBars: ArrayList<BarFillResult> = arrayListOf(),
        numRainbow: Int = 0,
        extras: Map<String, Any?> = emptyMap(),
    ): TrainingOption {
        return TrainingOption(
            name = name,
            statGains = statGains,
            failureChance = failureChance,
            relationshipBars = relationshipBars,
            numRainbow = numRainbow,
            extras = extras,
        )
    }

    // Helper function to create a default TrainingConfig for testing.
    private fun createDefaultConfig(
        trainingOptions: List<TrainingOption> = listOf(createDefaultTrainingOption()),
        currentStats: Map<StatName, Int> =
            mapOf(
                StatName.SPEED to 120,
                StatName.STAMINA to 120,
                StatName.POWER to 120,
                StatName.GUTS to 120,
                StatName.WIT to 120,
            ),
        statPrioritization: List<StatName> = listOf(StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.WIT, StatName.GUTS),
        preferredDistance: String = "Medium",
        currentDate: GameDate = GameDate(year = DateYear.JUNIOR, month = DateMonth.JANUARY, phase = DatePhase.EARLY),
        scenario: String = "URA Finale",
        enableRainbowTrainingBonus: Boolean = true,
        blacklist: List<StatName?> = emptyList(),
        disableTrainingOnMaxedStat: Boolean = false,
        skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { 0 },
        enablePrioritizeSkillHints: Boolean = false,
        statsTrainedOverBuffer: Set<StatName> = emptySet(),
        statCaps: Map<StatName, Int> = emptyMap(),
    ): TrainingConfig {
        return TrainingConfig(
            currentStats = currentStats,
            statPrioritization = statPrioritization,
            eventChoiceStatPriority = statPrioritization,
            summerTrainingStatPriority = statPrioritization,
            statTargets = getStatTargetsForDistance(preferredDistance),
            currentDate = currentDate,
            scenario = scenario,
            enableRainbowTrainingBonus = enableRainbowTrainingBonus,
            blacklist = blacklist,
            disableTrainingOnMaxedStat = disableTrainingOnMaxedStat,
            trainingOptions = trainingOptions,
            skillHintsPerLocation = skillHintsPerLocation,
            enablePrioritizeSkillHints = enablePrioritizeSkillHints,
            statsTrainedOverBuffer = statsTrainedOverBuffer,
            statCaps = statCaps,
        )
    }

    @Test
    @DisplayName("Speed rainbow training should be selected despite high current stat")
    fun testSpeedRainbowTrainingSelectedWithHighStats() {
        // Current stats with Speed already at 1100.
        val currentStats =
            mapOf(
                StatName.SPEED to 1100,
                StatName.STAMINA to 700,
                StatName.POWER to 800,
                StatName.GUTS to 400,
                StatName.WIT to 300,
            )

        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(60, 0, 30, 0, 0)),
                numRainbow = 1,
            )
        val staminaTraining =
            createDefaultTrainingOption(
                name = StatName.STAMINA,
                statGains = statGainsToMap(intArrayOf(0, 15, 0, 7, 0)),
                numRainbow = 0,
            )
        val powerTraining =
            createDefaultTrainingOption(
                name = StatName.POWER,
                statGains = statGainsToMap(intArrayOf(0, 25, 45, 0, 0)),
                numRainbow = 1,
            )
        val gutsTraining =
            createDefaultTrainingOption(
                name = StatName.GUTS,
                statGains = statGainsToMap(intArrayOf(0, 5, 0, 10, 0)),
                numRainbow = 0,
            )
        val witTraining =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(5, 0, 0, 0, 10)),
                numRainbow = 0,
            )

        val trainingOptions = listOf(speedTraining, staminaTraining, powerTraining, gutsTraining, witTraining)

        val config =
            createDefaultConfig(
                trainingOptions = trainingOptions,
                currentStats = currentStats,
                preferredDistance = "Medium",
                currentDate = GameDate(year = DateYear.CLASSIC, month = DateMonth.JUNE, phase = DatePhase.EARLY),
                enableRainbowTrainingBonus = true,
            )

        // Speed training should have the highest score due to rainbow bonus.
        val scores = trainingOptions.associateWith { calculateRawTrainingScore(config, it) }
        val bestTraining = scores.maxByOrNull { it.value }?.key
        assertEquals(StatName.SPEED, bestTraining?.name, "Speed rainbow training should be selected despite high current stat")
        assertTrue(scores[speedTraining]!! > 0, "Speed training score should be positive")
    }

    // ============================================================================
    // scoreFriendshipTraining Tests
    // ============================================================================

    @Test
    @DisplayName("Blue and green bars are prioritized with priority order blue > green > orange")
    fun testBarColorPriority() {
        // Blue bar should contribute most, green next, orange nothing.
        val blueBar = BarFillResult(statName = StatName.SPEED, fillPercent = 50.0, filledSegments = 2, dominantColor = "blue")
        val greenBar = BarFillResult(statName = StatName.SPEED, fillPercent = 50.0, filledSegments = 2, dominantColor = "green")
        val orangeBar = BarFillResult(statName = StatName.SPEED, fillPercent = 50.0, filledSegments = 2, dominantColor = "orange")

        val trainingWithBlue =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(blueBar),
            )
        val trainingWithGreen =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(greenBar),
            )
        val trainingWithOrange =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(orangeBar),
            )

        val blueScore = scoreFriendshipTraining(trainingWithBlue)
        val greenScore = scoreFriendshipTraining(trainingWithGreen)
        val orangeScore = scoreFriendshipTraining(trainingWithOrange)

        // Verify priority order: blue > green > orange.
        assertTrue(blueScore > greenScore, "Blue friendship bar should score higher than green")
        assertTrue(greenScore > orangeScore, "Green friendship bar should score higher than orange")
        assertTrue(blueScore > orangeScore, "Blue friendship bar should score higher than orange")
    }

    @Test
    @DisplayName("No bars returns negative infinity")
    fun testNoBarsReturnsNegativeInfinity() {
        val trainingWithNoBars =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(),
            )

        val score = scoreFriendshipTraining(trainingWithNoBars)

        assertEquals(Double.NEGATIVE_INFINITY, score, "Empty relationship bars should return negative infinity")
    }

    @Test
    @DisplayName("Only orange bars returns zero score")
    fun testOnlyOrangeBarsReturnsZero() {
        val orangeBar1 = BarFillResult(statName = StatName.SPEED, fillPercent = 85.0, filledSegments = 3, dominantColor = "orange")
        val orangeBar2 = BarFillResult(statName = StatName.SPEED, fillPercent = 95.0, filledSegments = 3, dominantColor = "orange")
        val orangeBar3 = BarFillResult(statName = StatName.SPEED, fillPercent = 100.0, filledSegments = 4, dominantColor = "orange")

        val trainingWithOnlyOrange =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(orangeBar1, orangeBar2, orangeBar3),
            )

        val score = scoreFriendshipTraining(trainingWithOnlyOrange)

        assertEquals(0.0, score, "A zero score should be given with only orange bars for the training")
    }

    // ============================================================================
    // calculateStatEfficiencyScore Tests
    // ============================================================================

    @Test
    @DisplayName("Stats furthest behind target get highest multiplier")
    fun testStatsBehindTargetGetHigherMultiplier() {
        val currentStats =
            mapOf(
                StatName.SPEED to 300,
                StatName.STAMINA to 600,
                StatName.POWER to 300,
                StatName.GUTS to 300,
                StatName.WIT to 300,
            )

        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 15, 0, 0)),
            )
        val staminaTraining =
            createDefaultTrainingOption(
                name = StatName.STAMINA,
                statGains = statGainsToMap(intArrayOf(0, 45, 0, 20, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(speedTraining, staminaTraining),
                currentStats = currentStats,
                preferredDistance = "Medium",
            )

        val speedScore = calculateStatEfficiencyScore(config, speedTraining)
        val staminaScore = calculateStatEfficiencyScore(config, staminaTraining)

        assertTrue(speedScore > staminaScore, "Speed should score higher than Stamina due to being more behind target and is higher in the stat priority list")
    }

    @Test
    @DisplayName("High main stat gains get bonus multiplier")
    fun testHighMainStatGainsGetBonus() {
        val currentStats =
            mapOf(
                StatName.SPEED to 600,
                StatName.STAMINA to 600,
                StatName.POWER to 600,
                StatName.GUTS to 600,
                StatName.WIT to 600,
            )

        val highMainStatTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(35, 0, 10, 0, 0)),
            )
        val lowMainStatTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(20, 0, 10, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(highMainStatTraining, lowMainStatTraining),
                currentStats = currentStats,
            )

        val highScore = calculateStatEfficiencyScore(config, highMainStatTraining)
        val lowScore = calculateStatEfficiencyScore(config, lowMainStatTraining)

        val expectedRatio = 35.0 / 20.0
        val actualRatio = highScore / lowScore
        assertTrue(actualRatio > expectedRatio, "High main stat gains (30+) should get bonus beyond just stat gain difference")
    }

    @Test
    @DisplayName("Stat efficiency score does not depend on a spark stat list (regression: focusOnSparkStatTarget is removed)")
    fun testStatEfficiencyHasNoSparkBonus() {
        // Speed below 600 used to receive a 2.5x bonus when in focusOnSparkStatTarget. That bonus is gone.
        // Two configs differ only in whether Speed is below or above 600; without spark, the score difference
        // must come only from ratio multiplier and other documented factors, not a 2.5x spark.
        val belowSixHundred =
            mapOf(
                StatName.SPEED to 400,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )
        val aboveSixHundred = belowSixHundred + (StatName.SPEED to 700)

        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(10, 0, 0, 0, 0)),
            )

        val configBelow = createDefaultConfig(trainingOptions = listOf(speedTraining), currentStats = belowSixHundred)
        val configAbove = createDefaultConfig(trainingOptions = listOf(speedTraining), currentStats = aboveSixHundred)

        val belowScore = calculateStatEfficiencyScore(configBelow, speedTraining)
        val aboveScore = calculateStatEfficiencyScore(configAbove, speedTraining)

        // belowScore is still > aboveScore because Speed at 400 has a higher ratio multiplier than Speed at 700,
        // but the gap must come only from the documented ratio-bucket transition. Speed target is 800 for "Medium" so completion is 50% (below) and 87.5% (above),
        // which land in different ratio buckets. The natural gap is bounded; a re-added 2.5x spark bonus on top would push the ratio well past 5x.
        val ratio = belowScore / aboveScore
        assertTrue(ratio < 5.0, "Without the spark bonus the score gap between <600 and >=600 must stay within bucket-transition range")
    }

    @Test
    @DisplayName("Zero stat gains return zero score")
    fun testZeroStatGainsReturnZero() {
        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 0)),
            )

        val config = createDefaultConfig(trainingOptions = listOf(training))
        val score = calculateStatEfficiencyScore(config, training)

        assertEquals(0.0, score, "Training with no stat gains should return zero")
    }

    // ============================================================================
    // calculateRelationshipScore Tests
    // ============================================================================

    @Test
    @DisplayName("Diminishing returns apply as bars fill up")
    fun testDiminishingReturnsForFilledBars() {
        val lowFillBar = BarFillResult(statName = StatName.SPEED, fillPercent = 20.0, filledSegments = 1, dominantColor = "blue")
        val highFillBar = BarFillResult(statName = StatName.SPEED, fillPercent = 70.0, filledSegments = 3, dominantColor = "green")

        val lowFillTraining =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(lowFillBar),
            )
        val highFillTraining =
            createDefaultTrainingOption(
                relationshipBars = arrayListOf(highFillBar),
            )

        val config = createDefaultConfig(trainingOptions = listOf(lowFillTraining, highFillTraining))

        val lowFillScore = calculateRelationshipScore(config, lowFillTraining)
        val highFillScore = calculateRelationshipScore(config, highFillTraining)

        assertTrue(lowFillScore > highFillScore, "Lower fill bars should score higher due to diminishing returns")
    }

    // ============================================================================
    // calculateMiscScore Tests
    // ============================================================================

    @Test
    @DisplayName("Trainings with skill hints score higher than those without")
    fun testSkillHintsAdd10PointsEach() {
        val speedTraining = createDefaultTrainingOption(name = StatName.SPEED)
        val staminaTraining = createDefaultTrainingOption(name = StatName.STAMINA)

        // Speed has 2 skill hints, Stamina has 0.
        val config =
            createDefaultConfig(
                trainingOptions = listOf(speedTraining, staminaTraining),
                skillHintsPerLocation =
                    mapOf(
                        StatName.SPEED to 2,
                        StatName.STAMINA to 0,
                        StatName.POWER to 0,
                        StatName.GUTS to 0,
                        StatName.WIT to 0,
                    ),
            )

        val speedScore = calculateMiscScore(config, speedTraining)
        val staminaScore = calculateMiscScore(config, staminaTraining)

        assertTrue(speedScore > staminaScore, "A training with skill hints should score higher than a training with no skill hints")
    }

    @Test
    @DisplayName("Prioritized skill hints return massive score")
    fun testPrioritizedSkillHintsReturnMassiveScore() {
        val training = createDefaultTrainingOption(name = StatName.SPEED)

        val configWithPriority =
            createDefaultConfig(
                trainingOptions = listOf(training),
                skillHintsPerLocation =
                    mapOf(
                        StatName.SPEED to 1,
                        StatName.STAMINA to 0,
                        StatName.POWER to 0,
                        StatName.GUTS to 0,
                        StatName.WIT to 0,
                    ),
                enablePrioritizeSkillHints = true,
            )
        val configWithoutPriority =
            createDefaultConfig(
                trainingOptions = listOf(training),
                skillHintsPerLocation =
                    mapOf(
                        StatName.SPEED to 1,
                        StatName.STAMINA to 0,
                        StatName.POWER to 0,
                        StatName.GUTS to 0,
                        StatName.WIT to 0,
                    ),
                enablePrioritizeSkillHints = false,
            )

        val priorityScore = calculateMiscScore(configWithPriority, training)
        val normalScore = calculateMiscScore(configWithoutPriority, training)

        assertTrue(priorityScore > normalScore, "Prioritized skill hints should return higher score than normal skill hints")
    }

    // ============================================================================
    // calculateRawTrainingScore Tests
    // ============================================================================

    @Test
    @DisplayName("Blacklisted training returns zero score")
    fun testBlacklistedTrainingReturnsZero() {
        val training = createDefaultTrainingOption(name = StatName.SPEED)

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                blacklist = listOf(StatName.SPEED),
            )

        val score = calculateRawTrainingScore(config, training)

        assertEquals(0.0, score, "Blacklisted training should return zero score")
    }

    @Test
    @DisplayName("Training at stat cap returns zero score")
    fun testTrainingAtStatCapReturnsZero() {
        val currentStats =
            mapOf(
                StatName.SPEED to 1200,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )

        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(60, 0, 30, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                currentStats = currentStats,
            )

        val score = calculateRawTrainingScore(config, training)

        assertEquals(0.0, score, "Training that would exceed stat cap should return zero score")
    }

    @Test
    @DisplayName("Maxed stat with disableTrainingOnMaxedStat returns zero")
    fun testMaxedStatWithDisableSettingReturnsZero() {
        val currentStats =
            mapOf(
                StatName.SPEED to 1999,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )

        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(60, 0, 30, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                currentStats = currentStats,
                disableTrainingOnMaxedStat = true,
            )

        val score = calculateRawTrainingScore(config, training)

        assertEquals(0.0, score, "Training for would-be maxed stat should return zero when disableTrainingOnMaxedStat is true")
    }

    @Test
    @DisplayName("Rainbow training scores higher")
    fun testRainbowMultiplierInYear2Plus() {
        val rainbowTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 15, 0, 0)),
                numRainbow = 1,
            )
        val normalTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 15, 0, 0)),
                numRainbow = 0,
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(rainbowTraining, normalTraining),
                currentDate = GameDate(year = DateYear.CLASSIC, month = DateMonth.DECEMBER, phase = DatePhase.LATE),
                enableRainbowTrainingBonus = true,
            )

        val rainbowScore = calculateRawTrainingScore(config, rainbowTraining)
        val normalScore = calculateRawTrainingScore(config, normalTraining)

        assertTrue(rainbowScore > normalScore, "Rainbow training should score higher")
    }

    @Test
    @DisplayName("Training with relationship bars uses different weights")
    fun testRelationshipBarsChangeWeightDistribution() {
        val bar = BarFillResult(statName = StatName.SPEED, fillPercent = 20.0, filledSegments = 2, dominantColor = "blue")
        val trainingWithBars =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(20, 0, 10, 0, 0)),
                relationshipBars = arrayListOf(bar),
            )
        val trainingWithoutBars =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(20, 0, 10, 0, 0)),
                relationshipBars = arrayListOf(),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(trainingWithBars, trainingWithoutBars),
            )

        val withBarsScore = calculateRawTrainingScore(config, trainingWithBars)
        val withoutBarsScore = calculateRawTrainingScore(config, trainingWithoutBars)

        // Both should have positive scores, and the relationship bar contribution should affect total.
        assertTrue(withBarsScore > 0, "Training with bars should have positive score")
        assertTrue(withoutBarsScore > 0, "Training without bars should have positive score")
        // The training with bars gets relationship contribution.
        assertNotEquals(withBarsScore, withoutBarsScore, "Scores should differ based on relationship bars presence")
    }

    @Test
    @DisplayName("Rainbow bonus is reduced when enableRainbowTrainingBonus is false")
    fun testReducedRainbowBonusWhenDisabled() {
        val rainbowTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 15, 0, 0)),
                numRainbow = 1,
            )
        val normalTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 15, 0, 0)),
                numRainbow = 0,
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(rainbowTraining, normalTraining),
                currentDate = GameDate(year = DateYear.CLASSIC, month = DateMonth.DECEMBER, phase = DatePhase.LATE),
                enableRainbowTrainingBonus = false,
            )

        val rainbowScore = calculateRawTrainingScore(config, rainbowTraining)
        val normalScore = calculateRawTrainingScore(config, normalTraining)

        assertTrue(rainbowScore > normalScore, "Rainbow training should still score higher when bonus is disabled")
    }

    // ============================================================================
    // scoreUnityCupTraining Tests
    // ============================================================================

    @Test
    @DisplayName("Spirit gauges ready to burst get highest priority")
    fun testSpiritGaugesReadyToBurstHighestPriority() {
        val trainingWithBurst =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                extras = mapOf("spiritGaugesReadyToBurst" to 1, "spiritGaugesCanFill" to 0),
            )
        val trainingWithFill =
            createDefaultTrainingOption(
                name = StatName.STAMINA,
                extras = mapOf("spiritGaugesCanFill" to 3),
            )
        val trainingWithNoGauges =
            createDefaultTrainingOption(
                name = StatName.POWER,
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(trainingWithBurst, trainingWithFill, trainingWithNoGauges),
                scenario = "Unity Cup",
            )

        val burstScore = scoreUnityCupTraining(config, trainingWithBurst)
        val fillScore = scoreUnityCupTraining(config, trainingWithFill)
        val noGaugeScore = scoreUnityCupTraining(config, trainingWithNoGauges)

        assertTrue(burstScore > fillScore, "Training with gauges ready to burst should score higher than training that can fill gauges")
        assertTrue(fillScore > noGaugeScore, "Training that can fill gauges should score higher than training with no gauges")
    }

    @Test
    @DisplayName("Speed and Wit get facility preference bonuses when spirit gauge bursting")
    fun testFacilityPreferenceBonusesForBursting() {
        // Zero out stat gains to isolate facility bonuses.
        val burstExtras = mapOf<String, Any?>("spiritGaugesReadyToBurst" to 1)
        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 0)),
                extras = burstExtras,
            )
        val witTraining =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 0)),
                extras = burstExtras,
            )
        val gutsTraining =
            createDefaultTrainingOption(
                name = StatName.GUTS,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 0)),
                extras = burstExtras,
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(speedTraining, witTraining, gutsTraining),
                scenario = "Unity Cup",
            )

        val speedScore = scoreUnityCupTraining(config, speedTraining)
        val witScore = scoreUnityCupTraining(config, witTraining)
        val gutsScore = scoreUnityCupTraining(config, gutsTraining)

        // Speed and Wit should have same bonuses (both get +500 facility bonus).
        assertEquals(speedScore, witScore, 0.01, "Speed and Wit should have equal facility bonuses")
        // Guts should score lower since it doesn't have the facility bonus.
        assertTrue(speedScore > gutsScore, "Speed should score higher than Guts for facility preference")
    }

    @Test
    @DisplayName("Retuned fill bonus: a strong stat training beats a weak one-gauge fill (the original GUTS-over-POWER complaint)")
    fun testUnityCupRetunedFillDoesNotOutrankStrongStats() {
        // Reproduces the reported turn shape: a 52-stat POWER option with no gauge vs a 16-stat GUTS option that only fills one gauge, in Junior year.
        val powerTraining = createDefaultTrainingOption(name = StatName.POWER, statGains = statGainsToMap(intArrayOf(0, 16, 36, 0, 0)))
        val gutsTraining = createDefaultTrainingOption(name = StatName.GUTS, statGains = statGainsToMap(intArrayOf(4, 0, 4, 8, 0)), extras = mapOf("spiritGaugesCanFill" to 1))
        val currentStats = mapOf(StatName.SPEED to 250, StatName.STAMINA to 250, StatName.POWER to 250, StatName.GUTS to 120, StatName.WIT to 120)
        val config =
            createDefaultConfig(
                trainingOptions = listOf(powerTraining, gutsTraining),
                currentStats = currentStats,
                currentDate = GameDate(year = DateYear.JUNIOR, month = DateMonth.JANUARY, phase = DatePhase.EARLY),
                scenario = "Unity Cup",
            )

        val powerScore = scoreUnityCupTraining(config, powerTraining)
        val gutsScore = scoreUnityCupTraining(config, gutsTraining)
        assertTrue(powerScore > gutsScore, "With the retuned fill bonus the 52-stat POWER training should beat the 16-stat one-gauge GUTS training (power=$powerScore, guts=$gutsScore)")
    }

    @Test
    @DisplayName("Early game provides spirit gauge filling bonus")
    fun testEarlyGameGaugeFillingBonus() {
        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                extras = mapOf("spiritGaugesCanFill" to 2),
            )

        val earlyConfig =
            createDefaultConfig(
                trainingOptions = listOf(training),
                scenario = "Unity Cup",
                currentDate = GameDate(year = DateYear.JUNIOR, month = DateMonth.JANUARY, phase = DatePhase.EARLY),
            )
        val lateConfig =
            createDefaultConfig(
                trainingOptions = listOf(training),
                scenario = "Unity Cup",
                currentDate = GameDate(year = DateYear.CLASSIC, month = DateMonth.JUNE, phase = DatePhase.EARLY),
            )

        val earlyScore = scoreUnityCupTraining(earlyConfig, training)
        val lateScore = scoreUnityCupTraining(lateConfig, training)

        assertTrue(earlyScore > lateScore, "Early game should provide bonus for spirit gauge filling")
    }

    @Test
    @DisplayName("Rainbow training provides bonus when spirit gauge bursting")
    fun testRainbowBonusWhenBursting() {
        val rainbowBurstTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                extras = mapOf("spiritGaugesReadyToBurst" to 1),
                numRainbow = 1,
            )
        val normalBurstTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                extras = mapOf("spiritGaugesReadyToBurst" to 1),
                numRainbow = 0,
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(rainbowBurstTraining, normalBurstTraining),
                scenario = "Unity Cup",
                currentDate = GameDate(year = DateYear.CLASSIC, month = DateMonth.JANUARY, phase = DatePhase.EARLY),
            )

        val rainbowScore = scoreUnityCupTraining(config, rainbowBurstTraining)
        val normalScore = scoreUnityCupTraining(config, normalBurstTraining)

        assertTrue(rainbowScore > normalScore, "Rainbow training should score higher when spirit gauge bursting")
    }

    // ============================================================================
    // Training Example Cases (Parameterized)
    // ============================================================================

    /**
     * Data class representing a training scenario test case.
     */
    data class TrainingTestCase(
        val description: String,
        val currentStats: Map<String, Int>,
        val trainings: List<TrainingDef>,
        val preferredDistance: String,
        val date: GameDate,
        val expectedTraining: StatName,
        val statPrioritization: List<StatName>? = null,
    ) {
        // Override toString() to only show the description in test names.
        override fun toString(): String = description
    }

    /**
     * Simplified training definition for test cases.
     */
    data class TrainingDef(
        val name: StatName,
        val statGains: IntArray,
        val relationshipBars: List<BarDef> = emptyList(),
        val extras: Map<String, Any?> = emptyMap(),
        val numRainbow: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TrainingDef

            if (extras != other.extras) return false
            if (numRainbow != other.numRainbow) return false
            if (name != other.name) return false
            if (!statGains.contentEquals(other.statGains)) return false
            if (relationshipBars != other.relationshipBars) return false

            return true
        }

        override fun hashCode(): Int {
            var result = extras.hashCode()
            result = 31 * result + numRainbow
            result = 31 * result + name.hashCode()
            result = 31 * result + statGains.contentHashCode()
            result = 31 * result + relationshipBars.hashCode()
            return result
        }
    }

    /**
     * Simplified bar definition for test cases.
     */
    data class BarDef(
        val fillPercent: Double = 50.0,
        val filledSegments: Int = 2,
        val color: String,
    )

    // Note: Stat prioritization follows the default of [Speed, Stamina, Power, Wit, Guts].
    companion object {
        /**
         * Provides Unity Cup test cases for parameterized testing.
         * Add new test cases here - each one will automatically be tested.
         */
        @JvmStatic
        fun unityCupTestCases(): Stream<TrainingTestCase> =
            Stream.of(
                TrainingTestCase(
                    description = "Junior Year Early Dec - Guts with the only burstable gauge",
                    currentStats = mapOf("Speed" to 358, "Stamina" to 217, "Power" to 258, "Guts" to 168, "Wit" to 168),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(15, 0, 6, 0, 0), listOf(BarDef(color = "green")), extras = mapOf("spiritGaugesCanFill" to 1)),
                            TrainingDef(StatName.STAMINA, intArrayOf(0, 8, 0, 4, 0)),
                            TrainingDef(StatName.POWER, intArrayOf(0, 4, 8, 0, 0)),
                            TrainingDef(
                                StatName.GUTS,
                                intArrayOf(11, 0, 10, 31, 0),
                                listOf(BarDef(color = "green"), BarDef(color = "green"), BarDef(color = "green")),
                                extras = mapOf("spiritGaugesCanFill" to 1, "spiritGaugesReadyToBurst" to 1),
                            ),
                            TrainingDef(StatName.WIT, intArrayOf(4, 0, 0, 0, 17), extras = mapOf("spiritGaugesReadyToBurst" to 1)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(year = DateYear.JUNIOR, month = DateMonth.DECEMBER, phase = DatePhase.EARLY),
                    expectedTraining = StatName.GUTS,
                ),
                TrainingTestCase(
                    description = "Classic Year Early Aug - Power with rainbow bonus, fillable gauge and stat gains",
                    currentStats = mapOf("Speed" to 453, "Stamina" to 372, "Power" to 483, "Guts" to 244, "Wit" to 214),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(22, 0, 10, 0, 0), listOf(BarDef(color = "green")), extras = mapOf("spiritGaugesCanFill" to 1)),
                            TrainingDef(
                                StatName.STAMINA,
                                intArrayOf(0, 25, 0, 13, 0),
                                listOf(BarDef(color = "orange"), BarDef(color = "green"), BarDef(color = "green")),
                                extras =
                                    mapOf(
                                        "spiritGaugesCanFill" to 1,
                                    ),
                            ),
                            TrainingDef(StatName.POWER, intArrayOf(0, 15, 23, 0, 0), listOf(BarDef(color = "orange")), extras = mapOf("spiritGaugesCanFill" to 1), numRainbow = 1),
                            TrainingDef(StatName.GUTS, intArrayOf(5, 0, 5, 15, 0)),
                            TrainingDef(StatName.WIT, intArrayOf(5, 0, 0, 0, 12)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(year = DateYear.CLASSIC, month = DateMonth.AUGUST, phase = DatePhase.EARLY),
                    expectedTraining = StatName.POWER,
                    statPrioritization = listOf(StatName.POWER, StatName.STAMINA, StatName.SPEED, StatName.WIT, StatName.GUTS),
                ),
                TrainingTestCase(
                    description = "Senior Year Early Jul - Speed with high main stat gain, rainbow bonus and fillable gauges",
                    currentStats = mapOf("Speed" to 834, "Stamina" to 588, "Power" to 724, "Guts" to 335, "Wit" to 283),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(33, 0, 13, 0, 0), listOf(BarDef(color = "orange")), extras = mapOf("spiritGaugesCanFill" to 2), numRainbow = 1),
                            TrainingDef(StatName.STAMINA, intArrayOf(0, 47, 0, 22, 0), listOf(BarDef(color = "orange")), extras = mapOf("spiritGaugesReadyToBurst" to 1)),
                            TrainingDef(StatName.POWER, intArrayOf(0, 8, 14, 0, 0), extras = mapOf("spiritGaugesCanFill" to 1)),
                            TrainingDef(StatName.GUTS, intArrayOf(12, 0, 9, 35, 0), extras = mapOf("spiritGaugesReadyToBurst" to 1)),
                            TrainingDef(StatName.WIT, intArrayOf(6, 0, 0, 0, 13)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(year = DateYear.SENIOR, month = DateMonth.JULY, phase = DatePhase.EARLY),
                    expectedTraining = StatName.SPEED,
                ),
            )

        /**
         * Provides URA Finale test cases for parameterized testing.
         * Add new test cases here - each one will automatically be tested.
         */
        @JvmStatic
        fun uraFinaleTestCases(): Stream<TrainingTestCase> =
            Stream.of(
                TrainingTestCase(
                    description = "URA Finale Qualifier - Speed with high main stat gain and rainbow bonus",
                    currentStats = mapOf("Speed" to 1042, "Stamina" to 615, "Power" to 841, "Guts" to 362, "Wit" to 315),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(31, 0, 15, 0, 0), numRainbow = 1),
                            TrainingDef(StatName.STAMINA, intArrayOf(0, 15, 0, 6, 0)),
                            TrainingDef(StatName.POWER, intArrayOf(0, 7, 15, 0, 0)),
                            TrainingDef(StatName.GUTS, intArrayOf(6, 0, 4, 16, 0)),
                            TrainingDef(StatName.WIT, intArrayOf(5, 0, 0, 0, 15)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(day = 73),
                    expectedTraining = StatName.SPEED,
                ),
                TrainingTestCase(
                    description = "Classic Year Early Aug - Speed with high main stat gain and rainbow bonus",
                    currentStats = mapOf("Speed" to 537, "Stamina" to 386, "Power" to 388, "Guts" to 228, "Wit" to 255),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(29, 0, 12, 0, 0), listOf(BarDef(color = "orange")), numRainbow = 1),
                            TrainingDef(StatName.STAMINA, intArrayOf(0, 25, 0, 10, 0), listOf(BarDef(color = "orange"))),
                            TrainingDef(StatName.POWER, intArrayOf(0, 8, 12, 0, 0)),
                            TrainingDef(StatName.GUTS, intArrayOf(7, 0, 7, 15, 0), listOf(BarDef(color = "green"))),
                            TrainingDef(StatName.WIT, intArrayOf(6, 0, 0, 0, 14)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(year = DateYear.CLASSIC, month = DateMonth.AUGUST, phase = DatePhase.EARLY),
                    expectedTraining = StatName.SPEED,
                ),
                TrainingTestCase(
                    description = "Junior Year Pre-Debut - Power with the most relationship bars",
                    currentStats = mapOf("Speed" to 136, "Stamina" to 189, "Power" to 160, "Guts" to 76, "Wit" to 135),
                    trainings =
                        listOf(
                            TrainingDef(StatName.SPEED, intArrayOf(10, 0, 4, 0, 0), listOf(BarDef(color = "blue"), BarDef(color = "blue"))),
                            TrainingDef(StatName.STAMINA, intArrayOf(0, 8, 0, 3, 0)),
                            TrainingDef(StatName.POWER, intArrayOf(0, 8, 12, 0, 0), listOf(BarDef(color = "blue"), BarDef(color = "blue"), BarDef(color = "blue"))),
                            TrainingDef(StatName.GUTS, intArrayOf(3, 0, 3, 6, 0)),
                            TrainingDef(StatName.WIT, intArrayOf(3, 0, 0, 0, 9)),
                        ),
                    preferredDistance = "Medium",
                    date = GameDate(day = 2),
                    expectedTraining = StatName.POWER,
                ),
            )
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("unityCupTestCases")
    @DisplayName("Unity Cup Training Selection")
    fun testUnityCupTrainingSelection(testCase: TrainingTestCase) {
        // Convert TrainingDef to TrainingOption.
        val trainingOptions =
            testCase.trainings.map { def ->
                createDefaultTrainingOption(
                    name = def.name,
                    statGains = statGainsToMap(def.statGains),
                    relationshipBars =
                        ArrayList(
                            def.relationshipBars.map { bar ->
                                BarFillResult(statName = StatName.SPEED, bar.fillPercent, bar.filledSegments, bar.color)
                            },
                        ),
                    numRainbow = def.numRainbow,
                    extras = def.extras,
                )
            }

        val config =
            createDefaultConfig(
                trainingOptions = trainingOptions,
                currentStats = statsToMap(testCase.currentStats),
                statPrioritization = testCase.statPrioritization ?: listOf(StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.WIT, StatName.GUTS),
                preferredDistance = testCase.preferredDistance,
                currentDate = testCase.date,
                scenario = "Unity Cup",
            )

        // Score all trainings using Unity Cup scoring.
        val scores =
            if (testCase.date.year < DateYear.SENIOR) {
                trainingOptions.associateWith { scoreUnityCupTraining(config, it) }
            } else {
                trainingOptions.associateWith { calculateRawTrainingScore(config, it) }
            }
        val bestTraining = scores.maxByOrNull { it.value }?.key

        assertEquals(testCase.expectedTraining, bestTraining?.name, testCase.description)
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("uraFinaleTestCases")
    @DisplayName("URA Finale Training Selection")
    fun testURAFinaleTrainingSelection(testCase: TrainingTestCase) {
        // Convert TrainingDef to TrainingOption.
        val trainingOptions =
            testCase.trainings.map { def ->
                createDefaultTrainingOption(
                    name = def.name,
                    statGains = statGainsToMap(def.statGains),
                    relationshipBars =
                        ArrayList(
                            def.relationshipBars.map { bar ->
                                BarFillResult(statName = StatName.SPEED, bar.fillPercent, bar.filledSegments, bar.color)
                            },
                        ),
                    numRainbow = def.numRainbow,
                )
            }

        val config =
            createDefaultConfig(
                trainingOptions = trainingOptions,
                currentStats = statsToMap(testCase.currentStats),
                preferredDistance = testCase.preferredDistance,
                currentDate = testCase.date,
                scenario = "URA Finale",
            )

        // Use friendship training scoring for Junior Year, otherwise use standard scoring.
        val scores =
            if (testCase.date.year == DateYear.JUNIOR) {
                trainingOptions.associateWith { scoreFriendshipTraining(it) }
            } else {
                trainingOptions.associateWith { calculateRawTrainingScore(config, it) }
            }
        val bestTraining = scores.maxByOrNull { it.value }?.key

        assertEquals(testCase.expectedTraining, bestTraining?.name, testCase.description)
    }

    // ///////////////////////////////////////////////////////////////////////////////////////////
    // Finale Race Stat Bonus Tests
    // ///////////////////////////////////////////////////////////////////////////////////////////

    @Test
    @DisplayName("getRemainingFinaleRaces returns correct values for boundary turns")
    fun testGetRemainingFinaleRaces() {
        assertEquals(3, getRemainingFinaleRaces(1), "Turn 1: all 3 finale races remaining")
        assertEquals(3, getRemainingFinaleRaces(60), "Turn 60: all 3 finale races remaining")
        assertEquals(3, getRemainingFinaleRaces(72), "Turn 72: all 3 finale races remaining")
        assertEquals(2, getRemainingFinaleRaces(73), "Turn 73: 2 finale races remaining")
        assertEquals(1, getRemainingFinaleRaces(74), "Turn 74: 1 finale race remaining")
        assertEquals(0, getRemainingFinaleRaces(75), "Turn 75: no finale races remaining")
    }

    @Test
    @DisplayName("getFinaleStatBonus returns correct bonus values")
    fun testGetFinaleStatBonus() {
        assertEquals(45, getFinaleStatBonus(60), "Turn 60: 3 races * 15 = 45")
        assertEquals(30, getFinaleStatBonus(73), "Turn 73: 2 races * 15 = 30")
        assertEquals(15, getFinaleStatBonus(74), "Turn 74: 1 race * 15 = 15")
        assertEquals(0, getFinaleStatBonus(75), "Turn 75: 0 races * 15 = 0")
    }

    @Test
    @DisplayName("Stat near cap blocked by finale bonus adjustment (turn 60, 3 races remaining)")
    fun testFinaleAdjustmentBlocksTrainingNearCap() {
        // URA Finale caps Speed at 1400. With 3 finale races remaining, effective cap = 1400 - 100 - 45 = 1255.
        // A stat at 1260 should be blocked.
        val currentStats =
            mapOf(
                StatName.SPEED to 1260,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )

        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(60, 0, 30, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                currentStats = currentStats,
                disableTrainingOnMaxedStat = true,
                currentDate = GameDate(day = 60),
            )

        val score = calculateRawTrainingScore(config, training)

        assertEquals(0.0, score, "Stat at 1260 should be blocked when effective cap is 1255 (turn 60, 3 finale races)")
    }

    @Test
    @DisplayName("Same stat allowed when fewer finale races remain (turn 74, 1 race remaining)")
    fun testFinaleAdjustmentAllowsTrainingWithFewerRaces() {
        // URA Finale caps Speed at 1400. With 1 finale race remaining, effective cap = 1400 - 100 - 15 = 1285.
        // A stat at 1260 should NOT be blocked.
        val currentStats =
            mapOf(
                StatName.SPEED to 1260,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )

        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(20, 0, 10, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                currentStats = currentStats,
                disableTrainingOnMaxedStat = true,
                currentDate = GameDate(day = 74),
            )

        val score = calculateRawTrainingScore(config, training)

        assertTrue(score > 0.0, "Stat at 1260 should be allowed when effective cap is 1285 (turn 74, 1 finale race)")
    }

    @Test
    @DisplayName("No finale adjustment on turn 75 (all races done)")
    fun testNoFinaleAdjustmentOnFinalTurn() {
        // URA Finale caps Speed at 1400. With 0 finale races remaining, effective cap = 1400 - 100 = 1300.
        // A stat at 1260 with potential 1280 should NOT be blocked.
        val currentStats =
            mapOf(
                StatName.SPEED to 1260,
                StatName.STAMINA to 400,
                StatName.POWER to 400,
                StatName.GUTS to 400,
                StatName.WIT to 400,
            )

        val training =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(20, 0, 10, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(training),
                currentStats = currentStats,
                disableTrainingOnMaxedStat = true,
                currentDate = GameDate(day = 75),
            )

        val score = calculateRawTrainingScore(config, training)

        assertTrue(score > 0.0, "Stat at 1260 should be allowed on turn 75 with no finale adjustment (effective cap = 1300)")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Level Boost Multiplier

    @Test
    @DisplayName("levelBoostMultiplier: Lvl 1 returns 1.0 regardless of rank")
    fun testLevelBoostMultiplier_lvl1NeverBoosts() {
        for (rank in 1..5) {
            assertEquals(1.0, levelBoostMultiplier(rank, 1), 1e-9, "Rank $rank, Lvl 1 should never boost")
        }
    }

    @Test
    @DisplayName("levelBoostMultiplier: null level treated as Lvl 1")
    fun testLevelBoostMultiplier_nullLevelNoBoost() {
        for (rank in 1..5) {
            assertEquals(1.0, levelBoostMultiplier(rank, null), 1e-9, "Rank $rank, null level should never boost")
        }
    }

    @Test
    @DisplayName("levelBoostMultiplier: priority ranks 4 and 5 are never boosted")
    fun testLevelBoostMultiplier_lowPriorityNeverBoosts() {
        for (level in 1..5) {
            assertEquals(1.0, levelBoostMultiplier(4, level), 1e-9, "Rank 4 should never boost (Lvl $level)")
            assertEquals(1.0, levelBoostMultiplier(5, level), 1e-9, "Rank 5 should never boost (Lvl $level)")
        }
    }

    @Test
    @DisplayName("levelBoostMultiplier: rank 1 at Lvl 5 returns 1.75x")
    fun testLevelBoostMultiplier_rank1Lvl5() {
        assertEquals(1.75, levelBoostMultiplier(1, 5), 1e-9)
    }

    @Test
    @DisplayName("levelBoostMultiplier: rank 2 at Lvl 5 returns 1.25x")
    fun testLevelBoostMultiplier_rank2Lvl5() {
        assertEquals(1.25, levelBoostMultiplier(2, 5), 1e-9)
    }

    @Test
    @DisplayName("levelBoostMultiplier: rank 3 at Lvl 5 returns 1.10x")
    fun testLevelBoostMultiplier_rank3Lvl5() {
        assertEquals(1.10, levelBoostMultiplier(3, 5), 1e-9)
    }

    @Test
    @DisplayName("levelBoostMultiplier: rank 1 at Lvl 3 scales linearly to 1.375x")
    fun testLevelBoostMultiplier_rank1Lvl3() {
        // levelFactor = (3 - 1) / 4.0 = 0.5; rank 1 factor = 0.75; boost = 1 + 0.75 * 0.5 = 1.375
        assertEquals(1.375, levelBoostMultiplier(1, 3), 1e-9)
    }

    @Test
    @DisplayName("levelBoostMultiplier: out-of-range level above 5 caps via formula (Lvl 5 effectively)")
    fun testLevelBoostMultiplier_levelClampedBehavior() {
        // The helper does not clamp; callers are expected to feed 1..5. Verify the formula is well-defined for boundary inputs.
        assertEquals(1.75, levelBoostMultiplier(1, 5), 1e-9)
        // Rank 0 (out of priority list) returns 1.0 since the when branch falls through to else.
        assertEquals(1.0, levelBoostMultiplier(0, 5), 1e-9)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // disableStatTargets override

    /**
     * Builds the post-override statTargets map that recommendTraining() applies when disableStatTargets is true.
     * Mirrors the production branch so tests stay aligned with real behavior.
     */
    private fun cappedStatTargets(cap: Int = 1200): Map<StatName, Int> {
        return StatName.entries.associateWith { cap }
    }

    @Test
    @DisplayName("disableStatTargets override: over-target stat regains ratio bonus when targets are pinned to the cap")
    fun testDisableStatTargetsLiftsOverTargetPenalty() {
        // Scenario from the Tokai Teio run: Wit at 828, normally targeted at 600 (Medium), heavily penalized by ratioMultiplier.
        val currentStats =
            mapOf(
                StatName.SPEED to 998,
                StatName.STAMINA to 685,
                StatName.POWER to 725,
                StatName.GUTS to 361,
                StatName.WIT to 828,
            )
        val witTraining =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(9, 0, 0, 0, 21)),
                numRainbow = 0,
            )

        val baseConfig =
            createDefaultConfig(
                trainingOptions = listOf(witTraining),
                currentStats = currentStats,
                statPrioritization = listOf(StatName.WIT, StatName.SPEED, StatName.POWER, StatName.STAMINA, StatName.GUTS),
                preferredDistance = "Medium",
                currentDate = GameDate(year = DateYear.SENIOR, month = DateMonth.AUGUST, phase = DatePhase.EARLY),
            )
        val overrideConfig = baseConfig.copy(statTargets = cappedStatTargets(), disableStatTargets = true)

        val baseScore = calculateStatEfficiencyScore(baseConfig, witTraining)
        val overrideScore = calculateStatEfficiencyScore(overrideConfig, witTraining)

        // With the override, Wit drops from 138% completion (target=600) to 69% completion (target=1200). The lower band gives a much higher ratio multiplier,
        // so the score must rise noticeably.
        assertTrue(overrideScore > baseScore * 2.0, "Override should at least double Wit's score (base=$baseScore, override=$overrideScore)")
    }

    @Test
    @DisplayName("disableStatTargets override: under-target stat is not penalized by the override")
    fun testDisableStatTargetsLeavesUnderTargetUntouched() {
        // Speed at 998 vs Medium target 800 -> 124% (mild over-target penalty); at cap=1200 -> 83% (mild under-target bonus).
        // The override must not REDUCE the score for an already-strong priority stat.
        val currentStats =
            mapOf(
                StatName.SPEED to 998,
                StatName.STAMINA to 685,
                StatName.POWER to 725,
                StatName.GUTS to 361,
                StatName.WIT to 828,
            )
        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(44, 0, 18, 0, 0)),
                numRainbow = 1,
            )
        val baseConfig =
            createDefaultConfig(
                trainingOptions = listOf(speedTraining),
                currentStats = currentStats,
                statPrioritization = listOf(StatName.WIT, StatName.SPEED, StatName.POWER, StatName.STAMINA, StatName.GUTS),
                preferredDistance = "Medium",
                currentDate = GameDate(year = DateYear.SENIOR, month = DateMonth.AUGUST, phase = DatePhase.EARLY),
            )
        val overrideConfig = baseConfig.copy(statTargets = cappedStatTargets(), disableStatTargets = true)

        val baseScore = calculateStatEfficiencyScore(baseConfig, speedTraining)
        val overrideScore = calculateStatEfficiencyScore(overrideConfig, speedTraining)

        assertTrue(overrideScore >= baseScore, "Override must not penalize an under-target priority stat (base=$baseScore, override=$overrideScore)")
    }

    @Test
    @DisplayName("disableStatTargets defaults to false on TrainingConfig")
    fun testDisableStatTargetsDefault() {
        val config = createDefaultConfig()
        assertEquals(false, config.disableStatTargets, "TrainingConfig.disableStatTargets should default to false")
    }

    @Test
    @DisplayName("TrainingScoringConstants defaults match original hardcoded values")
    fun testScoringConstantsDefaults() {
        val c = TrainingScoringConstants()
        assertEquals(listOf(15.0, 30.0, 45.0, 60.0, 75.0, 90.0), c.ratioBreakpoints)
        assertEquals(listOf(5.0, 4.0, 3.0, 2.0, 1.0, 0.5, 0.3), c.ratioMultipliers)
        assertEquals(0.5, c.priorityCoefficient)
        assertEquals(0.75, c.levelBoostRank1Factor)
        assertEquals(0.25, c.levelBoostRank2Factor)
        assertEquals(0.10, c.levelBoostRank3Factor)
        assertEquals(2.0, c.mainStatBonusMagnitude)
        assertEquals(15, c.mainStatThresholds[StatName.WIT])
        assertEquals(30, c.mainStatThresholds[StatName.SPEED])
        assertEquals(0.0, c.relationshipOrangeValue)
        assertEquals(1.0, c.relationshipGreenValue)
        assertEquals(2.5, c.relationshipBlueValue)
        assertEquals(0.5, c.relationshipDiminishingFactor)
        assertEquals(1.3, c.relationshipEarlyGameBonus)
        assertEquals(1.15, c.relationshipTrainerSupportBonus)
        assertEquals(10.0, c.skillHintPerHintScore)
        assertEquals(10000.0, c.skillHintOverrideScore)
        assertEquals(0.6, c.statWeightWithBars)
        assertEquals(0.7, c.statWeightWithoutBars)
        assertEquals(0.1, c.relationshipWeightWithBars)
        assertEquals(0.3, c.miscWeight)
        assertEquals(100.0, c.juniorEarlyGameFlatBonus)
        assertEquals(1.5, c.relationshipScale)
        assertEquals(60.0, c.unityFillBaseBonus)
        assertEquals(40.0, c.unityFillPerGaugeBonus)
        assertEquals(800.0, c.unityBurstBaseBonus)
        assertEquals(400.0, c.unityBurstPerGaugeBonus)
        assertEquals(0.0, c.unityFillEnergyPenaltyPerGauge)
        assertEquals(0.0, c.unityBurstEnergyPenaltyPerGauge)
        assertEquals(2.0, c.rainbowMultiplierEnabled)
        assertEquals(1.5, c.rainbowMultiplierDisabled)
        assertEquals(200.0, c.rainbowPerInstanceBase)
        assertEquals(0.5, c.rainbowPerInstanceDecay)
        assertEquals(50.0, c.anticipatoryMinFillPercent)
        assertEquals(0.2, c.anticipatoryCoefficient)
        assertEquals(0.6, c.anticipatoryCap)
    }

    @Test
    @DisplayName("mainStatBonus fires for Wit at gain 15")
    fun testWitMainStatBonusFiresAtFifteen() {
        val witTraining =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 15)),
            )
        val below =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 14)),
            )

        val config = createDefaultConfig(trainingOptions = listOf(witTraining, below))

        val withBonus = calculateStatEfficiencyScore(config, witTraining)
        val withoutBonus = calculateStatEfficiencyScore(config, below)

        // +15 Wit gets 2x bonus; +14 doesn't. Score ratio should be close to (15 * 2) / 14.
        assertTrue(withBonus > withoutBonus * 2.0, "Wit at +15 should receive the 2x main-stat bonus while +14 does not")
    }

    @Test
    @DisplayName("mainStatBonus still requires gain 30 for Speed/Stamina/Power/Guts")
    fun testNonWitMainStatBonusRequiresThirty() {
        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(29, 0, 0, 0, 0)),
            )
        val speedThreshold =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(30, 0, 0, 0, 0)),
            )

        val config = createDefaultConfig(trainingOptions = listOf(speedTraining, speedThreshold))

        val below = calculateStatEfficiencyScore(config, speedTraining)
        val at = calculateStatEfficiencyScore(config, speedThreshold)

        // +30 gets the 2x bonus; +29 doesn't. Expect a big jump despite only +1 gain.
        assertTrue(at > below * 1.9, "Speed at +30 should receive the 2x main-stat bonus while +29 does not")
    }

    @Test
    @DisplayName("mainStatBonus threshold is per-stat, configurable through TrainingScoringConstants")
    fun testMainStatBonusThresholdIsConfigurable() {
        val gutsTraining =
            createDefaultTrainingOption(
                name = StatName.GUTS,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 22, 0)),
            )

        val configDefault = createDefaultConfig(trainingOptions = listOf(gutsTraining))
        val customConstants =
            TrainingScoringConstants(
                mainStatThresholds =
                    mapOf(StatName.GUTS to 20) +
                        StatName.entries.filter { it != StatName.GUTS }.associateWith { 30 },
            )
        val configCustom =
            configDefault.copy(scoring = customConstants)

        val defaultScore = calculateStatEfficiencyScore(configDefault, gutsTraining)
        val customScore = calculateStatEfficiencyScore(configCustom, gutsTraining)

        // Default: Guts +22 below 30, no bonus. Custom: threshold 20, bonus fires.
        assertTrue(customScore > defaultScore * 1.9, "Lowering the Guts threshold should activate the 2x main-stat bonus")
    }

    @Test
    @DisplayName("Priority multiplier applies regardless of completion gap to top-priority stat")
    fun testPriorityMultiplierIgnoresCompletionGap() {
        // Stats configured so Wit is far behind in completion (10%), Speed is far ahead (90%).
        // Under the old 10% gate, Speed would receive no priority bonus. Under the new design, it must.
        val currentStats =
            mapOf(
                StatName.SPEED to 720,
                StatName.STAMINA to 0,
                StatName.POWER to 0,
                StatName.GUTS to 0,
                StatName.WIT to 80,
            )
        val speedTraining =
            createDefaultTrainingOption(
                name = StatName.SPEED,
                statGains = statGainsToMap(intArrayOf(10, 0, 0, 0, 0)),
            )

        val config =
            createDefaultConfig(
                trainingOptions = listOf(speedTraining),
                currentStats = currentStats,
                statPrioritization = listOf(StatName.WIT, StatName.SPEED, StatName.POWER, StatName.STAMINA, StatName.GUTS),
            )

        val scoreWithPriorityActive = calculateStatEfficiencyScore(config, speedTraining)

        // Compare against a config where Speed is not in the priority list at all.
        val configNoPriority =
            config.copy(statPrioritization = listOf(StatName.WIT, StatName.STAMINA, StatName.GUTS, StatName.POWER))
        val scoreWithoutPriority = calculateStatEfficiencyScore(configNoPriority, speedTraining)

        assertTrue(
            scoreWithPriorityActive > scoreWithoutPriority,
            "Speed at index 1 in the priority list must outscore Speed not in the priority list, even when its completion is far above Wit's",
        )
    }

    @Test
    @DisplayName("Priority coefficient 0.5 yields 3.0x for top of a 4-stat list")
    fun testPriorityCoefficientYieldsExpectedTopMultiplier() {
        // Plain Wit training, no rainbows, no main-stat bonus, with Wit at the top of a 4-stat list.
        val witTraining =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 5)),
            )
        val witLast =
            createDefaultTrainingOption(
                name = StatName.WIT,
                statGains = statGainsToMap(intArrayOf(0, 0, 0, 0, 5)),
            )

        val configTop =
            createDefaultConfig(
                trainingOptions = listOf(witTraining),
                statPrioritization = listOf(StatName.WIT, StatName.POWER, StatName.SPEED, StatName.STAMINA),
            )
        val configBottom =
            createDefaultConfig(
                trainingOptions = listOf(witLast),
                statPrioritization = listOf(StatName.POWER, StatName.SPEED, StatName.STAMINA, StatName.WIT),
            )

        val topScore = calculateStatEfficiencyScore(configTop, witTraining)
        val bottomScore = calculateStatEfficiencyScore(configBottom, witLast)

        // Top: 1.0 + 0.5 * 4 = 3.0. Bottom: 1.0 + 0.5 * 1 = 1.5. Ratio 2.0.
        val ratio = topScore / bottomScore
        assertEquals(2.0, ratio, 0.01, "Top-of-list priority should be 2x stronger than bottom-of-list under coefficient 0.5")
    }

    @Test
    @DisplayName("scoringConstantsFromMap: empty map returns all defaults")
    fun scoringConstantsFromMapEmptyReturnsDefaults() {
        val defaults = TrainingScoringConstants()
        val result = scoringConstantsFromMap(emptyMap())
        assertEquals(defaults, result, "Empty settings map should yield defaults")
    }

    @Test
    @DisplayName("scoringConstantsFromMap: single override applied, others remain default")
    fun scoringConstantsFromMapSingleOverride() {
        val defaults = TrainingScoringConstants()
        val result = scoringConstantsFromMap(mapOf("priorityCoefficient" to 0.8))
        assertEquals(0.8, result.priorityCoefficient, 1e-9, "priorityCoefficient should reflect override")
        assertEquals(defaults.copy(priorityCoefficient = 0.8), result, "All other fields should match defaults")
    }

    @Test
    @DisplayName("scoringConstantsFromMap: per-stat threshold override applied")
    fun scoringConstantsFromMapStatThresholdOverride() {
        val defaults = TrainingScoringConstants()
        val result = scoringConstantsFromMap(mapOf("mainStatThresholdWit" to 25))
        assertEquals(25, result.mainStatThresholds[StatName.WIT], "WIT threshold should reflect override")
        assertEquals(defaults.mainStatThresholds[StatName.SPEED], result.mainStatThresholds[StatName.SPEED], "SPEED threshold unchanged")
    }

    @Test
    @DisplayName("scoringConstantsFromMap: non-numeric or NaN values fall back to default")
    fun scoringConstantsFromMapInvalidFallback() {
        val defaults = TrainingScoringConstants()
        val result = scoringConstantsFromMap(mapOf("priorityCoefficient" to "oops", "miscWeight" to Double.NaN))
        assertEquals(defaults.priorityCoefficient, result.priorityCoefficient, 1e-9, "Non-numeric value should fall back")
        assertEquals(defaults.miscWeight, result.miscWeight, 1e-9, "NaN value should fall back")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Dynamic per-stat stat caps (July 2026)

    @Test
    @DisplayName("getScenarioStatCap: per-scenario per-stat caps")
    fun getScenarioStatCapPerScenario() {
        // URA Finale: all five stats cap at 1400.
        for (stat in StatName.entries) {
            assertEquals(1400, getScenarioStatCap("URA Finale", stat), "URA Finale $stat cap should be 1400")
        }
        // Unity Cup: SPD/STA/POW/GUTS 1300, WIT 1800.
        assertEquals(1300, getScenarioStatCap("Unity Cup", StatName.SPEED), "Unity Cup Speed cap")
        assertEquals(1300, getScenarioStatCap("Unity Cup", StatName.STAMINA), "Unity Cup Stamina cap")
        assertEquals(1300, getScenarioStatCap("Unity Cup", StatName.POWER), "Unity Cup Power cap")
        assertEquals(1300, getScenarioStatCap("Unity Cup", StatName.GUTS), "Unity Cup Guts cap")
        assertEquals(1800, getScenarioStatCap("Unity Cup", StatName.WIT), "Unity Cup Wit cap")
        // Trackblazer: STA 1900, WIT 1500, everything else 1200.
        assertEquals(1900, getScenarioStatCap("Trackblazer", StatName.STAMINA), "Trackblazer Stamina cap")
        assertEquals(1500, getScenarioStatCap("Trackblazer", StatName.WIT), "Trackblazer Wit cap")
        assertEquals(1200, getScenarioStatCap("Trackblazer", StatName.SPEED), "Trackblazer Speed cap")
        assertEquals(1200, getScenarioStatCap("Trackblazer", StatName.POWER), "Trackblazer Power cap")
        // Unknown scenario falls back to 1200 for every stat.
        assertEquals(1200, getScenarioStatCap("Something Else", StatName.WIT), "Unknown scenario default cap")
    }

    @Test
    @DisplayName("softCapEffectivenessMultiplier: below 1200 full, above 1200 half, blended across the threshold")
    fun softCapEffectivenessMultiplierBlends() {
        val cap = 1400
        // Entire gain below the soft-cap threshold -> fully effective.
        assertEquals(1.0, softCapEffectivenessMultiplier(1000, 20, cap), 1e-9, "gain entirely below 1200")
        // Entire gain above the threshold -> half effective.
        assertEquals(0.5, softCapEffectivenessMultiplier(1300, 20, cap), 1e-9, "gain entirely above 1200")
        // Gain straddles the threshold (10 below at 1.0 + 10 above at 0.5 = 15 / 20).
        assertEquals(0.75, softCapEffectivenessMultiplier(1190, 20, cap), 1e-9, "gain straddling 1200")
        // Gain overflows the real cap (10 in the soft zone at 0.5 + 10 wasted above cap = 5 / 20).
        assertEquals(0.25, softCapEffectivenessMultiplier(1390, 20, cap), 1e-9, "gain overflowing the cap")
        // Already at the cap -> no effective gain.
        assertEquals(0.0, softCapEffectivenessMultiplier(1400, 20, cap), 1e-9, "already at cap")
        // No gain -> neutral multiplier.
        assertEquals(1.0, softCapEffectivenessMultiplier(1000, 0, cap), 1e-9, "no gain")
    }

    @Test
    @DisplayName("getCurrentStatCap: OCR'd statCaps override the per-scenario table")
    fun getCurrentStatCapUsesDynamicOverride() {
        // No override -> scenario table (URA -> 1400).
        val baseConfig = createDefaultConfig(scenario = "URA Finale")
        assertEquals(1400, getCurrentStatCap(StatName.SPEED, baseConfig), "no override falls back to the URA table cap")
        // An OCR'd cap (raised by sparks/inheritance/duels) wins over the static table.
        val dynamicConfig = createDefaultConfig(scenario = "URA Finale", statCaps = mapOf(StatName.SPEED to 1460))
        assertEquals(1460, getCurrentStatCap(StatName.SPEED, dynamicConfig), "OCR'd cap overrides the table")
        // A stat without an OCR'd entry still falls back to the table.
        assertEquals(1400, getCurrentStatCap(StatName.WIT, dynamicConfig), "unlisted stat falls back to the table cap")
    }

    @Test
    @DisplayName("calculateStatEfficiencyScore: a gain fully above 1200 is worth half a gain fully below")
    fun statEfficiencyAppliesSoftCap() {
        // Only Speed gains, so the summed score is just Speed's contribution. Both currents sit far above the Medium Speed target (800), so both land in the same top ratio bucket
        // and only the soft-cap multiplier differs: 1.0 fully below 1200 vs 0.5 fully above.
        val training = createDefaultTrainingOption(name = StatName.SPEED, statGains = statGainsToMap(intArrayOf(20, 0, 0, 0, 0)))
        val belowThreshold = mapOf(StatName.SPEED to 1000, StatName.STAMINA to 400, StatName.POWER to 400, StatName.GUTS to 400, StatName.WIT to 400)
        val aboveThreshold = mapOf(StatName.SPEED to 1300, StatName.STAMINA to 400, StatName.POWER to 400, StatName.GUTS to 400, StatName.WIT to 400)
        val scoreBelow = calculateStatEfficiencyScore(createDefaultConfig(trainingOptions = listOf(training), currentStats = belowThreshold), training)
        val scoreAbove = calculateStatEfficiencyScore(createDefaultConfig(trainingOptions = listOf(training), currentStats = aboveThreshold), training)
        assertTrue(scoreBelow > 0.0, "sanity: the below-threshold score is positive")
        assertEquals(scoreBelow * 0.5, scoreAbove, 1e-6, "a gain fully above 1200 should be half as effective")
    }
}
