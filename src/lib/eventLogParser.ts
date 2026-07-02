export type LogFileInput = {
    /** The name of the file. */
    name: string
    /** The content of the file. */
    content: string
}

export type DayActions = {
    /** Whether energy occurred. */
    energy: boolean
    /** Whether mood occurred. */
    mood: boolean
    /** Whether injury occurred. */
    injury: boolean
    /** Whether training occurred. */
    training: boolean
    /** Whether race occurred. */
    race: boolean
}

export type DayRecord = {
    /** The kind of record. */
    kind: "day"
    /** The day number. */
    dayNumber: number
    /** The date text. */
    dateText?: string
    /** The summary of the day. */
    summary: string
    /** The actions that occurred on the day. */
    actions: DayActions
    /** The name of the file. */
    fileName: string
    /** The triggers that occurred on the day. */
    triggers?: DayTriggers
    /** The year of the day. */
    year?: string
    /** The training type of the day. */
    trainingType?: string
    /** The training stat gains of the day. */
    trainingStatGains?: number[]
    /** The timestamp of the day in milliseconds from file start (HH*3600000 + MM*60000 + SS*1000 + mmm). */
    timestamp?: number
    /** The name of the trainee detected. */
    traineeName?: string
    /** The grade of the race if any. */
    raceGrade?: string
    /** The type of energy recovery if any. */
    energyType?: string
    /** The type of mood recovery if any. */
    moodType?: string
    /** The name of the race run if any (e.g. "Niigata Junior Stakes"). */
    raceName?: string
    /** The finishing place of the race if any (e.g. "1st"). */
    racePlace?: string
    /** Whether the race was won (finished 1st). */
    raceWon?: boolean
    /** The scenario/campaign this run was played on (e.g. "Trackblazer"). */
    scenario?: string
}

export type GapRecord = {
    /** The kind of record. */
    kind: "gap"
    /** The start day number. */
    from: number
    /** The end day number. */
    to: number
}

export type FileDividerRecord = {
    /** The kind of record. */
    kind: "fileDivider"
    /** The name of the file. */
    fileName: string
    /** The name of the trainee detected in this file. */
    traineeName?: string
    /** The scenario/campaign this run was played on (e.g. "Trackblazer"). */
    scenario?: string
}

export type ParseError = {
    /** The message of the error. */
    message: string
    /** The name of the file. */
    fileName: string
}

export type ParseResult = {
    /** The records parsed from the log files. */
    records: Array<DayRecord | GapRecord | FileDividerRecord>
    /** The errors that occurred during parsing. */
    errors: ParseError[]
    /** The metadata of the parsing. */
    meta: {
        firstDay?: number
        lastDay?: number
        filesProcessed: number
    }
}

export type DayTriggers = {
    energy: string[]
    mood: string[]
    injury: string[]
    training: string[]
    race: string[]
}

const ACTION_KEYS = ["training", "race", "energy", "mood", "injury"] as const
type ActionKey = (typeof ACTION_KEYS)[number]

