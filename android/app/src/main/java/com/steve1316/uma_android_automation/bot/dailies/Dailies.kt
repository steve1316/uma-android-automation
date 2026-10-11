package com.steve1316.uma_android_automation.bot.dailies

import android.content.Context
import android.graphics.Bitmap
import com.steve1316.automation_library.utils.BotHold
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.RunReport
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Task
import com.steve1316.uma_android_automation.bot.TaskResult
import com.steve1316.uma_android_automation.bot.TaskResultCode
import com.steve1316.uma_android_automation.components.BaseComponentInterface
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonCancel
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonDailyProgram
import com.steve1316.uma_android_automation.components.ButtonHomeMissionsIcon
import com.steve1316.uma_android_automation.components.ButtonHomePresentsIcon
import com.steve1316.uma_android_automation.components.ButtonMenuBarHome
import com.steve1316.uma_android_automation.components.ButtonMenuBarHomeSelected
import com.steve1316.uma_android_automation.components.ButtonMenuBarRaceUnselected
import com.steve1316.uma_android_automation.components.ButtonNo
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.ButtonPleasingParfait
import com.steve1316.uma_android_automation.components.ButtonRaceExclamation
import com.steve1316.uma_android_automation.components.ButtonRadioRating
import com.steve1316.uma_android_automation.components.ButtonSkip
import com.steve1316.uma_android_automation.components.ButtonSortAsc
import com.steve1316.uma_android_automation.components.ButtonSortRating
import com.steve1316.uma_android_automation.components.ButtonSortToggle
import com.steve1316.uma_android_automation.components.ButtonTeamTrials
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogUtils
import com.steve1316.uma_android_automation.components.LabelDailyPrograms
import com.steve1316.uma_android_automation.components.LabelDoneForToday
import com.steve1316.uma_android_automation.components.LabelMainRewards
import com.steve1316.uma_android_automation.components.LabelRunnerSelection
import com.steve1316.uma_android_automation.components.LabelRunnerSpeedHeader
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Point

private val TAG: String = "[${MainActivity.loggerTag}]Dailies"

/** Dialogs a Dailies part opens and handles itself, so the generic handler must leave them alone. */
private val PART_OWNED_DIALOGS: List<String> =
    listOf("confirm_donations", "display_settings", "item_request", "items_selected", "multi_race", "presents", "race_details", "rewards_collected", "special_missions")

/** Dialogs that would spend RP, tickets or money. Dailies always declines them. */
private val SPENDING_DIALOGS: List<String> = listOf("confirm_restore_rp", "daily_sale", "purchase_daily_race_ticket")

/** Each Dailies debug test's setting key and the part it runs on its own. */
val DAILIES_DEBUG_TEST_KEYS: Map<String, String> =
    mapOf(
        "debugMode_startDailiesTeamTrialsTest" to "Team Trials",
        "debugMode_startDailiesDailyRacesTest" to "Daily Races",
        "debugMode_startDailiesLegendRaceTest" to "Legend Race",
        "debugMode_startDailiesClubTest" to "Club",
        "debugMode_startDailiesMissionsTest" to "Missions",
        "debugMode_startDailiesPresentsTest" to "Presents",
    )

/** Matches a "current/total" counter, allowing spaces around the slash and a letter O for zero. */
private val COUNTER_REGEX = Regex("""([0-9oO]+)\s*/\s*([0-9oO]+)""")

/** Where the "Done for today!" strip sits relative to the Race screen's Daily Program label. */
private val DONE_STRIP_DX = -60..60
private val DONE_STRIP_DY = -90..-20

// Offsets below are measured on the 1080x1920 screenshots, from the centre of the anchor template named in each comment.

/** From the "Daily Programs" label: the Daily Races ticket counter box (x, y, w, h). */
private val DAILY_RACES_COUNTER = intArrayOf(-199, 80, 120, 50)

/** From the "Daily Programs" label: the Daily Races "Resets in" / "Done for today!" strip. */
private val DAILY_RACES_STRIP = intArrayOf(-444, 245, 330, 50)

