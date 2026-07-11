package com.steve1316.uma_scoring

/**
 * The shared, canonical type surface for training scoring. Every type is `@JsExport`-annotated so the Kotlin/JS target emits TypeScript declarations the React Native sandbox
 * can consume. The Android bot consumes them as ordinary JVM classes; the math (in `Scoring.kt`) is parameterized over these and the same compiled functions run on both
 * runtimes.
 */

/** The five trainable stats. Mirrors the in-game stat icons. */
@JsExport
enum class StatName {
    SPEED,
    STAMINA,
    POWER,
    GUTS,
    WIT,
    ;

    companion object {
        /** Mapping of stat names to their corresponding enum entries. */
        private val nameMap = entries.associateBy { it.name }

        fun fromName(value: String): StatName? = nameMap[value.uppercase()]
    }
}

/** The three career years. Comparable via natural enum ordinal (`JUNIOR < CLASSIC < SENIOR`), which the scoring math relies on for "Year 2+" rainbow gating. */
@JsExport
enum class DateYear(val longName: String) {
    JUNIOR("JUNIOR YEAR"),
    CLASSIC("CLASSIC YEAR"),
    SENIOR("SENIOR YEAR"),
    ;

    companion object {
        private val nameMap = entries.associateBy { it.name }
        private val ordinalMap = entries.associateBy { it.ordinal }

        fun fromName(value: String): DateYear? = nameMap[value.uppercase()]

        fun fromOrdinal(ordinal: Int): DateYear? = ordinalMap[ordinal]
    }
}

/**
 * The minimal date information the scoring math needs at decision time. The Android bot keeps its richer `GameDate` class for navigation and date math; only this snapshot is
 * passed across the shared-scoring boundary. Year drives rainbow / early-game gating, `day` drives finale-bonus accounting, `bIsPreDebut` toggles the early-game relationship
 * bonus, and `isSummer` selects the Summer-specific priority list.
 *
 * @property year Career year (junior/classic/senior).
 * @property day Turn number, 1-75. Used by `getFinaleStatBonus` to compute remaining finale races.
 * @property bIsPreDebut True before the trainee has debuted. Triggers the early-game relationship bonus alongside `JUNIOR`.
 * @property isSummer True during the Summer Training block. Selects `summerTrainingStatPriority` instead of `statPrioritization`.
 */
@JsExport
data class GameDateSnapshot(
    val year: DateYear,
    val day: Int = 0,
    val bIsPreDebut: Boolean = false,
    val isSummer: Boolean = false,
)

/**
 * The friendship-bar inputs the scoring math reads. Lean by design - the Android OCR pipeline keeps a richer `CustomImageUtils.BarFillResult` that also carries the source
 * `StatBlock`; only these three fields cross the shared-scoring boundary.
 *
 * @property dominantColor Lower-case bar color: `"orange"`, `"green"`, `"blue"`, or any non-scoring sentinel. The scoring math switches off this.
 * @property fillPercent Fill level of the bar in [0.0, 100.0]. Diminishing-returns and anticipatory math both read this.
 * @property isTrainerSupport True when the bar belongs to a Trainer support card. Picks up `relationshipTrainerSupportBonus`.
 */
@JsExport
data class BarFillResult(
    val dominantColor: String,
    val fillPercent: Double,
    val isTrainerSupport: Boolean = false,
)

/**
 * The scoring-math view of one training option. Slim by design - the Android bot keeps a richer `Training.TrainingOption` carrying OCR-correction data, scenario extras, and
 * skip-reason metadata, and the React Native sandbox builds this directly from its synthetic scenario. Both call sites convert into this shape at the boundary so the
 * shared math doesn't drag Android-only types into commonMain.
 *
 * @property name Primary stat of this training (the icon at the top of the column).
 * @property statGains Stat gain map detected from OCR (Android) or simulated (sandbox). Keys not present default to 0 in the math.
 * @property relationshipBars Detected friendship bars, in any order. Used by relationship + anticipatory scoring.
 * @property numRainbow Count of rainbow tints detected on this training. Drives the rainbow multiplier.
 * @property numSkillHints Count of skill-hint icons on this training. The math reads `config.skillHintsPerLocation[name]` instead; this field is forwarded for telemetry.
 * @property trainingLevel OCR-detected facility level (1-5), or null when the feature is off or detection failed. Drives the level-boost multiplier.
 */
