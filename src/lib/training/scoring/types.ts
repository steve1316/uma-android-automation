// src/lib/training/scoring/types.ts

/** Enum of the five trainable character stats. */
export enum StatName {
    SPEED = "SPEED",
    STAMINA = "STAMINA",
    POWER = "POWER",
    GUTS = "GUTS",
    WIT = "WIT",
}

/** Canonical ordered list of every `StatName`, used when iterating all stats in a stable order. */
export const ALL_STAT_NAMES: readonly StatName[] = [
    StatName.SPEED,
    StatName.STAMINA,
    StatName.POWER,
    StatName.GUTS,
    StatName.WIT,
]

/** Enum of the four in-game career year buckets used by date-sensitive scoring rules. */
export enum DateYear {
    PRE_DEBUT = "PRE_DEBUT",
    JUNIOR = "JUNIOR",
    CLASSIC = "CLASSIC",
    SENIOR = "SENIOR",
}

/** Snapshot of the current in-game date, including the career year, day index, and seasonal flags. */
export interface GameDate {
    /** Which career year bucket the trainee is currently in. */
    year: DateYear
    /** Zero-based day index within the career, used for the finale stat bonus and other day-driven rules. */
    day: number
    /** True when the trainee is still in the Pre-Debut window before the first race. */
    bIsPreDebut: boolean
    /** True when the current turn falls in the Summer Training block, switching to the summer priority list. */
    isSummer: boolean
}

/** Result of analyzing a single relationship bar on a training option (color and how full it is). */
export interface BarFillResult {
    /** Dominant bar color as a lowercase string ("orange", "green", "blue", etc.). */
    dominantColor: string
    /** Fill level of the bar as a percentage from 0 to 100. */
    fillPercent: number
    /** True if the bar belongs to a trainer-support character (slight scoring bonus). */
    isTrainerSupport: boolean
}

/** One analyzed training option (one of the five facilities) with all OCR-derived fields scoring needs. */
export interface TrainingOption {
    /** The primary `StatName` this training trains. */
    name: StatName
    /** Per-stat gain detected by OCR, keyed by `StatName`. */
    statGains: Partial<Record<StatName, number>>
    /** Detected failure chance for this training as a percentage from 0 to 100. */
    failureChance: number
    /** List of detected relationship bar fill levels for support cards present on this training. */
    relationshipBars: BarFillResult[]
    /** Total number of rainbow (orange) friendship bars detected on this training. */
    numRainbow: number
    /** Total number of skill hints detected on this training's support cards. */
    numSkillHints: number
    /** OCR-detected training level (1-5) for this option's primary stat, or null if unknown. */
    trainingLevel: number | null
}

