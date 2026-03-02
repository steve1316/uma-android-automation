package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.utils.CustomImageUtils.RaceDetails
import com.steve1316.automation_library.utils.SQLiteSettingsManager
import com.steve1316.automation_library.utils.MessageLog
import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Point
import android.util.Log
import android.graphics.Bitmap

import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.TrackSurface
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.BoundingBox

import com.steve1316.uma_android_automation.components.*

class Racing (private val game: Game) {
    private val TAG: String = "[${MainActivity.loggerTag}]Racing"

    private val enableFarmingFans = SettingsHelper.getBooleanSetting("racing", "enableFarmingFans")
    internal val ignoreConsecutiveRaceWarning = SettingsHelper.getBooleanSetting("racing", "ignoreConsecutiveRaceWarning")
    private val daysToRunExtraRaces: Int = SettingsHelper.getIntSetting("racing", "daysToRunExtraRaces")
    internal val disableRaceRetries: Boolean = SettingsHelper.getBooleanSetting("racing", "disableRaceRetries")
    internal val enableFreeRaceRetry: Boolean = SettingsHelper.getBooleanSetting("racing", "enableFreeRaceRetry")
    internal val enableCompleteCareerOnFailure: Boolean = SettingsHelper.getBooleanSetting("racing", "enableCompleteCareerOnFailure")
    internal val enableForceRacing = SettingsHelper.getBooleanSetting("racing", "enableForceRacing")

    private val enableRacingPlan = SettingsHelper.getBooleanSetting("racing", "enableRacingPlan")
    private val lookAheadDays = SettingsHelper.getIntSetting("racing", "lookAheadDays")
    private val smartRacingCheckInterval = SettingsHelper.getIntSetting("racing", "smartRacingCheckInterval")
    private val minFansThreshold = SettingsHelper.getIntSetting("racing", "minFansThreshold")
    private val preferredTrackSurfaceString = SettingsHelper.getStringSetting("racing", "preferredTerrain")
    private val preferredGradesString = SettingsHelper.getStringSetting("racing", "preferredGrades")
    private val preferredTrackDistanceString = SettingsHelper.getStringSetting("racing", "preferredDistances")
    private val racingPlanJson = SettingsHelper.getStringSetting("racing", "racingPlan")
    private val minimumQualityThreshold = SettingsHelper.getDoubleSetting("racing", "minimumQualityThreshold")
    private val timeDecayFactor = SettingsHelper.getDoubleSetting("racing", "timeDecayFactor")
    private val improvementThreshold = SettingsHelper.getDoubleSetting("racing", "improvementThreshold")
    private val enableMandatoryRacingPlan = SettingsHelper.getBooleanSetting("racing", "enableMandatoryRacingPlan")
    private val enableUserInGameRaceAgenda = SettingsHelper.getBooleanSetting("racing", "enableUserInGameRaceAgenda")
    private val selectedUserAgenda = SettingsHelper.getStringSetting("racing", "selectedUserAgenda")

    internal var raceRetries = 3
    internal var raceRepeatWarningCheck = false
    var encounteredRacingPopup = false
    var firstTimeRacing = true
    var hasFanRequirement = false  // Indicates that a fan requirement has been detected on the main screen.
    var hasTrophyRequirement = false  // Indicates that a trophy requirement has been detected on the main screen.
    var hasPreOpOrAboveRequirement = false  // Indicates that a Pre-OP or above requirement has been detected (any race can fulfill it).
    var hasG3OrAboveRequirement = false  // Indicates that a G3 or above requirement has been detected (any race can fulfill it).
    private var nextSmartRaceDay: Int? = null  // Tracks the specific day to race based on opportunity cost analysis.
    private var hasLoadedUserRaceAgenda = false  // Tracks if the user's race agenda has been loaded this career.

    internal val enableStopOnMandatoryRace: Boolean = SettingsHelper.getBooleanSetting("racing", "enableStopOnMandatoryRaces")
    internal var detectedMandatoryRaceCheck = false

    // Race strategy override settings.
    internal val juniorYearRaceStrategy = SettingsHelper.getStringSetting("racing", "juniorYearRaceStrategy")
    internal val userSelectedOriginalStrategy = SettingsHelper.getStringSetting("racing", "originalRaceStrategy")
    private var detectedOriginalStrategy: String? = null
    private var hasAppliedStrategyOverride = false

    // Cached race plan data loaded once per class instance.
    private val raceData: Map<String, RaceData> = loadRaceData()
    private val userPlannedRaces: List<PlannedRace> = loadUserPlannedRaces()

    companion object {
        private const val TABLE_RACES = "races"
        private const val RACES_COLUMN_NAME = "name"
        private const val RACES_COLUMN_GRADE = "grade"
        private const val RACES_COLUMN_FANS = "fans"
        private const val RACES_COLUMN_TURN_NUMBER = "turnNumber"
        private const val RACES_COLUMN_NAME_FORMATTED = "nameFormatted"
        private const val RACES_COLUMN_TRACK_SURFACE = "terrain"
        private const val RACES_COLUMN_TRACK_DISTANCE = "distanceType"
        private const val SIMILARITY_THRESHOLD = 0.7
    }