const REGEX = {
    // Matches the turn number from an authoritative per-turn marker only: the parenthesized "(Turn N)" form
    // (e.g. "[DATE] New date: JUNIOR YEAR LATE JANUARY (Turn 2)", "Current Date: ... (Turn 16)") or the legacy
    // "Turn Number N" form (e.g. "[DATE] It is currently Junior Year Late Dec / Turn Number 24."). This deliberately
    // does NOT match bare "Turn N (...)" lines like the Smart Race Solver preview schedule ("  Turn 16 (... G3)"),
    // which reference future turns and would otherwise create phantom day records with the wrong date.
    dateTurn: /(?:\(Turn\s+(\d+)\)|Turn\s+Number\s+(\d+))/i,
    // Example: "[INFO] Detected date: Junior Year Late Dec".
    dateDetectedText: /\[INFO\][^\n]*Detected date:\s*(.+)$/i,
    // Example: "[DATE] It is currently Senior Year Late Dec / Turn Number 24."
    // Example: "[DATE] It is currently Finale Qualifier / Turn Number 73."
    // New Example: "[DATE] New date: JUNIOR YEAR LATE JANUARY (Turn 2)"
    // This captures the date text from the formatted date line (used for Finals and regular dates).
    dateFormattedText: /\[DATE\][^\n]*(?:It is currently|New date:)\s+(.+?)\s+(?:\/ Turn Number|\(Turn)/i,
    // Extract year from date text: "Junior Year", "Classic Year", or "Senior Year".
    yearExtract: /(Junior|Classic|Senior)\s+Year/i,
    // Extract training type: "[TRAINING] Executing the Power Training." or "[TRAINING] Now starting process to execute Power Training."
    trainingExecution: /\[TRAINING\]\s+(?:Executing\s+the|Now\s+starting\s+process\s+to\s+execute)\s+(\w+)\s+Training/i,
    // Extract stat gains: "[INFO] Speed Training stat gains: [14, 0, 6, 0, 0]"
    // New Example: "SPEED Training: stats={SPEED=10, STAMINA=0, POWER=6, GUTS=0, WIT=0}"
    trainingStatGains:
        /(?:\[INFO\]\s+)?(\w+)\s+Training(?:.*?stat\s+gains:\s+\[([\d,\s]+)\]|:\s+stats=\{SPEED=(-?\d+)\*?,\s*STAMINA=(-?\d+)\*?,\s*POWER=(-?\d+)\*?,\s*GUTS=(-?\d+)\*?,\s*WIT=(-?\d+)\*?\})/i,
    // Extract timestamp: "00:12:22.190" or "00:00:14.810"
    timestamp: /^(\d{2}):(\d{2}):(\d{2})\.(\d{3})/,
    // Extract trainee name from content: "[TRAINEE] Detected trainee name: Special Week"
    traineeName: /\[TRAINEE\][^\n]*(?:Detected trainee name|Name):\s*(.+)$/i,
    // Extract the scenario/campaign from the startup banner: "Campaign Selected: Trackblazer".
    scenario: /Campaign Selected:\s*(.+)$/i,
    // Extract the selected race name and grade: 'Selected Race: Niigata Junior Stakes (G3) Rival: true'. The greedy name
    // capture backtracks so the last parenthesized group is the grade (handles inner parens like "Tenno Sho (Spring)").
    raceSelected: /Selected Race:\s+(.+)\s+\(([^()]+)\)\s+Rival:/i,
    // Extract the race name and finishing place from the history line: 'Race "Niigata Junior Stakes" on turn 16 confirmed 1st;'.
    raceResult: /Race\s+"([^"]+)"\s+on\s+turn\s+\d+\s+confirmed\s+(\w+)/i,
    // Extract whether the race was won (finished 1st): "Race result detected - 1st place: true.".
    raceWon: /Race result detected -\s+\d+\w+ place:\s+(true|false)/i,
}

/** Generic matcher that supports substring contains checks only. */
type LineMatcher = {
    /** The substrings to match. */
    substr?: string[]
    /** The substrings to exclude. */
    negativeSubstr?: string[]
    /** The regex to match. */
    regex?: RegExp[]
}

/**
 * Checks if a line matches a matcher.
 * @param line The line to check.
 * @param matcher The matcher to use.
 * @returns True if the line matches the matcher, false otherwise.
 */
function matchesLine(line: string, matcher: LineMatcher): boolean {
    const l = line
    if (matcher.negativeSubstr && matcher.negativeSubstr.some((s) => l.toLowerCase().includes(s.toLowerCase()))) return false

    // Check substrings.
    const substrOk = matcher.substr ? matcher.substr.some((s) => l.toLowerCase().includes(s.toLowerCase())) : false
    if (substrOk) return true

    // Check regex.
    const regexOk = matcher.regex ? matcher.regex.some((r) => r.test(l)) : false
    return regexOk
}

