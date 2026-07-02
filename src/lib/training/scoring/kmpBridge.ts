// src/lib/training/scoring/kmpBridge.ts
// Bridge between the React Native side's idiomatic TypeScript shapes (`Record<StatName, number>`, string-valued enums, plain object literals) and the Kotlin/JS exports from
// `uma-scoring` (deep `com.steve1316.uma_scoring.*` namespace, `KtMap` / `KtList` / `KtSet` wrappers, class-instance enums). The TS scoring lib delegates to the shared math
// through this file so the formulas live in exactly one place (`android/scoring-shared/src/commonMain`) while the TypeScript API surface stays unchanged for callers.
import * as uma from "uma-scoring"
import { BarFillResult, DateYear, GameDate, StatName, TrainingConfig, TrainingOption, TrainingScoringConstants } from "./types"

const ns = uma.com.steve1316.uma_scoring
const collections = uma.kotlin.collections

/** Flat re-export of the shared-scoring namespace. Use this for direct calls into the Kotlin/JS scoring math when the TS wrappers below aren't enough. */
export const kmp = ns

type KtStatName = ReturnType<typeof ns.StatName.values>[number]
type KtDateYear = ReturnType<typeof ns.DateYear.values>[number]
type KtMap<K, V> = ReturnType<typeof collections.KtMap.fromJsMap<K, V>>
type KtList<T> = ReturnType<typeof collections.KtList.fromJsArray<T>>
type KtSet<T> = ReturnType<typeof collections.KtSet.fromJsSet<T>>

/** Convert a TS `StatName` string enum value to the Kotlin/JS `StatName` class instance. */
export function toKtStatName(s: StatName): KtStatName {
    return ns.StatName.valueOf(s)
}

/** Convert a TS `StatName | null` to the Kotlin/JS class instance or null. */
function toKtStatNameNullable(s: StatName | null): KtStatName | null {
    return s == null ? null : toKtStatName(s)
}

/** Convert a Kotlin/JS `StatName` class instance back to the TS string enum value. */
function fromKtStatName(s: KtStatName): StatName {
    return s.name as StatName
}

/** Convert a TS `Partial<Record<StatName, number>>` to a `KtMap<StatName, number>` keyed by `ns.StatName` instances. */
function toKtStatMap(record: Partial<Record<StatName, number>>): KtMap<KtStatName, number> {
    const m = new Map<KtStatName, number>()
    for (const [key, value] of Object.entries(record) as Array<[StatName, number | undefined]>) {
        if (value !== undefined) m.set(toKtStatName(key), value)
    }
    return collections.KtMap.fromJsMap(m)
}

/** Convert a Kotlin/JS `KtMap<StatName, number>` back to a TS `Record<StatName, number>`. */
function fromKtStatMap(ktMap: KtMap<KtStatName, number>): Record<StatName, number> {
    const out = {} as Record<StatName, number>
    const view = ktMap.asJsReadonlyMapView()
    view.forEach((value, key) => {
        out[fromKtStatName(key)] = value
    })
    return out
}

/** Convert a TS `StatName[]` (or list with nulls) to a `KtList`. */
function toKtStatList(arr: ReadonlyArray<StatName>): KtList<KtStatName> {
    return collections.KtList.fromJsArray(arr.map(toKtStatName))
}

/** Convert a TS `(StatName | null)[]` to a `KtList`. */
function toKtNullableStatList(arr: ReadonlyArray<StatName | null>): KtList<KtStatName | null> {
    return collections.KtList.fromJsArray(arr.map(toKtStatNameNullable))
}

/** Convert a TS `Set<StatName>` to a `KtSet`. */
function toKtStatSet(s: ReadonlySet<StatName>): KtSet<KtStatName> {
    return collections.KtSet.fromJsSet(new Set(Array.from(s).map(toKtStatName)))
}

