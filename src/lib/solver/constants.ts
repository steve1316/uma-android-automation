/**
 * Shared types and constants for the Smart Race Solver helpers in `src/lib/solver`.
 * Mirrors the shape of the bundled `races.json` / `epithets.json` / `characterPresets.json` data files.
 */

import epithetsData from "../../data/epithets.json"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Types

export interface RaceEntry {
    /** Display name of the race (e.g. "Tokyo Yushun"). Also used as the unique key. */
    name: string
    /** In-game date string the scraper produced (e.g. "Junior Class June, First Half"). */
    date: string
    /** Absolute 1-indexed turn within the 72-turn career when this race runs. */
    turnNumber: number
    /** Race grade tier: "G1" | "G2" | "G3" | "OP" | "PRE_OP" | "MAIDEN" | "DEBUT" | "FINALE" | "EX". */
    grade: string
    /** Surface type: "Turf" or "Dirt". */
    terrain: string
    /** Distance bucket: "Sprint" | "Mile" | "Medium" | "Long". */
    distanceType: string
    /** Race distance in meters (e.g. 1600, 2400). */
    distanceMeters: number
    /** Fans rewarded for winning, used by the Farming Fans flow. */
    fans: number
    /** Race-track venue name (e.g. "Tokyo", "Hanshin"). */
    raceTrack: string
}

export interface EpithetEntry {
    /** Display name and unique key. */
    name: string
    /** Free-text bullets in gametora's visible row order: scenario / character restriction (when present) first, then the win conditions.
     *  Most epithets list NO reward bullet at all - when one exists it is identified by its wording, not its position (see `splitEpithetBullets`). */
    bullet_points: string[]
    /** Scenario gate, e.g. `["Trackblazer"]`. Empty means universal. Derived by the scraper from `<X> scenario only` bullets.
     *  Consumers may also fall back to parsing `bullet_points` directly via `scenariosForEpithet` when this field is absent on legacy snapshots. */
    scenarios?: string[]
    /** Character gate, e.g. `["Yaeno Muteki"]`. Empty means available to every character. Derived from standalone `<name> only` bullets. */
    characters?: string[]
    /** Structured race-condition matchers used by the solver. Optional only for fixtures / test scaffolding. Production data always carries this. */
    matchers?: Array<Record<string, unknown>>
}

export interface CharacterPresetEntry {
    /** Character display name and unique key (e.g. "Special Week"). */
    name: string
    /** Default distance aptitude grades (S..G) seeded when this preset is applied. */
    distanceAptitudes: { Sprint: string; Mile: string; Medium: string; Long: string }
    /** Default surface aptitude grades (S..G) seeded when this preset is applied. */
    surfaceAptitudes: { Turf: string; Dirt: string }
}

export interface AptitudeMap {
    /** Sprint-distance aptitude grade (S..G). */
    Sprint: string
    /** Mile-distance aptitude grade (S..G). */
    Mile: string
    /** Medium-distance aptitude grade (S..G). */
    Medium: string
    /** Long-distance aptitude grade (S..G). */
    Long: string
    /** Turf-surface aptitude grade (S..G). */
    Turf: string
    /** Dirt-surface aptitude grade (S..G). */
    Dirt: string
}

export interface WeightsMap {
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
    /** Score added to any epithet picked as a target, on top of its reward. Most epithets grant no listed reward, so without this a target is
     *  worth 0 and the solver has no reason to pursue it. 0 makes target selection purely informational. */
    targetEpithetBonus: number
    /** Penalty per race when racing 3+ turns in a row. */
    consecutiveRacePenalty: number
    /** Penalty for racing during summer training-camp turns. */
    summerPenalty: number
    /** Percentage uplift applied to base stat / SP reward of every race before scoring. */
    raceBonusPct: number
    /** Cost subtracted from each race's reward, expressed as a percentage of a G2 baseline. */
    raceCostPct: number
    /** Per-fan score contribution applied to a race's reward fans. 0.0 ignores fans entirely (Stat Epitaphs preset default).
     *  1e-3 (Fans + Epitaphs preset) makes a 25k-fan G1 contribute ~25 score points - meaningful but not dominant. */
    fanWeight: number
    /** Minimum aptitude rank (S..G) a race needs in BOTH its distance type and surface to be eligible. */
    aptitudeThreshold: string
    /** When true, OP and Pre-OP races are also considered alongside G1 / G2 / G3. */
    includeOpAndPreOp: boolean
    /** When true, races during the Classic / Senior summer training camps are not blocked. */
    allowSummerRacing: boolean
}

/** Progress against a single matcher or a whole epithet. `current` is capped at `required`. */
export interface MatcherProgress {
    /** Current count of qualifying wins toward the matcher, capped at `required`. */
    current: number
    /** Total qualifying wins needed to satisfy the matcher. */
    required: number
}