const MATCHERS: Record<ActionKey, LineMatcher> = {
    training: {
        substr: ["Process to execute training completed", " stat gains: [", "[TRAINING] Executing the ", " with a focus on building relationship bars"],
        regex: [/\[TRAINING\] Now starting process to execute (?:\w+\s+)?training/i],
    },
    race: {
        substr: ["Racing process for ", " is completed. Grade: "],
        regex: [/\[RACE\] Racing process for .*? is completed\. Grade: ([\w\-]+)/i],
    },
    energy: {
        substr: ["Successfully recovered energy via "],
        regex: [/\[ENERGY\] Successfully recovered energy via (.*?)(\.|$)/i],
    },
    mood: {
        substr: ["Successfully recovered mood"],
        regex: [/\[MOOD\] Successfully recovered mood(?: via (.*?)(\.|$))?/i],
    },
    injury: {
        // Current wording is "[INJURY] Injury detected. Attempting to heal...". The legacy "attempted to heal" form is kept for archived logs.
        substr: ["Injury detected. Attempting to heal", "Injury detected and attempted to heal"],
        regex: [/\[INJURY\]\s+Injury detected\.?\s+Attempting to heal/i, /\[INJURY\]\s+Injury detected and attempted to heal/i],
    },
}

/**
 * Sanitizes a summary by removing leading and trailing whitespace and truncating it if it is too long.
 * @param text The summary to sanitize.
 * @returns The sanitized summary.
 */
function sanitizeSummary(text?: string): string {
    if (!text) return ""
    const trimmed = text.trim()
    return trimmed.length > 140 ? trimmed.slice(0, 137) + "..." : trimmed
}

/**
 * Converts a token to title case, e.g. the uppercase training types the bot now logs ("WIT" -> "Wit").
 * @param text The token to convert.
 * @returns The token with the first letter uppercased and the rest lowercased.
 */
function toTitleCase(text: string): string {
    if (!text) return text
    return text.charAt(0).toUpperCase() + text.slice(1).toLowerCase()
}

/**
 * Composes a summary for a day based on the actions that occurred.
 * @param day The day data containing actions and granular types.
 * @param firstNotable The first notable action that occurred on the day.
 * @returns The summary for the day.
 */
function composeSummary(
    day: {
        actions: DayActions
        trainingType?: string
        raceGrade?: string
        raceName?: string
        energyType?: string
        moodType?: string
    },
    firstNotable?: string
): string {
    const labels: string[] = []

    if (day.actions.training) {
        labels.push(day.trainingType ? `${toTitleCase(day.trainingType)} Training` : "Training")
    }
    if (day.actions.race) {
        if (day.raceName) labels.push(day.raceGrade ? `${day.raceName} (${day.raceGrade})` : day.raceName)
        else labels.push(day.raceGrade ? `${day.raceGrade} Race` : "Race")
    }
    if (day.actions.energy) {
        // Normalize energy types for clarity.
        let type = day.energyType ? day.energyType.trim().toLowerCase() : ""
        if (type === "summer rest") type = "Summer"
        else if (type === "recreation date") type = "Date"
        else if (type === "rest") type = "Rest"
        else if (type) type = type.charAt(0).toUpperCase() + type.slice(1)

        labels.push(type ? `Recover Energy (${type})` : "Recover Energy")
    }
    if (day.actions.mood) {
        // Normalize mood types for clarity.
        let type = day.moodType ? day.moodType.trim().toLowerCase() : ""
        if (type === "recreation date") type = "Date"
        else if (type) type = type.charAt(0).toUpperCase() + type.slice(1)

        labels.push(type ? `Recover Mood (${type})` : "Recover Mood")
    }
    if (day.actions.injury) labels.push("Recover Injury")

    if (labels.length > 0) {
        return labels.join(" + ")
    }
    return sanitizeSummary(firstNotable) || "No notable actions detected."
}

/**
 * Determines the year for a given day number and date text.
 * Finals (turns 73-75) are assigned to "Senior" year.
 * @param dayNumber The day number.
 * @param dateText The date text.
 * @returns The year for the day or undefined if no year could be determined.
 */
function determineYear(dayNumber: number, dateText?: string): string | undefined {
    // Finals occur at turns 73, 74, and 75, and belong to Senior Year.
    if (dayNumber >= 73 && dayNumber <= 75) {
        return "Senior"
    }
    // Check if date text contains "Finale" (case-insensitive).
    if (dateText && /finale/i.test(dateText)) {
        return "Senior"
    }
    // Fall back to regex extraction from date text.
    if (dateText) {
        const yearMatch = dateText.match(REGEX.yearExtract)
        if (yearMatch) {
            // Normalize to canonical title case ("JUNIOR" -> "Junior") so year grouping in aggregateYearSummaries matches.
            return toTitleCase(yearMatch[1])
        }
    }
    return undefined
}

