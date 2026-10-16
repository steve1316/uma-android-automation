package com.steve1316.uma_android_automation.bot.career_setup

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.CampaignBreakpointException
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.solver.MandatoryRaces
import com.steve1316.uma_android_automation.components.ButtonAutoSelect
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonBorrowSupportCard
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonCareerStartArrowRight
import com.steve1316.uma_android_automation.components.ButtonCareerStartDeckArrowLeft
import com.steve1316.uma_android_automation.components.ButtonCareerStartDeckArrowRight
import com.steve1316.uma_android_automation.components.ButtonCareerStartHomeCareer
import com.steve1316.uma_android_automation.components.ButtonCareerStartIndependentStart
import com.steve1316.uma_android_automation.components.ButtonCareerStartLegacyReset
import com.steve1316.uma_android_automation.components.ButtonCareerStartStorySkipOff
import com.steve1316.uma_android_automation.components.ButtonCareerStartStorySkipOne
import com.steve1316.uma_android_automation.components.ButtonCareerStartStorySkipTwo
import com.steve1316.uma_android_automation.components.ButtonCompleteCareer
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonHomeFullStats
import com.steve1316.uma_android_automation.components.ButtonMenuBarHome
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonNo
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.ButtonSelectLegacy
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.ButtonStartCareer
import com.steve1316.uma_android_automation.components.Checkbox
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.DialogBorrowCard
import com.steve1316.uma_android_automation.components.DialogConfirmAutoSelect
import com.steve1316.uma_android_automation.components.DialogConfirmRestoreRP
import com.steve1316.uma_android_automation.components.DialogDisplaySettings
import com.steve1316.uma_android_automation.components.DialogFinalConfirmation
import com.steve1316.uma_android_automation.components.DialogIndependentTraining
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogUtils
import com.steve1316.uma_android_automation.components.LabelCareerStartIndependentTrainingComplete
import com.steve1316.uma_android_automation.components.LabelCareerStartLegacySelectHeader
import com.steve1316.uma_android_automation.components.LabelCareerStartPotentialLvl
import com.steve1316.uma_android_automation.components.LabelCareerStartScenarioSelectHeader
import com.steve1316.uma_android_automation.components.LabelCareerStartSupportFormationHeader
import com.steve1316.uma_android_automation.components.LabelCareerStartTraineeSelectHeader
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.uma_android_automation.utils.ScrollList
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Point

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Screen geometry, measured at 1080x1920 as offsets from a template on the same screen so it holds wherever the screen puts that template

/** The orange keyword in each scenario's description on Scenario Select, keyed by the `general.scenario` value. */
private val SCENARIO_KEYWORDS: Map<String, String> =
    mapOf(
        "URA Finale" to "urafinale",
        "Unity Cup" to "unitycup",
        "Trackblazer" to "twinklestar",
        "Grand Live" to "grandconcert",
    )

/** The scenario description box on Scenario Select, from the Next button. */
private val SCENARIO_DESCRIPTION = intArrayOf(-455, -316, 900, 180)

/** The outfit and name lines of the selected trainee on Trainee Select, from the screen's header. */
private val TRAINEE_OUTFIT_LINE = intArrayOf(-53, 321, 420, 32)
private val TRAINEE_NAME_LINE = intArrayOf(-53, 353, 420, 36)

/** A trainee card's portrait sits this far above its "Potential Lvl" label. */
private const val TRAINEE_PORTRAIT_DY = -110

/** The "Deck N" title on Support Formation, from the screen's header. */
private val DECK_TITLE = intArrayOf(-74, 71, 300, 60)

/** The card type count columns under the deck on Support Formation, as their center dx from the Borrow button, in game order. */
private val DECK_TYPE_COLUMNS: List<Pair<String, Int>> =
    listOf("Speed" to -606, "Stamina" to -510, "Power" to -414, "Guts" to -318, "Wit" to -222, "Friend" to -126, "Group" to -30)

/** The "xN" count under each type icon, from the Borrow button. */
private const val DECK_COUNT_DY = 270
private const val DECK_COUNT_W = 90
private const val DECK_COUNT_H = 42

/** The Normal Career tab on Final Confirmation, from the Cancel button. */
private const val NORMAL_CAREER_TAB_DX = -20
private const val NORMAL_CAREER_TAB_DY = -1450

/** Screens the loop may pass through before giving up. */
private const val MAX_TICKS = 120

/** Arrow taps allowed on the scenario and deck carousels. */
private const val MAX_CAROUSEL_TAPS = 10

