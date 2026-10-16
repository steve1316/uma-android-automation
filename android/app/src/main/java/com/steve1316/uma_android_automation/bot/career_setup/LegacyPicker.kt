package com.steve1316.uma_android_automation.bot.career_setup

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.CampaignBreakpointException
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.IconCareerStartAffinityCircle
import com.steve1316.uma_android_automation.components.IconCareerStartAffinityDoubleCircle
import com.steve1316.uma_android_automation.components.IconCareerStartAffinityNone
import com.steve1316.uma_android_automation.components.IconCareerStartAffinityTriangle
import com.steve1316.uma_android_automation.components.LabelCareerStartLegacy1Tag
import com.steve1316.uma_android_automation.components.LabelCareerStartLegacyDuplicate
import com.steve1316.uma_android_automation.components.LabelCareerStartLegacyFreeBorrow
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.utils.ScrollList
import org.opencv.core.Point

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Picker geometry, measured at 1080x1920. The grid and tabs are offsets from the picker's Confirm button.

/** The picker's grid frame, including the Filters / Sparks pill bar pinned over its bottom. */
private val GRID = intArrayOf(-521, -580, 1052, 502)

/** The bottom of the part of the grid above the pill bar, where cards can be read. */
private const val READ_BOTTOM_DY = -156

/** Centers of the Veteran Umamusume and Guests tabs. */
private const val TAB_DY = -629
private const val VETERAN_TAB_DX = -253
private const val GUESTS_TAB_DX = 260

/** Offsets from a card's blue badge to the middle of its portrait. */
private const val PORTRAIT_DX = 95
private const val PORTRAIT_DY = -70

/** A card's "Duplicate" or "Legacy 1" tag starts 172px above its badge row. The window starts a little higher and covers the tag. */
private const val CARD_TOP_DY = -180
private const val TAG_REGION_H = 50

/** How many cards to tap for their affinity. */
private const val SHORTLIST_SIZE = 3

/** Scroll pages allowed per tab, as a guard against a list that never reports its bottom. */
private const val MAX_PAGES = 40

/**
 * Picks a legacy parent on the Legacy Select picker by scoring each card's sparks, then reading the affinity of the best few.
 *
 * @property game Reference to the bot's [Game] instance.
 * @property settings The Career Setup settings.
 * @property wishList The sparks to score parents against, most wanted first.
 */