/**
 * Parses the log files and returns the parsed records.
 * @param files The log files to parse.
 * @returns The parsed records.
 */
export function parseLogs(files: LogFileInput[]): ParseResult {
    const sorted = [...files].sort((a, b) => a.name.localeCompare(b.name))
    const errors: ParseError[] = []
    const dayMap = new Map<
        number,
        {
            dayNumber: number
            dateText?: string
            actions: DayActions
            firstNotable?: string
            fileName: string
            triggers: DayTriggers
            year?: string
            trainingType?: string
            trainingStatGains?: number[]
            ended?: boolean // True if day ended with [END] or "Now saving Message Log".
            timestamp?: number // Timestamp in milliseconds from file start.
            traineeName?: string // The name of the trainee.
            raceGrade?: string // The grade of the race.
            energyType?: string // The type of energy recovery.
            moodType?: string // The type of mood recovery.
            raceName?: string // The name of the race run.
            racePlace?: string // The finishing place of the race.
            raceWon?: boolean // Whether the race was won (1st place).
            scenario?: string // The scenario/campaign for this run.
        }
    >()

    let lastDaySeen: number | undefined
    let firstDaySeen: number | undefined

    for (const file of sorted) {
        const lines = file.content.split(/\r?\n/)
        let currentDay: number | undefined
        let foundAnyDay = false
        let pendingDateText: string | undefined
        let fileTraineeName: string | undefined
        let fileScenario: string | undefined

        // Try to extract trainee name from filename prefix.
        // Format example: "Admire_Vega_2026-03-06 16_13_03.txt".
        // Format example: "Vega_log @ 2026-03-06 16_13_03.txt".
        // We look for anything before the first date-like pattern (YYYY-MM-DD).
        const fileName = file.name
        const dateMatch = fileName.match(/(\d{4}-\d{2}-\d{2})/)
        if (dateMatch) {
            const prefix = fileName.substring(0, dateMatch.index || 0).trim()
            if (prefix) {
                // Remove "log @" or trailing underscores/spaces.
                let namePart = prefix
                    .replace(/_log\s*@\s*$/, "")
                    .replace(/log\s*@\s*$/, "")
                    .replace(/[_\s]+$/, "")
                if (namePart.toLowerCase() !== "log" && namePart.length > 0) {
                    fileTraineeName = namePart.replace(/_/g, " ")
                }
            }
        }

        // First pass: detect if this file starts at a day earlier than lastDaySeen.
        let firstDayInThisFile: number | undefined
        for (const line of lines) {
            const match = line.match(REGEX.dateTurn)
            if (match) {
                firstDayInThisFile = parseInt(match[1], 10)
                break
            }
        }
        if (typeof firstDayInThisFile === "number") {
            if (typeof lastDaySeen === "number" && firstDayInThisFile < lastDaySeen) {
                errors.push({
                    message: `File ${file.name} starts at Day ${firstDayInThisFile} which is earlier than last seen Day ${lastDaySeen}. Skipping file.`,
                    fileName: file.name,
                })
                continue
            }
        }

        // Store stat gains by training type before execution is detected.
        const statGainsByType = new Map<string, number[]>()

        for (const raw of lines) {
            const line = raw

            // Extract timestamp from line for elapsed time calculation.
            const timestampMatch = line.match(REGEX.timestamp)
            const lineTimestamp = timestampMatch
                ? parseInt(timestampMatch[1], 10) * 3600000 + parseInt(timestampMatch[2], 10) * 60000 + parseInt(timestampMatch[3], 10) * 1000 + parseInt(timestampMatch[4], 10)
                : undefined

            const dateDetected = line.match(REGEX.dateDetectedText)
            if (dateDetected) {
                pendingDateText = dateDetected[1].trim()
            }

            // Also extract date text from formatted date line (used for Finals and as fallback).
            // Example: "[DATE] It is currently Senior Year Late Dec / Turn Number 73."
            const dateFormatted = line.match(REGEX.dateFormattedText)
            if (dateFormatted) {
                pendingDateText = dateFormatted[1].trim()
            }

            const match = line.match(REGEX.dateTurn)
            if (match) {
                const detectedDay = parseInt(match[1] ?? match[2], 10)
                foundAnyDay = true
                if (firstDaySeen === undefined) firstDaySeen = detectedDay
                if (lastDaySeen === undefined || detectedDay > lastDaySeen) lastDaySeen = detectedDay

                let existing = dayMap.get(detectedDay)

                // If day exists but ended in a previous file (with [END] or "Now saving Message Log"),
                // replace it with the new occurrence from this file (bot restarted).
                if (existing && existing.ended && existing.fileName !== file.name) {
                    // Remove the old ended day and create a new one for this file.
                    dayMap.delete(detectedDay)
                    existing = undefined
                }

                // Only set currentDay if this day doesn't exist, belongs to this file, or was just replaced.
                if (!existing || existing.fileName === file.name) {
                    currentDay = detectedDay

                    if (!dayMap.has(currentDay)) {
                        const year = determineYear(currentDay, pendingDateText)
                        dayMap.set(currentDay, {
                            dayNumber: currentDay,
                            dateText: pendingDateText,
                            actions: { energy: false, mood: false, injury: false, training: false, race: false },
                            firstNotable: undefined,
                            fileName: file.name,
                            triggers: { energy: [], mood: [], injury: [], training: [], race: [] },
                            year,
                            trainingType: undefined,
                            trainingStatGains: undefined,
                            ended: false,
                            timestamp: lineTimestamp,
                            traineeName: fileTraineeName,
                            scenario: fileScenario,
                            raceGrade: undefined,
                            energyType: undefined,
                            moodType: undefined,
                        })
                    } else {
                        const existingDay = dayMap.get(currentDay)!
                        if (!existingDay.traineeName && fileTraineeName) {
                            existingDay.traineeName = fileTraineeName
                        }
                        if (!existingDay.scenario && fileScenario) {
                            existingDay.scenario = fileScenario
                        }
                        if (!existingDay.dateText && pendingDateText) {
                            existingDay.dateText = pendingDateText
                            // Re-determine year in case dateText provides new information.
                            existingDay.year = determineYear(currentDay, pendingDateText)
                        } else if (!existingDay.year) {
                            // If year is still missing, try to determine it from available info.
                            existingDay.year = determineYear(currentDay, existingDay.dateText || pendingDateText)
                        }
                        // Update timestamp if this one is earlier (first detection in file).
                        if (lineTimestamp && (!existingDay.timestamp || lineTimestamp < existingDay.timestamp)) {
                            existingDay.timestamp = lineTimestamp
                        }
                    }
                    // Clear stat gains map when a new day is detected.
                    statGainsByType.clear()
                } else {
                    // This day already exists and belongs to a different file - don't process it in this file.
                    currentDay = undefined
                }
                continue
            }

            // Mark day as ended if we encounter [END] or "Now saving Message Log".
            if (currentDay !== undefined && (line.includes("[END]") || line.includes("Now saving Message Log"))) {
                const day = dayMap.get(currentDay)!
                if (day) {
                    day.ended = true
                }
                currentDay = undefined
                statGainsByType.clear()
                continue
            }

            // Capture the scenario/campaign once from the startup banner, which is logged before the first day.
            if (!fileScenario) {
                const scenarioMatch = line.match(REGEX.scenario)
                if (scenarioMatch) fileScenario = scenarioMatch[1].trim()
            }

            if (currentDay === undefined) {
                // Skip lines before the first detected day in this file.
                continue
            }

            const day = dayMap.get(currentDay)!

            // Extract training stat gains for any training type (usually logged before execution).
            const statGainsMatch = line.match(REGEX.trainingStatGains)
            if (statGainsMatch) {
                const trainingType = statGainsMatch[1].toLowerCase()
                let gains: number[] = []
                if (statGainsMatch[2]) {
                    // Old format
                    gains = statGainsMatch[2]
                        .split(",")
                        .map((s) => parseInt(s.trim(), 10))
                        .filter((n) => !isNaN(n))
                } else if (statGainsMatch[3] !== undefined) {
                    // New format
                    gains = [parseInt(statGainsMatch[3], 10), parseInt(statGainsMatch[4], 10), parseInt(statGainsMatch[5], 10), parseInt(statGainsMatch[6], 10), parseInt(statGainsMatch[7], 10)]
                }

                if (gains.length === 5) {
                    statGainsByType.set(trainingType, gains)
                    // If execution already happened for this type, update stat gains immediately.
                    if (day.trainingType && day.trainingType.toLowerCase() === trainingType) {
                        day.trainingStatGains = gains
                    }
                }
            }

            // Extract training execution type and match with stored stat gains.
            const trainingExec = line.match(REGEX.trainingExecution)
            if (trainingExec) {
                const executedType = trainingExec[1]
                day.trainingType = executedType
                // Match stat gains if available for this training type (from earlier in the log).
                const storedGains = statGainsByType.get(executedType.toLowerCase())
                if (storedGains) {
                    day.trainingStatGains = storedGains
                }
            }

            // Extract trainee name from content if detected.
            const traineeMatch = line.match(REGEX.traineeName)
            if (traineeMatch) {
                day.traineeName = traineeMatch[1].trim()
            }

            // Capture the race name (and grade), finishing place, and win result when a race is run this turn.
            const raceSelectedMatch = line.match(REGEX.raceSelected)
            if (raceSelectedMatch) {
                day.raceName = raceSelectedMatch[1].trim()
                if (!day.raceGrade) day.raceGrade = raceSelectedMatch[2].trim()
                day.triggers.race.push(line)
            }
            const raceResultMatch = line.match(REGEX.raceResult)
            if (raceResultMatch) {
                day.raceName = raceResultMatch[1].trim()
                day.racePlace = raceResultMatch[2].trim()
                day.triggers.race.push(line)
            }
            const raceWonMatch = line.match(REGEX.raceWon)
            if (raceWonMatch) {
                day.raceWon = raceWonMatch[1].toLowerCase() === "true"
                day.triggers.race.push(line)
            }

            for (const key of ACTION_KEYS) {
                const matcher = MATCHERS[key]
                if (matchesLine(line, matcher)) {
                    day.actions[key] = true
                    day.triggers[key].push(line)

                    // Extract granular details if available via regex.
                    if (matcher.regex) {
                        for (const r of matcher.regex) {
                            const m = line.match(r)
                            if (m && m[1]) {
                                if (key === "race") day.raceGrade = m[1].trim()
                                if (key === "energy") day.energyType = m[1].trim()
                                if (key === "mood") day.moodType = m[1].trim()
                                break
                            }
                        }
                    }

                    if (!day.firstNotable && (key === "training" || key === "race")) {
                        day.firstNotable = line.trim()
                    }
                }
            }
        }

        if (!foundAnyDay) {
            errors.push({
                message: `No days found in ${file.name}.`,
                fileName: file.name,
            })
        }
    }

    const dayNumbers = Array.from(dayMap.keys()).sort((a, b) => a - b)
    const records: Array<DayRecord | GapRecord | FileDividerRecord> = []
    let prevDay: number | undefined
    let prevFileName: string | undefined

    for (const d of dayNumbers) {
        const entry = dayMap.get(d)!

        // Insert gap if needed.
        if (prevDay !== undefined && d > prevDay + 1) {
            records.push({ kind: "gap", from: prevDay + 1, to: d - 1 })
        }

        // Insert file divider if fileName changes (for consecutive days or after gaps).
        // Also insert at the very beginning for the first file.
        if ((prevFileName && entry.fileName !== prevFileName) || prevFileName === undefined) {
            records.push({ kind: "fileDivider", fileName: entry.fileName, traineeName: entry.traineeName, scenario: entry.scenario })
        }

        records.push({
            kind: "day",
            dayNumber: d,
            dateText: entry.dateText,
            summary: composeSummary(entry, entry.firstNotable),
            actions: entry.actions,
            fileName: entry.fileName,
            triggers: entry.triggers,
            year: entry.year,
            trainingType: entry.trainingType,
            trainingStatGains: entry.trainingStatGains,
            timestamp: entry.timestamp,
            traineeName: entry.traineeName,
            raceGrade: entry.raceGrade,
            energyType: entry.energyType,
            moodType: entry.moodType,
            raceName: entry.raceName,
            racePlace: entry.racePlace,
            raceWon: entry.raceWon,
            scenario: entry.scenario,
        })
        prevDay = d
        prevFileName = entry.fileName
    }

    return {
        records,
        errors,
        meta: {
            firstDay: firstDaySeen,
            lastDay: lastDaySeen,
            filesProcessed: sorted.length,
        },
    }
}

