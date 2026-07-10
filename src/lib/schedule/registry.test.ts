import type { Settings } from "../../context/BotStateContext"
import type { SchedulePreview } from "../solver/preview"
import { buildScheduleModel, planClaim, SCHEDULE_SOURCES } from "./registry"
import { recreationSource } from "./sources/recreationSource"
import { purePassionSource } from "./sources/purePassionSource"
import { stopAtDateSource } from "./sources/stopAtDateSource"
import { srsSource } from "./sources/srsSource"
import { mandatorySource } from "./sources/mandatorySource"
import type { ScheduleMutators, ScheduleSourceContext } from "./types"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// Fixtures

function makeCtx(overrides: Partial<{ general: Record<string, unknown>; racing: Record<string, unknown> } & Omit<ScheduleSourceContext, "general" | "racing">> = {}): ScheduleSourceContext {
    return {
        general: {
            enableDatingSchedule: true,
            enableStopAtDate: true,
            recreationTurns: [],
            purePassionTurn: -1,
            stopAtDates: [],
            datingSchedulePreset: "custom",
            ...(overrides.general ?? {}),
        } as unknown as Settings["general"],
        racing: {
            enableSmartRaceSolver: true,
            smartRaceSolverManualLocks: "{}",
            ...(overrides.racing ?? {}),
        } as unknown as Settings["racing"],
        preview: overrides.preview ?? null,
        racesByKey: overrides.racesByKey ?? {},
        objectives: overrides.objectives ?? {},
        character: overrides.character ?? "Special Week",
        scenario: overrides.scenario ?? "URA Finale",
    }
}

const preview = (decisions: SchedulePreview["decisions"]): SchedulePreview => ({ decisions, projectedEpithets: [], totalScore: 0 })

