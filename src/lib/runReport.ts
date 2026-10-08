/** Shape version of the run-end report JSON written by the automation library. */
const SCHEMA_VERSION = 1

/** Outcomes the library reports. */
const OUTCOMES = ["FINISHED", "STOPPED_BY_BOT", "STOPPED_BY_USER", "CRASHED"] as const

/** How a run ended. */
export type RunOutcome = (typeof OUTCOMES)[number]

/** The exception that crashed a run. */
export interface RunError {
    /** Exception class simple name, such as "NullPointerException". */
    className: string
    /** Exception message, or empty. */
    message: string
    /** App stack frames as "Class.method (File.kt:123)", most relevant first. */
    frames: string[]
}

/** The five trainee stats. */
export interface RunStats {
    /** Speed value. */
    speed: number
    /** Stamina value. */
    stamina: number
    /** Power value. */
    power: number
    /** Guts value. */
    guts: number
    /** Wit value. */
    wit: number
}

/** Uma's end-of-run summary, added to the report by `RunAnalytics`. */
export interface RunSummary {
    /** Trainee name read by OCR, or empty. */
    trainee: string
    /** Scenario name such as "Grand Live". */
    scenario: string
    /** Final stats. */
    stats: RunStats
    /** Stat caps the bars fill toward. */
    statCaps: RunStats
    /** Final fan count. */
    fans: number
    /** Unspent skill points. */
    skillPoints: number
    /** Races won this run. */
    racesWon: number
    /** Races run this run. */
    racesRun: number
}

/** The report of how the last run ended. */
export interface RunReport {
    /** How the run ended. */
    outcome: RunOutcome
    /** Why it ended, as set by the bot. */
    reason: string
    /** Turn reached, or 0 before the first turn. */
    turn: number
    /** Total turns, or 0 when unknown. */
    totalTurns: number
    /** Running time in ms, without paused time. */
    runtimeMs: number
    /** Wall-clock end time in epoch ms. */
    endedAt: number
    /** Saved log file name, or empty. */
    logFile: string
    /** Crash details, or null when the run did not crash. */
    error: RunError | null
    /** Run summary, or null when the run ended before the first turn. */
    summary: RunSummary | null
}

/** Where the current run stands, read while it is still going. */
export interface LiveRun {
    /** Turn reached so far. */
    turn: number
    /** Total turns, or 0 when unknown. */
    totalTurns: number
    /** Running time so far in ms, without paused time. */
    runtimeMs: number
    /** Trainee summary so far. */
    summary: RunSummary
}

/** Color family of the result card. */
export type RunTone = "success" | "error" | "warning" | "neutral"

/** Display strings for the result card. */
export interface RunReportView {
    /** Color family. */
    tone: RunTone
    /** Status pill text. */
    pill: string
    /** Card title. */
    title: string
    /** Line under the title, or empty. */
    sub: string
    /** Header facts (turn, runtime), missing values left out. */
    facts: string[]
}

/** Per-outcome display constants. */
const OUTCOME_VIEW: Record<RunOutcome, { tone: RunTone; pill: string; title: string }> = {
    FINISHED: { tone: "success", pill: "✓ FINISHED", title: "Run finished" },
    CRASHED: { tone: "error", pill: "! CRASHED", title: "Bot crashed" },
    STOPPED_BY_BOT: { tone: "warning", pill: "■ STOPPED", title: "Bot stopped itself" },
    STOPPED_BY_USER: { tone: "neutral", pill: "■ STOPPED", title: "You stopped the bot" },
}

/**
 * Reads a number field, falling back to 0.
 * @param obj The source object.
 * @param key The field name.
 * @returns The number, or 0.
 */
const num = (obj: any, key: string): number => (typeof obj?.[key] === "number" ? obj[key] : 0)

/**
 * Reads a string field, falling back to "".
 * @param obj The source object.
 * @param key The field name.
 * @returns The string, or "".
 */
const str = (obj: any, key: string): string => (typeof obj?.[key] === "string" ? obj[key] : "")

/**
 * Reads a string array field, falling back to [].
 * @param obj The source object.
 * @param key The field name.
 * @returns The strings.
 */
const strs = (obj: any, key: string): string[] => (Array.isArray(obj?.[key]) ? obj[key].filter((it: unknown) => typeof it === "string") : [])

/**
 * Reads the five stats from an object.
 * @param obj The stats object.
 * @returns The stats, 0 for any missing one.
 */
const stats = (obj: any): RunStats => ({ speed: num(obj, "speed"), stamina: num(obj, "stamina"), power: num(obj, "power"), guts: num(obj, "guts"), wit: num(obj, "wit") })

/**
 * Reads a run summary object.
 * @param s The summary object from native code.
 * @returns The summary, or null when it is missing.
 */
const parseSummary = (s: any): RunSummary | null =>
    s && typeof s === "object"
        ? {
              trainee: str(s, "trainee"),
              scenario: str(s, "scenario"),
              stats: stats(s.stats),
              statCaps: stats(s.statCaps),
              fans: num(s, "fans"),
              skillPoints: num(s, "skillPoints"),
              racesWon: num(s, "racesWon"),
              racesRun: num(s, "racesRun"),
          }
        : null

