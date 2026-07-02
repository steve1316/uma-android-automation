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
import com.steve1316.uma_android_automation.components.LabelEnergy
import com.steve1316.uma_android_automation.components.LabelStatTableHeaderSkillPoints
import com.steve1316.uma_android_automation.components.LabelTrainingCannotPerform
import com.steve1316.uma_android_automation.components.LabelTrainingFailureChance
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.applyRunningStyleStaminaFactor
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.uma_scoring.RawScoreBreakdown
import com.steve1316.uma_scoring.TrainingScoringConstants
import org.opencv.core.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import com.steve1316.uma_scoring.calculateMiscScore as sharedCalculateMiscScore
import com.steve1316.uma_scoring.calculateRawTrainingScore as sharedCalculateRawTrainingScore
import com.steve1316.uma_scoring.calculateRelationshipScore as sharedCalculateRelationshipScore
import com.steve1316.uma_scoring.calculateStatEfficiencyScore as sharedCalculateStatEfficiencyScore
import com.steve1316.uma_scoring.estimateFailureChanceFromEnergy as sharedEstimateFailureChanceFromEnergy
import com.steve1316.uma_scoring.getCurrentStatCap as sharedGetCurrentStatCap
import com.steve1316.uma_scoring.getFinaleStatBonus as sharedGetFinaleStatBonus
import com.steve1316.uma_scoring.getRemainingFinaleRaces as sharedGetRemainingFinaleRaces
import com.steve1316.uma_scoring.getScenarioStatCap as sharedGetScenarioStatCap
import com.steve1316.uma_scoring.levelBoostMultiplier as sharedLevelBoostMultiplier
import com.steve1316.uma_scoring.rawTrainingScoreComponents as sharedRawTrainingScoreComponents
import com.steve1316.uma_scoring.scoringConstantsFromMap as sharedScoringConstantsFromMap

/**
 * Handle the training process, including analysis of options, scoring recommendations, and execution.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign-specific data.
 */

/**
 * Identifies which branch of `recommendTraining` produced the most recent selection so that
 * callers (and the logs) can distinguish an analysis-driven pick from a fallback pick.
 */
enum class SelectionSource {
    /** Returned the highest-scoring training from `trainingScores` (normal happy path). */
    ANALYSIS,

    /** Analysis rejected everything; `forceSelection=true` picked the highest-scored skipped training. */
    FORCED_FROM_SKIPPED,

    /** Analysis rejected everything and no skipped scores existed; `forceSelection=true` defaulted to first non-blacklisted. */
    FORCED_DEFAULT,

    /** Analysis returned no winner and `forceSelection=false`; first non-blacklisted returned for the caller to handle. */
    UNFORCED_DEFAULT,
}

/**
 * Identifies which scoring algorithm produced a training recommendation, so the log's scoring-mode line and the
 * per-mode key-factor explanation always match the scorer that actually ran. The `label` is the human-readable log string.
 */
enum class TrainingScoringMode(val label: String) {
    /** Pre-debut / Junior year: `scoreFriendshipTraining` prioritizes building relationship bars. */
    FRIENDSHIP("Friendship (Pre-Debut/Junior)"),

    /** Classic / Senior year (default campaign): `calculateRawTrainingScore` stat-efficiency composition. */
    STAT_EFFICIENCY("Stat Efficiency (Year 2+)"),

    /** Unity Cup (year < Senior): `scoreUnityCupTraining` spirit-gauge burst/fill priority. */
    UNITY_CUP("Unity Cup (Spirit Gauge)"),

    /** Trackblazer irregular-training evaluation: the standard scorer with a minimum main-stat-gain filter. */
    TRACKBLAZER_IRREGULAR("Trackblazer (Irregular Training)"),
}

open class Training(protected val game: Game, protected val campaign: Campaign) {
    /** Map to store detected training options. */
    internal var trainingMap: MutableMap<StatName, TrainingOption> = mutableMapOf()

    /** Map to store training options that were skipped. */
    internal var skippedTrainingMap: MutableMap<StatName, TrainingOption> = mutableMapOf()

    /** Records which branch of `recommendTraining` produced the most recent selection (analysis vs. fallback). */
    var lastSelectionSource: SelectionSource? = null
        private set

    /** List of training names that are restricted or unavailable. */
    private var restrictedTrainingNames: MutableSet<StatName> = mutableSetOf()

    /** List of analysis results cached for reuse during the current turn. */
    var cachedAnalysisResults: List<TrainingAnalysisResult>? = null
        private set

    /** The current training scenario name. */
    private val scenario = game.scenario

    /** The current stat prioritization settings. */
    private val statPrioritizationRaw: List<StatName> = SettingsHelper.getStringArraySetting("training", "statPrioritization").mapNotNull { StatName.fromName(it) }

    /** The final stat prioritization list. */
    internal val statPrioritization: List<StatName> = statPrioritizationRaw.ifEmpty { StatName.entries }

    /** The raw stat prioritization list for in-game event choices, sourced from user settings. */
    private val eventChoiceStatPriorityRaw: List<StatName> = SettingsHelper.getStringArraySetting("training", "eventChoiceStatPriority").mapNotNull { StatName.fromName(it) }

    /** The final stat prioritization list applied to event choice scoring. Falls back to [statPrioritization] if unset. */
    internal val eventChoiceStatPriority: List<StatName> = eventChoiceStatPriorityRaw.ifEmpty { statPrioritization }

    /** The raw stat prioritization list for Summer Training, sourced from user settings. */
    private val summerTrainingStatPriorityRaw: List<StatName> = SettingsHelper.getStringArraySetting("training", "summerTrainingStatPriority").mapNotNull { StatName.fromName(it) }

    /** The final stat prioritization list applied during Summer Training. Falls back to [statPrioritization] if unset. */
    internal val summerTrainingStatPriority: List<StatName> = summerTrainingStatPriorityRaw.ifEmpty { statPrioritization }

    /** The maximum allowed failure chance for training. */
    private val maximumFailureChance: Int = SettingsHelper.getIntSetting("training", "maximumFailureChance")

    /** Unity Cup only: the failure-chance ceiling a training with a Spirit Explosion gauge ready to burst may reach before being skipped. 0 (default) disables the exemption. */
    private val unityCupBurstMaxFailureChance: Int = SettingsHelper.getIntSetting("scenarioOverrides", "unityCupBurstMaxFailureChance", 0)

    /** Whether to skip training for stats at their cap. */
    private val disableTrainingOnMaxedStat: Boolean = SettingsHelper.getBooleanSetting("training", "disableTrainingOnMaxedStat")

    /** Whether the rainbow training bonus is active. */
    private val enableRainbowTrainingBonus: Boolean = SettingsHelper.getBooleanSetting("training", "enableRainbowTrainingBonus")

    /** Whether to apply an anticipatory rainbow multiplier in Year 2+ when a training has multiple near-max (green/blue) friendship bars. */
    private val enablePrioritizeNearMaxFriendship: Boolean = SettingsHelper.getBooleanSetting("training", "enablePrioritizeNearMaxFriendship", true)

    /** Whether to enable risky training logic. */
    private val enableRiskyTraining: Boolean = SettingsHelper.getBooleanSetting("training", "enableRiskyTraining")

    /** The minimum stat gain required for risky training. */
    private val riskyTrainingMinStatGain: Int = SettingsHelper.getIntSetting("training", "riskyTrainingMinStatGain")

    /** The maximum failure chance allowed for risky training. */
    private val riskyTrainingMaxFailureChance: Int = SettingsHelper.getIntSetting("training", "riskyTrainingMaxFailureChance")

    /** Whether the expected-value failure gate is active. When on, trainings whose total stat gain is poor value for their failure chance are skipped. */
    private val enableExpectedValueGate: Boolean = SettingsHelper.getBooleanSetting("training", "enableExpectedValueGate", true)

    /** Required total stat gain per percent of failure chance for the expected-value gate (community rule of thumb: 2.0, i.e. gain must be >= 2x the fail chance). */
    private val expectedValueGainPerFailPercent: Double = SettingsHelper.getDoubleSetting("training", "expectedValueGainPerFailPercent", 2.0)

    /** Failure chance below which the expected-value gate never fires (low-risk trainings are always allowed). */
    private val expectedValueMinFailureChance: Int = SettingsHelper.getIntSetting("training", "expectedValueMinFailureChance", 10)

    /** Whether to scale the stamina target by the trainee's running style (Front Runners need more stamina than Late/End Closers). Applies only once the running style is detected. */
    private val enableRunningStyleStaminaAdjustment: Boolean = SettingsHelper.getBooleanSetting("training", "enableRunningStyleStaminaAdjustment", false)

    /** Whether to force Wit training during the Finale. */
    private val trainWitDuringFinale: Boolean = SettingsHelper.getBooleanSetting("training", "trainWitDuringFinale")

    /** Whether to prioritize skill hints. */
    private val enablePrioritizeSkillHints: Boolean = SettingsHelper.getBooleanSetting("training", "enablePrioritizeSkillHints")

    /** Whether to weight training scores by the OCR-detected training level (1-5) of the priority-list stats. */
    internal val enableTrainingLevelWeighting: Boolean = SettingsHelper.getBooleanSetting("training", "enableTrainingLevelWeighting", false)