/** Tunable scoring constants used by the training scoring functions. */
export interface TrainingScoringConstants {
    /** Completion-percentage breakpoints (ascending) used to bucket stats into ratio multiplier tiers. Fixed at [15, 30, 45, 60, 75, 90] and not user-tunable. */
    ratioBreakpoints: number[]
    /** Ratio multipliers paired with `ratioBreakpoints`; one entry longer than the breakpoints list (last entry covers stats at or above 90% of target). */
    ratioMultipliers: number[]
    /** Coefficient applied to the priority-list tiebreaker bonus (higher = stronger priority influence). */
    priorityCoefficient: number
    /** Level-boost factor applied when the trained stat is rank 1 in the priority list. */
    levelBoostRank1Factor: number
    /** Level-boost factor applied when the trained stat is rank 2 in the priority list. */
    levelBoostRank2Factor: number
    /** Level-boost factor applied when the trained stat is rank 3 in the priority list. */
    levelBoostRank3Factor: number
    /** Per-stat threshold for the "high main stat" rainbow-fallback bonus (gain at or above this triggers the bonus). */
    mainStatThresholds: Record<StatName, number>
    /** Multiplier applied to a training's main-stat score once `mainStatThresholds` is hit. */
    mainStatBonusMagnitude: number
    /** Base relationship value contributed by an orange (maxed) friendship bar. */
    relationshipOrangeValue: number
    /** Base relationship value contributed by a green friendship bar. */
    relationshipGreenValue: number
    /** Base relationship value contributed by a blue friendship bar. */
    relationshipBlueValue: number
    /** Diminishing-returns coefficient: relationship value decays by `fillPercent * this` as bars fill up. */
    relationshipDiminishingFactor: number
    /** Multiplier applied to relationship scores during Pre-Debut and Junior year to favor early relationship building. */
    relationshipEarlyGameBonus: number
    /** Multiplier applied to relationship bars belonging to trainer-support characters. */
    relationshipTrainerSupportBonus: number
    /** Per-hint score added to the misc score for each detected skill hint. */
    skillHintPerHintScore: number
    /** Override score returned by misc scoring when "prioritize skill hints" is enabled and any are present. */
    skillHintOverrideScore: number
    /** Stat-efficiency weight used in the weighted total when relationship bars are present. */
    statWeightWithBars: number
    /** Stat-efficiency weight used in the weighted total when no relationship bars are present. */
    statWeightWithoutBars: number
    /** Relationship-score weight used in the weighted total when relationship bars are present. */
    relationshipWeightWithBars: number
    /** Misc-score weight used in the weighted total regardless of bar presence. */
    miscWeight: number
    /** Flat bonus added to scores during Junior early-game turns to bias toward early progression. */
    juniorEarlyGameFlatBonus: number
    /** Final relationship-score scale factor applied after weighting. */
    relationshipScale: number
    /** Rainbow multiplier when `enableRainbowTrainingBonus` is true and the trainee is in Year 2+ with at least one rainbow. */
    rainbowMultiplierEnabled: number
    /** Rainbow multiplier when the trainee has rainbows in Year 2+ but the rainbow training bonus setting is disabled. */
    rainbowMultiplierDisabled: number
    /** Base value used when computing per-instance rainbow scaling for multi-rainbow trainings. */
    rainbowPerInstanceBase: number
    /** Decay coefficient applied to additional rainbow instances after the first. */
    rainbowPerInstanceDecay: number
    /** Minimum bar fill percent a green or blue bar must have to qualify for the anticipatory rainbow multiplier. */
    anticipatoryMinFillPercent: number
    /** Coefficient applied to summed bar contributions when computing the anticipatory rainbow multiplier. */
    anticipatoryCoefficient: number
    /** Maximum extra multiplier the anticipatory rainbow bonus can contribute (kept below the real rainbow multiplier). */
    anticipatoryCap: number
    /** Flat Unity Cup bonus for a training that can fill at least one Spirit Explosion gauge. Kept on the stat-efficiency scale so strong stat turns still win. */
    unityFillBaseBonus: number
    /** Additional Unity Cup fill bonus per fillable gauge, added on top of `unityFillBaseBonus`. */
    unityFillPerGaugeBonus: number
    /** Flat Unity Cup bonus for a training with at least one Spirit Explosion gauge ready to burst. Significant but not enough to always override large stat gains. */
    unityBurstBaseBonus: number
    /** Additional Unity Cup burst bonus per gauge ready to burst, added on top of `unityBurstBaseBonus`. */
    unityBurstPerGaugeBonus: number
    /** Per-fillable-gauge penalty subtracted from the fill bonus to reflect Special Training's extra energy cost. Gauge-count-scaled proxy, default 0 (off). */
    unityFillEnergyPenaltyPerGauge: number
    /** Per-ready-gauge penalty subtracted from the burst bonus to reflect Special Training's extra energy cost. Gauge-count-scaled proxy, default 0 (off). */
    unityBurstEnergyPenaltyPerGauge: number
    /** Flat Unity Cup bonus for a training with a support ready for an Extreme Spirit Burst. Larger than a normal burst so the extreme facility always outranks it. */
    unityExtremeBurstBaseBonus: number
    /** Additional Unity Cup Extreme Spirit Burst bonus per ready support, added on top of `unityExtremeBurstBaseBonus`. */
    unityExtremeBurstPerGaugeBonus: number
}

