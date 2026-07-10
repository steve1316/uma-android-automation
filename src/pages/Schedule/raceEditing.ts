import racesData from "../../data/races.json"
import epithetsData from "../../data/epithets.json"
import {
    isRaceEligible,
    epithetsForRace,
    turnsContributingToEpithet,
    conditionLabelsForRaceAndEpithet,
    epithetProgress,
    pendingPrerequisitesForEpithet,
    scenariosForEpithet,
    charactersForEpithet,
} from "../../lib/solver/scoring"
import type { AptitudeMap, EpithetEntry, RaceEntry, WeightsMap } from "../../lib/solver/constants"
import type { SchedulePreview } from "../../lib/solver/preview"

/** Every race in the catalog, materialised once. */
const ALL_RACES = Object.values(racesData) as unknown as RaceEntry[]

/** Every epithet in the catalog, materialised once. */
const ALL_EPITHETS = Object.values(epithetsData) as unknown as EpithetEntry[]

/** Grade tiers ranked strongest-first, used to sort each turn's eligible-race alternatives. */
const GRADE_RANK: Record<string, number> = { G1: 0, G2: 1, G3: 2, OP: 3, PRE_OP: 4, "Pre-OP": 4 }

/** Name -> epithet entry, for O(1) prerequisite lookups. */
export const EPITHETS_BY_NAME: Map<string, EpithetEntry> = new Map(ALL_EPITHETS.map((e) => [e.name, e]))

/** One epithet's progression line for a race, as shown in the per-turn modal. */
export interface ProgressionLine {
    /** Epithet name. */
    epithet: string
    /** "(before/required -> after/required) " prefix, or "" when progress is unknown. */
    progLabel: string
    /** The race/epithet condition labels. */
    conditions: string[]
    /** "Still pending" prerequisite lines. */
    pending: string[]
}

/**
 * Buckets every eligible race by the turn it runs on, sorted strongest-first within each turn.
 *
 * @param aptitudes The runner's aptitude ranks per distance/terrain slot.
 * @param weights Solver weights, used here for the aptitude threshold and OP/Pre-OP inclusion gates.
 * @returns Map from turn number to the eligible races on that turn, sorted by grade rank then descending fans.
 */
export const buildEligibleRacesByTurn = (aptitudes: AptitudeMap, weights: WeightsMap): Map<number, RaceEntry[]> => {
    const byTurn = new Map<number, RaceEntry[]>()
    for (const race of ALL_RACES) {
        if (!isRaceEligible(race, aptitudes, weights)) continue
        const list = byTurn.get(race.turnNumber) ?? []
        list.push(race)
        byTurn.set(race.turnNumber, list)
    }
    for (const list of byTurn.values()) {
        list.sort((a, b) => (GRADE_RANK[a.grade] ?? 99) - (GRADE_RANK[b.grade] ?? 99) || b.fans - a.fans)
    }
    return byTurn
}

/**
 * Whether an epithet is visible under the active scenario and character-preset gates.
 *
 * @param e The epithet entry to check.
 * @param scenario The active career scenario name, e.g. "Trackblazer". Defaults to "Trackblazer" when falsy.
 * @param characterPreset The active character preset name, or "" when none is selected.
 * @returns False when the epithet is restricted away by scenario or character, true otherwise.
 */
export const isEpithetAllowed = (e: EpithetEntry, scenario: string, characterPreset: string): boolean => {
    const activeScenario = (scenario || "Trackblazer").toLowerCase()
    const activePreset = (characterPreset || "").toLowerCase()
    const scenarioRestrictions = scenariosForEpithet(e).map((s) => s.toLowerCase())
    if (scenarioRestrictions.length > 0 && !scenarioRestrictions.includes(activeScenario)) return false
    const characterRestrictions = charactersForEpithet(e).map((c) => c.toLowerCase())
    if (characterRestrictions.length > 0 && activePreset && !characterRestrictions.includes(activePreset)) return false
    return true
}

/**
 * Builds the set of epithet names visible under the active scenario and character-preset gates.
 *
 * @param scenario The active career scenario name, e.g. "Trackblazer". Defaults to "Trackblazer" when falsy.
 * @param characterPreset The active character preset name, or "" when none is selected.
 * @returns Names of epithets not restricted away by scenario or character.
 */
export const buildAllowedEpithetNames = (scenario: string, characterPreset: string): Set<string> => {
    return new Set(ALL_EPITHETS.filter((e) => isEpithetAllowed(e, scenario, characterPreset)).map((e) => e.name))
}

/**
 * Builds the epithet-progression lines shown when a race is scheduled on a turn: which epithets it advances,
 * how far, and any still-pending prerequisites.
 *
 * @param race The race scheduled on `turn`.
 * @param turn The 1-indexed turn number the race runs on.
 * @param preview The current schedule preview, used to compute before/after progress.
 * @param racesByKey Lookup table from race key to race entry.
 * @param allowedNames Epithet names visible under the active scenario/character gates, from `buildAllowedEpithetNames`.
 * @returns One `ProgressionLine` per epithet the race actually contributes to on this turn.
 */
export const raceProgressionLines = (race: RaceEntry, turn: number, preview: SchedulePreview, racesByKey: Record<string, RaceEntry>, allowedNames: Set<string>): ProgressionLine[] => {
    const matched = epithetsForRace(race).filter((ep) => allowedNames.has(ep.name) && turnsContributingToEpithet(ep, preview, racesByKey).has(turn))
    return matched.map((ep) => {
        const before = epithetProgress(turn - 1, ep, preview, racesByKey)
        const after = epithetProgress(turn, ep, preview, racesByKey)
        const progLabel = before && after ? `(${before.current}/${before.required} -> ${after.current}/${after.required}) ` : ""
        const conditions = conditionLabelsForRaceAndEpithet(race, ep)
        const pending = pendingPrerequisitesForEpithet(ep, turn, EPITHETS_BY_NAME, preview, racesByKey)
        return { epithet: ep.name, progLabel, conditions, pending }
    })
}
