// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// EventLog presentational constants
//
// Shared geometry and taxonomy for the timeline cards so the same values are not redeclared across DayRow,
// GapsNotice, and YearSummaryCard.

/** Width of the left rail column, shared by DayRow (the rail) and GapsNotice (the aligned break marker). */
export const RAIL_WIDTH = 34

/** Diameter of the day-number node drawn on the rail. */
export const NODE_SIZE = 26

/** The five trainee stats in the fixed order used by `trainingStatGains` and the `YearSummary` stat fields. */
export const STATS: { abbr: string; key: "speed" | "stamina" | "power" | "guts" | "wit" }[] = [
    { abbr: "SPD", key: "speed" },
    { abbr: "STA", key: "stamina" },
    { abbr: "PWR", key: "power" },
    { abbr: "GUT", key: "guts" },
    { abbr: "WIT", key: "wit" },
]