/**
 * Formats a gap record into a human-readable string.
 * @param gap The gap record to format.
 * @returns A string representing the gap.
 */
export function formatGapText(gap: GapRecord): string {
    return gap.from === gap.to ? `Day ${gap.from} missing.` : `Days ${gap.from}–${gap.to} missing.`
}

export type YearSummary = {
    /** The year this summary represents. */
    year: string
    /** The number of energy days in this year. */
    energyCount: number
    /** The number of mood days in this year. */
    moodCount: number
    /** The number of injury days in this year. */
    injuryCount: number
    /** The number of race days in this year. */
    raceCount: number
    /** The number of training days in this year. */
    trainingCount: number
    /** The total stat gains for this year. */
    totalStatGains: {
        speed: number
        stamina: number
        power: number
        guts: number
        wit: number
    }
    /** The total stat gains for each training type in this year. */
    trainingCounts: {
        speed: number
        stamina: number
        power: number
        guts: number
        wit: number
    }
    /** The elapsed time in milliseconds for this year. */
    elapsedTimeMs?: number
    /** The elapsed time in "HH:MM:SS" format for this year. */
    elapsedTimeFormatted?: string
    /** The elapsed time in "X hours and Y minutes" format for this year. */
    elapsedTimeHuman?: string
    /** True if this year summary includes Finals days (turns 73-75). */
    hasFinals?: boolean
    /** The names of the trainees detected in this year. */
    traineeNames: string[]
}

