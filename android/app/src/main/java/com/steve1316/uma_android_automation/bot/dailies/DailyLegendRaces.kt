package com.steve1316.uma_android_automation.bot.dailies

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonDailyLegendRacesCard
import com.steve1316.uma_android_automation.components.ButtonNext
import com.steve1316.uma_android_automation.components.ButtonRaceExclamation
import com.steve1316.uma_android_automation.components.ButtonRaceExclamationShiftedUp
import com.steve1316.uma_android_automation.components.ButtonViewResults
import com.steve1316.uma_android_automation.components.LabelDailyLegendLogos
import com.steve1316.uma_android_automation.components.LabelDailyLegendRacesHeader
import com.steve1316.uma_android_automation.components.LabelDailyPrograms
import com.steve1316.uma_android_automation.components.LabelStarUnlockText
import com.steve1316.uma_android_automation.utils.ScrollList
import org.opencv.core.Point

/** From a "to Star Unlock" strip: a spot on the card's art to tap. Measured on the 1080x1920 screenshots. */
private const val STRIP_TO_CARD_DY = -70

/** How far one scroll moves the Legend grid, about two rows of cards so each page overlaps the last. */
private const val LEGEND_GRID_SCROLL_TRAVEL = 500

/** Most pages of the Legend grid to look through before giving up. */
private const val MAX_LEGEND_GRID_PAGES = 10

/**
 * Spends the Daily Legend Race ticket on the chosen legend, or on the first legend that can still unlock a star.
 *
 * @property game The bot's [Game] instance.
 * @property flags Flags shared with the Daily Races part.
 * @property enabled Whether the user turned this part on.
 * @property preferred The legend chosen in settings.
 * @property useParfait Whether to use a Pleasing Parfait.
 */
