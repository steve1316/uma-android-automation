import { heroView } from "./heroView"
import { RunReport } from "../../lib/runReport"

const report = (over: Partial<RunReport> = {}): RunReport => ({
    outcome: "FINISHED",
    reason: "Career complete",
    turn: 72,
    totalTurns: 72,
    runtimeMs: 6500000,
    endedAt: 1,
    logFile: "log.txt",
    error: null,
    summary: {
        trainee: "Sakura",
        scenario: "Grand Live",
        stats: { speed: 1, stamina: 1, power: 1, guts: 1, wit: 1 },
        statCaps: { speed: 2, stamina: 2, power: 2, guts: 2, wit: 2 },
        fans: 1,
        skillPoints: 1,
        racesWon: 9,
        racesRun: 11,
    },
    ...over,
})
const crash = { className: "IllegalStateException", message: "m", frames: ["a"] }

describe("heroView without a result", () => {
    it.each([
        [false, "ready", "● READY", "success"],
        [true, "ready", "● OVERLAY ON", "brand"],
        [false, "notReady", "● NOT READY", "warning"],
        [false, "error", "● ERROR", "warning"],
    ] as const)("overlayOn=%s health=%s shows %s", (overlayOn, health, pillLabel, pillTone) => {
        const v = heroView({ overlayOn, health, report: null })
        expect(v).toMatchObject({ pillLabel, pillTone, cardTone: "brand", facts: "", hasResult: false, details: "none", drawerDefaultOpen: false })
        expect(v.buttonMode).toBe(overlayOn ? "openGame" : "start")
    })
})

describe("heroView with a result", () => {
    it("finished shows stats, closed by default", () => {
        const v = heroView({ overlayOn: false, health: "ready", report: report() })
        expect(v).toMatchObject({
            cardTone: "success",
            pillLabel: "✓ FINISHED",
            pillTone: "success",
            facts: "Turn 72/72 · 1:48:20",
            title: "Career complete",
            sub: "Sakura · 9/11 races won",
            hasResult: true,
            buttonMode: "start",
            details: "stats",
            drawerDefaultOpen: false,
        })
    })

    it("crash opens to the error and the result pill wins over OVERLAY ON", () => {
        const v = heroView({ overlayOn: true, health: "ready", report: report({ outcome: "CRASHED", reason: "IllegalStateException", turn: 41, error: crash }) })
        expect(v).toMatchObject({
            cardTone: "error",
            pillLabel: "! CRASHED",
            title: "Bot crashed",
            sub: "IllegalStateException on turn 41",
            buttonMode: "openGame",
            details: "error",
            drawerDefaultOpen: true,
        })
    })

    it("bot stop shows the reason", () => {
        const v = heroView({ overlayOn: true, health: "ready", report: report({ outcome: "STOPPED_BY_BOT", reason: "Mandatory race detected. Stopping bot..." }) })
        expect(v).toMatchObject({ cardTone: "warning", pillLabel: "■ STOPPED", title: "Bot stopped itself", sub: "Mandatory race detected. Stopping bot...", details: "reason" })
    })

    it("manual stop with trainee info is a paused career with stats", () => {
        const v = heroView({ overlayOn: false, health: "ready", report: report({ outcome: "STOPPED_BY_USER", reason: "You stopped the bot", turn: 22 }) })
        expect(v).toMatchObject({ cardTone: "neutral", pillLabel: "■ PAUSED", pillTone: "neutral", title: "Career paused", details: "stats" })
    })

    it("manual stop before turn 1 has no details", () => {
        const v = heroView({ overlayOn: false, health: "ready", report: report({ outcome: "STOPPED_BY_USER", turn: 0, runtimeMs: 400, summary: null }) })
        expect(v).toMatchObject({ pillLabel: "■ STOPPED", title: "You stopped the bot", sub: "No career turn was reached", facts: "", details: "none" })
    })

    it("a finished debug test without a summary has no details", () => {
        const v = heroView({ overlayOn: false, health: "ready", report: report({ reason: "Run ended", summary: null }) })
        expect(v).toMatchObject({ title: "Run ended", details: "none" })
    })

    it("passes a long reason through untouched", () => {
        const long = "x".repeat(400)
        expect(heroView({ overlayOn: false, health: "ready", report: report({ outcome: "STOPPED_BY_BOT", reason: long }) }).sub).toBe(long)
    })
})

describe("heroView with a live run", () => {
    const live = { turn: 22, totalTurns: 72, runtimeMs: 186000, summary: report().summary! }

    it("shows live stats in the drawer while the overlay is on", () => {
        const v = heroView({ overlayOn: true, health: "ready", report: null, live })
        expect(v).toMatchObject({ pillLabel: "● OVERLAY ON", details: "live", hasResult: false, facts: "" })
    })

    it("ignores live stats with the overlay off or once a result is showing", () => {
        expect(heroView({ overlayOn: false, health: "ready", report: null, live }).details).toBe("none")
        expect(heroView({ overlayOn: true, health: "ready", report: report(), live }).details).toBe("stats")
    })
})