/** Capture the functional/patch update a claim/release applies to a `general`/`racing` fixture. */
function captureGeneral(prev: Record<string, unknown>): { mut: ScheduleMutators; result: () => Record<string, unknown> } {
    let out: Record<string, unknown> = prev
    const mut: ScheduleMutators = {
        updateGeneral: (u) => {
            out = typeof u === "function" ? (u(prev as never) as never) : { ...prev, ...u }
        },
        updateRacing: () => {},
    }
    return { mut, result: () => out }
}
function captureRacing(prev: Record<string, unknown>): { mut: ScheduleMutators; result: () => Record<string, unknown> } {
    let out: Record<string, unknown> = prev
    const mut: ScheduleMutators = {
        updateGeneral: () => {},
        updateRacing: (u) => {
            out = typeof u === "function" ? (u(prev as never) as never) : { ...prev, ...u }
        },
    }
    return { mut, result: () => out }
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// Source getEvents

describe("source getEvents", () => {
    it("recreation emits an explicit event per pinned turn", () => {
        const events = recreationSource.getEvents(makeCtx({ general: { recreationTurns: [29, 35] } }))
        expect(events.map((e) => e.turn)).toEqual([29, 35])
        expect(events.every((e) => e.ownership === "explicit" && e.marker === "📅")).toBe(true)
    })

    it("pure passion emits one explicit event only when set", () => {
        expect(purePassionSource.getEvents(makeCtx({ general: { purePassionTurn: -1 } }))).toEqual([])
        const events = purePassionSource.getEvents(makeCtx({ general: { purePassionTurn: 60 } }))
        expect(events).toHaveLength(1)
        expect(events[0]).toMatchObject({ turn: 60, ownership: "explicit", marker: "✨" })
    })

    it("stop-at-date emits coexisting annotation events from strings", () => {
        const events = stopAtDateSource.getEvents(makeCtx({ general: { stopAtDates: ["Senior January Early", "bogus"] } }))
        expect(events).toHaveLength(1)
        expect(events[0]).toMatchObject({ turn: 49, ownership: "annotation", marker: "🛑" })
    })

    it("srs classifies locks, reservations, and auto races and skips mandatory", () => {
        const ctx = makeCtx({
            racing: { smartRaceSolverManualLocks: JSON.stringify({ "20": "Satsuki Sho", "40": "__TRAIN__" }) },
            preview: preview({
                "20": { type: "Race", name: "Satsuki Sho", grade: "G1" },
                "24": { type: "Race", name: "Hopeful Stakes", grade: "G1", mandatory: true },
                "33": { type: "Race", name: "Aoba Sho", grade: "OP" },
            }),
        })
        const byTurn = Object.fromEntries(srsSource.getEvents(ctx).map((e) => [e.turn, e]))
        expect(byTurn[20]).toMatchObject({ ownership: "explicit", marker: "🔒", label: "Satsuki Sho" })
        expect(byTurn[40]).toMatchObject({ ownership: "reservation", variant: "__TRAIN__" })
        expect(byTurn[33]).toMatchObject({ ownership: "auto", label: "Aoba Sho" })
        expect(byTurn[24]).toBeUndefined() // mandatory handled elsewhere
    })

    it("mandatory reads objectives and excludes Trackblazer", () => {
        const objectives = {
            "Special Week": {
                name: "Special Week",
                mandatoryRaces: [{ turn: 24, isChoice: false, options: [{ raceName: "Hopeful Stakes", grade: "G1", surface: "Turf", distanceType: "Medium", fans: 7000 }] }],
            },
        }
        const events = mandatorySource.getEvents(makeCtx({ objectives }))
        expect(events[0]).toMatchObject({ turn: 24, ownership: "mandatory", movable: false, badge: "G1" })
        expect(mandatorySource.getEvents(makeCtx({ objectives, scenario: "Trackblazer" }))).toEqual([])
    })
})

// //////////////////////////////////////////////////////////////////////////////////////////////////
// buildScheduleModel + planClaim

describe("buildScheduleModel", () => {
    it("picks mandatory over explicit and flags conflicts", () => {
        const objectives = {
            "Special Week": {
                name: "Special Week",
                mandatoryRaces: [{ turn: 24, isChoice: false, options: [{ raceName: "Hopeful", grade: "G1", surface: "Turf", distanceType: "Medium", fans: 1 }] }],
            },
        }
        const ctx = makeCtx({
            general: { recreationTurns: [52], purePassionTurn: -1 },
            racing: { smartRaceSolverManualLocks: JSON.stringify({ "52": "Japan Cup" }) },
            objectives,
        })
        const model = buildScheduleModel(SCHEDULE_SOURCES, ctx)
        expect(model.byTurn.get(24)?.owner?.ownership).toBe("mandatory")
        expect(model.byTurn.get(52)?.conflict).toBe(true) // recreation + srs lock
    })

    it("marks a recreation turn with an SRS auto-race as reservable", () => {
        const ctx = makeCtx({ general: { recreationTurns: [35] }, preview: preview({ "35": { type: "Race", name: "Tenno Sho", grade: "G1" } }) })
        const merged = buildScheduleModel(SCHEDULE_SOURCES, ctx).byTurn.get(35)
        expect(merged?.owner?.sourceId).toBe("recreation")
        expect(merged?.autoRace?.label).toBe("Tenno Sho")
        expect(merged?.hasReservableAutoRace).toBe(true)
    })

    it("stop-at-date coexists with a race owner without conflict", () => {
        const ctx = makeCtx({ general: { stopAtDates: ["Classic January Early"] }, racing: { smartRaceSolverManualLocks: JSON.stringify({ "25": "Kyoto Kinen" }) } })
        const merged = buildScheduleModel(SCHEDULE_SOURCES, ctx).byTurn.get(25)
        expect(merged?.owner?.sourceId).toBe("srs")
        expect(merged?.annotations).toHaveLength(1)
        expect(merged?.conflict).toBe(false)
    })
})

describe("planClaim", () => {
    const objectives = {
        "Special Week": { name: "Special Week", mandatoryRaces: [{ turn: 24, isChoice: false, options: [{ raceName: "Hopeful", grade: "G1", surface: "Turf", distanceType: "Medium", fans: 1 }] }] },
    }
    const ctx = makeCtx({
        general: { recreationTurns: [35], stopAtDates: ["Classic January Early"] },
        racing: { smartRaceSolverManualLocks: JSON.stringify({ "27": "Kyoto Kinen" }) },
        objectives,
    })
    const model = buildScheduleModel(SCHEDULE_SOURCES, ctx)

    it("is free on an unowned turn", () => expect(planClaim(10, "recreation", model).kind).toBe("free"))
    it("blocks on a mandatory turn", () => expect(planClaim(24, "recreation", model).kind).toBe("blocked-mandatory"))
    it("conflicts when another source owns the turn", () => expect(planClaim(35, "purePassion", model).kind).toBe("conflict"))
    it("lets stop-at-date coexist with a race owner", () => expect(planClaim(27, "stopAtDate", model).kind).toBe("free"))
    it("conflicts a stop-at-date claim against a recreation date", () => expect(planClaim(35, "stopAtDate", model).kind).toBe("conflict"))
    it("conflicts a recreation claim against an existing stop-at-date", () => expect(planClaim(25, "recreation", model).kind).toBe("conflict"))
})

// //////////////////////////////////////////////////////////////////////////////////////////////////
// claim / release write-through

describe("claim / release", () => {
    it("recreation claim adds a sorted turn and flips the preset to custom", () => {
        const { mut, result } = captureGeneral({ recreationTurns: [43, 29], purePassionTurn: -1, datingSchedulePreset: "siriusSenior" })
        recreationSource.claimTurn!(35, makeCtx(), mut)
        expect(result()).toMatchObject({ recreationTurns: [29, 35, 43], datingSchedulePreset: "custom" })
    })

    it("pure passion claim removes the turn from recreation turns", () => {
        const { mut, result } = captureGeneral({ recreationTurns: [35, 43], purePassionTurn: -1, datingSchedulePreset: "custom" })
        purePassionSource.claimTurn!(35, makeCtx(), mut)
        expect(result()).toMatchObject({ purePassionTurn: 35, recreationTurns: [43] })
    })

    it("srs claim writes a manual lock and release removes it", () => {
        const claimed = captureRacing({ smartRaceSolverManualLocks: "{}" })
        srsSource.claimTurn!(20, makeCtx(), claimed.mut, "Satsuki Sho")
        expect(JSON.parse(claimed.result().smartRaceSolverManualLocks as string)).toEqual({ "20": "Satsuki Sho" })

        const released = captureRacing({ smartRaceSolverManualLocks: JSON.stringify({ "20": "Satsuki Sho", "30": "__TRAIN__" }) })
        srsSource.releaseTurn!(20, makeCtx(), released.mut)
        expect(JSON.parse(released.result().smartRaceSolverManualLocks as string)).toEqual({ "30": "__TRAIN__" })
    })

    it("stop-at-date claim appends the turn's string and enables the feature", () => {
        const { mut, result } = captureGeneral({ enableStopAtDate: false, stopAtDates: [] })
        stopAtDateSource.claimTurn!(49, makeCtx(), mut)
        expect(result().stopAtDates).toEqual(["Senior January Early"])
        expect(result().enableStopAtDate).toBe(true)
    })

    it("stop-at-date release removes the turn's string and disables the feature when none remain", () => {
        const { mut, result } = captureGeneral({ enableStopAtDate: true, stopAtDates: ["Senior January Early"] })
        stopAtDateSource.releaseTurn!(49, makeCtx(), mut)
        expect(result().stopAtDates).toEqual([])
        expect(result().enableStopAtDate).toBe(false)
    })

    it("stop-at-date release keeps the feature enabled when other stops remain", () => {
        const { mut, result } = captureGeneral({ enableStopAtDate: true, stopAtDates: ["Senior January Early", "Senior February Early"] })
        stopAtDateSource.releaseTurn!(49, makeCtx(), mut)
        expect(result().stopAtDates).toEqual(["Senior February Early"])
        expect(result().enableStopAtDate).toBe(true)
    })
})