/** Aggregate stats shown in the preview summary panel. */
export interface PreviewStats {
    /** Total number of races scheduled across the 72-turn career. */
    races: number
    /** Number of epithets the schedule is projected to complete. */
    epithets: number
    /** Sum of stat rewards from all scheduled races. */
    raceStats: number
    /** Sum of skill-point (SP) rewards from all scheduled races. */
    raceSp: number
    /** Sum of stat rewards from projected epithet completions. */
    epithetStats: number
    /** Number of skill hints earned via hint-reward epithets. */
    hints: number
    /** Sum of reward fans across all scheduled races. */
    fans: number
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Constants

export const APTITUDE_RANKS = ["S", "A", "B", "C", "D", "E", "F", "G"]

export const MONTH_LABELS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

export const YEAR_LABELS: Array<{ name: string; startTurn: number }> = [
    { name: "Junior", startTurn: 1 },
    { name: "Classic", startTurn: 25 },
    { name: "Senior", startTurn: 49 },
]

/** Reference Trackblazer scoring breakdown (matches `solver-browser.js` BASE_REWARD). */
export const BASE_STAT_BY_GRADE: Record<string, number> = { G1: 10, G2: 8, G3: 8, OP: 5, PRE_OP: 5 }
export const BASE_SP_BY_GRADE: Record<string, number> = { G1: 35, G2: 25, G3: 25, OP: 15, PRE_OP: 10 }

export const GRADE_COLORS: Record<string, string> = {
    G1: "#2563eb",
    G2: "#ec4899",
    G3: "#16a34a",
    OP: "#ca8a04",
    PRE_OP: "#a16207",
    MAIDEN: "#6b7280",
    DEBUT: "#6b7280",
    FINALE: "#7c3aed",
    EX: "#7c3aed",
}

/** Display labels for each race grade, keyed by the canonical `RaceGrade` enum name from `Types.kt`. */
export const GRADE_LABELS: Record<string, string> = {
    G1: "G1",
    G2: "G2",
    G3: "G3",
    OP: "OP",
    PRE_OP: "Pre-OP",
    MAIDEN: "Maiden",
    DEBUT: "Debut",
    FINALE: "Finale",
    EX: "EX",
}

/**
 * Folds a race grade onto its canonical `RaceGrade` enum key. Grades reach the UI under two spellings: `races.json` stores "Pre-OP" while the Kotlin solver
 * serialises the enum name "PRE_OP". Normalising both to "PRE_OP" lets the label and color lookups below hit on either.
 * @param grade The raw grade string from either source.
 * @returns The canonical enum key, e.g. "PRE_OP".
 */
export const normalizeGrade = (grade: string): string => {
    const key = grade.replace(/[-\s]/g, "_").toUpperCase()
    return key === "PREOP" ? "PRE_OP" : key
}

/**
 * Resolves a race grade to its human-readable display label.
 * @param grade The raw grade string from either source.
 * @returns The display label, e.g. "Pre-OP", falling back to the raw grade when it is unrecognized.
 */
export const formatGradeLabel = (grade: string): string => GRADE_LABELS[normalizeGrade(grade)] ?? grade

/**
 * Resolves a race grade to its badge color.
 * @param grade The raw grade string from either source.
 * @returns The hex color, or undefined when the grade is unrecognized so the caller can fall back to its own default.
 */
export const gradeColor = (grade: string): string | undefined => GRADE_COLORS[normalizeGrade(grade)]

/** Every epithet from the bundled `epithets.json`, keyed by name. The single cast of that data file - import this rather than re-casting it. */
export const EPITHETS_BY_NAME = epithetsData as unknown as Record<string, EpithetEntry>


export const DEFAULT_APTITUDES: AptitudeMap = { Sprint: "A", Mile: "A", Medium: "A", Long: "A", Turf: "A", Dirt: "A" }

export const DEFAULT_WEIGHTS: WeightsMap = {
    raceValue: 1.0,
    epithetValue: 1.0,
    statWeight: 1.0,
    spWeight: 1.0,
    hintWeight: 8.0,
    targetEpithetBonus: 25.0,
    consecutiveRacePenalty: 3.0,
    summerPenalty: 5.0,
    raceBonusPct: 50.0,
    raceCostPct: 100.0,
    fanWeight: 0.0,
    aptitudeThreshold: "C",
    includeOpAndPreOp: false,
    allowSummerRacing: false,
}

/** Named optimization-mode presets for the Smart Race Solver. Selecting a mode in the UI snaps the
 *  editable weight sliders to the corresponding bundle; the user can still override individual
 *  sliders afterward. The mode itself is not persisted as a separate setting - it is derived from
 *  `weights.fanWeight > 0`, which keeps the stored state and UI in sync. */
export const OPTIMIZE_MODE_PRESETS: Record<"STAT_EPITAPH" | "FANS_EPITAPH", Partial<WeightsMap>> = {
    STAT_EPITAPH: { raceValue: 1.0, epithetValue: 1.0, fanWeight: 0.0 },
    FANS_EPITAPH: { raceValue: 1.0, epithetValue: 1.0, fanWeight: 1.0e-3 },
}

/** Key identifying which optimization-mode preset is active. */
export type OptimizeModeKey = keyof typeof OPTIMIZE_MODE_PRESETS

/** Display labels for each optimization mode (used by the radio toggle on the Schedule Race Solver tab and the MessageLog banner). */
export const OPTIMIZE_MODE_LABELS: Record<OptimizeModeKey, string> = {
    STAT_EPITAPH: "Stat Epitaphs",
    FANS_EPITAPH: "Fans + Epitaphs",
}

/** The sentinel a manual-lock entry takes to lock a turn to Train / no race. The Kotlin parser understands this as `Decision.Train`.
 *  Keep in sync with `TRAIN_LOCK_SENTINEL` in `SmartRaceSolverIntegration.kt`. */
export const TRAIN_LOCK_SENTINEL = "__TRAIN__"

/** Aptitude rank ordering from G to S. Lower index = weaker.
 *  Used for the eligibility check on the TS side so we don't have to round-trip to Kotlin to know which alternative races are valid. */
export const APT_ORDER: Record<string, number> = { G: 0, F: 1, E: 2, D: 3, C: 4, B: 5, A: 6, S: 7 }

export const OP_GRADES = new Set(["OP", "PRE_OP", "Pre-OP", "PreOP"])

/** Mirror of `EpithetFilters.COUNTRY_NAMES` in `Epithet.kt`. Keep these two lists in sync.
 *  Used by the `nameContainsCountry` branch of the `winCount` filter (Globe-Trotter epithet).
 *  Trailing space on `"Japan "` is intentional - prevents false matches on "Japanese ..." races. */
export const COUNTRY_NAMES = ["Saudi Arabia", "Argentina", "American", "New Zealand", "Japan "]

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Calendar helpers

/**
 * In-game date label for a turn-in-year offset (0..23). Floor-divides by 2 to pick the month and uses parity to choose Early / Late.
 * Example: offset 13 returns "Late Jul".
 *
 * @param turnInYear The 0-indexed turn offset within a year (0..23).
 * @returns The "Early <Month>" / "Late <Month>" style date label.
 */
export const turnDateLabel = (turnInYear: number): string => {
    const month = MONTH_LABELS[Math.floor(turnInYear / 2)]
    const half = turnInYear % 2 === 0 ? "Early" : "Late"
    return `${half} ${month}`
}

/**
 * Full "Year Month Phase" label for an absolute 1-indexed career turn (1-72). Junior is turns 1-24, Classic 25-48, Senior 49-72.
 * Example: turn 60 returns "Senior Late Jun".
 *
 * @param turn The absolute 1-indexed career turn number (1-72).
 * @returns The "Junior/Classic/Senior Early/Late <Month>" label.
 */
export const formatCareerTurn = (turn: number): string => {
    const yearName = turn <= 24 ? "Junior" : turn <= 48 ? "Classic" : "Senior"
    return `${yearName} ${turnDateLabel((turn - 1) % 24)}`
}

const RACE_NAME_ABBREVIATIONS: Record<string, string> = {
    "Hanshin Juvenile Fillies": "Hanshin Juv. F.",
    "Mile Championship": "Mile Champ.",
    "Takarazuka Kinen": "Takarazuka K.",
    "Saudi Arabia Royal Cup": "Saudi Arabia P.",
    "Tokyo Sports Hai Niko Sai Sho": "Tokyo Sports",
    "Niigata Junior Stakes": "Niigata Jr. S.",
    "Kokura Junior Stakes": "Kokura Jr. S.",
    "Sprinters Stakes": "Sprinters S.",
    "Asahi Hai Futurity Stakes": "Asahi Hai F. S.",
}

/**
 * Trims a race name for narrow calendar cells: strips the trailing parenthetical date suffix (e.g. "(Junior Class December, Second Half)")
 * and applies the abbreviation table for known over-long names.
 *
 * @param name The race name to shorten.
 * @returns The trimmed and (when applicable) abbreviated race name.
 */
export const shortenRaceName = (name: string): string => {
    const stripped = name.replace(/\s*\(.*\)\s*$/, "").trim()
    return RACE_NAME_ABBREVIATIONS[stripped] ?? stripped
}
