package com.steve1316.uma_android_automation.types

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.components.LabelEnergy
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.utils.CustomImageUtils

/** Matches any run of whitespace. OCR can wrap the date onto more than one line, so date strings are tokenized on this instead of on a single space. */
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Represents the Game's date in the training scenario.
 *
 * This class stores the game's date representation (Year, Month, Phase) and the corresponding turn number (Day). It provides functions to convert between these formats and to detect the current date
 * from the screen using OCR.
 *
 * The game is structured into three years: Junior, Classic, and Senior. Each year has 12 months, and each month is split into two phases: Early and Late. Total turns: 3 years * 12 months * 2 phases =
 * 72 turns. The Finale season starts after turn 72.
 */
class GameDate {
    /** The date's year (Junior, Classic, Senior). */
    var year: DateYear = DateYear.JUNIOR

    /** The date's month (January to December). */
    var month: DateMonth = DateMonth.JANUARY

    /** The date's phase (Early, Late). */
    var phase: DatePhase = DatePhase.EARLY

    /** The current turn number (1 to 72, with 73-75 for the Finale). */
    var day: Int = 1

    /** Whether the game is in the Pre-Debut phase (Turns 1-11). */
    var bIsPreDebut: Boolean = false

    /** Whether the game is in the Finale season (Turns 73-75). */
    var bIsFinaleSeason: Boolean = false

    /**
     * Constructor that takes year/month/phase arguments to calculate the day.
     *
     * @param year The date's year.
     * @param month The date's month.
     * @param phase The date's phase.
     */
    constructor(year: DateYear, month: DateMonth, phase: DatePhase) {
        // Calculate the turn number based on the components.
        val day = toDay(year, month, phase)

        this.year = year
        this.month = month
        this.phase = phase
        this.day = day

        // Update season flags. Pre-Debut is the first 11 turns (Junior Year Early Jan to Late June).
        bIsPreDebut = this.day < 12
        // Finale season starts after turn 72 (Senior Year Late Dec).
        bIsFinaleSeason = this.day > 72
    }

    /**
     * Constructor that takes a day argument (turn number) to calculate the year/month/phase.
     *
     * @param day The turn number to convert.
     */
    constructor(day: Int) {
        // Calculate the components based on the turn number.
        val tmpDate = fromDay(day)
        this.year = tmpDate.year
        this.month = tmpDate.month
        this.phase = tmpDate.phase
        this.day = day

        // Update season flags.
        bIsPreDebut = this.day < 12
        bIsFinaleSeason = this.day > 72
    }