@JsExport
data class TrainingOption(
    val name: StatName,
    val statGains: Map<StatName, Int>,
    val relationshipBars: List<BarFillResult>,
    val numRainbow: Int,
    val numSkillHints: Int = 0,
    val trainingLevel: Int? = null,
)

/**
 * The scoring-math view of the overall trainee state and tunable behavior. Slim by design - the Android bot keeps a richer `Training.TrainingConfig` that carries the analyzed
 * `trainingOptions` list, event-choice priorities, and other fields unrelated to per-training scoring; only the inputs the math reads cross the shared-scoring boundary.
 *
 * @property currentStats Current trainee stats keyed by stat. Missing entries are treated as 0.
 * @property statPrioritization Ordered priority list for regular trainings. Top of list = highest weight.
 * @property summerTrainingStatPriority Ordered priority list used during the Summer Training block (when `currentDate.isSummer`).
 * @property statTargets Per-stat target values used to bucket completion %. Missing entries skip the stat.
 * @property currentDate Date snapshot driving year + day + summer + pre-debut gating.
 * @property scenario Current campaign name. Drives stat-cap lookup.
 * @property enableRainbowTrainingBonus When false, rainbow trainings get a smaller multiplier (the "disabled" tier).
 * @property blacklist Stat names the user is skipping. The math returns 0 for any blacklisted training.
 * @property disableTrainingOnMaxedStat When true, trainings whose primary stat is at or past the buffer are skipped (single rainbow allowance applies).
 * @property skillHintsPerLocation Per-training skill-hint counts the misc-score logic reads.
 * @property enablePrioritizeSkillHints When true and any hints are present, the misc score floors to `skillHintOverrideScore` so hint trainings dominate.
 * @property enableTrainingLevelWeighting When true, level boosts amplify priority-stat scoring.
 * @property enablePrioritizeNearMaxFriendship When true (Year 2+), trainings with multiple near-max friendship bars receive the anticipatory rainbow multiplier.
 * @property statsTrainedOverBuffer Set of stats that already used their single rainbow allowance over the buffer.
 * @property scoring The numeric tuning constants. Default reproduces current hardcoded behavior.
 */
@JsExport
data class TrainingConfig(
    val currentStats: Map<StatName, Int>,
    val statPrioritization: List<StatName>,
    val summerTrainingStatPriority: List<StatName>,
    val statTargets: Map<StatName, Int>,
    val currentDate: GameDateSnapshot,
    val scenario: String,
    val enableRainbowTrainingBonus: Boolean,
    val blacklist: List<StatName?> = emptyList(),
    val disableTrainingOnMaxedStat: Boolean = false,
    val skillHintsPerLocation: Map<StatName, Int> = StatName.entries.associateWith { 0 },
    val enablePrioritizeSkillHints: Boolean = false,
    val enableTrainingLevelWeighting: Boolean = false,
    val enablePrioritizeNearMaxFriendship: Boolean = true,
    val statsTrainedOverBuffer: Set<StatName> = emptySet(),
    val scoring: TrainingScoringConstants = TrainingScoringConstants(),
)