    /** Whether to ignore per-distance stat targets and treat every stat's target as the scenario stat cap. When ON, the bot keeps the ratio multiplier in the "encourage training" band for every stat. */
    internal val disableStatTargets: Boolean = SettingsHelper.getBooleanSetting("training", "disableStatTargets", false)

    /** Cached screen location of the Energy label, used as the anchor for training level OCR. Resolved lazily on first use, reused for the rest of the bot session. */
    private var cachedEnergyLocation: Point? = null

    /** Whether to enable validation of training analysis. */
    private val enableTrainingAnalysisValidation: Boolean = SettingsHelper.getBooleanSetting("training", "enableTrainingAnalysisValidation")

    /** Classic Year milestone percentage (applied to primary stat targets during Junior Year). */
    private val classicMilestonePct: Int = SettingsHelper.getIntSetting("training", "classicMilestonePercent", 33)

    /** Senior Year milestone percentage (applied to primary stat targets during Classic Year). */
    private val seniorMilestonePct: Int = SettingsHelper.getIntSetting("training", "seniorMilestonePercent", 66)

    /** Map of current stat targets. */
    private var statTargets: Map<StatName, Int> = emptyMap()

    /** Whether to ignore the stat cap when training. */
    private var ignoreStatCap: Boolean = false

    /** Set of stats that have already exceeded their cap buffer. */
    private val statsTrainedOverBuffer: MutableSet<StatName> = mutableSetOf()

    /** List of stat trainings to ignore. */
    internal val blacklist: List<StatName?> = SettingsHelper.getStringArraySetting("training", "trainingBlacklist").map { StatName.fromName(it) }

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

        /** Total number of detected skill hints. */
        var numSkillHints: Int = 0

        /** The OCR-detected training level (1-5) for this stat, or null if the feature is disabled or OCR failed. */
        var trainingLevel: Int? = null