/** Convert the TS `DateYear` enum to the Kotlin/JS `DateYear` class instance. `PRE_DEBUT` collapses to `JUNIOR` (the Kotlin side carries pre-debut as a separate `bIsPreDebut` flag on the snapshot). */
function toKtDateYear(year: DateYear): KtDateYear {
    if (year === DateYear.PRE_DEBUT) return ns.DateYear.JUNIOR
    return ns.DateYear.valueOf(year)
}

/** Convert a TS `GameDate` to a Kotlin/JS `GameDateSnapshot`. `PRE_DEBUT` is collapsed to `JUNIOR + bIsPreDebut=true`. */
function toKtGameDateSnapshot(date: GameDate): InstanceType<typeof ns.GameDateSnapshot> {
    const bIsPreDebut = date.bIsPreDebut || date.year === DateYear.PRE_DEBUT
    return new ns.GameDateSnapshot(toKtDateYear(date.year), date.day, bIsPreDebut, date.isSummer)
}

/** Convert a TS `BarFillResult` to the Kotlin/JS class. */
function toKtBarFillResult(bar: BarFillResult): InstanceType<typeof ns.BarFillResult> {
    return new ns.BarFillResult(bar.dominantColor, bar.fillPercent, bar.isTrainerSupport)
}

/** Convert a TS `TrainingScoringConstants` to the Kotlin/JS class. All 30+ fields are passed positionally; the constructor accepts every parameter as optional with the same defaults as commonMain. */
export function toKtScoringConstants(c: TrainingScoringConstants): InstanceType<typeof ns.TrainingScoringConstants> {
    return new ns.TrainingScoringConstants(
        collections.KtList.fromJsArray(c.ratioBreakpoints),
        collections.KtList.fromJsArray(c.ratioMultipliers),
        c.priorityCoefficient,
        c.levelBoostRank1Factor,
        c.levelBoostRank2Factor,
        c.levelBoostRank3Factor,
        toKtStatMap(c.mainStatThresholds),
        c.mainStatBonusMagnitude,
        c.relationshipOrangeValue,
        c.relationshipGreenValue,
        c.relationshipBlueValue,
        c.relationshipDiminishingFactor,
        c.relationshipEarlyGameBonus,
        c.relationshipTrainerSupportBonus,
        c.skillHintPerHintScore,
        c.skillHintOverrideScore,
        c.statWeightWithBars,
        c.statWeightWithoutBars,
        c.relationshipWeightWithBars,
        c.miscWeight,
        c.juniorEarlyGameFlatBonus,
        c.relationshipScale,
        c.rainbowMultiplierEnabled,
        c.rainbowMultiplierDisabled,
        c.rainbowPerInstanceBase,
        c.rainbowPerInstanceDecay,
        c.anticipatoryMinFillPercent,
        c.anticipatoryCoefficient,
        c.anticipatoryCap,
        c.unityFillBaseBonus,
        c.unityFillPerGaugeBonus,
        c.unityBurstBaseBonus,
        c.unityBurstPerGaugeBonus,
        c.unityFillEnergyPenaltyPerGauge,
        c.unityBurstEnergyPenaltyPerGauge,
    )
}