/** The trainee grid's sort pill ("Stars", "Name", ...) and its ascending / descending toggle, from the Next button. */
private val TRAINEE_SORT_PILL = intArrayOf(60, -150, 230, 44)
private val TRAINEE_ORDER_TOGGLE = intArrayOf(360, -150, 110, 44)

/** The Sort tab and the Name option on the Display Settings dialog, from its OK button. */
private const val DISPLAY_SORT_TAB_DX = -494
private const val DISPLAY_SORT_TAB_DY = -1570
private const val DISPLAY_SORT_NAME_DX = -238
private const val DISPLAY_SORT_NAME_DY = -912

/** Cards below this line sit behind the grid's Filters / sort pill bar. Measured from the Next button. */
private const val TRAINEE_GRID_READ_BOTTOM_DY = -161

/** Scroll pages allowed on the trainee grid. */
private const val MAX_TRAINEE_PAGES = 30

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Helpers

/**
 * Converts offsets measured at 1080x1920 from a template into a screen region.
 *
 * @param imageUtils The bot's image utilities, for scaling to the device.
 * @param anchor The template's center on screen.
 * @param offsets The region as dx, dy, width and height from the anchor.
 * @returns The region as x, y, width and height.
 */
internal fun anchoredRegion(imageUtils: CustomImageUtils, anchor: Point, offsets: IntArray): IntArray =
    intArrayOf(imageUtils.relX(anchor.x, offsets[0]), imageUtils.relY(anchor.y, offsets[1]), imageUtils.relWidth(offsets[2]), imageUtils.relHeight(offsets[3]))

/**
 * OCRs a region measured at 1080x1920 from a template.
 *
 * @param imageUtils The bot's image utilities.
 * @param bitmap The screenshot.
 * @param anchor The template's center on screen.
 * @param offsets The region as dx, dy, width and height from the anchor.
 * @param debugName Name for debug images.
 * @returns The text read.
 */
internal fun ocrAnchored(imageUtils: CustomImageUtils, bitmap: Bitmap, anchor: Point, offsets: IntArray, debugName: String): String {
    val (x, y, w, h) = anchoredRegion(imageUtils, anchor, offsets).toList()
    return imageUtils.performOCROnRegion(bitmap, x, y, w, h, useThreshold = false, ocrEngine = "mlkit", debugName = debugName)
}

/**
 * Taps a point measured at 1080x1920 from a template.
 *
 * @param game Reference to the bot's [Game] instance.
 * @param anchor The template's center on screen.
 * @param dx The x offset from the anchor.
 * @param dy The y offset from the anchor.
 */
internal fun tapAnchored(game: Game, anchor: Point, dx: Int, dy: Int) = game.tap(game.imageUtils.relX(anchor.x, dx).toDouble(), game.imageUtils.relY(anchor.y, dy).toDouble())

/**
 * Finds the empty legacy slots from the plus buttons on Legacy Select. Slot 1 is on the left half of the screen and slot 2 on the right.
 *
 * @param plusXs The x of each plus button found.
 * @param screenWidth The screen width.
 * @returns The empty slots, in order.
 */
internal fun emptyLegacySlots(plusXs: List<Double>, screenWidth: Int): List<Int> = plusXs.map { legacySlotOf(it, screenWidth) }.distinct().sorted()

/**
 * Tells which legacy slot a point on Legacy Select belongs to.
 *
 * @param x The point's x.
 * @param screenWidth The screen width.
 * @returns 1 for the left slot, 2 for the right one.
 */
internal fun legacySlotOf(x: Double, screenWidth: Int): Int = if (x < screenWidth / 2) 1 else 2

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Settings