        /** Scenario-specific extra data populated by [runExtraTrainingAnalysis]. */
        val extras: MutableMap<String, Any?> = mutableMapOf()
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
     * @property numSkillHints Total number of detected skill hints.
     * @property extras Scenario-specific extra data from [runExtraTrainingAnalysis].
     * @property trainingLevel OCR-detected training level (1-5) for this option's primary stat, or null if unknown.
     * @property skipReason Optional reason if this training was skipped during recommendation.
     */
    data class TrainingOption(
        val name: StatName,
        val statGains: Map<StatName, Int>,
        val correctedStats: List<StatName> = emptyList(),
        val failureChance: Int,
        val relationshipBars: ArrayList<CustomImageUtils.BarFillResult>,
        val numRainbow: Int,
        val numSkillHints: Int = 0,
        val extras: Map<String, Any?> = emptyMap(),
        val trainingLevel: Int? = null,
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
            if (numSkillHints != other.numSkillHints) return false
            if (extras != other.extras) return false
            if (trainingLevel != other.trainingLevel) return false
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
            result = 31 * result + numSkillHints
            result = 31 * result + extras.hashCode()
            result = 31 * result + (trainingLevel ?: 0)
            result = 31 * result + (skipReason?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Store configuration for training scoring calculations.
     *
     * @property currentStats Map of current character stats.
     * @property statPrioritization Ordered list of stat priorities for regular training.
     * @property eventChoiceStatPriority Ordered list of stat priorities used when scoring in-game event choices.
     * @property summerTrainingStatPriority Ordered list of stat priorities applied during Summer Training.
     * @property statTargets Map of target values for each stat.
     * @property currentDate The current in-game date.
     * @property scenario The current training scenario name.
     * @property enableRainbowTrainingBonus Whether the rainbow training bonus is active.
     * @property blacklist List of stat trainings to ignore.
     * @property disableTrainingOnMaxedStat Whether to skip training for stats at their cap.
     * @property trainingOptions List of all analyzed training options.
     * @property skillHintsPerLocation Map of detected skill hints for each training.
     * @property enablePrioritizeSkillHints Whether to prioritize skill hints.
     * @property enableTrainingLevelWeighting Whether to amplify priority-list stat scores by their OCR-detected training level (1-5).
     * @property disableStatTargets Whether per-distance stat targets are overridden by the scenario stat cap for all stats.
     * @property enablePrioritizeNearMaxFriendship Whether to apply an anticipatory rainbow multiplier in Year 2+ when a training has multiple near-max (green/blue) friendship bars.
     * @property statsTrainedOverBuffer Set of stats that have already exceeded their cap buffer.
     * @property scoring All tunable numeric constants for the scoring math. Defaults to current hardcoded values.
     */
    data class TrainingConfig(
        // Global configuration.
        val currentStats: Map<StatName, Int>,
        val statPrioritization: List<StatName>,
        val eventChoiceStatPriority: List<StatName>,
        val summerTrainingStatPriority: List<StatName>,
        val statTargets: Map<StatName, Int>,
        val currentDate: GameDate,
        val scenario: String,
        val enableRainbowTrainingBonus: Boolean,
        val blacklist: List<StatName?> = emptyList(),
        val disableTrainingOnMaxedStat: Boolean = false,
        val trainingOptions: List<TrainingOption>,
        val skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { 0 },
        val enablePrioritizeSkillHints: Boolean = false,
        val enableTrainingLevelWeighting: Boolean = false,
        val disableStatTargets: Boolean = false,
        val enablePrioritizeNearMaxFriendship: Boolean = true,
        val statsTrainedOverBuffer: Set<StatName> = emptySet(),
        val scoring: TrainingScoringConstants = TrainingScoringConstants(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TrainingConfig

            if (currentStats != other.currentStats) return false
            if (statPrioritization != other.statPrioritization) return false
            if (eventChoiceStatPriority != other.eventChoiceStatPriority) return false
            if (summerTrainingStatPriority != other.summerTrainingStatPriority) return false
            if (statTargets != other.statTargets) return false
            if (currentDate != other.currentDate) return false
            if (scenario != other.scenario) return false
            if (enableRainbowTrainingBonus != other.enableRainbowTrainingBonus) return false
            if (blacklist != other.blacklist) return false
            if (disableTrainingOnMaxedStat != other.disableTrainingOnMaxedStat) return false
            if (trainingOptions != other.trainingOptions) return false
            if (skillHintsPerLocation != other.skillHintsPerLocation) return false
            if (enablePrioritizeSkillHints != other.enablePrioritizeSkillHints) return false
            if (enableTrainingLevelWeighting != other.enableTrainingLevelWeighting) return false
            if (disableStatTargets != other.disableStatTargets) return false
            if (enablePrioritizeNearMaxFriendship != other.enablePrioritizeNearMaxFriendship) return false
            if (statsTrainedOverBuffer != other.statsTrainedOverBuffer) return false
            if (scoring != other.scoring) return false

            return true
        }

        override fun hashCode(): Int {
            var result = currentStats.hashCode()
            result = 31 * result + statPrioritization.hashCode()
            result = 31 * result + eventChoiceStatPriority.hashCode()
            result = 31 * result + summerTrainingStatPriority.hashCode()
            result = 31 * result + statTargets.hashCode()
            result = 31 * result + currentDate.hashCode()
            result = 31 * result + scenario.hashCode()
            result = 31 * result + enableRainbowTrainingBonus.hashCode()
            result = 31 * result + blacklist.hashCode()
            result = 31 * result + disableTrainingOnMaxedStat.hashCode()
            result = 31 * result + trainingOptions.hashCode()
            result = 31 * result + skillHintsPerLocation.hashCode()
            result = 31 * result + enablePrioritizeSkillHints.hashCode()
            result = 31 * result + enableTrainingLevelWeighting.hashCode()
            result = 31 * result + disableStatTargets.hashCode()
            result = 31 * result + enablePrioritizeNearMaxFriendship.hashCode()
            result = 31 * result + statsTrainedOverBuffer.hashCode()
            result = 31 * result + scoring.hashCode()
            return result
        }
    }

    companion object {
        /** The logging tag for this class. */
        internal val TAG: String = "[${MainActivity.loggerTag}]Training"

        /** Secondary-stat gain at or above which the selection explanation calls it out. Log-only threshold, not a scoring input. */
        const val SECONDARY_GAIN_CALLOUT_THRESHOLD: Int = 20

        /** Target-completion percent below which the selection explanation flags a stat as behind. Log-only threshold, not a scoring input. */
        const val BEHIND_TARGET_CALLOUT_PERCENT: Double = 70.0

        /**
         * Build a [TrainingScoringConstants] from an arbitrary settings map keyed by the same strings used by the TypeScript counterpart
         * `scoringConstantsFromSettings()` in `src/lib/training/scoring/scoringConstantsFromSettings.ts`. Any missing or non-numeric value falls back to the
         * matching field in [defaults]. This pure function exists so unit tests can exercise the mapping without needing a live [SettingsHelper].
         *
         * @param settings Map of setting key to value. Numeric values are read as [Number] and converted to Double or Int as appropriate.
         * @param defaults Defaults used when a key is missing or non-numeric. Almost always [TrainingScoringConstants] with no args.
         * @return A fully populated [TrainingScoringConstants] mirroring the supplied overrides.
         */
        fun scoringConstantsFromMap(settings: Map<String, Any?>, defaults: TrainingScoringConstants = TrainingScoringConstants()): TrainingScoringConstants =
            sharedScoringConstantsFromMap(settings, defaults)

        /**
         * Build a [TrainingScoringConstants] from persisted settings under the `training` namespace via [SettingsHelper]. Any missing key falls back to the
         * default value on a freshly-constructed [TrainingScoringConstants]. Mirrors the TypeScript `scoringConstantsFromSettings()` exactly key-for-key.
         *
         * @return A fully populated [TrainingScoringConstants] mirroring the user's saved overrides.
         */
        fun scoringConstantsFromSettings(): TrainingScoringConstants {
            val defaults = TrainingScoringConstants()
            val keys =
                listOf(
                    "ratioMultiplier1",
                    "ratioMultiplier2",
                    "ratioMultiplier3",
                    "ratioMultiplier4",
                    "ratioMultiplier5",
                    "ratioMultiplier6",
                    "ratioMultiplier7",
                    "priorityCoefficient",
                    "levelBoostRank1Factor",
                    "levelBoostRank2Factor",
                    "levelBoostRank3Factor",
                    "mainStatBonusMagnitude",
                    "relationshipOrangeValue",
                    "relationshipGreenValue",
                    "relationshipBlueValue",
                    "relationshipDiminishingFactor",
                    "relationshipEarlyGameBonus",
                    "relationshipTrainerSupportBonus",
                    "skillHintPerHintScore",
                    "skillHintOverrideScore",
                    "statWeightWithBars",
                    "statWeightWithoutBars",
                    "relationshipWeightWithBars",
                    "miscWeight",
                    "juniorEarlyGameFlatBonus",
                    "relationshipScale",
                    "rainbowMultiplierEnabled",
                    "rainbowMultiplierDisabled",
                    "rainbowPerInstanceBase",
                    "rainbowPerInstanceDecay",
                    "anticipatoryMinFillPercent",
                    "anticipatoryCoefficient",
                    "anticipatoryCap",
                    "unityFillBaseBonus",
                    "unityFillPerGaugeBonus",
                    "unityBurstBaseBonus",
                    "unityBurstPerGaugeBonus",
                    "unityFillEnergyPenaltyPerGauge",
                    "unityBurstEnergyPenaltyPerGauge",
                )
            val intKeys = listOf("mainStatThresholdSpeed", "mainStatThresholdStamina", "mainStatThresholdPower", "mainStatThresholdGuts", "mainStatThresholdWit")
            // Use NaN as the per-key default so we can distinguish "user set it" from "missing"; scoringConstantsFromMap drops NaN via isFinite check.
            val map: MutableMap<String, Any?> = mutableMapOf()
            for (k in keys) {
                val v = SettingsHelper.getDoubleSetting("training", k, Double.NaN)
                if (!v.isNaN()) map[k] = v
            }
            for (k in intKeys) {
                // Sentinel of Int.MIN_VALUE marks "missing"; any real override is in [0, 9999].
                val v = SettingsHelper.getIntSetting("training", k, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) map[k] = v
            }
            return scoringConstantsFromMap(map, defaults)
        }

        /** Adapter for the shared `getScenarioStatCap`. Currently a flat 1200 across scenarios; kept here so call sites in `:app` resolve the existing companion symbol. */
        fun getScenarioStatCap(scenario: String, statName: StatName): Int = sharedGetScenarioStatCap(scenario, statName)

        /** Adapter for the shared `getCurrentStatCap`. Converts the Android-rich `TrainingConfig` at the boundary. */
        fun getCurrentStatCap(statName: StatName, config: TrainingConfig): Int = sharedGetCurrentStatCap(statName, config.toScoring())

        /** Adapter for the shared `getRemainingFinaleRaces`. */
        fun getRemainingFinaleRaces(currentDay: Int): Int = sharedGetRemainingFinaleRaces(currentDay)

        /** Adapter for the shared `getFinaleStatBonus`. */
        fun getFinaleStatBonus(currentDay: Int): Int = sharedGetFinaleStatBonus(currentDay)

        /** Adapter for the shared `estimateFailureChanceFromEnergy`. */
        fun estimateFailureChanceFromEnergy(currentEnergy: Int, statName: StatName? = null): Int = sharedEstimateFailureChanceFromEnergy(currentEnergy, statName)

        /**
         * Cross-validate failure chances and return corrected values.
         *
         * Failure chances are monotonically non-decreasing in training order:
         * Speed <= Stamina <= Power <= Guts. Wit is excluded because it naturally
         * has a lower failure chance than the other trainings.
         *
         * If an earlier training's failure chance is significantly lower than
         * the next, it is validated against the mathematically expected energy curve.
         * If it deviates heavily, it is an OCR misread and is corrected.
         *
         * @param failureChances List of ([StatName], failureChance) pairs with valid (>= 0) values.
         * @param currentEnergy The character's current energy level.
         * @param suspiciousJumpThreshold Minimum difference to consider suspicious.
         * @param outlierTolerance Maximum allowable deviation from the expected math before an OCR correction is applied.
         * @return Map of [StatName] to corrected failure chance.
         */
        fun crossValidateFailureChances(
            failureChances: List<Pair<StatName, Int>>,
            currentEnergy: Int,
            suspiciousJumpThreshold: Int = 20,
            outlierTolerance: Int = 30,
        ): Map<StatName, Int> {
            // Wit is excluded from cross-validation as it naturally has a lower failure chance.
            val witEntry = failureChances.filter { it.first == StatName.WIT }
            val withoutWit = failureChances.filter { it.first != StatName.WIT }

            if (withoutWit.size < 2) return failureChances.toMap()

            val sorted = withoutWit.sortedBy { it.first.ordinal }.toMutableList()

            // Walk backwards: correct any value that is suspiciously lower than its successor.
            for (i in sorted.size - 2 downTo 0) {
                val (currentName, currentChance) = sorted[i]
                val (_, nextChance) = sorted[i + 1]

                // Trigger: Is the current chance suspiciously lower than the next?
                if (nextChance - currentChance > suspiciousJumpThreshold) {
                    // Validation: Check expected math based on character energy
                    val expectedChance = estimateFailureChanceFromEnergy(currentEnergy, currentName)

                    // If the read chance is wildly different from the expected math, it's an OCR error
                    if (kotlin.math.abs(currentChance - expectedChance) > outlierTolerance) {
                        sorted[i] = currentName to expectedChance
                    }
                    // Otherwise, it's a valid in-game Support Card buff and we leave it alone.
                }
            }

            return (sorted + witEntry).toMap()
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
                // Note: these bar weights are intentionally independent of `TrainingScoringConstants.relationship*Value` because `scoreFriendshipTraining` is a separate scorer that does not take a `TrainingConfig`.
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

            val numSpiritGaugesCanFill = training.extras["spiritGaugesCanFill"] as? Int ?: 0
            val numSpiritGaugesReadyToBurst = training.extras["spiritGaugesReadyToBurst"] as? Int ?: 0

            // 1. Primary Priority: Stat Efficiency.
            var score = calculateStatEfficiencyScore(config, training)
            MessageLog.i(TAG, "[TRAINING] [${training.name}] Base stat efficiency score: ${String.format("%.2f", score)}")

            // 2. Second Priority: Trainings with Spirit Explosion Gauges ready to burst.
            if (numSpiritGaugesReadyToBurst > 0) {
                // We give a significant bonus for bursting, but not so much that it always overrides huge stat gains elsewhere. The optional energy penalty reflects the extra energy a
                // Special Training burst costs, scaled by the number of gauges involved (default 0, so behavior is unchanged unless the user tunes it).
                val burstBonus =
                    config.scoring.unityBurstBaseBonus + (numSpiritGaugesReadyToBurst * config.scoring.unityBurstPerGaugeBonus) -
                        (numSpiritGaugesReadyToBurst * config.scoring.unityBurstEnergyPenaltyPerGauge)
                score += burstBonus
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Adding burst bonus for $numSpiritGaugesReadyToBurst gauge(s): $burstBonus")

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
                        if (numSpiritGaugesCanFill >= 2) {
                            score += 100.0 // Building up multiple gauges to allow for bursting.
                        } else {
                            score -= 50.0 // Not ideal without building up multiple gauges.
                        }
                    }
                }
            }

            // 3. Third Priority: Trainings that can fill Spirit Explosion Gauges (not at 100% yet).
            if (numSpiritGaugesCanFill > 0) {
                // Score increases with number of gauges that can be filled. Kept on the stat-efficiency scale so a strong stat turn still outranks pure gauge-filling.
                // Each gauge fills by 25% per training execution. The optional energy penalty (default 0) reflects the extra energy filling costs, scaled by the number of gauges involved.
                val fillBonus =
                    config.scoring.unityFillBaseBonus + (numSpiritGaugesCanFill * config.scoring.unityFillPerGaugeBonus) -
                        (numSpiritGaugesCanFill * config.scoring.unityFillEnergyPenaltyPerGauge)
                score += fillBonus
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Training can fill $numSpiritGaugesCanFill Spirit Explosion Gauge(s). Adding fill bonus: $fillBonus")

                // Early game: If gauges can be filled for deprioritized stat trainings, ignore stat prioritization.
                if (config.currentDate.year == DateYear.JUNIOR) {
                    score += config.scoring.juniorEarlyGameFlatBonus
                    MessageLog.i(TAG, "[TRAINING] [${training.name}] Early game bonus for gauge filling.")
                }
            }

            // 4. Fourth Priority: Relationship bars.
            if (training.relationshipBars.isNotEmpty()) {
                val relationshipScore = calculateRelationshipScore(config, training)
                val scaledRelationshipScore = relationshipScore * config.scoring.relationshipScale // Scaled to be a significant bonus but below bursting.
                score += scaledRelationshipScore
                MessageLog.i(TAG, "[TRAINING] [${training.name}] Adding relationship bonus: ${String.format("%.2f", scaledRelationshipScore)}.")
            }

            // Rainbow Training Bonus synergy.
            if (training.numRainbow > 0 && config.currentDate.year > DateYear.JUNIOR) {
                var rainbowBonusScore = 0.0
                for (i in 1 until training.numRainbow + 1) {
                    rainbowBonusScore += config.scoring.rainbowPerInstanceBase * config.scoring.rainbowPerInstanceDecay.pow(i)
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

        /** Adapter for the shared `levelBoostMultiplier`. Signature unchanged so existing call sites resolve. */
        fun levelBoostMultiplier(priorityRank: Int, trainingLevel: Int?, constants: TrainingScoringConstants = TrainingScoringConstants()): Double =
            sharedLevelBoostMultiplier(priorityRank, trainingLevel, constants)

        /** Adapter for the shared `calculateStatEfficiencyScore`. Converts the Android-rich config + training at the boundary. */
        fun calculateStatEfficiencyScore(config: TrainingConfig, training: TrainingOption): Double = sharedCalculateStatEfficiencyScore(config.toScoring(), training.toScoring())

        /** Adapter for the shared `calculateRelationshipScore`. */
        fun calculateRelationshipScore(config: TrainingConfig, training: TrainingOption): Double = sharedCalculateRelationshipScore(config.toScoring(), training.toScoring())

        /** Adapter for the shared `calculateMiscScore`. */
        fun calculateMiscScore(config: TrainingConfig, training: TrainingOption): Double = sharedCalculateMiscScore(config.toScoring(), training.toScoring())

        /** Adapter for the shared `calculateRawTrainingScore`. The shared function applies the full composition pipeline (stat efficiency + relationship + misc, rainbow + anticipatory multipliers). */
        fun calculateRawTrainingScore(config: TrainingConfig, training: TrainingOption): Double = sharedCalculateRawTrainingScore(config.toScoring(), training.toScoring())

        /** Adapter for the shared `rawTrainingScoreComponents`. Returns the per-factor decomposition whose `total` equals `calculateRawTrainingScore` for the same inputs. */
        fun rawTrainingScoreComponents(config: TrainingConfig, training: TrainingOption): RawScoreBreakdown = sharedRawTrainingScoreComponents(config.toScoring(), training.toScoring())

        /**
         * Resolve the default scoring mode from the campaign date. Pure so the mode selection can be unit-tested without a live [Game] / [Campaign].
         *
         * @param bIsPreDebut Whether the trainee is still pre-debut.
         * @param year The current in-game year.
         * @return The scoring mode the default campaign uses for this date.
         */
        fun defaultScoringModeFor(bIsPreDebut: Boolean, year: DateYear): TrainingScoringMode =
            if (bIsPreDebut || year == DateYear.JUNIOR) TrainingScoringMode.FRIENDSHIP else TrainingScoringMode.STAT_EFFICIENCY

        /**
         * Expected-value failure gate. A training is poor value when its total stat gain is worth less than the risk it carries - the community rule of thumb "don't train if the
         * total stat gain is less than `gainPerFailPercent` times the fail chance" (typically 2x). Only bites at or above `minFailureChance`, so low-risk trainings are never gated.
         * Source: https://docs.google.com/document/d/11X2P7pLuh-k9E7PhRiD20nDX22rNWtCpC1S4IMx_8pQ/edit?tab=t.chehxs4igdlt ("if the stats you gain are less than double the fail chance, don't do it").
         *
         * @param totalStatGain Sum of all stat gains from the training.
         * @param failureChance The training's failure chance percent.
         * @param gainPerFailPercent Required stat gain per percent of failure chance (e.g. 2.0).
         * @param minFailureChance Failure chance below which the gate never fires.
         * @return True when the training should be skipped as poor value for its risk.
         */
        fun failsExpectedValueGate(totalStatGain: Int, failureChance: Int, gainPerFailPercent: Double, minFailureChance: Int): Boolean =
            failureChance >= minFailureChance && totalStatGain < gainPerFailPercent * failureChance

        /**
         * Unity Cup burst exemption. A training with a Spirit Explosion gauge ready to burst may be worth a higher failure chance than the base ceiling, so this raises the ceiling to
         * `burstMax` when a gauge is ready. No-op when no gauge is ready or when `burstMax` is below the base, keeping the default (0) a pure pass-through. Pure and unit-testable.
         *
         * @param baseFailureChance The normal (or risky) failure-chance ceiling for this training.
         * @param readyToBurst The number of Spirit Explosion gauges ready to burst on this training.
         * @param burstMax The Unity Cup burst failure-chance ceiling from settings. 0 disables the exemption.
         * @return The effective failure-chance ceiling to apply to this training.
         */
        fun burstExemptFailureChance(baseFailureChance: Int, readyToBurst: Int, burstMax: Int): Int = if (readyToBurst > 0) maxOf(baseFailureChance, burstMax) else baseFailureChance

        /**
         * Key factors for the Friendship scoring mode. Mirrors `scoreFriendshipTraining`, which ranks purely by relationship-bar color.
         *
         * @param selected The selected training option.
         * @return The list of key-factor strings.
         */
        fun friendshipKeyFactors(selected: TrainingOption): List<String> {
            val factors = mutableListOf<String>()
            val blueCount = selected.relationshipBars.count { it.dominantColor == "blue" }
            val greenCount = selected.relationshipBars.count { it.dominantColor == "green" }
            if (blueCount > 0 || greenCount > 0) {
                factors.add("Has $blueCount blue and $greenCount green relationship bar(s) to build.")
            }
            return factors
        }

        /**
         * Key factors for the Stat Efficiency scoring mode. Each factor mirrors a term the shared `calculateRawTrainingScore` actually applies, so the
         * anticipatory factor uses the exact `anticipatoryMinFillPercent` threshold and year/rainbow gates rather than a divergent hardcoded threshold.
         *
         * @param config The current training configuration.
         * @param selected The selected training option.
         * @return The list of key-factor strings.
         */
        fun statEfficiencyKeyFactors(config: TrainingConfig, selected: TrainingOption): List<String> {
            val factors = mutableListOf<String>()
            if (selected.numRainbow > 0) {
                factors.add("Rainbow training detected (multiplier applied).")
            }
            // Mirror the exact anticipatory predicate from calculateRawTrainingScore so the explanation never claims a multiplier the scorer did not apply.
            if (config.enablePrioritizeNearMaxFriendship && selected.numRainbow == 0 && config.currentDate.year > DateYear.JUNIOR) {
                val threshold = config.scoring.anticipatoryMinFillPercent
                val nearMaxCount = selected.relationshipBars.count { (it.dominantColor == "green" || it.dominantColor == "blue") && it.fillPercent > threshold }
                if (nearMaxCount > 0) {
                    factors.add("$nearMaxCount friendship bar(s) above ${threshold.toInt()}% fill (anticipatory rainbow multiplier applied).")
                }
            }
            val mainGain = selected.statGains[selected.name] ?: 0
            val currentVal = config.currentStats[selected.name] ?: 0
            val targetVal = config.statTargets[selected.name] ?: 600
            val completion = if (targetVal > 0) (currentVal.toDouble() / targetVal * 100.0) else 100.0
            if (completion < BEHIND_TARGET_CALLOUT_PERCENT) {
                factors.add("${selected.name} stat is at ${String.format("%.0f", completion)}% of target (below ${BEHIND_TARGET_CALLOUT_PERCENT.toInt()}%, higher priority).")
            }
            val mainThreshold = config.scoring.mainStatThresholds[selected.name] ?: error("No mainStatThresholds entry for ${selected.name}")
            if (mainGain >= mainThreshold && selected.numRainbow == 0) {
                factors.add("Main stat gain $mainGain >= threshold $mainThreshold: ${config.scoring.mainStatBonusMagnitude}x main-stat bonus applied.")
            }
            for ((statName, gain) in selected.statGains) {
                if (statName != selected.name && gain >= SECONDARY_GAIN_CALLOUT_THRESHOLD) {
                    factors.add("High secondary $statName gain of $gain (>= $SECONDARY_GAIN_CALLOUT_THRESHOLD).")
                }
            }
            return factors
        }

        /**
         * Key factors for the Unity Cup scoring mode. Mirrors `scoreUnityCupTraining`'s spirit-gauge priority (burst outranks fill) and never references the
         * anticipatory / rainbow multiplier, which the Unity Cup scorer does not apply.
         *
         * @param selected The selected training option.
         * @return The list of key-factor strings.
         */
        fun unityCupKeyFactors(selected: TrainingOption): List<String> {
            val readyToBurst = selected.extras["spiritGaugesReadyToBurst"] as? Int ?: 0
            val canFill = selected.extras["spiritGaugesCanFill"] as? Int ?: 0
            return when {
                readyToBurst > 0 -> listOf("Has $readyToBurst Spirit Gauge(s) ready to burst (highest priority).")
                canFill > 0 -> listOf("Can fill $canFill Spirit Gauge(s).")
                else -> emptyList()
            }
        }

        /**
         * Key factors for the Trackblazer irregular-training evaluation, which reuses the standard scorer behind a minimum main-stat-gain filter.
         *
         * @param selected The selected training option.
         * @param minIrregularGain The minimum main-stat gain that qualifies a training for irregular evaluation.
         * @return The list of key-factor strings.
         */
        fun trackblazerIrregularKeyFactors(selected: TrainingOption, minIrregularGain: Int): List<String> {
            val factors = mutableListOf<String>()
            val mainGain = selected.statGains[selected.name] ?: 0
            if (mainGain >= minIrregularGain) {
                factors.add("Met irregular training main stat gain threshold ($mainGain >= $minIrregularGain).")
            }
            if (selected.numRainbow > 0) {
                factors.add("Rainbow training detected (multiplier applied).")
            }
            return factors
        }

        /**
         * A cross-mode key factor for when WIT was the only training that survived the failure-chance gate. At low energy every stat except WIT tends to fail, so WIT gets chosen by
         * elimination rather than merit. Surfacing this explains a low-value WIT pick and flags that the trainee is running low on energy.
         *
         * @param selected The selected training stat.
         * @param passedStats The stats whose trainings passed the failure-chance gate.
         * @param anySkipped Whether any training was skipped by the failure-chance gate.
         * @param energy The trainee's current energy percentage.
         * @return The WIT-only factor string, or null when WIT was not the sole survivor.
         */
        fun witOnlyKeyFactor(selected: StatName, passedStats: Set<StatName>, anySkipped: Boolean, energy: Int): String? =
            if (selected == StatName.WIT && anySkipped && passedStats == setOf(StatName.WIT)) {
                "Only WIT passed the failure-chance gate at $energy% energy (picked by elimination, not merit)."
            } else {
                null
            }

        /**
         * Format the selection ranking line for the explanation log. Training scores live on an internal, mode-specific scale that is not comparable across
         * modes, so this reports the absolute scores plus a ratio and the mode label rather than an unlabeled "N points" difference that reads as meaningful.
         *
         * @param selectedName The selected training stat.
         * @param selectedScore The selected training's score.
         * @param runnerUpName The runner-up training stat.
         * @param runnerUpScore The runner-up training's score.
         * @param modeLabel The scoring mode label.
         * @param numExcluded The number of trainings excluded from the ranking (skipped before scoring).
         * @return The formatted ranking line.
         */
        fun formatSelectionRankingLine(selectedName: StatName, selectedScore: Double, runnerUpName: StatName, runnerUpScore: Double, modeLabel: String, numExcluded: Int): String {
            val ratioNote = if (runnerUpScore > 0.0) ", ${String.format("%.2f", selectedScore / runnerUpScore)}x higher" else ""
            val excludedNote = if (numExcluded > 0) " ($numExcluded training(s) excluded from ranking: skipped)" else ""
            return "Selected $selectedName (score ${String.format("%.2f", selectedScore)}) over runner-up $runnerUpName (score ${String.format("%.2f", runnerUpScore)})$ratioNote [mode: $modeLabel]$excludedNote"
        }

        /**
         * Format a compact, greppable decision-trace line: the scoring mode, the selection, and every candidate's final score highest-first. This gives a reader (or a log tool)
         * the full ranking behind a pick in one line, which is the machine-readable answer to "why was this training chosen?".
         *
         * @param modeLabel The scoring mode label.
         * @param selectedName The selected training stat, or null if none was selected.
         * @param scores Map of each candidate training stat to its final score.
         * @return The formatted decision-trace line.
         */
        fun formatDecisionTrace(modeLabel: String, selectedName: StatName?, scores: Map<StatName, Double>): String {
            val ranked = scores.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${String.format("%.2f", it.value)}" }
            return "Decision trace | mode=$modeLabel | selected=${selectedName ?: "none"} | scores: $ranked"
        }

        /**
         * Format the per-factor breakdown of a stat-efficiency raw score: each weighted component and the multipliers that were applied, ending in the total. Only meaningful for
         * the stat-efficiency composition (the mode whose score is built by [rawTrainingScoreComponents]).
         *
         * @param name The training stat this breakdown is for.
         * @param breakdown The per-factor decomposition from [rawTrainingScoreComponents].
         * @return The formatted breakdown line.
         */
        fun formatScoreBreakdown(name: StatName, breakdown: RawScoreBreakdown): String =
            "Breakdown [$name]: statEff=${String.format("%.2f", breakdown.statScoreWeighted)}, rel=${String.format("%.2f", breakdown.relationshipScoreWeighted)}, " +
                "misc=${String.format("%.2f", breakdown.miscScoreWeighted)}, xRainbow=${String.format("%.2f", breakdown.rainbowMultiplier)}, " +
                "xAnticip=${String.format("%.2f", breakdown.anticipatoryMultiplier)} => ${String.format("%.2f", breakdown.total)}"
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
     *             - "minStatGainForCharm" (Int): Minimum stat gain to allow training with Good-Luck Charm (default 30).
     *             - "irregularTrainingMinStatGain" (Int): Minimum stat gain for irregular training evaluation (default 30).
     */
    fun analyzeTrainings(args: Map<String, Any?> = emptyMap()) {
        needsEnergyRecovery = false
        val test = args["test"] as? Boolean ?: false
        val singleTraining = args["singleTraining"] as? Boolean ?: false
        val ignoreFailureChance = args["ignoreFailureChance"] as? Boolean ?: false

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
            processAnalysisResults(cachedAnalysisResults!!, ignoreFailureChance, test, args)
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

            // Give getActiveStat a dedicated sub-budget so it cannot consume the entire
            // timeout before the click and header poll have a chance to run.
            // Reserve at least POST_CLICK_BUDGET_MS for the header detection loop after clicking.
            val postClickBudgetMs = 3000
            val activeStatBudgetMs = (timeoutMs - postClickBudgetMs).coerceAtLeast(1000)

            val activeStat: StatName? = getActiveStat(activeStatBudgetMs)
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

            // Small delay to allow the stats to appear before checking to avoid accidental skips
            game.wait(0.1, skipWaitingForLoading = true)

            // Poll for the header using whatever budget remains, with a guaranteed
            // minimum of postClickBudgetMs regardless of how long getActiveStat took.

            val postClickDeadline = System.currentTimeMillis() + postClickBudgetMs
            do {
                if (header.check(game.imageUtils)) {
                    return true
                }
            } while (System.currentTimeMillis() < postClickDeadline)

            MessageLog.w(TAG, "[WARN] goToStat:: Timed out while waiting for $statName training header.")
            return false
        }

        /**
         * Recover from a failed first failure-chance detection by backing out to the Main screen and re-entering the Training screen, then retry once.
         *
         * @return The retried failure chance, or -1 if recovery or retry failed.
         */
        fun recoverAndRetryFailureChance(): Int {
            // Back out to the Main screen.
            ButtonBack.click(game.imageUtils)
            game.wait(1.0)
            if (!campaign.checkMainScreen()) {
                MessageLog.w(TAG, "[WARN] recoverAndRetryFailureChance:: Could not confirm Main screen after backing out. Aborting recovery.")
                return -1
            }

            // Re-enter the Training screen.
            if (!ButtonTraining.click(game.imageUtils)) {
                MessageLog.w(TAG, "[WARN] recoverAndRetryFailureChance:: Could not click Training button to re-enter Training screen.")
                return -1
            }
            game.wait(0.5)

            // Re-establish SPEED as the active stat.
            if (!goToStat(StatName.SPEED)) {
                MessageLog.w(TAG, "[WARN] recoverAndRetryFailureChance:: goToStat(SPEED) failed after re-entering Training screen.")
                return -1
            }

            val retried = game.imageUtils.findTrainingFailureChance(tries = 3)
            if (retried == -1) {
                MessageLog.w(TAG, "[WARN] recoverAndRetryFailureChance:: Retry of findTrainingFailureChance still returned -1.")
            } else {
                MessageLog.i(TAG, "[TRAINING] Recovery succeeded. Failure chance detected on retry: $retried%.")
            }
            return retried
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
        var failureChance: Int = game.imageUtils.findTrainingFailureChance(tries = 3)
        if (failureChance == -1 && !singleTraining) {
            MessageLog.w(
                TAG,
                "[WARN] analyzeTrainings:: First failure chance detection failed all attempts. Attempting recovery by backing out to the Main screen and re-entering the Training screen.",
            )
            failureChance = recoverAndRetryFailureChance()
        }
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
                val hintSourceBitmap = game.imageUtils.getSourceBitmap()
                val skillHintLocations: ArrayList<Point> = IconStatSkillHint.findAll(game.imageUtils, sourceBitmap = hintSourceBitmap, region = game.imageUtils.regionBottomHalf)
                if (skillHintLocations.isNotEmpty()) {
                    val firstHint = skillHintLocations.first()

                    // Map the hint icon to the nearest training button to learn which stat it belongs to.
                    val hintStat: StatName? =
                        trainingButtons.entries
                            .mapNotNull { (statName, button) ->
                                val buttonLocation = button.findImageWithBitmap(game.imageUtils, hintSourceBitmap, region = game.imageUtils.regionBottomHalf) ?: return@mapNotNull null
                                val dx = buttonLocation.x - firstHint.x
                                val dy = buttonLocation.y - firstHint.y
                                statName to (dx * dx + dy * dy)
                            }
                            .minByOrNull { it.second }
                            ?.first

                    if (hintStat == null) {
                        MessageLog.w(
                            TAG,
                            "[WARN] analyzeTrainings:: Found ${skillHintLocations.size} skill hint(s) but could not map any to a training button. Falling back to normal training analysis.",
                        )
                    } else if (!goToStat(hintStat)) {
                        MessageLog.w(
                            TAG,
                            "[WARN] analyzeTrainings:: Found a skill hint on $hintStat training but could not navigate to it to read its failure chance. Falling back to normal training analysis.",
                        )
                    } else {
                        // Read the hinted training's failure chance before committing to it.
                        val hintFailureChance: Int = game.imageUtils.findTrainingFailureChance(tries = 3)
                        val effectiveFailureChance = if (enableRiskyTraining) riskyTrainingMaxFailureChance else maximumFailureChance
                        val bypassThreshold = isFinals || ignoreFailureChance
                        if (hintFailureChance == -1) {
                            MessageLog.w(
                                TAG,
                                "[WARN] analyzeTrainings:: Could not read the failure chance for the hinted $hintStat training. Falling back to normal training analysis instead of tapping the hint blindly.",
                            )
                        } else if (bypassThreshold || hintFailureChance <= effectiveFailureChance) {
                            val reason =
                                if (bypassThreshold) {
                                    "the failure chance check is bypassed (${if (isFinals) "Finals" else "Good-Luck Charm active"})"
                                } else {
                                    "its failure chance ($hintFailureChance%) is within the threshold ($effectiveFailureChance%)"
                                }
                            MessageLog.i(TAG, "[TRAINING] Tapping skill hint on $hintStat training because $reason. Skipping further training analysis.")
                            game.tap(firstHint.x, firstHint.y, IconStatSkillHint.template.path, taps = 3)
                            game.wait(1.0)
                            MessageLog.v(TAG, "[TRAINING] Process to execute skill hint training completed.")
                            return
                        } else {
                            MessageLog.i(
                                TAG,
                                "[TRAINING] Not tapping skill hint on $hintStat training because its failure chance ($hintFailureChance%) exceeds the threshold ($effectiveFailureChance%). Falling back to normal training analysis to pick a safer training.",
                            )
                        }
                    }
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
                // The 5th slot is for scenario-specific extra analysis via runExtraTrainingAnalysis().
                val latch = CountDownLatch(5)

                // Create result object to store analysis state.
                val result =
                    TrainingAnalysisResult(
                        name = statName,
                        latch = latch,
                        startTime = startTime,
                    )

                // Run scenario-specific extra analysis (e.g. Unity Cup spirit gauge analysis).
                // In parallel mode, this runs synchronously. In singleTraining mode, the scenario may start a thread.
                runExtraTrainingAnalysis(result, sourceBitmap, singleTraining)

                // OCR the displayed training level (1-5) for this stat while its panel is on screen.
                // Skipped during Pre-Debut, Junior, and Summer since the level boost only fires in Year 2+ Stat Efficiency scoring,
                // and Summer forces every training to Lvl 5 (the boost would equalize across stats).
                if (enableTrainingLevelWeighting && !campaign.date.bIsPreDebut && campaign.date.year != DateYear.JUNIOR && !campaign.date.isSummer()) {
                    val energyAnchor =
                        cachedEnergyLocation ?: run {
                            val located = LabelEnergy.find(game.imageUtils).first
                            if (located != null) {
                                cachedEnergyLocation = located
                            }
                            located
                        }
                    if (energyAnchor != null) {
                        val detectedLevel = game.imageUtils.extractTrainingLevel(sourceBitmap, energyAnchor)
                        result.trainingLevel = detectedLevel
                        if (detectedLevel == null) {
                            MessageLog.w(TAG, "[WARN] analyzeTrainings:: Training level OCR failed for $statName. Falling back to no level boost.")
                        } else {
                            MessageLog.i(TAG, "[TRAINING] $statName training level detected as Lvl $detectedLevel.")
                        }
                    } else {
                        MessageLog.w(TAG, "[WARN] analyzeTrainings:: Failed to locate Energy label anchor for training level OCR. Falling back to no level boost.")
                    }
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
                        // Count rainbow trainings directly from the glowing support face circles rather than inferring from an orange relationship bar, which also fired on maxed
                        // supports that were not actually glowing.
                        result.numRainbow = game.imageUtils.countRainbowTrainings(sourceBitmap)
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
                            extras = result.extras,
                            numSkillHints = result.numSkillHints,
                            trainingLevel = result.trainingLevel,
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
                processAnalysisResults(analysisResults, ignoreFailureChance, test, args)

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
     * @param test Whether the analysis is being performed for testing.
     * @param args Scenario-specific parameters from [analyzeTrainings].
     */
    private fun processAnalysisResults(results: List<TrainingAnalysisResult>, ignoreFailureChance: Boolean, test: Boolean, args: Map<String, Any?> = emptyMap()) {
        val isIrregularEvaluation = args["isIrregularEvaluation"] as? Boolean ?: false
        val minStatGainForCharm = args["minStatGainForCharm"] as? Int ?: 30
        val irregularTrainingMinStatGain = args["irregularTrainingMinStatGain"] as? Int ?: 30
        // Clear maps to ensure fresh results if reusing cached analysis.
        trainingMap.clear()
        skippedTrainingMap.clear()

        // Process results and output logs in training order.
        for (result in results) {
            // Check if risky training logic should apply based on main stat gain.
            val mainStatGain: Int = result.statGains[result.name] ?: 0
            val baseFailureChance =
                if (enableRiskyTraining && mainStatGain >= riskyTrainingMinStatGain) {
                    riskyTrainingMaxFailureChance
                } else {
                    maximumFailureChance
                }
            // Unity Cup burst exemption: a gauge ready to burst may be worth a higher failure chance than usual. No-op unless the setting is raised and a gauge is ready to burst.
            val readyToBurst = result.extras["spiritGaugesReadyToBurst"] as? Int ?: 0
            val effectiveFailureChance = burstExemptFailureChance(baseFailureChance, readyToBurst, unityCupBurstMaxFailureChance)

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
                        extras = result.extras,
                        numSkillHints = result.numSkillHints,
                        trainingLevel = result.trainingLevel,
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
                        extras = result.extras,
                        numSkillHints = result.numSkillHints,
                        trainingLevel = result.trainingLevel,
                        skipReason = "low gain with charm",
                    )
                skippedTrainingMap[result.name] = skippedTraining
                continue
            }

            // Expected-value gate: skip trainings whose total stat gain is poor value for their failure chance. Exempt when risky training opted into this training, when a Unity Cup
            // burst is ready (burst value is not captured by statGains), or for Good-Luck Charm flows (ignoreFailureChance).
            val riskyExempt = enableRiskyTraining && mainStatGain >= riskyTrainingMinStatGain
            val burstExempt = (result.extras["spiritGaugesReadyToBurst"] as? Int ?: 0) > 0
            if (!test && !ignoreFailureChance && enableExpectedValueGate && !riskyExempt && !burstExempt) {
                val totalStatGain = result.statGains.values.sum()
                if (failsExpectedValueGate(totalStatGain, result.failureChance, expectedValueGainPerFailPercent, expectedValueMinFailureChance)) {
                    MessageLog.i(TAG, "[TRAINING] Skipping ${result.name} training: poor value for risk (total gain $totalStatGain < ${expectedValueGainPerFailPercent}x fail ${result.failureChance}%).")
                    val skippedTraining =
                        TrainingOption(
                            name = result.name,
                            statGains = result.statGains,
                            correctedStats = result.correctedStats,
                            failureChance = result.failureChance,
                            relationshipBars = result.relationshipBars,
                            numRainbow = result.numRainbow,
                            extras = result.extras,
                            numSkillHints = result.numSkillHints,
                            trainingLevel = result.trainingLevel,
                            skipReason = "poor value for risk",
                        )
                    skippedTrainingMap[result.name] = skippedTraining
                    continue
                }
            }

            if (!test && isIrregularEvaluation) {
                if (mainStatGain < irregularTrainingMinStatGain) {
                    MessageLog.i(TAG, "[TRAINING] Skipping ${result.name} training due to irregular training threshold ($mainStatGain < $irregularTrainingMinStatGain).")

                    // Store the skipped training for logging purposes.
                    val skippedTraining =
                        TrainingOption(
                            name = result.name,
                            statGains = result.statGains,
                            correctedStats = result.correctedStats,
                            failureChance = result.failureChance,
                            relationshipBars = result.relationshipBars,
                            numRainbow = result.numRainbow,
                            extras = result.extras,
                            numSkillHints = result.numSkillHints,
                            trainingLevel = result.trainingLevel,
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
                    extras = result.extras,
                    numSkillHints = result.numSkillHints,
                    trainingLevel = result.trainingLevel,
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
        val corrected = crossValidateFailureChances(input, campaign.trainee.energy)

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
     * Returns a human-readable label for the current training scoring mode. Used for logging.
     * Override in Training subclasses to provide scenario-specific scoring mode labels.
     *
     * @return The scoring mode label.
     */
    open fun getTrainingScoringMode(): TrainingScoringMode = defaultScoringModeFor(campaign.date.bIsPreDebut, campaign.date.year)

    /**
     * Scores a training option for recommendation. Override in Training subclasses to use custom scoring algorithms.
     *
     * @param config The current training configuration.
     * @param option The training option to score.
     * @return The computed score.
     */
    open fun scoreTraining(config: TrainingConfig, option: TrainingOption): Double {
        return if (campaign.date.bIsPreDebut || campaign.date.year == DateYear.JUNIOR) {
            scoreFriendshipTraining(option)
        } else {
            calculateRawTrainingScore(config, option)
        }
    }

    /**
     * Runs any scenario-specific extra analysis during training analysis.
     * Called once per training stat during [analyzeTrainings]. The implementation must call
     * `result.latch.countDown()` when finished, either synchronously or from a spawned thread.
     *
     * @param result The analysis result object to populate with extra data.
     * @param sourceBitmap The current screenshot bitmap.
     * @param singleTraining Whether only a single training is being analyzed.
     */
    open fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        result.latch.countDown()
    }

    /**
     * Returns scenario-specific extra log lines for a training option. Override to add custom log output.
     *
     * @param training The training option to generate extra log fields for.
     * @return A list of log line strings, or empty if none.
     */
    open fun getExtraLogFields(training: TrainingOption): List<String> = emptyList()

    /**
     * Returns the per-mode key factors for the selection explanation. The dispatch is exhaustive so every scoring mode has explanation logic that matches
     * the scorer that produced the score. Override only to add scenario-specific factors on top of the base per-mode set.
     *
     * @param mode The scoring mode that produced the recommendation.
     * @param config The current training configuration.
     * @param selected The selected training option.
     * @param args Scenario-specific parameters.
     * @return A list of key factor strings, or empty if none.
     */
    open fun getKeyFactors(mode: TrainingScoringMode, config: TrainingConfig, selected: TrainingOption, args: Map<String, Any?> = emptyMap()): List<String> =
        when (mode) {
            TrainingScoringMode.FRIENDSHIP -> friendshipKeyFactors(selected)
            TrainingScoringMode.STAT_EFFICIENCY -> statEfficiencyKeyFactors(config, selected)
            TrainingScoringMode.UNITY_CUP -> unityCupKeyFactors(selected)
            TrainingScoringMode.TRACKBLAZER_IRREGULAR -> trackblazerIrregularKeyFactors(selected, args["irregularTrainingMinStatGain"] as? Int ?: 30)
        }

    /**
     * Recommend the best training option based on the current scoring mode and game state.
     *
     * @param forceSelection If true, the best training option will be selected even if it exceeds the failure chance threshold.
     * @param args Scenario-specific parameters.
     * @return The name of the recommended training option, or null if no suitable option is found.
     */
    fun recommendTraining(forceSelection: Boolean = false, args: Map<String, Any?> = emptyMap()): StatName? {
        val isIrregularEvaluation = args["isIrregularEvaluation"] as? Boolean ?: false
        // Build skillHintsPerLocation from the training map.
        val skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { trainingMap[it]?.numSkillHints ?: 0 }

        // Build a TrainingConfig using the current game state for use with companion object scoring functions.
        val trainingConfig =
            TrainingConfig(
                currentStats = campaign.trainee.stats.asMap(),
                statPrioritization = statPrioritization,
                eventChoiceStatPriority = eventChoiceStatPriority,
                summerTrainingStatPriority = summerTrainingStatPriority,
                statTargets =
                    if (disableStatTargets) {
                        StatName.entries.associateWith { getScenarioStatCap(game.scenario, it) }
                    } else {
                        val baseTargets = campaign.trainee.getPhaseStatTargets(campaign.date.year)
                        if (enableRunningStyleStaminaAdjustment && campaign.trainee.bHasSetRunningStyle) {
                            applyRunningStyleStaminaFactor(baseTargets, campaign.trainee.runningStyle)
                        } else {
                            baseTargets
                        }
                    },
                currentDate = campaign.date,
                scenario = game.scenario,
                enableRainbowTrainingBonus = enableRainbowTrainingBonus,
                blacklist = blacklist,
                disableTrainingOnMaxedStat = disableTrainingOnMaxedStat,
                trainingOptions = trainingMap.values.toList(),
                skillHintsPerLocation = skillHintsPerLocation,
                enablePrioritizeSkillHints = enablePrioritizeSkillHints,
                enableTrainingLevelWeighting = enableTrainingLevelWeighting,
                disableStatTargets = disableStatTargets,
                enablePrioritizeNearMaxFriendship = enablePrioritizeNearMaxFriendship,
                statsTrainedOverBuffer = statsTrainedOverBuffer,
                scoring = scoringConstantsFromSettings(),
            )

        // Compute scores and determine the best training option.
        val scoringMode: TrainingScoringMode
        val trainingScores: Map<TrainingOption, Double>
        val skippedScores: Map<TrainingOption, Double>
        val best: TrainingOption?

        scoringMode = getTrainingScoringMode()
        trainingScores = trainingMap.values.associateWith { scoreTraining(trainingConfig, it) }
        skippedScores = skippedTrainingMap.values.associateWith { scoreTraining(trainingConfig, it) }
        best = trainingScores.maxByOrNull { it.value }?.key

        // Build and log training analysis results and selection reasoning.
        val finalScoringMode = if (isIrregularEvaluation) TrainingScoringMode.TRACKBLAZER_IRREGULAR else scoringMode
        logSelectionReasoning(trainingConfig, finalScoringMode, trainingScores, skippedScores, best, args)

        if (best != null) {
            lastSelectionSource = SelectionSource.ANALYSIS
            return best.name
        }

        // Analysis produced no winning training. Pick a fallback and log which branch fired so that
        // a downstream `Selected Training: X` is never preceded by an unexplained "no training selected".
        if (forceSelection) {
            val topSkipped = skippedScores.maxByOrNull { it.value }
            if (topSkipped != null) {
                val pick = topSkipped.key
                val mainGain = pick.statGains[pick.name] ?: 0
                MessageLog.i(
                    TAG,
                    "[TRAINING] Analysis rejected all trainings. forceSelection=true → picking highest-scored REJECTED training: " +
                        "${pick.name} (score=${String.format("%.2f", topSkipped.value)}, fail=${pick.failureChance}%, mainGain=$mainGain). " +
                        "This selection will execute despite analysis rejection.",
                )
                lastSelectionSource = SelectionSource.FORCED_FROM_SKIPPED
                return pick.name
            }
            val defaulted = trainingMap.keys.firstOrNull { it !in blacklist }
            MessageLog.i(
                TAG,
                "[TRAINING] Analysis produced no scored entries. forceSelection=true → defaulting to first non-blacklisted training: ${defaulted ?: "none available"}.",
            )
            lastSelectionSource = SelectionSource.FORCED_DEFAULT
            return defaulted
        }

        val unforced = trainingMap.keys.firstOrNull { it !in blacklist }
        MessageLog.i(
            TAG,
            "[TRAINING] Analysis returned no winning training. forceSelection=false → returning first non-blacklisted training: ${unforced ?: "none available"}. Caller decides whether to execute.",
        )
        lastSelectionSource = SelectionSource.UNFORCED_DEFAULT
        return unforced
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
    private fun logSelectionReasoning(
        config: TrainingConfig,
        scoringMode: TrainingScoringMode,
        scores: Map<TrainingOption, Double>,
        skippedScores: Map<TrainingOption, Double>,
        selected: TrainingOption?,
        args: Map<String, Any?> = emptyMap(),
    ) {
        val sb = StringBuilder()
        sb.appendLine("\n========== Training Analysis Results ==========")

        // Show scoring context.
        sb.appendLine("Scoring Mode: ${scoringMode.label}")
        sb.appendLine("Current Date: ${campaign.date}")

        // Show current stats.
        val currentStats = config.currentStats
        val statNames = StatName.entries
        val currentStatsFormatted =
            statNames.joinToString(", ") {
                "${it.name.lowercase().replaceFirstChar { char -> char.titlecase() }}=${currentStats[it]}"
            }
        sb.appendLine("Current Stats: $currentStatsFormatted")

        // Show stat targets for context.
        val targets = config.statTargets
        val preferredDistance = campaign.trainee.trackDistance
        val phaseLabel =
            when (config.currentDate.year) {
                DateYear.JUNIOR -> "Junior → Classic milestone ~$classicMilestonePct%"
                DateYear.CLASSIC -> "Classic → Senior milestone ~$seniorMilestonePct%"
                DateYear.SENIOR -> "Senior → Stat targets (100%)"
            }

        val targetsFormatted =
            statNames.joinToString(", ") {
                "${it.name.lowercase().replaceFirstChar { char -> char.titlecase() }}=${targets[it]}"
            }
        if (config.disableStatTargets) {
            val cap = getScenarioStatCap(config.scenario, StatName.SPEED)
            sb.appendLine("Stat Targets: Disabled (treating cap=$cap as the target for all stats)")
        } else {
            sb.appendLine("Stat Targets ($preferredDistance) [$phaseLabel]: $targetsFormatted")
        }

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

            // Per-mode key factors. The dispatch is keyed on the same mode enum the scorer used, so the explanation always matches the scorer that ran.
            keyFactors.addAll(getKeyFactors(scoringMode, config, selected, args))

            // Global key factors.
            witOnlyKeyFactor(selected.name, scores.keys.map { it.name }.toSet(), anySkipped = skippedScores.isNotEmpty(), energy = campaign.trainee.energy)?.let { keyFactors.add(it) }

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

            if (selected.failureChance > maximumFailureChance) {
                keyFactors.add("Selected despite ${selected.failureChance}% failure chance (Risky Training enabled or Finals).")
            }

            // Output ranking reasoning if a runner-up exists.
            if (secondBest != null) {
                sb.appendLine(formatSelectionRankingLine(selected.name, selectedScore, secondBest.first.name, secondBest.second, scoringMode.label, skippedScores.size))
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

            // Structured decision trace (verbose channel, additive - not consumed by the frontend log parsers) plus a per-factor breakdown for stat-efficiency picks.
            sb.appendLine(formatDecisionTrace(scoringMode.label, selected.name, scores.mapKeys { it.key.name }))
            if (scoringMode == TrainingScoringMode.STAT_EFFICIENCY) {
                sb.appendLine(formatScoreBreakdown(selected.name, rawTrainingScoreComponents(config, selected)))
                secondBest?.let { sb.appendLine(formatScoreBreakdown(it.first.name, rawTrainingScoreComponents(config, it.first))) }
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
            if (!needsEnergyRecovery) {
                sb.appendLine("Could not confirm bot is on the Training screen. No analysis performed.")
            } else if (trainWitDuringFinale && campaign.date.day > 72) {
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

        val levelInfo = training.trainingLevel?.let { ", level=Lvl $it" } ?: ""
        val basicInfo = "${training.name} Training: stats=$formattedStatGains, fail=${training.failureChance}%, rainbows=${training.numRainbow}$levelInfo$skippedIndicator$selectedIndicator"
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

        // Print scenario-specific extra data.
        for (line in getExtraLogFields(training)) {
            sb.appendLine("  -> $line")
        }

        // Print skill hints if any.
        if (training.numSkillHints > 0) {
            sb.appendLine("  -> Skill hints: ${training.numSkillHints}")
        }
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
            trainingSelected =
                if (forceStat != null) {
                    MessageLog.i(TAG, "[TRAINING] forceStat override active - selecting $forceStat without running recommendTraining.")
                    forceStat
                } else {
                    recommendTraining()
                }

            if (trainingMap.isEmpty()) {
                // Check if we should force Wit training during the Finale instead of recovering energy.
                // Always force Wit on turn 75 since recovering energy on the very last turn is completely useless.
                if ((trainWitDuringFinale && campaign.date.day > 72) || campaign.date.day == 75) {
                    val finaleReason =
                        if (campaign.date.day == 75) {
                            MessageLog.v(TAG, "[TRAINING] It is the final turn. Forcing Wit training instead of recovering energy since resting provides zero benefit now.")
                            "Finale Turn 75: forcing WIT via direct button click (resting on final turn is wasteful)"
                        } else {
                            MessageLog.v(TAG, "[TRAINING] There is not enough energy for training to be done but the setting to train Wit during the Finale is enabled. Forcing Wit training...")
                            "Finale forced WIT (trainWitDuringFinale enabled and day>72, no other training viable)"
                        }
                    // Directly attempt to tap Wit training.
                    if (ButtonTrainingWit.click(game.imageUtils, taps = 3)) {
                        game.waitForLoading()
                        MessageLog.v(TAG, "[TRAINING] Successfully forced Wit training during the Finale instead of recovering energy.")
                        campaign.decisionTracer.recordTrainingSelection(
                            selected = StatName.WIT,
                            source = SelectionSource.FORCED_DEFAULT,
                            reason = finaleReason,
                            runnerUps = buildRunnerUps(StatName.WIT),
                        )
                        firstTrainingCheck = false
                    } else {
                        MessageLog.w(TAG, "[WARN] handleTraining:: Could not find Wit training button. Falling back to recovering energy...")
                        campaign.decisionTracer.recordTrainingSelection(
                            selected = null,
                            source = lastSelectionSource,
                            reason = "$finaleReason - but WIT button click failed; falling back to energy recovery",
                            runnerUps = buildRunnerUps(null),
                        )
                        ButtonBack.click(game.imageUtils)
                        game.wait(1.0)
                        if (campaign.checkMainScreen()) {
                            campaign.decisionTracer.recordRecoveryExecuted(
                                action = "RECOVER_ENERGY",
                                reason = "Finale forced WIT button click failed; calling recoverEnergy() as fallback",
                            )
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
                        val recoverReason =
                            if (restrictedTrainingNames.size == StatName.entries.size ||
                                (restrictedTrainingNames.size + blacklist.size) >= StatName.entries.size
                            ) {
                                MessageLog.v(TAG, "[TRAINING] Will recover energy due to all available trainings being restricted or blacklisted.")
                                "All trainings restricted or blacklisted"
                            } else {
                                MessageLog.v(TAG, "[TRAINING] Will recover energy due to either failure chance was high enough to do so or no failure chances were detected via OCR.")
                                "Failure chance too high, or OCR detected no failure chances"
                            }
                        campaign.decisionTracer.recordTrainingSelection(
                            selected = null,
                            source = lastSelectionSource,
                            reason = "Empty training map ($recoverReason); recovering energy instead",
                            runnerUps = buildRunnerUps(null),
                        )
                        campaign.decisionTracer.recordRecoveryExecuted(
                            action = "RECOVER_ENERGY",
                            reason = recoverReason,
                        )
                        campaign.recoverEnergy()
                    } else {
                        MessageLog.w(TAG, "[WARN] handleTraining:: Could not head back to the Main screen in order to recover energy.")
                    }
                }
            } else {
                // Now select the training option with the highest weight.
                campaign.decisionTracer.recordTrainingSelection(
                    selected = trainingSelected,
                    source = if (forceStat != null) SelectionSource.FORCED_DEFAULT else lastSelectionSource,
                    reason =
                        if (forceStat != null) {
                            "Caller-supplied forceStat override - executing $forceStat without analyzer re-evaluation"
                        } else {
                            "Selected by Training.handleTraining base flow (analyzer pick)"
                        },
                    runnerUps = buildRunnerUps(trainingSelected),
                )
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
     * Builds the runner-up training list for the Decision Report from live analyzer state.
     * Mirrors the Trackblazer-private snapshot-based version, but reads `cachedAnalysisResults` / `skippedTrainingMap` directly
     * since the base flow (URA Finale, Unity Cup) does not re-analyze mid-turn the way Trackblazer does (no Reset Whistle path).
     *
     * @param picked The stat selected this turn (excluded from the runner-up list). Null when no training was picked.
     * @return One entry per non-picked, non-blacklisted training that the analyzer evaluated this turn.
     */
    fun buildRunnerUps(picked: StatName?): List<DecisionTracer.TrainingRunnerUp> {
        val analyzed = cachedAnalysisResults.orEmpty()
        val runnerUps = mutableListOf<DecisionTracer.TrainingRunnerUp>()
        StatName.entries.forEach { stat ->
            if (stat == picked) return@forEach
            // Blacklisted trainings are intentional user config, not a per-turn decision - omit so the report stays focused.
            if (stat in blacklist) return@forEach
            val skipEntry = skippedTrainingMap[stat]
            val analyzedEntry = analyzed.firstOrNull { it.name == stat }
            when {
                skipEntry != null ->
                    runnerUps.add(
                        DecisionTracer.TrainingRunnerUp(
                            stat = stat,
                            rejected = true,
                            reason = skipEntry.skipReason ?: "skipped (no reason recorded)",
                            failureChance = skipEntry.failureChance,
                            statGains = skipEntry.statGains,
                        ),
                    )
                analyzedEntry != null ->
                    runnerUps.add(
                        DecisionTracer.TrainingRunnerUp(
                            stat = stat,
                            rejected = false,
                            reason = "considered by analyzer but lost to selection",
                            failureChance = analyzedEntry.failureChance.takeIf { it >= 0 },
                            statGains = analyzedEntry.statGains,
                        ),
                    )
            }
        }
        return runnerUps
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
