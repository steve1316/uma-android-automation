// Bidirectional conversion between a 1-indexed career turn (1-72) and the Stop-at-Date string format the Kotlin bot parses ("<Year> <Month> <Phase>", full month names).
// The turn math matches GameDate.toDay: 24 turns per year (Junior/Classic/Senior), 2 phases per month (Early/Late).

/** Career year names in turn order; index 0 = Junior (turns 1-24), 1 = Classic (25-48), 2 = Senior (49-72). */
export const SCHEDULE_YEARS = ["Junior", "Classic", "Senior"] as const

/** Full month names as the Stop-at-Date string stores them (the format Kotlin's checkStopAtDate parses). */
export const SCHEDULE_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"] as const

/** Phase names; index 0 = Early (first half), 1 = Late (second half). */
export const SCHEDULE_PHASES = ["Early", "Late"] as const

/**
 * Convert a 1-indexed career turn (1-72) into the Stop-at-Date string ("Senior January Early").
 * @param turn The 1-indexed career turn.
 * @returns The "<Year> <Month> <Phase>" string, or null when the turn is outside the 1-72 career range.
 */
export function turnToStopDate(turn: number): string | null {
    if (turn < 1 || turn > 72) return null
    const index = turn - 1
    const year = SCHEDULE_YEARS[Math.floor(index / 24)]
    const within = index % 24
    const month = SCHEDULE_MONTHS[Math.floor(within / 2)]
    const phase = SCHEDULE_PHASES[within % 2]
    return `${year} ${month} ${phase}`
}

/**
 * Parse a Stop-at-Date string ("Senior January Early") back into its 1-indexed career turn. Unrecognized years/months/phases or a wrong token count return null.
 * @param value The "<Year> <Month> <Phase>" string.
 * @returns The 1-indexed career turn (1-72), or null when the string is malformed.
 */
export function stopDateToTurn(value: string): number | null {
    const parts = value.trim().split(/\s+/)
    if (parts.length !== 3) return null
    const yearIndex = SCHEDULE_YEARS.indexOf(parts[0] as (typeof SCHEDULE_YEARS)[number])
    const monthIndex = SCHEDULE_MONTHS.indexOf(parts[1] as (typeof SCHEDULE_MONTHS)[number])
    const phaseIndex = SCHEDULE_PHASES.indexOf(parts[2] as (typeof SCHEDULE_PHASES)[number])
    if (yearIndex < 0 || monthIndex < 0 || phaseIndex < 0) return null
    return yearIndex * 24 + monthIndex * 2 + phaseIndex + 1
}
