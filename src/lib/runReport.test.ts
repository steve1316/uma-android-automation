import { parseRunReport, parseLiveRun, formatRuntime, formatRunReport, runCopyText, statFill, RunReport } from "./runReport"

const base = (over: Partial<RunReport> = {}): RunReport => ({
    outcome: "FINISHED",
    reason: "Career complete",
    turn: 72,
    totalTurns: 72,
    runtimeMs: 6500000,
    endedAt: 1,
    logFile: "log @ 2026-10-07 22_47_12.txt",
    error: null,
    summary: {
        trainee: "Sakura Chiyono O",
        scenario: "Grand Live",
        stats: { speed: 1246, stamina: 521, power: 983, guts: 461, wit: 824 },
        statCaps: { speed: 1700, stamina: 1300, power: 1300, guts: 1500, wit: 1300 },
        fans: 125671,
        skillPoints: 40,
        racesWon: 9,
        racesRun: 11,
    },
    ...over,
})

describe("parseRunReport", () => {
    it("parses a full report", () => {
        const r = parseRunReport(JSON.stringify({ schemaVersion: 1, ...base(), error: { className: "NullPointerException", message: "x", frames: ["a", "b", "c"] } }))
        expect(r?.outcome).toBe("FINISHED")
        expect(r?.error?.frames).toEqual(["a", "b", "c"])
        expect(r?.summary?.stats.speed).toBe(1246)
    })

    it("returns null for empty, corrupt, old, or unknown-outcome input", () => {
        expect(parseRunReport(null)).toBeNull()
        expect(parseRunReport('{"schemaVersion":1,"outc')).toBeNull()
        expect(parseRunReport(JSON.stringify({ schemaVersion: 2, outcome: "FINISHED" }))).toBeNull()
        expect(parseRunReport(JSON.stringify({ schemaVersion: 1, outcome: "EXPLODED" }))).toBeNull()
    })

    it("defaults missing fields", () => {
        const r = parseRunReport(JSON.stringify({ schemaVersion: 1, outcome: "STOPPED_BY_USER" }))
        expect(r).toEqual({ outcome: "STOPPED_BY_USER", reason: "", turn: 0, totalTurns: 0, runtimeMs: 0, endedAt: 0, logFile: "", error: null, summary: null })
    })
})

describe("formatRuntime", () => {
    it("formats h:mm:ss", () => {
        expect(formatRuntime(6500000)).toBe("1:48:20")
        expect(formatRuntime(5000)).toBe("0:00:05")
    })
})

describe("statFill", () => {
    it("clamps to 0..1 and handles a missing cap", () => {
        expect(statFill(650, 1300)).toBe(0.5)
        expect(statFill(2000, 1300)).toBe(1)
        expect(statFill(100, 0)).toBe(0)
    })
})

describe("formatRunReport", () => {
    it("formats a finished run", () => {
        const v = formatRunReport(base())
        expect(v).toMatchObject({ tone: "success", pill: "✓ FINISHED", title: "Career complete", sub: "Sakura Chiyono O · 9/11 races won" })
        expect(v.facts).toEqual(["Turn 72/72", "1:48:20"])
    })

    it("shows a manual stop with trainee info as a paused career", () => {
        const v = formatRunReport(base({ outcome: "STOPPED_BY_USER", reason: "You stopped the bot", turn: 22 }))
        expect(v).toMatchObject({ tone: "neutral", pill: "■ PAUSED", title: "Career paused", sub: "Sakura Chiyono O · 9/11 races won" })
    })

    it("titles a finished run by its reason, so a debug test does not read as a full career", () => {
        expect(formatRunReport(base({ reason: "Run ended" })).title).toBe("Run ended")
        expect(formatRunReport(base({ reason: "" })).title).toBe("Run finished")
    })

    it("formats a crash with the class and turn", () => {
        const v = formatRunReport(
            base({
                outcome: "CRASHED",
                reason: "NullPointerException",
                turn: 41,
                error: { className: "NullPointerException", message: "m", frames: ["Training.analyze (Training.kt:1364)"] },
            })
        )
        expect(v).toMatchObject({ tone: "error", pill: "! CRASHED", title: "Bot crashed", sub: "NullPointerException on turn 41" })
        const copy = runCopyText(
            base({ outcome: "CRASHED", reason: "NullPointerException", turn: 41, error: { className: "NullPointerException", message: "m", frames: ["Training.analyze (Training.kt:1364)"] } }),
            "5.0.0"
        )
        expect(copy).toContain("NullPointerException: m")
        expect(copy).toContain("  at Training.analyze (Training.kt:1364)")
        expect(copy).toContain("Log: log @ 2026-10-07 22_47_12.txt")
        expect(copy).toContain("v5.0.0")
    })

    it("uses the reason as the sub line when the bot stopped itself", () => {
        const v = formatRunReport(base({ outcome: "STOPPED_BY_BOT", reason: "Mandatory race detected. Stopping bot..." }))
        expect(v).toMatchObject({ tone: "warning", pill: "■ STOPPED", title: "Bot stopped itself", sub: "Mandatory race detected. Stopping bot..." })
    })

    it("hides missing facts for a stop before the first turn", () => {
        const v = formatRunReport(base({ outcome: "STOPPED_BY_USER", turn: 0, runtimeMs: 0, summary: null }))
        expect(v).toMatchObject({ tone: "neutral", pill: "■ STOPPED", title: "You stopped the bot", sub: "No career turn was reached" })
        expect(v.facts).toEqual([])
    })

    it("leaves out a runtime under one second", () => {
        const v = formatRunReport(base({ outcome: "CRASHED", turn: 0, runtimeMs: 450, summary: null, error: { className: "E", message: "", frames: [] } }))
        expect(v.facts).toEqual([])
    })

    it("keeps a long error message whole in the copy text", () => {
        const long = "x".repeat(500)
        expect(runCopyText(base({ outcome: "CRASHED", error: { className: "E", message: long, frames: [] } }), "5.0.0")).toContain(long)
    })
})

describe("parseLiveRun", () => {
    it("parses the live snapshot", () => {
        const live = parseLiveRun(JSON.stringify({ turn: 22, totalTurns: 72, runtimeMs: 186000, summary: base().summary }))
        expect(live).toMatchObject({ turn: 22, totalTurns: 72, runtimeMs: 186000 })
        expect(live?.summary.stats.speed).toBe(1246)
    })

    it("returns null without a summary or for bad input", () => {
        expect(parseLiveRun(null)).toBeNull()
        expect(parseLiveRun("{bad")).toBeNull()
        expect(parseLiveRun(JSON.stringify({ turn: 3 }))).toBeNull()
    })
})
