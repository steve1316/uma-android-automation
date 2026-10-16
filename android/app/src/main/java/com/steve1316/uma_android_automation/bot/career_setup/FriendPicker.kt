package com.steve1316.uma_android_automation.bot.career_setup

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonCareerStartFriendRefresh
import com.steve1316.uma_android_automation.components.IconCareerStartSupportSsr
import com.steve1316.uma_android_automation.components.IconCareerStartSupportTypes
import com.steve1316.uma_android_automation.components.LabelCareerStartDuplicateSupport
import com.steve1316.uma_android_automation.components.LabelCareerStartLastLogin
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.createDialogScrollList
import org.opencv.core.Point

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Row geometry relative to the center of a row's "Last Login" pill, measured at 1080x1920

/** Each field as dx, dy, width and height. */
private val TITLE_LINE = intArrayOf(-107, -110, 620, 40)
private val NAME_LINE = intArrayOf(-107, -67, 620, 42)
private val DUPLICATE_TAG = intArrayOf(-310, -225, 300, 55)
private val LEVEL_TEXT = intArrayOf(-215, -18, 85, 34)
private val SSR_BADGE = intArrayOf(-285, -182, 60, 45)
private val TYPE_ICON = intArrayOf(-185, -185, 50, 50)

/** Where to tap a row, on its title text. */
private const val TAP_DX = 150
private const val TAP_DY = -90

/** Rows lower than this are covered by the Filters / Rarity pill bar. Measured from the Refresh button. */
private const val READ_BOTTOM_DY = 73

/** Scroll pages allowed per pass, as a guard against a list that never reports its bottom. */
private const val MAX_PAGES = 30

/**
 * Picks the friend support card on the Borrow Card dialog.
 *
 * @property game Reference to the bot's [Game] instance.
 * @property settings The Career Setup settings.
 */
