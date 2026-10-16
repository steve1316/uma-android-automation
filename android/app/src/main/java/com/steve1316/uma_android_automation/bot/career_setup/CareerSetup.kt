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
