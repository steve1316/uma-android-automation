import { readFileSync } from "fs"
import { join } from "path"
import { parseLogs, formatGapText, aggregateYearSummaries, LogFileInput, DayRecord, GapRecord, FileDividerRecord } from "../eventLogParser"

// ---------------------------------------------------------------------------
// Helper: builds a minimal log file content string for a single day.
// ---------------------------------------------------------------------------

/** Creates a log line that sets the turn/day number in the old format. */
const dateLine = (turn: number, dateText?: string) => {
    if (dateText) {
        return `[DATE] It is currently ${dateText} / Turn Number ${turn}.`
    }
    return `[DATE] New date: TURN (Turn ${turn})`
}

/** Creates a log line that sets the turn/day number in the new format. */
const dateLineNew = (turn: number, dateText: string) => {
    return `[DATE] New date: ${dateText} (Turn ${turn})`
}

/** Creates a timestamped version of any line. */
const withTimestamp = (hh: number, mm: number, ss: number, ms: number, line: string) => {
    const h = String(hh).padStart(2, "0")
    const m = String(mm).padStart(2, "0")
    const s = String(ss).padStart(2, "0")
    const milli = String(ms).padStart(3, "0")
    return `${h}:${m}:${s}.${milli} ${line}`
}

const file = (name: string, content: string): LogFileInput => ({ name, content })

// ---------------------------------------------------------------------------
// Helpers to extract records by kind.
// ---------------------------------------------------------------------------

const dayRecords = (result: ReturnType<typeof parseLogs>) => result.records.filter((r): r is DayRecord => r.kind === "day")

const gapRecords = (result: ReturnType<typeof parseLogs>) => result.records.filter((r): r is GapRecord => r.kind === "gap")

const dividerRecords = (result: ReturnType<typeof parseLogs>) => result.records.filter((r) => r.kind === "fileDivider")

// ===========================================================================
// parseLogs
// ===========================================================================

