import { stopDateToTurn, turnToStopDate } from "../dateConversions"
import type { ScheduleEvent, ScheduleSource } from "../types"

/**
 * Stop-at-Date halt markers. Stopping does not consume a turn's action, so these are `annotation` events that coexist with any owner and never participate in
 * one-owner enforcement. Stored as "<Year> <Month> <Phase>" strings (`general.stopAtDates`); claim appends the string for a turn, release filters it out.
 */
export const stopAtDateSource: ScheduleSource = {
    id: "stopAtDate",
    title: "Stop at Date",
    coexists: true,

    isEnabled: (ctx) => ctx.general.enableStopAtDate,

    getEvents: (ctx): ScheduleEvent[] =>
        ctx.general.stopAtDates
            .map((str) => ({ str, turn: stopDateToTurn(str) }))
            .filter((entry): entry is { str: string; turn: number } => entry.turn !== null)
            .map(({ str, turn }) => ({ sourceId: "stopAtDate", turn, ownership: "annotation", marker: "🛑", label: "Stop bot here", detail: str, movable: true })),

    // Enable the feature on the first claim so the write is honored (the bot skips stops when enableStopAtDate is false) and the model surfaces the annotation.
    claimTurn: (turn, _ctx, mut) => {
        const str = turnToStopDate(turn)
        if (str === null) return
        mut.updateGeneral((prev) => ({ ...prev, enableStopAtDate: true, stopAtDates: prev.stopAtDates.includes(str) ? prev.stopAtDates : [...prev.stopAtDates, str] }))
    },

    // Disable the feature again once the last stop is removed so its legend chip and MessageLog banner reflect that no stops remain.
    releaseTurn: (turn, _ctx, mut) => {
        const str = turnToStopDate(turn)
        if (str === null) return
        mut.updateGeneral((prev) => {
            const stopAtDates = prev.stopAtDates.filter((s) => s !== str)
            return { ...prev, stopAtDates, enableStopAtDate: stopAtDates.length > 0 }
        })
    },
}