/**
 * All tunable numeric constants used by the training scoring math. Each property keeps its current hardcoded value as the default so existing behavior is preserved when no
 * overrides are supplied. The Android bot and the React Native sandbox both load instances of this class (via `scoringConstantsFromMap` in `Scoring.kt`) and feed it into the
 * shared scoring functions.
 *
 * @property ratioBreakpoints Completion-percent boundaries that bucket each stat into a ratio-multiplier tier. Fixed at [15, 30, 45, 60, 75, 90] and not user-tunable.
 * @property ratioMultipliers Multipliers paired with `ratioBreakpoints`, indexed by which bucket the stat's completion percent falls into. Index 0 applies when completion is below `ratioBreakpoints[0]`. The final entry applies when completion is above every breakpoint.
 * @property priorityCoefficient Linear coefficient applied to `(activePriority.size - priorityIndex)` to produce the per-stat priority multiplier. Default 0.5 makes priority a primary driver: in a 4-stat list, the top stat receives a 3.0x multiplier and the bottom a 1.5x.
 * @property levelBoostRank1Factor Weight applied to the level-boost multiplier when the stat is the top-priority entry. Combines with the OCR-detected training level to amplify the score for high-level priority trainings.
 * @property levelBoostRank2Factor As `levelBoostRank1Factor`, applied to the second-priority stat.
 * @property levelBoostRank3Factor As `levelBoostRank1Factor`, applied to the third-priority stat. Ranks 4+ receive no boost.
 * @property mainStatThresholds Per-stat gain threshold at which a training's main-stat score receives the `mainStatBonusMagnitude` multiplier. Acts as a "this looks like an undetected rainbow" heuristic.
 * @property mainStatBonusMagnitude Multiplier applied to a stat's score when the training's main-stat gain meets the per-stat threshold in `mainStatThresholds`.
 * @property relationshipOrangeValue Base score contribution for an orange (early-stage) relationship bar.
 * @property relationshipGreenValue Base score contribution for a green (mid-stage) relationship bar.
 * @property relationshipBlueValue Base score contribution for a blue (near-rainbow) relationship bar.
 * @property relationshipDiminishingFactor Coefficient on the bar's fill level for diminishing-returns scaling. Higher values penalize fuller bars more aggressively.
 * @property relationshipEarlyGameBonus Multiplier applied to relationship score during Junior Year or pre-debut. Encourages relationship building when there is still time to benefit.
 * @property relationshipTrainerSupportBonus Multiplier applied to bars belonging to the Trainer support card to slightly prefer Trainer over other supports.
 * @property skillHintPerHintScore Score added per detected skill hint icon on a training.
 * @property skillHintOverrideScore Floor added to the misc score when the user has enabled "prioritize skill hints" and any hints are present, ensuring skill-hint trainings outscore everything else.
 * @property statWeightWithBars Weight applied to stat-efficiency score when the training has at least one relationship bar.
 * @property statWeightWithoutBars Weight applied to stat-efficiency score when the training has no relationship bars (Trainer-only training).
 * @property relationshipWeightWithBars Weight applied to relationship score in the raw-score composition when bars are present.
 * @property miscWeight Weight applied to misc score in the raw-score composition.
 * @property juniorEarlyGameFlatBonus Flat score bonus added during Junior Year to encourage gauge filling early.
 * @property relationshipScale Multiplier applied to the aggregate relationship score before adding it to the Unity Cup raw score. Tuned to be a significant bonus without exceeding the rainbow burst threshold.
 * @property unityFillBaseBonus Flat Unity Cup bonus for a training that can fill at least one Spirit Explosion gauge. Kept on the stat-efficiency scale so strong stat turns still win.
 * @property unityFillPerGaugeBonus Additional Unity Cup fill bonus per fillable gauge, added on top of `unityFillBaseBonus`.
 * @property unityBurstBaseBonus Flat Unity Cup bonus for a training with at least one Spirit Explosion gauge ready to burst. Significant but not enough to always override large stat gains.
 * @property unityBurstPerGaugeBonus Additional Unity Cup burst bonus per gauge ready to burst, added on top of `unityBurstBaseBonus`.
 * @property unityFillEnergyPenaltyPerGauge Per-fillable-gauge penalty subtracted from the fill bonus to reflect Special Training's extra energy cost. Gauge-count-scaled proxy, default 0 (off).
 * @property unityBurstEnergyPenaltyPerGauge Per-ready-gauge penalty subtracted from the burst bonus to reflect Special Training's extra energy cost. Gauge-count-scaled proxy, default 0 (off).
 * @property rainbowMultiplierEnabled Multiplier applied to total score in Year 2+ when at least one rainbow is detected and the user has enabled the rainbow training bonus.
 * @property rainbowMultiplierDisabled Multiplier applied when rainbows are detected but the user has disabled the rainbow training bonus. Kept below `rainbowMultiplierEnabled`.
 * @property rainbowPerInstanceBase Base value for the per-rainbow bonus score, geometrically decayed by `rainbowPerInstanceDecay`.
 * @property rainbowPerInstanceDecay Geometric decay factor for the per-rainbow bonus. Lower values mean each additional rainbow contributes less.
 * @property anticipatoryMinFillPercent Minimum fill percent for a green or blue bar to count toward the anticipatory near-max-friendship multiplier.
 * @property anticipatoryCoefficient Coefficient on the sum of qualifying bar fill ratios when computing the anticipatory multiplier.
 * @property anticipatoryCap Maximum extra fraction the anticipatory multiplier can add (cap above 1.0). Kept below the real rainbow multiplier so anticipation never outranks a detected rainbow.
 */
