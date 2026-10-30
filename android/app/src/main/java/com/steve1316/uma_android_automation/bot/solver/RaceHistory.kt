package com.steve1316.uma_android_automation.bot.solver

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonBurger
import com.steve1316.uma_android_automation.components.ButtonCareer
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.IconRaceHistory1st
import com.steve1316.uma_android_automation.components.LabelStrategy
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import com.steve1316.uma_android_automation.utils.createDialogScrollList
import org.opencv.core.Point

/**
 * Reads the in-game Career -> Race History dialog by OCR. Used at bot startup to seed
 * [SmartRaceSolverIntegration] with the trainee's actual past race wins.
 */
object RaceHistory {
    private val TAG: String = "[${MainActivity.loggerTag}]RaceHistory"

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Crop Offsets

    // Crop offsets relative to the LabelStrategy anchor's screen-absolute centre.
    private const val FORMATTED_NAME_OFFSET_X = -55
    private const val FORMATTED_NAME_OFFSET_Y = -70
    private const val FORMATTED_NAME_W = 565
    private const val FORMATTED_NAME_H = 45

    private const val DATE_OFFSET_X = 390
    private const val DATE_OFFSET_Y = -20
    private const val DATE_W = 335
    private const val DATE_H = 45

    private const val FIRST_PLACE_OFFSET_X = 720
    private const val FIRST_PLACE_OFFSET_Y = -105
    private const val FIRST_PLACE_W = 160
    private const val FIRST_PLACE_H = 120

    // Running-style chip sits just right of the LabelStrategy anchor on the same row. Offset derived from crop (190, 960) minus anchor centre (130, 980).
    private const val STRATEGY_OFFSET_X = 60
    private const val STRATEGY_OFFSET_Y = -20
    private const val STRATEGY_W = 130
    private const val STRATEGY_H = 40

    /**
     * One race entry parsed from the Career -> Race History dialog. The race name itself
     * is intentionally not captured - OCR on the row's primary label was unreliable, and
     * the tuple of formatted track string + in-game date is sufficient to look the race up in races.json.
     *
     * @property nameFormatted Formatted track string (e.g. "Tokyo Turf 1600m (Mile) Left").
     * @property dateString In-game date as shown on screen (e.g. "Junior Year Early Nov").
     * @property won True if the IconRaceHistory1st laurel was found in the placement region.
     * @property strategy Raw OCR of the running-style chip (e.g. "Front Runner"). Empty when OCR returned nothing.
     */
    data class RaceHistoryEntry(val nameFormatted: String, val dateString: String, val won: Boolean, val strategy: String)

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /**
     * Open Career -> Race History from the Main Screen, OCR every entry, then close back
     * to the Main Screen. ScrollList's standard scrollbar-based termination ends the scan
     * at the bottom of the list. The bottom Junior Make Debut row will appear in the
     * results but its formatted-name lookup against races.json will fail and the caller
     * silently drops it. The Remote Log Viewer surfaces a separate synthetic Make Debut
     * entry built directly inside SmartRaceSolverIntegration.buildCalendarSnapshotJson;
     * that entry is display-only and is never recorded into raceHistory.
     *
     * @param game Active Game instance for tap/screenshot/OCR access.
     * @return Newest-first scraped entries, or null if any navigation/OCR step failed.
     */
    fun scrape(game: Game): List<RaceHistoryEntry>? {
        MessageLog.i(TAG, "[INFO] Opening Career → Race History to read past results...")

        if (!ButtonBurger.click(game.imageUtils)) {
            MessageLog.w(TAG, "[WARN] scrape:: Failed to find/click hamburger menu button.")
            return null
        }
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

        if (!ButtonCareer.click(game.imageUtils)) {
            MessageLog.w(TAG, "[WARN] scrape:: Failed to find/click Career button. Closing menu.")
            ButtonClose.click(game.imageUtils)
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
            return null
        }
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

        val results = mutableListOf<RaceHistoryEntry>()
        val seenKeys = mutableSetOf<String>()
        try {
            // Use the Career dialog's own scroll-list corner icons so the bbox bounds
            // the actual race-history list region. Then let ScrollList iterate via
            // LabelStrategy as the per-row anchor and drive its own scrolling.
            // The Race History thumb is a real handle, so the list is scrolled by dragging it. A swipe here would drift on past the lift and skip rows.
            val list = createDialogScrollList(game, bThumbIsHandle = true)
            if (list == null) {
                MessageLog.w(TAG, "[WARN] scrape:: Failed to detect race history list bounds; aborting scrape.")
                closeBackToMainScreen(game)
                return null
            }

            list.process(
                entryComponent = LabelStrategy,
                bForceComponentDetection = true,
                // No key extractor. A thumb drag lands every page's rows at the same screen positions, so a key built from the position would mark a whole
                // new page as already seen. The overlap row is read again instead and dropped by name and date below.
                onEntry = { _, entry ->
                    val parsed = parseEntry(game, entry)
                    // A row cut off by the top of the list reads with no name, so it is skipped. Spaces are left out of the key, since OCR reads the same
                    // "Right / Outer" as "Right/ Outer" on another page.
                    if (parsed != null && parsed.nameFormatted.isNotEmpty()) {
                        val key = "${parsed.nameFormatted}|${parsed.dateString}".lowercase().filterNot { it.isWhitespace() }
                        if (seenKeys.add(key)) results.add(parsed)
                    }
                    false
                },
            )
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] scrape:: Exception while iterating Race History entries: ${e.message}.")
            closeBackToMainScreen(game)
            return null
        }

