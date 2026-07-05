import type { Settings } from "../../context/BotStateContext"
import { skillPlanSettingsPages } from "../../pages/SkillPlanSettings/config"

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

/** Enabled skill plans plus the skill-point threshold, ready for the hero's Plans row. */
export interface ActiveSkillPlans {
    /** Display titles of the enabled plans, in registry order. */
    names: string[]
    /** The skill-point threshold when the Skill Point Check plan is on, else null. */
    spThreshold: number | null
}

/** The derived values that decide whether the hero glance zone has anything to show. */
export interface HeroGlanceContent {
    /** Whether Debug Mode is on. */
    debugMode: boolean
    /** The armed debug test's display name, or null. */
    activeTest: string | null
    /** Whether the Smart Race Solver is on. */
    srs: boolean
    /** Enabled skill-plan titles. */
    planNames: string[]
    /** Ordered stat priority abbreviations. */
    priority: string[]
}

/**
 * Find the debug test currently armed and return its readable name (e.g. "Rainbow Detection"). The 11 test toggles are mutually exclusive, so at most one is on.
 * The name is derived from the setting key so newly added tests are picked up without a hardcoded list.
 * @param debug The debug settings slice.
 * @returns The active test's display name, or null when no test is armed.
 */
export function findActiveDebugTest(debug: DebugLike): string | null {
    const activeKey = Object.keys(debug).find((key) => /^debugMode_start.+Test$/.test(key) && debug[key] === true)
    return activeKey ? prettifyDebugTestKey(activeKey) : null
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
 * Whether the hero glance zone has anything to render. Single source of truth for the "mount the glance at all" decision so the hairline divider is never left dangling
 * over an empty zone. Mirrors the per-row guards inside `HeroGlance`.
 * @param content The derived glance values.
 * @returns True when at least one chip or row would render.
 */
export function heroGlanceHasContent(content: HeroGlanceContent): boolean {
    return content.debugMode || content.activeTest !== null || content.srs || content.planNames.length > 0 || content.priority.length > 0
}
