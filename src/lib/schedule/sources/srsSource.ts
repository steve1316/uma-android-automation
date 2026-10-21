import { gradeColor, normalizeGrade, TRAIN_LOCK_SENTINEL } from "../../solver/constants"
import type { RaceEntry } from "../../solver/constants"
import type { ScheduleEvent, ScheduleSource, ScheduleSourceContext } from "../types"
import { parseTurnMap } from "../turnMap"

/**
 * Resolve a locked race's grade by name, preferring the solver decision and falling back to the race catalog.
 * @param name The locked race name.
 * @param ctx The source context.
 * @returns The grade string, or undefined when the race can't be found.
 */
function gradeForRaceName(name: string, ctx: ScheduleSourceContext): string | undefined {
    const found = Object.values(ctx.racesByKey).find((race: RaceEntry) => race.name === name)
    return found?.grade
}

/**
 * The Smart Race Solver's plan for the calendar. A user race-lock is an explicit owner (🔒); a `__TRAIN__` lock is a coexisting reservation; an unlocked solver-suggested
 * race is `auto` (reservable, muted). Mandatory and hand-picked turns are skipped here since `mandatorySource` and `pickedRaceSource` own them. Claims/releases write the same `manualLocks` JSON the SRS page uses.
 */
export const srsSource: ScheduleSource = {
    id: "srs",
    title: "Smart Race Solver",

    isEnabled: (ctx) => ctx.racing.enableSmartRaceSolver,

    getEvents: (ctx): ScheduleEvent[] => {
        const locks = parseTurnMap(ctx.racing.smartRaceSolverManualLocks)
        const picked = parseTurnMap(ctx.racing.pickedRaces)
        const decisions = ctx.preview?.decisions ?? {}
        const events: ScheduleEvent[] = []
        const turns = new Set<number>([...Object.keys(decisions), ...Object.keys(locks)].map((t) => parseInt(t, 10)).filter((t) => t >= 1 && t <= 72))
        turns.forEach((turn) => {
            const decision = decisions[String(turn)]
            const lock = locks[String(turn)]
            if (decision?.mandatory) return // owned by mandatorySource
            if (picked[String(turn)]) return // owned by pickedRaceSource
            if (lock === TRAIN_LOCK_SENTINEL) {
                events.push({ sourceId: "srs", turn, ownership: "reservation", marker: "🚆", label: "Reserved (no race)", movable: true, variant: TRAIN_LOCK_SENTINEL })
            } else if (lock) {
                const grade = decision?.grade ?? gradeForRaceName(lock, ctx)
                events.push({
                    sourceId: "srs",
                    turn,
                    ownership: "explicit",
                    marker: "🔒",
                    label: lock,
                    detail: lock,
                    badge: grade ? normalizeGrade(grade) : undefined,
                    color: grade ? gradeColor(grade) : undefined,
                    movable: true,
                    variant: lock,
                })
            } else if (decision?.type === "Race" && decision.name) {
                events.push({
                    sourceId: "srs",
                    turn,
                    ownership: "auto",
                    marker: "",
                    label: decision.name,
                    detail: decision.name,
                    badge: decision.grade ? normalizeGrade(decision.grade) : undefined,
                    color: decision.grade ? gradeColor(decision.grade) : undefined,
                    movable: true,
                    variant: decision.name,
                })
            }
        })
        return events
    },

    claimTurn: (turn, _ctx, mut, variant) => {
        const value = variant ?? TRAIN_LOCK_SENTINEL
        mut.updateRacing((prev) => {
            const locks = parseTurnMap(prev.smartRaceSolverManualLocks)
            locks[String(turn)] = value
            return { ...prev, smartRaceSolverManualLocks: JSON.stringify(locks) }
        })
    },

    releaseTurn: (turn, _ctx, mut) => {
        mut.updateRacing((prev) => {
            const locks = parseTurnMap(prev.smartRaceSolverManualLocks)
            delete locks[String(turn)]
            return { ...prev, smartRaceSolverManualLocks: JSON.stringify(locks) }
        })
    },
}
