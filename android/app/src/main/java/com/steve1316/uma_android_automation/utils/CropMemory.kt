package com.steve1316.uma_android_automation.utils

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/** Pixels trimmed from each side of a stored crop before it is matched, so an entry box detected a few pixels off still fits inside the new crop. */
private const val CROP_MATCH_INSET = 8

/** Correlation a crop must reach to count as the same entry. Measured on device, the same skill title scored 0.995 after a scroll and different titles 0.30-0.50. */
private const val CROP_MATCH_MIN_SCORE = 0.95

/** Pixels allowed to differ by more than [CROP_MATCH_DIFF_LEVEL] between two crops of the same entry. A "○" against a "◎" of the same skill differs by far more than this. */
private const val CROP_MATCH_MAX_DIFF_PIXELS = 10

/** Grayscale difference that counts a pixel as changed. The same skill title after a scroll differed by at most 34. */
private const val CROP_MATCH_DIFF_LEVEL = 80.0

/**
 * Converts a bitmap to a grayscale [Mat].
 *
 * @param bitmap The bitmap to convert.
 * @return A new single-channel [Mat] the caller must release.
 */
fun toGrayMat(bitmap: Bitmap): Mat {
    val rgba = Mat()
    Utils.bitmapToMat(bitmap, rgba)
    val gray = Mat()
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    rgba.release()
    return gray
}

/**
 * Remembers the grayscale crops of list entries already read with OCR, so a later frame or a second pass over the list can recognize the same entry by its
 * pixels instead of reading it again.
 *
 * @param T What is remembered for each crop, such as the text read from it.
 */
class CropMemory<T> {
    /** Every distinct crop kept, with its value, in the order they were added. */
    private val known: MutableList<Pair<Mat, T>> = mutableListOf()

    /**
     * Finds the value of a kept crop that shows the same entry. Correlation finds where the kept crop sits, then a pixel diff rules out a near miss such as the
     * same skill with a different rank glyph.
     *
     * @param crop The grayscale crop of the entry to look up.
     * @return The matching value, or null when the entry is new.
     */
    fun find(crop: Mat): T? {
        // Check the newest first, since a repeat is almost always the overlap with the previous frame.
        for ((kept, value) in known.asReversed()) {
            val tplRect = Rect(CROP_MATCH_INSET, CROP_MATCH_INSET, kept.cols() - (2 * CROP_MATCH_INSET), kept.rows() - (2 * CROP_MATCH_INSET))
            if (tplRect.width <= 0 || tplRect.height <= 0 || tplRect.width > crop.cols() || tplRect.height > crop.rows()) continue

            val template = Mat(kept, tplRect)
            val result = Mat()
            Imgproc.matchTemplate(crop, template, result, Imgproc.TM_CCOEFF_NORMED)
            val mmr = Core.minMaxLoc(result)
            result.release()
            if (mmr.maxVal < CROP_MATCH_MIN_SCORE) {
                template.release()
                continue
            }

            val found = Mat(crop, Rect(mmr.maxLoc.x.toInt(), mmr.maxLoc.y.toInt(), tplRect.width, tplRect.height))
            val diff = Mat()
            Core.absdiff(found, template, diff)
            Imgproc.threshold(diff, diff, CROP_MATCH_DIFF_LEVEL, 255.0, Imgproc.THRESH_BINARY)
            val changedPixels: Int = Core.countNonZero(diff)
            diff.release()
            found.release()
            template.release()
            if (changedPixels <= CROP_MATCH_MAX_DIFF_PIXELS) return value
        }
        return null
    }

    /**
     * Keeps a crop with its value. The memory takes ownership of the crop and releases it in [clear].
     *
     * @param crop The grayscale crop.
     * @param value What to remember for it.
     */
    fun add(crop: Mat, value: T) {
        known.add(crop to value)
    }

    /** Releases every kept crop. */
    fun clear() {
        known.forEach { it.first.release() }
        known.clear()
    }
}