class FriendPicker(private val game: Game, private val settings: CareerSetupSettings) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]FriendPicker"
    }

    /** One row of the Borrow Card list, with the catalog card it was matched to. */
    private data class Row(
        val contentY: Int,
        val anchorX: Int,
        val card: SupportCard?,
        val title: String,
        val name: String,
        val type: String?,
        val isSsr: Boolean,
        val level: Int,
        val isDuplicate: Boolean,
    )

    /** The cards to look for, most wanted first: the card chosen in settings, else the scenario's favorites. */
    private val targets: List<String> =
        settings.friendCard.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: settings.friendFavorites[settings.scenario]?.favorites.orEmpty()

    /**
     * Borrows the most wanted target card, refreshing the list when none is there, then falls back to the best card of the type the deck lacks.
     *
     * @param deckCounts How many cards of each type the deck holds, read on Support Formation.
     * @returns True once a card was borrowed.
     */
    fun pick(deckCounts: Map<String, Int>): Boolean {
        val type = fallbackType(deckCounts)
        var rows: List<Row> = emptyList()
        var scroll: ScrollList? = null
        for (attempt in 0..(if (targets.isEmpty()) 0 else settings.friendMaxRefreshes)) {
            if (attempt > 0) {
                MessageLog.i(TAG, "[CAREER SETUP] No wanted friend card in the list. Refreshing ($attempt/${settings.friendMaxRefreshes}).")
                if (!ButtonCareerStartFriendRefresh.click(game.imageUtils, tries = 5)) break
                game.wait(2.0)
            }
            val list = trackedScroll() ?: return false
            scroll = list
            val startMs = System.currentTimeMillis()
            // The most wanted target ends the scan early. A lesser one is only taken once the whole list has been read.
            rows = scan(list, type) { !it.isDuplicate && it.card?.id == targets.firstOrNull() }
            MessageLog.i(TAG, "[CAREER SETUP] Read ${rows.size} friend cards in ${"%.1f".format((System.currentTimeMillis() - startMs) / 1000.0)}s.")
            val best = targets.firstNotNullOfOrNull { id -> rows.firstOrNull { !it.isDuplicate && it.card?.id == id } }
            // The list tracks where the card is, so it is scrolled to from wherever the scan stopped instead of from the top.
            if (best != null) return borrow(list, best)
        }

        MessageLog.i(TAG, "[CAREER SETUP] Deck holds $deckCounts. Looking for a ${type ?: "card of any type"}.")
        val fallback = rows.filter { !it.isDuplicate && (type == null || it.type == type) }.maxWithOrNull(compareBy({ it.isSsr }, { it.level }))
        if (fallback == null) {
            MessageLog.w(TAG, "[CAREER SETUP] No borrowable ${type ?: ""} support card in the list.")
            return false
        }
        MessageLog.i(TAG, "[CAREER SETUP] Falling back to ${fallback.title} ${fallback.name} (${fallback.type ?: "unknown type"}, Lvl ${fallback.level}).")
        return borrow(scroll ?: return false, fallback)
    }

    /**
     * Picks the card type for the fallback.
     *
     * @param deckCounts How many cards of each type the deck holds.
     * @returns The chosen card's type, else the scenario's wanted type the deck lacks most, or null to take any type.
     */
    private fun fallbackType(deckCounts: Map<String, Int>): String? {
        settings.supportCards[settings.friendCard]?.let { return it.type }
        val wanted = settings.friendFavorites[settings.scenario]?.types.orEmpty()
        return CareerCatalog.deckGapType(wanted, deckCounts)
    }

    /**
     * Reads the list from the top, stopping early at the first row [stopAt] accepts.
     *
     * @param scroll The Borrow Card list.
     * @param fallbackType The card type the fallback would take, or null for any type.
     * @param stopAt Ends the scan when it returns true for a row. That row is the last one returned.
     * @returns The rows read, in list order.
     */
    private fun scan(scroll: ScrollList, fallbackType: String?, stopAt: (Row) -> Boolean): List<Row> {
        scroll.scrollToTopTracked()
        val rows = mutableListOf<Row>()
        repeat(MAX_PAGES) {
            val bitmap = game.imageUtils.getSourceBitmap()
            for (anchor in anchors(bitmap, scroll)) {
                val contentY = scroll.contentY(anchor.y.toInt())
                if (rows.any { kotlin.math.abs(it.contentY - contentY) < game.imageUtils.relHeight(40) }) continue
                val row = readRow(bitmap, anchor, contentY, fallbackType)
                rows.add(row)
                if (stopAt(row)) return rows
            }
            if (!scroll.scrollDownTracked()) return rows
        }
        return rows
    }

    /**
     * Finds the "Last Login" pills of the rows fully inside the readable area.
     *
     * @param bitmap The screenshot.
     * @param scroll The Borrow Card list.
     * @returns The pill centers from top to bottom.
     */
    private fun anchors(bitmap: Bitmap, scroll: ScrollList): List<Point> {
        val top = scroll.readRegion[1] + game.imageUtils.relHeight(-DUPLICATE_TAG[1])
        val bottom = scroll.readBottom - game.imageUtils.relHeight(40)
        return LabelCareerStartLastLogin.findAll(game.imageUtils, region = scroll.readRegion, sourceBitmap = bitmap).filter { it.y.toInt() in top..bottom }.sortedBy { it.y }
    }

    /**
     * Reads one row's fields.
     *
     * @param bitmap The screenshot.
     * @param anchor The row's "Last Login" pill center.
     * @param contentY The row's content position.
     * @param fallbackType The card type the fallback would take, or null for any type.
     * @returns The row. Its level is 0 when it was not read.
     */
    private fun readRow(bitmap: Bitmap, anchor: Point, contentY: Int, fallbackType: String?): Row {
        // A card the deck already holds can never be borrowed, so it is not read any further.
        if (LabelCareerStartDuplicateSupport.check(game.imageUtils, region = field(anchor, DUPLICATE_TAG), sourceBitmap = bitmap)) {
            return Row(contentY, anchor.x.toInt(), null, "", "", null, isSsr = false, level = 0, isDuplicate = true)
        }
        val title = ocr(bitmap, anchor, TITLE_LINE, "friendTitle")
        val name = ocr(bitmap, anchor, NAME_LINE, "friendName")
        val card = CareerCatalog.match(title, name, settings.supportCards.values)
        val type = card?.type ?: IconCareerStartSupportTypes.entries.firstOrNull { (_, icon) -> icon.check(game.imageUtils, region = field(anchor, TYPE_ICON), sourceBitmap = bitmap) }?.key
        val isSsr = IconCareerStartSupportSsr.check(game.imageUtils, region = field(anchor, SSR_BADGE), sourceBitmap = bitmap)
        // The level only ranks fallback cards, so it is read only for a card of the fallback type.
        val level = if (fallbackType == null || type == fallbackType) NameMatching.parseLevel(ocr(bitmap, anchor, LEVEL_TEXT, "friendLevel")) ?: 0 else 0
        MessageLog.d(TAG, "[DEBUG] readRow:: $title $name card=${card?.id} type=$type ssr=$isSsr lvl=$level")
        return Row(contentY, anchor.x.toInt(), card, title, name, type, isSsr, level, isDuplicate = false)
    }

    /**
     * Taps a row to borrow it. The game asks for no confirmation.
     *
     * @param scroll The Borrow Card list.
     * @param row The row to borrow.
     * @returns True if the row was tapped.
     */
    private fun borrow(scroll: ScrollList, row: Row): Boolean {
        val screenY = scroll.bringIntoView(row.contentY, margin = game.imageUtils.relHeight(-DUPLICATE_TAG[1])) ?: return false
        MessageLog.i(TAG, "[CAREER SETUP] Borrowing ${row.title} ${row.name}.")
        tapAnchored(game, Point(row.anchorX.toDouble(), screenY.toDouble()), TAP_DX, TAP_DY)
        game.wait(1.5)
        return true
    }

    /**
     * OCRs one field of a row.
     *
     * @param bitmap The screenshot.
     * @param anchor The row's "Last Login" pill center.
     * @param offsets The field as dx, dy, width and height.
     * @param debugName Name for debug images.
     * @returns The text read.
     */
    private fun ocr(bitmap: Bitmap, anchor: Point, offsets: IntArray, debugName: String): String = ocrAnchored(game.imageUtils, bitmap, anchor, offsets, debugName)

    /**
     * Converts a field's offsets into a screen region.
     *
     * @param anchor The row's "Last Login" pill center.
     * @param offsets The field as dx, dy, width and height.
     * @returns The region as x, y, width and height.
     */
    private fun field(anchor: Point, offsets: IntArray): IntArray = anchoredRegion(game.imageUtils, anchor, offsets)

    /**
     * Finds the Borrow Card list and sets where its readable part ends.
     *
     * @returns The list, or null if it was not found.
     */
    private fun trackedScroll(): ScrollList? {
        val bitmap = game.imageUtils.getSourceBitmap()
        val list = createDialogScrollList(game, bitmap)
        val refresh = ButtonCareerStartFriendRefresh.findImageWithBitmap(game.imageUtils, bitmap)
        if (list == null || refresh == null) return null.also { MessageLog.w(TAG, "[CAREER SETUP] Borrow Card list not found.") }
        return list.also { it.readBottom = game.imageUtils.relY(refresh.y, READ_BOTTOM_DY + 40) }
    }
}
