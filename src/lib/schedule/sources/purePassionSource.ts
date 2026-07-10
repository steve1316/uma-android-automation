import { DATING_SCHEDULE_CUSTOM } from "../../datingSchedule"
import type { ScheduleEvent, ScheduleSource } from "../types"

/**
 * The single Pure Passion final outing. At most one turn carries it (`general.purePassionTurn`, -1 = none). Claiming mirrors the Settings-page handler: it moves off any
 * prior turn and removes that turn from `recreationTurns`, flipping the preset to custom. An explicit action owner, distinct glyph from a regular recreation date.
 */
export const purePassionSource: ScheduleSource = {
    id: "purePassion",
    title: "Pure Passion",

    isEnabled: (ctx) => ctx.general.enableDatingSchedule,

    getEvents: (ctx): ScheduleEvent[] =>
        ctx.general.purePassionTurn > 0 ? [{ sourceId: "purePassion", turn: ctx.general.purePassionTurn, ownership: "explicit", marker: "✨", label: "Pure Passion final", movable: true }] : [],

    claimTurn: (turn, _ctx, mut) => {
        mut.updateGeneral((prev) => ({
            ...prev,
            datingSchedulePreset: DATING_SCHEDULE_CUSTOM,
            purePassionTurn: turn,
            recreationTurns: prev.recreationTurns.filter((t) => t !== turn),
        }))
    },

    releaseTurn: (turn, _ctx, mut) => {
        mut.updateGeneral((prev) => ({ ...prev, datingSchedulePreset: DATING_SCHEDULE_CUSTOM, purePassionTurn: prev.purePassionTurn === turn ? -1 : prev.purePassionTurn }))
    },
}