@JsExport
data class TrainingScoringConstants(
    val ratioBreakpoints: List<Double> = listOf(15.0, 30.0, 45.0, 60.0, 75.0, 90.0),
    val ratioMultipliers: List<Double> = listOf(5.0, 4.0, 3.0, 2.0, 1.0, 0.5, 0.3),
    val priorityCoefficient: Double = 0.5,
    val levelBoostRank1Factor: Double = 0.75,
    val levelBoostRank2Factor: Double = 0.25,
    val levelBoostRank3Factor: Double = 0.10,
    val mainStatThresholds: Map<StatName, Int> =
        mapOf(
            StatName.SPEED to 30,
            StatName.STAMINA to 30,
            StatName.POWER to 30,
            StatName.GUTS to 30,
            StatName.WIT to 15,
        ),
    val mainStatBonusMagnitude: Double = 2.0,
    val relationshipOrangeValue: Double = 0.0,
    val relationshipGreenValue: Double = 1.0,
    val relationshipBlueValue: Double = 2.5,
    val relationshipDiminishingFactor: Double = 0.5,
    val relationshipEarlyGameBonus: Double = 1.3,
    val relationshipTrainerSupportBonus: Double = 1.15,
    val skillHintPerHintScore: Double = 10.0,
    val skillHintOverrideScore: Double = 10000.0,
    val statWeightWithBars: Double = 0.6,
    val statWeightWithoutBars: Double = 0.7,
    val relationshipWeightWithBars: Double = 0.1,
    val miscWeight: Double = 0.3,
    val juniorEarlyGameFlatBonus: Double = 100.0,
    val relationshipScale: Double = 1.5,
    val rainbowMultiplierEnabled: Double = 2.0,
    val rainbowMultiplierDisabled: Double = 1.5,
    val rainbowPerInstanceBase: Double = 200.0,
    val rainbowPerInstanceDecay: Double = 0.5,
    val anticipatoryMinFillPercent: Double = 50.0,
    val anticipatoryCoefficient: Double = 0.2,
    val anticipatoryCap: Double = 0.6,
    val unityFillBaseBonus: Double = 60.0,
    val unityFillPerGaugeBonus: Double = 40.0,
    val unityBurstBaseBonus: Double = 800.0,
    val unityBurstPerGaugeBonus: Double = 400.0,
    val unityFillEnergyPenaltyPerGauge: Double = 0.0,
    val unityBurstEnergyPenaltyPerGauge: Double = 0.0,
) {
    init {
        require(ratioMultipliers.size == ratioBreakpoints.size + 1) {
            "ratioMultipliers must have exactly one more entry than ratioBreakpoints (got ${ratioMultipliers.size} multipliers vs ${ratioBreakpoints.size} breakpoints)"
        }
    }
}

/**
 * Ordered decomposition of a raw training score so callers can log exactly how a stat-efficiency score was built. Each score field is the value AFTER its weight is applied.
 * `total` equals what `calculateRawTrainingScore` returns for the same inputs, so the breakdown is a faithful explanation, not an approximation.
 *
 * @property statScoreWeighted Stat-efficiency score after the stat weight.
 * @property relationshipScoreWeighted Relationship score after the relationship weight.
 * @property miscScoreWeighted Misc score after the misc weight.
 * @property rainbowMultiplier Multiplier applied for detected rainbow trainings (1.0 when none apply).
 * @property anticipatoryMultiplier Multiplier applied for near-max friendship bars (1.0 when none apply).
 * @property total The final composed score, equal to `calculateRawTrainingScore` for the same inputs.
 */
@JsExport
data class RawScoreBreakdown(
    val statScoreWeighted: Double,
    val relationshipScoreWeighted: Double,
    val miscScoreWeighted: Double,
    val rainbowMultiplier: Double,
    val anticipatoryMultiplier: Double,
    val total: Double,
)