    data class RaceData(
        val name: String,
        val grade: RaceGrade,
        val fans: Int,
        val nameFormatted: String,
        val trackSurface: TrackSurface,
        val trackDistance: TrackDistance,
        val turnNumber: Int
    ) {
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
            // Scraper source uses "Short" instead of "Sprint" which is expected by our enum.
            TrackDistance.fromName(trackDistance.lowercase().replace("short", "sprint"))!!,
            turnNumber,
        )
    }

    data class ScoredRace(
        val raceData: RaceData,
        val score: Double,
        val fansScore: Double,
        val gradeScore: Double,
        val aptitudeBonus: Double
    )

    data class PlannedRace(
        val raceName: String,
        val date: String,
        val priority: Int,
        val turnNumber: Int
    )

    /**
     * Clears all racing requirement flags like fan and trophy requirements.
     */
    internal fun clearRacingRequirementFlags() {
        hasFanRequirement = false
        hasTrophyRequirement = false
        hasPreOpOrAboveRequirement = false
        hasG3OrAboveRequirement = false
    }


    /**
     * Retrieves the user's planned races from saved settings.
     *
     * @return A list of [PlannedRace] entries defined by the user, or an empty list if none exist.
     */
    private fun loadUserPlannedRaces(): List<PlannedRace> {
        if (!enableRacingPlan) {
            MessageLog.i(TAG, "[RACE] Racing plan is disabled, returning empty planned races list.")
            return emptyList()
        }

        return try {
            if (racingPlanJson.isEmpty() || racingPlanJson == "[]") {
                MessageLog.i(TAG, "[RACE] User-selected racing plan is empty, returning empty list.")
                return emptyList()
            }

            val jsonArray = JSONArray(racingPlanJson)
            val plannedRaces = mutableListOf<PlannedRace>()

            for (i in 0 until jsonArray.length()) {
                val raceObj = jsonArray.getJSONObject(i)
                val plannedRace = PlannedRace(
                    raceName = raceObj.getString("raceName"),
                    date = raceObj.getString("date"),
                    priority = raceObj.optInt("priority", 0),
                    turnNumber = raceObj.getInt("turnNumber")
                )
                plannedRaces.add(plannedRace)
            }

            MessageLog.i(TAG, "[RACE] Successfully loaded ${plannedRaces.size} user-selected planned races from settings.")
            plannedRaces
        } catch (e: Exception) {
            MessageLog.e(TAG, "Failed to parse user-selected racing plan JSON: ${e.message}. Returning empty list.")
            emptyList()
        }
    }

    /**
     * Loads the complete race database from saved settings, including all race metadata such as names, grades, distances, and turn numbers.
     *
     * @return A map of race names to their [RaceData] or an empty map if racing plan data is missing or invalid.
     */
    private fun loadRaceData(): Map<String, RaceData> {
        return try {
            val racingPlanDataJson = SettingsHelper.getStringSetting("racing", "racingPlanData")
            if (racingPlanDataJson.isEmpty()) {
                MessageLog.i(TAG, "[RACE] Racing plan data is empty, returning empty map.")
                return emptyMap()
            }

            val jsonObject = JSONObject(racingPlanDataJson)
            val raceDataMap = mutableMapOf<String, RaceData>()

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val raceObj = jsonObject.getJSONObject(key)

                val raceData = RaceData(
                    name = raceObj.getString(RACES_COLUMN_NAME),
                    grade = raceObj.getString(RACES_COLUMN_GRADE),
                    trackSurface = raceObj.getString(RACES_COLUMN_TRACK_SURFACE),
                    trackDistance = raceObj.getString(RACES_COLUMN_TRACK_DISTANCE),
                    fans = raceObj.getInt(RACES_COLUMN_FANS),
                    turnNumber = raceObj.getInt(RACES_COLUMN_TURN_NUMBER),
                    nameFormatted = raceObj.getString(RACES_COLUMN_NAME_FORMATTED)
                )

                raceDataMap[raceData.name] = raceData
            }

            MessageLog.i(TAG, "[RACE] Successfully loaded ${raceDataMap.size} race entries from racing plan data.")
            raceDataMap
        } catch (e: Exception) {
            MessageLog.e(TAG, "Failed to parse racing plan data JSON: ${e.message}. Returning empty map.")
            emptyMap()
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Handles the test to detect the currently displayed races on the Race List screen.
     */
    fun startRaceListDetectionTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning detection test on the Race List screen for the currently displayed races.")
        if (game.imageUtils.findImage("race_status").first == null) {
            MessageLog.i(TAG, "[TEST] Bot is not on the Race List screen. Ending the test.")
            return
        }

        // Detect the current date first.
        game.updateDate()

        // Check for all double star predictions.
        val doublePredictionLocations = game.imageUtils.findAll("race_extra_double_prediction")
        MessageLog.i(TAG, "[TEST] Found ${doublePredictionLocations.size} races with double predictions.")
        
        doublePredictionLocations.forEachIndexed { index, location ->
            val raceName = game.imageUtils.extractRaceName(location)
            MessageLog.i(TAG, "[TEST] Race #${index + 1} - Detected name: \"$raceName\".")
            
            // Query database for race details (may return multiple matches with different fan counts).
            val raceDataList = lookupRaceInDatabase(game.currentDate.day, raceName)
            
            if (raceDataList.isNotEmpty()) {
                MessageLog.i(TAG, "[TEST] Race #${index + 1} - Found ${raceDataList.size} match(es):")
                raceDataList.forEach { raceData ->
                    MessageLog.i(TAG, "[TEST]     Name: ${raceData.name}")
                    MessageLog.i(TAG, "[TEST]     Grade: ${raceData.grade}")
                    MessageLog.i(TAG, "[TEST]     Fans: ${raceData.fans}")
                    MessageLog.i(TAG, "[TEST]     Formatted: ${raceData.nameFormatted}")
                }
            } else {
                MessageLog.i(TAG, "[TEST] Race #${index + 1} - No match found for turn ${game.currentDate.day}")
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    // Entry Points
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * The entry point for handling mandatory or extra races.
     *
     * @param isScheduledRace True if there is a scheduled race pending. False otherwise.
     * @return True if the mandatory/extra race was completed successfully. Otherwise false.
     */
    fun handleRaceEvents(isScheduledRace: Boolean = false): Boolean {
        MessageLog.i(TAG, "\n********************")
        MessageLog.i(TAG, "[RACE] Starting Racing process on ${game.currentDate}.")

        // If there are no races available, cancel the racing process.
        if (game.imageUtils.findImage("race_none_available", tries = 1, region = game.imageUtils.regionMiddle, suppressError = true).first != null) {
            MessageLog.i(TAG, "[RACE] There are no races to compete in. Canceling the racing process and doing something else.")
            MessageLog.i(TAG, "********************")
            // Clear requirement flags since we cannot proceed with racing.
            clearRacingRequirementFlags()
            return false
        }

        // First, check if there is a mandatory or a extra race available. If so, head into the Race Selection screen.
        // Note: If there is a mandatory race, the bot would be on the Home screen.
        // Otherwise, it would have found itself at the Race Selection screen already (by way of the insufficient fans popup).
        val loc: Point? = IconRaceDayRibbon.find(imageUtils = game.imageUtils).first
        if (loc != null) {
            // Offset 100px down from the ribbon since the ribbon isn't clickable.
            game.tap(loc.x, loc.y + 100, IconRaceDayRibbon.template.path, ignoreWaiting = true)
            game.wait(0.5, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            game.campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
            return handleMandatoryRace()
        } else if (!game.trainee.bHasCompletedMaidenRace && !isScheduledRace && ButtonRaceSelectExtra.click(imageUtils = game.imageUtils)) {
            game.wait(1.0, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            game.campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
            return handleMaidenRace()
        } else if ((!game.currentDate.bIsPreDebut && ButtonRaceSelectExtra.click(imageUtils = game.imageUtils)) || isScheduledRace) {
            var overrideIgnore = false
            if (hasFanRequirement || hasTrophyRequirement) {
                MessageLog.i(TAG, "[RACE] Racing requirement is active. Ignoring consecutive race warning.")
                overrideIgnore = true
            }
            game.wait(0.5, skipWaitingForLoading = true)
            // Check for the consecutive race dialog before proceeding.
            val (bWasDialogHandled, dialog) = game.campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to overrideIgnore))
            if (dialog != null && dialog.name == "consecutive_race_warning" && !(overrideIgnore || enableForceRacing || ignoreConsecutiveRaceWarning)) {
                MessageLog.i(TAG, "[RACE] Consecutive race warning but conditions dictate to not race. Skipping...")
                return false
            }
            return handleExtraRace(isScheduledRace = isScheduledRace)
        } else if (game.imageUtils.findImage("race_change_strategy", tries = 1, region = game.imageUtils.regionMiddle).first != null) {
            MessageLog.i(TAG, "[RACE] The bot is currently sitting on the race screen. Most likely here for a scheduled race.")
            handleStandaloneRace()
        }

        // Clear requirement flags if no race selection buttons were found.
        clearRacingRequirementFlags()
        MessageLog.i(TAG, "********************")
        return false
    }

    /**
     * The entry point for handling standalone races if the user started the bot on the Racing screen.
     */
    fun handleStandaloneRace() {
        MessageLog.i(TAG, "\n********************")
        MessageLog.i(TAG, "[RACE] Starting Standalone Racing process...")

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults()

        MessageLog.i(TAG, "[RACE] Racing process for Standalone Race is completed.")
        MessageLog.i(TAG, "********************")
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    // Mandatory and Extra Racing Processes
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Handles mandatory race processing.
     * 
     * @return True if the mandatory race was completed successfully, false otherwise.
     */
    private fun handleMandatoryRace(): Boolean {
        MessageLog.i(TAG, "[RACE] Starting process for handling a mandatory race.")

        if (enableStopOnMandatoryRace) {
            MessageLog.i(TAG, "********************")
            detectedMandatoryRaceCheck = true
            return false
        }

        // If there is a popup warning about racing too many times, confirm the popup to continue as this is a mandatory race.
        if (game.imageUtils.findImage("ok", tries = 1, region = game.imageUtils.regionMiddle).first != null) {
            game.findAndTapImage("ok", tries = 1, region = game.imageUtils.regionMiddle, suppressError = true)
            game.wait(2.0)
        }

        MessageLog.i(TAG, "[RACE] Confirming the mandatory race selection.")
        game.findAndTapImage("race_confirm", tries = 3, region = game.imageUtils.regionBottomHalf)
        game.wait(1.0)
        MessageLog.i(TAG, "[RACE] Confirming any popup from the mandatory race selection.")
        game.findAndTapImage("race_confirm", tries = 3, region = game.imageUtils.regionBottomHalf)
        game.wait(2.0)

        game.waitForLoading()

        // Handle race strategy override if enabled.
        selectRaceStrategy()
        game.wait(1.0)

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults()

        MessageLog.i(TAG, "[RACE] Racing process for Mandatory Race is completed.")
        MessageLog.i(TAG, "********************")
        return true
    }

    private fun selectMaidenRace(): Boolean {
        // Get the bounding region of the race list.
        val raceListTopLeftBitmap: Bitmap? = IconScrollListTopLeft.template.getBitmap(game.imageUtils)
        if (raceListTopLeftBitmap == null) {
            MessageLog.e(TAG, "[RACE] Failed to load IconScrollListTopLeft bitmap.")
            return false
        }

        val raceListBottomRightBitmap: Bitmap? = IconScrollListBottomRight.template.getBitmap(game.imageUtils)
        if (raceListBottomRightBitmap == null) {
            MessageLog.e(TAG, "[RACE] Failed to load IconScrollListBottomRight bitmap.")
            return false
        }

        val raceListTopLeft: Point? = IconScrollListTopLeft.find(game.imageUtils).first
        if (raceListTopLeft == null) {
            MessageLog.e(TAG, "[RACE] Failed to find top left corner of race list.")
            return false
        }
        val raceListBottomRight: Point? = IconScrollListBottomRight.find(game.imageUtils).first
        if (raceListBottomRight == null) {
            MessageLog.e(TAG, "[RACE] Failed to find bottom right corner of race list.")
            return false
        }
        val x0 = (raceListTopLeft.x - (raceListTopLeftBitmap.width / 2)).toInt()
        val y0 = (raceListTopLeft.y - (raceListTopLeftBitmap.height / 2)).toInt()
        val x1 = (raceListBottomRight.x + (raceListBottomRightBitmap.width / 2)).toInt()
        val y1 = (raceListBottomRight.y + (raceListBottomRightBitmap.height / 2)).toInt()
        val bboxRaceList = BoundingBox(
            x = x0,
            y = y0,
            w = x1 - x0,
            h = y1 - y0,
        )

        // Smaller region used to detect double star icons in the race list.
        val bboxRaceListDoubleStars = BoundingBox(
            x = game.imageUtils.relX(bboxRaceList.x.toDouble(), 845),
            y = bboxRaceList.y,
            w = game.imageUtils.relWidth(45),
            h = bboxRaceList.h,
        )

        val bboxScrollBar = BoundingBox(
            x = game.imageUtils.relX(bboxRaceList.x.toDouble(), 1034),
            y = bboxRaceList.y,
            w = 10,
            h = bboxRaceList.h,
        )

        // The selected race in the race list has green brackets that overlap the
        // scroll bar a bit. Thus we are really only interested in a single column
        // of pixels on the right side of the scroll bar when checking for changes.
        val bboxScrollBarSingleColumn = BoundingBox(
            // give ourselves a few pixels of buffer
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
            // high value here ensures we go all the way to top of list
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

        var bitmap = game.imageUtils.getSourceBitmap()
        var prevScrollBarBitmap: Bitmap? = null

        // Max time limit for the while loop to search for a valid race.
        val startTime: Long = System.currentTimeMillis()
        val maxTimeMs: Long = 10000

        while (System.currentTimeMillis() - startTime < maxTimeMs) {
            bitmap = game.imageUtils.getSourceBitmap()
            val scrollBarBitmap: Bitmap? = game.imageUtils.createSafeBitmap(
                bitmap,
                bboxScrollBarSingleColumn.x,
                bboxScrollBarSingleColumn.y,
                bboxScrollBarSingleColumn.w,
                bboxScrollBarSingleColumn.h,
                "race list scrollbar right half bitmap",
            )
            if (scrollBarBitmap == null) {
                MessageLog.e(TAG, "[RACE] Failed to createSafeBitmap for scrollbar.")
                return false
            }

            // If after scrolling the scrollbar hasn't changed, that means
            // we've reached the end of the list.
            if (prevScrollBarBitmap != null && scrollBarBitmap.sameAs(prevScrollBarBitmap)) {
                Log.d(TAG, "[RACE] Scrollbar has not changed, reached end of list.")
                return false
            }

            prevScrollBarBitmap = scrollBarBitmap

            val locs: ArrayList<Point> = IconRaceListPredictionDoubleStar.findAll(
                imageUtils = game.imageUtils,
                region = bboxRaceListDoubleStars.toIntArray(),
                confidence = 0.0,
            )

            if (!locs.isEmpty()) {
                Log.d(TAG, "[RACE] Found double predictions at (${locs.first().x}, ${locs.first().y}).")
                game.tap(
                    locs.first().x,
                    locs.first().y,
                    IconRaceListPredictionDoubleStar.template.path,
                    ignoreWaiting = true,
                )
                return true
            }

            // Longer swipe duration prevents overscrolling.
            // Swipe up approximately one entry in list.
            // Each entry is roughly 200px tall and there is a 30px gap between entries.
            // I find that a 500px swipe with 1500ms duration is a good balance.
            // For some reason with shorter durations, it overscrolls too much. Also
            // with longer durations, the amount scrolled is less than the pixel amount
            // specified so we use 500px to counter this.
            game.gestureUtils.swipe(
                (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
                (bboxRaceList.y + (bboxRaceList.h / 2)).toFloat(),
                (bboxRaceList.x + (bboxRaceList.w / 2)).toFloat(),
                ((bboxRaceList.y + (bboxRaceList.h / 2)) - 500).toFloat(),
                duration=500,
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

    private fun handleMaidenRace(): Boolean {
        MessageLog.i(TAG, "[RACE] Starting process for handling a maiden race.")

        if (!ButtonRaceListFullStats.check(imageUtils = game.imageUtils, tries = 30)) {
            MessageLog.e(TAG, "[RACE] Not at race list screen. Aborting racing...")
            // Clear requirement flags since we cannot proceed with racing.
            clearRacingRequirementFlags()
            return false
        }

        game.campaign.bHasCheckedForMaidenRaceToday = true
        if (IconRaceListMaidenPill.check(imageUtils = game.imageUtils)) {
            MessageLog.d(TAG, "[RACE] Detected maiden races in race list.")
            if (selectMaidenRace()) {
                MessageLog.i(TAG, "[RACE] Found maiden race with good aptitudes. Racing...")
            } else {
                MessageLog.i(TAG, "[RACE] Could not find any maiden races with good aptitudes. Aborting racing...")
                ButtonBack.click(imageUtils = game.imageUtils)
                return false
            }
        } else {
            // No maiden races available on this day. Check for extra races instead.
            MessageLog.i(TAG, "[RACE] No maiden races available on this day. Checking for extra races instead...")
            return handleExtraRace()
        }

        // Confirm the selection and the resultant popup and then wait for the game to load.
        ButtonRace.click(imageUtils = game.imageUtils)
        game.wait(0.5, skipWaitingForLoading = true)
        val (bWasDialogHandled, dialog) = game.campaign.handleDialogs()
        if (!bWasDialogHandled || (dialog != null && dialog.name != "race_details")) {
            Log.w(TAG, "[RACE] Failed to handle dialogs. Aborting racing...")
            return false
        }
        game.wait(2.0)

        // Handle race strategy override if enabled.
        selectRaceStrategy()
        game.wait(1.0)

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults(isExtra = true)

        // Clear the next smart race day tracker since we just completed a race.
        nextSmartRaceDay = null

        MessageLog.i(TAG, "[RACE] Racing process for Maiden Race is completed.")
        MessageLog.i(TAG, "********************")
        return true
    }

    /**
     * Handles extra race processing.
     * 
     * @param isScheduledRace True if there is a scheduled race pending. False otherwise.
     * @return True if the extra race was completed successfully, false otherwise.
     */
    private fun handleExtraRace(isScheduledRace: Boolean = false): Boolean {
        MessageLog.i(TAG, "[RACE] Starting process for handling a extra race${if (isScheduledRace) " (scheduled)" else ""}.")

        // If there is a scheduled race pending, proceed to run it immediately.
        if (!isScheduledRace) {
            // Check for mandatory racing plan mode before any screen detection.
            // Bypass this check if a fan or trophy requirement is active.
            val (_, mandatoryExtraRaceData) = if (hasFanRequirement || hasTrophyRequirement) {
                Pair(null, null)
            } else {
                findMandatoryExtraRaceForCurrentTurn()
            }

            if (mandatoryExtraRaceData != null) {
                // Check if aptitudes match (both track surface and distance must be B or greater) for double predictions.
                val aptitudesMatch = checkRaceAptitudeMatch(mandatoryExtraRaceData)
                if (!aptitudesMatch) {
                    // Get the trainee's aptitude for this race's track surface/distance.
                    val trackSurfaceAptitude: Aptitude = game.trainee.checkTrackSurfaceAptitude(mandatoryExtraRaceData.trackSurface)
                    val trackDistanceAptitude: Aptitude = game.trainee.checkTrackDistanceAptitude(mandatoryExtraRaceData.trackDistance)
                    MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" aptitudes don't match requirements (TrackSurface: $trackSurfaceAptitude, TrackDistance: $trackDistanceAptitude). Both must be B or greater.")
                    return false
                } else {
                    MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" aptitudes match requirements. Proceeding to navigate to the Extra Races screen.")
                }
            }

            // If there is a popup warning about repeating races 3+ times, stop the process and do something else other than racing.
            // Note that if the Racing Plan is set to be mandatory, then this popup is dismissed.
            if (game.imageUtils.findImage("race_repeat_warning").first != null) {
                if (!enableForceRacing && !enableMandatoryRacingPlan) {
                    raceRepeatWarningCheck = true
                    MessageLog.i(TAG, "[RACE] Closing popup warning of doing more than 3+ races and setting flag to prevent racing for now. Canceling the racing process and doing something else.")
                    game.findAndTapImage("cancel", region = game.imageUtils.regionBottomHalf)
                    // Clear requirement flags since we cannot proceed with racing.
                    clearRacingRequirementFlags()
                    MessageLog.i(TAG, "********************")
                    return false
                } else {
                    game.findAndTapImage("ok", tries = 1, region = game.imageUtils.regionMiddle)
                    game.wait(1.0)
                }
            }

            // There is an extra race.
            val statusLocation = ButtonRaceListFullStats.find(imageUtils = game.imageUtils, tries = 30).first
            if (statusLocation == null) {
                MessageLog.e(TAG, "[ERROR] Unable to determine existence of list of extra races. Canceling the racing process and doing something else.")
                // Clear requirement flags since we cannot proceed with racing.
                clearRacingRequirementFlags()
                MessageLog.i(TAG, "********************")
                return false
            }

            val maxCount = game.imageUtils.findAll("race_selection_fans", region = game.imageUtils.regionBottomHalf).size
            if (maxCount == 0) {
                // If there is a fan/trophy requirement but no races available, reset the flags and proceed with training to advance the day.
                if (hasFanRequirement || hasTrophyRequirement) {
                    MessageLog.i(TAG, "[RACE] Fan/trophy requirement detected but no extra races available. Clearing requirement flags and proceeding with training to advance the day.")
                } else {
                    MessageLog.e(TAG, "[ERROR] Was unable to find any extra races to select. Canceling the racing process and doing something else.")
                }
                // Always clear requirement flags when no races are available.
                clearRacingRequirementFlags()
                MessageLog.i(TAG, "********************")
                return false
            } else {
                MessageLog.i(TAG, "[RACE] There are $maxCount extra race options currently on screen.")
            }

            if (hasFanRequirement) MessageLog.i(TAG, "[RACE] Fan requirement criteria detected. This race must be completed to meet the requirement.")
            if (hasTrophyRequirement) {
                when {
                    hasPreOpOrAboveRequirement -> {
                        MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria detected. Any race can be selected to meet the requirement.")
                    }
                    hasG3OrAboveRequirement -> {
                        MessageLog.i(TAG, "[RACE] Trophy requirement with G3 or above criteria detected. Any race can be selected to meet the requirement.")
                    }
                    else -> {
                        MessageLog.i(TAG, "[RACE] Trophy requirement criteria detected. Only G1 races will be selected to meet the requirement.")
                    }
                }
            }

            // Determine whether to use smart racing with user-selected races or standard racing.
            val useSmartRacing = if (hasFanRequirement) {
                // If fan requirement is needed, force standard racing to ensure the race proceeds.
                false
            } else if (hasTrophyRequirement) {
                // Trophy requirement can use smart racing as it filters to G1 races internally.
                // Use smart racing for all years except Year 1 (Junior Year).
                game.currentDate.year != DateYear.JUNIOR
            } else if (enableRacingPlan && game.currentDate.year != DateYear.JUNIOR) {
                // Year 2 and 3: Use smart racing if conditions are met.
                enableFarmingFans && !enableForceRacing
            } else {
                false
            }

            val success = if (useSmartRacing && game.currentDate.year != DateYear.JUNIOR) {
                // Use the smart racing logic.
                MessageLog.i(TAG, "[RACE] Using smart racing for Year ${game.currentDate.year}.")
                processSmartRacing(mandatoryExtraRaceData)
            } else {
                // Use the standard racing logic.
                // If needed, print the reason(s) to why the smart racing logic was not started.
                if (enableRacingPlan && !hasFanRequirement && !hasTrophyRequirement) {
                    MessageLog.i(TAG, "[RACE] Smart racing conditions not met due to current settings, using traditional racing logic...")
                    MessageLog.i(TAG, "[RACE] Reason: One or more conditions failed:")
                    if (game.currentDate.year != DateYear.JUNIOR) {
                        if (!enableFarmingFans) MessageLog.i(TAG, "[RACE]   - enableFarmingFans is false")
                        if (enableForceRacing) MessageLog.i(TAG, "[RACE]   - enableForceRacing is true")
                    } else {
                        MessageLog.i(TAG, "[RACE]   - It is currently the Junior Year.")
                    }
                }

                processStandardRacing()
            }

            if (!success) {
                // Clear requirement flags if race selection failed.
                Log.w(TAG, "[RACE] Failed to select a race. Aborting racing...")
                clearRacingRequirementFlags()
                return false
            }
        } else if (isScheduledRace) {
            MessageLog.i(TAG, "[RACE] Confirming the scheduled race dialog...")
            ButtonRace.click(game.imageUtils, tries = 30)
            game.waitForLoading()
        }

        // Confirm the selection and the resultant popup and then wait for the game to load.
        game.findAndTapImage("race_confirm", tries = 30, region = game.imageUtils.regionBottomHalf)
        game.wait(1.0)
        game.findAndTapImage("race_confirm", tries = 10, region = game.imageUtils.regionBottomHalf)
        game.wait(2.0)

        // Handle race strategy override if enabled.
        selectRaceStrategy()
        game.wait(1.0)

        // Skip the race if possible, otherwise run it manually.
        runRaceWithRetries()
        finalizeRaceResults(isExtra = true)

        // Clear the next smart race day tracker since we just completed a race.
        nextSmartRaceDay = null

        MessageLog.i(TAG, "[RACE] Racing process for Extra Race${if (isScheduledRace) " (scheduled) " else " "}is completed.")
        MessageLog.i(TAG, "********************")
        return true
    }

    /**
     * Handles extra races using Smart Racing logic.
     *
     * @param mandatoryExtraRaceData Race data for for the extra race that is mandatory to run. If provided, this race will be selected immediately if found on the screen.
     * @return True if a race was successfully selected and ready to run; false if the process was canceled.
     */
    private fun processSmartRacing(mandatoryExtraRaceData: RaceData? = null): Boolean {
        MessageLog.i(TAG, "[RACE] Using Smart Racing Plan logic...")

        // Updates the current date and aptitudes for accurate scoring.
        game.updateDate()

        // Use cached user planned races and race plan data.
        MessageLog.i(TAG, "[RACE] Loaded ${userPlannedRaces.size} user-selected races and ${raceData.size} race entries.")

        // Detects all double-star race predictions on screen.
        val doublePredictionLocations = game.imageUtils.findAll("race_extra_double_prediction")
        MessageLog.i(TAG, "[RACE] Found ${doublePredictionLocations.size} double-star prediction locations.")
        if (doublePredictionLocations.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No double-star predictions found. Canceling racing process.")
            return false
        }

        // Extracts race names from the screen and matches them with the in-game database.
        MessageLog.i(TAG, "[RACE] Extracting race names and matching with database...")
        val currentRaces = doublePredictionLocations.flatMap { location ->
            val raceName = game.imageUtils.extractRaceName(location)
            val raceDataList = lookupRaceInDatabase(game.currentDate.day, raceName)
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

        // If mandatory extra race data is provided, immediately find and select it on screen.
        if (mandatoryExtraRaceData != null) {
            MessageLog.i(TAG, "[RACE] Mandatory mode for extra races enabled. Looking for planned race \"${mandatoryExtraRaceData.name}\" on screen for turn ${game.currentDate.day}.")

            // Check if there are multiple races with the same formatted name but different fan counts.
            val raceVariants = currentRaces.filter { it.nameFormatted == mandatoryExtraRaceData.nameFormatted }
            val hasMultipleFanVariants = raceVariants.size > 1
            if (hasMultipleFanVariants) {
                MessageLog.i(TAG, "[RACE] Found ${raceVariants.size} variants with different fan counts.")
            }

            // Search for the mandatory extra race with scroll retry logic.
            // Some devices show fewer races due to shorter screen heights, so scroll down up to 2 times to check.
            val maxScrollAttempts = 2
            var currentDoublePredictions = doublePredictionLocations

            for (scrollAttempt in 0..maxScrollAttempts) {
                // Find the mandatory extra race on screen.
                val mandatoryExtraRaceLocation = findRaceLocationByName(currentDoublePredictions, mandatoryExtraRaceData.name)

                if (mandatoryExtraRaceLocation != null) {
                    // If multiple fan variants exist in database, scroll to try to find the higher-fan version.
                    if (hasMultipleFanVariants && scrollAttempt == 0) {
                        MessageLog.i(TAG, "[RACE] Found a match but database shows multiple fan variants. Scrolling to check for higher-fan version...")
                        val firstFoundLocation = mandatoryExtraRaceLocation

                        val newPredictions = scrollRaceListAndRedetect(scrollDown = true)
                        if (newPredictions != null) {
                            currentDoublePredictions = newPredictions
                            val higherFanLocation = findRaceLocationByName(currentDoublePredictions, mandatoryExtraRaceData.name)

                            if (higherFanLocation != null) {
                                MessageLog.i(TAG, "[RACE] ✓ Found higher-fan variant after scrolling. Selecting it.")
                                game.tap(higherFanLocation.x, higherFanLocation.y, "race_extra_double_prediction", ignoreWaiting = true)
                                return true
                            } else {
                                // Not found after scroll, scroll back up and use the first found location.
                                MessageLog.i(TAG, "[RACE] Higher-fan variant not found after scrolling. Scrolling back up...")
                                val restoredPredictions = scrollRaceListAndRedetect(scrollDown = false)
                                if (restoredPredictions != null) {
                                    currentDoublePredictions = restoredPredictions
                                    val relocatedLocation = findRaceLocationByName(currentDoublePredictions, mandatoryExtraRaceData.name)
                                    val finalLocation = relocatedLocation ?: firstFoundLocation
                                    MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" found. Selecting it.")
                                    game.tap(finalLocation.x, finalLocation.y, "race_extra_double_prediction", ignoreWaiting = true)
                                    return true
                                } else {
                                    MessageLog.i(TAG, "[RACE] Could not scroll back. Using first found position.")
                                    game.tap(firstFoundLocation.x, firstFoundLocation.y, "race_extra_double_prediction", ignoreWaiting = true)
                                    return true
                                }
                            }
                        }
                    }

                    MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" found on screen with double predictions${if (scrollAttempt > 0) " after $scrollAttempt scroll(s)" else ""}. Selecting it immediately (skipping opportunity cost analysis).")
                    game.tap(mandatoryExtraRaceLocation.x, mandatoryExtraRaceLocation.y, "race_extra_double_prediction", ignoreWaiting = true)
                    return true
                }

                // If not found and we have scrolls remaining, scroll down and re-detect.
                if (scrollAttempt < maxScrollAttempts) {
                    MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" not found on current screen. Scrolling down (attempt ${scrollAttempt + 1}/$maxScrollAttempts)...")

                    val newPredictions = scrollRaceListAndRedetect(scrollDown = true)
                    if (newPredictions == null) {
                        MessageLog.i(TAG, "[RACE] Stopping scroll attempts due to scroll failure.")
                        break
                    }

                    currentDoublePredictions = newPredictions
                    MessageLog.i(TAG, "[RACE] After scrolling, found ${currentDoublePredictions.size} double-star prediction locations.")
                    if (currentDoublePredictions.isEmpty()) {
                        MessageLog.i(TAG, "[RACE] No double-star predictions found after scrolling. Stopping scroll attempts.")
                        break
                    }
                }
            }

            MessageLog.i(TAG, "[RACE] Mandatory extra race \"${mandatoryExtraRaceData.name}\" not found on screen after $maxScrollAttempts scroll(s). Canceling racing process.")
            return false
        }

        if (currentRaces.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No races matched in database. Canceling racing process.")
            return false
        }
        MessageLog.i(TAG, "[RACE] Successfully matched ${currentRaces.size} races in database.")

        // If trophy requirement is active, filter to only G1 races.
        // Trophy requirement is independent of racing plan and farming fans settings.
        // If Pre-OP or G3 criteria is active, any race can fulfill the requirement.
        val racesForSelection = if (hasTrophyRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
            val g1Races = currentRaces.filter { it.grade == RaceGrade.G1 }
            if (g1Races.isEmpty()) {
                // No G1 races available. Cancel since trophy requirement specifically needs G1 races.
                MessageLog.i(TAG, "[RACE] Trophy requirement active but no G1 races available. Canceling racing process (independent of racing plan/farming fans).")
                return false
            } else {
                MessageLog.i(TAG, "[RACE] Trophy requirement active. Filtering to ${g1Races.size} G1 races: ${g1Races.map { it.name }}.")
                g1Races
            }
        } else if (hasTrophyRequirement && (hasPreOpOrAboveRequirement || hasG3OrAboveRequirement)) {
            if (hasPreOpOrAboveRequirement) {
                MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria active. Using all ${currentRaces.size} races.")
            } else {
                MessageLog.i(TAG, "[RACE] Trophy requirement with G3 or above criteria active. Using all ${currentRaces.size} races.")
            }
            currentRaces
        } else {
            currentRaces
        }

        // Separate matched races into planned vs unplanned.
        val (plannedRaces, regularRaces) = racesForSelection.partition { race ->
            userPlannedRaces.any { it.raceName == race.name }
        }

        // Log which races are user-selected vs regular.
        MessageLog.i(TAG, "[RACE] Found ${plannedRaces.size} user-selected races on screen: ${plannedRaces.map { it.name }}.")
        MessageLog.i(TAG, "[RACE] Found ${regularRaces.size} regular races on screen: ${regularRaces.map { it.name }}.")

        // Filter both lists by user Racing Plan settings.
        // If trophy requirement is active, bypass min fan filtering but still apply other filters.
        val filteredPlannedRaces = if (hasTrophyRequirement) {
            if (hasPreOpOrAboveRequirement || hasG3OrAboveRequirement) {
                MessageLog.i(TAG, "[RACE] Trophy requirement active. Bypassing min fan threshold for all valid races.")
            } else {
                MessageLog.i(TAG, "[RACE] Trophy requirement active. Bypassing min fan threshold for G1 races.")
            }
            filterRacesByCriteria(plannedRaces, bypassMinFans = true)
        } else {
            filterRacesByCriteria(plannedRaces)
        }
        val filteredRegularRaces = if (hasTrophyRequirement) {
            filterRacesByCriteria(regularRaces, bypassMinFans = true)
        } else {
            filterRacesByCriteria(regularRaces)
        }
        MessageLog.i(TAG, "[RACE] After filtering: ${filteredPlannedRaces.size} planned races and ${filteredRegularRaces.size} regular races remain.")

        // Combine all filtered races for Opportunity Cost analysis.
        val allFilteredRaces = filteredPlannedRaces + filteredRegularRaces
        if (allFilteredRaces.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No races match current settings after filtering. Canceling racing process.")
            return false
        }

        // Evaluate whether the bot should race now using Opportunity Cost logic.
        // If fan or trophy requirement is active, bypass opportunity cost to prioritize clearing the requirement.
        if (hasFanRequirement || hasTrophyRequirement) {
            MessageLog.i(TAG, "[RACE] Bypassing opportunity cost analysis to prioritize satisfying the current requirement.")
        } else if (!evaluateOpportunityCost(allFilteredRaces, lookAheadDays)) {
            MessageLog.i(TAG, "[RACE] Smart racing suggests waiting for better opportunities. Canceling racing process.")
            return false
        }

        // Decide which races to score based on availability.
        val racesToScore = if (filteredPlannedRaces.isNotEmpty()) {
            // Prefer planned races, but include regular races for comparison.
            MessageLog.i(TAG, "[RACE] Prioritizing ${filteredPlannedRaces.size} planned races with ${filteredRegularRaces.size} regular races for comparison.")
            filteredPlannedRaces + filteredRegularRaces
        } else {
            // No planned races available, use regular races only.
            MessageLog.i(TAG, "[RACE] No planned races available, using ${filteredRegularRaces.size} regular races only.")
            filteredRegularRaces
        }

        // Score all eligible races with bonus for planned races.
        val scoredRaces = racesToScore.map { race ->
            val baseScore = scoreRace(race)
            if (plannedRaces.contains(race)) {
                // Add a bonus for planned races.
                val bonusScore = baseScore.copy(score = baseScore.score + 50.0)
                MessageLog.i(TAG, "[RACE] Planned race \"${race.name}\" gets a bonus: ${game.decimalFormat.format(baseScore.score)} -> ${game.decimalFormat.format(bonusScore.score)}.")
                bonusScore
            } else {
                baseScore
            }
        }

        // Sort by score and find the best race.
        val sortedScoredRaces = scoredRaces.sortedByDescending { it.score }
        val bestRace = sortedScoredRaces.first()

        MessageLog.i(TAG, "[RACE] Best race selected: ${bestRace.raceData.name} (score: ${game.decimalFormat.format(bestRace.score)}).")
        if (plannedRaces.contains(bestRace.raceData)) {
            MessageLog.i(TAG, "[RACE] Selected race is from user's planned races list.")
        } else {
            MessageLog.i(TAG, "[RACE] Selected race is from regular available races.")
        }

        // Check if there are multiple races with the same formatted name but different fan counts.
        // If so, we may need to scroll to find the higher-fan version (game sorts by fan count ascending and ordered by grade).
        // Reuse the already-extracted currentRaces list instead of querying the database again.
        val targetRaceMatches = currentRaces.filter { it.nameFormatted == bestRace.raceData.nameFormatted }
        val hasMultipleFanVariants = targetRaceMatches.size > 1

        // Locates the best race on screen and selects it.
        MessageLog.i(TAG, "[RACE] Looking for target race \"${bestRace.raceData.name}\" on screen...")
        var currentDoublePredictions = doublePredictionLocations
        var targetRaceLocation = findRaceLocationByName(currentDoublePredictions, bestRace.raceData.name, logMatch = true)

        // If multiple fan variants exist and we found one, try scrolling to find the higher-fan version.
        if (hasMultipleFanVariants && targetRaceLocation != null) {
            MessageLog.i(TAG, "[RACE] Found a match but there may be a higher-fan variant below (game sorts by fan count ascending).")
            val firstFoundLocation = targetRaceLocation
            
            // Scroll down to look for the higher-fan variant.
            MessageLog.i(TAG, "[RACE] Scrolling down to check for higher-fan variant...")
            val newPredictions = scrollRaceListAndRedetect(scrollDown = true)
            if (newPredictions != null) {
                currentDoublePredictions = newPredictions
                val newTargetLocation = findRaceLocationByName(currentDoublePredictions, bestRace.raceData.name)

                if (newTargetLocation != null) {
                    MessageLog.i(TAG, "[RACE] ✓ Found higher-fan variant at location (${newTargetLocation.x}, ${newTargetLocation.y}) after scrolling.")
                    targetRaceLocation = newTargetLocation
                } else {
                    // Not found after scroll, scroll back up and use the first found location.
                    MessageLog.i(TAG, "[RACE] Higher-fan variant not found after scrolling. Scrolling back up...")
                    val restoredPredictions = scrollRaceListAndRedetect(scrollDown = false)
                    if (restoredPredictions != null) {
                        currentDoublePredictions = restoredPredictions
                        targetRaceLocation = findRaceLocationByName(currentDoublePredictions, bestRace.raceData.name)

                        if (targetRaceLocation == null) {
                            MessageLog.i(TAG, "[RACE] Could not re-locate target race after scrolling back. Using last known position.")
                            targetRaceLocation = firstFoundLocation
                        } else {
                            MessageLog.i(TAG, "[RACE] ✓ Re-located target race at (${targetRaceLocation.x}, ${targetRaceLocation.y}) after scrolling back.")
                        }
                    } else {
                        MessageLog.i(TAG, "[RACE] Could not scroll back. Using first found position.")
                        targetRaceLocation = firstFoundLocation
                    }
                }
            }
        }

        if (targetRaceLocation == null) {
            MessageLog.i(TAG, "[RACE] Could not find target race \"${bestRace.raceData.name}\" on screen. Canceling racing process.")
            return false
        }

        MessageLog.i(TAG, "[RACE] Selecting smart racing choice: ${bestRace.raceData.name} (score: ${game.decimalFormat.format(bestRace.score)}).")
        game.tap(targetRaceLocation.x, targetRaceLocation.y, "race_extra_double_prediction", ignoreWaiting = true)

        return true
    }

    /**
     * Handles extra races using the standard or traditional racing logic.
     *
     * @return True if a race was successfully selected; false if the process was canceled.
     */
    private fun processStandardRacing(): Boolean {
        MessageLog.i(TAG, "[RACE] Using traditional racing logic for extra races...")

        // Detects double-star races on screen.
        var doublePredictionLocations = game.imageUtils.findAll("race_extra_double_prediction")

        // If no double predictions found and fans/Pre-OP/G3 requirement is active and is after Junior Year, scroll to find them.
        if (doublePredictionLocations.isEmpty() && game.currentDate.year != DateYear.JUNIOR && (hasFanRequirement || hasPreOpOrAboveRequirement || hasG3OrAboveRequirement)) {
            val maxScrollAttempts = 5
            MessageLog.i(TAG, "[RACE] No double-star predictions found on initial screen. Scrolling to find races to satisfy fans/pre-op requirement...")

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
            MessageLog.w(TAG, "No extra races with double predictions found on screen. Canceling racing process.")
            return false
        }

        // If only one race has double predictions, check if it's G1 when trophy requirement is active.
        // If Pre-OP or G3 criteria is active, any race is acceptable.
        if (maxCount == 1) {
            if (hasTrophyRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
                game.updateDate()
                val raceName = game.imageUtils.extractRaceName(doublePredictionLocations[0])
                val raceDataList = lookupRaceInDatabase(game.currentDate.day, raceName)
                // Check if any matched race is G1.
                if (raceDataList.any { it.grade == RaceGrade.G1 }) {
                    MessageLog.i(TAG, "[RACE] Only one race with double predictions and it's G1. Selecting it.")
                    game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, "race_extra_double_prediction", ignoreWaiting = true)
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
                game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, "race_extra_double_prediction", ignoreWaiting = true)
                return true
            } else {
                MessageLog.i(TAG, "[RACE] Only one race with double predictions. Selecting it.")
                game.tap(doublePredictionLocations[0].x, doublePredictionLocations[0].y, "race_extra_double_prediction", ignoreWaiting = true)
                return true
            }
        }

        // Otherwise, iterates through each extra race to determine fan gain and double prediction status.
        val (sourceBitmap, templateBitmap) = game.imageUtils.getBitmaps("race_extra_double_prediction")
        val listOfRaces = ArrayList<RaceDetails>()
        val extraRaceLocations = ArrayList<Point>()
        val raceNamesList = ArrayList<String>()

        for (count in 0 until maxCount) {
            val selectedExtraRace = game.imageUtils.findImage("race_extra_selection", region = game.imageUtils.regionBottomHalf).first ?: break
            extraRaceLocations.add(selectedExtraRace)

            // Extract race name for G1 filtering if trophy requirement is active.
            if (hasTrophyRequirement && count < doublePredictionLocations.size) {
                val raceName = game.imageUtils.extractRaceName(doublePredictionLocations[count])
                raceNamesList.add(raceName)
            }

            val raceDetails = game.imageUtils.determineExtraRaceFans(selectedExtraRace, sourceBitmap, templateBitmap!!, forceRacing = enableForceRacing)
            listOfRaces.add(raceDetails)

            if (count + 1 < maxCount) {
                val nextX = game.imageUtils.relX(selectedExtraRace.x, -100)
                val nextY = game.imageUtils.relY(selectedExtraRace.y, 150)
                game.tap(nextX.toDouble(), nextY.toDouble(), "race_extra_selection", ignoreWaiting = true)
            }

            game.wait(0.5)
        }

        // If trophy requirement is active, filter to only G1 races.
        val (filteredRaces, filteredLocations, _) = if (hasTrophyRequirement && !hasPreOpOrAboveRequirement && !hasG3OrAboveRequirement) {
            game.updateDate()
            val g1Indices = raceNamesList.mapIndexedNotNull { index, raceName ->
                val raceDataList = lookupRaceInDatabase(game.currentDate.day, raceName)
                // Check if any matched race is G1.
                if (raceDataList.any { it.grade == RaceGrade.G1 }) index else null
            }

            if (g1Indices.isEmpty()) {
                // No G1 races available. Cancel since trophy requirement specifically needs G1 races.
                // Trophy requirement is independent of racing plan and farming fans settings.
                MessageLog.i(TAG, "[RACE] Trophy requirement active but no G1 races available. Canceling racing process (independent of racing plan/farming fans).")
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
        MessageLog.i(TAG, "[RACE] Number of fans detected for each extra race are: ${filteredRaces.joinToString(", ") { it.fans.toString() }}")

        // Evaluates which race to select based on maximum fans and double prediction priority (if force racing is enabled).
        val index = if (!enableForceRacing) {
            filteredRaces.indexOfFirst { it.fans == maxFans }
        } else {
            filteredRaces.indexOfFirst { it.hasDoublePredictions }.takeIf { it != -1 } ?: filteredRaces.indexOfFirst { it.fans == maxFans }
        }

        // Selects the determined race on screen.
        MessageLog.i(TAG, "[RACE] Selecting extra race at option #${index + 1}.")
        val target = filteredLocations[index]
        game.tap(
            target.x - game.imageUtils.relWidth((100 * 1.36).toInt()),
            target.y - game.imageUtils.relHeight(70),
            "race_extra_selection",
            ignoreWaiting = true,
        )

        return true
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    // Helper Functions
    ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Loads the user's selected in-game race agenda.
     * This function navigates through the agenda UI, finds the matching agenda,
     * and loads it. If the agenda is not immediately visible, it will scroll
     * the list to find it.
     */
    fun loadUserRaceAgenda() {
        // Only load the agenda once per career.
        if (!enableUserInGameRaceAgenda || hasLoadedUserRaceAgenda || game.currentDate.bIsFinaleSeason) {
            return
        } else if (game.imageUtils.findImage("race_maiden_criteria", tries = 1, region = game.imageUtils.regionTopHalf).first != null) {
            MessageLog.i(TAG, "[RACE] A maiden race needs to be won first before applying the user's race agenda.")
            return
        }

        // Navigate to the race selection screen.
        if (!ButtonRaceSelectExtra.click(game.imageUtils)) {
            MessageLog.w(TAG, "[RACE] Could not find the Race Selection button. Skipping loading the user's race agenda.")
            return
        }

        game.waitForLoading()

        // We are forced to race, so we need to ignore this warning dialog.
        game.campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))

        game.waitForLoading()

        MessageLog.i(TAG, "[RACE] Loading user's in-game race agenda: $selectedUserAgenda")
        
        // It is assumed that the user is already at the screen with the list of selectable races.
        // Now tap on the Agenda button.
        if (!game.findAndTapImage("race_agenda", tries = 1, region = game.imageUtils.regionBottomHalf)) {
            MessageLog.w(TAG, "[RACE] Could not find the Agenda button. Backing out and skipping agenda loading.")
            ButtonBack.click(game.imageUtils)
            game.waitForLoading()
            return
        }
        game.waitForLoading()

        // Now tap on the My Agenda button.
        if (!game.findAndTapImage("race_my_agenda", tries = 1, region = game.imageUtils.regionBottomHalf)) {
            MessageLog.w(TAG, "[RACE] Could not find the My Agenda button. Closing and backing out.")
            ButtonClose.click(game.imageUtils)
            game.wait(0.5)
            ButtonBack.click(game.imageUtils)
            game.waitForLoading()
            return
        }
        game.waitForLoading()
        
        // Check if an agenda is already loaded. If so, then the user must have loaded this earlier in the career so no need to select it again.
        if (game.imageUtils.findImage("race_agenda_empty", tries = 1, region = game.imageUtils.regionTopHalf).first == null) {
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
            val loadListButtonLocations = game.imageUtils.findAll("race_agenda_load_list")
            
            if (loadListButtonLocations.isEmpty()) {
                MessageLog.w(TAG, "[RACE] No Load List buttons found on screen.")
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
                if (agendaText == selectedUserAgenda) {
                    MessageLog.i(TAG, "[RACE] ✓ Found $selectedUserAgenda. Tapping the Load List button...")
                    game.gestureUtils.tap(buttonLocation.x, buttonLocation.y, "race_agenda_load_list")
                    game.wait(0.5)
                    
                    // Tap the overwrite button.
                    game.findAndTapImage("race_agenda_overwrite", tries = 1, region = game.imageUtils.regionMiddle)
                    game.waitForLoading()
                    
                    foundAgenda = true
                    break
                }
                
                // Check if we've reached "Agenda 8" (end of list).
                if (agendaText == "Agenda 8") {
                    MessageLog.w(TAG, "[RACE] Reached Agenda 8 but target $selectedUserAgenda not found.")
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
            MessageLog.w(TAG, "[RACE] Could not find $selectedUserAgenda after $swipeCount swipe(s). Closing agenda selection.")
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
            val raceDataList = lookupRaceInDatabase(game.currentDate.day, raceName)
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
     * Checks if the racer's aptitudes match the race requirements (both terrain and distance must be B or greater).
     *
     * @param raceData The race data to check aptitudes against.
     * @return True if both track surface and distance aptitudes are B or greater; false otherwise.
     */
    private fun checkRaceAptitudeMatch(raceData: RaceData): Boolean {
        val trackSurfaceAptitude: Aptitude = game.trainee.checkTrackSurfaceAptitude(raceData.trackSurface)
        val trackDistanceAptitude: Aptitude = game.trainee.checkTrackDistanceAptitude(raceData.trackDistance)

        val trackSurfaceMatch: Boolean = trackSurfaceAptitude >= Aptitude.B
        val trackDistanceMatch: Boolean = trackDistanceAptitude >= Aptitude.B

        return trackSurfaceMatch && trackDistanceMatch
    }

    /**
     * Finds the mandatory planned race for the current turn number if mandatory mode is enabled.
     *
     * @return A Pair containing the PlannedRace and RaceData for the mandatory extra race if found, null otherwise.
     */
    private fun findMandatoryExtraRaceForCurrentTurn(): Pair<PlannedRace?, RaceData?> {
        if (!enableRacingPlan || !enableMandatoryRacingPlan) {
            Log.d(TAG, "[RACE] Mandatory racing plan is not enabled so skipping the search for a mandatory extra race.")
            return Pair(null, null)
        }

        val currentTurnNumber = game.currentDate.day

        // Find planned race matching current turn number.
        val matchingPlannedRace = userPlannedRaces.find { it.turnNumber == currentTurnNumber }
        if (matchingPlannedRace == null) {
            Log.d(TAG, "[RACE] No mandatory extra race found for current turn number $currentTurnNumber.")
            return Pair(null, null)
        }

        // Look up race data from raceData map.
        val raceData = this.raceData[matchingPlannedRace.raceName]
        if (raceData == null) {
            MessageLog.e(TAG, "[ERROR] Planned race \"${matchingPlannedRace.raceName}\" not found in race data.")
            return Pair(null, null)
        }

        return Pair(matchingPlannedRace, raceData)
    }

    /**
     * Check if there are fan or trophy requirements that need to be satisfied.
     */
    fun checkRacingRequirements(sourceBitmap: Bitmap? = null) {
        // Skip racing requirements checks during Summer.
        if (game.currentDate.isSummer()) {
            if (hasFanRequirement || hasTrophyRequirement) {
                MessageLog.i(TAG, "[RACE] It is currently Summer. Skipping racing requirements checks and clearing flags.")
                clearRacingRequirementFlags()
            }
            return
        }

        // Check for fan requirement on the main screen.
        val sourceBitmap = sourceBitmap ?: game.imageUtils.getSourceBitmap()
        val needsFanRequirement = game.imageUtils.findImageWithBitmap("race_fans_criteria", sourceBitmap, region = game.imageUtils.regionTopHalf, customConfidence = 0.90) != null
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
            val needsTrophyRequirement = game.imageUtils.findImageWithBitmap("race_trophies_criteria", sourceBitmap, region = game.imageUtils.regionTopHalf, customConfidence = 0.90) != null
            if (needsTrophyRequirement) {
                hasTrophyRequirement = true
                
                // Check for Pre-OP or above criteria.
                val needsPreOpOrAbove = game.imageUtils.findImageWithBitmap("race_pre_op_or_above_criteria", sourceBitmap, region = game.imageUtils.regionTopHalf, customConfidence = 0.90) != null
                if (needsPreOpOrAbove) {
                    hasPreOpOrAboveRequirement = true
                    MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria detected. Any race can be run to fulfill the requirement.")
                } else {
                    hasPreOpOrAboveRequirement = false
                }

                // Check for G3 or above criteria.
                val needsG3OrAbove = game.imageUtils.findImageWithBitmap("race_g3_or_above_criteria", sourceBitmap, region = game.imageUtils.regionTopHalf, customConfidence = 0.90) != null
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
     * Determines if the extra racing process should be started now or later.
     *
     * @return True if the current date is okay to start the extra racing process and false otherwise.
     */
    fun checkEligibilityToStartExtraRacingProcess(): Boolean {
        MessageLog.i(TAG, "\n[RACE] Now determining eligibility to start the extra racing process...")
        val turnsRemaining = game.imageUtils.determineTurnsRemainingBeforeNextGoal()
        MessageLog.i(TAG, "[RACE] Current remaining number of days before the next mandatory race: $turnsRemaining.")

        // If the setting to force racing extra races is enabled, always return true.
        if (enableForceRacing) {
            Log.d(TAG, "[RACE] Force racing is enabled so eligibility to start extra races will be true.")
            return true
        }

        // Check for common restrictions that apply to both smart and standard racing via screen checks.
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        if (game.checkFinals()) {
            MessageLog.i(TAG, "[RACE] It is UMA Finals right now so there will be no extra races. Stopping extra race check.")
            return false
        } else if (game.imageUtils.findImageWithBitmap("race_select_extra_locked", sourceBitmap, region = game.imageUtils.regionBottomHalf) != null) {
            MessageLog.i(TAG, "[RACE] Extra Races button is currently locked. Stopping extra race check.")
            return false
        } else if (game.currentDate.isSummer()) {
            MessageLog.i(TAG, "[RACE] It is currently Summer right now. Stopping extra race check.")
            return false
        }

        // If fan or trophy requirement is detected, bypass smart racing logic to force racing.
        // Both requirements are independent of racing plan and farming fans settings.
        if (hasFanRequirement) {
            MessageLog.i(TAG, "[RACE] Fan requirement detected. Bypassing smart racing logic to fulfill requirement.")
            return !raceRepeatWarningCheck
        } else if (hasTrophyRequirement) {
            if (hasPreOpOrAboveRequirement || hasG3OrAboveRequirement) {
                if (hasPreOpOrAboveRequirement) {
                    MessageLog.i(TAG, "[RACE] Trophy requirement with Pre-OP or above criteria detected. Proceeding to racing screen.")
                } else {
                    MessageLog.i(TAG, "[RACE] Trophy requirement with G3 or above criteria detected. Proceeding to racing screen.")
                }
                return !raceRepeatWarningCheck
            }

            // Check if G1 races exist at current turn before proceeding.
            // If no G1 races are available, it will still allow regular racing if it's a regular race day or smart racing day.
            if (!hasG1RacesAtTurn(game.currentDate.day)) {
                // Skip interval check if Racing Plan is enabled.
                val isRegularRacingDay = enableFarmingFans && !enableRacingPlan && (turnsRemaining % daysToRunExtraRaces == 0)
                val isSmartRacingDay = enableRacingPlan && enableFarmingFans && nextSmartRaceDay == turnsRemaining

                if (isRegularRacingDay || isSmartRacingDay) {
                    MessageLog.i(TAG, "[RACE] Trophy requirement detected but no G1 races at turn ${game.currentDate.day}. Allowing regular racing on eligible day.")
                } else {
                    MessageLog.i(TAG, "[RACE] Trophy requirement detected but no G1 races available at turn ${game.currentDate.day} and not a regular/smart racing day. Skipping racing.")
                    return false
                }
            } else {
                MessageLog.i(TAG, "[RACE] Trophy requirement detected. G1 races available at turn ${game.currentDate.day}. Proceeding to racing screen.")
            }

            return !raceRepeatWarningCheck
        }

        // Check for mandatory racing plan mode (before opportunity cost analysis and while still on the main screen).
        if (enableRacingPlan && enableMandatoryRacingPlan) {
            val currentTurnNumber = game.currentDate.day

            // Find planned race matching current turn number.
            val matchingPlannedRace = userPlannedRaces.find { it.turnNumber == currentTurnNumber }

            if (matchingPlannedRace != null) {
                MessageLog.i(TAG, "[RACE] Found planned race \"${matchingPlannedRace.raceName}\" for turn $currentTurnNumber and mandatory mode for extra races is enabled.")
                return !raceRepeatWarningCheck
            } else {
                MessageLog.i(TAG, "[RACE] No planned race matches current turn $currentTurnNumber and mandatory mode for extra races is enabled. Continuing with normal eligibility checks.")
            }
        } else if (enableFarmingFans && enableRacingPlan && game.currentDate.year != DateYear.JUNIOR) {
            // For Classic and Senior Year, check if planned races are coming up in the look-ahead window and are eligible for racing.
            // Handle the user-selected planned races here.
            if (userPlannedRaces.isNotEmpty()) {
                val currentTurnNumber = game.currentDate.day

                // Check each planned race for eligibility.
                val eligiblePlannedRaces = userPlannedRaces.filter { plannedRace ->
                    val raceDetails = raceData[plannedRace.raceName]
                    if (raceDetails == null) {
                        MessageLog.e(TAG, "[ERROR] Planned race \"${plannedRace.raceName}\" not found in race data.")
                        false
                    } else {
                        val turnDistance = raceDetails.turnNumber - currentTurnNumber

                        // Check if race is within look-ahead window.
                        if (turnDistance < 0 || turnDistance > lookAheadDays) {
                            if (turnDistance > lookAheadDays) {
                                if (game.debugMode) {
                                    MessageLog.d(TAG, "[DEBUG] Planned race \"${plannedRace.raceName}\" is too far ahead of the look-ahead window (distance $turnDistance > lookAheadDays $lookAheadDays).")
                                } else {
                                    Log.d(TAG, "[DEBUG] Planned race \"${plannedRace.raceName}\" is too far ahead of the look-ahead window (distance $turnDistance > lookAheadDays $lookAheadDays).")
                                }
                            }
                            false
                        } else {
                            // For Classic Year, check if it's an eligible racing day.
                            if (game.currentDate.year == DateYear.CLASSIC && !enableRacingPlan) {
                                val isEligible = turnsRemaining % daysToRunExtraRaces == 0
                                if (!isEligible) {
                                    MessageLog.i(TAG, "[RACE] Planned race \"${plannedRace.raceName}\" is not on an eligible racing day (day $turnsRemaining, interval $daysToRunExtraRaces).")
                                }
                                isEligible
                            } else {
                                true
                            }
                        }
                    }
                }

                if (eligiblePlannedRaces.isEmpty()) {
                    MessageLog.i(TAG, "[RACE] No user-selected races are eligible at turn $currentTurnNumber. Continuing with other checks.")
                } else {
                    MessageLog.i(TAG, "[RACE] Found ${eligiblePlannedRaces.size} eligible user-selected races: ${eligiblePlannedRaces.map { it.raceName }}.")
                }
            } else {
                MessageLog.i(TAG, "[RACE] No user-selected races configured. Continuing with other checks.")
            }
        } else if (enableRacingPlan && !enableMandatoryRacingPlan && enableFarmingFans) {
            // Smart racing: Check turn-based eligibility before screen checks.
            // Only run opportunity cost analysis with smartRacingCheckInterval.
            val isCheckInterval = game.currentDate.day % smartRacingCheckInterval == 0

            if (isCheckInterval) {
                MessageLog.i(TAG, "[RACE] Running opportunity cost analysis at turn ${game.currentDate.day} (smartRacingCheckInterval: every $smartRacingCheckInterval turns)...")

                // Check if there are any races available at the current turn.
                val currentTurnRaces = queryRacesFromDatabase(game.currentDate.day, 0)
                if (currentTurnRaces.isEmpty()) {
                    MessageLog.i(TAG, "[RACE] No races available at turn ${game.currentDate.day}.")
                    return false
                }

                MessageLog.i(TAG, "[RACE] Found ${currentTurnRaces.size} race(s) at the current turn ${game.currentDate.day}.")

                // Query upcoming races in the look-ahead window for opportunity cost analysis.
                val upcomingRaces = queryRacesFromDatabase(game.currentDate.day + 1, lookAheadDays)
                MessageLog.i(TAG, "[RACE] Found ${upcomingRaces.size} upcoming races in look-ahead window.")

                // Apply filters to both current and upcoming races.
                val filteredCurrentRaces = filterRacesByCriteria(currentTurnRaces)
                val filteredUpcomingRaces = filterRacesByCriteria(upcomingRaces)

                MessageLog.i(TAG, "[RACE] After filtering: ${filteredCurrentRaces.size} current races, ${filteredUpcomingRaces.size} upcoming races.")

                // If no filtered current races exist, we shouldn't race.
                if (filteredCurrentRaces.isEmpty()) {
                    MessageLog.i(TAG, "[RACE] No current races match the filter criteria. Skipping racing.")
                    return false
                }

                // If there are no upcoming races to compare against, race now if we have acceptable races.
                if (filteredUpcomingRaces.isEmpty()) {
                    MessageLog.i(TAG, "[RACE] No upcoming races to compare against. Racing now with available races.")
                    nextSmartRaceDay = turnsRemaining
                } else {
                    // Use opportunity cost logic to determine if we should race now or wait.
                    val shouldRace = evaluateOpportunityCost(filteredCurrentRaces, lookAheadDays)
                    if (!shouldRace) {
                        MessageLog.i(TAG, "[RACE] No suitable races at turn ${game.currentDate.day} based on opportunity cost analysis.")
                        return false
                    }

                    // Opportunity cost analysis determined we should race now, so set the optimal race day to the current day.
                    nextSmartRaceDay = turnsRemaining
                }

                MessageLog.i(TAG, "[RACE] Opportunity cost analysis completed, proceeding with screen checks...")
            } else {
                MessageLog.i(TAG, "[RACE] Skipping opportunity cost analysis (turn ${game.currentDate.day} does not match smartRacingCheckInterval). Using cached optimal race day.")
            }

            // Check if current day matches the optimal race day or falls on the interval.
            val isOptimalDay = nextSmartRaceDay == turnsRemaining
            val isIntervalDay = !enableRacingPlan && (turnsRemaining % daysToRunExtraRaces == 0)

            if (isOptimalDay) {
                MessageLog.i(TAG, "[RACE] Current day ($turnsRemaining) matches optimal race day.")
                return !raceRepeatWarningCheck
            } else if (isIntervalDay) {
                MessageLog.i(TAG, "[RACE] Current day ($turnsRemaining) falls on racing interval ($daysToRunExtraRaces).")
                return !raceRepeatWarningCheck
            } else {
                if (enableRacingPlan) {
                    MessageLog.i(TAG, "[RACE] Current day ($turnsRemaining) is not optimal (next: $nextSmartRaceDay).")
                } else {
                    MessageLog.i(TAG, "[RACE] Current day ($turnsRemaining) is not optimal (next: $nextSmartRaceDay, interval: $daysToRunExtraRaces).")
                }
                return false
            }
        }

        // Conditionally start the standard racing process.
        // This fallback only applies when Racing Plan is disabled, so use interval-based logic.
        return enableFarmingFans && !enableRacingPlan && (turnsRemaining % daysToRunExtraRaces == 0) && !raceRepeatWarningCheck
    }

    internal fun updateRaceScreenRunningStyleAptitudes(): Boolean {
        val bitmap = game.imageUtils.getSourceBitmap()
        val bbox = BoundingBox(
            x = game.imageUtils.relX(0.0, 125),
            y = game.imageUtils.relY(0.0, 1140),
            w = game.imageUtils.relWidth(825),
            h = game.imageUtils.relHeight(45),
        )
        var text: String = game.imageUtils.performOCROnRegion(
            bitmap,
            bbox.x,
            bbox.y,
            bbox.w,
            bbox.h,
            useThreshold=false,
            useGrayscale=false,
            debugName="updateRaceScreenRunningStyleAptitudes",
        )
        if (text == "") {
            MessageLog.w(TAG, "performOCROnRegion did not detect any text.")
            return false
        }
        text = text.replace("[^A-Za-z]".toRegex(), "").lowercase()
        val substrings = listOf("end", "late", "pace", "front")
        val parts = text.split(*substrings.toTypedArray()).filter { it.isNotBlank() }
        if (parts.size != 4) {
            MessageLog.w(TAG, "performOCROnRegion returned a malformed string: $text")
            return false
        }
        val styleMap: Map<String, String> = substrings.zip(parts).toMap()
        for ((styleString, aptitudeString) in styleMap) {
            val style: RunningStyle? = RunningStyle.fromShortName(styleString)
            if (style == null) {
                MessageLog.w(TAG, "performOCROnRegion returned invalid running style: $styleString")
                return false
            }
            val aptitude: Aptitude? = Aptitude.fromName(aptitudeString)
            if (aptitude == null) {
                MessageLog.w(TAG, "performOCROnRegion returned invalid aptitude for running style: $style -> $aptitudeString")
                return false
            }
            game.trainee.setRunningStyleAptitude(style, aptitude)
        }

        MessageLog.d(TAG, "Set temporary running style aptitudes: ${game.trainee.runningStyleAptitudes}")
        return true
    }

	/**
	 * Handles race strategy override for Junior Year races.
	 *
	 * During Junior Year: Applies the user-selected strategy and stores the original.
	 * After Junior Year: Restores the original strategy and disables the feature.
	 */
	fun selectRaceStrategy() {
		val isJuniorYear = game.currentDate.year == DateYear.JUNIOR
		val isPastJuniorYear = game.currentDate.year.ordinal > DateYear.JUNIOR.ordinal

		// Determine if a strategy override or reversion is needed.
		val needsOverride = isJuniorYear && !hasAppliedStrategyOverride && juniorYearRaceStrategy != userSelectedOriginalStrategy
		val needsReversion = isPastJuniorYear && hasAppliedStrategyOverride

		if (
			!game.trainee.bHasUpdatedAptitudes &&
			!game.trainee.bTemporaryRunningStyleAptitudesUpdated
		) {
			// If trainee aptitudes are unknown, this means we probably started the bot
			// at the race screen. We need to open the race strategy dialog and
			// read the aptitudes in from there.
			MessageLog.i(TAG, "Setting running style and performing temporary initial aptitude check.")
			ButtonChangeRunningStyle.click(imageUtils = game.imageUtils, tries = 10)
			game.wait(0.5, skipWaitingForLoading = true)
			var tries = 10
			while (tries > 0 && !game.campaign.handleDialogs().first) {
				tries--
			}
		} else if (!game.trainee.bHasSetRunningStyle || needsOverride || needsReversion) {
			if (needsOverride) {
				MessageLog.i(TAG, "[RACE] Junior Year detected. Applying Junior race strategy override: $juniorYearRaceStrategy")
			} else if (needsReversion) {
				MessageLog.i(TAG, "[RACE] Past Junior Year detected. Reverting to original race strategy: $userSelectedOriginalStrategy")
			} else {
				// If we haven't set the trainee's running style yet, open the dialog.
				MessageLog.i(TAG, "Setting running style for the first time.")
			}

			ButtonChangeRunningStyle.click(imageUtils = game.imageUtils, tries = 10)
			game.wait(0.5, skipWaitingForLoading = true)
			var tries = 10
			while (tries > 0 && !game.campaign.handleDialogs().first) {
				tries--
			}
		}
	}

    /**
     * Executes the race with retry logic.
     *
     * @return True if the bot completed the race with retry attempts remaining. Otherwise false.
     */
    fun runRaceWithRetries(): Boolean {
        MessageLog.i(TAG, "[RACE] Proceeding to handle the race...")

        do {
            if (game.tryHandleAllDialogs()) {
                continue
            }

            val bitmap: Bitmap = game.imageUtils.getSourceBitmap()

            when {
                // Attempt to skip the race.
                ButtonViewResults.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Clicked ViewResults button to skip race.")
                }
                // If the race skip is locked.
                ButtonViewResultsLocked.check(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Skip is locked. Preparing to run the race manually.")
                    ButtonRaceManual.click(game.imageUtils, sourceBitmap = bitmap)
                }
                ButtonRaceManual.click(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Started the manual race.")
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
                ButtonNext.check(game.imageUtils, sourceBitmap = bitmap) -> {
                    MessageLog.i(TAG, "[RACE] Reached race results screen. Exiting race retry loop...")
                    return true
                }
                // Otherwise click to progress through screens.
                else -> game.tap(350.0, 450.0, "ok", taps = 3)
            }
        } while (raceRetries >= 0)

        MessageLog.d(TAG, "runRaceWithRetries: No retries remaining ($raceRetries). Returning FALSE.")
        return false
    }

    /**
     * Finishes up and confirms the results of the race and its success.
     *
     * @param isExtra Flag to determine the following actions to finish up this mandatory or extra race.
     */
    fun finalizeRaceResults(isExtra: Boolean = false): Boolean {
        MessageLog.i(TAG, "[RACE] Now performing cleanup and finishing the race.")

        // Always reset flags after successful race completion, regardless of UI flow.
        firstTimeRacing = false
        clearRacingRequirementFlags()

        // Bot should be at the screen where it shows the final positions of all participants.
        if (!ButtonNext.check(game.imageUtils, tries = 30)) {
            MessageLog.e(TAG, "Cannot start the cleanup process for finishing the race. Moving on...")
            return false
        }
        
        // Max time limit for the while loop to attempt to finalize race results.
        // It really shouldn't ever take this long.
        val startTime: Long = System.currentTimeMillis()
        val maxTimeMs: Long = 30000
        while (System.currentTimeMillis() - startTime < maxTimeMs) {
            if (game.tryHandleAllDialogs()) {
                // Don't want to start next iteration too quick since dialogs
                // may still be in process of closing.
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
                    return true
                }
                // Tap on the screen to progress through screens.
                else -> game.tap(350.0, 750.0, "ok", taps = 3)
            }
        }
        return false
    }

    /**
     * Race database lookup using exact and/or fuzzy matching.
     * Returns multiple matches when races share the same name and date but have different fan counts.
     *
     * @param turnNumber The current turn number to match against.
     * @param detectedName The race name detected by OCR.
     * @return A list of [RaceData] objects matching the criteria, or an empty list if no matches found.
     */
    private fun lookupRaceInDatabase(turnNumber: Int, detectedName: String): ArrayList<RaceData> {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.initialize()) {
            MessageLog.e(TAG, "[ERROR] Database not available for race lookup.")
            return arrayListOf()
        }

        return try {
            MessageLog.i(TAG, "[RACE] Looking up race for turn $turnNumber with detected name: \"$detectedName\".")

            val database = settingsManager.getDatabase()
            if (database == null) {
                MessageLog.e(TAG, "[RACE] Database not available for race lookup.")
                settingsManager.close()
                return arrayListOf()
            }

            val matches = arrayListOf<RaceData>()

            // Do exact matching based on the info gathered.
            val exactCursor = database.query(
                TABLE_RACES,
                arrayOf(
                    RACES_COLUMN_NAME,
                    RACES_COLUMN_GRADE,
                    RACES_COLUMN_FANS,
                    RACES_COLUMN_NAME_FORMATTED,
                    RACES_COLUMN_TRACK_SURFACE,
                    RACES_COLUMN_TRACK_DISTANCE,
                    RACES_COLUMN_TURN_NUMBER
                ),
                "$RACES_COLUMN_TURN_NUMBER = ? AND $RACES_COLUMN_NAME_FORMATTED = ?",
                arrayOf(turnNumber.toString(), detectedName),
                null, null, null
            )

            // Collect all exact matches (may have different fan counts).
            if (exactCursor.moveToFirst()) {
                do {
                    val race = RaceData(
                        name = exactCursor.getString(0),
                        grade = exactCursor.getString(1),
                        fans = exactCursor.getInt(2),
                        nameFormatted = exactCursor.getString(3),
                        trackSurface = exactCursor.getString(4),
                        trackDistance = exactCursor.getString(5),
                        turnNumber = exactCursor.getInt(6)
                    )
                    matches.add(race)
                } while (exactCursor.moveToNext())

                exactCursor.close()
                settingsManager.close()

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
            val fuzzyCursor = database.query(
                TABLE_RACES,
                arrayOf(
                    RACES_COLUMN_NAME,
                    RACES_COLUMN_GRADE,
                    RACES_COLUMN_FANS,
                    RACES_COLUMN_NAME_FORMATTED,
                    RACES_COLUMN_TRACK_SURFACE,
                    RACES_COLUMN_TRACK_DISTANCE,
                    RACES_COLUMN_TURN_NUMBER
                ),
                "$RACES_COLUMN_TURN_NUMBER = ?",
                arrayOf(turnNumber.toString()),
                null, null, null
            )

            if (!fuzzyCursor.moveToFirst()) {
                fuzzyCursor.close()
                settingsManager.close()
                MessageLog.i(TAG, "[RACE] No match found for turn $turnNumber with name \"$detectedName\".")
                return arrayListOf()
            }

            val similarityService = StringSimilarityServiceImpl(JaroWinklerStrategy())
            var bestScore = 0.0
            val fuzzyMatches = mutableListOf<Pair<RaceData, Double>>()

            do {
                val nameFormatted = fuzzyCursor.getString(3)
                val similarity = similarityService.score(detectedName, nameFormatted)

                if (similarity >= SIMILARITY_THRESHOLD) {
                    val race = RaceData(
                        name = fuzzyCursor.getString(0),
                        grade = fuzzyCursor.getString(1),
                        fans = fuzzyCursor.getInt(2),
                        nameFormatted = nameFormatted,
                        trackSurface = fuzzyCursor.getString(4),
                        trackDistance = fuzzyCursor.getString(5),
                        turnNumber = fuzzyCursor.getInt(6)
                    )
                    fuzzyMatches.add(Pair(race, similarity))
                    if (similarity > bestScore) bestScore = similarity
                    if (game.debugMode) MessageLog.d(TAG, "[DEBUG] Fuzzy match candidate: \"${race.name}\" AKA \"$nameFormatted\" with similarity ${game.decimalFormat.format(similarity)} (Fans: ${race.fans}).")
                    else Log.d(TAG, "[DEBUG] Fuzzy match candidate: \"${race.name}\" AKA \"$nameFormatted\" with similarity ${game.decimalFormat.format(similarity)} (Fans: ${race.fans}).")
                }
            } while (fuzzyCursor.moveToNext())

            fuzzyCursor.close()
            settingsManager.close()

            // Return all matches with the best similarity score.
            val bestMatches = ArrayList(fuzzyMatches.filter { it.second == bestScore }.map { it.first })

            if (bestMatches.isNotEmpty()) {
                if (bestMatches.size == 1) {
                    MessageLog.i(TAG, "[RACE] Found fuzzy match: \"${bestMatches[0].name}\" AKA \"${bestMatches[0].nameFormatted}\" with similarity ${game.decimalFormat.format(bestScore)} (Fans: ${bestMatches[0].fans}).")
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
            MessageLog.e(TAG, "[ERROR] Error looking up race: ${e.message}.")
            settingsManager.close()
            arrayListOf()
        }
    }

    /**
     * Calculates a composite race score based on fan count, race grade, and aptitude performance.
     *
     * The score is derived from three weighted factors:
     * - **Fans:** Normalized to a 0–100 scale.
     * - **Grade:** Weighted to a map of values based on grade.
     * - **Aptitude:** Adds a bonus if both track surface and distance aptitudes are A or S.
     *
     * The final score is the average of these three components.
     *
     * @param race The [RaceData] instance to evaluate.
     * @return A [ScoredRace] object containing the final score and individual factor breakdowns.
     */
    private fun scoreRace(race: RaceData): ScoredRace {
        // Normalize fans to 0-100 scale (assuming max fans is 30000).
        val fansScore = (race.fans.toDouble() / 30000.0) * 100.0
        
        // Grade scoring: G1 = 75, G2 = 50, G3 = 25.
        val gradeScore = when (race.grade) {
            RaceGrade.G1 -> 75.0
            RaceGrade.G2 -> 50.0
            RaceGrade.G3 -> 25.0
            else -> 0.0
        }
        
        // Get the trainee's aptitude for this race's track surface/distance.
        val trackSurfaceAptitude: Aptitude = game.trainee.checkTrackSurfaceAptitude(race.trackSurface)
        val trackDistanceAptitude: Aptitude = game.trainee.checkTrackDistanceAptitude(race.trackDistance)
        
        // Aptitude bonus: 100 if both track surface and distance are >= B aptitude, else 0.
        val trackSurfaceMatch: Boolean = trackSurfaceAptitude >= Aptitude.B
        val trackDistanceMatch: Boolean = trackDistanceAptitude >= Aptitude.B

        val aptitudeBonus = if (trackSurfaceMatch && trackDistanceMatch) 100.0 else 0.0
        
        // Calculate final score with equal weights.
        val finalScore = (fansScore + gradeScore + aptitudeBonus) / 3.0
        
        // Log detailed scoring breakdown for debugging.
        if (game.debugMode) MessageLog.d(
            TAG,
            """
            [DEBUG] Scoring ${race.name}:
            Fans            = ${race.fans} (${game.decimalFormat.format(fansScore)})
            Grade           = ${race.grade} (${game.decimalFormat.format(gradeScore)})
            Track Surface   = ${race.trackSurface} ($trackSurfaceAptitude)
            Track Distance  = ${race.trackDistance} ($trackDistanceAptitude)
            Aptitude        = ${game.decimalFormat.format(aptitudeBonus)}
            Final           = ${game.decimalFormat.format(finalScore)}
            """.trimIndent()
        )
        
        return ScoredRace(
            raceData = race,
            score = finalScore,
            fansScore = fansScore,
            gradeScore = gradeScore,
            aptitudeBonus = aptitudeBonus
        )
    }

    /**
     * Database queries for races.
     *
     * @param currentTurn The current turn number used as the starting point.
     * @param lookAheadDays The number of days (turns) to look ahead for upcoming races.
     * @return A list of [RaceData] objects representing all races within the look-ahead window.
     */
    private fun queryRacesFromDatabase(currentTurn: Int, lookAheadDays: Int): List<RaceData> {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.initialize()) {
            MessageLog.e(TAG, "[ERROR] Database not available for race lookup.")
            return emptyList()
        }

        return try {
            val database = settingsManager.getDatabase()
            if (database == null) {
                MessageLog.e(TAG, "[ERROR] Database is null for race lookup.")
                return emptyList()
            }

            val endTurn = currentTurn + lookAheadDays
            val cursor = database.query(
                TABLE_RACES,
                arrayOf(
                    RACES_COLUMN_NAME,
                    RACES_COLUMN_GRADE,
                    RACES_COLUMN_FANS,
                    RACES_COLUMN_NAME_FORMATTED,
                    RACES_COLUMN_TRACK_SURFACE,
                    RACES_COLUMN_TRACK_DISTANCE,
                    RACES_COLUMN_TURN_NUMBER
                ),
                "$RACES_COLUMN_TURN_NUMBER >= ? AND $RACES_COLUMN_TURN_NUMBER <= ?",
                arrayOf(currentTurn.toString(), endTurn.toString()),
                null, null, "$RACES_COLUMN_TURN_NUMBER ASC"
            )

            val races = mutableListOf<RaceData>()
            if (cursor.moveToFirst()) {
                do {
                    val race = RaceData(
                        name = cursor.getString(0),
                        grade = cursor.getString(1),
                        fans = cursor.getInt(2),
                        nameFormatted = cursor.getString(3),
                        trackSurface = cursor.getString(4),
                        trackDistance = cursor.getString(5),
                        turnNumber = cursor.getInt(6)
                    )
                    races.add(race)
                } while (cursor.moveToNext())
            }
            cursor.close()
            settingsManager.close()
            
            MessageLog.i(TAG, "[RACE] Found ${races.size} races in look-ahead window (turns $currentTurn to $endTurn).")
            races
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] Error getting races from database: ${e.message}")
            settingsManager.close()
            emptyList()
        }
    }

    /**
     * Checks if any G1 races exist at the specified turn number in the database.
     *
     * @param turnNumber The turn number to check for G1 races.
     * @return True if at least one G1 race exists at the specified turn, false otherwise.
     */
    private fun hasG1RacesAtTurn(turnNumber: Int): Boolean {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.initialize()) {
            MessageLog.e(TAG, "[ERROR] Database not available for G1 race check.")
            return false
        }

        return try {
            val database = settingsManager.getDatabase()
            if (database == null) {
                MessageLog.e(TAG, "[ERROR] Database is null for G1 race check.")
                return false
            }

            val cursor = database.query(
                TABLE_RACES,
                arrayOf(RACES_COLUMN_GRADE),
                "$RACES_COLUMN_TURN_NUMBER = ? AND $RACES_COLUMN_GRADE = ?",
                arrayOf(turnNumber.toString(), "G1"),
                null, null, null
            )

            val hasG1 = cursor.count > 0
            cursor.close()
            settingsManager.close()
            
            hasG1
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] Error checking for G1 races: ${e.message}")
            settingsManager.close()
            false
        }
    }

    /**
     * Filters the given list of races according to the user's Racing Plan settings.
     *
     * @param races The list of [RaceData] entries to filter.
     * @param bypassMinFans If true, bypasses the minimum fans threshold check (useful for trophy requirement).
     * @return A list of [RaceData] objects that satisfy all Racing Plan filter criteria.
     */
    private fun filterRacesByCriteria(races: List<RaceData>, bypassMinFans: Boolean = false): List<RaceData> {
        // Parse preferred grades.
        val preferredGrades = try {
            // Parse as JSON array.
            val jsonArray = JSONArray(preferredGradesString)
            val parsed = (0 until jsonArray.length()).map { jsonArray.getString(it).uppercase() }
            MessageLog.i(TAG, "[RACE] Parsed preferred grades as JSON array: $parsed.")
            parsed
        } catch (e: Exception) {
            MessageLog.i(TAG, "[RACE] Error parsing preferred grades: ${e.message}, using fallback.")
            val parsed = preferredGradesString.split(",").map { it.trim().uppercase() }
            MessageLog.i(TAG, "[RACE] Fallback parsing result: $parsed")
            parsed
        }

        // Parse preferred distances.
        val preferredDistances = try {
            // Parse as JSON array.
            val jsonArray = JSONArray(preferredTrackDistanceString)
            val parsed = (0 until jsonArray.length()).map { jsonArray.getString(it).uppercase() }
            MessageLog.i(TAG, "[RACE] Parsed preferred distances as JSON array: $parsed.")
            parsed
        } catch (e: Exception) {
            MessageLog.i(TAG, "[RACE] Error parsing preferred distances: ${e.message}, using fallback.")
            val parsed = preferredTrackDistanceString.split(",").map { it.trim().uppercase() }
            MessageLog.i(TAG, "[RACE] Fallback parsing result: $parsed")
            parsed
        }

        if (game.debugMode) MessageLog.d(TAG, "[DEBUG] Filter criteria: Min fans: $minFansThreshold, trackSurface: $preferredTrackSurfaceString, grades: $preferredGrades, distances: $preferredDistances")
        else Log.d(TAG, "[DEBUG] Filter criteria: Min fans: $minFansThreshold, trackSurface: $preferredTrackSurfaceString, grades: $preferredGrades, distances: $preferredDistances")

        val filteredRaces = races.filter { race ->
            val isRequirementActive = hasFanRequirement || hasTrophyRequirement
            val meetsFansThreshold = bypassMinFans || isRequirementActive || race.fans >= minFansThreshold
            val meetsTrackSurfacePreference = isRequirementActive || preferredTrackSurfaceString == "Any" || race.trackSurface == TrackSurface.fromName(preferredTrackSurfaceString)
            val meetsGradePreference = isRequirementActive || preferredGrades.isEmpty() || preferredGrades.contains(race.grade.name)
            val meetsTrackDistancePreference = isRequirementActive || preferredDistances.isEmpty() || preferredDistances.contains(race.trackDistance.name)

            val passes = meetsFansThreshold && meetsTrackSurfacePreference && meetsGradePreference && meetsTrackDistancePreference

            // If the race did not pass any of the filters, print the reason why.
            if (!passes) {
                val reasons = mutableListOf<String>()
                if (!meetsFansThreshold) reasons.add("fans ${race.fans} < $minFansThreshold")
                if (!meetsTrackSurfacePreference) reasons.add("trackSurface ${race.trackSurface} != $preferredTrackSurfaceString")
                if (!meetsGradePreference) reasons.add("grade ${race.grade} not in $preferredGrades")
                if (!meetsTrackDistancePreference) reasons.add("distance ${race.trackDistance} not in $preferredDistances")
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] ✗ Filtered out ${race.name}: ${reasons.joinToString(", ")}")
                else Log.d(TAG, "[DEBUG] ✗ Filtered out ${race.name}: ${reasons.joinToString(", ")}")
            } else {
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] ✓ Passed filter: ${race.name} (fans: ${race.fans}, trackSurface: ${race.trackSurface}, grade: ${race.grade}, distance: ${race.trackDistance})")
                else Log.d(TAG, "[DEBUG] ✓ Passed filter: ${race.name} (fans: ${race.fans}, trackSurface: ${race.trackSurface}, grade: ${race.grade}, distance: ${race.trackDistance})")
            }
            
            passes
        }
        
        return filteredRaces
    }

    /**
     * Evaluates opportunity cost to determine whether the bot should race immediately or wait for a better opportunity.
     *
     * @param currentRaces List of currently available [RaceData] races.
     * @param lookAheadDays Number of turns/days to consider for upcoming races.
     * @return True if the bot should race now, false if it is better to wait for a future race.
     */
    private fun evaluateOpportunityCost(currentRaces: List<RaceData>, lookAheadDays: Int): Boolean {
        MessageLog.i(TAG, "[RACE] Evaluating whether to race now using Opportunity Cost logic...")
        if (currentRaces.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No current races available, cannot race now.")
            return false
        }
        
        // Score current races.
        MessageLog.i(TAG, "[RACE] Scoring ${currentRaces.size} current races (sorted by score descending):")
        val currentScoredRaces = currentRaces.map { scoreRace(it) }
        val sortedScoredRaces = currentScoredRaces.sortedByDescending { it.score }
        sortedScoredRaces.forEach { scoredRace ->
            MessageLog.i(TAG, "[RACE]     Current race: ${scoredRace.raceData.name} (score: ${game.decimalFormat.format(scoredRace.score)})")
        }
        val bestCurrentRace = sortedScoredRaces.maxByOrNull { it.score }
        
        if (bestCurrentRace == null) {
            MessageLog.i(TAG, "[RACE] Failed to score current races, cannot race now.")
            return false
        }
        
        MessageLog.i(TAG, "[RACE] Best current race: ${bestCurrentRace.raceData.name} (score: ${game.decimalFormat.format(bestCurrentRace.score)})")
        
        // Get and score upcoming races.
        MessageLog.i(TAG, "[RACE] Looking ahead $lookAheadDays days for upcoming races...")
        val upcomingRaces = queryRacesFromDatabase(game.currentDate.day + 1, lookAheadDays)
        MessageLog.i(TAG, "[RACE] Found ${upcomingRaces.size} upcoming races in database.")
        
        val filteredUpcomingRaces = filterRacesByCriteria(upcomingRaces)
        MessageLog.i(TAG, "[RACE] After filtering: ${filteredUpcomingRaces.size} upcoming races remain.")
        
        if (filteredUpcomingRaces.isEmpty()) {
            MessageLog.i(TAG, "[RACE] No suitable upcoming races found, racing now with best current option.")
            return true
        }
        
        // Score all upcoming races and find the best one.
        val scoredUpcomingRaces = filteredUpcomingRaces.map { scoreRace(it) }
        val sortedUpcomingScoredRaces = scoredUpcomingRaces.sortedByDescending { it.score }
        val bestUpcomingRace = sortedUpcomingScoredRaces.maxByOrNull { it.score }
        
        if (bestUpcomingRace == null) {
            MessageLog.i(TAG, "[RACE] No suitable upcoming races found, racing now with best current option.")
            return true
        }
        
        MessageLog.i(TAG, "[RACE] Best upcoming race: ${bestUpcomingRace.raceData.name} (score: ${game.decimalFormat.format(bestUpcomingRace.score)}).")
        
        // Apply time decay to upcoming race score.
        val discountedUpcomingScore = bestUpcomingRace.score * timeDecayFactor
        
        // Calculate opportunity cost: How much better is waiting?
        val improvementFromWaiting = discountedUpcomingScore - bestCurrentRace.score
        
        // Decision criteria.
        val isGoodEnough = bestCurrentRace.score >= minimumQualityThreshold
        val notWorthWaiting = improvementFromWaiting < improvementThreshold
        val shouldRace = isGoodEnough && notWorthWaiting
        
        MessageLog.i(TAG, "[RACE] Opportunity Cost Analysis:")
        MessageLog.i(TAG, "[RACE]     Current score: ${game.decimalFormat.format(bestCurrentRace.score)}")
        MessageLog.i(TAG, "[RACE]     Upcoming score (raw): ${game.decimalFormat.format(bestUpcomingRace.score)}")
        MessageLog.i(TAG, "[RACE]     Upcoming score (discounted by ${game.decimalFormat.format((1 - timeDecayFactor) * 100)}%): ${game.decimalFormat.format(discountedUpcomingScore)}")
        MessageLog.i(TAG, "[RACE]     Improvement from waiting: ${game.decimalFormat.format(improvementFromWaiting)}")
        MessageLog.i(TAG, "[RACE]     Quality check (≥${minimumQualityThreshold}): ${if (isGoodEnough) "PASS" else "FAIL"}")
        MessageLog.i(TAG, "[RACE]     Worth waiting check (<${improvementThreshold}): ${if (notWorthWaiting) "PASS" else "FAIL"}")
        MessageLog.i(TAG, "[RACE]     Decision: ${if (shouldRace) "RACE NOW" else "WAIT FOR BETTER OPPORTUNITY"}")

        // Print the reasoning for the decision.
        if (shouldRace) {
            MessageLog.i(TAG, "[RACE] Reasoning: Current race is good enough (${game.decimalFormat.format(bestCurrentRace.score)} ≥ ${minimumQualityThreshold}) and waiting only gives ${game.decimalFormat.format(improvementFromWaiting)} more points (less than ${improvementThreshold}).")
            // Race now - clear the next race day tracker.
            nextSmartRaceDay = null
        } else {
            val reason = if (!isGoodEnough) {
                "Current race quality too low (${game.decimalFormat.format(bestCurrentRace.score)} < ${minimumQualityThreshold})."
            } else {
                "Worth waiting for better opportunity (+${game.decimalFormat.format(improvementFromWaiting)} points > ${improvementThreshold})."
            }
            MessageLog.i(TAG, "[RACE] Reasoning: $reason")
            // Wait for better opportunity - store the turn number to race on.
            val bestUpcomingRaceData = upcomingRaces.find { it.name == bestUpcomingRace.raceData.name }
            nextSmartRaceDay = bestUpcomingRaceData?.turnNumber
            MessageLog.i(TAG, "[RACE] Setting next smart race day to turn ${nextSmartRaceDay}.")
        }
        
        return shouldRace
    }
}
