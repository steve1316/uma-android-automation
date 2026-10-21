import { gradeColor, normalizeGrade } from "../../solver/constants"
import type { RaceEntry } from "../../solver/constants"
import type { ScheduleEvent, ScheduleSource, ScheduleSourceContext } from "../types"
import { parseTurnMap } from "../turnMap"

/**
 * Find a picked race in the catalog, preferring the entry that runs on the picked turn since some names repeat across years.
 * @param name The picked race name.
 * @param turn The turn the race was picked for.
 * @param ctx The source context.
 * @returns The matching catalog entry, or undefined when the name is unknown.
 */
function findRace(name: string, turn: number, ctx: ScheduleSourceContext): RaceEntry | undefined {
    const matches = Object.values(ctx.racesByKey).filter((race) => race.name === name)
    return matches.find((race) => race.turnNumber === turn) ?? matches[0]
}

/**
 * Races the user picked by hand from the calendar. Each one is an explicit action owner the bot runs whether or not the Smart Race Solver is on, and regardless of the
 * trainee's aptitudes. Claims and releases write the `pickedRaces` JSON the Kotlin bot reads.
 */
export const pickedRaceSource: ScheduleSource = {
    id: "pickedRace",
    title: "Picked races",

    isEnabled: () => true,

    getEvents: (ctx): ScheduleEvent[] =>
        Object.entries(parseTurnMap(ctx.racing.pickedRaces))
            .map(([turnStr, name]) => ({ turn: parseInt(turnStr, 10), name }))
            .filter(({ turn, name }) => turn >= 1 && turn <= 72 && !!name)
            .map(({ turn, name }) => {
                const grade = findRace(name, turn, ctx)?.grade
                return {
                    sourceId: "pickedRace",
                    turn,
                    ownership: "explicit",
                    marker: "🎯",
                    label: name,
                    detail: name,
                    badge: grade ? normalizeGrade(grade) : undefined,
                    color: grade ? gradeColor(grade) : undefined,
                    movable: true,
                    variant: name,
                }
            }),

    claimTurn: (turn, _ctx, mut, variant) => {
        if (!variant) return
        mut.updateRacing((prev) => {
            const picked = parseTurnMap(prev.pickedRaces)
            picked[String(turn)] = variant
            return { ...prev, pickedRaces: JSON.stringify(picked) }
        })
    },

    releaseTurn: (turn, _ctx, mut) => {
        mut.updateRacing((prev) => {
            const picked = parseTurnMap(prev.pickedRaces)
            delete picked[String(turn)]
            return { ...prev, pickedRaces: JSON.stringify(picked) }
        })
    },
}
