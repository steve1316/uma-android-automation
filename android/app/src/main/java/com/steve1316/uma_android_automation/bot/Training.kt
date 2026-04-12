package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.ButtonTraining
import com.steve1316.uma_android_automation.components.ButtonTrainingGuts
import com.steve1316.uma_android_automation.components.ButtonTrainingPower
import com.steve1316.uma_android_automation.components.ButtonTrainingSpeed
import com.steve1316.uma_android_automation.components.ButtonTrainingStamina
import com.steve1316.uma_android_automation.components.ButtonTrainingWit
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.IconStatSkillHint
import com.steve1316.uma_android_automation.components.IconTrainingHeaderGuts
import com.steve1316.uma_android_automation.components.IconTrainingHeaderPower
import com.steve1316.uma_android_automation.components.IconTrainingHeaderSpeed
import com.steve1316.uma_android_automation.components.IconTrainingHeaderStamina
import com.steve1316.uma_android_automation.components.IconTrainingHeaderWit
import com.steve1316.uma_android_automation.components.LabelStatTableHeaderSkillPoints
import com.steve1316.uma_android_automation.components.LabelTrainingCannotPerform
import com.steve1316.uma_android_automation.components.LabelTrainingFailureChance
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * Handle the training process, including analysis of options, scoring recommendations, and execution.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign-specific data.
 */
class Training(private val game: Game, private val campaign: Campaign) {
    /** Map to store detected training options. */
    internal var trainingMap: MutableMap<StatName, TrainingOption> = mutableMapOf()

    /** Map to store training options that were skipped. */
    internal var skippedTrainingMap: MutableMap<StatName, TrainingOption> = mutableMapOf()

    /** List of training names that are restricted or unavailable. */
    private var restrictedTrainingNames: MutableSet<StatName> = mutableSetOf()

    /** List of analysis results cached for reuse during the current turn. */
    private var cachedAnalysisResults: List<TrainingAnalysisResult>? = null

    /** The current training scenario name. */
    private val scenario = game.scenario

    /** The current stat prioritization settings. */
    private val statPrioritizationRaw: List<StatName> = SettingsHelper.getStringArraySetting("training", "statPrioritization").map { StatName.fromName(it)!! }

    /** The final stat prioritization list. */
    internal val statPrioritization: List<StatName> = statPrioritizationRaw.ifEmpty { StatName.entries }

    /** The maximum allowed failure chance for training. */
    private val maximumFailureChance: Int = SettingsHelper.getIntSetting("training", "maximumFailureChance")

    /** Whether to skip training for stats at their cap. */
    private val disableTrainingOnMaxedStat: Boolean = SettingsHelper.getBooleanSetting("training", "disableTrainingOnMaxedStat")

    /** List of stats to prioritize for spark events. */
    private val focusOnSparkStatTarget: List<StatName> = SettingsHelper.getStringArraySetting("training", "focusOnSparkStatTarget").map { StatName.fromName(it)!! }

    /** Whether the rainbow training bonus is active. */
    private val enableRainbowTrainingBonus: Boolean = SettingsHelper.getBooleanSetting("training", "enableRainbowTrainingBonus")

    /** Whether to enable risky training logic. */
    private val enableRiskyTraining: Boolean = SettingsHelper.getBooleanSetting("training", "enableRiskyTraining")

    /** The minimum stat gain required for risky training. */
    private val riskyTrainingMinStatGain: Int = SettingsHelper.getIntSetting("training", "riskyTrainingMinStatGain")

    /** The maximum failure chance allowed for risky training. */
    private val riskyTrainingMaxFailureChance: Int = SettingsHelper.getIntSetting("training", "riskyTrainingMaxFailureChance")

    /** Whether to force Wit training during the Finale. */
    private val trainWitDuringFinale: Boolean = SettingsHelper.getBooleanSetting("training", "trainWitDuringFinale")

    /** Whether to prioritize skill hints. */
    private val enablePrioritizeSkillHints: Boolean = SettingsHelper.getBooleanSetting("training", "enablePrioritizeSkillHints")

    /** Whether to enable validation of training analysis. */
    private val enableTrainingAnalysisValidation: Boolean = SettingsHelper.getBooleanSetting("training", "enableTrainingAnalysisValidation")

    /** The minimum stat gain required for using a Good-Luck Charm. */
    private val minStatGainForCharm = SettingsHelper.getIntSetting("scenarioOverrides", "trackblazerMinStatGainForCharm", 30)

    /** Map of current stat targets. */
    private var statTargets: Map<StatName, Int> = emptyMap()

    /** Whether to ignore the stat cap when training. */
    private var ignoreStatCap: Boolean = false

    /** Set of stats that have already exceeded their cap buffer. */
    private val statsTrainedOverBuffer: MutableSet<StatName> = mutableSetOf()

    /** List of stat trainings to ignore. */
    private val blacklist: List<StatName?> = SettingsHelper.getStringArraySetting("training", "trainingBlacklist").map { StatName.fromName(it) }

    /** Whether the last analysis was skipped due to energy being too low (failure chance too high). */
    var needsEnergyRecovery: Boolean = false

    /** Whether this is the first training check of the turn. */
    internal var firstTrainingCheck = true

    /**
     * Retrieve the current stat cap for a given stat.
     *
     * @param statName The stat name.
     * @return The current maximum value for the specified stat.
     */
    private fun getCurrentStatCap(statName: StatName): Int {
        return getScenarioStatCap(game.scenario, statName)
    }

    /**
     * Store analysis results for a training during parallel processing.
     *
     * @property name The [StatName] associated with this training.
     * @property latch The [CountDownLatch] used for thread synchronization.
     * @property startTime The system time when the analysis started.
     */
    data class TrainingAnalysisResult(val name: StatName, val latch: CountDownLatch, val startTime: Long) {
        /** Map of stat names to their detected gain values. */
        var statGains: Map<StatName, Int> = mapOf()

        /** Map of stat names to their raw row values from OCR. */
        var statGainRowValues: Map<StatName, List<Int>> = emptyMap()

        /** List of stats that required manual correction during analysis. */
        var correctedStats: List<StatName> = emptyList()

        /** The detected failure chance percentage. */
        var failureChance: Int = -1

        /** List of detected relationship bar fill levels. */
        var relationshipBars: ArrayList<CustomImageUtils.BarFillResult> = arrayListOf()

        /** Total number of rainbow trainings detected. */
        var numRainbow: Int = 0

        /** Total number of Spirit Gauges that can currently be filled. */
        var numSpiritGaugesCanFill: Int = 0

        /** Total number of Spirit Gauges that are ready for a Spirit Explosion. */
        var numSpiritGaugesReadyToBurst: Int = 0

        /** Total number of detected skill hints. */
        var numSkillHints: Int = 0
    }

    /**
     * Store a completed training option with all its analyzed properties.
     *
     * @property name The [StatName] associated with this training.
     * @property statGains Map of stat names to their detected gain values.
     * @property correctedStats List of stats that required manual correction.
     * @property failureChance The detected failure chance percentage.
     * @property relationshipBars List of detected relationship bar fill levels.
     * @property numRainbow Total number of rainbow trainings detected.
     * @property numSpiritGaugesCanFill Total number of fillable Spirit Gauges.
     * @property numSpiritGaugesReadyToBurst Total number of Spirit Gauges ready to burst.
     * @property numSkillHints Total number of detected skill hints.
     * @property skipReason Optional reason if this training was skipped during recommendation.
     */
    data class TrainingOption(
        val name: StatName,
        val statGains: Map<StatName, Int>,
        val correctedStats: List<StatName> = emptyList(),
        val failureChance: Int,
        val relationshipBars: ArrayList<CustomImageUtils.BarFillResult>,
        val numRainbow: Int,
        val numSpiritGaugesCanFill: Int = 0,
        val numSpiritGaugesReadyToBurst: Int = 0,
        val numSkillHints: Int = 0,
        val skipReason: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TrainingOption

            if (failureChance != other.failureChance) return false
            if (name != other.name) return false
            if (statGains != other.statGains) return false
            if (correctedStats != other.correctedStats) return false
            if (relationshipBars != other.relationshipBars) return false
            if (numRainbow != other.numRainbow) return false
            if (numSpiritGaugesCanFill != other.numSpiritGaugesCanFill) return false
            if (numSpiritGaugesReadyToBurst != other.numSpiritGaugesReadyToBurst) return false
            if (numSkillHints != other.numSkillHints) return false
            if (skipReason != other.skipReason) return false

            return true
        }