/**
 * Parses the report JSON from the library.
 * @param json The JSON text from `getLastRun()` or the `RunEnded` event.
 * @returns The report, or null when it is missing, unreadable, from another version, or has an unknown outcome.
 */
export function parseRunReport(json: string | null | undefined): RunReport | null {
    if (!json) return null
    let raw: any
    try {
        raw = JSON.parse(json)
    } catch {
        return null
    }
    if (raw?.schemaVersion !== SCHEMA_VERSION || !OUTCOMES.includes(raw.outcome)) return null
    const e = raw.error
    return {
        outcome: raw.outcome,
        reason: str(raw, "reason"),
        turn: num(raw, "turn"),
        totalTurns: num(raw, "totalTurns"),
        runtimeMs: num(raw, "runtimeMs"),
        endedAt: num(raw, "endedAt"),
        logFile: str(raw, "logFile"),
        error: e && typeof e === "object" ? { className: str(e, "className"), message: str(e, "message"), frames: strs(e, "frames") } : null,
        summary: parseSummary(raw.summary),
    }
}

/**
 * Parses the live run snapshot from `getLiveRun()`.
 * @param json The JSON text, or null when no run is going.
 * @returns The live run, or null when it is missing, unreadable, or has no trainee summary yet.
 */
export function parseLiveRun(json: string | null | undefined): LiveRun | null {
    if (!json) return null
    let raw: any
    try {
        raw = JSON.parse(json)
    } catch {
        return null
    }
    const summary = parseSummary(raw?.summary)
    if (!summary) return null
    return { turn: num(raw, "turn"), totalTurns: num(raw, "totalTurns"), runtimeMs: num(raw, "runtimeMs"), summary }
}

/**
 * Formats a duration as h:mm:ss.
 * @param ms The duration in milliseconds.
 * @returns The formatted time, such as "1:48:20".
 */
export function formatRuntime(ms: number): string {
    const total = Math.max(0, Math.floor(ms / 1000))
    const pad = (n: number) => String(n).padStart(2, "0")
    return `${Math.floor(total / 3600)}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}

/**
 * How full a stat bar is.
 * @param value The stat value.
 * @param cap The stat cap.
 * @returns A fraction from 0 to 1, or 0 when the cap is unknown.
 */
export function statFill(value: number, cap: number): number {
    if (cap <= 0) return 0
    return Math.min(1, Math.max(0, value / cap))
}

/**
 * Builds the display strings for the result card.
 * @param report The parsed report.
 * @returns The card's tone, labels, and facts.
 */
export function formatRunReport(report: RunReport): RunReportView {
    const { outcome, turn, totalTurns, runtimeMs, summary, error } = report
    const look = OUTCOME_VIEW[outcome]
    const races = summary && summary.racesRun > 0 ? `${summary.racesWon}/${summary.racesRun} races won` : ""
    const identity = summary ? [summary.trainee, races].filter(Boolean).join(" · ") : ""
    // A manual stop with trainee info reads as a paused career, laid out like a finished one.
    const paused = outcome === "STOPPED_BY_USER" && summary !== null
    let sub = identity
    if (outcome === "CRASHED") sub = error ? (turn > 0 ? `${error.className} on turn ${turn}` : error.className) : report.reason
    else if (outcome === "STOPPED_BY_BOT") sub = report.reason
    else if (outcome === "STOPPED_BY_USER" && !paused) sub = "No career turn was reached"

    const turnFact = turn > 0 ? (totalTurns > 0 ? `Turn ${turn}/${totalTurns}` : `Turn ${turn}`) : ""
    const facts = [turnFact, runtimeMs >= 1000 ? formatRuntime(runtimeMs) : ""].filter(Boolean)

    return {
        ...look,
        // A finished run is titled by its reason ("Career complete"), since debug tests also finish with "Run ended".
        title: outcome === "FINISHED" && report.reason ? report.reason : look.title,
        ...(paused ? { pill: "■ PAUSED", title: "Career paused" } : {}),
        sub,
        facts,
    }
}

/**
 * Builds the ready-to-paste block for Copy error.
 * @param report The parsed report.
 * @param appVersion The app version, so a bug report says which build crashed.
 * @returns The outcome, turn, scenario, log file, exception, and every app frame, one per line.
 */
export function runCopyText(report: RunReport, appVersion: string): string {
    const { outcome, turn, totalTurns, summary, error } = report
    return [
        `Uma Android Automation v${appVersion}`,
        `Outcome: ${outcome} - ${report.reason}`,
        turn > 0 ? `Turn: ${turn}/${totalTurns}` : "",
        summary?.scenario ? `Scenario: ${summary.scenario}` : "",
        report.logFile ? `Log: ${report.logFile}` : "",
        error ? `${error.className}: ${error.message}` : "",
        ...(error ? error.frames.map((f) => `  at ${f}`) : []),
    ]
        .filter(Boolean)
        .join("\n")
}