/** Default values for `TrainingScoringConstants`, matching the constants currently hardcoded in the Kotlin scoring functions. */
export const DEFAULT_TRAINING_SCORING_CONSTANTS: TrainingScoringConstants = {
    ratioBreakpoints: [15, 30, 45, 60, 75, 90],
    ratioMultipliers: [5, 4, 3, 2, 1, 0.5, 0.3],
    priorityCoefficient: 0.5,
    levelBoostRank1Factor: 0.75,
    levelBoostRank2Factor: 0.25,
    levelBoostRank3Factor: 0.1,
    mainStatThresholds: {
        [StatName.SPEED]: 30,
        [StatName.STAMINA]: 30,
        [StatName.POWER]: 30,
        [StatName.GUTS]: 30,
        [StatName.WIT]: 15,
    },
    mainStatBonusMagnitude: 2,
    relationshipOrangeValue: 0,
    relationshipGreenValue: 1,
    relationshipBlueValue: 2.5,
    relationshipDiminishingFactor: 0.5,
    relationshipEarlyGameBonus: 1.3,
    relationshipTrainerSupportBonus: 1.15,
    skillHintPerHintScore: 10,
    skillHintOverrideScore: 10000,
    statWeightWithBars: 0.6,
    statWeightWithoutBars: 0.7,
    relationshipWeightWithBars: 0.1,
    miscWeight: 0.3,
    juniorEarlyGameFlatBonus: 100,
    relationshipScale: 1.5,
    rainbowMultiplierEnabled: 2,
    rainbowMultiplierDisabled: 1.5,
    rainbowPerInstanceBase: 200,
    rainbowPerInstanceDecay: 0.5,
    anticipatoryMinFillPercent: 50,
    anticipatoryCoefficient: 0.2,
    anticipatoryCap: 0.6,
    unityFillBaseBonus: 60,
    unityFillPerGaugeBonus: 40,
    unityBurstBaseBonus: 800,
    unityBurstPerGaugeBonus: 400,
    unityFillEnergyPenaltyPerGauge: 0,
    unityBurstEnergyPenaltyPerGauge: 0,
    unityExtremeBurstBaseBonus: 2000,
    unityExtremeBurstPerGaugeBonus: 1000,
}

/** Bundle of every input the training scoring functions need to score a turn: current state, settings, and the analyzed training options. */
export interface TrainingConfig {
    /** Map of current character stats by `StatName` (missing entries default to 0). */
    currentStats: Partial<Record<StatName, number>>
    /** Ordered list of stat priorities used for regular (non-Summer) training scoring. */
    statPrioritization: StatName[]
    /** Ordered list of stat priorities applied during the Summer Training block. */
    summerTrainingStatPriority: StatName[]
    /** Map of target values for each stat, used to compute completion percentages and ratio multipliers. */
    statTargets: Partial<Record<StatName, number>>
    /** The current in-game date snapshot driving year- and day-sensitive scoring rules. */
    currentDate: GameDate
    /** The current training scenario name (e.g. "URA", "Unity Cup"). */
    scenario: string
    /** Whether the rainbow training bonus multiplier is active. */
    enableRainbowTrainingBonus: boolean
    /** List of stat trainings to ignore entirely (any blacklisted training is scored 0). */
    blacklist: (StatName | null)[]
    /** Whether to skip training for stats at or above their effective cap buffer. */
    disableTrainingOnMaxedStat: boolean
    /** List of all analyzed training options for the current turn. */
    trainingOptions: TrainingOption[]
    /** Map of detected skill hints per training stat, used by misc scoring. */
    skillHintsPerLocation: Partial<Record<StatName, number>>
    /** Whether skill-hint trainings should be prioritized via the override score. */
    enablePrioritizeSkillHints: boolean
    /** Whether to amplify priority-list stat scores by their OCR-detected training level (1-5). */
    enableTrainingLevelWeighting: boolean
    /** Whether per-distance stat targets are overridden by the scenario stat cap for all stats. */
    disableStatTargets: boolean
    /** Whether to apply an anticipatory rainbow multiplier in Year 2+ when a training has multiple near-max friendship bars. */
    enablePrioritizeNearMaxFriendship: boolean
    /** Set of stats that have already used their one-time over-buffer training allowance this run. */
    statsTrainedOverBuffer: Set<StatName>
    /** Tunable scoring constants used by the scoring functions. */
    scoring: TrainingScoringConstants
    /** Live per-stat caps OCR'd from the career/training screen (raised by sparks, inheritance, duels, extreme bursts). A present entry overrides the static per-scenario cap table; omit or leave empty to use the table. */
    statCaps?: Partial<Record<StatName, number>>
}

/** Numeric rank of each `DateYear` for ordering comparisons (PRE_DEBUT=0, JUNIOR=1, CLASSIC=2, SENIOR=3). */
export const YEAR_RANK: Record<DateYear, number> = {
    [DateYear.PRE_DEBUT]: 0,
    [DateYear.JUNIOR]: 1,
    [DateYear.CLASSIC]: 2,
    [DateYear.SENIOR]: 3,
}

/**
 * Returns true when year `a` is strictly later than year `b` per `YEAR_RANK`.
 *
 * @param a The candidate later year.
 * @param b The candidate earlier year.
 * @returns True if `a` is strictly after `b`.
 */
export function yearGreaterThan(a: DateYear, b: DateYear): boolean {
    return YEAR_RANK[a] > YEAR_RANK[b]
}