/** From the "Daily Programs" label: the Daily Legend Races ticket counter box. */
private val LEGEND_COUNTER = intArrayOf(251, 80, 120, 50)

/** From the "Daily Programs" label: the Daily Legend Races strip. */
private val LEGEND_STRIP = intArrayOf(6, 245, 330, 50)

/** From a row's "Main Rewards" label: the race banner to tap. */
private const val ROW_BANNER_DX = -222
private const val ROW_BANNER_DY = 10

/** From the Runner Selection Speed stat header: the first trainee's portrait just below it. */
private const val FIRST_TRAINEE_DX = -28
private const val FIRST_TRAINEE_DY = 270

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Part types and the runner

/** One chore in the Dailies run, such as Team Trials or Presents. */
interface DailyPart {
    /** Display name used in logs and the run-end summary. */
    val name: String

    /** Whether the user turned this part on in settings. */
    val enabled: Boolean

    /**
     * Runs the part from the Home screen. Must be safe to run again from the start after a pause.
     *
     * @return Done with a short detail, or Skipped with the reason there was nothing to do.
     * @throws DailyStepFailed When a required screen or button could not be reached.
     */
    fun start(): PartOutcome

    /**
     * Whether the part can start from the screen the previous part left, so the runner does not go Home first.
     *
     * @return True to skip the trip Home.
     */
    fun canStartHere(): Boolean = false
}

/** How a part ended when it did not fail. */
sealed interface PartOutcome {
    /**
     * The part did its work.
     *
     * @property detail Short note for the summary, such as "4 races".
     */
    data class Done(val detail: String) : PartOutcome

    /**
     * The part had nothing to do.
     *
     * @property reason Why, such as "no RP".
     */
    data class Skipped(val reason: String) : PartOutcome
}

/**
 * Thrown by a part when it cannot continue, such as a button that never appeared. The runner records the part as failed and moves on.
 *
 * @param message What went wrong, shown in the log and the summary.
 */
class DailyStepFailed(message: String) : Exception(message)

/** How a part ended, as recorded by the runner. */
enum class PartStatus {
    DONE,
    SKIPPED,
    FAILED,
}

/**
 * The recorded result of one part.
 *
 * @property name The part's name.
 * @property status How it ended.
 * @property detail The done detail, the skip reason, or the failure message.
 */
data class PartResult(val name: String, val status: PartStatus, val detail: String)

/** Shared flags the Dailies dialog handler raises while a part runs. */
class DailiesFlags {
    /** Set when a Restore RP dialog was cancelled, meaning RP ran out. */
    @Volatile var rpExhausted: Boolean = false

    /** Set when a Purchase Daily Race Ticket dialog was cancelled, meaning no tickets were left. */
    @Volatile var ticketsExhausted: Boolean = false

    /** Set when the Race screen's Daily Program card says "Done for today!", so both Daily Program parts skip without going in. */
    @Volatile var dailyProgramDone: Boolean = false

    /** Legend Race tickets read on the Daily Programs screen by whichever part went in first, or null when not read yet. */
    @Volatile var legendTickets: Int? = null
}

/**
 * Tickets left on the Daily Programs screen.
 *
 * @property dailyRaces Daily Race tickets, or null when unreadable.
 * @property legend Daily Legend Race tickets, or null when unreadable.
 */
data class ProgramTickets(val dailyRaces: Int?, val legend: Int?)

/**
 * Runs the Dailies parts in order, one per `step()`, and records how each one ended.
 *
 * A part that fails is recorded and the next step moves on. An exception that `shouldRethrow` claims, such as a stop or a pause abort,
 * propagates without recording anything, so the same part runs again from the start on the next step.
 *
 * @property parts The parts in run order.
 * @property beforePart Runs before each enabled part that cannot start where the last one left off, normally going to the Home screen.
 * @property shouldRethrow Whether an exception belongs to the task loop (stop or pause) rather than to the part.
 * @property onFailure Called with the part and its exception when a part fails.
 */