/** The Career Setup settings and catalogs, read once when the setup starts. */
data class CareerSetupSettings(
    /** The scenario to pick on Scenario Select, as stored in `general.scenario`. */
    val scenario: String,
    /** True to keep whichever trainee the game has selected. */
    val usePreselectedTrainee: Boolean,
    /** The trainee outfit's card id, or empty when none is chosen. */
    val trainee: String,
    /** "keep" to keep the last parents (Smart for an empty slot), "smart" to pick by the outfit's derived sparks, or "custom" to pick by [sparkWishList]. */
    val legacyMode: String,
    /** Spark labels in priority order for the custom legacy mode, such as "SPD" or "MILE". */
    val sparkWishList: List<String>,
    /** The support deck to use, 1-10, or 0 to keep the current one. */
    val deckNumber: Int,
    /** The friend support card's support id, or empty for the smart pick. */
    val friendCard: String,
    /** How many times to refresh the Borrow Card list before falling back. */
    val friendMaxRefreshes: Int,
    /** The Smart Race Solver's character preset while the solver is on, else null. */
    val solverCharacter: String?,
    /** Character names with a goal race that is always a Long race, which Smart Auto-Select ranks Stamina first for. */
    val longGoalCharacters: Set<String>,
    /** Trainee outfits keyed by card id. */
    val traineeCards: Map<String, TraineeCard>,
    /** Support cards keyed by support id. */
    val supportCards: Map<String, SupportCard>,
    /** Friend card favorites keyed by scenario. */
    val friendFavorites: Map<String, FriendFavorites>,
) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]CareerSetupSettings"

        /** The wish list used when Smart Auto-Select cannot tell which outfit was picked. */
        val FALLBACK_WISH_LIST: List<String> = listOf("SPD", "STA", "PWR")

        /**
         * Reads the settings and catalogs from SQLite.
         *
         * @returns The current Career Setup settings.
         */
        fun load(): CareerSetupSettings =
            CareerSetupSettings(
                scenario = SettingsHelper.getStringSetting("general", "scenario"),
                usePreselectedTrainee = SettingsHelper.getBooleanSetting("careerSetup", "usePreselectedTrainee", true),
                trainee = SettingsHelper.getStringSetting("careerSetup", "trainee"),
                legacyMode = SettingsHelper.getStringSetting("careerSetup", "legacyMode").ifEmpty { "keep" },
                sparkWishList = SettingsHelper.getStringArraySetting("careerSetup", "sparkWishList", FALLBACK_WISH_LIST),
                deckNumber = SettingsHelper.getIntSetting("careerSetup", "deckNumber", 0),
                friendCard = SettingsHelper.getStringSetting("careerSetup", "friendCard"),
                friendMaxRefreshes = SettingsHelper.getIntSetting("careerSetup", "friendMaxRefreshes", 3),
                solverCharacter =
                    SettingsHelper.getStringSetting("racing", "smartRaceSolverCharacterPreset").takeIf {
                        it.isNotEmpty() &&
                            SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver")
                    },
                longGoalCharacters = parseLongGoalCharacters(),
                traineeCards =
                    parseCatalog("traineeCardData") { id, o ->
                        TraineeCard(id, o.getString("name"), o.getString("title"), o.getJSONObject("aptitudes").toStringMap(), o.getJSONObject("growth").toIntMap())
                    },
                supportCards = parseCatalog("supportCardData") { id, o -> SupportCard(id, o.getString("name"), o.getString("title"), o.getString("type"), o.getString("rarity")) },
                friendFavorites = parseCatalog("friendFavoritesData") { _, o -> FriendFavorites(o.getJSONArray("favorites").toStringList(), o.getJSONArray("types").toStringList()) },
            )

        /**
         * Finds the characters with a goal race that is always a Long race. A choice between races only counts when every option is Long.
         *
         * @returns The character names, or an empty set if the objectives are missing or unreadable.
         */
        private fun parseLongGoalCharacters(): Set<String> =
            runCatching {
                MandatoryRaces.parse(SettingsHelper.getStringSetting("racing", "characterObjectivesData").ifEmpty { "{}" })
                    .filterValues { races -> races.any { race -> race.options.isNotEmpty() && race.options.all { it.distanceType == TrackDistance.LONG } } }
                    .keys
            }.getOrElse {
                MessageLog.e(TAG, "[ERROR] Could not read the character objectives for Career Setup: ${it.message}")
                emptySet()
            }

        /**
         * Parses one catalog that the frontend saved at startup.
         *
         * @param key The `careerSetup` setting key holding the catalog JSON.
         * @param build Builds one entry from its key and JSON object.
         * @returns The catalog, or an empty map if it is missing or unreadable.
         */
        private fun <T> parseCatalog(key: String, build: (String, JSONObject) -> T): Map<String, T> =
            runCatching {
                val json = JSONObject(SettingsHelper.getStringSetting("careerSetup", key).ifEmpty { "{}" })
                json.keys().asSequence().associateWith { build(it, json.getJSONObject(it)) }
            }.getOrElse {
                MessageLog.e(TAG, "[ERROR] Could not read the Career Setup catalog $key: ${it.message}")
                emptyMap()
            }

        /**
         * Reads a JSON object of strings.
         *
         * @returns The object as a map.
         */
        private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

        /**
         * Reads a JSON object of numbers.
         *
         * @returns The object as a map.
         */
        private fun JSONObject.toIntMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }

        /**
         * Reads a JSON array of strings.
         *
         * @returns The array as a list.
         */
        private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
    }
}