        override fun hashCode(): Int {
            var result = failureChance
            result = 31 * result + name.hashCode()
            result = 31 * result + statGains.entries.hashCode()
            result = 31 * result + correctedStats.hashCode()
            result = 31 * result + relationshipBars.hashCode()
            result = 31 * result + numRainbow
            result = 31 * result + numSpiritGaugesCanFill
            result = 31 * result + numSpiritGaugesReadyToBurst
            result = 31 * result + numSkillHints
            result = 31 * result + (skipReason?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Store configuration for training scoring calculations.
     *
     * @property currentStats Map of current character stats.
     * @property statPrioritization Ordered list of stat priorities.
     * @property statTargets Map of target values for each stat.
     * @property currentDate The current in-game date.
     * @property scenario The current training scenario name.
     * @property enableRainbowTrainingBonus Whether the rainbow training bonus is active.
     * @property focusOnSparkStatTarget List of stats to prioritize for spark events.
     * @property blacklist List of stat trainings to ignore.
     * @property disableTrainingOnMaxedStat Whether to skip training for stats at their cap.
     * @property trainingOptions List of all analyzed training options.
     * @property skillHintsPerLocation Map of detected skill hints for each training.
     * @property enablePrioritizeSkillHints Whether to prioritize skill hints.
     * @property statsTrainedOverBuffer Set of stats that have already exceeded their cap buffer.
     */
    data class TrainingConfig(
        // Global configuration.
        val currentStats: Map<StatName, Int>,
        val statPrioritization: List<StatName>,
        val statTargets: Map<StatName, Int>,
        val currentDate: GameDate,
        val scenario: String,
        val enableRainbowTrainingBonus: Boolean,
        val focusOnSparkStatTarget: List<StatName>,
        val blacklist: List<StatName?> = emptyList(),
        val disableTrainingOnMaxedStat: Boolean = false,
        val trainingOptions: List<TrainingOption>,
        val skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { 0 },
        val enablePrioritizeSkillHints: Boolean = false,
        val statsTrainedOverBuffer: Set<StatName> = emptySet(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TrainingConfig

            if (currentStats != other.currentStats) return false
            if (statPrioritization != other.statPrioritization) return false
            if (statTargets != other.statTargets) return false
            if (currentDate != other.currentDate) return false
            if (scenario != other.scenario) return false
            if (enableRainbowTrainingBonus != other.enableRainbowTrainingBonus) return false
            if (focusOnSparkStatTarget != other.focusOnSparkStatTarget) return false
            if (blacklist != other.blacklist) return false
            if (disableTrainingOnMaxedStat != other.disableTrainingOnMaxedStat) return false
            if (trainingOptions != other.trainingOptions) return false
            if (skillHintsPerLocation != other.skillHintsPerLocation) return false
            if (enablePrioritizeSkillHints != other.enablePrioritizeSkillHints) return false
            if (statsTrainedOverBuffer != other.statsTrainedOverBuffer) return false

            return true
        }

        override fun hashCode(): Int {
            var result = currentStats.hashCode()
            result = 31 * result + statPrioritization.hashCode()
            result = 31 * result + statTargets.hashCode()
            result = 31 * result + currentDate.hashCode()
            result = 31 * result + scenario.hashCode()
            result = 31 * result + enableRainbowTrainingBonus.hashCode()
            result = 31 * result + focusOnSparkStatTarget.hashCode()
            result = 31 * result + blacklist.hashCode()
            result = 31 * result + disableTrainingOnMaxedStat.hashCode()
            result = 31 * result + trainingOptions.hashCode()
            result = 31 * result + skillHintsPerLocation.hashCode()
            result = 31 * result + enablePrioritizeSkillHints.hashCode()
            result = 31 * result + statsTrainedOverBuffer.hashCode()
            return result
        }
    }

    companion object {
        /** The logging tag for this class. */
        private val TAG: String = "[${MainActivity.loggerTag}]Training"

        /**
         * Retrieve the scenario-specific cap for a given stat.
         *
         * @param scenario The current training scenario.
         * @param statName The stat name.
         * @return The maximum value for the specified stat.
         */
        fun getScenarioStatCap(scenario: String, statName: StatName): Int {
            return 1200
        }

        /**
         * Retrieve the current stat cap based on the provided configuration.
         *
         * @param statName The stat name.
         * @param config The current [TrainingConfig].
         * @return The current maximum value for the specified stat.
         */
        fun getCurrentStatCap(statName: StatName, config: TrainingConfig): Int {
            return getScenarioStatCap(config.scenario, statName)
        }

        /** Stats gained per finale race win, per stat. Slightly above the actual +10 to account for misc event/card gains. */
        private const val FINALE_RACE_STAT_BONUS = 15

        /**
         * Calculate the number of remaining finale races based on the current turn.
         *
         * Finale races occur on turns 73, 74, and 75. Before the finale (turn <= 72), all 3 races remain.
         *
         * @param currentDay The current turn number (1-75).
         * @return The number of remaining finale races (0-3).
         */
        fun getRemainingFinaleRaces(currentDay: Int): Int {
            return (75 - maxOf(currentDay, 72)).coerceAtLeast(0)
        }

        /**
         * Calculate the expected total stat bonus from remaining finale race wins.
         *
         * @param currentDay The current turn number (1-75).
         * @return The expected stat gain per stat from remaining finale races.
         */
        fun getFinaleStatBonus(currentDay: Int): Int {
            return getRemainingFinaleRaces(currentDay) * FINALE_RACE_STAT_BONUS
        }

        /**
         * Cross-validate failure chances and return corrected values.
         *
         * Failure chances are monotonically non-decreasing in training order
         * (Speed <= Stamina <= Power <= Guts <= Wit). If an earlier training's
         * failure chance is significantly lower than the next, it is likely an
         * OCR misread and should be corrected upward.
         *
         * @param failureChances List of ([StatName], failureChance) pairs with valid (>= 0) values.
         * @param suspiciousJumpThreshold Minimum difference to consider suspicious.
         * @return Map of [StatName] to corrected failure chance.
         */
        fun crossValidateFailureChances(
            failureChances: List<Pair<StatName, Int>>,
            suspiciousJumpThreshold: Int = 20,
        ): Map<StatName, Int> {
            if (failureChances.size < 2) return failureChances.toMap()

            val sorted = failureChances.sortedBy { it.first.ordinal }.toMutableList()

            // Walk backwards: correct any value that is suspiciously lower than its successor.
            for (i in sorted.size - 2 downTo 0) {
                val (currentName, currentChance) = sorted[i]
                val (_, nextChance) = sorted[i + 1]
                if (nextChance - currentChance > suspiciousJumpThreshold) {
                    sorted[i] = currentName to nextChance
                }
            }

            return sorted.toMap()
        }

        /**
         * Score the training option based on friendship bar progress.
         *
         * This method prefers training options with the least relationship progress, specifically focusing on blue bars.
         *
         * @param training The [TrainingOption] to score.
         * @return A score representing the relationship-building value.
         */
        fun scoreFriendshipTraining(training: TrainingOption): Double {
            // Ignore the blacklist in favor of making sure we build up the relationship bars as fast as possible.
            MessageLog.v(TAG, "\n[TRAINING] Starting process to score ${training.name} Training with a focus on building relationship bars.")

            val barResults = training.relationshipBars
            if (barResults.isEmpty()) return Double.NEGATIVE_INFINITY

            var score = 0.0
            for (bar in barResults) {
                val contribution =
                    when (bar.dominantColor) {
                        "orange" -> 0.0
                        "green" -> 1.0
                        "blue" -> 2.5
                        else -> 0.0
                    }
                score += contribution
            }

            val scoreString: String = String.format("%.2f", score)
            MessageLog.i(TAG, "[TRAINING] ${training.name} Training has a score of $scoreString with a focus on building ${barResults.size} relationship bars.")
            return score
        }

        /**
         * Score training options for the Unity Cup scenario based on a redirected priority system.
         *
         * The priority order is as follows:
         * 1. Stat Efficiency: Raw stat gains toward targets.
         * 2. Spirit Explosion: Trainings with gauges ready to burst.
         * 3. Gauge Filling: Trainings that can fill Spirit Explosion gauges.
         * 4. Relationship: Relationship building.
         *
         * @param config The [TrainingConfig] containing global scoring inputs.
         * @param training The [TrainingOption] to score.
         * @return A score representing the Unity Cup training value.
         */
        fun scoreUnityCupTraining(config: TrainingConfig, training: TrainingOption): Double {
            MessageLog.v(TAG, "\n[TRAINING] Starting process to score ${training.name} Training for Unity Cup with redirected priority: Stats > Burst > Filling.")

            // 1. Primary Priority: Stat Efficiency.
            var score = calculateStatEfficiencyScore(config, training)
            MessageLog.i(TAG, "[TRAINING] [${training.name}] Base stat efficiency score: ${String.format("%.2f", score)}")

            // 2. Second Priority: Trainings with Spirit Explosion Gauges ready to burst.
            if (training.numSpiritGaugesReadyToBurst > 0) {
                // We give a significant bonus for bursting, but not so much that it always overrides huge stat gains elsewhere.
                val burstBonus = 800.0 + (training.numSpiritGaugesReadyToBurst * 400.0)
                score += burstBonus
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Adding burst bonus for ${training.numSpiritGaugesReadyToBurst} gauge(s): $burstBonus")

                // Facility preference bonuses for bursting.
                when (training.name) {
                    StatName.SPEED -> {
                        score += 200.0
                    }

                    // Best for increased speed stat gains.
                    StatName.WIT -> {
                        score += 200.0
                    }

                    // Best for energy recovery and slightly increased speed stat gain.
                    StatName.STAMINA, StatName.POWER -> {
                        val currentStat = config.currentStats[training.name] ?: 0
                        val targetStat = config.statTargets[training.name] ?: 600
                        // Can be exploded if lacking stats.
                        if (currentStat < targetStat * 0.8) {
                            score += 150.0
                        }
                    }

                    StatName.GUTS -> {
                        // Guts is not ideal, but can be worth it if building up gauges to max them out for bursting.
                        if (training.numSpiritGaugesCanFill >= 2) {
                            score += 100.0 // Building up multiple gauges to allow for bursting.
                        } else {
                            score -= 50.0 // Not ideal without building up multiple gauges.
                        }
                    }
                }
            }

            // 3. Third Priority: Trainings that can fill Spirit Explosion Gauges (not at 100% yet).
            if (training.numSpiritGaugesCanFill > 0) {
                // Score increases with number of gauges that can be filled.
                // Each gauge fills by 25% per training execution.
                val fillBonus = 300.0 + (training.numSpiritGaugesCanFill * 100.0)
                score += fillBonus
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Training can fill ${training.numSpiritGaugesCanFill} Spirit Explosion Gauge(s). Adding fill bonus: $fillBonus")

                // Early game: If gauges can be filled for deprioritized stat trainings, ignore stat prioritization.
                if (config.currentDate.year == DateYear.JUNIOR) {
                    score += 200.0
                    MessageLog.i(TAG, "[TRAINING] [${training.name}] Early game bonus for gauge filling.")
                }
            }

            // 4. Fourth Priority: Relationship bars.
            if (training.relationshipBars.isNotEmpty()) {
                val relationshipScore = calculateRelationshipScore(config, training)
                val scaledRelationshipScore = relationshipScore * 1.5 // Scaled to be a significant bonus but below bursting.
                score += scaledRelationshipScore
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Adding relationship bonus: ${String.format("%.2f", scaledRelationshipScore)}.")
            }

            // Rainbow Training Bonus synergy.
            if (training.numRainbow > 0 && config.currentDate.year > DateYear.JUNIOR) {
                var rainbowBonusScore = 0.0
                for (i in 1 until training.numRainbow + 1) {
                    rainbowBonusScore += 200 * (0.5).pow(i)
                }
                if (rainbowBonusScore > 0) {
                    MessageLog.i(TAG, "[TRAINING] [${training.name}] Adding bonus score for ${training.numRainbow} rainbow trainings: $rainbowBonusScore")
                    score += rainbowBonusScore
                }
            }

            val scoreString: String = String.format("%.2f", score)
            MessageLog.v(TAG, "[TRAINING] [${training.name}] Training has a Unity Cup score of $scoreString.")
            return score
        }

        /**
         * Calculate the stat efficiency score based on the ratio completion toward targets.
         *
         * This method treats stat targets as desired ratios and scores training based on how well it balances the overall stat distribution.
         *
         * @param config The [TrainingConfig] containing global scoring inputs.
         * @param training The [TrainingOption] to score.
         * @return The raw score representing stat efficiency.
         */
        fun calculateStatEfficiencyScore(config: TrainingConfig, training: TrainingOption): Double {
            var score = 0.0

            for (statName in StatName.entries) {
                val currentStat = config.currentStats[statName] ?: 0
                val targetStat = config.statTargets[statName] ?: 0
                val statGain = training.statGains[statName] ?: 0

                if (statGain > 0 && targetStat > 0) {
                    val priorityIndex = config.statPrioritization.indexOf(statName)

                    // Calculate completion percentage (how far along this stat is toward its target).
                    val completionPercent = (currentStat.toDouble() / targetStat) * 100.0

                    // Ratio-based multiplier: Stats furthest behind get the highest priority.
                    val ratioMultiplier =
                        when {
                            completionPercent < 30.0 -> 5.0

                            // Severely behind.
                            completionPercent < 50.0 -> 4.0

                            // Significantly behind.
                            completionPercent < 70.0 -> 3.0

                            // Moderately behind.
                            completionPercent < 90.0 -> 2.0

                            // Slightly behind.
                            completionPercent < 110.0 -> 1.0

                            // At target.
                            completionPercent < 130.0 -> 0.5

                            // Slightly over.
                            else -> 0.3 // Well over.
                        }

                    // Priority-based tiebreaker (only applies when completion is similar).
                    // Find the completion percentage of the highest priority stat for comparison.
                    val highestPriorityStat: StatName? = config.statPrioritization.firstOrNull()
                    val highestPriorityCompletion =
                        if (highestPriorityStat != null) {
                            val hpCurrent = config.currentStats[highestPriorityStat] ?: 0
                            val hpTarget = config.statTargets[highestPriorityStat] ?: 1
                            (hpCurrent.toDouble() / hpTarget) * 100.0
                        } else {
                            completionPercent
                        }

                    // Only apply priority bonus if this stat's completion is within 10% of highest priority stat.
                    val priorityMultiplier =
                        if (priorityIndex != -1 && kotlin.math.abs(completionPercent - highestPriorityCompletion) <= 10.0) {
                            1.0 + (0.1 * (config.statPrioritization.size - priorityIndex))
                        } else {
                            1.0
                        }

                    // Main stat gain bonus: If training improves its MAIN stat by a large amount, it is most likely an undetected rainbow.
                    val isMainStat = training.name == statName
                    val mainStatBonus =
                        if (isMainStat && statGain >= 30) {
                            2.0
                        } else {
                            1.0
                        }

                    // Spark bonus: Prioritize training sessions for 3* sparks for selected stats below 600 if the setting is enabled.
                    val isSparkStat = statName in config.focusOnSparkStatTarget
                    val canTriggerSpark = currentStat < 600
                    val sparkBonus =
                        if (isSparkStat && canTriggerSpark) {
                            MessageLog.i(TAG, "[TRAINING] $statName is at $currentStat (< 600). Prioritizing this training for potential spark event to get above 600.")
                            2.5
                        } else {
                            1.0
                        }

                    val bonusNote = if (isMainStat && statGain >= 30) " [HIGH MAIN STAT]" else ""
                    val sparkNote = if (isSparkStat && canTriggerSpark) " [SPARK PRIORITY]" else ""
                    val completionString: String = String.format("%.2f", completionPercent)
                    val ratioMultiplierString: String = String.format("%.2f", ratioMultiplier)
                    val priorityMultiplierString: String = String.format("%.2f", priorityMultiplier)
                    Log.d(
                        TAG,
                        "$statName: gain=$statGain, completion=$completionString%, " +
                            "ratioMultiplierString=$ratioMultiplierString, priorityMultiplierString=${priorityMultiplierString}$bonusNote$sparkNote",
                    )

                    // Calculate final score for this stat.
                    var statScore = statGain.toDouble()
                    statScore *= ratioMultiplier
                    statScore *= priorityMultiplier
                    statScore *= mainStatBonus
                    statScore *= sparkBonus

                    score += statScore
                }
            }

            return score
        }

        /**
         * Calculate the relationship building score with diminishing returns.
         *
         * This method evaluates relationship bars based on their color and fill level, applying diminishing returns as bars fill up and early game bonuses.
         *
         * @param config The [TrainingConfig] containing global scoring inputs.
         * @param training The [TrainingOption] to score.
         * @return A normalized score (0-100) representing the relationship building value.
         */
        fun calculateRelationshipScore(config: TrainingConfig, training: TrainingOption): Double {
            if (training.relationshipBars.isEmpty()) return 0.0

            var score = 0.0
            var maxScore = 0.0

            for (bar in training.relationshipBars) {
                val baseValue =
                    when (bar.dominantColor) {
                        "orange" -> 0.0
                        "green" -> 1.0
                        "blue" -> 2.5
                        else -> 0.0
                    }

                if (baseValue > 0) {
                    // Apply diminishing returns for relationship building.
                    val fillLevel = bar.fillPercent / 100.0
                    // Less valuable as bars fill up.
                    val diminishingFactor = 1.0 - (fillLevel * 0.5)

                    // Early game bonus for relationship building.
                    val earlyGameBonus = if (config.currentDate.year == DateYear.JUNIOR || config.currentDate.bIsPreDebut) 1.3 else 1.0

                    // Trainer support bonus to prioritize them slightly above regular supports.
                    val trainerSupportBonus = if (bar.isTrainerSupport) 1.15 else 1.0

                    val contribution = baseValue * diminishingFactor * earlyGameBonus * trainerSupportBonus
                    score += contribution
                    maxScore += 2.5 * 1.3
                }
            }

            return if (maxScore > 0) (score / maxScore * 100.0) else 0.0
        }

        /**
         * Calculate miscellaneous bonuses and penalties based on training properties.
         *
         * This method applies bonuses for skill hints that provide additional value to training sessions.
         *
         * @param config The [TrainingConfig] containing global scoring inputs.
         * @param training The [TrainingOption] to score.
         * @return A misc score between 0 and 100 representing situational bonuses.
         */
        fun calculateMiscScore(config: TrainingConfig, training: TrainingOption): Double {
            // Start with neutral score.
            var score = 50.0

            val numSkillHints: Int = config.skillHintsPerLocation[training.name] ?: 0
            score += 10.0 * numSkillHints

            // If skill hints are prioritized, and we found some, return a massive score to override other factors.
            // This handles the case where skill hints only become visible after a training is selected.
            if (config.enablePrioritizeSkillHints && numSkillHints > 0) {
                return 10000.0 + score
            }

            return score.coerceIn(0.0, 100.0)
        }

        /**
         * Calculate the raw training score without normalization.
         *
         * This method calculates raw high-level scores that will later be normalized based on the actual maximum score in the current training session.
         *
         * @param config The [TrainingConfig] containing global scoring inputs.
         * @param training The [TrainingOption] to score.
         * @return The raw score representing overall training value.
         */
        fun calculateRawTrainingScore(config: TrainingConfig, training: TrainingOption): Double {
            if (training.name in config.blacklist) {
                return 0.0
            }

            val currentStat: Int = config.currentStats.getOrDefault(training.name, 0)
            val potentialStat: Int = currentStat + training.statGains.getOrElse(training.name) { 0 }
            val statCap = getCurrentStatCap(training.name, config)
            val finaleBonus = getFinaleStatBonus(config.currentDate.day)
            val effectiveStatCap = statCap - 100 - finaleBonus

            // Don't score for stats that are close to the absolute cap.
            if (currentStat >= statCap) {
                return 0.0
            }

            // Don't score for stats that are already above the buffer, unless it's a rainbow training
            // and this stat haven't used its one-time allowance yet.
            if (config.disableTrainingOnMaxedStat && currentStat >= effectiveStatCap) {
                val canUseAllowance = training.numRainbow > 0 && training.name !in config.statsTrainedOverBuffer
                if (!canUseAllowance) {
                    return 0.0
                } else {
                    MessageLog.i(TAG, "[TRAINING] [${training.name}] Current stat ($currentStat) is at or over buffer ($effectiveStatCap), but allowing one-time rainbow training.")
                }
            }

            if (potentialStat >= effectiveStatCap) {
                val canUseAllowance = training.numRainbow > 0 && training.name !in config.statsTrainedOverBuffer
                if (!canUseAllowance) {
                    return 0.0
                } else {
                    MessageLog.i(TAG, "[TRAINING] [${training.name}] Potential stat ($potentialStat) would be over buffer ($effectiveStatCap), but allowing one-time rainbow training.")
                }
            }

            var totalScore = 0.0

            // 1. Stat Efficiency scoring
            val statScore = calculateStatEfficiencyScore(config, training)

            // 2. Friendship scoring
            val relationshipScore = calculateRelationshipScore(config, training)

            // 3. Misc-aware scoring
            val miscScore = calculateMiscScore(config, training)

            // Define scoring weights based on relationship bars presence.
            val statWeight = if (training.relationshipBars.isNotEmpty()) 0.6 else 0.7
            val relationshipWeight = if (training.relationshipBars.isNotEmpty()) 0.1 else 0.0
            val miscWeight = 0.3

            // Calculate weighted total score.
            totalScore += statScore * statWeight
            totalScore += relationshipScore * relationshipWeight
            totalScore += miscScore * miscWeight

            // 4. Rainbow training multiplier (Year 2+ only).
            // Rainbow is heavily favored because it improves overall ratio balance.
            val rainbowMultiplier =
                if (training.numRainbow > 0 && config.currentDate.year > DateYear.JUNIOR) {
                    if (config.enableRainbowTrainingBonus) {
                        MessageLog.i(TAG, "[TRAINING] [${training.name}] ${training.numRainbow} rainbows detected. Adding multiplier to score.")
                        2.0
                    } else {
                        MessageLog.i(TAG, "[TRAINING] [${training.name}] ${training.numRainbow} rainbows detected, but rainbow training bonus is not enabled.")
                        1.5
                    }
                } else {
                    1.0
                }

            // Apply rainbow multiplier to total score.
            totalScore *= rainbowMultiplier

            return totalScore.coerceAtLeast(0.0)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /**
     * Start a single training OCR test for debugging.
     *
     * This method performs OCR on a single training screen and prints the results to the log.
     */
    fun startSingleTrainingOCRTest() {
        MessageLog.v(TAG, "[TEST] Starting Single Training OCR Test.")

        // Detect which training is currently selected on screen.
        val trainingName =
            when {
                IconTrainingHeaderSpeed.check(game.imageUtils) -> {
                    StatName.SPEED
                }

                IconTrainingHeaderStamina.check(game.imageUtils) -> {
                    StatName.STAMINA
                }

                IconTrainingHeaderPower.check(game.imageUtils) -> {
                    StatName.POWER
                }

                IconTrainingHeaderGuts.check(game.imageUtils) -> {
                    StatName.GUTS
                }

                IconTrainingHeaderWit.check(game.imageUtils) -> {
                    StatName.WIT
                }

                else -> {
                    MessageLog.e(TAG, "[ERROR] startSingleTrainingOCRTest:: Could not detect which training is currently selected on screen. Aborting test.")
                    return
                }
            }

        analyzeTrainings(mapOf("singleTraining" to true))
        val result = trainingMap[trainingName]
        if (result != null) {
            MessageLog.v(TAG, "[TEST] OCR Results for $trainingName: $result")
        } else {
            MessageLog.e(TAG, "[ERROR] startSingleTrainingOCRTest:: OCR failed for $trainingName.")
        }
    }

    /**
     * Start a comprehensive training OCR test for debugging.
     *
     * This method performs OCR on all available training screens and prints the results to the log.
     */
    fun startComprehensiveTrainingOCRTest() {
        MessageLog.v(TAG, "[TEST] Starting Comprehensive Training OCR Test.")

        analyzeTrainings()
        val result = trainingMap
        MessageLog.v(TAG, "[TEST] Comprehensive OCR Results: $result")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Analyze all available training options to determine their stat gains, relationship progress, and other details.
     *
     * @param args A map containing optional parameters:
     *             - "test" (Boolean): Whether to force high failure chance trainings through for testing purposes.
     *             - "singleTraining" (Boolean): Whether to analyze only the currently displayed training on the screen.
     *             - "ignoreFailureChance" (Boolean): Whether to bypass the failure chance threshold check.
     *             - "isIrregularEvaluation" (Boolean): Whether this analysis is for an irregular training evaluation.
     */
    fun analyzeTrainings(args: Map<String, Any?> = emptyMap()) {
        needsEnergyRecovery = false
        val test = args["test"] as? Boolean ?: false
        val singleTraining = args["singleTraining"] as? Boolean ?: false
        val ignoreFailureChance = args["ignoreFailureChance"] as? Boolean ?: false
        val isIrregularEvaluation = args["isIrregularEvaluation"] as? Boolean ?: false

        // Skip training analysis entirely when energy is depleted and no charm is available to offset the failure chance.
        if (!test && !ignoreFailureChance && !campaign.checkFinals() && campaign.trainee.energy <= 0) {
            MessageLog.i(TAG, "[TRAINING] Skipping training analysis as energy is ${campaign.trainee.energy}% with no Good-Luck Charm to offset failure chance.")
            needsEnergyRecovery = true
            trainingMap.clear()
            skippedTrainingMap.clear()
            return
        }

        if (test) {
            MessageLog.v(TAG, "\n[TRAINING] Now starting process to analyze all 5 Trainings for Testing.")
        } else if (singleTraining) {
            MessageLog.v(TAG, "\n[TRAINING] Now starting process to analyze the training on screen.")
        } else if (cachedAnalysisResults != null) {
            MessageLog.i(TAG, "[TRAINING] Using cached training analysis results for this turn.")
            processAnalysisResults(cachedAnalysisResults!!, ignoreFailureChance, isIrregularEvaluation, test)
            return
        } else {
            MessageLog.v(TAG, "\n[TRAINING] Now starting process to analyze all 5 Trainings.")
        }

        val trainingButtons: Map<StatName, ComponentInterface> =
            mapOf(
                StatName.SPEED to ButtonTrainingSpeed,
                StatName.STAMINA to ButtonTrainingStamina,
                StatName.POWER to ButtonTrainingPower,
                StatName.GUTS to ButtonTrainingGuts,
                StatName.WIT to ButtonTrainingWit,
            )

        val iconTrainingHeaders: Map<StatName, ComponentInterface> =
            mapOf(
                StatName.SPEED to IconTrainingHeaderSpeed,
                StatName.STAMINA to IconTrainingHeaderStamina,
                StatName.POWER to IconTrainingHeaderPower,
                StatName.GUTS to IconTrainingHeaderGuts,
                StatName.WIT to IconTrainingHeaderWit,
            )

        /**
         * Detects the current active (selected) stat in the training screen.
         *
         * @param timeoutMs The max time (in milliseconds) for the operation to run before it times out.
         * @return On success, the [StatName] of the active stat. On error or timeout, null is returned.
         */
        fun getActiveStat(timeoutMs: Int = 5000): StatName? {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val bitmap: Bitmap = game.imageUtils.getSourceBitmap()

                // Using threads here is slower only if the active tab is Speed.
                // Stamina was about even, then each stat after that using threads gained an additional 100ms improvement.
                val waitLatch = CountDownLatch(5)
                val matchFound = AtomicBoolean(false)
                val matchMap = ConcurrentHashMap<StatName, Boolean>()
                for ((statName, header) in iconTrainingHeaders) {
                    Thread {
                        try {
                            if (!BotService.isRunning) {
                                return@Thread
                            }

                            // Exit thread early if we already found a match.
                            if (matchFound.get()) {
                                return@Thread
                            }
                            // Return immediately if we get a match.
                            val bIsFound = header.check(game.imageUtils, sourceBitmap = bitmap)
                            matchMap[statName] = bIsFound
                            if (bIsFound) {
                                matchFound.set(true)
                                return@Thread
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[ERROR] getActiveStat:: Error detecting stat header $statName: ${e.stackTraceToString()}")
                            matchMap[statName] = false
                        } finally {
                            waitLatch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }

                // Collect our threads.
                try {
                    waitLatch.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    MessageLog.e(TAG, "[ERROR] getActiveStat:: Stat header detection threads timed out.")
                }

                // If we got a match, then return it. Otherwise, continue the loop.
                val match = matchMap.entries.firstOrNull { it.value }
                if (match != null) {
                    return match.key
                }
            }

            MessageLog.w(TAG, "[WARN] getActiveStat:: Timed out while trying to detect the active stat.")
            return null
        }

        /**
         * Navigates to the specified training stat page in the training screen.
         *
         * @param statName The stat to switch to.
         * @param timeoutMs The max time (in milliseconds) for the operation to run before it times out.
         * @return Whether we successfully navigated to the specified training page.
         */
        fun goToStat(statName: StatName, timeoutMs: Int = 5000): Boolean {
            val startTime = System.currentTimeMillis()

            // KeyError indicates programmer error.
            val header: ComponentInterface = iconTrainingHeaders[statName]!!
            val button: ComponentInterface = trainingButtons[statName]!!

            // Fast early check if we're already at the stat.
            // Helps to do this before calling getActiveStat so we can save time.
            if (header.check(game.imageUtils)) {
                return true
            }

            // If this option isn't enabled, then we just do a fast lazy validation.
            if (!enableTrainingAnalysisValidation) {
                for (i in 0..2) {
                    button.click(game.imageUtils)

                    // Wait for screen to finish updating before proceeding.
                    game.wait(0.2, skipWaitingForLoading = true)
                    if (header.check(game.imageUtils)) {
                        return true
                    }
                }

                MessageLog.w(TAG, "[WARN] goToStat:: Failed to go to $statName on training screen after 3 attempts.")
                return false
            }

            // Perform full validation.

            val activeStat: StatName? = getActiveStat(timeoutMs)
            if (activeStat == null) {
                MessageLog.w(TAG, "[WARN] goToStat:: getActiveStat returned null.")
                return false
            }

            // If we're already at the stat, return early.
            // Otherwise, we may accidentally click the button to train the stat.
            if (activeStat == statName) {
                return true
            }

            // Now click on the desired stat button.
            button.click(game.imageUtils)

            // Now wait for the header to be detected.
            // In case the previous operations took too long, we still want to do
            // at least one check for the header before we time out since it doesn't
            // take hardly any time to check just once.
            do {
                if (header.check(game.imageUtils)) {
                    return true
                }
            } while (System.currentTimeMillis() - startTime < timeoutMs)

            MessageLog.w(TAG, "[WARN] goToStat:: Timed out while waiting for $statName training header.")
            return false
        }

        // If not doing single training and speed training isn't active, make it active.
        if (!singleTraining && !goToStat(StatName.SPEED)) {
            MessageLog.w(TAG, "[WARN] analyzeTrainings:: Skipping training due to not being able to confirm whether the bot is at the training screen.")
            return
        }

        // List to store all training analysis results for parallel processing.
        val analysisResults = mutableListOf<TrainingAnalysisResult>()

        // Check if failure chance is acceptable: either within regular threshold or within risky threshold (if enabled).
        // This acts as an early exit from training analysis to speed up training.
        val failureChance: Int = game.imageUtils.findTrainingFailureChance(tries = 3)
        if (failureChance == -1) {
            MessageLog.w(TAG, "[WARN] analyzeTrainings:: Skipping training due to not being able to confirm whether or not the bot is at the Training screen.")
            return
        }
        val isWithinRegularThreshold = failureChance <= maximumFailureChance
        val isWithinRiskyThreshold = enableRiskyTraining && failureChance <= riskyTrainingMaxFailureChance
        val isFinals = campaign.checkFinals()
        if (test || isWithinRegularThreshold || isWithinRiskyThreshold || isFinals || ignoreFailureChance) {
            if (!test) {
                if (isWithinRegularThreshold) {
                    MessageLog.i(TAG, "[TRAINING] $failureChance% within acceptable range of $maximumFailureChance%. Proceeding to acquire all other percentages and total stat increases...")
                } else if (isWithinRiskyThreshold) {
                    MessageLog.i(
                        TAG,
                        "[TRAINING] $failureChance% exceeds regular threshold ($maximumFailureChance%) but is within risky training threshold ($riskyTrainingMaxFailureChance%). Proceeding to acquire all other percentages and total stat increases...",
                    )
                } else if (ignoreFailureChance) {
                    MessageLog.i(TAG, "[TRAINING] Flag set to ignore failure chance. Proceeding to acquire all other percentages and total stat increases...")
                } else if (isFinals) {
                    MessageLog.i(TAG, "[TRAINING] $failureChance% exceeds thresholds but it is the Finals. Ignoring and proceeding to acquire all other percentages and total stat increases...")
                }
            }

            // Early skill hint detection: If prioritization is enabled, scan for skill hints before analyzing trainings.
            // This ensures skill hints are detected even if some trainings are blacklisted.
            if (!test && enablePrioritizeSkillHints) {
                MessageLog.v(TAG, "[TRAINING] Skill hint prioritization is enabled. Scanning for skill hints before training analysis...")
                val skillHintLocations: ArrayList<Point> = IconStatSkillHint.findAll(game.imageUtils, region = game.imageUtils.regionBottomHalf)
                if (skillHintLocations.isNotEmpty()) {
                    MessageLog.i(TAG, "[TRAINING] Found ${skillHintLocations.size} skill hint(s) on the training screen. Tapping on the first skill hint location and skipping training analysis.")
                    val firstHint = skillHintLocations.first()

                    game.tap(firstHint.x, firstHint.y, IconStatSkillHint.template.path, taps = 3)
                    game.wait(1.0)
                    MessageLog.v(TAG, "[TRAINING] Process to execute skill hint training completed.")
                    return
                } else {
                    MessageLog.i(TAG, "[TRAINING] No skill hints found. Proceeding with normal training analysis.")
                }
            }

            // Now analyze each stat.
            for (statName in StatName.entries) {
                if (!test && statName in blacklist) {
                    MessageLog.i(TAG, "[TRAINING] Skipping $statName training due to being blacklisted.")
                    continue
                }

                // Keep iterating until the current training is found.
                if (singleTraining) {
                    val iconTrainingHeader = iconTrainingHeaders[statName]!!
                    if (!iconTrainingHeader.check(game.imageUtils)) {
                        continue
                    }
                    MessageLog.i(TAG, "[TRAINING] The $statName training is currently selected on the screen.")
                }

                // Only go to a different stat if we aren't doing single training.
                if (!singleTraining && !goToStat(statName)) {
                    MessageLog.e(TAG, "[ERROR] analyzeTrainings:: Failed to click training button for $statName. Aborting training...")
                    return
                }

                // Check if the currently selected training is restricted.
                if (LabelTrainingCannotPerform.check(game.imageUtils)) {
                    MessageLog.i(TAG, "[TRAINING] The currently selected $statName training is restricted and cannot be performed.")
                    restrictedTrainingNames.add(statName)
                    continue
                }

                // Get bitmaps and locations before starting threads to make them safe for parallel processing.
                val sourceBitmap = game.imageUtils.getSourceBitmap()
                val skillPointsLocation = LabelStatTableHeaderSkillPoints.find(game.imageUtils).first
                val failureChanceLocation = LabelTrainingFailureChance.find(game.imageUtils).first

                // Record start time for elapsed time measurement.
                val startTime = System.currentTimeMillis()

                // Unified approach: always use result object and start threads the same way.
                // Use CountDownLatch to run the operations in parallel to cut down on processing time.
                // Note: For parallel processing, Spirit Explosion Gauge is handled synchronously for Unity Cup, so latch count is 4.
                // For singleTraining, Spirit Explosion Gauge runs in a thread for Unity Cup, so latch count is 5.
                val latch = CountDownLatch(if (singleTraining && game.scenario == "Unity Cup") 5 else 4)

                // Create result object to store analysis state.
                val result =
                    TrainingAnalysisResult(
                        name = statName,
                        latch = latch,
                        startTime = startTime,
                    )

                // For Unity Cup in parallel mode, run Spirit Explosion Gauge analysis synchronously before moving to next training.
                // This ensures if retry is needed, it can take a new screenshot while still on the correct training.
                // For singleTraining mode, handle it in a thread like the other analyses.
                if (game.scenario == "Unity Cup" && BotService.isRunning && !singleTraining) {
                    val startTimeSpiritGauge = System.currentTimeMillis()
                    val gaugeResult = game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap)
                    if (gaugeResult != null) {
                        result.numSpiritGaugesCanFill = gaugeResult.numGaugesCanFill
                        result.numSpiritGaugesReadyToBurst = gaugeResult.numGaugesReadyToBurst
                    } else {
                        result.numSpiritGaugesCanFill = 0
                        result.numSpiritGaugesReadyToBurst = 0
                    }
                    Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to analyze Spirit Explosion Gauge for $statName: ${System.currentTimeMillis() - startTimeSpiritGauge}ms")
                }

                // Check if bot is still running before starting parallel threads.
                if (!BotService.isRunning) {
                    return
                }

                // Thread 1: Determine stat gains.
                Thread {
                    val startTimeStatGains = System.currentTimeMillis()
                    try {
                        if (skillPointsLocation != null) {
                            val statGainResult = game.imageUtils.determineStatGainFromTraining(statName, sourceBitmap, skillPointsLocation)
                            result.statGains = statGainResult.statGains
                            result.statGainRowValues = statGainResult.rowValuesMap
                            result.correctedStats = statGainResult.correctedStats
                        } else {
                            MessageLog.w(TAG, "[WARN] analyzeTrainings:: Skill points location was not found during OCR. Skipping stat gain detection for $statName.")
                            result.statGains = StatName.entries.associateWith { 0 }.toMap()
                            result.statGainRowValues = emptyMap()
                            result.correctedStats = emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERROR] analyzeTrainings:: Error in determineStatGainFromTraining: ${e.stackTraceToString()}")
                        result.statGains = StatName.entries.associateWith { 0 }.toMap()
                        result.statGainRowValues = emptyMap()
                        result.correctedStats = emptyList()
                    } finally {
                        latch.countDown()
                        val elapsedTime = System.currentTimeMillis() - startTimeStatGains
                        Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to determine stat gains for $statName: ${elapsedTime}ms")
                    }
                }.start()

                // Thread 2: Find failure chance.
                Thread {
                    val startTimeFailureChance = System.currentTimeMillis()
                    try {
                        if (failureChanceLocation != null) {
                            result.failureChance = game.imageUtils.findTrainingFailureChance(sourceBitmap, failureChanceLocation)
                        } else {
                            MessageLog.w(TAG, "[WARN] analyzeTrainings:: Failure chance location was not found during OCR. Skipping failure chance detection for $statName.")
                            result.failureChance = -1
                        }
                    } catch (e: Exception) {
                        MessageLog.e(TAG, "[ERROR] analyzeTrainings:: Error in findTrainingFailureChance: ${e.stackTraceToString()}")
                        result.failureChance = -1
                    } finally {
                        latch.countDown()
                        val elapsedTime = System.currentTimeMillis() - startTimeFailureChance
                        Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to determine failure chance for $statName: ${elapsedTime}ms")
                    }
                }.start()

                // Thread 3: Analyze relationship bars.
                Thread {
                    val startTimeRelationshipBars = System.currentTimeMillis()
                    try {
                        result.relationshipBars = game.imageUtils.analyzeRelationshipBars(sourceBitmap, statName, game.scenario)
                        result.numRainbow = result.relationshipBars.count { barFillResult -> barFillResult.isRainbow }
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERROR] analyzeTrainings:: Error in analyzeRelationshipBars: ${e.stackTraceToString()}")
                        result.relationshipBars = arrayListOf()
                    } finally {
                        latch.countDown()
                        val elapsedTime = System.currentTimeMillis() - startTimeRelationshipBars
                        Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to analyze relationship bars for $statName: ${elapsedTime}ms")
                    }
                }.start()

                // Thread 4: Detect skill hints.
                Thread {
                    val startTimeSkillHints = System.currentTimeMillis()
                    try {
                        val skillHintLocations: ArrayList<Point> =
                            IconStatSkillHint.findAll(
                                game.imageUtils,
                                sourceBitmap = sourceBitmap,
                                region = game.imageUtils.regionTopHalf,
                            )
                        result.numSkillHints = skillHintLocations.size
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERROR] analyzeTrainings:: Error in skill hint detection: ${e.stackTraceToString()}")
                        result.numSkillHints = 0
                    } finally {
                        latch.countDown()
                        val elapsedTime = System.currentTimeMillis() - startTimeSkillHints
                        Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to detect skill hints for $statName: ${elapsedTime}ms")
                    }
                }.start()

                // Thread 5: Analyze Spirit Explosion Gauges (Unity Cup only, singleTraining mode only).
                if (game.scenario == "Unity Cup" && singleTraining) {
                    Thread {
                        val startTimeSpiritGauge = System.currentTimeMillis()
                        try {
                            val gaugeResult = game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap)
                            if (gaugeResult != null) {
                                result.numSpiritGaugesCanFill = gaugeResult.numGaugesCanFill
                                result.numSpiritGaugesReadyToBurst = gaugeResult.numGaugesReadyToBurst
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[ERROR] analyzeTrainings:: Error in Spirit Explosion Gauge analysis: ${e.stackTraceToString()}")
                            result.numSpiritGaugesCanFill = 0
                            result.numSpiritGaugesReadyToBurst = 0
                        } finally {
                            latch.countDown()
                            Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time to analyze Spirit Explosion Gauge for $statName: ${System.currentTimeMillis() - startTimeSpiritGauge}ms")
                        }
                    }.start()
                }

                // Branch on singleTraining vs parallel processing.
                if (singleTraining) {
                    // For singleTraining, wait here and process immediately.
                    try {
                        latch.await(3, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Log.e(TAG, "[ERROR] analyzeTrainings:: Parallel training analysis timed out.")
                    } finally {
                        val elapsedTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time for $statName training analysis: ${elapsedTime}ms")
                        MessageLog.i(TAG, "[TRAINING] All 5 stat regions processed for $statName training. Results: ${result.statGains.toSortedMap(compareBy { it.ordinal })}")
                    }

                    applyContextualStatGainBoost(result)
                    MessageLog.i(TAG, "[TRAINING] Contextually boosted results: ${result.statGains.toSortedMap(compareBy { it.ordinal })}")

                    // Determine which failure chance threshold to use.
                    val effectiveFailureChance =
                        if (enableRiskyTraining) {
                            riskyTrainingMaxFailureChance
                        } else {
                            maximumFailureChance
                        }

                    // If we failed to detect a failure chance, fallback to the initial failure chance that was read as we first entered the training screen.
                    if (result.failureChance == -1) {
                        MessageLog.w(TAG, "[WARN] analyzeTrainings:: ${result.name} failure chance OCR failed. Falling back to the initial robustly read value of $failureChance%.")
                        result.failureChance = failureChance
                    }

                    if (result.failureChance == -1) {
                        MessageLog.w(TAG, "[WARN] analyzeTrainings:: Failed to analyze failure chance for $statName.")
                        continue
                    }

                    // For Risky Training, filter out trainings that exceed the effective failure chance threshold or do not meet the minimum main stat gain threshold.
                    val mainStatGain = result.statGains[result.name] ?: 0
                    if (!test && !ignoreFailureChance && result.failureChance > effectiveFailureChance) {
                        MessageLog.i(
                            TAG,
                            "[TRAINING] Skipping $statName training due to failure chance (${result.failureChance}%) exceeding the effective failure chance threshold ($effectiveFailureChance%).",
                        )
                        continue
                    }

                    if (!test && ignoreFailureChance && result.failureChance > effectiveFailureChance && mainStatGain < 30) {
                        MessageLog.i(
                            TAG,
                            "[TRAINING] Skipping $statName training with Good-Luck Charm because main stat gain ($mainStatGain) is less than 30 and failure chance (${result.failureChance}%) is risky.",
                        )
                        continue
                    }
                    if (!test && enableRiskyTraining && mainStatGain < riskyTrainingMinStatGain) {
                        MessageLog.i(TAG, "[TRAINING] Skipping $statName training due to main stat gain ($mainStatGain) not meeting minimum threshold ($riskyTrainingMinStatGain).")
                        continue
                    }

                    val newTraining =
                        TrainingOption(
                            name = result.name,
                            statGains = result.statGains,
                            correctedStats = result.correctedStats,
                            failureChance = result.failureChance,
                            relationshipBars = result.relationshipBars,
                            numRainbow = result.numRainbow,
                            numSpiritGaugesCanFill = result.numSpiritGaugesCanFill,
                            numSpiritGaugesReadyToBurst = result.numSpiritGaugesReadyToBurst,
                            numSkillHints = result.numSkillHints,
                        )
                    trainingMap[result.name] = newTraining
                    break
                } else {
                    // For parallel processing, store result for later processing.
                    analysisResults.add(result)
                }
            }

            // For parallel processing, wait for all analyses and process results.
            if (!singleTraining && analysisResults.isNotEmpty()) {
                // Wait for all analysis threads to complete in parallel with 10s timeout.
                val waitThreads =
                    analysisResults.map { result ->
                        Thread {
                            try {
                                // Check if bot is still running before waiting.
                                if (!BotService.isRunning) {
                                    return@Thread
                                }
                                result.latch.await(10, TimeUnit.SECONDS)
                            } catch (e: InterruptedException) {
                                Log.e(TAG, "[ERROR] analyzeTrainings:: Parallel training analysis timed out for ${result.name}")
                                Thread.currentThread().interrupt()
                            } finally {
                                val elapsedTime = System.currentTimeMillis() - result.startTime
                                Log.d(TAG, "[DEBUG] analyzeTrainings:: Total time for ${result.name} training analysis: ${elapsedTime}ms")
                            }
                        }
                    }

                // Start all wait threads concurrently.
                waitThreads.forEach { it.start() }
                // Join all wait threads to ensure completion.
                if (BotService.isRunning) {
                    waitThreads.forEach { it.join() }
                } else {
                    return
                }

                // Apply secondary stat gain boosts based on context for all results before caching.
                for (result in analysisResults) {
                    applyContextualStatGainBoost(result)
                }

                // Apply the initial failure chance as a fallback if OCR failed during individual stat analysis.
                for (result in analysisResults) {
                    if (result.failureChance == -1) {
                        MessageLog.i(TAG, "[TRAINING] [${result.name}] Failure chance OCR failed. Falling back to the initial robustly read value of $failureChance%.")
                        result.failureChance = failureChance
                    }
                }

                // Cross-validate failure chances across trainings to correct OCR misreads.
                normalizeFailureChances(analysisResults)

                // Process results and populate training maps.
                processAnalysisResults(analysisResults, ignoreFailureChance, isIrregularEvaluation, test)

                // Store analysis results in cache for reuse during the same turn.
                if (!test && !singleTraining) {
                    cachedAnalysisResults = analysisResults.toList()
                }
            }
        } else {
            // Clear the Training map if the bot failed to have enough energy to conduct the training.
            needsEnergyRecovery = true
            trainingMap.clear()
            skippedTrainingMap.clear()
        }

        if (singleTraining) {
            MessageLog.v(TAG, "[TRAINING] Process to analyze the singular Training complete.")
        } else {
            MessageLog.v(TAG, "[TRAINING] Process to analyze all 5 Trainings complete.")
        }
    }

    /**
     * Processes a list of training analysis results and populates the training maps based on thresholds and settings.
     *
     * @param results The list of [TrainingAnalysisResult] to process.
     * @param ignoreFailureChance Whether to ignore the failure chance check.
     * @param isIrregularEvaluation Whether this analysis is for an irregular training evaluation.
     * @param test Whether the analysis is being performed for testing.
     */
    private fun processAnalysisResults(results: List<TrainingAnalysisResult>, ignoreFailureChance: Boolean, isIrregularEvaluation: Boolean, test: Boolean) {
        // Clear maps to ensure fresh results if reusing cached analysis.
        trainingMap.clear()
        skippedTrainingMap.clear()

        // Process results and output logs in training order.
        for (result in results) {
            // Check if risky training logic should apply based on main stat gain.
            val mainStatGain: Int = result.statGains[result.name] ?: 0
            val effectiveFailureChance =
                if (enableRiskyTraining && mainStatGain >= riskyTrainingMinStatGain) {
                    riskyTrainingMaxFailureChance
                } else {
                    maximumFailureChance
                }

            // Filter out trainings that exceed the effective failure chance threshold.
            if (!test && !ignoreFailureChance && result.failureChance > effectiveFailureChance) {
                val skipReason =
                    if (enableRiskyTraining && mainStatGain >= riskyTrainingMinStatGain) {
                        MessageLog.i(
                            TAG,
                            "[TRAINING] Skipping ${result.name} training due to failure chance (${result.failureChance}%) exceeding risky threshold ($riskyTrainingMaxFailureChance%) despite high main stat gain of $mainStatGain.",
                        )
                        "high failure chance (risky)"
                    } else {
                        MessageLog.i(TAG, "[TRAINING] Skipping ${result.name} training due to failure chance (${result.failureChance}%) exceeding threshold ($maximumFailureChance%).")
                        "high failure chance"
                    }

                // Store the skipped training for logging purposes.
                val skippedTraining =
                    TrainingOption(
                        name = result.name,
                        statGains = result.statGains,
                        correctedStats = result.correctedStats,
                        failureChance = result.failureChance,
                        relationshipBars = result.relationshipBars,
                        numRainbow = result.numRainbow,
                        numSpiritGaugesCanFill = result.numSpiritGaugesCanFill,
                        numSpiritGaugesReadyToBurst = result.numSpiritGaugesReadyToBurst,
                        numSkillHints = result.numSkillHints,
                        skipReason = skipReason,
                    )
                skippedTrainingMap[result.name] = skippedTraining
                continue
            }

            if (!test && ignoreFailureChance && result.failureChance > effectiveFailureChance && mainStatGain < minStatGainForCharm) {
                MessageLog.i(
                    TAG,
                    "[TRAINING] Skipping ${result.name} training with Good-Luck Charm because main stat gain ($mainStatGain) is less than $minStatGainForCharm and failure chance (${result.failureChance}%) is risky.",
                )

                // Store the skipped training for logging purposes.
                val skippedTraining =
                    TrainingOption(
                        name = result.name,
                        statGains = result.statGains,
                        correctedStats = result.correctedStats,
                        failureChance = result.failureChance,
                        relationshipBars = result.relationshipBars,
                        numRainbow = result.numRainbow,
                        numSpiritGaugesCanFill = result.numSpiritGaugesCanFill,
                        numSpiritGaugesReadyToBurst = result.numSpiritGaugesReadyToBurst,
                        numSkillHints = result.numSkillHints,
                        skipReason = "low gain with charm",
                    )
                skippedTrainingMap[result.name] = skippedTraining
                continue
            }

            if (!test && isIrregularEvaluation) {
                val minIrregularGain = SettingsHelper.getIntSetting("scenarioOverrides", "trackblazerIrregularTrainingMinStatGain", 30)
                if (mainStatGain < minIrregularGain) {
                    MessageLog.i(TAG, "[TRAINING] Skipping ${result.name} training due to irregular training threshold ($mainStatGain < $minIrregularGain).")

                    // Store the skipped training for logging purposes.
                    val skippedTraining =
                        TrainingOption(
                            name = result.name,
                            statGains = result.statGains,
                            correctedStats = result.correctedStats,
                            failureChance = result.failureChance,
                            relationshipBars = result.relationshipBars,
                            numRainbow = result.numRainbow,
                            numSpiritGaugesCanFill = result.numSpiritGaugesCanFill,
                            numSpiritGaugesReadyToBurst = result.numSpiritGaugesReadyToBurst,
                            numSkillHints = result.numSkillHints,
                            skipReason = "low irregular gain",
                        )
                    skippedTrainingMap[result.name] = skippedTraining
                    continue
                }
            }

            val newTraining =
                TrainingOption(
                    name = result.name,
                    statGains = result.statGains,
                    correctedStats = result.correctedStats,
                    failureChance = result.failureChance,
                    relationshipBars = result.relationshipBars,
                    numRainbow = result.numRainbow,
                    numSpiritGaugesCanFill = result.numSpiritGaugesCanFill,
                    numSpiritGaugesReadyToBurst = result.numSpiritGaugesReadyToBurst,
                    numSkillHints = result.numSkillHints,
                )
            trainingMap[result.name] = newTraining
        }
    }

    /**
     * Clears the current training analysis results from the cache.
     */
    fun clearAnalysisCache() {
        Log.d(TAG, "[DEBUG] clearAnalysisCache:: Clearing the training analysis cache.")
        cachedAnalysisResults = null
        trainingMap.clear()
        skippedTrainingMap.clear()
        restrictedTrainingNames.clear()
    }

    /**
     * Cross-validate and normalize failure chances across all training results.
     *
     * Failure chances are monotonically non-decreasing in training order
     * (Speed <= Stamina <= Power <= Guts <= Wit). If an earlier training's
     * failure chance is significantly lower than the next, it is likely an
     * OCR misread and should be corrected upward.
     *
     * @param results The list of [TrainingAnalysisResult] to normalize.
     */
    private fun normalizeFailureChances(results: List<TrainingAnalysisResult>) {
        val validResults = results.filter { it.failureChance >= 0 }
        if (validResults.size < 2) return

        val input = validResults.map { it.name to it.failureChance }
        val corrected = crossValidateFailureChances(input)

        for (result in validResults) {
            val newValue = corrected[result.name] ?: continue
            if (newValue != result.failureChance) {
                MessageLog.w(
                    TAG,
                    "[WARN] normalizeFailureChances:: ${result.name} failure chance corrected from ${result.failureChance}% to $newValue% based on cross-validation with neighboring trainings.",
                )
                result.failureChance = newValue
            }
        }
    }

    /**
     * Apply secondary stat gain boosts based on the current scenario and context.
     *
     * This method adjusts detected stat gains for specific scenarios like Trackblazer, where certain events or conditions provide predictable bonuses.
     *
     * @param result The [TrainingAnalysisResult] to update.
     */
    private fun applyContextualStatGainBoost(result: TrainingAnalysisResult) {
        val allStats = campaign.trainee.stats.asMap()
        val currentStat = allStats[result.name] ?: 0
        val statCap = getCurrentStatCap(result.name)
        val effectiveStatCap = statCap - 20

        // Helper: a stat gain is "at cap" if currentValue + gain >= the stat's cap,
        // meaning the low OCR value is expected (not an OCR error).
        fun isAtCap(statName: StatName, gain: Int): Boolean {
            val current = allStats[statName] ?: 0
            val cap = getCurrentStatCap(statName)
            return current + gain >= cap
        }

        val mainStatGainRaw = result.statGains[result.name] ?: 0
        val mainStatAtCap = isAtCap(result.name, mainStatGainRaw)

        val newStatGains = result.statGains.toMutableMap()
        val sideEffectStats = newStatGains.keys.filter { it != result.name }
        val maxSideEffectGain = sideEffectStats.maxOfOrNull { newStatGains[it] ?: 0 } ?: 0
        val mainStatGain = newStatGains[result.name] ?: 0

        var boosted = false

        // Edge case: Specific manual correction for GUTS training where POWER gain should be greater than SPEED gain.
        if (result.name == StatName.GUTS) {
            val speedGain = newStatGains[StatName.SPEED] ?: 0
            var powerGain = newStatGains[StatName.POWER] ?: 0

            if (powerGain < speedGain && !isAtCap(StatName.POWER, powerGain)) {
                val originalPowerGain = powerGain
                while (powerGain <= speedGain) {
                    powerGain += 10
                }
                newStatGains[StatName.POWER] = powerGain

                val newCorrectedStats = result.correctedStats.toMutableList()
                if (!newCorrectedStats.contains(StatName.POWER)) {
                    newCorrectedStats.add(StatName.POWER)
                    result.correctedStats = newCorrectedStats
                }

                if (game.imageUtils.debugMode) {
                    MessageLog.d(
                        TAG,
                        "[DEBUG] applyContextualStatGainBoost:: Artificially increased POWER stat gain for GUTS training from $originalPowerGain to $powerGain to be greater than SPEED gain ($speedGain).",
                    )
                } else {
                    Log.d(
                        TAG,
                        "[DEBUG] applyContextualStatGainBoost:: Artificially increased POWER stat gain for GUTS training from $originalPowerGain to $powerGain to be greater than SPEED gain ($speedGain).",
                    )
                }
                boosted = true
            }
        }

        // Expected side effects mapping.
        val affectedStatsMap =
            mapOf(
                StatName.SPEED to listOf(StatName.POWER),
                StatName.STAMINA to listOf(StatName.GUTS),
                StatName.POWER to listOf(StatName.STAMINA),
                StatName.GUTS to listOf(StatName.SPEED, StatName.POWER),
                StatName.WIT to listOf(StatName.SPEED),
            )
        val expectedSideEffects = affectedStatsMap[result.name] ?: emptyList()

        // Check if any expected side effect stat has a higher or equal gain than the main stat.
        // This check only runs if the main stat gain is greater than zero to avoid overlapping with other edge cases.
        // Skip if the main stat is at cap since the low gain is expected.
        if (mainStatGain > 0 && mainStatGain in 1..maxSideEffectGain && !mainStatAtCap) {
            newStatGains[result.name] = maxSideEffectGain + 10

            val newCorrectedStats = result.correctedStats.toMutableList()
            if (!newCorrectedStats.contains(result.name)) {
                newCorrectedStats.add(result.name)
                result.correctedStats = newCorrectedStats
            }

            MessageLog.d(
                TAG,
                "[DEBUG] applyContextualStatGainBoost:: Artificially increased ${result.name} stat gain from $mainStatGain to ${newStatGains[result.name]} due to possible OCR failure. Side-effect stats had higher or equal gains: $sideEffectStats",
            )
            boosted = true
        }

        // If the expected side effect stat gains were zeroes, boost them to half of the main stat gain.
        // Skip if the side effect stat is at cap since a zero gain is expected.
        val boostedMainStatGain = newStatGains[result.name] ?: 0
        for (statName in expectedSideEffects) {
            if ((newStatGains[statName] ?: 0) == 0 && boostedMainStatGain > 0 && !isAtCap(statName, 0)) {
                newStatGains[statName] = boostedMainStatGain / 2

                val newCorrectedStats = result.correctedStats.toMutableList()
                if (!newCorrectedStats.contains(statName)) {
                    newCorrectedStats.add(statName)
                    result.correctedStats = newCorrectedStats
                }

                MessageLog.d(
                    TAG,
                    "[DEBUG] applyContextualStatGainBoost:: Artificially increased $statName side-effect stat gain to ${newStatGains[statName]} because it was 0 due to possible OCR failure. Based on half of boosted ${result.name} = $boostedMainStatGain.",
                )
                boosted = true
            }
        }

        // Edge case: Main stat is 0 but side effect is > 0, and not near stat cap.
        if (mainStatGain == 0 && maxSideEffectGain > 0 && currentStat < effectiveStatCap) {
            var newMainGain = mainStatGain
            while (newMainGain <= maxSideEffectGain) {
                newMainGain += 10
            }
            newStatGains[result.name] = newMainGain

            val newCorrectedStats = result.correctedStats.toMutableList()
            if (!newCorrectedStats.contains(result.name)) {
                newCorrectedStats.add(result.name)
                result.correctedStats = newCorrectedStats
            }

            if (game.imageUtils.debugMode) {
                MessageLog.d(
                    TAG,
                    "[DEBUG] applyContextualStatGainBoost:: Artificially increased ${result.name} stat gain from $mainStatGain to $newMainGain because it was 0, max side effect was $maxSideEffectGain, and current stat $currentStat is not near cap.",
                )
            } else {
                Log.d(
                    TAG,
                    "[DEBUG] applyContextualStatGainBoost:: Artificially increased ${result.name} stat gain from $mainStatGain to $newMainGain because it was 0, max side effect was $maxSideEffectGain, and current stat $currentStat is not near cap.",
                )
            }
            boosted = true
        }

        // Edge case: Low stat gains with relationship bars in Senior Year.
        // Skip if the main stat is at cap since the low gain is expected.
        val currentMainStatGain = newStatGains[result.name] ?: 0
        if (campaign.date.year == DateYear.SENIOR && currentMainStatGain <= 9 && result.relationshipBars.isNotEmpty() && !mainStatAtCap) {
            val boostAmount = result.relationshipBars.size * 5
            newStatGains[result.name] = currentMainStatGain + boostAmount

            val newCorrectedStats = result.correctedStats.toMutableList()
            if (!newCorrectedStats.contains(result.name)) {
                newCorrectedStats.add(result.name)
                result.correctedStats = newCorrectedStats
            }

            if (game.imageUtils.debugMode) {
                MessageLog.d(
                    TAG,
                    "[DEBUG] applyContextualStatGainBoost:: Artificially increased ${result.name} stat gain from $currentMainStatGain to ${newStatGains[result.name]} due to having ${result.relationshipBars.size} relationship bars in Senior Year.",
                )
            } else {
                Log.d(
                    TAG,
                    "[DEBUG] applyContextualStatGainBoost:: Artificially increased ${result.name} stat gain from $currentMainStatGain to ${newStatGains[result.name]} due to having ${result.relationshipBars.size} relationship bars in Senior Year.",
                )
            }
            boosted = true
        }

        // Edge case: Side effect is less than 9 and the difference with the main effect is greater than 20.
        // Skip if the side effect stat is at cap since the low gain is expected.
        for (sideEffect in expectedSideEffects) {
            val sideGain = newStatGains[sideEffect] ?: 0
            if (sideGain < 9 && (mainStatGain - sideGain) > 20 && !isAtCap(sideEffect, sideGain)) {
                newStatGains[sideEffect] = sideGain + 10

                val newCorrectedStats = result.correctedStats.toMutableList()
                if (!newCorrectedStats.contains(sideEffect)) {
                    newCorrectedStats.add(sideEffect)
                    result.correctedStats = newCorrectedStats
                }

                if (game.imageUtils.debugMode) {
                    MessageLog.d(
                        TAG,
                        "[DEBUG] applyContextualStatGainBoost:: Artificially increased $sideEffect side-effect stat gain from $sideGain to ${newStatGains[sideEffect]} due to being less than 9 and having >20 difference with main stat gain of $mainStatGain.",
                    )
                } else {
                    Log.d(
                        TAG,
                        "[DEBUG] applyContextualStatGainBoost:: Artificially increased $sideEffect side-effect stat gain from $sideGain to ${newStatGains[sideEffect]} due to being less than 9 and having >20 difference with main stat gain of $mainStatGain.",
                    )
                }
                boosted = true
            }
        }

        if (boosted) {
            result.statGains = newStatGains
        }
    }

    /**
     * Recommend the best training option based on the current scoring mode and game state.
     *
     * This method implements a multi-stage recommendation system:
     * 1. **Unity Cup Rule**: Prioritizes Spirit Explosion gauges for the Unity Cup scenario.
     * 2. **Early Game Rule**: Focuses on relationship building during the Pre-Debut and Junior Year.
     * 3. **Mid/Late Game Rule**: Uses ratio-based stat efficiency scoring for Year 2 and beyond.
     *
     * @param forceSelection If true, the best training option will be selected even if it exceeds the failure chance threshold.
     * @return The name of the recommended training option, or null if no suitable option is found.
     */
    fun recommendTraining(forceSelection: Boolean = false, isIrregularEvaluation: Boolean = false): StatName? {
        // Build skillHintsPerLocation from the training map.
        val skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { trainingMap[it]?.numSkillHints ?: 0 }

        // Build a TrainingConfig using the current game state for use with companion object scoring functions.
        val trainingConfig =
            TrainingConfig(
                currentStats = campaign.trainee.stats.asMap(),
                statPrioritization = statPrioritization,
                statTargets = campaign.trainee.getStatTargetsByDistance(),
                currentDate = campaign.date,
                scenario = game.scenario,
                enableRainbowTrainingBonus = enableRainbowTrainingBonus,
                focusOnSparkStatTarget = focusOnSparkStatTarget,
                blacklist = blacklist,
                disableTrainingOnMaxedStat = disableTrainingOnMaxedStat,
                trainingOptions = trainingMap.values.toList(),
                skillHintsPerLocation = skillHintsPerLocation,
                enablePrioritizeSkillHints = enablePrioritizeSkillHints,
                statsTrainedOverBuffer = statsTrainedOverBuffer,
            )

        // Compute scores and determine the best training option.
        val scoringMode: String
        val trainingScores: Map<TrainingOption, Double>
        val skippedScores: Map<TrainingOption, Double>
        val best: TrainingOption?

        if (game.scenario == "Unity Cup" && campaign.date.year < DateYear.SENIOR) {
            // Unity Cup (Year < 3): Use Spirit Explosion Gauge priority system.
            scoringMode = "Unity Cup (Spirit Gauge)"
            trainingScores = trainingMap.values.associateWith { scoreUnityCupTraining(trainingConfig, it) }
            skippedScores = skippedTrainingMap.values.associateWith { scoreUnityCupTraining(trainingConfig, it) }
            best = trainingScores.maxByOrNull { it.value }?.key
        } else if (campaign.date.bIsPreDebut || campaign.date.year == DateYear.JUNIOR) {
            // Junior Year: Focus on building relationship bars.
            scoringMode = "Friendship (Pre-Debut/Junior)"
            trainingScores = trainingMap.values.associateWith { scoreFriendshipTraining(it) }
            skippedScores = skippedTrainingMap.values.associateWith { scoreFriendshipTraining(it) }
            best = trainingScores.maxByOrNull { it.value }?.key
        } else {
            // For Year 2+ as a fallback, use ratio-based stat efficiency scoring.
            scoringMode = "Stat Efficiency (Year 2+)"
            trainingScores = trainingMap.values.associateWith { calculateRawTrainingScore(trainingConfig, it) }
            skippedScores = skippedTrainingMap.values.associateWith { calculateRawTrainingScore(trainingConfig, it) }
            best = trainingScores.maxByOrNull { it.value }?.key
        }

        // Build and log training analysis results and selection reasoning.
        val finalScoringMode = if (isIrregularEvaluation) "Trackblazer (Irregular Training)" else scoringMode
        logSelectionReasoning(trainingConfig, finalScoringMode, trainingScores, skippedScores, best)

        return best?.name ?: if (forceSelection) {
            skippedScores.maxByOrNull { it.value }?.key?.name ?: trainingMap.keys.firstOrNull { it !in blacklist }
        } else {
            trainingMap.keys.firstOrNull { it !in blacklist }
        }
    }

    /**
     * Log detailed selection reasoning and training analysis results for debugging.
     *
     * This method combines scoring context, training details, and selection explanation into a single output.
     *
     * @param config The current [TrainingConfig] used for scoring.
     * @param scoringMode The name of the scoring algorithm that was used.
     * @param scores Map of training options to their calculated scores.
     * @param skippedScores Map of skipped training options to their calculated scores.
     * @param selected The training option that was selected, or null if none.
     */
    private fun logSelectionReasoning(config: TrainingConfig, scoringMode: String, scores: Map<TrainingOption, Double>, skippedScores: Map<TrainingOption, Double>, selected: TrainingOption?) {
        val sb = StringBuilder()
        sb.appendLine("\n========== Training Analysis Results ==========")

        // Show scoring context.
        sb.appendLine("Scoring Mode: $scoringMode")
        sb.appendLine("Current Date: ${campaign.date}")

        // Show current stats.
        val currentStats = config.currentStats
        sb.appendLine(
            "Current Stats: Speed=${currentStats[StatName.SPEED]}, Stam=${currentStats[StatName.STAMINA]}, Pow=${currentStats[StatName.POWER]}, Guts=${currentStats[StatName.GUTS]}, Wit=${currentStats[StatName.WIT]}",
        )

        // Show stat targets for context.
        val targets = config.statTargets
        val preferredDistance = campaign.trainee.trackDistance
        sb.appendLine(
            "Stat Targets ($preferredDistance): Speed=${targets[StatName.SPEED]}, Stam=${targets[StatName.STAMINA]}, Pow=${targets[StatName.POWER]}, Guts=${targets[StatName.GUTS]}, Wit=${targets[StatName.WIT]}",
        )

        // Compute completion percentages for each stat.
        val completionPercentages =
            StatName.entries.associateWith { statName ->
                val current = currentStats[statName] ?: 0
                val target = targets[statName] ?: 600
                val pct = if (target > 0) (current.toDouble() / target * 100.0) else 100.0
                String.format("%.0f%%", pct)
            }
        sb.appendLine("Completion: ${completionPercentages.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        sb.appendLine("")

        // Print individual training details.
        appendTrainingDetails(sb, config.blacklist, selected)

        // Combine regular and skipped scores for the selection explanation.
        val allScores = scores.map { Triple(it.key, it.value, false) } + skippedScores.map { Triple(it.key, it.value, true) }
        val sortedScores = allScores.sortedBy { it.first.name.ordinal }

        // Add selection explanation if a training was selected.
        if (selected != null) {
            sb.appendLine("")
            sb.appendLine("--- Selection Explanation ---")

            // Sort scores to find the selected training and its relative performance.
            val scoreRanked = allScores.filter { !it.third }.sortedByDescending { it.second }
            val selectedScore = scoreRanked.firstOrNull { it.first == selected }?.second ?: 0.0
            val secondBest = scoreRanked.getOrNull(1)

            // Provide specific reasoning based on mode and training properties.
            val keyFactors = mutableListOf<String>()

            // Mode-specific key factors.
            when (scoringMode) {
                "Unity Cup (Spirit Gauge)" -> {
                    if (selected.numSpiritGaugesReadyToBurst > 0) {
                        keyFactors.add("Has ${selected.numSpiritGaugesReadyToBurst} Spirit Gauge(s) ready to burst (highest priority).")
                    } else if (selected.numSpiritGaugesCanFill > 0) {
                        keyFactors.add("Can fill ${selected.numSpiritGaugesCanFill} Spirit Gauge(s).")
                    }
                }

                "Friendship (Junior Year)" -> {
                    val blueCount = selected.relationshipBars.count { it.dominantColor == "blue" }
                    val greenCount = selected.relationshipBars.count { it.dominantColor == "green" }
                    if (blueCount > 0 || greenCount > 0) {
                        keyFactors.add("Has $blueCount blue and $greenCount green relationship bar(s) to build.")
                    }
                }

                "Trackblazer (Irregular Training)" -> {
                    val mainGain = selected.statGains[selected.name] ?: 0
                    val minIrregularGain = SettingsHelper.getIntSetting("scenarioOverrides", "trackblazerIrregularTrainingMinStatGain", 30)
                    if (mainGain >= minIrregularGain) {
                        keyFactors.add("Met irregular training main stat gain threshold ($mainGain >= $minIrregularGain).")
                    }
                    if (selected.numRainbow > 0) {
                        keyFactors.add("Rainbow training detected (multiplier applied).")
                    }
                }

                else -> {
                    // Stat Efficiency mode.
                    if (selected.numRainbow > 0) {
                        keyFactors.add("Rainbow training detected (multiplier applied).")
                    }
                    val mainGain = selected.statGains[selected.name] ?: 0
                    val currentVal = config.currentStats[selected.name] ?: 0
                    val targetVal = config.statTargets[selected.name] ?: 600
                    val completion = if (targetVal > 0) (currentVal.toDouble() / targetVal * 100.0) else 100.0
                    if (completion < 70.0) {
                        keyFactors.add("${selected.name} stat is at ${String.format("%.0f", completion)}% of target (behind, higher priority).")
                    }
                    if (mainGain >= 30 && selected.numRainbow == 0) {
                        keyFactors.add("High main stat gain of $mainGain (potential undetected rainbow bonus).")
                    }

                    // High secondary stat gains.
                    for ((statName, gain) in selected.statGains) {
                        if (statName != selected.name && gain >= 20) {
                            keyFactors.add("High secondary $statName gain of $gain.")
                        }
                    }
                }
            }

            // Global key factors.
            if (selected.numSkillHints > 0) {
                keyFactors.add("Provides ${selected.numSkillHints} skill hint(s).")
            }

            selected.relationshipBars.forEach { bar ->
                if (bar.isTrainerSupport && bar.trainerName != null) {
                    keyFactors.add("${bar.trainerName} is present (special trainer bonus).")
                }
            }

            if (selected.relationshipBars.size >= 3) {
                keyFactors.add("Multiple relationship bars present (${selected.relationshipBars.size}).")
            }

            val isSparkStat = selected.name in config.focusOnSparkStatTarget
            val currentVal = config.currentStats[selected.name] ?: 0
            if (isSparkStat && currentVal < 600) {
                keyFactors.add("${selected.name} is prioritized for potential 3* spark (under 600).")
            }

            if (selected.failureChance > maximumFailureChance) {
                keyFactors.add("Selected despite ${selected.failureChance}% failure chance (Risky Training enabled or Finals).")
            }

            // Output beat reasoning if second best exists.
            if (secondBest != null) {
                val scoreDiff = selectedScore - secondBest.second
                val pctDiff = if (secondBest.second > 0) (scoreDiff / secondBest.second * 100.0) else 0.0
                sb.appendLine("${selected.name} beat ${secondBest.first.name} by ${String.format("%.2f", scoreDiff)} points (${String.format("%.1f", pctDiff)}% higher)")
            } else {
                // Only one training available - clarify reasons.
                val numSkipped = skippedScores.size
                val numBlacklisted = config.blacklist.filterNotNull().size
                val reasons = mutableListOf<String>()
                if (numSkipped > 0) reasons.add("$numSkipped skipped due to high failure chance")
                if (numBlacklisted > 0) reasons.add("$numBlacklisted blacklisted")
                if (reasons.isNotEmpty()) {
                    sb.appendLine("${selected.name} was the only available training (${reasons.joinToString(", ")}).")
                } else {
                    sb.appendLine("${selected.name} was the only available training.")
                }
            }

            // Output all collected key factors.
            keyFactors.forEach { factor ->
                sb.appendLine("Key factor: $factor")
            }
        } else if (scores.isNotEmpty() || skippedScores.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("--- Selection Explanation ---")
            val numSkipped = skippedScores.size
            val numBlacklisted = config.blacklist.filterNotNull().size
            val reasons = mutableListOf<String>()
            if (numSkipped > 0) {
                val skipReasons = skippedTrainingMap.values.mapNotNull { it.skipReason }.distinct()
                if (skipReasons.isNotEmpty()) {
                    reasons.add("$numSkipped skipped due to: ${skipReasons.joinToString(", ")}")
                } else {
                    reasons.add("$numSkipped skipped due to high failure chance")
                }
            }
            if (numBlacklisted > 0) reasons.add("$numBlacklisted blacklisted")
            if (restrictedTrainingNames.isNotEmpty()) reasons.add("${restrictedTrainingNames.size} restricted")

            if (reasons.isNotEmpty()) {
                sb.appendLine("No training was selected (${reasons.joinToString(", ")}).")
            } else {
                sb.appendLine("No training was selected.")
            }
        }

        // Only show the manual stat correction notice if there were actually any corrections.
        val anyCorrections = scores.keys.any { it.correctedStats.isNotEmpty() } || skippedScores.keys.any { it.correctedStats.isNotEmpty() }
        if (anyCorrections) {
            sb.appendLine("* means manual stat correction")
        }

        sb.appendLine("================================================")
        MessageLog.v(TAG, sb.toString())
    }

    /**
     * Append training details for all analyzed trainings to the provided [StringBuilder].
     *
     * @param sb The [StringBuilder] to append details to.
     * @param blacklist List of stat trainings that were ignored.
     * @param selected The training option that was selected, or null if none.
     */
    private fun appendTrainingDetails(sb: StringBuilder, blacklist: List<StatName?> = emptyList(), selected: TrainingOption? = null) {
        if (trainingMap.isEmpty() && skippedTrainingMap.isEmpty()) {
            if (trainWitDuringFinale && campaign.date.day > 72) {
                sb.appendLine("Energy recovery needed. No analysis performed. Bot will force Wit training during Finale.")
            } else {
                sb.appendLine("Energy recovery needed. No analysis performed.")
            }
            return
        }

        sb.appendLine("--- Training Details ---")

        val allStats = StatName.entries
        for (statName in allStats) {
            val training = trainingMap[statName]
            val skipped = skippedTrainingMap[statName]
            val isBlacklisted = statName in blacklist

            when {
                training != null -> {
                    val isSelected = training == selected
                    appendSingleTrainingDetails(sb, training, isSelected, false)
                }

                skipped != null -> {
                    appendSingleTrainingDetails(sb, skipped, isSelected = false, isSkipped = true)
                }

                isBlacklisted -> {
                    sb.appendLine("[$statName] BLACKLISTED")
                }

                else -> {
                    sb.appendLine("[$statName] NOT ANALYZED (Insufficient energy or other skip)")
                }
            }
        }
    }

    /**
     * Append details for a single training option to the provided [StringBuilder].
     *
     * @param sb The [StringBuilder] to append details to.
     * @param training The [TrainingOption] to detail.
     * @param isSelected Whether this training was the selected one.
     * @param isSkipped Whether this training was skipped due to thresholds.
     */
    private fun appendSingleTrainingDetails(sb: StringBuilder, training: TrainingOption, isSelected: Boolean, isSkipped: Boolean) {
        // Build the basic training info line with optional selected indicator.
        val selectedIndicator = if (isSelected) " <---- SELECTED" else ""
        val skippedIndicator = if (isSkipped) " (SKIPPED)" else ""

        // Create a formatted string for stat gains, appending an asterisk to any corrected stats.
        val formattedStatGains =
            training.statGains.toSortedMap(compareBy { it.ordinal }).map { (stat, gain) ->
                if (stat in training.correctedStats) {
                    "$stat=$gain*"
                } else {
                    "$stat=$gain"
                }
            }.joinToString(", ", "{", "}")

        val basicInfo = "${training.name} Training: stats=$formattedStatGains, fail=${training.failureChance}%, rainbows=${training.numRainbow}$skippedIndicator$selectedIndicator"
        sb.appendLine(basicInfo)

        // Print relationship bars if any.
        if (training.relationshipBars.isNotEmpty()) {
            val barsSummary =
                training.relationshipBars.mapIndexed { index, bar ->
                    val trainerLabel = if (bar.isTrainerSupport && bar.trainerName != null) "[${bar.trainerName}]" else ""
                    "#${index + 1}:${bar.dominantColor}(${String.format("%.0f", bar.fillPercent)}%)$trainerLabel"
                }.joinToString(", ")
            sb.appendLine("  -> Relationship bars: $barsSummary")
        }

        // Print Spirit Gauge info if any gauges are present.
        if (training.numSpiritGaugesCanFill > 0 || training.numSpiritGaugesReadyToBurst > 0) {
            sb.appendLine("  -> Spirit Gauges: fillable=${training.numSpiritGaugesCanFill}, ready to burst=${training.numSpiritGaugesReadyToBurst}")
        }

        // Print skill hints if any.
        if (training.numSkillHints > 0) {
            sb.appendLine("  -> Skill hints: ${training.numSkillHints}")
        }
    }

    /**
     * Print the current training map details for debugging.
     *
     * This method logs the stat gains, relationship bars, and other properties for all analyzed trainings.
     */
    fun printTrainingMap() {
        MessageLog.v(TAG, "================ Training Map Details ================")
        if (trainingMap.isEmpty()) {
            MessageLog.v(TAG, "Training map is currently empty.")
            return
        }

        for ((statName, training) in trainingMap) {
            val sb = StringBuilder()
            sb.append("[$statName] Gains: ")
            val gains = training.statGains.entries.filter { it.value > 0 }.joinToString(", ") { "${it.key}=${it.value}" }
            sb.append(gains.ifEmpty { "None" })
            sb.append(" | Fail: ${training.failureChance}%")
            sb.append(" | Rainbow: ${training.numRainbow}")
            if (game.scenario == "Unity Cup") {
                sb.append(" | Gauges: CanFill=${training.numSpiritGaugesCanFill}, Ready=${training.numSpiritGaugesReadyToBurst}")
            }
            if (training.relationshipBars.isNotEmpty()) {
                sb.append(" | Bars: ${training.relationshipBars.size}")
            }
            MessageLog.v(TAG, sb.toString())
        }
        MessageLog.v(TAG, "======================================================")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Handle the training process for the current turn.
     *
     * This method orchestrates the identifying, analyzing, recommending, and executing of training.
     *
     * @param forceStat Optional stat name to force the bot to perform regardless of analysis.
     * @return The name of the training that was executed, or null if none.
     */
    fun handleTraining(forceStat: StatName? = null): StatName? {
        MessageLog.v(TAG, "\n********************")
        MessageLog.v(TAG, "[TRAINING] Starting Training process on ${campaign.date}.")
        val startTime = System.currentTimeMillis()
        var trainingSelected: StatName? = null

        // Enter the Training screen.
        if (ButtonTraining.click(game.imageUtils)) {
            // Upon going to the training screen, there is a short animation
            // on the training header icon. We need to make sure this is finished
            // before we can properly begin analyzing the screen.
            game.wait(0.5)
            // Acquire the percentages and stat gains for each training.
            analyzeTrainings()
            trainingSelected = forceStat ?: recommendTraining()

            if (trainingMap.isEmpty()) {
                // Check if we should force Wit training during the Finale instead of recovering energy.
                // Always force Wit on turn 75 since recovering energy on the very last turn is completely useless.
                if ((trainWitDuringFinale && campaign.date.day > 72) || campaign.date.day == 75) {
                    if (campaign.date.day == 75) {
                        MessageLog.v(TAG, "[TRAINING] It is the final turn. Forcing Wit training instead of recovering energy since resting provides zero benefit now.")
                    } else {
                        MessageLog.v(TAG, "[TRAINING] There is not enough energy for training to be done but the setting to train Wit during the Finale is enabled. Forcing Wit training...")
                    }
                    // Directly attempt to tap Wit training.
                    if (ButtonTrainingWit.click(game.imageUtils, taps = 3)) {
                        game.waitForLoading()
                        MessageLog.v(TAG, "[TRAINING] Successfully forced Wit training during the Finale instead of recovering energy.")
                        firstTrainingCheck = false
                    } else {
                        MessageLog.w(TAG, "[WARN] handleTraining:: Could not find Wit training button. Falling back to recovering energy...")
                        ButtonBack.click(game.imageUtils)
                        game.wait(1.0)
                        if (campaign.checkMainScreen()) {
                            campaign.recoverEnergy()
                        } else {
                            MessageLog.w(TAG, "[WARN] handleTraining:: Could not head back to the Main screen in order to recover energy.")
                        }
                    }
                } else {
                    MessageLog.v(TAG, "[TRAINING] Backing out of Training and returning on the Main screen.")
                    ButtonBack.click(game.imageUtils)
                    game.wait(1.0)

                    if (campaign.checkMainScreen()) {
                        if (restrictedTrainingNames.size == StatName.entries.size || (restrictedTrainingNames.size + blacklist.size) >= StatName.entries.size) {
                            MessageLog.v(TAG, "[TRAINING] Will recover energy due to all available trainings being restricted or blacklisted.")
                        } else {
                            MessageLog.v(TAG, "[TRAINING] Will recover energy due to either failure chance was high enough to do so or no failure chances were detected via OCR.")
                        }
                        campaign.recoverEnergy()
                    } else {
                        MessageLog.w(TAG, "[WARN] handleTraining:: Could not head back to the Main screen in order to recover energy.")
                    }
                }
            } else {
                // Now select the training option with the highest weight.
                executeTraining(trainingSelected)
                firstTrainingCheck = false
            }

            MessageLog.v(TAG, "[TRAINING] Training process completed. Total time: ${System.currentTimeMillis() - startTime}ms")
        } else {
            MessageLog.e(TAG, "[ERROR] handleTraining:: Cannot start the Training process. Moving on...")
        }
        MessageLog.v(TAG, "********************")
        return trainingSelected
    }

    /**
     * Execute the selected training by clicking the corresponding button and handling popups.
     *
     * @param trainingSelected The name of the training to execute.
     */
    fun executeTraining(trainingSelected: StatName?) {
        MessageLog.v(TAG, "[TRAINING] Now starting process to execute $trainingSelected training...")

        if (trainingSelected != null) {
            MessageLog.v(TAG, "[TRAINING] Executing the $trainingSelected Training.\n")

            // Check if this training is a rainbow training that exceeds the stat cap buffer.
            val training = trainingMap[trainingSelected]
            if (training != null && training.numRainbow > 0) {
                val currentStat = campaign.trainee.stats.asMap()[trainingSelected] ?: 0
                val potentialStat = currentStat + (training.statGains[trainingSelected] ?: 0)
                val statCap = getCurrentStatCap(trainingSelected)
                val finaleBonus = getFinaleStatBonus(campaign.date.day)
                val effectiveStatCap = statCap - 100 - finaleBonus

                if ((currentStat >= effectiveStatCap || potentialStat >= effectiveStatCap) && trainingSelected !in statsTrainedOverBuffer) {
                    MessageLog.v(TAG, "[TRAINING] [$trainingSelected] One-time stat cap buffer allowance used for this stat.")
                    statsTrainedOverBuffer.add(trainingSelected)
                }
            }

            val trainingButtons: Map<StatName, ComponentInterface> =
                mapOf(
                    StatName.SPEED to ButtonTrainingSpeed,
                    StatName.STAMINA to ButtonTrainingStamina,
                    StatName.POWER to ButtonTrainingPower,
                    StatName.GUTS to ButtonTrainingGuts,
                    StatName.WIT to ButtonTrainingWit,
                )

            // These values are hardcoded and exhaustive. A KeyError would be a programmer error.
            val trainingButton: ComponentInterface = trainingButtons[trainingSelected]!!
            trainingButton.click(game.imageUtils, taps = 3)
            game.wait(game.dialogWaitDelay)

            // Dismiss any popup warning about a scheduled race.
            ButtonOk.click(game.imageUtils, region = game.imageUtils.regionMiddle)
            game.waitForLoading()

            MessageLog.v(TAG, "[TRAINING] Process to execute training completed.")
        } else {
            MessageLog.v(TAG, "[TRAINING] Conditions have not been met so training will not be done.")
        }

        // Now reset the Training maps and analysis cache.
        clearAnalysisCache()
    }
}