class DailiesRunner(
    private val parts: List<DailyPart>,
    private val beforePart: () -> Unit,
    private val shouldRethrow: (Exception) -> Boolean,
    private val onFailure: (DailyPart, Exception) -> Unit = { _, _ -> },
) {
    /** Index of the next part to run. */
    private var index = 0

    /** Results recorded so far, in run order. */
    private val recorded = mutableListOf<PartResult>()

    /** A read-only copy of the results recorded so far. */
    val results: List<PartResult>
        get() = recorded.toList()

    /** Whether every part has a result. */
    val isFinished: Boolean
        get() = index >= parts.size

    /** Number of parts that failed. */
    val failedCount: Int
        get() = recorded.count { it.status == PartStatus.FAILED }

    /** The part the next `step()` runs, or null when finished. */
    val nextPart: DailyPart?
        get() = parts.getOrNull(index)

    /**
     * Runs the next part and records its result, unless the part was aborted by a stop or pause.
     *
     * @return The recorded result.
     */
    fun step(): PartResult {
        val part = parts[index]
        if (!part.enabled) return record(part, PartStatus.SKIPPED, "off in settings")

        return try {
            if (!part.canStartHere()) beforePart()
            when (val outcome = part.start()) {
                is PartOutcome.Done -> record(part, PartStatus.DONE, outcome.detail)
                is PartOutcome.Skipped -> record(part, PartStatus.SKIPPED, outcome.reason)
            }
        } catch (e: Exception) {
            if (shouldRethrow(e)) throw e
            onFailure(part, e)
            record(part, PartStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Counts the results by status.
     *
     * @return A line such as "5 done, 1 skipped, 0 failed".
     */
    fun summaryLine(): String {
        val done = recorded.count { it.status == PartStatus.DONE }
        val skipped = recorded.count { it.status == PartStatus.SKIPPED }
        return "$done done, $skipped skipped, $failedCount failed"
    }

    /**
     * Records a part's result and moves to the next part.
     *
     * @param part The part.
     * @param status How it ended.
     * @param detail The detail, reason, or failure message.
     * @return The recorded result.
     */
    private fun record(part: DailyPart, status: PartStatus, detail: String): PartResult {
        val result = PartResult(part.name, status, detail)
        recorded.add(result)
        index++
        return result
    }
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Screen-reading helpers

/**
 * Converts an OCR'd digit run to an int, reading the letter O as zero.
 *
 * @param s The digit run.
 * @return The number, or null when it is not numeric.
 */
private fun digits(s: String): Int? = s.replace('o', '0').replace('O', '0').toIntOrNull()

/**
 * Reads the first "current/total" counter in the text, such as a "6/6" ticket count or the "5/5" RP count in Items Selected.
 *
 * @param text The OCR text of the counter area.
 * @return The current and total values, 0 to 0 for "Done for today", or null when unreadable.
 */
fun parseCounter(text: String): Pair<Int, Int>? {
    if (text.contains("done for today", ignoreCase = true)) return 0 to 0
    val match = COUNTER_REGEX.find(text) ?: return null
    val remaining = digits(match.groupValues[1]) ?: return null
    val total = digits(match.groupValues[2]) ?: return null
    return remaining to total
}

/**
 * Whether a "to Star Unlock" strip belongs to the legend logo above it, meaning that legend is not maxed yet.
 *
 * @param logo Centre of the legend's name logo.
 * @param strip Centre of the "to Star Unlock" text.
 * @return True when the strip sits in the same card, about 104 px right of and 75 px below the logo centre.
 */
fun stripBelongsToLogo(logo: Pair<Int, Int>, strip: Pair<Int, Int>): Boolean = isOffsetWithin(logo, strip, 70..140, 50..100)

/**
 * Whether a "Done for today!" strip belongs to the Race screen's Daily Program card.
 *
 * @param card Centre of the Daily Program label.
 * @param strip Centre of the strip.
 * @return True when the strip sits just above the label on the same card.
 */
fun doneStripOnDailyProgram(card: Pair<Int, Int>, strip: Pair<Int, Int>): Boolean = isOffsetWithin(card, strip, DONE_STRIP_DX, DONE_STRIP_DY)

/**
 * Whether a point sits within an offset window of an anchor.
 *
 * @param anchor The anchor point.
 * @param point The point to test.
 * @param dx Allowed horizontal offsets from the anchor.
 * @param dy Allowed vertical offsets from the anchor.
 * @return True when both offsets are in range.
 */
private fun isOffsetWithin(anchor: Pair<Int, Int>, point: Pair<Int, Int>, dx: IntRange, dy: IntRange): Boolean =
    point.first - anchor.first in dx && point.second - anchor.second in dy

/**
 * Picks the first point in reading order: top row first, then left to right. Points within `rowTolerance` px of y share a row.
 *
 * @param points The points to order.
 * @param rowTolerance How far apart two y values can be and still count as one row.
 * @return The first point, or null when there are none.
 */
fun firstInReadingOrder(points: List<Pair<Int, Int>>, rowTolerance: Int = 30): Pair<Int, Int>? {
    val top = points.minOfOrNull { it.second } ?: return null
    return points.filter { it.second - top <= rowTolerance }.minByOrNull { it.first }
}

/**
 * Reduces an item name to lower-case letters and digits so OCR that drops punctuation still compares equal.
 *
 * @param name The item name.
 * @return The normalized name.
 */
fun normalizeItemName(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]"), "")

/**
 * Whether an OCR'd item row title is the target item. Up to two dropped leading characters still match, since ML Kit can lose the first letter
 * at a crop edge. No two item names differ only by their first two characters, so this never matches the wrong item.
 *
 * @param ocr The OCR text of the row title.
 * @param target The item name chosen in settings.
 * @return True when the OCR text is the target name, or the target name missing at most its first two characters.
 */
fun matchItemName(ocr: String, target: String): Boolean {
    val read = normalizeItemName(ocr)
    val wanted = normalizeItemName(target)
    return read.isNotEmpty() && wanted.endsWith(read) && wanted.length - read.length <= 2
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Race steps shared by Team Trials, Daily Races and the Legend Race

/**
 * Opens the Race tab from the nav bar unless it is already showing.
 *
 * @param game The bot's [Game] instance.
 * @throws DailyStepFailed When the Race screen never appears.
 */
internal fun openRaceTab(game: Game) {
    val sourceBitmap = game.imageUtils.getSourceBitmap()
    if (ButtonTeamTrials.check(game.imageUtils, sourceBitmap = sourceBitmap) || ButtonDailyProgram.check(game.imageUtils, sourceBitmap = sourceBitmap)) return
    if (!ButtonMenuBarRaceUnselected.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Race tab not found")
    game.wait(2.0)
    game.waitForAny(ButtonTeamTrials, ButtonDailyProgram, timeoutSec = 15.0) ?: throw DailyStepFailed("Race screen did not appear")
}

/**
 * Opens the Race tab and checks whether its Daily Program card says "Done for today!", meaning every Daily Program ticket is spent.
 *
 * @param game The bot's [Game] instance.
 * @return True when the card says it is done for today.
 */
private fun dailyProgramDoneForToday(game: Game): Boolean {
    openRaceTab(game)
    val card = game.waitForAny(ButtonDailyProgram)?.second ?: throw DailyStepFailed("Daily Program card not found")
    val cardPoint = card.x.toInt() to card.y.toInt()
    val done = LabelDoneForToday.findAll(game.imageUtils).any { doneStripOnDailyProgram(cardPoint, it.x.toInt() to it.y.toInt()) }
    if (done) MessageLog.i(TAG, "[DAILIES] The Daily Program card says Done for today. Skipping Daily Races and the Legend Race.")
    return done
}

/**
 * Reads the tickets left for one Daily Program card: the counter first, then the "Done for today!" strip when the counter is unreadable.
 *
 * @param game The bot's [Game] instance.
 * @param sourceBitmap The Daily Programs screenshot.
 * @param anchor Centre of the "Daily Programs" label.
 * @param counter Counter box offsets.
 * @param strip Status strip offsets.
 * @param name Debug name for the OCR crops.
 * @return Tickets left, or null when unreadable.
 */
private fun readTickets(game: Game, sourceBitmap: Bitmap, anchor: Point, counter: IntArray, strip: IntArray, name: String): Int? {
    return listOf(counter to "${name}_counter", strip to "${name}_strip").firstNotNullOfOrNull { (box, debugName) ->
        val text =
            game.imageUtils.performOCRFromReference(
                anchor,
                box[0],
                box[1],
                game.imageUtils.relWidth(box[2]),
                game.imageUtils.relHeight(box[3]),
                useThreshold = false,
                debugName = debugName,
                bitmap = sourceBitmap,
            )
        parseCounter(text)?.first
    }
}

/**
 * Opens the Daily Programs screen, or stays when it is already open, and reads both ticket counters. Skips the trip when the Race screen's Daily
 * Program card already says "Done for today!". The Legend Race tickets are kept in `flags` so the Legend Race part can skip without going in.
 *
 * @param game The bot's [Game] instance.
 * @param flags Where the Done for today result and the Legend Race tickets are kept.
 * @return The tickets left, or null when every Daily Program ticket is spent.
 * @throws DailyStepFailed When the Daily Programs screen never appears.
 */
internal fun enterDailyProgram(game: Game, flags: DailiesFlags): ProgramTickets? {
    if (flags.dailyProgramDone) return null
    if (!LabelDailyPrograms.check(game.imageUtils)) {
        if (dailyProgramDoneForToday(game)) {
            flags.dailyProgramDone = true
            return null
        }
        if (!ButtonDailyProgram.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Daily Program button not found")
        game.wait(1.0)
        game.waitForAny(LabelDailyPrograms, timeoutSec = 15.0) ?: throw DailyStepFailed("Daily Program screen did not appear")
    }

    game.wait(1.0)
    val sourceBitmap = game.imageUtils.getSourceBitmap()
    val anchor = LabelDailyPrograms.findImageWithBitmap(game.imageUtils, sourceBitmap) ?: throw DailyStepFailed("Daily Program screen did not appear")
    val tickets =
        ProgramTickets(
            readTickets(game, sourceBitmap, anchor, DAILY_RACES_COUNTER, DAILY_RACES_STRIP, "daily_races"),
            readTickets(game, sourceBitmap, anchor, LEGEND_COUNTER, LEGEND_STRIP, "legend"),
        )
    flags.legendTickets = tickets.legend
    return tickets
}

/**
 * Taps the top (hardest) row of a race list, found by its "Main Rewards" label, and waits for Race Details.
 *
 * @param game The bot's [Game] instance.
 * @throws DailyStepFailed When the race list or Race Details never appears.
 */
internal fun tapTopRaceRow(game: Game) {
    game.waitForAny(LabelMainRewards, timeoutSec = 15.0) ?: throw DailyStepFailed("Race list did not appear")
    val top = LabelMainRewards.findAll(game.imageUtils).minByOrNull { it.y } ?: throw DailyStepFailed("Race list rows not found")
    game.tap(game.imageUtils.relX(top.x, ROW_BANNER_DX).toDouble(), game.imageUtils.relY(top.y, ROW_BANNER_DY).toDouble())
    game.wait(1.0)
    game.waitForAny(ButtonRaceExclamation) ?: throw DailyStepFailed("Race Details did not appear")
}

/**
 * Taps the race skip button once it shows, then taps through the results until one of the end buttons appears.
 *
 * @param game The bot's [Game] instance.
 * @param ends The buttons that mark the end of the results.
 * @throws DailyStepFailed When the skip button or the end of the results never appears.
 */
internal fun skipRace(game: Game, vararg ends: BaseComponentInterface) {
    val skip = game.waitForAny(ButtonSkip, timeoutSec = 45.0)?.second ?: throw DailyStepFailed("Race skip button did not appear")
    game.tap(skip.x, skip.y)
    game.wait(1.0)
    game.waitForLoading()
    game.tapUntil(*ends) ?: throw DailyStepFailed("End of race results did not appear")
}

/**
 * On Runner Selection, sorts by Rating, highest first, picks the first trainee, and confirms.
 *
 * @param game The bot's [Game] instance.
 * @param next What shows the screen after Confirm, or nothing to not wait for anything.
 * @throws DailyStepFailed When Runner Selection or its controls never appear.
 */
internal fun pickTopTrainee(game: Game, vararg next: BaseComponentInterface) {
    game.waitForAny(LabelRunnerSelection, timeoutSec = 20.0) ?: throw DailyStepFailed("Runner Selection did not appear")
    game.wait(0.5)
    val sourceBitmap = game.imageUtils.getSourceBitmap()
    val speedHeader = LabelRunnerSpeedHeader.findImageWithBitmap(game.imageUtils, sourceBitmap) ?: throw DailyStepFailed("Speed header not found on Runner Selection")
    if (!ButtonSortRating.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
        MessageLog.i(TAG, "[DAILIES] Sorting the trainees by Rating.")
        if (!ButtonSortToggle.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Sort pill not found on Runner Selection")
        game.wait(1.0)
        if (!ButtonRadioRating.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Rating option not found in Display Settings")
        if (!ButtonOk.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Display Settings OK not found")
        game.wait(1.0)
    }

    // If the order pill shows Asc, tap it once to flip to Desc.
    if (ButtonSortAsc.click(game.imageUtils, tries = 1)) MessageLog.i(TAG, "[DAILIES] Switching the trainee order to highest first.")
    game.tap(game.imageUtils.relX(speedHeader.x, FIRST_TRAINEE_DX).toDouble(), game.imageUtils.relY(speedHeader.y, FIRST_TRAINEE_DY).toDouble())
    game.wait(0.5)
    if (!ButtonConfirm.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Runner Selection Confirm not found")
    game.wait(1.0)
    if (next.isNotEmpty()) game.waitForAny(*next, timeoutSec = 15.0) ?: throw DailyStepFailed("Screen after Runner Selection did not appear")
}

/**
 * In Items Selected, taps the Pleasing Parfait so the race uses it. Logs and carries on without it when none is left.
 *
 * @param game The bot's [Game] instance.
 */
internal fun useParfait(game: Game) {
    if (!ButtonPleasingParfait.click(game.imageUtils)) {
        MessageLog.w(TAG, "[DAILIES] No Pleasing Parfait in Items Selected. Racing without it.")
        return
    }
    MessageLog.i(TAG, "[DAILIES] Using a Pleasing Parfait.")
}

/**
 * The Dailies run: Team Trials, Daily Races, the Legend Race, the Club item request, Missions and Presents, in that order, then stop.
 *
 * Each `process()` call runs one part from the Home screen. A failing part is logged and skipped. A pause re-runs the current part.
 *
 * @param game The bot's [Game] instance.
 */
class Dailies(game: Game) : Task(game) {
    /** Whether to spend RP on Team Trials. */
    private val enableTeamTrials: Boolean = SettingsHelper.getBooleanSetting("dailies", "enableTeamTrials", true)

    /** Whether to use a Pleasing Parfait before each Team Trials race. */
    private val teamTrialsUseParfait: Boolean = SettingsHelper.getBooleanSetting("dailies", "teamTrialsUseParfait", false)

    /** Whether to spend the Daily Race tickets. */
    private val enableDailyRaces: Boolean = SettingsHelper.getBooleanSetting("dailies", "enableDailyRaces", true)

    /** "Moonlight Sho" or "Jupiter Cup". */
    private val dailyRaceType: String = SettingsHelper.getStringSetting("dailies", "dailyRaceType").ifEmpty { "Jupiter Cup" }

    /** Whether to spend the Daily Legend Race ticket. */
    private val enableLegendRace: Boolean = SettingsHelper.getBooleanSetting("dailies", "enableLegendRace", true)

    /** The legend to race when it can still unlock a star. */
    private val legendRaceCharacter: String = SettingsHelper.getStringSetting("dailies", "legendRaceCharacter").ifEmpty { "El Condor Pasa" }

    /** Whether to use a Pleasing Parfait before the Legend Race. */
    private val legendRaceUseParfait: Boolean = SettingsHelper.getBooleanSetting("dailies", "legendRaceUseParfait", false)

    /** Whether to collect and issue the Club item request. */
    private val clubRequestEnabled: Boolean = SettingsHelper.getBooleanSetting("dailies", "clubRequestEnabled", true)

    /** The shoes or Winner's Sash to request. */
    private val clubRequestItem: String = SettingsHelper.getStringSetting("dailies", "clubRequestItem").ifEmpty { "Dirt Racing Shoes" }

    /** Whether to Donate to All in the Club. */
    private val clubDonateEnabled: Boolean = SettingsHelper.getBooleanSetting("dailies", "clubDonateEnabled", true)

    /** Whether to collect mission rewards. */
    private val enableMissions: Boolean = SettingsHelper.getBooleanSetting("dailies", "enableMissions", true)

    /** Whether to collect the Presents box. */
    private val enablePresents: Boolean = SettingsHelper.getBooleanSetting("dailies", "enablePresents", true)

    /** Flags the dialog handler raises for the race parts. */
    private val flags = DailiesFlags()

    /** The parts in run order. */
    private val parts: List<DailyPart> =
        listOf(
            TeamTrials(game, flags, enableTeamTrials, teamTrialsUseParfait),
            DailyRaces(game, flags, enableDailyRaces, dailyRaceType),
            DailyLegendRaces(game, flags, enableLegendRace, legendRaceCharacter, legendRaceUseParfait),
            Club(game, clubRequestEnabled, clubRequestItem, clubDonateEnabled),
        )

    /** Runs the parts and records their results. */
    private val runner =
        DailiesRunner(
            parts,
            beforePart = ::goHome,
            shouldRethrow = { it is InterruptedException || BotHold.isAbortRaised || !BotService.isRunning },
            onFailure = { part, _ -> game.imageUtils.saveDebugScreenshot(game.imageUtils.getSourceBitmap(), "dailies_${part.name.lowercase().replace(' ', '_')}_failed") },
        )

    override fun process(): TaskResult? {
        val part = runner.nextPart
        if (part != null) {
            if (part.enabled) MessageLog.i(TAG, "[DAILIES] Starting ${part.name}.")
            val result = runner.step()
            val line = "[DAILIES] ${result.name} ${result.status.name.lowercase()}: ${result.detail}"
            if (result.status == PartStatus.FAILED) MessageLog.e(TAG, line) else MessageLog.i(TAG, line)
        }
        if (!runner.isFinished) return null

        MessageLog.i(TAG, "[DAILIES] Summary: ${runner.summaryLine()}")
        goHomeQuietly()
        return TaskResult.Success(TaskResultCode.TASK_RESULT_COMPLETE, if (runner.failedCount > 0) "Dailies finished with errors" else "Dailies complete")
    }

    override fun onRunStart(startTimeMs: Long, context: Context) {
        RunReport.setSummaryProvider(::buildSummary)
    }

    override fun handleDialogs(dialog: DialogInterface?, args: Map<String, Any>): DialogHandlerResult {
        @Suppress("UNCHECKED_CAST")
        val callerDefers = args["dialogNamesToDefer"] as? List<String> ?: listOf()
        val result = super.handleDialogs(dialog, args + mapOf("dialogNamesToDefer" to callerDefers + PART_OWNED_DIALOGS + SPENDING_DIALOGS))
        if (result !is DialogHandlerResult.Deferred) return result

        val deferred = result.dialog
        when (deferred.name) {
            "daily_sale" -> {
                MessageLog.i(TAG, "[DAILIES] Declining the Daily Sale.")
                ButtonCancel.click(game.imageUtils)
            }

            "confirm_restore_rp" -> {
                MessageLog.i(TAG, "[DAILIES] Out of RP. Not restoring it.")
                flags.rpExhausted = true
                ButtonNo.click(game.imageUtils)
            }

            "purchase_daily_race_ticket" -> {
                MessageLog.i(TAG, "[DAILIES] Out of Daily Race tickets. Not buying more.")
                flags.ticketsExhausted = true
                ButtonCancel.click(game.imageUtils)
            }

            else -> return result
        }
        game.wait(0.5)
        return DialogHandlerResult.Handled(deferred)
    }

    override fun startTests(): Boolean {
        var bDidAnyTestsRun = false
        for ((setting, partName) in DAILIES_DEBUG_TEST_KEYS) {
            if (!SettingsHelper.getBooleanSetting("debug", setting, false)) continue
            val part = parts.first { it.name == partName }
            MessageLog.i(TAG, "\n[TEST] Running the Dailies $partName part on its own.")
            try {
                goHome()
                MessageLog.i(TAG, "[TEST] $partName ended: ${part.start()}")
            } catch (e: DailyStepFailed) {
                MessageLog.e(TAG, "[TEST] $partName failed: ${e.message}")
            }
            bDidAnyTestsRun = true
        }
        return bDidAnyTestsRun
    }

    /**
     * Builds the run-end summary for the report.
     *
     * @return The summary JSON with each part's status and detail.
     */
    private fun buildSummary(): JSONObject {
        val list = JSONArray()
        runner.results.forEach { list.put(JSONObject().put("name", it.name).put("status", it.status.name.lowercase()).put("detail", it.detail)) }
        return JSONObject().put("kind", "dailies").put("parts", list)
    }

    /**
     * Gets to the Home screen from wherever the game is, closing dialogs and backing out of screens on the way.
     *
     * @throws DailyStepFailed When Home could not be reached.
     */
    private fun goHome() {
        repeat(6) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            val homeIcons = ButtonHomeMissionsIcon.check(game.imageUtils, sourceBitmap = sourceBitmap) || ButtonHomePresentsIcon.check(game.imageUtils, sourceBitmap = sourceBitmap)
            if (homeIcons && ButtonMenuBarHomeSelected.check(game.imageUtils, sourceBitmap = sourceBitmap)) return
            // A dialog left open by a failed or paused part blocks the nav bar, so close it first.
            val dialog =
                try {
                    DialogUtils.getDialog(game.imageUtils, sourceBitmap)
                } catch (e: IllegalStateException) {
                    null
                }
            if (dialog != null) {
                MessageLog.i(TAG, "[DAILIES] Closing the \"${dialog.name}\" dialog on the way Home.")
                if (!dialog.close(game.imageUtils)) ButtonCancel.click(game.imageUtils)
            } else if (!ButtonMenuBarHome.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                ButtonBack.click(game.imageUtils, sourceBitmap = sourceBitmap) ||
                    ButtonClose.click(game.imageUtils, sourceBitmap = sourceBitmap) ||
                    ButtonCancel.click(game.imageUtils, sourceBitmap = sourceBitmap)
            }
            game.wait(1.0)
            game.waitForLoading()
        }
        game.waitForAny(ButtonHomeMissionsIcon, ButtonHomePresentsIcon) ?: throw DailyStepFailed("Home screen did not appear")
    }

    /** Goes Home at the end of the run, logging instead of failing when Home cannot be reached. */
    private fun goHomeQuietly() {
        try {
            goHome()
        } catch (e: DailyStepFailed) {
            MessageLog.w(TAG, "[DAILIES] Could not return Home at the end: ${e.message}")
        }
    }
}