/** Convert a Kotlin/JS `TrainingScoringConstants` instance back to a TS plain object. */
export function fromKtScoringConstants(c: InstanceType<typeof ns.TrainingScoringConstants>): TrainingScoringConstants {
    return {
        ratioBreakpoints: Array.from(c.ratioBreakpoints.asJsReadonlyArrayView()),
        ratioMultipliers: Array.from(c.ratioMultipliers.asJsReadonlyArrayView()),
        priorityCoefficient: c.priorityCoefficient,
        levelBoostRank1Factor: c.levelBoostRank1Factor,
        levelBoostRank2Factor: c.levelBoostRank2Factor,
        levelBoostRank3Factor: c.levelBoostRank3Factor,
        mainStatThresholds: fromKtStatMap(c.mainStatThresholds),
        mainStatBonusMagnitude: c.mainStatBonusMagnitude,
        relationshipOrangeValue: c.relationshipOrangeValue,
        relationshipGreenValue: c.relationshipGreenValue,
        relationshipBlueValue: c.relationshipBlueValue,
        relationshipDiminishingFactor: c.relationshipDiminishingFactor,
        relationshipEarlyGameBonus: c.relationshipEarlyGameBonus,
        relationshipTrainerSupportBonus: c.relationshipTrainerSupportBonus,
        skillHintPerHintScore: c.skillHintPerHintScore,
        skillHintOverrideScore: c.skillHintOverrideScore,
        statWeightWithBars: c.statWeightWithBars,
        statWeightWithoutBars: c.statWeightWithoutBars,
        relationshipWeightWithBars: c.relationshipWeightWithBars,
        miscWeight: c.miscWeight,
        juniorEarlyGameFlatBonus: c.juniorEarlyGameFlatBonus,
        relationshipScale: c.relationshipScale,
        rainbowMultiplierEnabled: c.rainbowMultiplierEnabled,
        rainbowMultiplierDisabled: c.rainbowMultiplierDisabled,
        rainbowPerInstanceBase: c.rainbowPerInstanceBase,
        rainbowPerInstanceDecay: c.rainbowPerInstanceDecay,
        anticipatoryMinFillPercent: c.anticipatoryMinFillPercent,
        anticipatoryCoefficient: c.anticipatoryCoefficient,
        anticipatoryCap: c.anticipatoryCap,
        unityFillBaseBonus: c.unityFillBaseBonus,
        unityFillPerGaugeBonus: c.unityFillPerGaugeBonus,
        unityBurstBaseBonus: c.unityBurstBaseBonus,
        unityBurstPerGaugeBonus: c.unityBurstPerGaugeBonus,
        unityFillEnergyPenaltyPerGauge: c.unityFillEnergyPenaltyPerGauge,
        unityBurstEnergyPenaltyPerGauge: c.unityBurstEnergyPenaltyPerGauge,
    }
}

/** Convert a TS `TrainingConfig` to a Kotlin/JS `TrainingConfig`. The shared shape drops a handful of TS fields the math doesn't read (`eventChoiceStatPriority`, `disableStatTargets`, `trainingOptions`). */
export function toKtTrainingConfig(config: TrainingConfig): InstanceType<typeof ns.TrainingConfig> {
    return new ns.TrainingConfig(
        toKtStatMap(config.currentStats),
        toKtStatList(config.statPrioritization),
        toKtStatList(config.summerTrainingStatPriority),
        toKtStatMap(config.statTargets),
        toKtGameDateSnapshot(config.currentDate),
        config.scenario,
        config.enableRainbowTrainingBonus,
        toKtNullableStatList(config.blacklist),
        config.disableTrainingOnMaxedStat,
        toKtStatMap(config.skillHintsPerLocation),
        config.enablePrioritizeSkillHints,
        config.enableTrainingLevelWeighting,
        config.enablePrioritizeNearMaxFriendship,
        toKtStatSet(config.statsTrainedOverBuffer),
        toKtScoringConstants(config.scoring),
    )
}

/** Convert a TS `TrainingOption` to a Kotlin/JS `TrainingOption`. */
export function toKtTrainingOption(t: TrainingOption): InstanceType<typeof ns.TrainingOption> {
    return new ns.TrainingOption(
        toKtStatName(t.name),
        toKtStatMap(t.statGains),
        collections.KtList.fromJsArray(t.relationshipBars.map(toKtBarFillResult)),
        t.numRainbow,
        t.numSkillHints,
        t.trainingLevel,
    )
}

/** Convert a TS `Record<string, unknown>` settings map to a `KtMap<string, any | null>`. */
export function toKtSettingsMap(settings: Record<string, unknown>): KtMap<string, unknown | null> {
    const m = new Map<string, unknown | null>()
    for (const [key, value] of Object.entries(settings)) {
        m.set(key, value as unknown | null)
    }
    return collections.KtMap.fromJsMap(m)
}