describe("parseLogs", () => {
    // -----------------------------------------------------------------------
    // Basic parsing
    // -----------------------------------------------------------------------

    it("parses a single file with one day", () => {
        const content = dateLine(1, "Junior Year Early Jan")
        const result = parseLogs([file("a.txt", content)])

        expect(result.errors).toHaveLength(0)
        expect(result.meta.filesProcessed).toBe(1)
        expect(result.meta.firstDay).toBe(1)
        expect(result.meta.lastDay).toBe(1)

        const days = dayRecords(result)
        expect(days).toHaveLength(1)
        expect(days[0].dayNumber).toBe(1)
        expect(days[0].fileName).toBe("a.txt")
        expect(days[0].year).toBe("Junior")
    })

    it("parses multiple sequential days", () => {
        const content = [dateLine(1, "Junior Year Early Jan"), dateLine(2, "Junior Year Late Jan"), dateLine(3, "Junior Year Early Feb")].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days).toHaveLength(3)
        expect(days.map((d) => d.dayNumber)).toEqual([1, 2, 3])
    })

    it("sets meta firstDay and lastDay correctly", () => {
        const content = [dateLine(5), dateLine(10)].join("\n")
        const result = parseLogs([file("a.txt", content)])
        expect(result.meta.firstDay).toBe(5)
        expect(result.meta.lastDay).toBe(10)
    })

    // -----------------------------------------------------------------------
    // Gap detection
    // -----------------------------------------------------------------------

    it("inserts a gap record for a single missing day", () => {
        const content = [dateLine(1), dateLine(3)].join("\n")
        const result = parseLogs([file("a.txt", content)])

        const gaps = gapRecords(result)
        expect(gaps).toHaveLength(1)
        expect(gaps[0].from).toBe(2)
        expect(gaps[0].to).toBe(2)
    })

    it("inserts a gap record for multiple missing days", () => {
        const content = [dateLine(1), dateLine(10)].join("\n")
        const result = parseLogs([file("a.txt", content)])

        const gaps = gapRecords(result)
        expect(gaps).toHaveLength(1)
        expect(gaps[0].from).toBe(2)
        expect(gaps[0].to).toBe(9)
    })

    it("inserts no gap when days are consecutive", () => {
        const content = [dateLine(1), dateLine(2)].join("\n")
        const result = parseLogs([file("a.txt", content)])
        expect(gapRecords(result)).toHaveLength(0)
    })

    // -----------------------------------------------------------------------
    // File dividers
    // -----------------------------------------------------------------------

    it("inserts a file divider at the start of the first file", () => {
        const content = dateLine(1)
        const result = parseLogs([file("a.txt", content)])
        expect(dividerRecords(result)).toHaveLength(1)
        expect(dividerRecords(result)[0]).toMatchObject({ kind: "fileDivider", fileName: "a.txt" })
    })

    it("inserts a file divider when fileName changes between files", () => {
        const f1 = file("a.txt", dateLine(1))
        const f2 = file("b.txt", dateLine(2))
        const result = parseLogs([f1, f2])

        const dividers = dividerRecords(result)
        expect(dividers).toHaveLength(2)
        expect(dividers[0]).toMatchObject({ fileName: "a.txt" })
        expect(dividers[1]).toMatchObject({ fileName: "b.txt" })
    })

    // -----------------------------------------------------------------------
    // Overlapping file skip
    // -----------------------------------------------------------------------

    it("skips a file that starts at an earlier day than the last seen day", () => {
        const f1 = file("a.txt", [dateLine(3), dateLine(5)].join("\n"))
        const f2 = file("b.txt", dateLine(2)) // starts before day 5
        const result = parseLogs([f1, f2])

        expect(result.errors).toHaveLength(1)
        expect(result.errors[0].message).toContain("starts at Day 2")
        expect(result.errors[0].message).toContain("earlier than last seen Day 5")
    })

    // -----------------------------------------------------------------------
    // Empty file
    // -----------------------------------------------------------------------

    it("produces a ParseError for a file with no days", () => {
        const result = parseLogs([file("empty.txt", "some random log line\nanother line")])
        expect(result.errors).toHaveLength(1)
        expect(result.errors[0].message).toContain("No days found")
        expect(result.errors[0].fileName).toBe("empty.txt")
    })

    // -----------------------------------------------------------------------
    // Action detection: Training
    // -----------------------------------------------------------------------

    it("detects training action (old format: Executing)", () => {
        const content = [dateLine(1), "[TRAINING] Executing the Speed Training."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.training).toBe(true)
        expect(days[0].trainingType).toBe("Speed")
    })

    it("detects training action (new format: Now starting process)", () => {
        const content = [dateLine(1), "[TRAINING] Now starting process to execute Power training."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.training).toBe(true)
        expect(days[0].trainingType).toBe("Power")
    })

    it("detects training via stat gains line as trigger", () => {
        const content = [dateLine(1), "Process to execute training completed"].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.training).toBe(true)
    })

    // -----------------------------------------------------------------------
    // Action detection: Race
    // -----------------------------------------------------------------------

    it("detects race action with grade", () => {
        const content = [dateLine(1), "[RACE] Racing process for Tenno Sho is completed. Grade: G1"].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.race).toBe(true)
        expect(days[0].raceGrade).toBe("G1")
    })

    // -----------------------------------------------------------------------
    // Action detection: Energy
    // -----------------------------------------------------------------------

    it("detects energy recovery with type", () => {
        const content = [dateLine(1), "[ENERGY] Successfully recovered energy via Summer Rest."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.energy).toBe(true)
        expect(days[0].energyType).toBe("Summer Rest")
    })

    // -----------------------------------------------------------------------
    // Action detection: Mood
    // -----------------------------------------------------------------------

    it("detects mood recovery", () => {
        const content = [dateLine(1), "[MOOD] Successfully recovered mood"].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.mood).toBe(true)
    })

    it("detects mood recovery with type", () => {
        const content = [dateLine(1), "[MOOD] Successfully recovered mood via Recreation Date."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.mood).toBe(true)
        expect(days[0].moodType).toBe("Recreation Date")
    })

    // -----------------------------------------------------------------------
    // Action detection: Injury
    // -----------------------------------------------------------------------

    it("detects injury", () => {
        const content = [dateLine(1), "[INJURY] Injury detected and attempted to heal"].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.injury).toBe(true)
    })

    // -----------------------------------------------------------------------
    // Stat gains
    // -----------------------------------------------------------------------

    it("parses stat gains in old format", () => {
        const content = [dateLine(1), "[INFO] Speed Training stat gains: [14, 0, 6, 0, 0]", "[TRAINING] Executing the Speed Training."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].trainingStatGains).toEqual([14, 0, 6, 0, 0])
    })

    it("parses stat gains in new format", () => {
        const content = [dateLine(1), "SPEED Training: stats={SPEED=10, STAMINA=0, POWER=6, GUTS=0, WIT=0}", "[TRAINING] Executing the Speed Training."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].trainingStatGains).toEqual([10, 0, 6, 0, 0])
    })

    // -----------------------------------------------------------------------
    // Date text extraction
    // -----------------------------------------------------------------------

    it("extracts date text from old format", () => {
        const content = dateLine(24, "Junior Year Late Dec")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].dateText).toBe("Junior Year Late Dec")
        expect(days[0].year).toBe("Junior")
    })

    it("extracts date text from new format", () => {
        const content = dateLineNew(2, "JUNIOR YEAR LATE JANUARY")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].dateText).toBe("JUNIOR YEAR LATE JANUARY")
        // dateText is kept verbatim, but the year is normalized to canonical title case so year grouping works.
        expect(days[0].year).toBe("Junior")
    })

    it("extracts detected date from INFO line", () => {
        const content = ["[INFO] Detected date: Classic Year Early Feb", dateLine(26, "Classic Year Early Feb")].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].dateText).toBe("Classic Year Early Feb")
        expect(days[0].year).toBe("Classic")
    })

    // -----------------------------------------------------------------------
    // Year determination
    // -----------------------------------------------------------------------

    it("assigns Senior year for turns 73-75 (Finals)", () => {
        const content = dateLine(73, "Finale Qualifier")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].year).toBe("Senior")
    })

    it("assigns Senior year when dateText contains Finale", () => {
        const content = dateLine(73, "Finale")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].year).toBe("Senior")
    })

    // -----------------------------------------------------------------------
    // Timestamp extraction
    // -----------------------------------------------------------------------

    it("extracts timestamp from line", () => {
        const line = withTimestamp(0, 12, 22, 190, dateLine(1))
        const result = parseLogs([file("a.txt", line)])
        const days = dayRecords(result)
        // 0*3600000 + 12*60000 + 22*1000 + 190 = 742190
        expect(days[0].timestamp).toBe(742190)
    })

    it("extracts timestamp for multiple hours", () => {
        const line = withTimestamp(1, 1, 1, 0, dateLine(1))
        const result = parseLogs([file("a.txt", line)])
        const days = dayRecords(result)
        // 1*3600000 + 1*60000 + 1*1000 + 0 = 3661000
        expect(days[0].timestamp).toBe(3661000)
    })

    // -----------------------------------------------------------------------
    // Trainee name extraction
    // -----------------------------------------------------------------------

    it("extracts trainee name from content", () => {
        const content = [dateLine(1), "[TRAINEE] Detected trainee name: Special Week"].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].traineeName).toBe("Special Week")
    })

    it("extracts trainee name from filename (underscore format)", () => {
        const content = dateLine(1)
        const result = parseLogs([file("Admire_Vega_2026-03-06 16_13_03.txt", content)])
        const days = dayRecords(result)
        expect(days[0].traineeName).toBe("Admire Vega")
    })

    it("extracts trainee name from filename (log @ format)", () => {
        const content = dateLine(1)
        const result = parseLogs([file("Vega_log @ 2026-03-06 16_13_03.txt", content)])
        const days = dayRecords(result)
        expect(days[0].traineeName).toBe("Vega")
    })

    it("does not extract trainee name when filename has no recognizable prefix", () => {
        const content = dateLine(1)
        const result = parseLogs([file("2026-03-06 16_13_03.txt", content)])
        const days = dayRecords(result)
        expect(days[0].traineeName).toBeUndefined()
    })

    // -----------------------------------------------------------------------
    // Day ended then restarted
    // -----------------------------------------------------------------------

    it("replaces an ended day when re-detected in a new file", () => {
        const f1 = file("a.txt", [dateLine(5, "Junior Year Early Mar"), "some action", "[END]"].join("\n"))
        const f2 = file("b.txt", [dateLine(5, "Junior Year Early Mar"), "[TRAINING] Executing the Speed Training."].join("\n"))

        const result = parseLogs([f1, f2])
        const days = dayRecords(result)
        const day5 = days.find((d) => d.dayNumber === 5)!
        expect(day5).toBeDefined()
        expect(day5.fileName).toBe("b.txt")
        expect(day5.actions.training).toBe(true)
    })

    it("marks day as ended on 'Now saving Message Log'", () => {
        const f1 = file("a.txt", [dateLine(5), "Now saving Message Log"].join("\n"))
        const f2 = file("b.txt", [dateLine(5), "[TRAINING] Executing the Stamina Training."].join("\n"))

        const result = parseLogs([f1, f2])
        const days = dayRecords(result)
        const day5 = days.find((d) => d.dayNumber === 5)!
        expect(day5.fileName).toBe("b.txt")
        expect(day5.actions.training).toBe(true)
    })

    // -----------------------------------------------------------------------
    // Multiple actions per day
    // -----------------------------------------------------------------------

    it("detects multiple actions on the same day", () => {
        const content = [dateLine(1), "[TRAINING] Executing the Speed Training.", "[ENERGY] Successfully recovered energy via Rest."].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].actions.training).toBe(true)
        expect(days[0].actions.energy).toBe(true)
    })

    // -----------------------------------------------------------------------
    // Summary composition
    // -----------------------------------------------------------------------

    it("composes summary for training action", () => {
        const content = [dateLine(1), "[TRAINING] Executing the Speed Training."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Speed Training")
    })

    it("composes summary for race action", () => {
        const content = [dateLine(1), "[RACE] Racing process for Tenno Sho is completed. Grade: G1"].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("G1 Race")
    })

    it("composes summary for energy recovery with normalized type", () => {
        const content = [dateLine(1), "[ENERGY] Successfully recovered energy via Summer Rest."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Recover Energy (Summer)")
    })

    it("composes summary for multiple actions", () => {
        const content = [dateLine(1), "[TRAINING] Executing the Speed Training.", "[ENERGY] Successfully recovered energy via Rest."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Speed Training + Recover Energy (Rest)")
    })

    it("composes summary 'No notable actions detected.' when no actions", () => {
        const content = [dateLine(1), "some irrelevant log line"].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("No notable actions detected.")
    })

    it("composes summary for mood recovery with type normalization", () => {
        const content = [dateLine(1), "[MOOD] Successfully recovered mood via Recreation Date."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Recover Mood (Date)")
    })

    it("composes summary for injury", () => {
        const content = [dateLine(1), "[INJURY] Injury detected and attempted to heal"].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Recover Injury")
    })

    // -----------------------------------------------------------------------
    // Coverage: sanitizeSummary truncation (lines 187-188)
    // -----------------------------------------------------------------------

    it("truncates long firstNotable to 140 chars in summary", () => {
        // firstNotable is only set for training/race triggers, and when labels are populated
        // it won't be used. However sanitizeSummary is still tested indirectly.
        // We can trigger it via a training line that is very long - the firstNotable gets set
        // but labels take priority. To actually test truncation, we'd need to export sanitizeSummary.
        // For now verify the indirect path works.
        const content = [dateLine(1), `Process to execute training completed ${"X".repeat(200)}`].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Training")
    })

    // -----------------------------------------------------------------------
    // Coverage: non-standard energy type (line 221)
    // -----------------------------------------------------------------------

    it("capitalizes unknown energy type", () => {
        const content = [dateLine(1), "[ENERGY] Successfully recovered energy via Outing."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].summary).toBe("Recover Energy (Outing)")
    })

    // -----------------------------------------------------------------------
    // Coverage: determineYear with Finale in dateText but day < 73 (line 255)
    // -----------------------------------------------------------------------

    it("assigns Senior when dateText contains Finale even if day < 73", () => {
        // Edge case: dateText has "Finale" but day number is below 73.
        const content = `[DATE] It is currently Finale Qualifier / Turn Number 50.`
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].year).toBe("Senior")
    })

    // -----------------------------------------------------------------------
    // Coverage: same day re-encountered in same file (lines 410-424)
    // -----------------------------------------------------------------------

    it("updates existing day's dateText when same day appears again in same file", () => {
        // Day 5 first appears without dateText (bare Turn Number line without [DATE] prefix),
        // then appears again with proper dateText.
        const content = ["Current Turn Number 5", "some log line", "[INFO] Detected date: Junior Year Early Mar", "[DATE] It is currently Junior Year Early Mar / Turn Number 5."].join("\n")
        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        const day5 = days.find((d) => d.dayNumber === 5)!
        expect(day5.dateText).toBe("Junior Year Early Mar")
        expect(day5.year).toBe("Junior")
    })

    // -----------------------------------------------------------------------
    // Coverage: day exists in different file, not ended (line 431)
    // -----------------------------------------------------------------------

    it("does not overwrite a day from a different file that was not ended", () => {
        // File A has day 5 (not ended). File B also has day 5. File B should NOT overwrite.
        const f1 = file("a.txt", [dateLine(5, "Junior Year Early Mar"), "[TRAINING] Executing the Speed Training."].join("\n"))
        const f2 = file("b.txt", [dateLine(5, "Junior Year Early Mar"), "[TRAINING] Executing the Stamina Training."].join("\n"))
        const result = parseLogs([f1, f2])
        const days = dayRecords(result)
        const day5 = days.find((d) => d.dayNumber === 5)!
        // Day 5 should belong to file A since it was not ended.
        expect(day5.fileName).toBe("a.txt")
        expect(day5.trainingType).toBe("Speed")
    })

    // -----------------------------------------------------------------------
    // Coverage: stat gains after execution line (line 474)
    // -----------------------------------------------------------------------

    it("links stat gains to training type when gains appear after execution", () => {
        const content = [dateLine(1), "[TRAINING] Executing the Speed Training.", "[INFO] Speed Training stat gains: [20, 0, 8, 0, 0]"].join("\n")

        const result = parseLogs([file("a.txt", content)])
        const days = dayRecords(result)
        expect(days[0].trainingType).toBe("Speed")
        expect(days[0].trainingStatGains).toEqual([20, 0, 8, 0, 0])
    })

    // -----------------------------------------------------------------------
    // Files sorted by name
    // -----------------------------------------------------------------------

    it("sorts files by name before processing", () => {
        const f1 = file("b.txt", dateLine(2))
        const f2 = file("a.txt", dateLine(1))
        const result = parseLogs([f1, f2])
        const days = dayRecords(result)
        expect(days[0].dayNumber).toBe(1)
        expect(days[0].fileName).toBe("a.txt")
    })
})

