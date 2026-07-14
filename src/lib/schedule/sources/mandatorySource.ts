import { gradeColor, normalizeGrade } from "../../solver/constants"
import type { ScheduleEvent, ScheduleSource } from "../types"

// Trackblazer has its own race structure, so URA-style objective races do not apply (mirrors MandatoryRaces.kt's EXCLUDED_SCENARIO).
const EXCLUDED_SCENARIO = "Trackblazer"

/**
 * Mandatory career-objective races for the active character (`character_objectives.json`). These are immovable and read-only - they outrank every other source and cannot
 * be claimed away. Derived independently of the SRS preview so they still render when SRS is off. Excluded for Trackblazer.
 */
export const mandatorySource: ScheduleSource = {
    id: "mandatory",
    title: "Mandatory races",

    isEnabled: () => true,

    getEvents: (ctx): ScheduleEvent[] => {
        if (ctx.scenario === EXCLUDED_SCENARIO) return []
        const races = ctx.objectives[ctx.character]?.mandatoryRaces
        if (!races) return []
        return races.map((race) => {
            const option = race.options[0]
            return {
                sourceId: "mandatory",
                turn: race.turn,
                ownership: "mandatory",
                marker: "📌",
                label: option?.raceName ?? "Mandatory race",
                detail: option?.raceName,
                badge: option ? normalizeGrade(option.grade) : undefined,
                color: option ? gradeColor(option.grade) : undefined,
                movable: false,
            }
        })
    },
}
