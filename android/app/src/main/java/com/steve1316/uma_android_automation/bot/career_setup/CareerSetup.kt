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

/**
 * Starts a new career from the Home screen: scenario, trainee, legacy parents, support deck, friend card, Final Confirmation, the opening
 * narration, Quick Mode, and the story skip speed. It then hands back to the normal career loop.
 *
 * Each tick reads which setup screen is showing and runs that step, so it picks up from wherever the game is.
 *
 * @property game Reference to the bot's [Game] instance.
 * @property bDryRun True to stop on Final Confirmation instead of starting the career, as the Career Setup Test does to test the setup without spending TP.
 */
class CareerSetup(private val game: Game, private val bDryRun: Boolean = false) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]CareerSetup"

        /**
         * Whether the game is outside a career, on Home or one of the career setup screens.
         *
         * @param game Reference to the bot's [Game] instance.
         * @param bitmap The screenshot to check.
         * @returns True when the bottom menu bar or the Final Confirmation dialog is showing.
         */
        fun isOutsideCareer(game: Game, bitmap: Bitmap): Boolean =
            ButtonMenuBarHome.check(game.imageUtils, sourceBitmap = bitmap) || DialogUtils.getDialogOrNull(game.imageUtils, bitmap)?.name == DialogFinalConfirmation.name
    }

    private val settings = CareerSetupSettings.load()

    /** Whether the legacy parents were picked during this setup. */
    private var bLegacyDone = false

    /** Whether the support deck was set during this setup. */
    private var bDeckDone = false

    /** Whether Start Career! was tapped on Final Confirmation. */
    private var bStartTapped = false

    /** How many cards of each type the deck holds, read before the friend slot is opened. */
    private var deckCounts: Map<String, Int> = emptyMap()

    /** The outfit chosen in settings, or null when the preselected trainee is kept. */
    private val wantedOutfit: TraineeCard? = settings.trainee.takeIf { !settings.usePreselectedTrainee }?.let { settings.traineeCards[it] }

    /** The outfit the career starts with, read on Trainee Select. Smart Auto-Select derives its sparks from it. */
    private var selectedOutfit: TraineeCard? = wantedOutfit

    /** The Trainee Select header's center, which the selected outfit's name lines are measured from. It never moves, so it is found once. */
    private var traineeHeader: Point? = null

    /** Whether a finished Independent Training is being claimed, which ends on its end-of-career screen instead of starting a career. */
    private var bClaimingIndependentTraining = false

    /** The Smart sparks for the outfit picked on Trainee Select, worked out once when the first parent is picked. */
    private val smartWishList: List<String> by lazy {
        val outfit = selectedOutfit
        if (outfit == null) {
            MessageLog.w(TAG, "[CAREER SETUP] Could not tell which outfit was picked, so Smart Auto-Select uses ${CareerSetupSettings.FALLBACK_WISH_LIST.joinToString(", ")}.")
            CareerSetupSettings.FALLBACK_WISH_LIST
        } else {
            SmartSparks.wishList(outfit.aptitudes, outfit.growth, outfit.name in settings.longGoalCharacters).also {
                MessageLog.i(TAG, "[CAREER SETUP] Smart sparks for ${outfit.label}: ${it.joinToString(", ")}.")
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Steps

    /** Taps the right arrow on Scenario Select until the description names the chosen scenario, then taps Next. */
    private fun handleScenarioSelect() {
        val keyword = SCENARIO_KEYWORDS[settings.scenario] ?: throw CampaignBreakpointException("Career Setup does not know the scenario \"${settings.scenario}\".")
        val next = anchorOf(ButtonNext, game.imageUtils.getSourceBitmap())
        repeat(MAX_CAROUSEL_TAPS) {
            val text = ocr(game.imageUtils.getSourceBitmap(), next, SCENARIO_DESCRIPTION, "scenarioDescription").lowercase().filter { it.isLetter() }
            if (text.contains(keyword)) {
                MessageLog.i(TAG, "[CAREER SETUP] Selected the ${settings.scenario} scenario.")
                ButtonNext.click(game.imageUtils, tries = 5)
                game.wait(2.0)
                return
            }
            ButtonCareerStartArrowRight.click(game.imageUtils, tries = 3)
            game.wait(1.5)
        }
        throw CampaignBreakpointException("Could not find the ${settings.scenario} scenario on Scenario Select.")
    }

    /** Picks the trainee on Trainee Select, or keeps the preselected one, then taps Next. */
    private fun handleTraineeSelect() {
        val current = readSelectedOutfit()
        selectedOutfit =
            if (settings.usePreselectedTrainee) {
                current ?: selectedOutfit
            } else {
                val wanted = wantedOutfit ?: throw CampaignBreakpointException("No trainee is set in Career Setup. Pick one or turn on \"Use Preselected Trainee\".")
                current?.takeIf { it.id == wanted.id } ?: findTrainee(wanted) ?: throw CampaignBreakpointException("Could not find ${wanted.label} on Trainee Select.")
            }
        MessageLog.i(TAG, "[CAREER SETUP] Trainee chosen: ${selectedOutfit?.label ?: "an outfit not in the catalog"}.")
        // The solver plans races from its own character preset, so a different trainee would race to the wrong objectives and aptitudes.
        CareerCatalog.solverMismatch(selectedOutfit, settings.solverCharacter)?.let {
            throw CampaignBreakpointException(
                "${selectedOutfit?.label} does not match the Smart Race Solver's character ($it). Set the solver's character on the Schedule page or turn the solver off.",
            )
        }
        ButtonNext.click(game.imageUtils, tries = 5)
        game.wait(2.0)
    }

    /**
     * Picks both legacy parents on the overview, or backs out of a picker the bot did not open.
     *
     * @param bitmap The current screenshot.
     */
    private fun handleLegacySelect(bitmap: Bitmap) {
        if (ButtonConfirm.check(game.imageUtils, sourceBitmap = bitmap)) {
            // A picker left open from before this setup started. Back out so the slots are filled in order.
            ButtonBack.click(game.imageUtils, sourceBitmap = bitmap)
            game.wait(1.5)
            return
        }
        if (!ButtonAutoSelect.check(game.imageUtils, sourceBitmap = bitmap)) {
            game.wait(1.0)
            return
        }
        if (!bLegacyDone) {
            bLegacyDone = true
            if (settings.legacyMode == "keep") {
                if (!keepLastParents(bitmap)) return
            } else {
                // Clear both slots first so neither picker shows the other slot's parent as taken.
                ButtonCareerStartLegacyReset.click(game.imageUtils, sourceBitmap = bitmap)
                game.wait(1.5)
                val wanted = if (settings.legacyMode == "custom") settings.sparkWishList else smartWishList
                val bSkipGuests = pickParent(1, skipGuests = false, wanted)
                pickParent(2, skipGuests = bSkipGuests, wanted)
            }
        }
        ButtonNext.click(game.imageUtils, tries = 5)
        game.wait(2.0)
    }

    /**
     * Sets the deck, opens the friend slot when it is empty, and taps Start Career! once a friend is borrowed.
     *
     * @param bitmap The current screenshot.
     */
    private fun handleSupportFormation(bitmap: Bitmap) {
        if (!bDeckDone) {
            selectDeck()
            bDeckDone = true
            return
        }
        val borrow = ButtonBorrowSupportCard.findImageWithBitmap(game.imageUtils, bitmap)
        if (borrow != null) {
            deckCounts = readDeckCounts(bitmap, borrow)
            ButtonBorrowSupportCard.click(game.imageUtils, sourceBitmap = bitmap)
            game.wait(1.5)
            return
        }
        if (ButtonStartCareer.click(game.imageUtils, sourceBitmap = bitmap)) game.wait(1.5)
    }

    /** Claims a finished Independent Training from its dialog, or stops while it is still running. */
    private fun handleIndependentTraining() {
        if (!LabelCareerStartIndependentTrainingComplete.check(game.imageUtils)) {
            DialogIndependentTraining.close(game.imageUtils, tries = 3)
            throw CampaignBreakpointException("An Independent Training is still running. Start the bot again once it finishes.")
        }
        // Claiming completes that career, which cannot be undone, so a dry run stops here.
        if (bDryRun) throw CampaignBreakpointException("Career Setup Test: an Independent Training is finished and ready to claim. Stopping before claiming it.")
        MessageLog.i(TAG, "[CAREER SETUP] The Independent Training is complete. Claiming its results.")
        bClaimingIndependentTraining = true
        DialogIndependentTraining.ok(game.imageUtils, tries = 3)
        game.wait(3.0)
    }

    /**
     * Handles the dialogs owned by the setup and passes every other dialog to the task.
     *
     * @param dialog The dialog on screen.
     */
    private fun handleDialog(dialog: DialogInterface) {
        when (dialog.name) {
            DialogFinalConfirmation.name -> handleFinalConfirmation()

            DialogBorrowCard.name -> {
                if (!FriendPicker(game, settings).pick(deckCounts)) throw CampaignBreakpointException("Could not borrow a friend support card.")
                game.wait(1.5)
            }

            DialogConfirmAutoSelect.name -> confirmAutoSelect()

            DialogIndependentTraining.name -> handleIndependentTraining()

            // Starting a career with too little TP asks to restore it, which shares its title and buttons with the RP prompt. TP is never restored.
            DialogConfirmRestoreRP.name -> {
                ButtonNo.click(game.imageUtils, tries = 3)
                throw CampaignBreakpointException("Out of TP to start the next career. Stopping bot...")
            }

            else -> {
                val result = game.task.handleDialogs(dialog)
                if (result is DialogHandlerResult.Unhandled) {
                    MessageLog.i(TAG, "[CAREER SETUP] Closing the \"${dialog.name}\" dialog.")
                    dialog.close(game.imageUtils)
                }
            }
        }
        game.wait(1.0)
    }

    /** Makes sure the Normal Career tab is active, then taps Start Career! and checks the career actually starts. */
    private fun handleFinalConfirmation() {
        if (ButtonCareerStartIndependentStart.check(game.imageUtils) && !ButtonStartCareer.check(game.imageUtils)) {
            MessageLog.i(TAG, "[CAREER SETUP] Switching Final Confirmation to the Normal Career tab.")
            tapAnchored(game, anchorOf(ButtonCancel, game.imageUtils.getSourceBitmap()), NORMAL_CAREER_TAB_DX, NORMAL_CAREER_TAB_DY)
            game.wait(1.0)
        }
        if (bDryRun) {
            if (!ButtonStartCareer.check(game.imageUtils, tries = 5)) throw CampaignBreakpointException("Could not find Start Career! on Final Confirmation.")
            throw CampaignBreakpointException("Career Setup Test reached Final Confirmation on the Normal Career tab. Stopping before Start Career!.")
        }
        if (!ButtonStartCareer.click(game.imageUtils, tries = 5)) throw CampaignBreakpointException("Could not find Start Career! on Final Confirmation.")
        MessageLog.i(TAG, "[CAREER SETUP] Tapped Start Career!.")
        bStartTapped = true
        game.wait(3.0)
    }

    /** Accepts the Confirm Auto-Select dialog with "Include guests" unticked, since a guest the game picks may cost monies. */
    private fun confirmAutoSelect() {
        val bitmap = game.imageUtils.getSourceBitmap()
        val checkbox = Checkbox.findImageWithBitmap(game.imageUtils, bitmap)
        if (checkbox != null) {
            // A ticked box has a green checkmark and an unticked one a grey checkmark.
            val boxSize = game.imageUtils.relWidth(44)
            if (game.imageUtils.isRegionGreen(bitmap, checkbox.x.toInt() - boxSize / 2, checkbox.y.toInt() - boxSize / 2, boxSize, boxSize)) {
                MessageLog.i(TAG, "[CAREER SETUP] Unticking \"Include guests\" so Auto-Select never pays for a borrow.")
                game.tap(checkbox.x, checkbox.y)
                game.wait(0.5)
            }
        }
        DialogConfirmAutoSelect.ok(game.imageUtils)
    }

    /**
     * Summarizes what the setup will do, so a pick the user did not expect can be traced to its setting.
     *
     * @returns The plan, such as "keep the selected trainee, keep the last parents, ...".
     */
    private fun describePlan(): String {
        val trainee = if (settings.usePreselectedTrainee) "keep the selected trainee" else "pick ${wantedOutfit?.label ?: "no trainee set"}"
        val parents =
            when (settings.legacyMode) {
                "smart" -> "pick parents by the trainee's smart sparks"
                "custom" -> "pick parents by sparks (${settings.sparkWishList.joinToString(", ")})"
                else -> "keep the last parents (Smart pick for an empty slot)"
            }
        val deck = if (settings.deckNumber in 1..10) "use Deck ${settings.deckNumber}" else "keep the current deck"
        val friend = settings.supportCards[settings.friendCard]?.let { "borrow ${it.label}" } ?: "borrow a smart friend card"
        return "$trainee, $parents, $deck, $friend"
    }

    /** Taps the story skip button once and lets its animation finish, moving it one step toward full speed. */
    private fun stepUpStorySkip() {
        MessageLog.i(TAG, "[CAREER SETUP] Raising the story skip speed.")
        ButtonCareerStartStorySkipOff.click(game.imageUtils) || ButtonCareerStartStorySkipOne.click(game.imageUtils)
        game.wait(1.0, skipWaitingForLoading = true)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /**
     * Keeps the last legacy parents. An empty slot shows the plus button, for example once the guest borrows run out, and only that slot gets a
     * Smart pick while the other parent stays.
     *
     * @param bitmap The current screenshot.
     * @returns False if the game's Auto-Select was used instead, so Next waits for the next tick.
     */
    private fun keepLastParents(bitmap: Bitmap): Boolean {
        val emptySlots = emptyLegacySlots(ButtonSelectLegacy.findAll(game.imageUtils, sourceBitmap = bitmap).map { it.x }, game.imageUtils.displayWidth)
        if (emptySlots.isNotEmpty()) {
            MessageLog.i(TAG, "[CAREER SETUP] Legacy slot ${emptySlots.joinToString(" and ")} is empty, so it gets a Smart pick and the last parents otherwise stay.")
            // The kept parent may already be the guest borrow, so a single empty slot skips the Guests tab.
            var bSkipGuests = emptySlots.size == 1
            for (slot in emptySlots) bSkipGuests = pickParent(slot, bSkipGuests, smartWishList)
        } else if (ButtonNext.checkDisabled(game.imageUtils, bitmap) == true) {
            MessageLog.i(TAG, "[CAREER SETUP] Next is disabled but no legacy slot looks empty. Using the game's Auto-Select.")
            ButtonAutoSelect.click(game.imageUtils, sourceBitmap = bitmap)
            game.wait(1.5)
            return false
        } else {
            MessageLog.i(TAG, "[CAREER SETUP] Keeping the last legacy parents.")
        }
        return true
    }

    /**
     * Opens a Legacy slot and picks its parent.
     *
     * @param slot 1 for the left slot, 2 for the right one.
     * @param skipGuests True to skip the Guests tab, since the other slot holds a guest or found none free.
     * @param wishList The sparks to score the parents against, most wanted first.
     * @returns True if the next slot should skip the Guests tab: a guest was taken, or none was free to borrow.
     * @throws CampaignBreakpointException When no parent could be picked.
     */
    private fun pickParent(slot: Int, skipGuests: Boolean, wishList: List<String>): Boolean {
        MessageLog.i(TAG, "[CAREER SETUP] Picking legacy parent $slot.")
        // An empty slot shows a large plus button. Tapping the "Select a Legacy" text beside it does nothing.
        val plus =
            ButtonSelectLegacy.findAll(game.imageUtils).firstOrNull { legacySlotOf(it.x, game.imageUtils.displayWidth) == slot }
                ?: throw CampaignBreakpointException("Legacy $slot has no plus button to open its picker.")
        game.tap(plus.x, plus.y)
        game.wait(1.5)
        game.waitForAny(ButtonConfirm, timeoutSec = 10.0, bHandleDialogs = false) ?: throw CampaignBreakpointException("The Legacy $slot picker did not open.")
        val picker = LegacyPicker(game, settings, wishList)
        val bGuest = picker.pick(skipGuests) ?: throw CampaignBreakpointException("Could not pick legacy parent $slot.")
        game.waitForAny(ButtonAutoSelect, timeoutSec = 10.0, bHandleDialogs = false) ?: throw CampaignBreakpointException("Legacy Select did not return after picking parent $slot.")
        return bGuest || picker.noFreeGuest
    }

    /**
     * Reads which outfit is selected at the top of Trainee Select.
     *
     * @returns The outfit, or null if the text matched no outfit in the catalog.
     */
    private fun readSelectedOutfit(): TraineeCard? {
        val bitmap = game.imageUtils.getSourceBitmap()
        val header = traineeHeader ?: anchorOf(LabelCareerStartTraineeSelectHeader, bitmap).also { traineeHeader = it }
        val name = ocr(bitmap, header, TRAINEE_NAME_LINE, "traineeName")
        val title = ocr(bitmap, header, TRAINEE_OUTFIT_LINE, "traineeOutfit")
        return CareerCatalog.match(title, name, settings.traineeCards.values).also { MessageLog.d(TAG, "[DEBUG] readSelectedOutfit:: \"$title $name\" -> ${it?.id}") }
    }

    /**
     * Finds the wanted outfit on Trainee Select. The grid is sorted by Name from whichever end is nearer the trainee. Each page's first and last
     * cards are read to decide whether to scroll up, scroll down, or search that page, so the search works from wherever the list starts.
     *
     * @param wanted The outfit to select.
     * @returns The wanted outfit once it is selected, or null if it is not owned.
     */
    private fun findTrainee(wanted: TraineeCard): TraineeCard? {
        val descending = CareerCatalog.searchDescending(wanted.name, settings.traineeCards.values.map { it.name })
        val next = sortTraineesByName(descending)
        // Like the Legacy grid, the scrollbar is painted over the cards and is not a handle.
        val list = ScrollList.create(game, bThumbIsHandle = false) ?: return null
        list.readBottom = game.imageUtils.relY(next.y, TRAINEE_GRID_READ_BOTTOM_DY)
        MessageLog.i(TAG, "[CAREER SETUP] Searching the Name-sorted trainee list ${if (descending) "descending" else "ascending"} for ${wanted.label}.")

        var lastMove: PageMove? = null
        repeat(MAX_TRAINEE_PAGES) {
            val cards = visibleTraineeCards(list)
            if (cards.isEmpty()) {
                if (!list.scrollDownTracked()) return null
                return@repeat
            }
            val first = selectTraineeCard(cards.first())
            if (first?.id == wanted.id) return wanted
            val last = if (cards.size > 1) selectTraineeCard(cards.last()) else first
            if (last?.id == wanted.id) return wanted

            // An unreadable edge card cannot steer the search, so the page is searched and the list stepped down.
            var move = if (first == null || last == null) PageMove.HERE else CareerCatalog.pageMove(first.name, last.name, wanted.name, descending)
            MessageLog.i(TAG, "[CAREER SETUP] Page shows ${first?.label ?: "an unreadable trainee"} to ${last?.label ?: "an unreadable trainee"}: ${move.name.lowercase()}.")
            if (move == PageMove.HERE) {
                if (cards.drop(1).dropLast(1).any { selectTraineeCard(it)?.id == wanted.id }) return wanted
                // Other outfits of the same trainee can carry on past either edge. Otherwise the outfit is not owned.
                move =
                    when {
                        first == null || last == null -> PageMove.DOWN
                        first.name == wanted.name && lastMove != PageMove.DOWN -> PageMove.UP
                        last.name == wanted.name && lastMove != PageMove.UP -> PageMove.DOWN
                        else -> return null
                    }
            }
            // Turning back means the wanted name falls between two pages already read, so the outfit is not owned.
            if (lastMove != null && move != lastMove) return null
            lastMove = move
            if (!(if (move == PageMove.UP) list.scrollUpTracked() else list.scrollDownTracked())) return null
        }
        return null
    }

    /**
     * Sorts the trainee grid by Name in the given direction, opening Display Settings only when the sort is not already Name.
     *
     * @param descending True for Z to A.
     * @returns The Next button's center, which the grid's sort bar is measured from.
     * @throws CampaignBreakpointException When Display Settings does not open.
     */
    private fun sortTraineesByName(descending: Boolean): Point {
        val bitmap = game.imageUtils.getSourceBitmap()
        val next = anchorOf(ButtonNext, bitmap)
        if (!ocr(bitmap, next, TRAINEE_SORT_PILL, "traineeSort").contains("Name", ignoreCase = true)) {
            MessageLog.i(TAG, "[CAREER SETUP] Sorting the trainee list by Name.")
            tapCenter(next, TRAINEE_SORT_PILL)
            game.wait(1.5)
            val dialogBitmap = game.imageUtils.getSourceBitmap()
            if (DialogUtils.getDialogOrNull(game.imageUtils, dialogBitmap)?.name != DialogDisplaySettings.name) throw CampaignBreakpointException("Display Settings did not open on Trainee Select.")
            val ok = anchorOf(ButtonOk, dialogBitmap)
            tapAnchored(game, ok, DISPLAY_SORT_TAB_DX, DISPLAY_SORT_TAB_DY)
            game.wait(0.5)
            tapAnchored(game, ok, DISPLAY_SORT_NAME_DX, DISPLAY_SORT_NAME_DY)
            game.wait(0.5)
            DialogDisplaySettings.ok(game.imageUtils, tries = 5)
            game.wait(1.5)
        }
        val isDescending = ocr(game.imageUtils.getSourceBitmap(), next, TRAINEE_ORDER_TOGGLE, "traineeOrder").contains("Desc", ignoreCase = true)
        if (isDescending != descending) {
            tapCenter(next, TRAINEE_ORDER_TOGGLE)
            game.wait(1.5)
        }
        return next
    }

    /**
     * Finds the trainee cards whose portrait is fully inside the readable part of the grid.
     *
     * @param list The trainee grid.
     * @returns Each card's "Potential Lvl" label center, in reading order.
     */
    private fun visibleTraineeCards(list: ScrollList): List<Point> {
        val portraitTop = list.listBoundingBox.y + game.imageUtils.relHeight(-TRAINEE_PORTRAIT_DY)
        return LabelCareerStartPotentialLvl.findAll(game.imageUtils, region = list.readRegion, sourceBitmap = game.imageUtils.getSourceBitmap())
            .filter { it.y.toInt() >= portraitTop }
            .sortedWith(compareBy({ it.y.toInt() / game.imageUtils.relHeight(40) }, { it.x }))
    }

    /**
     * Taps a trainee card and reads which outfit is now selected.
     *
     * @param label The card's "Potential Lvl" label center.
     * @returns The selected outfit, or null if it is not in the catalog.
     */
    private fun selectTraineeCard(label: Point): TraineeCard? {
        game.tap(label.x, label.y + game.imageUtils.relHeight(TRAINEE_PORTRAIT_DY))
        game.wait(0.8)
        return readSelectedOutfit()
    }

    /**
     * Taps the middle of a region measured at 1080x1920 from a template.
     *
     * @param anchor The template's center on screen.
     * @param offsets The region as dx, dy, width and height from the anchor.
     */
    private fun tapCenter(anchor: Point, offsets: IntArray) = tapAnchored(game, anchor, offsets[0] + offsets[2] / 2, offsets[1] + offsets[3] / 2)

    /**
     * Finds the template that a screen's positions are measured from.
     *
     * @param component The template.
     * @param bitmap The screenshot to search.
     * @returns The template's center.
     * @throws CampaignBreakpointException When the template is not on screen, since nothing on that screen can then be located.
     */
    private fun anchorOf(component: ComponentInterface, bitmap: Bitmap): Point =
        component.findImageWithBitmap(game.imageUtils, bitmap) ?: throw CampaignBreakpointException("Could not find the ${component.template.path} template to locate the rest of the screen from.")

    /**
     * Reads the "xN" count under each card type icon on Support Formation.
     *
     * @param bitmap The current screenshot.
     * @param borrow The Borrow button's center, which the counts are measured from.
     * @returns The count of each type the deck holds. Types with no count shown are left out.
     */
    private fun readDeckCounts(bitmap: Bitmap, borrow: Point): Map<String, Int> =
        DECK_TYPE_COLUMNS.mapNotNull { (type, dx) ->
            val text = ocr(bitmap, borrow, intArrayOf(dx - DECK_COUNT_W / 2, DECK_COUNT_DY, DECK_COUNT_W, DECK_COUNT_H), "deckCount$type")
            // Read the digit next to the "x" rather than every digit, in case the icon edge reads as a number.
            Regex("""[xX]\s*(\d)""").find(text)?.groupValues?.get(1)?.toInt()?.let { type to it }
        }.toMap()

    /** Taps the deck arrows until the "Deck N" title shows the chosen deck. */
    private fun selectDeck() {
        val target = settings.deckNumber
        if (target !in 1..10) return
        val header = anchorOf(LabelCareerStartSupportFormationHeader, game.imageUtils.getSourceBitmap())
        repeat(MAX_CAROUSEL_TAPS) {
            val current = Regex("""\d+""").find(ocr(game.imageUtils.getSourceBitmap(), header, DECK_TITLE, "deckTitle"))?.value?.toIntOrNull()
            if (current == target) {
                MessageLog.i(TAG, "[CAREER SETUP] Using Deck $target.")
                return
            }
            // The carousel wraps from Deck 10 back to Deck 1, so go the short way around.
            val forward = current == null || (target - current + 10) % 10 <= 5
            (if (forward) ButtonCareerStartDeckArrowRight else ButtonCareerStartDeckArrowLeft).click(game.imageUtils, tries = 3)
            game.wait(1.0)
        }
        throw CampaignBreakpointException("Could not switch to Deck $target on Support Formation.")
    }

    /**
     * OCRs a region measured at 1080x1920 from a template.
     *
     * @param bitmap The screenshot.
     * @param anchor The template's center on screen.
     * @param offsets The region as dx, dy, width and height from the anchor.
     * @param debugName Name for debug images.
     * @returns The text read.
     */
    private fun ocr(bitmap: Bitmap, anchor: Point, offsets: IntArray, debugName: String): String = ocrAnchored(game.imageUtils, bitmap, anchor, offsets, debugName)
}