class LegacyPicker(private val game: Game, private val settings: CareerSetupSettings, private val wishList: List<String>) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]LegacyPicker"
    }

    /** A card found during the scan and where to find it again. */
    private data class Seen(val isGuest: Boolean, val contentY: Int, val badgeX: Int, val sparks: List<Spark>)

    private val reader = SparkReader(game.imageUtils)

    /** The picker's Confirm button, which the grid and tabs are measured from. Found once the picker is open. */
    private val confirmAt: Point by lazy {
        ButtonConfirm.findImageWithBitmap(game.imageUtils, game.imageUtils.getSourceBitmap()) ?: throw CampaignBreakpointException("Could not find Confirm on the Legacy picker.")
    }

    /** The open tab, true for Guests, or null before the first tap. */
    private var guestTabOpen: Boolean? = null

    /** The open tab's grid, tracking how far it has scrolled. */
    private var scroll: ScrollList? = null

    /** The card tapped last, which the picker still has selected. */
    private var lastTapped: Seen? = null

    /** True once the Guests tab was scanned and had no free borrow, so the other slot can skip it. */
    var noFreeGuest: Boolean = false
        private set

    /**
     * Picks and confirms the best parent for the open picker. A free guest borrow is tried first, then the user's own veterans.
     *
     * @param skipGuests True to go straight to the veterans, when the other slot holds a guest or the Guests tab had no free borrow.
     * @returns True if a guest was chosen, false for a Veteran, or null if nothing could be picked.
     */
    fun pick(skipGuests: Boolean): Boolean? {
        if (!skipGuests) {
            val guests = scanTab(isGuest = true)
            choose(guests)?.let { return confirm(guests[it.index], it) }
            MessageLog.i(TAG, "[CAREER SETUP] No free guest legacy to borrow. Using your own veterans.")
            noFreeGuest = true
        }
        val veterans = scanTab(isGuest = false)
        val best = choose(veterans)
        if (best == null) {
            MessageLog.w(TAG, "[CAREER SETUP] No legacy cards could be read.")
            return null
        }
        return confirm(veterans[best.index], best)
    }

    /**
     * Shortlists the cards by spark score, taps each to read its affinity and cost, and picks the best one that can be used.
     *
     * @param seen The cards from one tab.
     * @returns The best usable card, or null if none can be used.
     */
    private fun choose(seen: List<Seen>): LegacyCandidate? {
        if (seen.isEmpty()) return null
        val candidates = seen.mapIndexed { i, card -> LegacyCandidate(i, card.isGuest, card.sparks) }
        val shortlist = SparkScoring.shortlist(candidates, wishList, SHORTLIST_SIZE)
        // The scan ends at the bottom of the tab, so the shortlist is tapped from the bottom up to walk the tab upward once.
        val inspected = shortlist.sortedByDescending { seen[it.index].contentY }.associate { it.index to inspect(seen[it.index]) }
        val checked =
            shortlist.map { candidate ->
                inspected[candidate.index]?.let { (affinity, free) -> candidate.copy(affinity = affinity, isFree = free) }
                    ?: candidate.copy(isFree = !candidate.isGuest)
            }
        return SparkScoring.pick(checked, wishList)
    }

    /**
     * Selects a card and confirms it, accepting the borrow prompt for a guest.
     *
     * @param card The card to select.
     * @param scored The card's scored candidate, for the log.
     * @returns True if the card is a guest, or null if it could not be confirmed.
     */
    private fun confirm(card: Seen, scored: LegacyCandidate): Boolean? {
        MessageLog.i(
            TAG,
            "[CAREER SETUP] Picking ${if (card.isGuest) "guest" else "veteran"} parent ${describe(card.sparks)} with affinity ${scored.affinity ?: "unknown"} " +
                "(score ${SparkScoring.totalScore(scored, wishList)}).",
        )
        // The last card inspected is still selected, so only a different card needs tapping again.
        if (card != lastTapped && select(card) == null) return null
        if (!ButtonConfirm.click(game.imageUtils, tries = 10)) return null
        game.wait(1.0)
        if (card.isGuest) {
            // "Borrow this Legacy?" asks once more for a guest.
            game.waitForAny(ButtonOk, timeoutSec = 5.0, bHandleDialogs = false)?.let { (_, point) -> game.tap(point.x, point.y) }
            game.wait(1.0)
        }
        return card.isGuest
    }

    /** Logs every readable card on the open picker with its spark score, for calibrating the reader on a device. */
    fun logVisibleCards() {
        val bitmap = game.imageUtils.getSourceBitmap()
        val cards = reader.readCards(bitmap, trackedScroll().readRegion)
        MessageLog.i(TAG, "[TEST] Read ${cards.size} cards.")
        for (card in cards) {
            val score = SparkScoring.sparkScore(card.sparks, wishList)
            MessageLog.i(TAG, "[TEST] Card at (${card.badgeX}, ${card.badgeY}): ${describe(card.sparks)} score=$score taken=${isTaken(bitmap, card)}")
        }
    }

    /**
     * Scans one tab from the top and reads every fully visible card.
     *
     * @param isGuest True for the Guests tab.
     * @returns The selectable cards with their content positions.
     */
    private fun scanTab(isGuest: Boolean): List<Seen> {
        // Each tab is scanned once, and opening it already starts from the top.
        val scroll = openTab(isGuest)
        val seen = mutableListOf<Seen>()
        repeat(MAX_PAGES) {
            val bitmap = game.imageUtils.getSourceBitmap()
            for (card in reader.readCards(bitmap, scroll.readRegion)) {
                // Skip cards whose top is cut off, since their tags cannot be checked. They are read again lower in the list.
                if (card.badgeY + game.imageUtils.relHeight(CARD_TOP_DY) < scroll.readRegion[1]) continue
                val contentY = scroll.contentY(card.badgeY)
                if (seen.any { it.badgeX == card.badgeX && kotlin.math.abs(it.contentY - contentY) < game.imageUtils.relHeight(40) }) continue
                if (isTaken(bitmap, card)) continue
                seen.add(Seen(isGuest, contentY, card.badgeX, card.sparks))
            }
            if (!scroll.scrollDownTracked()) return seen.also { MessageLog.i(TAG, "[CAREER SETUP] Read ${it.size} ${if (isGuest) "guest" else "veteran"} legacies.") }
        }
        return seen
    }

    /**
     * Taps a card and reads its affinity, and for a guest whether borrowing it is free.
     *
     * @param card The card to tap.
     * @returns The affinity and whether the card is free, or null if the card could not be brought into view.
     */
    private fun inspect(card: Seen): Pair<Affinity?, Boolean>? {
        val bitmap = select(card) ?: return null
        val affinity = readAffinity(bitmap)
        if (!card.isGuest) return affinity to true
        // OCR reads the "-" cost of a free borrow as "0" on some card art, so the "Free once per day!" banner decides instead. No banner counts as paid.
        val free = LabelCareerStartLegacyFreeBorrow.check(game.imageUtils, sourceBitmap = bitmap)
        MessageLog.i(TAG, "[CAREER SETUP] Guest ${describe(card.sparks)}: ${if (free) "free" else "paid"} borrow, affinity ${affinity ?: "unknown"}.")
        return affinity to free
    }

    /**
     * Scrolls a card into view and taps it to select it.
     *
     * @param card The card to select.
     * @returns The screenshot after the tap, or null if the card could not be brought into view.
     */
    private fun select(card: Seen): Bitmap? {
        val screenY = openTab(card.isGuest).bringIntoView(card.contentY, margin = game.imageUtils.relHeight(-CARD_TOP_DY))
        if (screenY == null) {
            MessageLog.w(TAG, "[CAREER SETUP] Could not scroll back to ${if (card.isGuest) "guest" else "veteran"} ${describe(card.sparks)}, so it is skipped.")
            return null
        }
        game.tap(card.badgeX + game.imageUtils.relWidth(PORTRAIT_DX).toDouble(), screenY + game.imageUtils.relHeight(PORTRAIT_DY).toDouble())
        game.wait(1.0)
        lastTapped = card
        return game.imageUtils.getSourceBitmap()
    }

    /**
     * Whether a card is already used, shown by a "Duplicate" or "Legacy 1" tag on its top edge.
     *
     * @param bitmap The screenshot.
     * @param card The card to check.
     * @returns True if the card cannot be chosen for this slot.
     */
    private fun isTaken(bitmap: Bitmap, card: SparkCard): Boolean {
        val region =
            intArrayOf(card.badgeX - game.imageUtils.relWidth(10), card.badgeY + game.imageUtils.relHeight(CARD_TOP_DY), game.imageUtils.relWidth(220), game.imageUtils.relHeight(TAG_REGION_H))
        return LabelCareerStartLegacyDuplicate.check(game.imageUtils, region = region, sourceBitmap = bitmap) ||
            LabelCareerStartLegacy1Tag.check(game.imageUtils, region = region, sourceBitmap = bitmap)
    }

    /**
     * Reads the affinity symbol at the top of the screen.
     *
     * @param bitmap The screenshot.
     * @returns The affinity, or null if no symbol matched.
     */
    private fun readAffinity(bitmap: Bitmap): Affinity? =
        listOf(
            IconCareerStartAffinityDoubleCircle to Affinity.DOUBLE_CIRCLE,
            IconCareerStartAffinityCircle to Affinity.CIRCLE,
            IconCareerStartAffinityTriangle to Affinity.TRIANGLE,
            IconCareerStartAffinityNone to Affinity.NONE,
        ).firstOrNull { (icon, _) -> icon.check(game.imageUtils, sourceBitmap = bitmap) }?.second

    /**
     * Opens the Veteran or Guests tab, starting again from the top of the grid when the tab changes.
     *
     * @param isGuest True for the Guests tab.
     * @returns The grid for that tab.
     */
    private fun openTab(isGuest: Boolean): ScrollList {
        scroll?.takeIf { guestTabOpen == isGuest }?.let { return it }
        tapAnchored(game, confirmAt, if (isGuest) GUESTS_TAB_DX else VETERAN_TAB_DX, TAB_DY)
        game.wait(1.0)
        guestTabOpen = isGuest
        return trackedScroll().also {
            it.scrollToTopTracked()
            scroll = it
        }
    }

    /**
     * Builds the scroll list for the picker grid.
     *
     * @returns The grid, read down to the pill bar.
     */
    private fun trackedScroll(): ScrollList {
        val imageUtils = game.imageUtils
        val (x, y, w, h) = anchoredRegion(imageUtils, confirmAt, GRID).toList()
        // The grid's scrollbar is painted over the cards, so dragging it scrolls the cards the wrong way. Swipe the cards instead.
        return ScrollList.fromBoundingBox(game, BoundingBox(x, y, w, h), bThumbIsHandle = false).also { it.readBottom = imageUtils.relY(confirmAt.y, READ_BOTTOM_DY) }
    }

    /**
     * Formats sparks for the log, such as "SPD3 MILE2 UNIQ1".
     *
     * @param sparks The sparks to describe.
     * @returns The short description.
     */
    private fun describe(sparks: List<Spark>): String = sparks.joinToString(" ") { "${it.label}${it.stars}" }
}
