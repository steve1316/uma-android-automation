/**
 * Bridge types and call surface for the Kotlin Smart Race Solver. The settings UI sends a `SolverConfigSnapshot` over to the
 * `SmartRaceSolverModule` native module and renders the returned `SchedulePreview` without duplicating the beam-search algorithm in TS.
 */

import { NativeModules } from "react-native"

export interface SolverConfigSnapshot {
    /** Active scenario gate sent to the solver (e.g. "Trackblazer"). Decides which scenario-restricted epithets are eligible. */
    scenario: string
    /** Selected character preset name. Drives the seeded distance and surface aptitudes when applied. */
    characterPreset: string
    /** Distance and surface aptitude grades (S..G). Inline equivalent of `AptitudeMap` from `./constants`; see that interface for per-field docs. */
    aptitudes: { Sprint: string; Mile: string; Medium: string; Long: string; Turf: string; Dirt: string }
    /** Epithet names the user wants the solver to prioritise. Order does not matter. */
    targetEpithets: string[]
    /** Epithet names the solver must complete. Hard-locks the schedule so these always finish, regardless of score. */
    forcedEpithets: string[]
    /** Per-turn forced decisions keyed by 1-indexed turn number (as a string). Each value is a race name or `TRAIN_LOCK_SENTINEL` from `./constants`. */
    manualLocks: Record<string, string>
    /** Solver weights bundle. Inline equivalent of `WeightsMap` from `./constants`; see that interface for per-field docs. */
    weights: {
        /** Multiplier applied to every race's stat + SP reward when scoring. */
        raceValue: number
        /** Multiplier applied to epithet stat rewards. */
        epithetValue: number
        /** Per-stat-point weight in the scoring function. */
        statWeight: number
        /** Per-SP-point weight in the scoring function. */
        spWeight: number
        /** Score awarded for completing a skill-hint epithet. */
        hintWeight: number
        /** Score added to any epithet picked as a target, on top of its reward. */
        targetEpithetBonus: number
        /** Penalty per race when racing 3+ turns in a row. */
        consecutiveRacePenalty: number
        /** Penalty for racing during summer training-camp turns. */
        summerPenalty: number
        /** Percentage uplift applied to base stat / SP reward of every race before scoring. */
        raceBonusPct: number
        /** Cost subtracted from each race's reward, expressed as a percentage of a G2 baseline. */
        raceCostPct: number
        /** Per-fan score contribution applied to a race's reward fans. 0.0 ignores fans entirely (Stat Epitaphs preset default). */
        fanWeight: number
        /** Minimum aptitude rank (S..G) a race needs in BOTH its distance type and surface to be eligible. */
        aptitudeThreshold: string
        /** When true, OP and Pre-OP races are also considered alongside G1 / G2 / G3. */
        includeOpAndPreOp: boolean
        /** When true, races during the Classic / Senior summer training camps are not blocked. */
        allowSummerRacing: boolean
    }
    /** Maximum number of optional races the solver may schedule. Mandatory races always run and do not count. 0 (or less) means no limit. */
    maxRaces: number
    /** Maximum races allowed in back-to-back turns. Late-December turns are exempt. 0 (or less) means no limit. */
    maxConsecutiveRaces: number
    /** Bundled races.json passed inline so the bridge does not depend on SettingsHelper persistence having reached SQLite by the time the preview fires. */
    racesDataJson?: string
    /** Bundled epithets.json passed inline for the same reason as `racesDataJson`. */
    epithetsDataJson?: string
    /** Bundled character_objectives.json passed inline for the same reason as `racesDataJson`. */
    objectivesDataJson?: string
}

/** Decision the solver picked for a given turn: race a real race, do generic training, or rest. */
export type ScheduleEntryType = "Race" | "Train" | "Rest"

export interface ScheduleEntry {
    /** Decision the solver picked for this turn. */
    type: ScheduleEntryType
    /** Lookup key into `racesByKey`. Present only when `type === "Race"`. */
    raceKey?: string
    /** Human-readable race name (e.g. "Tokyo Yushun"). Present only when `type === "Race"`. */
    name?: string
    /** Race grade string (e.g. "G1", "OP"). Present only when `type === "Race"`; same shape as `RaceEntry.grade`. */
    grade?: string
    /** True when this race is a forced mandatory career objective the solver locked, not a chosen optional race. */
    mandatory?: boolean
}

export interface SchedulePreview {
    /** Per-turn schedule keyed by 1-indexed turn number serialised as a string (Kotlin -> JSON bridge artefact). */
    decisions: Record<string, ScheduleEntry>
    /** Epithet names the schedule completes. Consumed by `computePreviewStats` for the summary panel. */
    projectedEpithets: string[]
    /** Aggregate score the solver assigned to this schedule. Higher is better. */
    totalScore: number
    /** Populated by the Kotlin integration on a soft failure (e.g. "races data unavailable"). Presence signals a degraded result. */
    error?: string
}

/**
 * Calls the Kotlin Smart Race Solver to compute a fresh-start schedule preview for the given config.
 * On a soft failure (e.g. missing races data) the Kotlin side returns an empty schedule with `SchedulePreview.error` populated;
 * a hard failure rejects the bridge promise with an `E_SOLVER` code, which surfaces here as a thrown exception.
 *
 * @param config Snapshot of the current solver configuration to evaluate.
 * @returns Schedule preview produced by the solver; check `error` to detect soft failures.
 */
export async function previewSchedule(config: SolverConfigSnapshot): Promise<SchedulePreview> {
    const json: string = await NativeModules.SmartRaceSolverModule.previewSchedule(JSON.stringify(config))
    return JSON.parse(json) as SchedulePreview
}