export type YearSummariesResult = {
    /** The summaries for each year. */
    summaries: YearSummary[]
    /** The total elapsed time in milliseconds for all years. */
    totalElapsedTimeMs?: number
    /** The total elapsed time in "HH:MM:SS" format for all years. */
    totalElapsedTimeFormatted?: string
    /** The total elapsed time in "X hours and Y minutes" format for all years. */
    totalElapsedTimeHuman?: string
    /** The scenario/campaign this run was played on (e.g. "Trackblazer"). */
    scenario?: string
}

/**
 * Formats elapsed time in milliseconds into "HH:MM:SS" and "X hours and Y minutes" formats.
 * @param ms The elapsed time in milliseconds.
 * @returns An object containing the formatted and human-readable elapsed time.
 */
function formatElapsedTime(ms: number): { formatted: string; human: string } {
    const totalSeconds = Math.floor(ms / 1000)
    const hours = Math.floor(totalSeconds / 3600)
    const minutes = Math.floor((totalSeconds % 3600) / 60)
    const seconds = totalSeconds % 60

    const formatted = `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`

    let human = ""
    if (hours > 0) {
        human += `${hours} ${hours === 1 ? "hour" : "hours"}`
    }
    if (minutes > 0) {
        if (human) human += " and "
        human += `${minutes} ${minutes === 1 ? "minute" : "minutes"}`
    }
    if (!human && seconds > 0) {
        human = `${seconds} ${seconds === 1 ? "second" : "seconds"}`
    }
    if (!human) human = "0 seconds"

    return { formatted, human }
}