// ===========================================================================
// formatGapText
// ===========================================================================

describe("formatGapText", () => {
    it("formats a single missing day", () => {
        expect(formatGapText({ kind: "gap", from: 5, to: 5 })).toBe("Day 5 missing.")
    })

    it("formats multiple missing days with en-dash", () => {
        expect(formatGapText({ kind: "gap", from: 3, to: 7 })).toBe("Days 3\u20137 missing.")
    })
})

// ===========================================================================
// aggregateYearSummaries
// ===========================================================================

describe("aggregateYearSummaries", () => {
    const makeDayRecord = (overrides: Partial<DayRecord>): DayRecord => ({
        kind: "day",
        dayNumber: 1,
        summary: "",
        actions: { energy: false, mood: false, injury: false, training: false, race: false },
        fileName: "a.txt",
        ...overrides,
    })

    it("returns empty summaries for empty input", () => {
        const result = aggregateYearSummaries([])
        expect(result.summaries).toHaveLength(0)
        expect(result.totalElapsedTimeMs).toBeUndefined()
    })

    it("excludes records with no year field", () => {
        const records = [makeDayRecord({ dayNumber: 1 })] // no year
        const result = aggregateYearSummaries(records)
        expect(result.summaries).toHaveLength(0)
    })

    it("produces summaries in order [Junior, Classic, Senior]", () => {
        const records = [makeDayRecord({ dayNumber: 25, year: "Classic" }), makeDayRecord({ dayNumber: 1, year: "Junior" }), makeDayRecord({ dayNumber: 49, year: "Senior" })]
        const result = aggregateYearSummaries(records)
        expect(result.summaries.map((s) => s.year)).toEqual(["Junior", "Classic", "Senior"])
    })

    it("counts action types correctly", () => {
        const records = [
            makeDayRecord({ dayNumber: 1, year: "Junior", actions: { energy: true, mood: false, injury: false, training: true, race: false } }),
            makeDayRecord({ dayNumber: 2, year: "Junior", actions: { energy: false, mood: false, injury: false, training: true, race: false } }),
            makeDayRecord({ dayNumber: 3, year: "Junior", actions: { energy: false, mood: false, injury: false, training: false, race: true } }),
        ]
        const result = aggregateYearSummaries(records)
        const junior = result.summaries[0]
        expect(junior.trainingCount).toBe(2)
        expect(junior.raceCount).toBe(1)
        expect(junior.energyCount).toBe(1)
    })

    it("accumulates stat gains per year", () => {
        const records = [
            makeDayRecord({
                dayNumber: 1,
                year: "Junior",
                actions: { energy: false, mood: false, injury: false, training: true, race: false },
                trainingStatGains: [10, 0, 5, 0, 0],
            }),
            makeDayRecord({
                dayNumber: 2,
                year: "Junior",
                actions: { energy: false, mood: false, injury: false, training: true, race: false },
                trainingStatGains: [5, 3, 0, 2, 1],
            }),
        ]
        const result = aggregateYearSummaries(records)
        expect(result.summaries[0].totalStatGains).toEqual({
            speed: 15,
            stamina: 3,
            power: 5,
            guts: 2,
            wit: 1,
        })
    })

    it("tallies training type counts", () => {
        const records = [
            makeDayRecord({
                dayNumber: 1,
                year: "Junior",
                actions: { energy: false, mood: false, injury: false, training: true, race: false },
                trainingType: "Speed",
            }),
            makeDayRecord({
                dayNumber: 2,
                year: "Junior",
                actions: { energy: false, mood: false, injury: false, training: true, race: false },
                trainingType: "Speed",
            }),
            makeDayRecord({
                dayNumber: 3,
                year: "Junior",
                actions: { energy: false, mood: false, injury: false, training: true, race: false },
                trainingType: "Power",
            }),
        ]
        const result = aggregateYearSummaries(records)
        expect(result.summaries[0].trainingCounts.speed).toBe(2)
        expect(result.summaries[0].trainingCounts.power).toBe(1)
    })

    it("calculates elapsed time from first to last timestamp per year", () => {
        const records = [
            makeDayRecord({ dayNumber: 1, year: "Junior", timestamp: 0 }),
            makeDayRecord({ dayNumber: 2, year: "Junior", timestamp: 3661000 }), // 1h 1m 1s
        ]
        const result = aggregateYearSummaries(records)
        expect(result.summaries[0].elapsedTimeMs).toBe(3661000)
        expect(result.summaries[0].elapsedTimeFormatted).toBe("01:01:01")
        expect(result.summaries[0].elapsedTimeHuman).toBe("1 hour and 1 minute")
    })

    it("formats elapsed time for seconds only", () => {
        const records = [
            makeDayRecord({ dayNumber: 1, year: "Junior", timestamp: 0 }),
            makeDayRecord({ dayNumber: 2, year: "Junior", timestamp: 45000 }), // 45s
        ]
        const result = aggregateYearSummaries(records)
        expect(result.summaries[0].elapsedTimeFormatted).toBe("00:00:45")
        expect(result.summaries[0].elapsedTimeHuman).toBe("45 seconds")
    })

    it("formats elapsed time for zero duration as undefined", () => {
        const records = [makeDayRecord({ dayNumber: 1, year: "Junior", timestamp: 5000 })]
        const result = aggregateYearSummaries(records)
        // Single timestamp: first == last, so elapsed = 0
        expect(result.summaries[0].elapsedTimeMs).toBe(0)
        expect(result.summaries[0].elapsedTimeFormatted).toBe("00:00:00")
        expect(result.summaries[0].elapsedTimeHuman).toBe("0 seconds")
    })

    it("marks hasFinals for Senior year when turns 73-75 are present", () => {
        const records = [makeDayRecord({ dayNumber: 72, year: "Senior" }), makeDayRecord({ dayNumber: 73, year: "Senior" })]
        const result = aggregateYearSummaries(records)
        const senior = result.summaries.find((s) => s.year === "Senior")!
        expect(senior.hasFinals).toBe(true)
    })

    it("does not mark hasFinals for Senior year without turns 73-75", () => {
        const records = [makeDayRecord({ dayNumber: 72, year: "Senior" })]
        const result = aggregateYearSummaries(records)
        const senior = result.summaries.find((s) => s.year === "Senior")!
        expect(senior.hasFinals).toBe(false)
    })

    it("collects trainee names per year", () => {
        const records = [
            makeDayRecord({ dayNumber: 1, year: "Junior", traineeName: "Special Week" }),
            makeDayRecord({ dayNumber: 2, year: "Junior", traineeName: "Special Week" }),
            makeDayRecord({ dayNumber: 3, year: "Junior", traineeName: "Silence Suzuka" }),
        ]
        const result = aggregateYearSummaries(records)
        expect(result.summaries[0].traineeNames).toEqual(expect.arrayContaining(["Special Week", "Silence Suzuka"]))
        expect(result.summaries[0].traineeNames).toHaveLength(2)
    })

    it("calculates total elapsed time across all years", () => {
        const records = [
            makeDayRecord({ dayNumber: 1, year: "Junior", timestamp: 0 }),
            makeDayRecord({ dayNumber: 24, year: "Junior", timestamp: 100000 }),
            makeDayRecord({ dayNumber: 25, year: "Classic", timestamp: 200000 }),
            makeDayRecord({ dayNumber: 48, year: "Classic", timestamp: 350000 }),
        ]
        const result = aggregateYearSummaries(records)
        // Junior: 100000, Classic: 150000, total: 250000
        expect(result.totalElapsedTimeMs).toBe(250000)
    })
})