class DailyLegendRaces(private val game: Game, private val flags: DailiesFlags, override val enabled: Boolean, private val preferred: String, private val useParfait: Boolean) :
    DailyPart {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]DailyLegendRaces"
    }

    override val name: String = "Legend Race"

    // No trip Home when Daily Races already showed there is nothing to do, or left the Daily Programs screen open.
    override fun canStartHere(): Boolean = flags.dailyProgramDone || flags.legendTickets == 0 || LabelDailyPrograms.check(game.imageUtils)

    override fun start(): PartOutcome {
        if (flags.legendTickets == 0) return PartOutcome.Skipped("no ticket")
        val tickets = (enterDailyProgram(game, flags) ?: return PartOutcome.Skipped("done for today")).legend
        MessageLog.i(TAG, "[DAILIES] Legend Race tickets: ${tickets ?: "unreadable"}.")
        if (tickets == 0) return PartOutcome.Skipped("no ticket")

        openLegendGrid()
        val (cardPoint, legend) = pickLegendCard() ?: return PartOutcome.Skipped("all legends maxed")
        game.tap(cardPoint.x, cardPoint.y)
        game.wait(2.0)

        tapTopRaceRow(game)
        if (!ButtonRaceExclamation.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Race Details Race! not found")
        game.wait(2.0)
        pickTopTrainee(game)
        runRace()
        tapThroughResults()
        return PartOutcome.Done(legend)
    }

    /**
     * Opens the Daily Legend Races grid from the Daily Programs screen.
     *
     * @throws DailyStepFailed When the card or the grid never appears.
     */
    private fun openLegendGrid() {
        if (!ButtonDailyLegendRacesCard.click(game.imageUtils, tries = 30)) throw DailyStepFailed("Daily Legend Races card not found")
        game.wait(2.0)
        game.waitForAny(LabelDailyLegendRacesHeader, timeoutSec = 15.0) ?: throw DailyStepFailed("Daily Legend Races screen did not appear")
        game.wait(1.0)
    }

    /**
     * Finds the card to race: the preferred legend if it still shows "to Star Unlock", else the first card that does.
     *
     * @return The point to tap and the legend's name (or "first unlockable legend"), or null when every legend is maxed.
     * @throws DailyStepFailed When the grid's scroll list is not on screen.
     */
    private fun pickLegendCard(): Pair<Point, String>? {
        val list = ScrollList.create(game) ?: throw DailyStepFailed("Legend grid not found")
        val box = list.listBoundingBox
        val region = intArrayOf(box.x, box.y, box.w, box.h)
        findPreferredLegend(list, region)?.let { return it to preferred }
        return findFirstUnlockableLegend(list, region)?.let { it to "first unlockable legend" }
    }

    /**
     * Looks for the preferred legend's logo page by page from the top of the grid.
     *
     * @param list The grid's scroll list.
     * @param region The grid's region, as x, y, width and height.
     * @return The logo's centre when that legend can still unlock a star, or null when it is maxed or not in the grid.
     */
    private fun findPreferredLegend(list: ScrollList, region: IntArray): Point? {
        val logo = LabelDailyLegendLogos[preferred]
        if (logo == null) {
            MessageLog.w(TAG, "[DAILIES] No logo template for $preferred. Looking for the first legend that can still unlock a star.")
            return null
        }
        val notFound = "[DAILIES] $preferred was not found in the grid. Looking for the first legend that can still unlock a star."
        list.ensureAtTop()
        repeat(MAX_LEGEND_GRID_PAGES) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            val logoPoint = logo.findImageWithBitmap(game.imageUtils, sourceBitmap, region = region)
            if (logoPoint != null) {
                val strips = LabelStarUnlockText.findAll(game.imageUtils, region = region, sourceBitmap = sourceBitmap)
                if (strips.any { stripBelongsToLogo(logoPoint.x.toInt() to logoPoint.y.toInt(), it.x.toInt() to it.y.toInt()) }) {
                    MessageLog.i(TAG, "[DAILIES] Racing $preferred.")
                    return logoPoint
                }
                MessageLog.i(TAG, "[DAILIES] $preferred is maxed. Looking for the first legend that can still unlock a star.")
                return null
            }
            if (list.isAtBottom(sourceBitmap)) {
                MessageLog.w(TAG, notFound)
                return null
            }
            scrollGrid(list)
        }
        MessageLog.w(TAG, notFound)
        return null
    }

    /**
     * Looks for the first card in reading order that still shows "to Star Unlock", page by page from the top of the grid.
     *
     * @param list The grid's scroll list.
     * @param region The grid's region, as x, y, width and height.
     * @return A spot on that card's art to tap, or null when every legend is maxed.
     */
    private fun findFirstUnlockableLegend(list: ScrollList, region: IntArray): Point? {
        list.ensureAtTop()
        repeat(MAX_LEGEND_GRID_PAGES) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            val strips = LabelStarUnlockText.findAll(game.imageUtils, region = region, sourceBitmap = sourceBitmap).filter { it.y + STRIP_TO_CARD_DY > region[1] }
            val first = firstInReadingOrder(strips.map { it.x.toInt() to it.y.toInt() })
            if (first != null) {
                MessageLog.i(TAG, "[DAILIES] Racing the first legend that can still unlock a star.")
                return Point(first.first.toDouble(), game.imageUtils.relY(first.second.toDouble(), STRIP_TO_CARD_DY).toDouble())
            }
            if (list.isAtBottom(sourceBitmap)) return null
            scrollGrid(list)
        }
        return null
    }

    /**
     * Scrolls the grid down about two rows and lets it settle.
     *
     * @param list The grid's scroll list.
     */
    private fun scrollGrid(list: ScrollList) {
        list.scrollContentDownBy(game.imageUtils.relHeight(LEGEND_GRID_SCROLL_TRAVEL))
        game.wait(1.0)
    }

    /**
     * Taps through the line-up, uses the Parfait when turned on, starts the race, and opens its results.
     *
     * @throws DailyStepFailed When a screen on the way never appears.
     */
    private fun runRace() {
        val lineUpNext = game.tapUntil(ButtonNext)?.second ?: throw DailyStepFailed("Line-up screen did not appear")
        game.tap(lineUpNext.x, lineUpNext.y)
        game.wait(1.0)
        val raceButton = game.waitForAny(ButtonRaceExclamationShiftedUp)?.second ?: throw DailyStepFailed("Items Selected did not appear")
        if (useParfait) useParfait(game)
        game.tap(raceButton.x, raceButton.y)
        game.wait(3.0)
        val viewResults = game.waitForAny(ButtonViewResults, timeoutSec = 30.0)?.second ?: throw DailyStepFailed("View Results did not appear")
        game.tap(viewResults.x, viewResults.y)
        game.wait(2.0)
    }

    /**
     * Taps through the results and rewards. Each ends on a Next button, so keep going until the legend grid or the Daily Program screen is back.
     *
     * @throws DailyStepFailed When the results never appear.
     */
    private fun tapThroughResults() {
        repeat(5) {
            val (found, point) = game.tapUntil(ButtonNext, LabelDailyPrograms, LabelDailyLegendRacesHeader) ?: throw DailyStepFailed("Legend Race results did not appear")
            if (found != ButtonNext) return
            game.tap(point.x, point.y)
            game.wait(1.0)
        }
    }
}
