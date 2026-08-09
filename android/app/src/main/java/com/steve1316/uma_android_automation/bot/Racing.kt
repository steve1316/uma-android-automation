package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SQLiteSettingsManager
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.solver.SmartRaceSolverIntegration
import com.steve1316.uma_android_automation.components.ButtonAgenda
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonChangeRunningStyle
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonMyAgendas
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonNextRaceEnd
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.ButtonRace
import com.steve1316.uma_android_automation.components.ButtonRaceAgendaLoadList
import com.steve1316.uma_android_automation.components.ButtonRaceExclamation
import com.steve1316.uma_android_automation.components.ButtonRaceListFullStats
import com.steve1316.uma_android_automation.components.ButtonRaceManual
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.ButtonTryAgainAlt
import com.steve1316.uma_android_automation.components.ButtonViewResults
import com.steve1316.uma_android_automation.components.IconRaceAgendaEmpty
import com.steve1316.uma_android_automation.components.IconRaceDayRibbon
import com.steve1316.uma_android_automation.components.IconRaceListMaidenPill
import com.steve1316.uma_android_automation.components.IconRaceListPredictionDoubleStar
import com.steve1316.uma_android_automation.components.IconRaceListSelectionBracketBottomRight
import com.steve1316.uma_android_automation.components.IconScrollListBottomRight
import com.steve1316.uma_android_automation.components.IconScrollListTopLeft
import com.steve1316.uma_android_automation.components.LabelCongratulations
import com.steve1316.uma_android_automation.components.LabelRaceCriteriaFans
import com.steve1316.uma_android_automation.components.LabelRaceCriteriaG3OrAbove
import com.steve1316.uma_android_automation.components.LabelRaceCriteriaMaiden
import com.steve1316.uma_android_automation.components.LabelRaceCriteriaPreOpOrAbove
import com.steve1316.uma_android_automation.components.LabelRaceCriteriaTrophies
import com.steve1316.uma_android_automation.components.LabelRaceSelectionFans
import com.steve1316.uma_android_automation.components.LabelThereAreNoRacesToCompeteIn
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import com.steve1316.uma_android_automation.utils.CustomImageUtils.RaceDetails
import com.steve1316.uma_android_automation.utils.LogStreamServer
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl
import org.json.JSONObject
import org.opencv.core.Point

/**
 * Whether the extra-race pick should defer to the Smart Race Solver instead of standard racing. Any mandatory career goal (fan, trophy, or goal-pts
 * requirement) forces standard racing, whose double-star and G1-only filtering actually satisfies the goal. The solver only optimizes fans and epithets
 * and never honors these goals, so routing a requirement to it silently drops it. Pure so it is unit-testable without a live Racing.
 *
 * @param hasFanRequirement Whether a minimum-fan-count career goal is active.
 * @param hasTrophyRequirement Whether a win-a-trophy (G1) career goal is active.
 * @param hasInsufficientGoalRacePtsRequirement Whether a minimum goal-race-points requirement is active.
 * @param year The current career year.
 * @param enableSmartRaceSolver Whether the Smart Race Solver setting is enabled.
 * @param enableForceRacing Whether the force-racing setting is enabled.
 * @return True when the solver should pick the race, false to use standard racing.
 */
internal fun shouldUseSmartRacing(
    hasFanRequirement: Boolean,
    hasTrophyRequirement: Boolean,
    hasInsufficientGoalRacePtsRequirement: Boolean,
    year: DateYear,
    enableSmartRaceSolver: Boolean,
    enableForceRacing: Boolean,
): Boolean =
    when {
        hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement -> false
        enableSmartRaceSolver && year != DateYear.JUNIOR -> !enableForceRacing
        else -> false
    }

/**
 * Whether an active racing requirement should force navigation to the race screen this turn. A G1-only trophy can only be satisfied by a G1, so when the races DB holds no G1
 * for this turn, forcing the race would just cancel after a costly screen round-trip - return false so the caller trains instead. Fan, goal-point, and Pre-OP/G3-or-above
 * trophy requirements accept other grades, so they always force racing.
 *
 * @param hasFanRequirement Whether a fan-count requirement is active.
 * @param hasTrophyRequirement Whether a trophy requirement is active.
 * @param hasPreOpOrAboveRequirement Whether the trophy accepts Pre-OP or above.
 * @param hasG3OrAboveRequirement Whether the trophy accepts G3 or above.
 * @param hasInsufficientGoalRacePtsRequirement Whether a goal-race-points requirement is active.
 * @param hasG1ThisTurn Whether the races DB holds a G1 for the current turn (ground truth read from the local races table).
 * @return True to force navigation to the race screen, false to skip it and let the normal decision run.
 */
internal fun requirementForcesRacing(
    hasFanRequirement: Boolean,
    hasTrophyRequirement: Boolean,
    hasPreOpOrAboveRequirement: Boolean,
    hasG3OrAboveRequirement: Boolean,
    hasInsufficientGoalRacePtsRequirement: Boolean,
    hasG1ThisTurn: Boolean,
): Boolean {
    if (!(hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement)) return false
    // A G1-only trophy forces racing only on turns the DB holds a G1 for. Every other active requirement always forces racing.
    val isG1OnlyTrophy = hasTrophyRequirement && !hasFanRequirement && !hasInsufficientGoalRacePtsRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement
    if (isG1OnlyTrophy) return hasG1ThisTurn
    return true
}

/**
 * Manage and orchestrate the racing process, including mandatory, maiden, and extra races.
 *
 * @property game A reference to the bot's [Game] instance.
 * @property campaign A reference to the current [Campaign] instance.
 */
class Racing(private val game: Game, private val campaign: Campaign) {
    /** Whether to ignore the warning that appears when racing three times in a row. */
    val ignoreConsecutiveRaceWarning = SettingsHelper.getBooleanSetting("racing", "ignoreConsecutiveRaceWarning")

    /** Minimum energy before the G1-day pre-screen will peek at the trainings. Below the floor the bot skips the peek and takes the G1 race. 0 disables the floor. */
    internal val minEnergyForExtraRacing: Int = SettingsHelper.getIntSetting("racing", "minEnergyForExtraRacing", 30)

    /** Whether to prefer training over the free race on a G1 race day when a strong-enough rainbow training is available. Default off. */
    val enableG1DayPreference: Boolean = SettingsHelper.getBooleanSetting("racing", "enableG1DayPreference")

    /** Minimum rainbow count on the best training that justifies staying to train instead of taking the G1 race when the G1-day preference is enabled. */
    val g1DayMinRainbowCount: Int = SettingsHelper.getIntSetting("racing", "g1DayMinRainbowCount", 2)

    /** Whether to disable race retries. */
    internal val disableRaceRetries: Boolean = SettingsHelper.getBooleanSetting("racing", "disableRaceRetries")

    /** Whether to enable a free race retry if available. */
    internal val enableFreeRaceRetry: Boolean = SettingsHelper.getBooleanSetting("racing", "enableFreeRaceRetry")

    /** Whether to automatically complete the career on a failure. */
    internal val enableCompleteCareerOnFailure: Boolean = SettingsHelper.getBooleanSetting("racing", "enableCompleteCareerOnFailure")

    /** Whether to force the bot to race extra races regardless of other conditions. */
    val enableForceRacing = SettingsHelper.getBooleanSetting("racing", "enableForceRacing")