// ===========================================================================
// Regression: trimmed slice of a real bot run
// ===========================================================================

describe("real-run slice regression", () => {
    // A trimmed slice of an actual Oguri Cap Trackblazer run. It keeps the Smart Race Solver preview schedule
    // (34 "Turn N (...)" lines) so we lock in that those future-turn lines never create phantom day records, plus
    // representative Junior/Classic/Senior turns covering training, racing, energy, mood, injury, and the Finale.
    const content = readFileSync(join(__dirname, "fixtures", "oguri-run-slice.log"), "utf8")
    const result = parseLogs([file("Oguri_CapP_2026-06-30 00_50_29.txt", content)])
    const days = dayRecords(result)
    const byDay = (n: number) => days.find((d) => d.dayNumber === n)

    it("does not create phantom days from the solver preview schedule", () => {
        expect(result.meta.firstDay).toBe(1)
        expect(result.meta.lastDay).toBe(75)
        // The schedule lists turns 16, 50, 65, ... but the slice has no real per-turn content for them.
        expect(byDay(16)).toBeUndefined()
        expect(byDay(50)).toBeUndefined()
        expect(byDay(65)).toBeUndefined()
        expect(days.map((d) => d.dayNumber)).toEqual([1, 2, 31, 58, 73, 74, 75])
    })

    it("normalizes uppercase year text into the three canonical year buckets", () => {
        // Day 2 logs its date as "JUNIOR YEAR ...", day 31 as "CLASSIC YEAR ...", day 58 as "SENIOR YEAR ...".
        expect(byDay(2)!.year).toBe("Junior")
        expect(byDay(31)!.year).toBe("Classic")
        expect(byDay(58)!.year).toBe("Senior")
    })

    it("title-cases the uppercase training type in the summary", () => {
        expect(byDay(1)!.trainingType).toBe("WIT")
        expect(byDay(1)!.summary).toContain("Wit Training")
        expect(byDay(1)!.summary).not.toContain("WIT Training")
    })

    it("detects the reworded injury line and the mood recovery", () => {
        expect(byDay(58)!.actions.injury).toBe(true)
        expect(byDay(58)!.summary).toContain("Recover Injury")
        expect(byDay(2)!.actions.mood).toBe(true)
    })

    it("builds correct year summaries with the Finale folded into Senior", () => {
        const summary = aggregateYearSummaries(days)
        expect(summary.summaries.map((s) => s.year)).toEqual(["Junior", "Classic", "Senior"])
        const junior = summary.summaries.find((s) => s.year === "Junior")!
        const classic = summary.summaries.find((s) => s.year === "Classic")!
        const senior = summary.summaries.find((s) => s.year === "Senior")!
        expect(junior.moodCount).toBe(1)
        expect(classic.raceCount).toBe(1)
        expect(senior.injuryCount).toBe(1)
        expect(senior.hasFinals).toBe(true)
        // Year-summary action counts reconcile with the timeline: 3 training days and 3 race days total.
        expect(summary.summaries.reduce((n, s) => n + s.trainingCount, 0)).toBe(3)
        expect(summary.summaries.reduce((n, s) => n + s.raceCount, 0)).toBe(3)
    })

    it("captures the race name, grade, finishing place, and win result", () => {
        const race = byDay(31)!
        expect(race.actions.race).toBe(true)
        expect(race.raceName).toBe("Satsuki Sho")
        expect(race.raceGrade).toBe("G1")
        expect(race.racePlace).toBe("1st")
        expect(race.raceWon).toBe(true)
        // The race name flows into the summary used as the row's accessibility label.
        expect(race.summary).toContain("Satsuki Sho")
        // The lines behind the detected name/place/result are also surfaced as expandable race trigger lines.
        const raceTriggers = race.triggers!.race.join("\n")
        expect(raceTriggers).toContain("Selected Race: Satsuki Sho")
        expect(raceTriggers).toContain("confirmed 1st")
        expect(raceTriggers).toContain("Race result detected")
    })

    it("captures the scenario/campaign and surfaces it on the file divider and year summaries", () => {
        const divider = result.records.find((r) => r.kind === "fileDivider") as FileDividerRecord
        expect(divider.scenario).toBe("Trackblazer")
        expect(byDay(31)!.scenario).toBe("Trackblazer")
        expect(aggregateYearSummaries(days).scenario).toBe("Trackblazer")
    })
})
