package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.graphics.Color
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonConditions
import org.opencv.core.Point

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Constants

/** Condition rows visible in the Details Conditions sublist at once (calibrated live). */
private const val VISIBLE_CONDITION_ROWS = 3

/** Maximum scroll passes. Conditions never approach the skill count, so this stays small. */
private const val MAX_CONDITION_SCROLLS = 6

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Result type

/** Holds the trainee's active conditions read from the Details Conditions sublist. */
data class DetailsConditionsResult(val positive: List<String>, val negative: List<String>)

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Reader

/**
 * Reads the trainee's active conditions from the Details dialog's Conditions sublist (the default tab).
 * Polarity is decided by the pill background color, independent of any name list. Each name is stored as
 * raw OCR text and deduped fuzzily across scroll passes. Mirrors [SkillList.parseDetailsSkillsTab].
 *
 * @param game A reference to the [Game] instance for screen capture and gestures.
 */
class ConditionList(private val game: Game) {
    companion object {
        const val TAG: String = "[${MainActivity.loggerTag}]ConditionList"
    }

    /**
     * Reads every active condition, scrolling the sublist until a frame ends short or two passes reveal nothing new.
     * The common case (0 to 3 conditions) ends on the first frame with no swipe.
     *
     * @return The deduped positive and negative condition names.
     */
    fun parseDetailsConditionsTab(): DetailsConditionsResult {
        val imageUtils = game.imageUtils
        val refPoint = ButtonConditions.findImageWithBitmap(imageUtils = imageUtils, sourceBitmap = imageUtils.getSourceBitmap()) ?: Point(285.0, 1210.0)
        val positives = mutableListOf<String>()
        val negatives = mutableListOf<String>()
        var emptyPasses = 0
        for (pass in 0..MAX_CONDITION_SCROLLS) {
            val sourceBitmap = imageUtils.getSourceBitmap()
            var newFound = 0
            var frameFull = true
            for (row in 0 until VISIBLE_CONDITION_ROWS) {
                val read = readConditionRow(sourceBitmap, refPoint, row, pass)
                if (read == null) {
                    // A non-pill slot means the sublist ends inside this frame, so nothing more can be below.
                    frameFull = false
                    break
                }
                val (name, isBad) = read
                val list = if (isBad) negatives else positives
                if (!fuzzyMatchesAny(name, list, STATUS_DEDUP_THRESHOLD)) {
                    list.add(name)
                    newFound++
                }
            }
            emptyPasses = if (newFound == 0) emptyPasses + 1 else 0
            // Frame ended short: bottom reached. Otherwise stop only after two empty passes so a fling settling mid-row does not end the scan early.
            if (!frameFull) break
            if (pass > 0 && emptyPasses >= 2) break
            scrollConditionsPanel()
        }
        MessageLog.i(TAG, "[INFO] Read ${positives.size} positive, ${negatives.size} negative conditions. Positive: ${positives.joinToString(", ")}. Negative: ${negatives.joinToString(", ")}.")
        return DetailsConditionsResult(positives, negatives)
    }

    /**
     * Reads one condition row: classifies polarity by the pill background color, then OCRs the name.
     *
     * @param sourceBitmap The current screen bitmap.
     * @param refPoint The anchored reference point of the Conditions sublist.
     * @param row The 0-indexed visible row.
     * @param pass The current scroll pass, used only for debug crop naming.
     * @return A pair of (raw OCR name, isNegative), or null when the row is not a condition pill.
     */
    private fun readConditionRow(sourceBitmap: Bitmap, refPoint: Point, row: Int, pass: Int): Pair<String, Boolean>? {
        val imageUtils = game.imageUtils
        val cropX = imageUtils.relX(refPoint.x, 10)
        val cropY = imageUtils.relY(refPoint.y, 85 + (row * 180))
        val cropWidth = imageUtils.relWidth(455)
        val cropHeight = imageUtils.relHeight(55)
        val croppedBitmap = imageUtils.createSafeBitmap(sourceBitmap, cropX, cropY, cropWidth, cropHeight, "conditionRow_${pass}_$row") ?: return null

        // Sample the pill background near the bottom-right to avoid text overlap.
        val pixel = croppedBitmap.getPixel(croppedBitmap.width - 20, croppedBitmap.height - 20)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // Bad color: #519FFB (blue). Good color: #FF9741 (orange).
        val isBad = (r in 70..95 && g in 145..175 && b in 240..255)
        val isGood = (r in 240..255 && g in 140..165 && b in 50..80)
        if (!isBad && !isGood) return null

        val statusTitle =
            imageUtils.performOCROnRegion(sourceBitmap, cropX, cropY, cropWidth, cropHeight, useThreshold = false, useGrayscale = true, scale = 2.0, ocrEngine = "mlkit", debugName = "conditionRow_${pass}_$row").trim()
        if (statusTitle.length < 3) return null
        return Pair(statusTitle, isBad)
    }

    /**
     * Scrolls the Conditions sublist up with a short, slow, overlapping swipe (anti-fling), mirroring [SkillList.scrollSkillsPanel].
     * Endpoints are calibrated against a crowded conditions screen.
     */
    private fun scrollConditionsPanel() {
        val cx = (SharedData.displayWidth / 2).toFloat()
        game.gestureUtils.swipe(cx, (SharedData.displayHeight * 0.82).toFloat(), cx, (SharedData.displayHeight * 0.70).toFloat(), 700L)
        game.wait(0.5, skipWaitingForLoading = true)
    }
}
