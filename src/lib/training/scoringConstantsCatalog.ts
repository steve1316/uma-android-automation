// src/lib/training/scoringConstantsCatalog.ts
import { DEFAULT_TRAINING_SCORING_CONSTANTS, StatName } from "./scoring"

/** Tab grouping for the Advanced section. */
export type CatalogGroup = "priority" | "ratio" | "weight" | "bonuses" | "level" | "misc"

/** A single tunable multiplier entry shown as one slider row in the Advanced section. */
export interface ScoringConstantEntry {
    /** Settings key under the `training` namespace, matching what `scoringConstantsFromSettings` reads. */
    key: string
    /** Human label shown above the slider. */
    label: string
    /** One-line description, always visible. */
    description: string
    /** Which Advanced tab this belongs to. */
    group: CatalogGroup
    /** Default value sourced from `DEFAULT_TRAINING_SCORING_CONSTANTS`. */
    defaultValue: number
    /** Slider minimum. */
    min: number
    /** Slider maximum. */
    max: number
    /** Slider step. */
    step: number
    /** When set, entries in the same monotonic group are clamped relative to each other. */
    monotonicGroup?: string
    /** Optional sub-section identifier within a tab. Drives the Misc tab's flat-header grouping. */
    subgroup?: "rel" | "misc" | "rainbow" | "anticipatory" | "unityCup"
}

const D = DEFAULT_TRAINING_SCORING_CONSTANTS

/** Per-bucket label and description for the 7 ratio multipliers, in ascending completion-percent order. */
const RATIO_BUCKETS: ReadonlyArray<{ label: string; description: string }> = [
    { label: "Multiplier: <15%", description: "Applied to a stat that is below 15% of its target. Highest tier because the stat is farthest from its goal." },
    { label: "Multiplier: 15-30%", description: "Applied to a stat between 15% and 30% of its target." },
    { label: "Multiplier: 30-45%", description: "Applied to a stat between 30% and 45% of its target." },
    { label: "Multiplier: 45-60%", description: "Applied to a stat between 45% and 60% of its target." },
    { label: "Multiplier: 60-75%", description: "Applied to a stat between 60% and 75% of its target." },
    { label: "Multiplier: 75-90%", description: "Applied to a stat between 75% and 90% of its target." },
    { label: "Multiplier: 90%+", description: "Applied to a stat at or above 90% of its target. Lowest tier because the stat is at or past its goal." },
]

/** Stat display name plus its `StatName` key for the 5 main-stat thresholds, in canonical stat order. */
const MAIN_STAT_ROWS: ReadonlyArray<{ name: string; stat: StatName }> = [
    { name: "Speed", stat: StatName.SPEED },
    { name: "Stamina", stat: StatName.STAMINA },
    { name: "Power", stat: StatName.POWER },
    { name: "Guts", stat: StatName.GUTS },
    { name: "Wit", stat: StatName.WIT },
]

