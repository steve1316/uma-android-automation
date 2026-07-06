import { DATING_SCHEDULE_CUSTOM } from "../../datingSchedule"
import type { ScheduleEvent, ScheduleSource } from "../types"

/**
 * Recreation ("Support Card Dating") outings. Each pinned turn is an explicit action owner marked with a calendar glyph. Claims/releases mirror the Settings-page
 * handlers verbatim (sort turns, flip the preset to custom on any manual edit, clear a paired Pure Passion role on the same turn) so persisted state stays identical.
 */
export const recreationSource: ScheduleSource = {
    id: "recreation",
    title: "Recreation dates",

    isEnabled: (ctx) => ctx.general.enableDatingSchedule,

    getEvents: (ctx): ScheduleEvent[] =>
        ctx.general.recreationTurns.map((turn) => ({
            sourceId: "recreation",
            turn,
            ownership: "explicit",
            marker: "📅",
            label: "Recreation date",
            movable: true,
        })),

    claimTurn: (turn, _ctx, mut) => {
        mut.updateGeneral((prev) => ({
            ...prev,
            datingSchedulePreset: DATING_SCHEDULE_CUSTOM,
            recreationTurns: prev.recreationTurns.includes(turn) ? prev.recreationTurns : [...prev.recreationTurns, turn].sort((a, b) => a - b),
            purePassionTurn: prev.purePassionTurn === turn ? -1 : prev.purePassionTurn,
        }))
    },

    releaseTurn: (turn, _ctx, mut) => {
        mut.updateGeneral((prev) => ({
            ...prev,
            datingSchedulePreset: DATING_SCHEDULE_CUSTOM,
            recreationTurns: prev.recreationTurns.filter((t) => t !== turn),
            purePassionTurn: prev.purePassionTurn === turn ? -1 : prev.purePassionTurn,
        }))
    },
}