    /** Whether the Smart Race Solver schedules extra races for the trainee. */
    val enableSmartRaceSolver =
        SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver").also {
            // Pushes the flag to the Remote Log Viewer so it can hide the Race History panel when SRS is off.
            LogStreamServer.broadcastSmartRaceSolverEnabled(it)
            if (it) SmartRaceSolverIntegration.reset()
        }

    /** Whether to use the in-game race agenda feature. */
    val enableUserInGameRaceAgenda = SettingsHelper.getBooleanSetting("racing", "enableUserInGameRaceAgenda")

    /** Whether to limit extra races to only those in the in-game agenda. */
    private val limitRacesToInGameAgenda = SettingsHelper.getBooleanSetting("racing", "limitRacesToInGameAgenda", true)

    /** The specific in-game race agenda selected by the user. */
    private val selectedUserAgenda = SettingsHelper.getStringSetting("racing", "selectedUserAgenda")

    /** Optional custom agenda title that overrides the selected agenda name for OCR matching. */
    private val customAgendaTitle = SettingsHelper.getStringSetting("racing", "customAgendaTitle")

    /** The effective agenda name used for OCR matching - custom title if provided, otherwise the selected agenda. */
    private val effectiveAgendaName = if (customAgendaTitle.isNotBlank()) customAgendaTitle else selectedUserAgenda

    /** Whether to skip Summer training to do races from the in-game agenda. */
    val skipSummerTrainingForAgenda = SettingsHelper.getBooleanSetting("racing", "skipSummerTrainingForAgenda")

    /** The current number of retries available for the run. */
    internal var raceRetries = campaign.getMaxRaceRetries()

    /** Whether to check for the consecutive race warning. */
    internal var raceRepeatWarningCheck = false

    /** Whether a racing-related popup has been encountered. */
    var encounteredRacingPopup = false

    /** Whether this is the first time the bot is racing in the current career. */
    var firstTimeRacing = true

    /** Indicates that a fan requirement has been detected on the main screen. */
    var hasFanRequirement = false

    /** Indicates that a trophy requirement has been detected on the main screen. */
    var hasTrophyRequirement = false

    /** Indicates that a Pre-OP or above requirement has been detected. */
    var hasPreOpOrAboveRequirement = false

    /** Indicates that a G3 or above requirement has been detected. */
    var hasG3OrAboveRequirement = false

    /** Indicates that an insufficient goal race result pts requirement has been detected. */
    var hasInsufficientGoalRacePtsRequirement = false

    /** Tracks if the user's race agenda has been loaded during this career. */
    private var hasLoadedUserRaceAgenda = false

    /** Tracks the grade of the last race that was selected. */
    var lastRaceGrade: RaceGrade? = null
    var lastRaceFans: Int = 0

    /** Tracks the distance of the last race that was selected. */
    var lastRaceDistance: TrackDistance? = null

    /** Tracks the name of the last race that was selected. Empty when the race was never matched against the database. */
    var lastRaceName: String = ""

    /** Tracks the surface of the last race that was selected. */
    var lastRaceSurface: TrackSurface? = null

    /** Tracks if the last race selected was a Rival Race. */
    var lastRaceIsRival: Boolean = false

    /** Tracks if the current race has already been retried. */
    var bRetriedCurrentRace: Boolean = false

    /** Retries used on the current race. Shared by the button-based and dialog-based retry paths so both honor the per-race limit. */
    var retriesThisRace: Int = 0

    /**
     * Whether the race being run is retried until 1st place (mandatory races and Unity Cup).
     * Set by [runRaceWithRetries] so the dialog-based retry path can see it too.
     */
    var bRetryUntilFirst: Boolean = false

    /** Whether to stop the bot when a mandatory race is detected. */
    internal val enableStopOnMandatoryRace: Boolean = SettingsHelper.getBooleanSetting("racing", "enableStopOnMandatoryRaces")

    /** Whether a mandatory race has been detected during the check. */
    internal var detectedMandatoryRaceCheck = false

    /** The race strategy override for the Junior Year. */
    internal val juniorYearRaceStrategy = SettingsHelper.getStringSetting("racing", "juniorYearRaceStrategy")

    /** The user's originally selected race strategy. */
    internal val userSelectedOriginalStrategy = SettingsHelper.getStringSetting("racing", "originalRaceStrategy")

    /** Whether per-distance strategy mode is enabled. */
    private val enablePerDistanceStrategy = SettingsHelper.getBooleanSetting("racing", "enablePerDistanceStrategy")

    /** Per-distance Junior Year strategies, keyed by distance name (Short, Mile, Medium, Long). */
    private val juniorYearPerDistanceStrategies: Map<String, String> = loadPerDistanceStrategies("juniorYearPerDistanceStrategies")

    /** Per-distance Original strategies, keyed by distance name (Short, Mile, Medium, Long). */
    private val originalPerDistanceStrategies: Map<String, String> = loadPerDistanceStrategies("originalPerDistanceStrategies")

    /** Whether the Junior Year strategy override has been applied. */
    private var bHasSetStrategyJunior: Boolean = false

    /** Whether the original strategy has been restored after Junior Year. */
    private var bHasSetStrategyOriginal: Boolean = false

    /** A control flag used between the dialog handler and [selectRaceStrategy]. Only set when a strategy is selected in the dialog handler and unset at the beginning of [selectRaceStrategy]. */
    var bHasSetTemporaryRunningStyle: Boolean = false

    /** The maximum number of retries allowed per race, provided by the campaign. */
    internal val maxRetriesPerRace: Int = campaign.getMaxRetriesPerRace()

    /** The list of race grades that are eligible for retries, provided by the campaign. */
    internal val retryEligibleGrades: List<RaceGrade> = campaign.getRetryEligibleGrades()

    /**
     * Stores comprehensive information about a specific race.
     *
     * @property name The internal name of the race.
     * @property grade The grade of the race (e.g., G1, G2, G3).
     * @property fans The number of fans gained by winning the race.
     * @property nameFormatted The user-friendly formatted name of the race.
     * @property trackSurface The track surface (e.g., Turf, Dirt).
     * @property trackDistance The distance category of the race (e.g., Sprint, Mile).
     * @property turnNumber The specific turn number the race occurs on.
     * @property isRival Indicates if the race is a Rival Race.
     */
    data class RaceData(
        val name: String,
        val grade: RaceGrade,
        val fans: Int,
        val nameFormatted: String,
        val trackSurface: TrackSurface,
        val trackDistance: TrackDistance,
        val turnNumber: Int,
        var isRival: Boolean = false,
    ) {
        /**
         * Secondary constructor for initializing [RaceData] from raw database strings.
         *
         * @param name The internal name of the race.
         * @param grade The string representation of the race grade.
         * @param fans The number of fans gained.
         * @param nameFormatted The user-friendly formatted name.
         * @param trackSurface The string representation of the track surface.
         * @param trackDistance The string representation of the track distance.
         * @param turnNumber The specific turn number.
         */
        constructor(
            name: String,
            grade: String,
            fans: Int,
            nameFormatted: String,
            trackSurface: String,
            trackDistance: String,
            turnNumber: Int,
        ) : this(
            name,
            // Scraper source uses "Pre-Op" but we need "PRE_OP" for our enum.
            RaceGrade.fromName(grade.lowercase().replace("pre-op", "pre_op"))!!,
            fans,
            nameFormatted,
            TrackSurface.fromName(trackSurface)!!,
            TrackDistance.fromName(trackDistance)!!,
            turnNumber,
        )
    }

    /**
     * Represents a race that has been evaluated and assigned a score for smart racing.
     *
     * @property raceData The data for evaluated race.
     * @property score The final calculated score for the race.
     * @property fansScore The score component based on fan gains.
     * @property gradeScore The score component based on race grade.
     * @property aptitudeBonus The bonus applied based on the trainee's aptitudes.
     */
    data class ScoredRace(val raceData: RaceData, val score: Double, val fansScore: Double, val gradeScore: Double, val aptitudeBonus: Double)

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]Racing"

        /** The name of the races table in the database. */
        private const val TABLE_RACES = "races"

        /** The name of the race name column. */
        private const val RACES_COLUMN_NAME = "name"

        /** The name of the race grade column. */
        private const val RACES_COLUMN_GRADE = "grade"

        /** The name of the fan count column. */
        private const val RACES_COLUMN_FANS = "fans"

        /** The name of the turn number column. */
        private const val RACES_COLUMN_TURN_NUMBER = "turnNumber"

        /** The name of the formatted race name column. */
        private const val RACES_COLUMN_NAME_FORMATTED = "nameFormatted"

        /** The name of the track surface column. */
        private const val RACES_COLUMN_TRACK_SURFACE = "terrain"

        /** The name of the track distance column. */
        private const val RACES_COLUMN_TRACK_DISTANCE = "distanceType"

        /** The threshold for fuzzy string matching (0.0 to 1.0). */
        private const val SIMILARITY_THRESHOLD = 0.7

        /** Matches the distance token of a formatted race name (e.g. "3000m"), tolerating the letter O that OCR reads in place of a zero. */
        private val DISTANCE_TOKEN_REGEX = Regex("""\b[0-9Oo]{3,4}m\b""")

        /** Matches the parenthesized distance category of a formatted race name (e.g. "(Long)"). */
        private val DISTANCE_CATEGORY_REGEX = Regex("""\((sprint|mile|med|long)\)""", RegexOption.IGNORE_CASE)

        /**
         * Repairs the distance token of an OCR'd race name by turning the letter O back into a zero.
         *
         * ML Kit reads "3000m" as "300Om" often enough to derail the lookup.
         * Only the distance token is rewritten so letters elsewhere in the name are left alone.
         *
         * @param detectedName The race name as read from the screen.
         * @return The race name with its distance token repaired.
         */
        fun normalizeRaceName(detectedName: String): String {
            return DISTANCE_TOKEN_REGEX.replace(detectedName) { match -> match.value.replace('O', '0').replace('o', '0') }
        }

        /**
         * Parses the distance category out of a formatted race name.
         *
         * @param detectedName The race name as read from the screen.
         * @return The distance category, or null if the name does not spell one out.
         */
        fun distanceCategoryFromRaceName(detectedName: String): TrackDistance? {
            val category = DISTANCE_CATEGORY_REGEX.find(detectedName)?.groupValues?.get(1)?.lowercase() ?: return null
            // The race name abbreviates Medium to "Med". Every other category is spelled the same as its enum entry.
            return if (category == "med") TrackDistance.MEDIUM else TrackDistance.fromName(category)
        }

        /**
         * Checks that a candidate race's distance does not contradict the distance category spelled out in the detected name.
         *
         * Whole-string fuzzy matching scores a 3000m Long race against an 1800m Mile race at 0.9 because they share a track name and most tokens.
         * The winning candidate needs this sanity check. A name with no category cannot contradict anything, so every candidate is accepted.
         *
         * @param detectedName The race name as read from the screen.
         * @param candidateDistance The distance of the candidate race from the database.
         * @return True if the candidate is consistent with the detected name.
         */
        fun matchesDetectedDistance(detectedName: String, candidateDistance: TrackDistance): Boolean {
            val detectedDistance = distanceCategoryFromRaceName(detectedName) ?: return true
            return detectedDistance == candidateDistance
        }

        /**
         * Decides whether the retry budgets still allow another retry of the current race.
         *
         * The Try Again prompt appears as a dialog or as an on-screen button, each with its own handler. This is the budget check both apply.
         *
         * The dialog surface (`Campaign.shouldRetryRace`) passes `retryUntilFirst`, so mandatory and Unity Cup races skip the per-race cap.
         * The button surface ([canRetryGrade]) does not, since [runRaceWithRetries] already gives those races their own uncapped fallback branch.
         * Exempting them here as well would drain the run-wide pool before they ever reach that branch. The pool bounds both surfaces either way.
         *
         * @param raceRetries The retries left in the run-wide pool.
         * @param retriesThisRace The retries already used on the current race.
         * @param maxRetriesPerRace The per-race retry cap.
         * @param retryUntilFirst Whether the current race is exempt from the per-race cap.
         * @return True if the race may be retried again.
         */
        fun canRetryRace(raceRetries: Int, retriesThisRace: Int, maxRetriesPerRace: Int, retryUntilFirst: Boolean): Boolean {
            if (raceRetries <= 0) return false
            return retryUntilFirst || retriesThisRace < maxRetriesPerRace
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /** Handles the test to detect the currently displayed races on the Race List screen. */
    fun startRaceListDetectionTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning detection test on the Race List screen for the currently displayed races.")
        if (!ButtonRaceListFullStats.check(game.imageUtils)) {
            MessageLog.i(TAG, "[TEST] Bot is not on the Race List screen. Ending the test.")
            return
        }

        // Detect the current date first.
        campaign.updateDate(isOnMainScreen = false)

        // Check for all double star predictions.
        val doublePredictionLocations = IconRaceListPredictionDoubleStar.findAll(game.imageUtils)
        MessageLog.i(TAG, "[TEST] Found ${doublePredictionLocations.size} races with double predictions.")

        doublePredictionLocations.forEachIndexed { index, location ->
            val raceName = game.imageUtils.extractRaceName(location)
            MessageLog.i(TAG, "[TEST] Race #${index + 1} - Detected name: \"$raceName\".")

            // Query database for race details (may return multiple matches with different fan counts).
            val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)

            if (raceDataList.isNotEmpty()) {
                MessageLog.i(TAG, "[TEST] Race #${index + 1} - Found ${raceDataList.size} match(es):")
                raceDataList.forEach { raceData ->
                    MessageLog.i(TAG, "[TEST]     Name: ${raceData.name}")
                    MessageLog.i(TAG, "[TEST]     Grade: ${raceData.grade}")
                    MessageLog.i(TAG, "[TEST]     Fans: ${raceData.fans}")
                    MessageLog.i(TAG, "[TEST]     Formatted: ${raceData.nameFormatted}")
                }
            } else {
                MessageLog.i(TAG, "[TEST] Race #${index + 1} - No match found for turn ${campaign.date.day}")
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /** Resets all racing requirement flags to their default state. */
    fun clearRacingRequirementFlags() {
        hasFanRequirement = false
        hasTrophyRequirement = false
        hasPreOpOrAboveRequirement = false
        hasG3OrAboveRequirement = false
        hasInsufficientGoalRacePtsRequirement = false
    }

    /**
     * Handles the dialog sequence that appears after tapping a Load List button (overwrite, scheduled-race, and closing dialogs).
     * Polls until the agenda finishes loading or a 5-second timeout elapses.
     */
    private fun handleAgendaLoadDialogs() {
        // Timeout after 5 seconds. Shouldn't ever take near that long.
        val timeoutMs = 5000
        val startTime: Long = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result: DialogHandlerResult =
                campaign.handleDialogs(
                    args =
                        mapOf(
                            "bShouldDefer" to true,
                            "bShouldWait" to true,
                            "bShouldWaitForLoading" to true,
                        ),
                )

            if (result is DialogHandlerResult.NoDialogDetected) {
                continue
            }

            if (result !is DialogHandlerResult.Deferred) {
                val name =
                    when (result) {
                        is DialogHandlerResult.Handled -> result.dialog.name
                        is DialogHandlerResult.Unhandled -> result.dialog.name
                        else -> "Unknown"
                    }
                throw IllegalStateException("loadUserRaceAgenda: Received non-deferred dialog result ($name). Expected deferred.")
            }

            when (result.dialog.name) {
                "overwrite" -> {
                    result.dialog.ok(game.imageUtils)
                }

                // Pops up when we try to load agenda with races that are in the past.
                "scheduled_race" -> {
                    result.dialog.close(game.imageUtils)
                }

                // We've closed all the extra dialogs after loading agenda so break from the loop.
                "scheduled_races" -> {
                    break
                }

                // We might detect the dialog too quick and find this one as it is in the process of closing. Ignore this and continue the loop.
                "my_agendas" -> {}

                // No dialog detected. This can happen if a dialog is closing. Not a problem, just continue with loop and timeout when the time comes.
                null -> {}

                // Fall back to the base dialog handler if we get a dialog that we weren't expecting.
                else -> {
                    MessageLog.e(TAG, "[ERROR] loadUserRaceAgenda:: Unknown dialog detected: ${result.dialog.name}. Falling back to base dialog handler.")
                    campaign.handleDialogs()
                }
            }
        }
    }

    /**
     * Loads the user's selected in-game race agenda.
     *
     * This function navigates through the agenda UI, finds the matching agenda, and loads it. If the agenda is not immediately visible, it will scroll the list to find it.
     */
    fun loadUserRaceAgenda() {
        // Only load the agenda once per career.
        if (!enableUserInGameRaceAgenda || hasLoadedUserRaceAgenda || campaign.date.bIsFinaleSeason) {
            return
        } else if (LabelRaceCriteriaMaiden.check(game.imageUtils)) {
            MessageLog.i(TAG, "[RACE] A maiden race needs to be won first before applying the user's race agenda.")
            return
        }

        // Navigate to the race selection screen.
        // We only proceed if the button is enabled AND we successfully click it.
        // Everything else returns from this function.
        when (campaign.racesButton.checkDisabled(game.imageUtils)) {
            true -> {
                MessageLog.i(TAG, "[RACE] Races button is disabled. Skipping loading the race agenda.")
                return
            }

            false -> {
                if (campaign.racesButton.click(game.imageUtils)) {
                    MessageLog.i(TAG, "[RACE] Clicked the Races button. Proceeding to load the race agenda...")
                } else {
                    MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Detected the Races button but failed to click it. Skipping loading the race agenda.")
                    return
                }
            }

            null -> {
                MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Failed to detect the Races button. Skipping loading the race agenda.")
                return
            }
        }

        game.waitForLoading()

        // Wait for any dialog (e.g. consecutive race warning) to appear before checking.
        game.wait(game.dialogWaitDelay)

        // We are forced to race, so we need to ignore this warning dialog.
        campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))

        game.waitForLoading()

        MessageLog.i(TAG, "[RACE] Loading user's in-game race agenda: $effectiveAgendaName")

        // It is assumed that the user is already at the screen with the list of selectable races.
        game.wait(game.dialogWaitDelay)

        // Taps on the Agenda button.
        if (!ButtonAgenda.click(game.imageUtils)) {
            MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Could not find the Agenda button. Backing out and skipping agenda loading.")
            ButtonBack.click(game.imageUtils)
            game.waitForLoading()
            return
        }
        game.wait(game.dialogWaitDelay)

        // Taps on the My Agenda button.
        if (!ButtonMyAgendas.click(game.imageUtils)) {
            MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Could not find the My Agenda button. Closing and backing out.")
            ButtonClose.click(game.imageUtils)
            game.wait(0.5)
            ButtonBack.click(game.imageUtils)
            game.waitForLoading()
            return
        }
        game.wait(game.dialogWaitDelay)

        // Check if an agenda is already loaded.
        // If so, then the user must have loaded this earlier in the career so no need to select it again.
        if (!IconRaceAgendaEmpty.check(game.imageUtils)) {
            MessageLog.i(TAG, "[RACE] A race agenda is already loaded. Skipping agenda selection.")

            // Mark as loaded so we don't try again this run and close the popup.
            hasLoadedUserRaceAgenda = true
            ButtonClose.click(game.imageUtils)
            game.wait(0.5)
            ButtonClose.click(game.imageUtils)
            game.wait(0.5)

            // Now back out of the race selection screen.
            ButtonBack.click(game.imageUtils)
            game.wait(0.5)
            return
        }

        var foundAgenda = false
        var swipeCount = 0
        val maxSwipes = 10

        while (!foundAgenda && swipeCount < maxSwipes) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()

            // Find all the Load List buttons on the current screen.
            val loadListButtonLocations: ArrayList<Point> = ButtonRaceAgendaLoadList.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
            if (loadListButtonLocations.isEmpty()) {
                MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: No Load List buttons found on screen.")
                break
            }

            MessageLog.i(TAG, "[RACE] Found ${loadListButtonLocations.size} Load List button(s) on screen.")

            // Get the mappings of button locations to agenda header texts via OCR.
            val agendaMappings = game.imageUtils.determineAgendaHeaderMappings(sourceBitmap, loadListButtonLocations)
            agendaMappings.forEach { (location, text) ->
                MessageLog.i(TAG, "[RACE] Detected agenda at (${location.x}, ${location.y}): \"$text\"")
            }

            // Search for the target agenda.
            for ((buttonLocation, agendaText) in agendaMappings) {
                if (agendaText == effectiveAgendaName) {
                    MessageLog.i(TAG, "[RACE] ✓ Found $effectiveAgendaName. Tapping the Load List button...")

                    // Clicking this button triggers connection to server.
                    // Or it could result in three other states:
                    // 1. The overwrite dialog appears.
                    // 2. The scheduled_race dialog appears.
                    // 3. The my_agendas dialog closes automatically and the scheduled_races dialog remains on screen.
                    game.gestureUtils.tap(buttonLocation.x, buttonLocation.y, ButtonRaceAgendaLoadList.template.path)

                    handleAgendaLoadDialogs()

                    foundAgenda = true
                    break
                }

                // Check if we've reached "Agenda 8" (end of list).
                if (agendaText == "Agenda 8") {
                    MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Reached Agenda 8 but target $effectiveAgendaName not found.")
                    break
                }
            }

            if (!foundAgenda && swipeCount < maxSwipes - 1) {
                // Swipe up to reveal more buttons.
                // Use the Close button location as a reference point for swiping.
                val closeButtonLocation = ButtonClose.find(game.imageUtils).first
                if (closeButtonLocation != null) {
                    val swipeX = closeButtonLocation.x.toFloat()
                    val swipeY = closeButtonLocation.y.toFloat()
                    MessageLog.i(TAG, "[RACE] Swiping up to reveal more agendas (attempt ${swipeCount + 1}/$maxSwipes)...")
                    game.gestureUtils.swipe(swipeX, swipeY - 300f, swipeX, swipeY - 400f)
                    game.wait(0.5)
                } else {
                    // If we can't find the close button for reference, try using the first Load List button.
                    if (loadListButtonLocations.isNotEmpty()) {
                        val swipeX = loadListButtonLocations[0].x.toFloat()
                        val swipeY = loadListButtonLocations[0].y.toFloat()
                        MessageLog.i(TAG, "[RACE] Swiping up using Load List button reference (attempt ${swipeCount + 1}/$maxSwipes)...")
                        game.gestureUtils.swipe(swipeX, swipeY - 300f, swipeX, swipeY - 400f)
                        game.wait(0.5)
                    }
                }
                swipeCount++
            } else if (!foundAgenda) {
                swipeCount++
            }
        }

        if (!foundAgenda) {
            MessageLog.w(TAG, "[WARN] loadUserRaceAgenda:: Could not find $effectiveAgendaName after $swipeCount swipe(s). Closing agenda selection.")
        }

        // Mark as loaded so we don't try again this run and close the popup.
        hasLoadedUserRaceAgenda = true
        ButtonClose.click(game.imageUtils)
        game.waitForLoading()
        ButtonClose.click(game.imageUtils)
        game.waitForLoading()
        game.wait(0.25)

        // Now back out of the race selection screen.
        ButtonBack.click(game.imageUtils)
        game.waitForLoading()
    }

    /**
     * Finds a race location from a list of prediction locations by matching the race name.
     *
     * @param predictionLocations List of double-prediction locations to search through.
     * @param targetRaceName The race name to find.
     * @param logMatch If true, log when a match is found.
     * @return The Point location if found, null otherwise.
     */
    private fun findRaceLocationByName(predictionLocations: ArrayList<Point>, targetRaceName: String, logMatch: Boolean = false): Point? {
        return predictionLocations.find { location ->
            val raceName = game.imageUtils.extractRaceName(location)
            val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
            val match = raceDataList.any { it.name == targetRaceName }
            if (match && logMatch) {
                MessageLog.i(TAG, "[RACE] ✓ Found target race at location (${location.x}, ${location.y}).")
            }
            match
        }
    }

    /**
     * Scrolls the list of races up or down and returns the updated list of prediction locations.
     *
     * @param scrollDown If true, scroll down; if false, scroll up.
     * @return The updated list of double-prediction locations after scrolling, or null if scroll failed.
     */
    private fun scrollRaceListAndRedetect(scrollDown: Boolean = true): ArrayList<Point>? {
        val confirmButtonLocation = ButtonRace.find(game.imageUtils).first
        if (confirmButtonLocation == null) {
            MessageLog.i(TAG, "[RACE] Could not find \"Race\" button for scroll reference.")
            return null
        }

        val startX = confirmButtonLocation.x.toFloat()
        val startY = (confirmButtonLocation.y - 300).toFloat()
        val endY = (confirmButtonLocation.y - 400).toFloat()

        if (scrollDown) {
            game.gestureUtils.swipe(startX, startY, startX, endY)
        } else {
            game.gestureUtils.swipe(startX, endY, startX, startY)
        }
        game.wait(2.0)

        return IconRaceListPredictionDoubleStar.findAll(game.imageUtils)
    }

    /**
     * Checks if there are fan or trophy requirements that need to be satisfied.
     *
     * @param sourceBitmap Optional source bitmap to use for detection.
     */
    fun checkRacingRequirements(sourceBitmap: Bitmap? = null) {
        // Skip racing requirements checks during Summer unless skipSummerTrainingForAgenda is enabled.
        if (campaign.date.isSummer() && !(skipSummerTrainingForAgenda && enableUserInGameRaceAgenda)) {
            if (hasFanRequirement || hasTrophyRequirement) {
                MessageLog.i(TAG, "[RACE] It is currently Summer. Skipping racing requirements checks and clearing flags.")
                clearRacingRequirementFlags()
            }
            return
        }

        // Check for fan requirement on the main screen.
        val sourceBitmapToUse = sourceBitmap ?: game.imageUtils.getSourceBitmap()
        val needsFanRequirement = LabelRaceCriteriaFans.check(game.imageUtils, sourceBitmap = sourceBitmapToUse, confidence = 0.9)
        if (needsFanRequirement) {
            hasFanRequirement = true
            MessageLog.i(TAG, "[RACE] Fan requirement criteria detected on main screen. Forcing racing to fulfill requirement.")
        } else {
            // Clear the flag if requirement is no longer present.
            if (hasFanRequirement) {
                MessageLog.i(TAG, "[RACE] Fan requirement no longer detected on main screen. Clearing flag.")
                hasFanRequirement = false
            }

            // Check for trophy requirement on the main screen.
            val needsTrophyRequirement = LabelRaceCriteriaTrophies.check(game.imageUtils, sourceBitmap = sourceBitmapToUse, confidence = 0.9)
            if (needsTrophyRequirement) {
                hasTrophyRequirement = true

                // Check for Pre-OP or above criteria.
                val needsPreOpOrAbove = LabelRaceCriteriaPreOpOrAbove.check(game.imageUtils, sourceBitmap = sourceBitmapToUse, confidence = 0.9)
                if (needsPreOpOrAbove) {
                    hasPreOpOrAboveRequirement = true
                    MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria detected. Any race can be run to fulfill the requirement.")
                } else {
                    hasPreOpOrAboveRequirement = false
                }

                // Check for G3 or above criteria.
                val needsG3OrAbove = LabelRaceCriteriaG3OrAbove.check(game.imageUtils, sourceBitmap = sourceBitmapToUse, confidence = 0.9)
                if (needsG3OrAbove) {
                    hasG3OrAboveRequirement = true
                    MessageLog.i(TAG, "[RACE] Trophy requirement with G3 or above criteria detected. Any race can be run to fulfill the requirement.")
                } else {
                    hasG3OrAboveRequirement = false
                }

                if (!hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
                    MessageLog.i(TAG, "[RACE] Trophy requirement criteria detected on main screen. Forcing racing to fulfill requirement (G1 races only).")
                }
            } else {
                // Clear the flags if requirement is no longer present.
                if (hasTrophyRequirement) {
                    MessageLog.i(TAG, "[RACE] Trophy requirement no longer detected on main screen. Clearing flags.")
                    // Clear trophy and criteria flags together since they are related.
                    hasTrophyRequirement = false
                    hasPreOpOrAboveRequirement = false
                    hasG3OrAboveRequirement = false
                }
            }
        }
    }

    /**
     * True when the active requirement should force racing this turn (see `requirementForcesRacing`). Reads the local races DB for the G1-only trophy case.
     *
     * @return True to force navigation to the race screen, false to skip it and let the normal decision run.
     */
    fun requirementForcesRacingThisTurn(): Boolean =
        requirementForcesRacing(
            hasFanRequirement,
            hasTrophyRequirement,
            hasPreOpOrAboveRequirement,
            hasG3OrAboveRequirement,
            hasInsufficientGoalRacePtsRequirement,
            hasG1ThisTurn = hasG1RacesAtTurn(campaign.date.day),
        )

    /**
     * Determines if the extra racing process should be started now or later. Discretionary extra races only ever come from the Smart Race Solver, so with it off this
     * only stays true for force racing and the scenario bypass.
     *
     * @return True if the current date is okay to start the extra racing process and false otherwise.
     */
    fun checkEligibilityToStartExtraRacingProcess(): Boolean {
        MessageLog.i(TAG, "\n[RACE] Now determining eligibility to start the extra racing process...")

        // Don't bother looking for races on Junior Year Early July (Turn 13) since they only start showing up on Turn 14.
        if (campaign.date.day == 13) {
            MessageLog.i(TAG, "[RACE] Junior Year Early July (Turn 13) detected. No races available until Turn 14. Skipping extra race check.")
            campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Junior Year Early July (Turn 13) - no races available until Turn 14")
            return false
        }

        // If the user wants to limit extra races to ONLY those scheduled in their in-game racing agenda.
        if (enableUserInGameRaceAgenda && limitRacesToInGameAgenda) {
            MessageLog.i(TAG, "[RACE] Skipping extra race check due to 'Limit Extra Races to Agenda' setting in favor of those scheduled by the user's in-game racing agenda.")
            campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Limit Extra Races to Agenda setting is on; no agenda race scheduled for this turn")
            return false
        }

        // The force-racing setting always navigates to the race screen.
        if (enableForceRacing) {
            Log.d(TAG, "[DEBUG] checkEligibilityToStartExtraRacingProcess:: Force racing is enabled so eligibility will be true.")
            campaign.decisionTracer.recordRaceEligibility(eligible = true, reason = "Force-racing setting is on")
            return true
        }

        // decideNextAction force-races any requirement a race this turn can satisfy before it ever calls this (via requirementForcesRacingThisTurn), so a requirement still
        // active here is the unsatisfiable G1-only trophy case - the races DB holds no G1 this turn. Suppress the extra-race fallback so it does not navigate only to cancel.
        if (hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement) {
            MessageLog.i(TAG, "[RACE] Requirement active but the races DB holds no qualifying race for turn ${campaign.date.day}. Skipping the extra-race fallback.")
            campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Requirement active but no qualifying race in the races DB for this turn")
            return false
        }

        // When the Smart Race Solver is enabled, its schedule is authoritative for extra races. If the solver did not plan a race for
        // this turn, suppress every extra-race fallback (including the scenario bypass below) so the bot trains or rests instead of
        // racing as filler. Hard requirements above still short-circuit to true before this guard.
        if (enableSmartRaceSolver) {
            val plannedKey = SmartRaceSolverIntegration.peekRaceKeyForTurn(currentTurn = campaign.date.day, scenario = game.scenario)
            if (plannedKey == null) {
                MessageLog.i(TAG, "[RACE] Smart Race Solver has no race planned for turn ${campaign.date.day}. Skipping the extra-race fallback.")
                campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Smart Race Solver has no race planned for this turn")
                return false
            }
            MessageLog.i(TAG, "[RACE] Smart Race Solver has \"$plannedKey\" planned for turn ${campaign.date.day}. Proceeding to racing screen.")
            val result = !raceRepeatWarningCheck
            campaign.decisionTracer.recordRaceEligibility(eligible = result, reason = "Smart Race Solver planned race \"$plannedKey\" (raceRepeatWarning=$raceRepeatWarningCheck)")
            return result
        }

        // For scenarios that race as often as possible, bypass most checks.
        if (campaign.shouldBypassSmartRacing()) {
            MessageLog.i(TAG, "[RACE] Bypassing smart racing and interval checks.")

            // Still check for finals and summer as they are hard restrictions.
            if (campaign.checkFinals()) {
                MessageLog.i(TAG, "[RACE] It is UMA Finals right now so there will be no extra races. Stopping extra race check.")
                campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "UMA Finals - no extra races")
                return false
            } else if (campaign.date.isSummer() && !(skipSummerTrainingForAgenda && enableUserInGameRaceAgenda)) {
                MessageLog.i(TAG, "[RACE] It is currently Summer right now. Stopping extra race check.")
                campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Summer - no extra races (skipSummerTrainingForAgenda not active)")
                return false
            } else if (campaign.racesButton.checkDisabled(game.imageUtils) == true) {
                MessageLog.i(TAG, "[RACE] Extra Races button is currently locked. Stopping extra race check.")
                campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "Extra Races button is currently locked / disabled")
                return false
            }

            val result = !raceRepeatWarningCheck
            campaign.decisionTracer.recordRaceEligibility(eligible = result, reason = "Scenario bypasses smart racing (raceRepeatWarning=$raceRepeatWarningCheck)")
            return result
        }

        // Every discretionary extra race now comes from the Smart Race Solver, so with it off there is nothing left to race for and the bot trains or rests instead.
        MessageLog.i(TAG, "[RACE] The Smart Race Solver is off, so there is no source of extra races. Skipping the extra race check.")
        campaign.decisionTracer.recordRaceEligibility(eligible = false, reason = "No extra-race source is active (Smart Race Solver is off)")
        return false
    }

    /**
     * Updates the running style aptitudes from the race screen.
     *
     * @return True if aptitudes were successfully updated; false otherwise.
     */
    internal fun updateRaceScreenRunningStyleAptitudes(): Boolean {
        val bitmap = game.imageUtils.getSourceBitmap()
        val bbox =
            BoundingBox(
                x = game.imageUtils.relX(0.0, 125),
                y = game.imageUtils.relY(0.0, 1140),
                w = game.imageUtils.relWidth(825),
                h = game.imageUtils.relHeight(45),
            )
        var text: String =
            game.imageUtils.performOCROnRegion(
                bitmap,
                bbox.x,
                bbox.y,
                bbox.w,
                bbox.h,
                useThreshold = false,
                useGrayscale = false,
                debugName = "updateRaceScreenRunningStyleAptitudes",
            )
        if (text == "") {
            MessageLog.w(TAG, "[WARN] updateRaceScreenRunningStyleAptitudes:: performOCROnRegion did not detect any text.")
            return false
        }
        text = text.replace("[^A-Za-z]".toRegex(), "").lowercase()
        val substrings = listOf("end", "late", "pace", "front")
        val parts = text.split(*substrings.toTypedArray()).filter { it.isNotBlank() }
        if (parts.size != 4) {
            MessageLog.w(TAG, "[WARN] updateRaceScreenRunningStyleAptitudes:: performOCROnRegion returned a malformed string: $text")
            return false
        }
        val styleMap: Map<String, String> = substrings.zip(parts).toMap()
        for ((styleString, aptitudeString) in styleMap) {
            val style: RunningStyle? = RunningStyle.fromShortName(styleString)
            if (style == null) {
                MessageLog.w(TAG, "[WARN] updateRaceScreenRunningStyleAptitudes:: performOCROnRegion returned invalid running style: $styleString")
                return false
            }
            val aptitude: Aptitude? = Aptitude.fromName(aptitudeString)
            if (aptitude == null) {
                MessageLog.w(TAG, "[WARN] updateRaceScreenRunningStyleAptitudes:: performOCROnRegion returned invalid aptitude for running style: $style -> $aptitudeString")
                return false
            }
            campaign.trainee.setRunningStyleAptitude(style, aptitude)
        }

        MessageLog.i(TAG, "[RACE] Updated running style aptitudes: ${campaign.trainee.runningStyleAptitudes}")
        return true
    }

    /**
     * Handles race strategy override for Junior Year races.
     *
     * During Junior Year: Applies the user-selected strategy and stores the original. After Junior Year: Restores the original strategy and disables the feature.
     *
     * If the date is unknown and the running style hasn't ever been set, then we set the strategy using the Original strategy. The next time we race and have access to the date, we will attempt to
     * set the running style no matter what in order to avoid weird edge cases.
     *
     * This is as opposed to setting a temporary flag and updating our flags the next time a date is detected. However, if we did this, then there are edge cases such as racing in late december of
     * junior year. This could cause us to incorrectly determine that we set the Original race strategy in the previous turn since we have no idea how many turns have passed since setting the initial
     * strategy.
     *
     * @param timeoutMs The max time (in milliseconds) for this operation to run.
     * @return If no change needed to be made to running style, returns True. Otherwise, returns whether a running style was successfully selected.
     */
    fun selectRaceStrategy(timeoutMs: Int = 30000): Boolean {
        // Unset this flag so that we can validate that the dialog handler completed the operation successfully.
        // If this isn't set by the end of this function, then we know we failed to set the strategy.
        // We can't use `campaign.trainee.bHasSetRunningStyle` since that flag isn't set when day is 1, and
        // we need to be able to handle cases where we don't know the date in this function.
        bHasSetTemporaryRunningStyle = false

        val isJuniorYear = campaign.date.day != 1 && campaign.date.year == DateYear.JUNIOR
        val isPastJuniorYear = campaign.date.year.ordinal > DateYear.JUNIOR.ordinal

        // Determine if a strategy override or reversion is needed.
        val bShouldSetStrategyJunior = isJuniorYear && !bHasSetStrategyJunior && juniorYearRaceStrategy != userSelectedOriginalStrategy
        val bShouldSetStrategyOriginal = isPastJuniorYear && bHasSetStrategyJunior && !bHasSetStrategyOriginal

        when {
            // Per-distance mode requires setting strategy before every race since different distances may have different strategies.
            enablePerDistanceStrategy -> MessageLog.i(TAG, "[RACE] Per-distance strategy enabled. Setting strategy for current race.")
            bShouldSetStrategyJunior -> MessageLog.i(TAG, "[RACE] Junior Year detected. Applying Junior race strategy override: $juniorYearRaceStrategy")
            bShouldSetStrategyOriginal -> MessageLog.i(TAG, "[RACE] Past Junior Year detected. Reverting to original race strategy: $userSelectedOriginalStrategy")
            !campaign.trainee.bHasSetRunningStyle -> MessageLog.i(TAG, "[RACE] Setting initial race strategy for unknown date.")
            else -> return true
        }

        var numTries = 0
        val startTime: Long = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            MessageLog.d(TAG, "[DEBUG] selectRaceStrategy:: Changing race strategy. Attempt #${numTries + 1}")
            if (ButtonChangeRunningStyle.click(game.imageUtils)) {
                game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
            }

            campaign.handleDialogs()

            if (bHasSetTemporaryRunningStyle) {
                break
            }

            numTries++
        }

        when {
            !bHasSetTemporaryRunningStyle -> {
                MessageLog.w(TAG, "[WARN] selectRaceStrategy:: Timed out setting the race strategy after $numTries tries.")
            }

            bShouldSetStrategyJunior -> {
                MessageLog.i(TAG, "[RACE] Successfully set Junior Year race strategy.")
                bHasSetStrategyJunior = true
            }

            bShouldSetStrategyOriginal -> {
                MessageLog.i(TAG, "[RACE] Successfully set Original race strategy.")
                bHasSetStrategyOriginal = true
            }

            else -> {
                MessageLog.i(TAG, "[RACE] Successfully set race strategy for unknown date.")
            }
        }

        return bHasSetTemporaryRunningStyle
    }

    /**
     * Loads per-distance strategy settings from a JSON object stored in SharedPreferences.
     *
     * @param settingKey The key for the per-distance strategies setting.
     * @return A map of distance name to strategy string.
     */
    private fun loadPerDistanceStrategies(settingKey: String): Map<String, String> {
        return try {
            val jsonStr = SettingsHelper.getStringSetting("racing", settingKey)
            if (jsonStr.isBlank()) return mapOf("Short" to "Default", "Mile" to "Default", "Medium" to "Default", "Long" to "Default")
            val jsonObj = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            jsonObj.keys().forEach { key -> map[key] = jsonObj.getString(key) }
            map
        } catch (e: Exception) {
            mapOf("Short" to "Default", "Mile" to "Default", "Medium" to "Default", "Long" to "Default")
        }
    }

    /**
     * Maps a [TrackDistance] enum to the per-distance settings key used in the frontend.
     */
    private fun TrackDistance.toSettingsKey(): String =
        when (this) {
            TrackDistance.SPRINT -> "Short"
            TrackDistance.MILE -> "Mile"
            TrackDistance.MEDIUM -> "Medium"
            TrackDistance.LONG -> "Long"
        }

    /**
     * Resolves the strategy string to use based on current mode (blanket vs per-distance) and race context.
     *
     * @param isJuniorYear Whether the current year is Junior Year.
     * @return The strategy string (e.g. "Default", "Auto", "Front", "Pace", "Late", "End").
     */
    internal fun resolveStrategyForCurrentRace(isJuniorYear: Boolean): String {
        if (!enablePerDistanceStrategy) {
            return if (isJuniorYear) juniorYearRaceStrategy else userSelectedOriginalStrategy
        }

        val distanceKey = lastRaceDistance?.toSettingsKey()
        val strategyMap = if (isJuniorYear) juniorYearPerDistanceStrategies else originalPerDistanceStrategies

        return if (distanceKey != null) {
            val strategy = strategyMap[distanceKey] ?: "Default"
            MessageLog.i(TAG, "[RACE] Per-distance strategy for $distanceKey: $strategy")
            strategy
        } else {
            MessageLog.w(TAG, "[RACE] Per-distance strategy enabled but race distance unknown. Falling back to blanket strategy.")
            if (isJuniorYear) juniorYearRaceStrategy else userSelectedOriginalStrategy
        }
    }

    /**
     * Whether the just-finished race is an eligible grade that can still be retried within the remaining budgets.
     *
     * @param sourceBitmap Optional pre-captured screenshot to match the retry button against.
     * @return True when a grade-based retry should be attempted.
     */
    private fun canRetryGrade(sourceBitmap: Bitmap? = null): Boolean =
        !disableRaceRetries &&
            lastRaceGrade != null &&
            retryEligibleGrades.contains(lastRaceGrade) &&
            canRetryRace(raceRetries, retriesThisRace, maxRetriesPerRace, retryUntilFirst = false) &&
            ButtonTryAgainAlt.checkDisabled(game.imageUtils, sourceBitmap = sourceBitmap) == false

    /**
     * Whether the just-finished race was a one-time rival race that can still be retried within the remaining budgets.
     *
     * @param sourceBitmap Optional pre-captured screenshot to match the retry button against.
     * @return True when a rival-race retry should be attempted (at most once per race).
     */
    private fun canRetryRival(sourceBitmap: Bitmap? = null): Boolean =
        lastRaceIsRival && !bRetriedCurrentRace && canRetryGrade(sourceBitmap)

    /**
     * Taps the retry button and, on a successful tap, waits for the race to restart and consumes one retry from the budgets.
     *
     * @param sourceBitmap Optional pre-captured screenshot to match the retry button against.
     */
    private fun attemptRaceRetry(sourceBitmap: Bitmap? = null) {
        if (ButtonTryAgainAlt.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
            game.wait(3.0)
            retriesThisRace++
            raceRetries--
        }
    }

    /**
     * Checks whether the skip button is genuinely locked.
     *
     * [ButtonViewResults.checkDisabled] reads a button as disabled whenever its crop is darker than the template, so a fading screen makes
     * every button look disabled. Only a true result is re-checked on a fresh frame since that branch costs us a full manual race.
     *
     * @param sourceBitmap The frame that the first check is made against.
     * @return Whether the skip button is disabled, or null if the button could not be found.
     */
    private fun isSkipLocked(sourceBitmap: Bitmap): Boolean? {
        val bIsLocked: Boolean? = ButtonViewResults.checkDisabled(game.imageUtils, sourceBitmap)
        if (bIsLocked != true) {
            return bIsLocked
        }

        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
        return ButtonViewResults.checkDisabled(game.imageUtils)
    }

    /**
     * Executes the race with retry logic.
     *
     * @param retryUntilFirst True to keep retrying until 1st place whenever possible (mandatory races and Unity Cup races).
     * @return True if the bot completed the race; otherwise false.
     */
    fun runRaceWithRetries(retryUntilFirst: Boolean = false): Boolean {
        MessageLog.i(TAG, "[RACE] Proceeding to handle the race...")
        game.wait(0.5, skipWaitingForLoading = true)

        // Flag used to prevent us from attempting to select a running style after we've already successfully selected a running style once.
        var bDidSelectRaceStrategy = false
        retriesThisRace = 0
        bRetryUntilFirst = retryUntilFirst

        // Safety counter to prevent infinite loop.
        var loopCount = 0
        val maxLoopCount = 100

        do {
            loopCount++
            if (loopCount > maxLoopCount) {
                MessageLog.w(TAG, "[WARN] runRaceWithRetries:: Safety loop limit reached. Exiting race retry loop...")
                return false
            }

            if (campaign.tryHandleAllDialogs()) {
                continue
            }

            var bitmap: Bitmap = game.imageUtils.getSourceBitmap()

            when {
                // Handle the race prep screen.
                // Check for both of these buttons in case one of them fails detection.
                // This helps prevent us from accidentally clicking the Race button.
                ButtonChangeRunningStyle.check(game.imageUtils, sourceBitmap = bitmap) ||
                    ButtonViewResults.check(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Detected ButtonChangeRunningStyle. Handling race prep screen...")

                    // Always handle race strategy at this screen in case it hasn't been handled yet.
                    // Latch the result so we don't continuously try to handle strategy.
                    if (!bDidSelectRaceStrategy) {
                        bDidSelectRaceStrategy = selectRaceStrategy()
                        // The dialog only opens when a strategy actually had to be set, and it leaves the bitmap above seconds out of date.
                        // Settle and re-capture in that case so the skip button is judged on a current frame instead of a pre-dialog one.
                        if (bHasSetTemporaryRunningStyle) {
                            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
                            bitmap = game.imageUtils.getSourceBitmap()
                        }
                    }

                    when (isSkipLocked(bitmap)) {
                        true -> {
                            if (ButtonRaceManual.click(game.imageUtils)) {
                                MessageLog.i(TAG, "[RACE] Skip is locked. Running race manually.")
                                // Clicking this button triggers connection to server.
                                game.waitForLoading()
                            } else {
                                MessageLog.w(TAG, "[WARN] runRaceWithRetries:: Skip is locked. Failed to click manual race button.")
                            }
                        }

                        false -> {
                            if (ButtonViewResults.click(game.imageUtils, sourceBitmap = bitmap)) {
                                MessageLog.i(TAG, "[RACE] Clicked ViewResults button to skip race.")
                                // Clicking this button triggers connection to server.
                                game.waitForLoading()
                            } else {
                                MessageLog.w(TAG, "[WARN] runRaceWithRetries:: Failed to click ViewResults button to skip race.")
                            }
                        }

                        null -> {
                            MessageLog.w(TAG, "[WARN] runRaceWithRetries:: At Race prep screen but failed to detect ViewResults button.")
                        }
                    }
                }

                ButtonRace.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Dismissed the list of participants.")
                }

                ButtonRaceExclamation.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Dismissed the list of participants.")
                }

                ButtonSkip.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Clicked skip button.")
                }

                // Handle post-race popups (e.g. Rival popups in Trackblazer).
                campaign.hasPostRacePopups() && ButtonClose.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Closed post-race popup.")
                    campaign.onRaceWin()
                    game.wait(1.0)

                    // After closing the popup, check if we can retry a specific race grade.
                    if (canRetryGrade()) {
                        MessageLog.i(TAG, "[RACE] $lastRaceGrade race detected and retry button is available. Retrying...")
                        attemptRaceRetry()
                    } else if (canRetryRival()) {
                        MessageLog.i(TAG, "[RACE] Rival Race retry button is available. Retrying once...")
                        bRetriedCurrentRace = true
                        attemptRaceRetry()
                    } else {
                        MessageLog.i(TAG, "[RACE] No retries remaining or eligible race conditions not met.")
                    }
                }

                canRetryGrade(bitmap) -> {
                    MessageLog.i(TAG, "[RACE] $lastRaceGrade race detected and retry button is available. Retrying...")
                    attemptRaceRetry(bitmap)
                }

                canRetryRival(bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Rival Race retry button is available. Retrying once...")
                    bRetriedCurrentRace = true
                    attemptRaceRetry(bitmap)
                }

                // Retry-until-1st races (mandatory races and Unity Cup) retry whenever possible.
                retryUntilFirst &&
                    ButtonTryAgainAlt.checkDisabled(game.imageUtils, sourceBitmap = bitmap) == false &&
                    !LabelCongratulations.check(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Finished below 1st place. Retrying for the win...")
                    if (ButtonTryAgainAlt.click(game.imageUtils, sourceBitmap = bitmap)) {
                        game.wait(3.0)
                    }
                }

                ButtonNext.check(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Reached race results screen. Exiting race retry loop...")
                    return true
                }

                // Otherwise click to progress through screens.
                else -> {
                    Log.d(TAG, "[DEBUG] runRaceWithRetries:: No components detected. Tapping to progress...")
                    game.tap(350.0, 450.0, taps = 3)
                }
            }
        } while (true)
    }

    /**
     * Mirror a finished race into [RunAnalytics] for the Analytics tab. This is the sole recorder for the career racing flow, so it runs on every
     * campaign whether or not the Smart Race Solver is enabled. Fields the racing flow never resolved are sent empty and simply go uncharted.
     * Unity Cup team races take their own results path and are not covered here.
     *
     * @param won True when the trainee finished 1st.
     * @param isExtra True when this was an extra race rather than a mandatory one.
     */
    private fun recordRaceForAnalytics(won: Boolean, isExtra: Boolean) {
        RunAnalytics.recordRace(
            turn = campaign.date.day,
            name = lastRaceName,
            grade = lastRaceGrade?.name ?: "",
            surface = lastRaceSurface?.name ?: "",
            distance = lastRaceDistance?.name ?: "",
            fans = lastRaceFans,
            won = won,
            mandatory = !isExtra,
        )
    }

    /**
     * Finishes up and confirms the results of the race and its success.
     *
     * @param isExtra Flag to determine the following actions to finish up this mandatory or extra race.
     * @return True if race results were successfully finalized; false otherwise.
     */
    fun finalizeRaceResults(isExtra: Boolean = false): Boolean {
        MessageLog.i(TAG, "[RACE] Now performing cleanup and finishing the race.")

        // Always reset flags after successful race completion, regardless of UI flow.
        firstTimeRacing = false
        clearRacingRequirementFlags()

        // Bot should be at the screen where it shows the final positions of all participants.
        if (!ButtonNext.check(game.imageUtils, tries = 30)) {
            MessageLog.e(TAG, "[ERROR] finalizeRaceResults:: Cannot start the cleanup process for finishing the race. Moving on...")
            SmartRaceSolverIntegration.commitPendingRace(won = false)
            recordRaceForAnalytics(won = false, isExtra = isExtra)
            return false
        }

        // Use the Congratulations banner to confirm 1st place - only real wins should land
        // in the solver's history. Losses drop the pending entry so the next plan sees the
        // unchanged history.
        val firstPlace = LabelCongratulations.check(game.imageUtils)
        MessageLog.i(TAG, "[RACE] Race result detected - 1st place: $firstPlace.")
        SmartRaceSolverIntegration.commitPendingRace(won = firstPlace)
        recordRaceForAnalytics(won = firstPlace, isExtra = isExtra)

        // Max time limit for the while loop to attempt to finalize race results.
        // It really shouldn't ever take this long.
        val startTime: Long = System.currentTimeMillis()
        val maxTimeMs: Long = 30000
        while (System.currentTimeMillis() - startTime < maxTimeMs) {
            if (campaign.tryHandleAllDialogs()) {
                // Don't want to start next iteration too quick since dialogs may still be in process of closing.
                game.wait(0.5, skipWaitingForLoading = true)
                continue
            }

            val bitmap: Bitmap = game.imageUtils.getSourceBitmap()

            when {
                ButtonNext.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Clicked on Next (race results) button.")
                }

                // If we see this button, click it a bunch to ensure we get a valid click.
                // This is also the exit point for this function.
                ButtonNextRaceEnd.click(game.imageUtils, sourceBitmap = bitmap, taps = 5) -> {
                    MessageLog.i(TAG, "[RACE] Clicked on Next (race end) button.")
                    // Clicking this button triggers connection to server.
                    game.waitForLoading()
                    return true
                }

                // Tap on the screen to progress through screens.
                else -> {
                    game.tap(350.0, 750.0, taps = 3)
                }
            }
        }
        return false
    }

    /**
     * Race database lookup using exact and/or fuzzy matching. Returns multiple matches when races share the same name and date but have different fan counts.
     *
     * @param turnNumber The current turn number to match against.
     * @param detectedName The race name detected by OCR.
     * @return A list of [RaceData] objects matching the criteria, or an empty list if no matches found.
     */
    internal fun lookupRaceInDatabase(turnNumber: Int, detectedName: String): ArrayList<RaceData> {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.isAvailable()) {
            MessageLog.e(TAG, "[ERROR] lookupRaceInDatabase:: Database not available for race lookup.")
            settingsManager.close()
            return arrayListOf()
        }

        return try {
            MessageLog.i(TAG, "[RACE] Looking up race for turn $turnNumber with detected name: \"$detectedName\".")

            // Repair the distance token before matching. OCR reads "3000m" as "300Om" often enough to derail both the exact and the fuzzy match.
            val normalizedName = normalizeRaceName(detectedName)
            if (normalizedName != detectedName) {
                MessageLog.i(TAG, "[RACE] Repaired the detected name to: \"$normalizedName\".")
            }

            val database = settingsManager.readableDatabase
            if (database == null) {
                MessageLog.e(TAG, "[ERROR] lookupRaceInDatabase:: Database not available for race lookup.")
                return arrayListOf()
            }

            val matches = arrayListOf<RaceData>()

            // Do exact matching based on the info gathered.
            val exactCursor =
                database.query(
                    TABLE_RACES,
                    arrayOf(
                        RACES_COLUMN_NAME,
                        RACES_COLUMN_GRADE,
                        RACES_COLUMN_FANS,
                        RACES_COLUMN_NAME_FORMATTED,
                        RACES_COLUMN_TRACK_SURFACE,
                        RACES_COLUMN_TRACK_DISTANCE,
                        RACES_COLUMN_TURN_NUMBER,
                    ),
                    "$RACES_COLUMN_TURN_NUMBER = ? AND $RACES_COLUMN_NAME_FORMATTED = ?",
                    arrayOf(turnNumber.toString(), normalizedName),
                    null,
                    null,
                    null,
                )

            // Collect all exact matches (may have different fan counts).
            if (exactCursor.moveToFirst()) {
                do {
                    val race =
                        RaceData(
                            name = exactCursor.getString(0),
                            grade = exactCursor.getString(1),
                            fans = exactCursor.getInt(2),
                            nameFormatted = exactCursor.getString(3),
                            trackSurface = exactCursor.getString(4),
                            trackDistance = exactCursor.getString(5),
                            turnNumber = exactCursor.getInt(6),
                        )
                    matches.add(race)
                } while (exactCursor.moveToNext())

                exactCursor.close()

                if (matches.size == 1) {
                    MessageLog.i(TAG, "[RACE] Found exact match: \"${matches[0].name}\" AKA \"${matches[0].nameFormatted}\" (Fans: ${matches[0].fans}).")
                } else {
                    MessageLog.i(TAG, "[RACE] Found ${matches.size} exact matches with same name but different fan counts:")
                    matches.forEach { race ->
                        MessageLog.i(TAG, "[RACE]     - \"${race.name}\" (Fans: ${race.fans})")
                    }
                }
                return matches
            }
            exactCursor.close()

            // Otherwise, do fuzzy matching to find matches using Jaro-Winkler.
            val fuzzyCursor =
                database.query(
                    TABLE_RACES,
                    arrayOf(
                        RACES_COLUMN_NAME,
                        RACES_COLUMN_GRADE,
                        RACES_COLUMN_FANS,
                        RACES_COLUMN_NAME_FORMATTED,
                        RACES_COLUMN_TRACK_SURFACE,
                        RACES_COLUMN_TRACK_DISTANCE,
                        RACES_COLUMN_TURN_NUMBER,
                    ),
                    "$RACES_COLUMN_TURN_NUMBER = ?",
                    arrayOf(turnNumber.toString()),
                    null,
                    null,
                    null,
                )

            if (!fuzzyCursor.moveToFirst()) {
                fuzzyCursor.close()
                MessageLog.i(TAG, "[RACE] No match found for turn $turnNumber with name \"$detectedName\".")
                return arrayListOf()
            }

            val similarityService = StringSimilarityServiceImpl(JaroWinklerStrategy())
            var bestScore = 0.0
            val fuzzyMatches = mutableListOf<Pair<RaceData, Double>>()

            do {
                val nameFormatted = fuzzyCursor.getString(3)
                val similarity = similarityService.score(normalizedName, nameFormatted)

                if (similarity >= SIMILARITY_THRESHOLD) {
                    val race =
                        RaceData(
                            name = fuzzyCursor.getString(0),
                            grade = fuzzyCursor.getString(1),
                            fans = fuzzyCursor.getInt(2),
                            nameFormatted = nameFormatted,
                            trackSurface = fuzzyCursor.getString(4),
                            trackDistance = fuzzyCursor.getString(5),
                            turnNumber = fuzzyCursor.getInt(6),
                        )

                    // Jaro-Winkler scores races that share a track name and most tokens very highly.
                    // A candidate that contradicts the detected distance must be dropped.
                    if (!matchesDetectedDistance(normalizedName, race.trackDistance)) {
                        MessageLog.i(
                            TAG,
                            "[RACE] Rejected fuzzy match \"${race.name}\" AKA \"$nameFormatted\": its distance (${race.trackDistance}) contradicts the detected name.",
                        )
                        continue
                    }

                    fuzzyMatches.add(Pair(race, similarity))
                    if (similarity > bestScore) bestScore = similarity
                    if (game.debugMode) {
                        MessageLog.d(
                            TAG,
                            "[DEBUG] lookupRaceInDatabase:: Fuzzy match candidate: \"${race.name}\" AKA \"$nameFormatted\" with similarity ${
                                game.decimalFormat.format(
                                    similarity,
                                )
                            } (Fans: ${race.fans}).",
                        )
                    } else {
                        Log.d(
                            TAG,
                            "[DEBUG] lookupRaceInDatabase:: Fuzzy match candidate: \"${race.name}\" AKA \"$nameFormatted\" with similarity ${
                                game.decimalFormat.format(
                                    similarity,
                                )
                            } (Fans: ${race.fans}).",
                        )
                    }
                }
            } while (fuzzyCursor.moveToNext())

            fuzzyCursor.close()

            // Return all matches with the best similarity score.
            val bestMatches = ArrayList(fuzzyMatches.filter { it.second == bestScore }.map { it.first })

            if (bestMatches.isNotEmpty()) {
                if (bestMatches.size == 1) {
                    MessageLog.i(
                        TAG,
                        "[RACE] Found fuzzy match: \"${bestMatches[0].name}\" AKA \"${bestMatches[0].nameFormatted}\" with similarity ${
                            game.decimalFormat.format(
                                bestScore,
                            )
                        } (Fans: ${bestMatches[0].fans}).",
                    )
                } else {
                    MessageLog.i(TAG, "[RACE] Found ${bestMatches.size} fuzzy matches with similarity ${game.decimalFormat.format(bestScore)} but different fan counts:")
                    bestMatches.forEach { race ->
                        MessageLog.i(TAG, "[RACE]     - \"${race.name}\" (Fans: ${race.fans})")
                    }
                }
                return bestMatches
            }

            MessageLog.i(TAG, "[RACE] No match found for turn $turnNumber with name \"$detectedName\".")
            arrayListOf()
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] lookupRaceInDatabase:: Error looking up race: ${e.message}.")
            arrayListOf()
        } finally {
            settingsManager.close()
        }
    }

    /**
     * Checks if any G1 races exist at the specified turn number in the database.
     *
     * @param turnNumber The turn number to check for G1 races.
     * @return True if at least one G1 race exists at the specified turn, false otherwise.
     */
    internal fun hasG1RacesAtTurn(turnNumber: Int): Boolean {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.isAvailable()) {
            MessageLog.e(TAG, "[ERROR] hasG1RacesAtTurn:: Database not available for G1 race check.")
            settingsManager.close()
            return false
        }

        return try {
            val database = settingsManager.readableDatabase
            if (database == null) {
                MessageLog.e(TAG, "[ERROR] hasG1RacesAtTurn:: Database is null for G1 race check.")
                return false
            }

            val cursor =
                database.query(
                    TABLE_RACES,
                    arrayOf(RACES_COLUMN_GRADE),
                    "$RACES_COLUMN_TURN_NUMBER = ? AND $RACES_COLUMN_GRADE = ?",
                    arrayOf(turnNumber.toString(), "G1"),
                    null,
                    null,
                    null,
                )

            val hasG1 = cursor.count > 0
            cursor.close()

            hasG1
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] hasG1RacesAtTurn:: Error checking for G1 races: ${e.message}")
            false
        } finally {
            settingsManager.close()
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Orchestrates the handling of various race events, including mandatory and extra races.
     *
     * @param isScheduledRace True if a race is currently scheduled, false otherwise.
     * @return True if a race event was handled successfully, false otherwise.
     */
    fun handleRaceEvents(isScheduledRace: Boolean = false): Boolean {
        // If the bot is already at the Race List screen and a race has already been selected,
        // we should not reset the flags as they may have been set somewhere else.
        if (!ButtonRace.check(game.imageUtils)) {
            lastRaceGrade = null
            lastRaceFans = 0
            lastRaceDistance = null
            lastRaceName = ""
            lastRaceSurface = null
            lastRaceIsRival = false
            bRetriedCurrentRace = false
        }
        MessageLog.v(TAG, "\n********************")
        MessageLog.v(TAG, "[RACE] Starting Racing process on ${campaign.date}.")

        // If the races button exists AND is disabled, we can exit early since we know that we're at the home screen and the bot cannot race.
        if (campaign.racesButton.checkDisabled(game.imageUtils) == true) {
            MessageLog.v(TAG, "[RACE] Races are locked. Canceling the racing process and doing something else.")
            clearRacingRequirementFlags()
            MessageLog.v(TAG, "********************")
            return false
        }

        // If there are no races available, cancel the racing process.
        if (LabelThereAreNoRacesToCompeteIn.check(game.imageUtils)) {
            MessageLog.v(TAG, "[RACE] There are no races to compete in. Canceling the racing process and doing something else.")
            MessageLog.v(TAG, "********************")
            // Clear requirement flags since we cannot proceed with racing.
            clearRacingRequirementFlags()
            return false
        }

        // First, check if there is a mandatory or an extra race available. If so, head into the Race Selection screen.
        // Note: If there is a mandatory race, the bot would be on the Home screen.
        // Otherwise, it would have found itself at the Race Selection screen already (by way of the insufficient fans popup).
        val loc: Point? = IconRaceDayRibbon.find(game.imageUtils).first
        if (loc != null) {
            tapRaceDayButton(loc)
            game.wait(0.5, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
            return handleMandatoryRace()
        } else if (!campaign.trainee.bHasCompletedMaidenRace && !isScheduledRace && campaign.racesButton.click(game.imageUtils)) {
            game.wait(1.0, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
            return handleMaidenRace()
        } else if ((!campaign.date.bIsPreDebut && campaign.racesButton.click(game.imageUtils)) || isScheduledRace) {
            // SRS-planned races bypass the consecutive-race limit. SRS owns the schedule, so its picks must not be gated by the local limit.
            val isSrsScheduledRace =
                enableSmartRaceSolver &&
                    !enableForceRacing &&
                    SmartRaceSolverIntegration.peekRaceKeyForTurn(currentTurn = campaign.date.day, scenario = game.scenario) != null
            var overrideIgnore = false
            if (isScheduledRace || hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement || isSrsScheduledRace) {
                MessageLog.v(TAG, "[RACE] Racing requirement is active. Ignoring consecutive race warning.")
                overrideIgnore = true
            }
            game.wait(0.5, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            val result: DialogHandlerResult =
                campaign.handleDialogs(
                    args =
                        mapOf(
                            "overrideIgnoreConsecutiveRaceWarning" to overrideIgnore,
                            "isScheduledRace" to isScheduledRace,
                            "isMandatoryRace" to (isScheduledRace || hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement),
                        ),
                )
            if (result is DialogHandlerResult.Handled &&
                result.dialog.name == "consecutive_race_warning" &&
                !(overrideIgnore || enableForceRacing || ignoreConsecutiveRaceWarning)
            ) {
                MessageLog.v(TAG, "[RACE] Consecutive race warning but conditions dictate to not race. Skipping...")
                return false
            }
            return handleExtraRace(isScheduledRace = isScheduledRace)
        } else if (ButtonRace.check(game.imageUtils)) {
            MessageLog.v(TAG, "[RACE] The bot is already at the Race List screen and a race has already been selected.")
            return handleSelectedRace()
        } else if (ButtonChangeRunningStyle.check(game.imageUtils)) {
            MessageLog.i(TAG, "[RACE] The bot is currently sitting on the race screen. Most likely here for a scheduled race.")
            handleStandaloneRace()
            return true
        }

        // Clear requirement flags if no race selection buttons were found.
        clearRacingRequirementFlags()
        MessageLog.v(TAG, "********************")
        return false
    }

    /** The entry point for handling standalone races if the user started the bot on the Racing screen. */
    fun handleStandaloneRace() {
        MessageLog.v(TAG, "\n********************")
        MessageLog.v(TAG, "[RACE] Starting Standalone Racing process...")

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults()

        MessageLog.v(TAG, "[RACE] Racing process for Standalone Race is completed. Grade: ${lastRaceGrade ?: "Standalone"}")
        MessageLog.v(TAG, "********************")
    }

    /**
     * Handles a race that has already been selected, usually via custom racing logic.
     *
     * @return True if the race was completed successfully, false otherwise.
     */
    private fun handleSelectedRace(): Boolean {
        MessageLog.v(TAG, "[RACE] Starting process for a race that is already selected.")

        // Confirm the selection and the resultant popup and then wait for the game to load.
        ButtonRace.click(game.imageUtils, tries = 30)
        game.wait(1.0)
        ButtonRace.click(game.imageUtils, tries = 10)
        game.wait(2.0)

        game.waitForLoading()

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults(isExtra = true)

        MessageLog.v(TAG, "[RACE] Racing process for already selected race is completed. Grade: ${lastRaceGrade ?: "OP"}")
        MessageLog.v(TAG, "********************")
        return true
    }

    /**
     * Taps the race-day button on the main screen to open the race list.
     *
     * Prefers the scenario's own race-day button since its size and position vary, such as Grand Live rendering a smaller one in a
     * Skills / Race / Lessons row. Falls back to tapping 100px below the race day ribbon, which is not clickable itself.
     *
     * @param ribbonLocation The already-found location of the race day ribbon. Defaults to null to look for it here.
     */
    private fun tapRaceDayButton(ribbonLocation: Point? = null) {
        if (campaign.raceDayButton.click(game.imageUtils, tries = 3)) {
            return
        }

        val loc: Point = ribbonLocation ?: IconRaceDayRibbon.find(game.imageUtils).first ?: return
        game.tap(loc.x, loc.y + 100, IconRaceDayRibbon.template.path, ignoreWaiting = true)
    }

    /**
     * Handles the process for a mandatory race.
     *
     * @return True if the mandatory race process was completed successfully, false otherwise.
     */
    private fun handleMandatoryRace(): Boolean {
        MessageLog.v(TAG, "[RACE] Starting process for handling a mandatory race.")

        if (enableStopOnMandatoryRace) {
            MessageLog.v(TAG, "********************")
            detectedMandatoryRaceCheck = true
            return false
        }

        // The caller already tapped the race-day button, but that tap gets swallowed while the turn's intro animation is still playing.
        // Everything below reads the race list, so re-tap until it opens instead of running the whole flow against the main screen.
        for (attempt in 1..3) {
            if (ButtonRaceListFullStats.check(game.imageUtils, tries = 10)) break
            MessageLog.i(TAG, "[RACE] The race list has not opened yet. Tapping the race-day button again.")
            tapRaceDayButton()
        }

        // For Finale races, manually set the grade and fans.
        if (campaign.date.bIsFinaleSeason && (campaign.date.day == 73 || campaign.date.day == 74 || campaign.date.day == 75)) {
            lastRaceGrade = RaceGrade.FINALE
            lastRaceFans = if (campaign.date.day == 75) 30000 else 10000
            // Distance is unknown for Finale races; per-distance strategy will fall back to blanket.
        }

        // OCR the mandatory race name via the on-screen double-star prediction icon to resolve its name, grade, surface, and distance. Per-distance
        // strategy needs the distance to avoid falling back to the user's blanket strategy, and the Analytics tab needs the rest to chart the race
        // rather than just counting it. Skipped once the details are already known so the OCR + DB lookup only runs when it would add something.
        if (lastRaceDistance == null) {
            val predictionLocations = IconRaceListPredictionDoubleStar.findAll(game.imageUtils)
            if (predictionLocations.isNotEmpty()) {
                val raceName = game.imageUtils.extractRaceName(predictionLocations[0])
                val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                if (raceDataList.isNotEmpty()) {
                    val raceData = raceDataList[0]
                    lastRaceDistance = raceData.trackDistance
                    lastRaceName = raceData.name
                    lastRaceSurface = raceData.trackSurface
                    // Preserve any grade/fans already set above (e.g. by the Finale block).
                    if (lastRaceGrade == null) lastRaceGrade = raceData.grade
                    if (lastRaceFans == 0) lastRaceFans = raceData.fans
                    MessageLog.i(TAG, "[RACE] Detected mandatory race \"${raceData.name}\" (Grade: ${raceData.grade}, Distance: ${raceData.trackDistance}).")
                }
            } else {
                MessageLog.i(TAG, "[RACE] No double-star prediction found on mandatory race screen. Per-distance strategy will fall back to blanket and the race goes uncharted.")
            }
        }

        // Let the campaign handle any pre-race logic (e.g. using race items in Trackblazer).
        campaign.onScheduledRacePrepScreen()

        // If there is a popup warning about racing too many times, confirm the popup to continue as this is a mandatory race.
        if (ButtonOk.click(game.imageUtils, region = game.imageUtils.regionMiddle)) {
            game.wait(2.0)
        }

        MessageLog.v(TAG, "[RACE] Confirming the mandatory race selection.")
        // This is the Race button on the race list, not the race-day button on the main screen, which the caller already tapped to get here.
        if (!ButtonRace.click(game.imageUtils, tries = 10)) {
            MessageLog.w(TAG, "[WARN] handleMandatoryRace:: Could not tap the Race button on the race list screen.")
        }
        game.wait(1.0)
        MessageLog.i(TAG, "[RACE] Confirming any popup from the mandatory race selection.")
        ButtonRace.click(game.imageUtils, tries = 3)
        game.wait(2.0)

        game.waitForLoading()

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries(retryUntilFirst = true)
        finalizeRaceResults()

        MessageLog.v(TAG, "[RACE] Racing process for Mandatory Race is completed. Grade: ${lastRaceGrade ?: "Mandatory"}")
        MessageLog.v(TAG, "********************")
        return true
    }

    /**
     * Searches for and selects a maiden race from the race list.
     *
     * @return True if a maiden race was successfully selected, false otherwise.
     */
    private fun selectMaidenRace(): Boolean {
        // Get the bounding region of the race list.
        val raceListTopLeftBitmap: Bitmap? = IconScrollListTopLeft.template.getBitmap(game.imageUtils)
        if (raceListTopLeftBitmap == null) {
            MessageLog.e(TAG, "[ERROR] selectMaidenRace:: Failed to load IconScrollListTopLeft bitmap.")
            return false
        }

        val raceListBottomRightBitmap: Bitmap? = IconScrollListBottomRight.template.getBitmap(game.imageUtils)
        if (raceListBottomRightBitmap == null) {
            MessageLog.e(TAG, "[ERROR] selectMaidenRace:: Failed to load IconScrollListBottomRight bitmap.")
            return false
        }

        val raceListTopLeft: Point? = IconScrollListTopLeft.find(game.imageUtils).first
        if (raceListTopLeft == null) {
            MessageLog.e(TAG, "[ERROR] selectMaidenRace:: Failed to find top left corner of race list.")
            return false
        }
        val raceListBottomRight: Point? = IconScrollListBottomRight.find(game.imageUtils).first
        if (raceListBottomRight == null) {
            MessageLog.e(TAG, "[ERROR] selectMaidenRace:: Failed to find bottom right corner of race list.")
            return false
        }
        val x0 = (raceListTopLeft.x - (raceListTopLeftBitmap.width / 2)).toInt()
        val y0 = (raceListTopLeft.y - (raceListTopLeftBitmap.height / 2)).toInt()
        val x1 = (raceListBottomRight.x + (raceListBottomRightBitmap.width / 2)).toInt()
        val y1 = (raceListBottomRight.y + (raceListBottomRightBitmap.height / 2)).toInt()
        val bboxRaceList =
            BoundingBox(
                x = x0,
                y = y0,
                w = x1 - x0,
                h = y1 - y0,
            )

        // Smaller region used to detect double star icons in the race list.
        val bboxRaceListDoubleStars =
            BoundingBox(
                x = game.imageUtils.relX(bboxRaceList.x.toDouble(), 845),
                y = bboxRaceList.y,
                w = game.imageUtils.relWidth(45),
                h = bboxRaceList.h,
            )

        val bboxScrollBar =
            BoundingBox(
                x = game.imageUtils.relX(bboxRaceList.x.toDouble(), 1034),
                y = bboxRaceList.y,
                w = 10,
                h = bboxRaceList.h,
            )

        // The selected race in the race list has green brackets that overlap the scroll bar a bit.
        // Thus, we are really only interested in a single column of pixels on the right side of the scroll bar when checking for changes.
        val bboxScrollBarSingleColumn =
            BoundingBox(
                // Give ourselves a few pixels of buffer.
                x = bboxScrollBar.x + bboxScrollBar.w - 3,
                y = bboxScrollBar.y,
                w = 1,
                h = bboxScrollBar.h,
            )

        // Scroll to top of list.
        game.gestureUtils.swipe(
            (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
            (bboxRaceList.y + (bboxRaceList.h / 2)).toFloat(),
            (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
            // High value here ensures we go all the way to top of list.
            (bboxRaceList.y + (bboxRaceList.h * 10)).toFloat(),
        )
        game.wait(0.1, skipWaitingForLoading = true)
        // Tap to prevent overscrolling. This location shouldn't select any races.
        game.tap(
            game.imageUtils.relX(bboxRaceList.x.toDouble(), 15).toDouble(),
            game.imageUtils.relY(bboxRaceList.y.toDouble(), 15).toDouble(),
            ignoreWaiting = true,
        )
        // Small delay for scrolling to stop.
        game.wait(0.1, skipWaitingForLoading = true)

        var bitmap: Bitmap
        var prevScrollBarBitmap: Bitmap? = null

        // Max time limit for the while loop to search for a valid race.
        val startTime: Long = System.currentTimeMillis()
        val maxTimeMs: Long = 10000

        while (System.currentTimeMillis() - startTime < maxTimeMs) {
            bitmap = game.imageUtils.getSourceBitmap()
            val scrollBarBitmap: Bitmap? =
                game.imageUtils.createSafeBitmap(
                    bitmap,
                    bboxScrollBarSingleColumn.x,
                    bboxScrollBarSingleColumn.y,
                    bboxScrollBarSingleColumn.w,
                    bboxScrollBarSingleColumn.h,
                    "race list scrollbar right half bitmap",
                )
            if (scrollBarBitmap == null) {
                MessageLog.e(TAG, "[ERROR] selectMaidenRace:: Failed to createSafeBitmap for scrollbar.")
                return false
            }

            // If after scrolling the scrollbar hasn't changed, that means we've reached the end of the list.
            if (prevScrollBarBitmap != null && scrollBarBitmap.sameAs(prevScrollBarBitmap)) {
                Log.d(TAG, "[DEBUG] selectMaidenRace:: Scrollbar has not changed, reached end of list.")
                return false
            }

            prevScrollBarBitmap = scrollBarBitmap

            val locations: ArrayList<Point> =
                IconRaceListPredictionDoubleStar.findAll(
                    game.imageUtils,
                    region = bboxRaceListDoubleStars.toIntArray(),
                    confidence = 0.0,
                )

            if (!locations.isEmpty()) {
                Log.d(TAG, "[DEBUG] selectMaidenRace:: Found double predictions at (${locations.first().x}, ${locations.first().y}).")
                game.tap(
                    locations.first().x,
                    locations.first().y,
                    IconRaceListPredictionDoubleStar.template.path,
                    ignoreWaiting = true,
                )
                return true
            }

            // Longer swipe duration prevents overscrolling. Swipe up approximately one entry in list.
            // Each entry is roughly 200px tall and there is a 30px gap between entries.
            // For some reason with shorter durations, it overscroll too much.
            // Also with longer durations, the amount scrolled is less than the pixel amount specified so we use 500px to counter this.
            game.gestureUtils.swipe(
                (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
                (bboxRaceList.y + (bboxRaceList.h / 2)).toFloat(),
                (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
                ((bboxRaceList.y + (bboxRaceList.h / 2)) - 500).toFloat(),
                duration = 500,
            )
            game.wait(0.1, skipWaitingForLoading = true)
            // Tap to prevent overscrolling. This location shouldn't select any races.
            game.tap(
                game.imageUtils.relX(bboxRaceList.x.toDouble(), 15).toDouble(),
                game.imageUtils.relY(bboxRaceList.y.toDouble(), 15).toDouble(),
                ignoreWaiting = true,
            )
            game.wait(0.5, skipWaitingForLoading = true)
        }

        return false
    }

    /**
     * Handles the process for a maiden race.
     *
     * @return True if the maiden race process was completed successfully, false otherwise.
     */
    private fun handleMaidenRace(): Boolean {
        MessageLog.v(TAG, "[RACE] Starting process for handling a maiden race.")

        if (!ButtonRaceListFullStats.check(game.imageUtils, tries = 30)) {
            MessageLog.e(TAG, "[ERROR] handleMaidenRace:: Not at race list screen. Aborting racing...")
            // Clear requirement flags since we cannot proceed with racing.
            clearRacingRequirementFlags()
            return false
        }

        campaign.bHasCheckedForMaidenRaceToday = true
        if (IconRaceListMaidenPill.check(game.imageUtils)) {
            MessageLog.i(TAG, "[RACE] Detected maiden races in race list.")
            if (selectMaidenRace()) {
                MessageLog.v(TAG, "[RACE] Found maiden race with good aptitudes. Racing...")
            } else {
                MessageLog.v(TAG, "[RACE] Could not find any maiden races with good aptitudes. Aborting racing...")
                ButtonBack.click(game.imageUtils)
                return false
            }
        } else {
            // No maiden races available on this day. Check for extra races instead.
            MessageLog.v(TAG, "[RACE] No maiden races available on this day. Checking for extra races instead...")
            return handleExtraRace()
        }

        // Confirm the selection and the resultant popup and then wait for the game to load.
        ButtonRace.click(game.imageUtils)
        game.wait(game.dialogWaitDelay)
        val result: DialogHandlerResult = campaign.handleDialogs()
        if (result !is DialogHandlerResult.Handled || result.dialog.name != "race_details") {
            Log.w(TAG, "[WARN] handleMaidenRace:: Failed to handle dialogs. Aborting racing...")
            return false
        }
        game.wait(2.0)

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults(isExtra = true)

        MessageLog.v(TAG, "[RACE] Racing process for Maiden Race is completed. Grade: ${lastRaceGrade ?: "Maiden"}")
        MessageLog.v(TAG, "********************")
        return true
    }

    /**
     * Handles the process for an extra race, including smart and standard racing logic.
     *
     * @param isScheduledRace True if an extra race is currently scheduled, false otherwise.
     * @return True if the extra race process was completed successfully, false otherwise.
     */
    private fun handleExtraRace(isScheduledRace: Boolean = false): Boolean {
        MessageLog.v(TAG, "[RACE] Starting process for handling a extra race${if (isScheduledRace) " (scheduled)" else ""}.")

        // If there is a scheduled race pending, proceed to run it immediately.
        if (!isScheduledRace) {
            // Check for the consecutive race dialog before proceeding.
            // SRS-planned races bypass the consecutive-race limit because the solver owns the racing schedule.
            val isSrsScheduledRace =
                enableSmartRaceSolver &&
                    !enableForceRacing &&
                    SmartRaceSolverIntegration.peekRaceKeyForTurn(currentTurn = campaign.date.day, scenario = game.scenario) != null
            val overrideIgnore: Boolean = isScheduledRace || enableForceRacing || hasInsufficientGoalRacePtsRequirement || isSrsScheduledRace
            val result: DialogHandlerResult = campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to overrideIgnore))
            if (result is DialogHandlerResult.Handled &&
                result.dialog.name == "consecutive_race_warning" &&
                !overrideIgnore
            ) {
                MessageLog.v(TAG, "[RACE] Consecutive race warning but conditions dictate to not race. Skipping...")
                return false
            }

            // Check for the existence of the extra race list button.
            val statusLocation = ButtonRaceListFullStats.find(game.imageUtils, tries = 30).first
            if (statusLocation == null) {
                MessageLog.e(TAG, "[ERROR] handleExtraRace:: Unable to determine existence of list of extra races. Canceling the racing process and doing something else.")
                // Clear requirement flags since we cannot proceed with racing.
                clearRacingRequirementFlags()
                MessageLog.v(TAG, "********************")
                return false
            }

            val maxCount = LabelRaceSelectionFans.findAll(game.imageUtils).size
            if (maxCount == 0) {
                // If there is a fan/trophy/goal pts requirement but no races available, reset the flags and proceed with training to advance the day.
                if (hasFanRequirement || hasTrophyRequirement || hasInsufficientGoalRacePtsRequirement) {
                    MessageLog.v(TAG, "[RACE] Requirement detected but no extra races available. Clearing requirement flags and proceeding with training to advance the day.")
                } else {
                    MessageLog.e(TAG, "[ERROR] handleExtraRace:: Was unable to find any extra races to select. Canceling the racing process and doing something else.")
                }
                // Always clear requirement flags when no races are available.
                clearRacingRequirementFlags()
                MessageLog.v(TAG, "********************")
                return false
            } else {
                MessageLog.i(TAG, "[RACE] There are $maxCount extra race options currently on screen.")
            }

            if (hasFanRequirement) MessageLog.v(TAG, "[RACE] Fan requirement criteria detected. This race must be completed to meet the requirement.")
            if (hasInsufficientGoalRacePtsRequirement) MessageLog.v(TAG, "[RACE] Goal race result pts requirement criteria detected. This race must be completed to meet the requirement.")
            if (hasTrophyRequirement) {
                when {
                    hasPreOpOrAboveRequirement -> {
                        MessageLog.v(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria detected. Any race can be selected to meet the requirement.")
                    }

                    hasG3OrAboveRequirement -> {
                        MessageLog.v(TAG, "[RACE] Trophy requirement with G3 or above criteria detected. Any race can be selected to meet the requirement.")
                    }

                    else -> {
                        MessageLog.v(TAG, "[RACE] Trophy requirement criteria detected. Only G1 races will be selected to meet the requirement.")
                    }
                }
            }

            // Determine whether to use smart racing with user-selected races or standard racing. Any mandatory requirement (fan, trophy, or goal pts) uses
            // standard racing since only it filters to and races the qualifying race - the solver would drop the requirement.
            val useSmartRacing =
                shouldUseSmartRacing(
                    hasFanRequirement = hasFanRequirement,
                    hasTrophyRequirement = hasTrophyRequirement,
                    hasInsufficientGoalRacePtsRequirement = hasInsufficientGoalRacePtsRequirement,
                    year = campaign.date.year,
                    enableSmartRaceSolver = enableSmartRaceSolver,
                    enableForceRacing = enableForceRacing,
                )

            val success =
                if (useSmartRacing) {
                    MessageLog.v(TAG, "[RACE] Using Smart Race Solver for Year ${campaign.date.year}.")
                    processSmartRacing()
                } else {
                    if (enableSmartRaceSolver && !hasFanRequirement && !hasTrophyRequirement) {
                        MessageLog.i(TAG, "[RACE] Smart Race Solver conditions not met, using traditional racing logic...")
                        if (campaign.date.year == DateYear.JUNIOR) {
                            MessageLog.i(TAG, "[RACE]   - It is currently the Junior Year.")
                        } else if (enableForceRacing) {
                            MessageLog.i(TAG, "[RACE]   - enableForceRacing is true")
                        }
                    }

                    processStandardRacing()
                }

            if (!success) {
                // Clear requirement flags if race selection failed.
                Log.w(TAG, "[WARN] handleExtraRace:: Failed to select a race. Aborting racing...")
                clearRacingRequirementFlags()
                return false
            }
        } else if (isScheduledRace) {
            // Attempt to update the date if the current day is 1. This handles cases where the bot starts at the race
            // prep screen and enters the race list directly via a scheduled race dialog, bypassing the home
            // screen's date detection.
            if (campaign.date.day == 1) {
                campaign.updateDate(isOnMainScreen = false)
            }

            // Detect the grade of the scheduled race from double predictions on the race list.
            val doublePredictionLocations = IconRaceListPredictionDoubleStar.findAll(game.imageUtils)
            if (doublePredictionLocations.isNotEmpty()) {
                val raceName = game.imageUtils.extractRaceName(doublePredictionLocations[0])
                val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                if (raceDataList.isNotEmpty()) {
                    lastRaceGrade = raceDataList[0].grade
                    lastRaceFans = raceDataList[0].fans
                    lastRaceDistance = raceDataList[0].trackDistance
                    lastRaceName = raceDataList[0].name
                    lastRaceSurface = raceDataList[0].trackSurface
                    MessageLog.i(TAG, "[RACE] Detected scheduled race grade: $lastRaceGrade.")

                    // Stage the scheduled race so finalizeRaceResults() records its win/loss in the solver's history. Without this, scheduled
                    // races run via the in-game agenda never get committed and render as blank Race History cells in the Remote Log Viewer.
                    if (enableSmartRaceSolver) {
                        SmartRaceSolverIntegration.markPendingRace(
                            raceKey = raceDataList[0].name,
                            raceName = raceDataList[0].name,
                            classYear = campaign.date.year.name,
                            turnNumber = campaign.date.day,
                        )
                    }
                }
            }

            // Let the campaign handle any necessary logic on the scheduled race's Race Prep screen (e.g. using race items).
            campaign.onScheduledRacePrepScreen()

            MessageLog.v(TAG, "[RACE] Confirming the scheduled race dialog...")
            ButtonRace.click(game.imageUtils, tries = 30)
            game.wait(game.dialogWaitDelay)
        }

        // Confirm the selection and the resultant popup and then wait for the game to load.
        ButtonRace.click(game.imageUtils, tries = 30)
        game.wait(1.0)
        ButtonRace.click(game.imageUtils, tries = 10)
        game.wait(2.0)

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults(isExtra = true)

        MessageLog.v(TAG, "[RACE] Racing process for Extra Race${if (isScheduledRace) " (scheduled) " else " "}is completed. Grade: ${lastRaceGrade ?: "OP"}")
        MessageLog.v(TAG, "********************")
        return true
    }

    /**
     * Handles extra races by delegating to the [SmartRaceSolverIntegration].
     *
     * Detects on-screen double-star race predictions, matches them via [lookupRaceInDatabase],
     * and asks the solver to pick one. Returns false if the solver is disabled, no candidates
     * match, or no schedule pick is found on screen - callers fall through to standard racing.
     *
     * @return True if a race was successfully selected and ready to run, false otherwise.
     */
    private fun processSmartRacing(): Boolean {
        MessageLog.v(TAG, "[RACE] Using Smart Race Solver logic.")
        campaign.updateDate()

        val doublePredictionLocations = IconRaceListPredictionDoubleStar.findAll(game.imageUtils)
        MessageLog.i(TAG, "[RACE] Found ${doublePredictionLocations.size} double-star prediction locations.")
        if (doublePredictionLocations.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No double-star predictions found. Canceling racing process.")
            return false
        }

        // Peek the Solver's planned race up front so we can short-circuit OCR as soon as we find it on screen, instead of OCR-scanning every candidate first.
        val plannedKey =
            SmartRaceSolverIntegration.peekRaceKeyForTurn(currentTurn = campaign.date.day, scenario = game.scenario)

        if (plannedKey != null) {
            MessageLog.i(TAG, "[RACE] Smart Race Solver wants \"$plannedKey\" for turn ${campaign.date.day} - scanning until matched.")
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            for (location in doublePredictionLocations) {
                val raceName = game.imageUtils.extractRaceName(location)
                val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                if (raceDataList.isEmpty()) {
                    MessageLog.i(TAG, "[RACE] ✗ No match found in database for \"$raceName\".")
                    continue
                }
                raceDataList.forEach { raceData ->
                    MessageLog.i(TAG, "[RACE] ✓ Matched in database: ${raceData.name} (Grade: ${raceData.grade}, Fans: ${raceData.fans}, Track Surface: ${raceData.trackSurface}).")
                }
                val match = raceDataList.firstOrNull { SmartRaceSolverIntegration.isRaceKeyMatch(it, plannedKey) }
                if (match != null) {
                    // Two races can share this row's track string (e.g. Oaks vs Derby). Confirm the planned race by its OCR'd fan count before tapping.
                    if (raceDataList.size > 1) {
                        val rowFans = game.imageUtils.determineExtraRaceFans(location, sourceBitmap, forceRacing = true).fans
                        if (!SmartRaceSolverIntegration.isCorrectCollisionRow(match.fans, rowFans)) {
                            MessageLog.i(TAG, "[RACE] Same-track sibling at this row (fans=$rowFans != planned ${match.fans}). Continuing scan for \"${match.name}\".")
                            continue
                        }
                        if (rowFans == -1) {
                            MessageLog.w(TAG, "[WARN] processSmartRacing:: Same-track collision but fan OCR failed; tapping scanned row for \"${match.name}\".")
                        } else {
                            MessageLog.i(TAG, "[RACE] Same-track collision resolved by fans=$rowFans for \"${match.name}\".")
                        }
                    }
                    MessageLog.v(TAG, "[RACE] Smart Race Solver selected \"${match.name}\". Selecting it.")
                    SmartRaceSolverIntegration.markPendingRace(
                        raceKey = match.name,
                        raceName = match.name,
                        classYear = campaign.date.year.name,
                        turnNumber = campaign.date.day,
                    )
                    game.tap(location.x, location.y, IconRaceListPredictionDoubleStar.template.path, ignoreWaiting = true)
                    lastRaceGrade = match.grade
                    lastRaceFans = match.fans
                    lastRaceDistance = match.trackDistance
                    lastRaceName = match.name
                    lastRaceSurface = match.trackSurface
                    lastRaceIsRival = match.isRival
                    return true
                }
            }
            MessageLog.i(TAG, "[RACE] Smart Race Solver's planned race \"$plannedKey\" was not found among on-screen candidates. Canceling racing process.")
            return false
        }

        // Fallback when the solver had no plan or chose Train/Rest: scan every on-screen
        // race and let pickRace decide.
        val currentRaces =
            doublePredictionLocations.flatMap { location ->
                val raceName = game.imageUtils.extractRaceName(location)
                val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                if (raceDataList.isNotEmpty()) {
                    raceDataList.forEach { raceData ->
                        MessageLog.i(TAG, "[RACE] ✓ Matched in database: ${raceData.name} (Grade: ${raceData.grade}, Fans: ${raceData.fans}, Track Surface: ${raceData.trackSurface}).")
                    }
                    raceDataList
                } else {
                    MessageLog.i(TAG, "[RACE] ✗ No match found in database for \"$raceName\".")
                    emptyList()
                }
            }
        if (currentRaces.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No on-screen races matched the database. Canceling racing process.")
            return false
        }

        val solverPick =
            SmartRaceSolverIntegration.pickRace(
                currentTurn = campaign.date.day,
                scenario = game.scenario,
                candidates = currentRaces,
            )
        if (solverPick == null) {
            MessageLog.i(TAG, "[RACE] Smart Race Solver did not return a pick for turn ${campaign.date.day}. Canceling racing process.")
            return false
        }

        val pickLocation = findRaceLocationByName(doublePredictionLocations, solverPick.name)
        if (pickLocation == null) {
            MessageLog.i(TAG, "[RACE] Smart Race Solver picked \"${solverPick.name}\" but no matching on-screen location was found. Canceling racing process.")
            return false
        }

        MessageLog.v(TAG, "[RACE] Smart Race Solver selected \"${solverPick.name}\". Selecting it.")
        SmartRaceSolverIntegration.markPendingRace(
            raceKey = solverPick.name,
            raceName = solverPick.name,
            classYear = campaign.date.year.name,
            turnNumber = campaign.date.day,
        )
        game.tap(pickLocation.x, pickLocation.y, IconRaceListPredictionDoubleStar.template.path, ignoreWaiting = true)
        lastRaceGrade = solverPick.grade
        lastRaceFans = solverPick.fans
        lastRaceDistance = solverPick.trackDistance
        lastRaceName = solverPick.name
        lastRaceSurface = solverPick.trackSurface
        lastRaceIsRival = solverPick.isRival
        return true
    }

    /**
     * Handles extra races using the standard or traditional racing logic.
     *
     * @return True if a race was successfully selected, false if the process was canceled.
     */
    private fun processStandardRacing(): Boolean {
        MessageLog.v(TAG, "[RACE] Using traditional racing logic for extra races...")

        // Detect double-star races on screen.
        var doublePredictionLocations = IconRaceListPredictionDoubleStar.findAll(game.imageUtils)

        // If no double predictions found and fans/Pre-OP/G3/GoalPts requirement is active and is after Junior Year, scroll to find them.
        if (doublePredictionLocations.isEmpty() &&
            campaign.date.year != DateYear.JUNIOR &&
            (hasFanRequirement || hasPreOpOrAboveRequirement || hasG3OrAboveRequirement || hasInsufficientGoalRacePtsRequirement)
        ) {
            val maxScrollAttempts = 5
            MessageLog.i(TAG, "[RACE] No double-star predictions found on initial screen. Scrolling to find races to satisfy requirements...")

            for (scrollAttempt in 1..maxScrollAttempts) {
                MessageLog.i(TAG, "[RACE] Scrolling down (attempt $scrollAttempt/$maxScrollAttempts)...")
                val newPredictions = scrollRaceListAndRedetect(scrollDown = true)

                if (newPredictions == null) {
                    MessageLog.i(TAG, "[RACE] Scroll failed. Stopping scroll attempts.")
                    break
                }

                doublePredictionLocations = newPredictions
                if (doublePredictionLocations.isNotEmpty()) {
                    MessageLog.i(TAG, "[RACE] Found ${doublePredictionLocations.size} double-star prediction(s) after $scrollAttempt scroll(s).")
                    break
                }
            }
        }

        val maxCount = doublePredictionLocations.size
        if (maxCount == 0) {
            MessageLog.w(TAG, "[WARN] processStandardRacing:: No extra races with double predictions found on screen. Canceling racing process.")
            return false
        }

        // If only one race has double predictions, check if it's G1 when trophy requirement is active.
        // If Pre-OP or G3 criteria is active, any race is acceptable.
        if (maxCount == 1) {
            if (hasTrophyRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
                campaign.updateDate(isOnMainScreen = false)
                val raceName = game.imageUtils.extractRaceName(doublePredictionLocations[0])
                val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                // Check if any matched race is G1.
                if (raceDataList.any { it.grade == RaceGrade.G1 }) {
                    MessageLog.i(TAG, "[RACE] Only one race with double predictions and it's G1. Selecting it.")
                    game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, IconRaceListPredictionDoubleStar.template.path, ignoreWaiting = true)
                    return true
                } else {
                    // Not G1. Trophy requirement specifically needs G1 races, so cancel.
                    MessageLog.i(TAG, "[RACE] Trophy requirement active but only non-G1 race available. Canceling racing process...")
                    return false
                }
            } else if (hasTrophyRequirement && (hasPreOpOrAboveRequirement || hasG3OrAboveRequirement)) {
                if (hasPreOpOrAboveRequirement) {
                    MessageLog.i(TAG, "[RACE] Only one race with double predictions and Pre-OP or above criteria active. Selecting it.")
                } else {
                    MessageLog.i(TAG, "[RACE] Only one race with double predictions and G3 or above criteria active. Selecting it.")
                }
                game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, IconRaceListPredictionDoubleStar.template.path, ignoreWaiting = true)
                return true
            } else {
                MessageLog.i(TAG, "[RACE] Only one race with double predictions. Selecting it.")
                game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, IconRaceListPredictionDoubleStar.template.path, ignoreWaiting = true)
                return true
            }
        }

        // Otherwise, iterate through each extra race to determine fan gain and double prediction status.
        val sourceBitmap: Bitmap = game.imageUtils.getSourceBitmap()
        val listOfRaces = ArrayList<RaceDetails>()
        val extraRaceLocations = ArrayList<Point>()
        val raceNamesList = ArrayList<String>()

        for (count in 0 until maxCount) {
            val selectedExtraRace = IconRaceListSelectionBracketBottomRight.find(game.imageUtils).first ?: break
            extraRaceLocations.add(selectedExtraRace)

            // Extract race name for G1 filtering if trophy requirement is active.
            if (hasTrophyRequirement && count < doublePredictionLocations.size) {
                val raceName = game.imageUtils.extractRaceName(doublePredictionLocations[count])
                raceNamesList.add(raceName)
            }

            val raceDetails = game.imageUtils.determineExtraRaceFans(selectedExtraRace, sourceBitmap, forceRacing = enableForceRacing)
            listOfRaces.add(raceDetails)

            if (count + 1 < maxCount) {
                val nextX = game.imageUtils.relX(selectedExtraRace.x, -100)
                val nextY = game.imageUtils.relY(selectedExtraRace.y, 150)
                game.tap(nextX.toDouble(), nextY.toDouble(), IconRaceListSelectionBracketBottomRight.template.path, ignoreWaiting = true)
            }

            game.wait(0.5)
        }

        // If trophy requirement is active, filter to only G1 races.
        val (filteredRaces, filteredLocations, _) =
            if (hasTrophyRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
                campaign.updateDate(isOnMainScreen = false)
                val g1Indices =
                    raceNamesList.mapIndexedNotNull { index, raceName ->
                        val raceDataList = lookupRaceInDatabase(campaign.date.day, raceName)
                        // Check if any matched race is G1.
                        if (raceDataList.any { it.grade == RaceGrade.G1 }) index else null
                    }

                if (g1Indices.isEmpty()) {
                    // No G1 races available. Cancel since trophy requirement specifically needs G1 races.
                    // A trophy requirement can only be met by a G1, so with none listed this turn there is nothing to race for.
                    MessageLog.i(TAG, "[RACE] Trophy requirement active but no G1 races available. Canceling racing process.")
                    return false
                } else {
                    MessageLog.i(TAG, "[RACE] Trophy requirement active. Filtering to ${g1Indices.size} G1 races.")
                    val filtered = g1Indices.map { listOfRaces[it] }
                    val filteredLocations = g1Indices.map { extraRaceLocations[it] }
                    val filteredNames = g1Indices.map { raceNamesList[it] }
                    Triple(filtered, filteredLocations, filteredNames)
                }
            } else if (hasTrophyRequirement && (hasPreOpOrAboveRequirement || hasG3OrAboveRequirement)) {
                if (hasPreOpOrAboveRequirement) {
                    MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria active. Using all ${listOfRaces.size} races.")
                } else {
                    MessageLog.i(TAG, "[RACE] Trophy requirement with G3 or above criteria active. Using all ${listOfRaces.size} races.")
                }
                Triple(listOfRaces, extraRaceLocations, raceNamesList)
            } else {
                Triple(listOfRaces, extraRaceLocations, raceNamesList)
            }

        // Determine max fans and select the appropriate race.
        val maxFans = filteredRaces.maxOfOrNull { it.fans } ?: -1
        if (maxFans == -1) {
            Log.w(TAG, "[RACE] Failed to determine max fans. Aborting racing...")
            return false
        }
        MessageLog.v(TAG, "[RACE] Number of fans detected for each extra race are: ${filteredRaces.joinToString(", ") { it.fans.toString() }}")

        // Evaluate which race to select based on Rival priority, maximum fans and double prediction priority.
        val index =
            if (filteredRaces.any { it.isRival }) {
                MessageLog.v(TAG, "[RACE] Rival Race(s) detected. Prioritizing Rival Races.")
                val rivalRaces = filteredRaces.filter { it.isRival }

                if (enableForceRacing) {
                    val rivalsWithDouble = rivalRaces.filter { it.hasDoublePredictions }
                    if (rivalsWithDouble.isNotEmpty()) {
                        val maxFansDouble = rivalsWithDouble.maxOf { it.fans }
                        filteredRaces.indexOfFirst { it.isRival && it.hasDoublePredictions && it.fans == maxFansDouble }
                    } else {
                        val maxRivalFans = rivalRaces.maxOf { it.fans }
                        filteredRaces.indexOfFirst { it.isRival && it.fans == maxRivalFans }
                    }
                } else {
                    val maxRivalFans = rivalRaces.maxOf { it.fans }
                    filteredRaces.indexOfFirst { it.isRival && it.fans == maxRivalFans }
                }
            } else {
                if (!enableForceRacing && !hasInsufficientGoalRacePtsRequirement) {
                    filteredRaces.indexOfFirst { it.fans == maxFans }
                } else {
                    filteredRaces.indexOfFirst { it.hasDoublePredictions }.takeIf { it != -1 } ?: filteredRaces.indexOfFirst { it.fans == maxFans }
                }
            }

        // Determine the grade of the selected race and store it for retry purposes.
        val selectedRaceName =
            if (hasTrophyRequirement && index < raceNamesList.size) {
                raceNamesList[index]
            } else {
                game.imageUtils.extractRaceName(extraRaceLocations[index])
            }
        val raceDataList = lookupRaceInDatabase(campaign.date.day, selectedRaceName)
        lastRaceGrade = raceDataList.firstOrNull()?.grade
        lastRaceFans = raceDataList.firstOrNull()?.fans ?: 0
        lastRaceDistance = raceDataList.firstOrNull()?.trackDistance
        lastRaceName = raceDataList.firstOrNull()?.name ?: selectedRaceName
        lastRaceSurface = raceDataList.firstOrNull()?.trackSurface
        lastRaceIsRival = filteredRaces[index].isRival

        // Selects the determined race on screen.
        MessageLog.v(TAG, "[RACE] Selecting extra race at option #${index + 1}.")
        val target = filteredLocations[index]
        game.tap(
            target.x - game.imageUtils.relWidth((100 * 1.36).toInt()),
            target.y - game.imageUtils.relHeight(70),
            IconRaceListSelectionBracketBottomRight.template.path,
            ignoreWaiting = true,
        )

        return true
    }
}