/** Catalog driving the Advanced section's slider rows. Order within each group is the rendered order. */
export const SCORING_CONSTANTS_CATALOG: ReadonlyArray<ScoringConstantEntry> = [
    // Priority group
    {
        key: "priorityCoefficient",
        label: "Priority coefficient",
        description: "Per-rank boost added to a stat's score for being higher in the priority list. Larger values make top-priority stats dominate.",
        group: "priority",
        defaultValue: D.priorityCoefficient,
        min: 0,
        max: 2,
        step: 0.05,
    },

    // Ratio group: 7 user-tunable multipliers (one per completion-percent bucket; bucket boundaries are fixed at 15/30/45/60/75/90)
    ...RATIO_BUCKETS.map(
        (bucket, index): ScoringConstantEntry => ({
            key: `ratioMultiplier${index + 1}`,
            label: bucket.label,
            description: bucket.description,
            group: "ratio",
            defaultValue: D.ratioMultipliers[index],
            min: 0,
            max: 10,
            step: 0.1,
            monotonicGroup: "ratio-multipliers",
        })
    ),

    // Weight group (composition of the final score)
    {
        key: "statWeightWithBars",
        label: "Stat weight (with bars)",
        description: "Weight applied to the stat-efficiency score when the training has any relationship bars.",
        group: "weight",
        defaultValue: D.statWeightWithBars,
        min: 0,
        max: 1,
        step: 0.05,
    },
    {
        key: "relationshipWeightWithBars",
        label: "Relationship weight (with bars)",
        description: "Weight applied to the relationship score when the training has any relationship bars.",
        group: "weight",
        defaultValue: D.relationshipWeightWithBars,
        min: 0,
        max: 1,
        step: 0.05,
    },
    {
        key: "miscWeight",
        label: "Misc weight",
        description: "Weight applied to the misc score: a flat 50-point baseline plus a per-skill-hint bonus. Applies whether or not the training has relationship bars.",
        group: "weight",
        defaultValue: D.miscWeight,
        min: 0,
        max: 1,
        step: 0.05,
    },

    // Bonuses group
    ...MAIN_STAT_ROWS.map(
        ({ name, stat }): ScoringConstantEntry => ({
            key: `mainStatThreshold${name}`,
            label: `Main-stat threshold (${name})`,
            description: `Minimum ${name} gain that triggers the main-stat bonus on ${name} training.`,
            group: "bonuses",
            defaultValue: D.mainStatThresholds[stat],
            min: 5,
            max: 60,
            step: 1,
        })
    ),
    {
        key: "mainStatBonusMagnitude",
        label: "Main-stat bonus magnitude",
        description: "Multiplier applied to a stat's score when its gain reaches the main-stat threshold.",
        group: "bonuses",
        defaultValue: D.mainStatBonusMagnitude,
        min: 1,
        max: 5,
        step: 0.1,
    },

    // Level group
    {
        key: "levelBoostRank1Factor",
        label: "Level boost rank 1",
        description: "Level-amplifier factor for the user's #1 priority stat.",
        group: "level",
        defaultValue: D.levelBoostRank1Factor,
        min: 0,
        max: 2,
        step: 0.05,
    },
    {
        key: "levelBoostRank2Factor",
        label: "Level boost rank 2",
        description: "Level-amplifier factor for the user's #2 priority stat.",
        group: "level",
        defaultValue: D.levelBoostRank2Factor,
        min: 0,
        max: 2,
        step: 0.05,
    },
    {
        key: "levelBoostRank3Factor",
        label: "Level boost rank 3",
        description: "Level-amplifier factor for the user's #3 priority stat.",
        group: "level",
        defaultValue: D.levelBoostRank3Factor,
        min: 0,
        max: 2,
        step: 0.05,
    },

    // Misc group: relationship-bar scoring sub-section
    {
        key: "relationshipOrangeValue",
        label: "Relationship orange value",
        description: "Base relationship value contributed by an orange (maxed) friendship bar.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipOrangeValue,
        min: 0,
        max: 10,
        step: 0.1,
    },
    {
        key: "relationshipGreenValue",
        label: "Relationship green value",
        description: "Base relationship value contributed by a green friendship bar.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipGreenValue,
        min: 0,
        max: 10,
        step: 0.1,
    },
    {
        key: "relationshipBlueValue",
        label: "Relationship blue value",
        description: "Base relationship value contributed by a blue friendship bar.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipBlueValue,
        min: 0,
        max: 10,
        step: 0.1,
    },
    {
        key: "relationshipDiminishingFactor",
        label: "Relationship diminishing factor",
        description: "Controls how much a bar's value shrinks as it fills. 0 = no shrinkage, 0.5 (default) = full bar worth half, 1 = full bar worth nothing.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipDiminishingFactor,
        min: 0,
        max: 1,
        step: 0.05,
    },
    {
        key: "relationshipEarlyGameBonus",
        label: "Relationship early-game bonus",
        description: "Multiplier applied to relationship scores during Pre-Debut and Junior year to favor early relationship building.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipEarlyGameBonus,
        min: 1,
        max: 3,
        step: 0.05,
    },
    {
        key: "relationshipTrainerSupportBonus",
        label: "Relationship trainer-support bonus",
        description: "Multiplier applied to relationship bars belonging to trainer-support characters.",
        group: "misc",
        subgroup: "rel",
        defaultValue: D.relationshipTrainerSupportBonus,
        min: 1,
        max: 2,
        step: 0.05,
    },

    // Misc group: skill-hint and misc sub-section
    {
        key: "statWeightWithoutBars",
        label: "Stat weight (no bars)",
        description: "Weight applied to the stat-efficiency score when the training has no relationship bars.",
        group: "misc",
        subgroup: "misc",
        defaultValue: D.statWeightWithoutBars,
        min: 0,
        max: 1,
        step: 0.05,
    },
    {
        key: "skillHintPerHintScore",
        label: "Skill hint per-hint score",
        description: "Per-hint score added to the misc score for each detected skill hint.",
        group: "misc",
        subgroup: "misc",
        defaultValue: D.skillHintPerHintScore,
        min: 0,
        max: 100,
        step: 1,
    },

    // Misc group: rainbow multiplier sub-section
    {
        key: "rainbowMultiplierEnabled",
        label: "Rainbow multiplier when enabled",
        description: "Rainbow multiplier when the rainbow training bonus is on and the trainee is in Year 2+ with rainbows.",
        group: "misc",
        subgroup: "rainbow",
        defaultValue: D.rainbowMultiplierEnabled,
        min: 1,
        max: 5,
        step: 0.1,
    },
    {
        key: "rainbowMultiplierDisabled",
        label: "Rainbow multiplier when disabled",
        description: "Rainbow multiplier when rainbows are present in Year 2+ but the rainbow training bonus setting is off.",
        group: "misc",
        subgroup: "rainbow",
        defaultValue: D.rainbowMultiplierDisabled,
        min: 1,
        max: 5,
        step: 0.1,
    },

    // Misc group: anticipatory multiplier sub-section
    {
        key: "anticipatoryMinFillPercent",
        label: "Near-rainbow bar fill threshold",
        description: "A green or blue bar must be filled past this percent to count toward the anticipatory bonus. Higher = only bars truly close to rainbow contribute (default 50).",
        group: "misc",
        subgroup: "anticipatory",
        defaultValue: D.anticipatoryMinFillPercent,
        min: 0,
        max: 100,
        step: 1,
    },
    {
        key: "anticipatoryCoefficient",
        label: "Anticipatory coefficient",
        description: "Coefficient applied to summed bar contributions when computing the anticipatory rainbow multiplier.",
        group: "misc",
        subgroup: "anticipatory",
        defaultValue: D.anticipatoryCoefficient,
        min: 0,
        max: 2,
        step: 0.05,
    },
    {
        key: "anticipatoryCap",
        label: "Anticipatory cap",
        description: "Maximum extra multiplier the anticipatory rainbow bonus can contribute (kept below the real rainbow multiplier).",
        group: "misc",
        subgroup: "anticipatory",
        defaultValue: D.anticipatoryCap,
        min: 0,
        max: 2,
        step: 0.05,
    },

    // Misc group: Unity Cup-only constants sub-section
    {
        key: "juniorEarlyGameFlatBonus",
        label: "Junior early-game flat bonus",
        description: "Flat bonus added to scores during Junior early-game turns to bias toward early progression.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.juniorEarlyGameFlatBonus,
        min: 0,
        max: 1000,
        step: 10,
    },
    {
        key: "relationshipScale",
        label: "Relationship scale",
        description: "Final relationship-score scale factor applied after weighting.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.relationshipScale,
        min: 0,
        max: 5,
        step: 0.05,
    },
    {
        key: "rainbowPerInstanceBase",
        label: "Rainbow per-instance base",
        description: "Base value used when computing per-instance rainbow scaling for multi-rainbow trainings.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.rainbowPerInstanceBase,
        min: 0,
        max: 1000,
        step: 10,
    },
    {
        key: "rainbowPerInstanceDecay",
        label: "Rainbow per-instance decay",
        description: "Decay coefficient applied to additional rainbow instances after the first.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.rainbowPerInstanceDecay,
        min: 0,
        max: 2,
        step: 0.05,
    },
    {
        key: "unityFillBaseBonus",
        label: "Unity fill base bonus",
        description: "Flat Unity Cup bonus for a training that can fill at least one Spirit Explosion gauge. Kept on the stat-efficiency scale so strong stat turns still win.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityFillBaseBonus,
        min: 0,
        max: 500,
        step: 10,
    },
    {
        key: "unityFillPerGaugeBonus",
        label: "Unity fill per-gauge bonus",
        description: "Additional Unity Cup fill bonus per fillable gauge, added on top of the base fill bonus.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityFillPerGaugeBonus,
        min: 0,
        max: 500,
        step: 10,
    },
    {
        key: "unityBurstBaseBonus",
        label: "Unity burst base bonus",
        description: "Flat Unity Cup bonus for a training with at least one Spirit Explosion gauge ready to burst. Significant but not enough to always override large stat gains.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityBurstBaseBonus,
        min: 0,
        max: 2000,
        step: 50,
    },
    {
        key: "unityBurstPerGaugeBonus",
        label: "Unity burst per-gauge bonus",
        description: "Additional Unity Cup burst bonus per gauge ready to burst, added on top of the base burst bonus.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityBurstPerGaugeBonus,
        min: 0,
        max: 1000,
        step: 50,
    },
    {
        key: "unityFillEnergyPenaltyPerGauge",
        label: "Unity fill energy penalty per gauge",
        description: "Per-fillable-gauge penalty subtracted from the fill bonus to reflect Special Training's extra energy cost. Default 0 (off); raise it to make strong stat turns win over pure filling.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityFillEnergyPenaltyPerGauge,
        min: 0,
        max: 200,
        step: 5,
    },
    {
        key: "unityBurstEnergyPenaltyPerGauge",
        label: "Unity burst energy penalty per gauge",
        description: "Per-ready-gauge penalty subtracted from the burst bonus to reflect Special Training's extra energy cost. Default 0 (off); raise it to temper bursting when energy is tight.",
        group: "misc",
        subgroup: "unityCup",
        defaultValue: D.unityBurstEnergyPenaltyPerGauge,
        min: 0,
        max: 400,
        step: 10,
    },
]