        closeBackToMainScreen(game)
        return results
    }

    /**
     * Tap Close twice to dismiss the Career dialog and the Menu dialog, returning to the Main Screen.
     *
     * @param game Active Game instance for tap/wait access.
     */
    private fun closeBackToMainScreen(game: Game) {
        ButtonClose.click(game.imageUtils)
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
        ButtonClose.click(game.imageUtils)
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
    }

    /**
     * Reads one crop region relative to an anchor point via OCR and trims the result.
     *
     * @param game Active Game instance for OCR.
     * @param bitmap The row bitmap to read from.
     * @param anchor The reference point the crop offsets are measured from, in [bitmap] pixels.
     * @param offsetX Horizontal offset of the crop from the anchor.
     * @param offsetY Vertical offset of the crop from the anchor.
     * @param w Crop width in reference pixels (scaled via relWidth).
     * @param h Crop height in reference pixels (scaled via relHeight).
     * @param debugName Identifier used for debug image dumps.
     * @return The trimmed OCR text for the crop region.
     */
    private fun ocrFromAnchor(game: Game, bitmap: Bitmap, anchor: Point, offsetX: Int, offsetY: Int, w: Int, h: Int, debugName: String): String =
        game.imageUtils
            .performOCRFromReference(
                referencePoint = anchor,
                offsetX = offsetX,
                offsetY = offsetY,
                width = game.imageUtils.relWidth(w),
                height = game.imageUtils.relHeight(h),
                debugName = debugName,
                bitmap = bitmap,
            ).trim()

    /**
     * Parse a single race history row by anchoring on its visible LabelStrategy chip and OCR-ing the crop regions relative to it.
     *
     * Everything is read from the row's own bitmap, cut from the frame the row was found in. A fresh screenshot would be off by however far the list moved since that frame,
     * which puts the crops between two lines of text.
     *
     * @param game Active Game instance for OCR and image search.
     * @param entry The row produced by [ScrollList] component-detection mode.
     * @return The parsed entry, or null if the LabelStrategy anchor could not be located.
     */
    private fun parseEntry(game: Game, entry: ScrollListEntry): RaceHistoryEntry? {
        val bitmap: Bitmap = entry.bitmap
        val anchor: Point =
            LabelStrategy.findImageWithBitmap(
                imageUtils = game.imageUtils,
                sourceBitmap = bitmap,
            ) ?: return null
        val anchorX: Int = anchor.x.toInt()
        val anchorY: Int = anchor.y.toInt()
        val screenY: Int = entry.bbox.y + anchorY

        val nameFormatted = ocrFromAnchor(game, bitmap, anchor, FORMATTED_NAME_OFFSET_X, FORMATTED_NAME_OFFSET_Y, FORMATTED_NAME_W, FORMATTED_NAME_H, "race_history_formatted_$screenY")

        val dateString = ocrFromAnchor(game, bitmap, anchor, DATE_OFFSET_X, DATE_OFFSET_Y, DATE_W, DATE_H, "race_history_date_$screenY")

        val strategy = ocrFromAnchor(game, bitmap, anchor, STRATEGY_OFFSET_X, STRATEGY_OFFSET_Y, STRATEGY_W, STRATEGY_H, "race_history_strategy_$screenY")

        val firstPlaceRegion =
            intArrayOf(
                anchorX + game.imageUtils.relWidth(FIRST_PLACE_OFFSET_X),
                anchorY + game.imageUtils.relHeight(FIRST_PLACE_OFFSET_Y),
                game.imageUtils.relWidth(FIRST_PLACE_W),
                game.imageUtils.relHeight(FIRST_PLACE_H),
            )
        val won: Boolean = IconRaceHistory1st.findImageWithBitmap(game.imageUtils, bitmap, region = firstPlaceRegion) != null

        return RaceHistoryEntry(nameFormatted, dateString, won, strategy)
    }
}