    companion object {
        const val TAG: String = "[${MainActivity.loggerTag}]GameDate"

        /** Candidate year names that an OCR'd date string is fuzzy matched against. */
        private val YEAR_NAMES: List<String> = DateYear.entries.map { it.name }

        /** Candidate month short names that an OCR'd date string is fuzzy matched against. */
        private val MONTH_SHORT_NAMES: List<String> = DateMonth.entries.map { it.shortName }

        /** Candidate phase names that an OCR'd date string is fuzzy matched against. */
        private val PHASE_NAMES: List<String> = DatePhase.entries.map { it.name }

        /**
         * Converts a year/month/phase to a day number (turn number).
         *
         * The formula calculates the offset from the start of the Junior year. There are 24 turns (2 per month * 12 months) per year.
         *
         * @param year The date's year.
         * @param month The date's month.
         * @param phase The date's phase.
         * @return The calculated day number (1-indexed).
         */
        fun toDay(year: DateYear, month: DateMonth, phase: DatePhase): Int {
            // formula: (YearIndex * 24) + ((MonthIndex + 1) * 2) + PhaseIndex - 1.
            return ((year.ordinal * (DateMonth.entries.size * 2)) + (((month.ordinal + 1) * 2) + phase.ordinal)) - 1
        }

        /**
         * Converts a day (or turn number) into a [GameDate] object.
         *
         * @param day The day number (turn number) to convert.
         * @return The [GameDate] object generated from the day number.
         */
        fun fromDay(day: Int): GameDate {
            // Clamp minimum day to 1 (Junior Year Early Jan).
            if (day < 1) {
                return GameDate(
                    year = DateYear.JUNIOR,
                    month = DateMonth.JANUARY,
                    phase = DatePhase.EARLY,
                )
            }

            // Values over 72 will break the division-based formula below, so we return a capped Senior Year Late Dec object.
            if (day > 72) {
                val tmpDate = GameDate(year = DateYear.SENIOR, month = DateMonth.DECEMBER, phase = DatePhase.LATE)
                tmpDate.day = day
                return tmpDate
            }

            // Day starts at 1, not 0, so we need to decrement for the following formula to work properly.
            val day = day - 1
            val y = day.floorDiv(24)
            val m = (day % 24).floorDiv(2)
            val p = day % 2

            val year: DateYear = DateYear.fromOrdinal(y)!!
            val month: DateMonth = DateMonth.fromOrdinal(m)!!
            val phase: DatePhase = DatePhase.fromOrdinal(p)!!

            return GameDate(
                year = year,
                month = month,
                phase = phase,
            )
        }

        /**
         * Detects the date on the screen.
         *
         * This is just a simple wrapper around [fromDateString].
         *
         * @param imageUtils A reference to a [CustomImageUtils] instance.
         * @param scenario The scenario name for special handling.
         * @return The detected [GameDate] object, or null if nothing could be detected.
         */
        fun detectDate(imageUtils: CustomImageUtils, scenario: String? = null): GameDate? {
            return fromDateString(imageUtils = imageUtils, scenario = scenario)
        }

        /**
         * Converts a date string recognized via OCR to a [GameDate] object.
         *
         * @param s The date string to convert from. If null, [imageUtils] will detect it.
         * @param turnsLeft The number turns left until the next goal. If null, [imageUtils] will detect it.
         * @param imageUtils The [CustomImageUtils] instance used for OCR detection.
         * @param scenario The current scenario name for special handling.
         * @return The [GameDate] object, or null if conversion failed.
         */
        fun fromDateString(s: String? = null, turnsLeft: Int? = null, imageUtils: CustomImageUtils, scenario: String? = null): GameDate? {
            // Determine the date string from the screen if it wasn't provided.
            val dayString: String = s ?: imageUtils.determineDayString()
            if (dayString == null || dayString == "") {
                MessageLog.d(TAG, "[DEBUG] fromDateString:: Passed string is null or empty.")
                return null
            }

            // Special case for Pre-Debut where year/month aren't explicitly shown.
            if (dayString.lowercase().contains("debut")) {
                val remainingTurns: Int = turnsLeft ?: imageUtils.determineTurnsRemainingBeforeNextGoal()
                if (remainingTurns == null) {
                    MessageLog.e(TAG, "[ERROR] fromDateString:: In Pre-Debut but received turnsLeft=null. Cannot calculate date without turnsLeft.")
                    return null
                }

                if (remainingTurns <= 0) {
                    // Turn 12 is the Debut race (Junior Year Late June).
                    return GameDate(DateYear.JUNIOR, DateMonth.JUNE, DatePhase.LATE)
                }

                // Calculate the day number in Pre-Debut (Turns 1-11).
                val turnsInPreDebut = 12
                val currTurn = (turnsInPreDebut - remainingTurns).coerceIn(1, 11)
                return fromDay(currTurn)
            }

            // Special case for Finale season (Turns 73-75) and for date strings that indicate it is the Finale season.
            if (isFinaleString(dayString, scenario)) {
                // Pass the cached day string to avoid redundant OCR operations.
                val (finalsDay, _) = getFinalsDay(imageUtils = imageUtils, cachedDayString = dayString, scenario = scenario)
                if (finalsDay == null) {
                    MessageLog.w(TAG, "[WARN] fromDateString:: Could not determine day in finale season. Defaulting to first day of finale (Turn 73).")
                    return GameDate(day = 73)
                }
                return GameDate(day = finalsDay)
            }

            return parseDateString(dayString)
        }

        /**
         * Converts a standard date string (e.g. "Senior Year Late Sep") into a [GameDate] object.
         *
         * This holds the pure string parsing so it can be tested without any OCR or Android dependencies. Pre-Debut and Finale strings are handled by [fromDateString] before
         * reaching here.
         *
         * @param dayString The date string to parse.
         * @return The parsed [GameDate] object, or null if the string could not be parsed.
         */
        fun parseDateString(dayString: String): GameDate? {
            // Split on any run of whitespace (e.g., "Classic Year Early Feb"). OCR wraps the date onto two lines when it is too long, so splitting on a single space would fuse
            // tokens together (e.g. "Year\nEarly") and shift every index after it.
            val parts = dayString.trim().split(WHITESPACE_REGEX)
            if (parts.size < 4) {
                MessageLog.w(TAG, "[WARN] parseDateString:: Invalid date string format: $dayString")
                return null
            }

            // The expected format is "<Year> Year <Phase> <Month>", so Junior/Classic/Senior is at index 0 and the month is at the end. The candidate lists are uppercase, so
            // uppercase each token to let the matcher take its exact-match fast path on a clean read.
            val yearPart: String = parts[0].uppercase()
            val phasePart: String = parts[2].uppercase()
            val monthPart: String = parts[3].uppercase()

            // Find the best match for the strings using Jaro Winkler fuzzy matching.
            val yearString: String? = TextUtils.matchStringInList(yearPart, YEAR_NAMES)
            if (yearString == null) {
                MessageLog.w(TAG, "[WARN] parseDateString:: Invalid date format. Could not detect YEAR from $yearPart.")
                return null
            }

            val monthString: String? = TextUtils.matchStringInList(monthPart, MONTH_SHORT_NAMES)
            if (monthString == null) {
                MessageLog.w(TAG, "[WARN] parseDateString:: Invalid date format. Could not detect MONTH from $monthPart.")
                return null
            }

            val phaseString: String? = TextUtils.matchStringInList(phasePart, PHASE_NAMES)
            if (phaseString == null) {
                MessageLog.w(TAG, "[WARN] parseDateString:: Invalid date format. Could not detect PHASE from $phasePart.")
                return null
            }

            // Fuzzy matching always returns an entry of the list it was given, so none of these lookups can miss.
            return GameDate(DateYear.fromName(yearString)!!, DateMonth.fromShortName(monthString)!!, DatePhase.fromName(phaseString)!!)
        }

        /**
         * Determines whether a day string indicates the game is in the Finale season.
         *
         * For most scenarios, the string contains "finale". For Trackblazer, it may instead contain "climax races underway".
         *
         * @param dayString The day string detected from the screen.
         * @param scenario The current scenario name (e.g., "Trackblazer").
         * @return True if the day string indicates the Finale season.
         */
        fun isFinaleString(dayString: String, scenario: String? = null): Boolean {
            val lower = dayString.lowercase()
            return lower.contains("finale") || (scenario == "Trackblazer" && lower.contains("climax races underway"))
        }

        /**
         * Parses OCR text from the Trackblazer Finale screen to determine the turn number.
         *
         * Trackblazer displays "X / 3" on the Finale screen where X is 0, 1, or 2 representing
         * the number of completed finals races (Qualifier, Semi-Final, Finals).
         *
         * @param detectedText The OCR text detected from the Trackblazer Finale indicator.
         * @return The turn number (73, 74, or 75). Defaults to 73 if text is unrecognizable.
         */
        fun parseTrackblazerFinaleDay(detectedText: String): Int {
            val text = detectedText.lowercase()
            return when {
                text.contains("0/3") -> 73
                text.contains("1/3") -> 74
                text.contains("2/3") -> 75
                else -> 73 // Default to first finale turn.
            }
        }

        /**
         * Determines whether a specific day number falls within the Summer training dates.
         *
         * Summer takes place during July and August in the Classic and Senior years. (Turns 37-40 and 61-64).
         *
         * @param dayToCheck The day number (turn number) to check.
         * @return True if the turn falls within Summer, false otherwise.
         */
        fun isSummer(dayToCheck: Int): Boolean {
            // Summer only takes place in Classic and Senior years.
            for (year in listOf(DateYear.CLASSIC, DateYear.SENIOR)) {
                if (
                    dayToCheck >= GameDate(year, DateMonth.JULY, DatePhase.EARLY).day &&
                    dayToCheck <= GameDate(year, DateMonth.AUGUST, DatePhase.LATE).day
                ) {
                    return true
                }
            }

            return false
        }

        /**
         * Determines the day number in the Finale season (Turns 73-75).
         *
         * Since the Finale season doesn't show standard date strings like "Junior Year Early Jan", we must use other on-screen cues like the goal text or scenario-specific indicators.
         *
         * @param imageUtils A reference to a [CustomImageUtils] instance.
         * @param cachedDayString Optional cached day string to avoid redundant OCR.
         * @param isOnMainScreen If true, skip the check for the training/race button in OCR.
         * @param scenario The current scenario name for special handling.
         * @return A Pair containing the detected day number (73-75) and the day string.
         */
        fun getFinalsDay(imageUtils: CustomImageUtils, cachedDayString: String? = null, isOnMainScreen: Boolean = false, scenario: String? = null): Pair<Int?, String?> {
            // Get the day string first to check if we're in finals before doing expensive OCR operations.
            val dayString: String = cachedDayString ?: imageUtils.determineDayString(isOnMainScreen)
            if (dayString == null) {
                MessageLog.w(TAG, "[WARN] getFinalsDay:: Could not detect day string.")
                return Pair(null, null)
            }

            // Early exit if the string doesn't indicate we are in the Finale season.
            if (!isFinaleString(dayString, scenario)) {
                return Pair(null, dayString)
            }

            // The Trackblazer scenario shows "X / 3" turns remaining for the Climax finals.
            if (scenario == "Trackblazer") {
                // If it's Trackblazer, we check a specific OCR region below the energy label for the finale stage turn.
                val (energyLocation, sourceBitmap) = LabelEnergy.find(imageUtils)
                if (energyLocation != null) {
                    val detectedText =
                        imageUtils.performOCROnRegion(
                            sourceBitmap,
                            imageUtils.relX(energyLocation.x, -245),
                            imageUtils.relY(energyLocation.y, 280),
                            imageUtils.relWidth(145),
                            imageUtils.relHeight(35),
                            useThreshold = true,
                            useGrayscale = true,
                            scale = 2.0,
                            ocrEngine = "mlkit",
                            debugName = "TrackblazerFinaleDay",
                        ).lowercase()

                    val finaleDay = parseTrackblazerFinaleDay(detectedText)
                    when (finaleDay) {
                        73 -> MessageLog.i(TAG, "[DATE] Trackblazer Finale Qualifier (Turn 73).")
                        74 -> MessageLog.i(TAG, "[DATE] Trackblazer Finale Semi-Final (Turn 74).")
                        75 -> MessageLog.i(TAG, "[DATE] Trackblazer Finale Finals (Turn 75).")
                    }
                    return Pair(finaleDay, dayString)
                } else {
                    MessageLog.w(TAG, "[WARN] getFinalsDay:: Could not find energy asset for Trackblazer finale detection. Defaulting to turn 73.")
                    return Pair(73, dayString)
                }
            }

            // For other scenarios, we check the goal text for "Qualifier", "Semi-Final", or "Finals".
            val goalText = imageUtils.getGoalText().lowercase()

            // Helper to match goal text substrings.
            fun goalTextMatch(target: String, query: String, threshold: Double = 0.9): Boolean {
                return TextUtils.findMostSimilarSubstring(target, query, threshold) != null
            }

            val finalsDay =
                when {
                    goalTextMatch(goalText, "qualifier") -> {
                        MessageLog.i(TAG, "[DATE] Finale Qualifier (Turn 73).")
                        73
                    }

                    goalTextMatch(goalText, "semifinal") -> {
                        MessageLog.i(TAG, "[DATE] Finale Semi-Final (Turn 74).")
                        74
                    }

                    goalTextMatch(goalText, "finals") -> {
                        MessageLog.i(TAG, "[DATE] Finale Finals (Turn 75).")
                        75
                    }

                    else -> {
                        MessageLog.w(TAG, "[WARN] getFinalsDay:: Could not determine Finals date. Defaulting to turn 73.")
                        73
                    }
                }
            return Pair(finalsDay, dayString)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Converts the current date to its corresponding turn number.
     *
     * This is an instance wrapper around the static [GameDate.toDay] function.
     *
     * @return The current turn number.
     */
    fun toDay(): Int {
        return toDay(this.year, this.month, this.phase)
    }

    /**
     * Checks whether the current day falls within the Summer date range.
     *
     * This is an instance wrapper around the static [GameDate.isSummer] function.
     *
     * @return True if the current date is in the Summer date range.
     */
    fun isSummer(): Boolean {
        return isSummer(this.day)
    }

    /**
     * Updates all class property members to reflect the provided day (turn number).
     *
     * This will recalculate the year, month, and phase based on the turn number.
     *
     * @param day The turn number to update to.
     */
    fun updateDay(day: Int) {
        val tmpDate: GameDate = fromDay(day)
        this.year = tmpDate.year
        this.month = tmpDate.month
        this.phase = tmpDate.phase
        this.day = day

        // Recalculate season flags.
        bIsPreDebut = this.day < 12
        bIsFinaleSeason = this.day > 72
    }

    /**
     * Returns a new [GameDate] object representing the next turn.
     *
     * @return The next turn's [GameDate] object.
     */
    fun getNextDate(): GameDate {
        return fromDay(this.day + 1)
    }

    /**
     * Returns a human-readable string representation of the current date.
     *
     * For example: "Junior Year Early Jan (Turn 1)" or "Finale Qualifier (Turn 73)".
     *
     * @return The string representation.
     */
    override fun toString(): String {
        // Special strings for the Finale season.
        if (bIsFinaleSeason) {
            return when (this.day) {
                73 -> "Finale Qualifier (Turn ${this.day})"
                74 -> "Finale Semi-Final (Turn ${this.day})"
                75 -> "Finale Finals (Turn ${this.day})"
                else -> "INVALID FINALE DAY (> 75): ${this.day}"
            }
        }
        return "${this.year.longName} ${this.phase} ${this.month} (Turn ${this.day})"
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Updates the current date by detecting it from the screen.
     *
     * This method first checks if the game is in the Finale season. If not, it falls back to parsing the date string from the screen.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param scenario The current scenario name for special handling.
     * @param isOnMainScreen If true, skip the check for the training/race button in OCR.
     * @return True if the date was updated successfully, false otherwise.
     */
    fun update(imageUtils: CustomImageUtils, scenario: String? = null, isOnMainScreen: Boolean = false): Boolean {
        // First try to detect if we're in the Finale season.
        val (finalsDay, cachedDayString) = getFinalsDay(imageUtils, isOnMainScreen = isOnMainScreen, scenario = scenario)
        if (finalsDay != null) {
            updateDay(finalsDay)
        } else {
            // If not in finals, pass the cached day string to avoid doing expensive OCR operations again.
            val tmpDate: GameDate? = fromDateString(s = cachedDayString, imageUtils = imageUtils, scenario = scenario)
            if (tmpDate == null) {
                MessageLog.e(TAG, "[ERROR] GameDate.update:: fromDateString returned null.")
                return false
            }
            // Update our internal turn number and derived components.
            updateDay(tmpDate.day)
        }
        return true
    }
}
