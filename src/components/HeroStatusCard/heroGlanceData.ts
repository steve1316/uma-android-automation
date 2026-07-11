import type { Settings } from "../../context/BotStateContext"
import { skillPlanSettingsPages } from "../../pages/SkillPlanSettings/config"
import { DEBUG_TESTS } from "../../pages/DebugSettings/debugTests"

// Stat priority abbreviations shown on the hero's Priority row.
const STAT_ABBREVIATIONS: Record<string, string> = {
    Speed: "SPD",
    Stamina: "STA",
    Power: "POW",
    Guts: "GUT",
    Wit: "WIT",
}

/** Minimal shape of the debug settings slice this module reads (only the boolean test toggles matter here). */
interface DebugLike {
    [key: string]: unknown
}

// Bind the scalar fields to the canonical Settings interface so a rename there surfaces here; `plans` stays a loose read since only `enabled` is used.
type SkillsLike = Partial<Pick<Settings["skills"], "enableSkillPointCheck" | "skillPointCheck">> & {
    /** Per-plan config keyed by plan id; only the `enabled` flag is read here. */
    plans?: Record<string, { enabled?: boolean } | undefined>
}

// Minimal shape of the racing slice the hero's Style row reads. Bound to the canonical Settings interface so a rename there surfaces here.
type RacingLike = Partial<Pick<Settings["racing"], "enablePerDistanceStrategy" | "originalRaceStrategy">>

/** The armed debug test's readable name plus the search id of its row, used to deep-link and highlight it from the hero's Test chip. */
export interface ActiveDebugTest {
    /** Readable name derived from the setting key (e.g. "Rainbow Detection"). */
    name: string
    /** Search id of the test's row for a deep-link highlight, or null if the key is not in `DEBUG_TESTS`. */
    searchId: string | null
}

/** Enabled skill plans plus the skill-point threshold, ready for the hero's Plans row. */
export interface ActiveSkillPlans {
    /** Display titles of the enabled plans, in registry order. */
    names: string[]
    /** The skill-point threshold when the Skill Point Check plan is on, else null. */
    spThreshold: number | null
}

/**
 * Find the debug test currently armed and return its readable name plus the search id of its row. The 11 test toggles are mutually exclusive, so at most one is on.
 * The name is derived from the key so newly added tests still get a label; the search id is looked up in `DEBUG_TESTS` so the Test chip can deep-link and highlight the row.
 * @param debug The debug settings slice.
 * @returns The active test's name and row search id, or null when no test is armed.
 */
export function findActiveDebugTest(debug: DebugLike): ActiveDebugTest | null {
    const activeKey = Object.keys(debug).find((key) => /^debugMode_start.+Test$/.test(key) && debug[key] === true)
    if (!activeKey) return null
    return { name: prettifyDebugTestKey(activeKey), searchId: DEBUG_TESTS.find((test) => test.key === activeKey)?.searchId ?? null }
}

/**
 * Turn a debug-test setting key into a readable name: strip the `debugMode_start` prefix and `Test` suffix, then split camelCase.
 * @param key The debug-test setting key (e.g. "debugMode_startRainbowDetectionTest").
 * @returns The readable name (e.g. "Rainbow Detection").
 */
function prettifyDebugTestKey(key: string): string {
    return key
        .replace(/^debugMode_start/, "")
        .replace(/Test$/, "")
        .replace(/([a-z])([A-Z])/g, "$1 $2")
        .trim()
}

/**
 * Collect the enabled skill plans and the skill-point threshold for the hero's Plans row. The Skill Point Check plan is gated by `enableSkillPointCheck`;
 * the other plans by their own `plans[key].enabled` flag. A plan missing from the live slice is treated as disabled (its default).
 * @param skills The skills settings slice.
 * @returns The enabled plan titles and the threshold (null when Skill Point Check is off).
 */
export function activeSkillPlans(skills: SkillsLike): ActiveSkillPlans {
    const names: string[] = []
    const skillPointCheckOn = skills.enableSkillPointCheck === true
    if (skillPointCheckOn) names.push(skillPlanSettingsPages.skillPointCheck.title)
    if (skills.plans?.preFinals?.enabled) names.push(skillPlanSettingsPages.preFinals.title)
    if (skills.plans?.careerComplete?.enabled) names.push(skillPlanSettingsPages.careerComplete.title)
    return { names, spThreshold: skillPointCheckOn ? (skills.skillPointCheck ?? null) : null }
}

/**
 * Map an ordered stat priority list to short uppercase abbreviations (Speed -> SPD). Unknown entries are dropped.
 * @param priority The ordered stat priority names.
 * @returns The abbreviations in the same order.
 */
export function abbreviateStatPriority(priority: string[]): string[] {
    return priority.map((stat) => STAT_ABBREVIATIONS[stat]).filter((abbr): abbr is string => abbr !== undefined)
}

/**
 * Resolve the race strategy label for the hero's Style row. In per-distance mode the strategies vary by distance, so a single "Per-distance" summary is shown; otherwise
 * the Original strategy (used for Year 2 and beyond) is shown, falling back to "Default" when unset.
 * @param racing The racing settings slice.
 * @returns The label for the Style row's pill.
 */
export function raceStrategyLabel(racing: RacingLike): string {
    if (racing.enablePerDistanceStrategy) return "Per-distance"
    return racing.originalRaceStrategy ?? "Default"
}

/**
 * Resolve the search id of the RacingSettings row the hero's Style chip should deep-link to and highlight. It mirrors `raceStrategyLabel`: in per-distance mode the
 * chip points at the per-distance toggle, otherwise at the Original strategy row. The ids match the `SearchableItem` ids in RacingSettings and their `searchConfig` entries.
 * @param racing The racing settings slice.
 * @returns The target row's search id.
 */
export function raceStrategyTargetId(racing: RacingLike): string {
    return racing.enablePerDistanceStrategy ? "enable-per-distance-strategy" : "original-race-strategy"
}