/**
 * Aggregates statistics for each year.
 * @param records The records to aggregate.
 * @returns The aggregated year summaries.
 */
export function aggregateYearSummaries(records: DayRecord[]): YearSummariesResult {
    // Filter to only days with valid year field.
    const daysWithYear = records.filter((r) => r.year)

    // Group by year.
    const yearMap = new Map<string, DayRecord[]>()
    for (const day of daysWithYear) {
        const year = day.year!
        if (!yearMap.has(year)) {
            yearMap.set(year, [])
        }
        yearMap.get(year)!.push(day)
    }

    // Aggregate statistics for each year.
    const summaries: YearSummary[] = []
    const yearOrder = ["Junior", "Classic", "Senior"]
    let totalElapsedTimeMs = 0
    // The scenario is run-wide. Capture it from the first day that carries one.
    let scenario: string | undefined

    for (const year of yearOrder) {
        const days = yearMap.get(year)
        if (!days || days.length === 0) continue

        let energyCount = 0
        let moodCount = 0
        let injuryCount = 0
        let raceCount = 0
        let trainingCount = 0
        const statTotals = { speed: 0, stamina: 0, power: 0, guts: 0, wit: 0 }
        const trainingCounts = { speed: 0, stamina: 0, power: 0, guts: 0, wit: 0 }
        let yearFirstTimestamp: number | undefined
        let yearLastTimestamp: number | undefined
        let hasFinals = false
        const traineeNamesSet = new Set<string>()

        for (const day of days) {
            // Collect trainee name if available.
            if (day.traineeName) {
                traineeNamesSet.add(day.traineeName)
            }
            if (!scenario && day.scenario) {
                scenario = day.scenario
            }
            // Check if this day is Finals (turns 73-75).
            if (day.dayNumber >= 73 && day.dayNumber <= 75) {
                hasFinals = true
            }
            // Track timestamps for elapsed time calculation.
            if (day.timestamp !== undefined) {
                if (yearFirstTimestamp === undefined || day.timestamp < yearFirstTimestamp) {
                    yearFirstTimestamp = day.timestamp
                }
                if (yearLastTimestamp === undefined || day.timestamp > yearLastTimestamp) {
                    yearLastTimestamp = day.timestamp
                }
            }

            if (day.actions.energy) energyCount++
            if (day.actions.mood) moodCount++
            if (day.actions.injury) injuryCount++
            if (day.actions.race) raceCount++
            if (day.actions.training) {
                trainingCount++
                // Sum stat gains if available.
                if (day.trainingStatGains && day.trainingStatGains.length === 5) {
                    statTotals.speed += day.trainingStatGains[0]
                    statTotals.stamina += day.trainingStatGains[1]
                    statTotals.power += day.trainingStatGains[2]
                    statTotals.guts += day.trainingStatGains[3]
                    statTotals.wit += day.trainingStatGains[4]
                }
                // Count training type executions.
                if (day.trainingType) {
                    const type = day.trainingType.toLowerCase() as keyof typeof trainingCounts
                    if (type in trainingCounts) {
                        trainingCounts[type]++
                    }
                }
            }
        }

        // Calculate elapsed time for this year.
        let elapsedTimeMs: number | undefined
        let elapsedTimeFormatted: string | undefined
        let elapsedTimeHuman: string | undefined

        if (yearFirstTimestamp !== undefined && yearLastTimestamp !== undefined) {
            elapsedTimeMs = yearLastTimestamp - yearFirstTimestamp
            const formatted = formatElapsedTime(elapsedTimeMs)
            elapsedTimeFormatted = formatted.formatted
            elapsedTimeHuman = formatted.human
            // Sum to total elapsed time.
            totalElapsedTimeMs += elapsedTimeMs
        }

        summaries.push({
            year,
            energyCount,
            moodCount,
            injuryCount,
            raceCount,
            trainingCount,
            totalStatGains: statTotals,
            trainingCounts,
            elapsedTimeMs,
            elapsedTimeFormatted,
            elapsedTimeHuman,
            hasFinals: year === "Senior" ? hasFinals : undefined,
            traineeNames: Array.from(traineeNamesSet),
        })
    }

    // Calculate total elapsed time (sum of all year elapsed times).
    let totalElapsedTimeFormatted: string | undefined
    let totalElapsedTimeHuman: string | undefined

    if (totalElapsedTimeMs > 0) {
        const formatted = formatElapsedTime(totalElapsedTimeMs)
        totalElapsedTimeFormatted = formatted.formatted
        totalElapsedTimeHuman = formatted.human
    }

    return {
        summaries,
        totalElapsedTimeMs: totalElapsedTimeMs > 0 ? totalElapsedTimeMs : undefined,
        totalElapsedTimeFormatted,
        totalElapsedTimeHuman,
        scenario,
    }
}
