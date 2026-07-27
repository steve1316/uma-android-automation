package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.ImageUtils
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonRaceListFullStats
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.IconEnergyBarLeftPart
import com.steve1316.uma_android_automation.components.IconEnergyBarRightPart0
import com.steve1316.uma_android_automation.components.IconEnergyBarRightPart1
import com.steve1316.uma_android_automation.components.IconEventTitleSpacer
import com.steve1316.uma_android_automation.components.IconRaceListPredictionDoubleStar
import com.steve1316.uma_android_automation.components.IconStatBlockGroup
import com.steve1316.uma_android_automation.components.IconStatBlockGuts
import com.steve1316.uma_android_automation.components.IconStatBlockPower
import com.steve1316.uma_android_automation.components.IconStatBlockSpeed
import com.steve1316.uma_android_automation.components.IconStatBlockStamina
import com.steve1316.uma_android_automation.components.IconStatBlockTrainer
import com.steve1316.uma_android_automation.components.IconStatBlockWit
import com.steve1316.uma_android_automation.components.IconStatSupportEtsukoOtonashi
import com.steve1316.uma_android_automation.components.IconStatSupportLightHello
import com.steve1316.uma_android_automation.components.IconStatSupportRikoKashimoto
import com.steve1316.uma_android_automation.components.IconStatSupportYayoiAkikawa
import com.steve1316.uma_android_automation.components.IconUnityCupExtremeSpiritExplosion
import com.steve1316.uma_android_automation.components.IconUnityCupSpiritExplosion
import com.steve1316.uma_android_automation.components.IconUnityCupSpiritTraining
import com.steve1316.uma_android_automation.components.LabelEnergy
import com.steve1316.uma_android_automation.components.LabelEventProgress
import com.steve1316.uma_android_automation.components.LabelRivalRacer
import com.steve1316.uma_android_automation.components.LabelStatMaxed
import com.steve1316.uma_android_automation.components.LabelStatTableHeaderSkillPoints
import com.steve1316.uma_android_automation.components.LabelStatTrackSurface
import com.steve1316.uma_android_automation.components.LabelTrainingFailureChance
import com.steve1316.uma_android_automation.components.Region
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.StatName
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.lang.Integer.max
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.text.replace

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Rainbow-ring detection constants

// The support face circles (findSupportFaceCircles) and the debug annotate crop (debugRainbowDetection) both scan the top-right third of the screen down to this fraction of the height.
// The rest of the rainbow-ring tuning (glow HSV bands, Hough params) lives as locals in the detector functions since each is used in only one place.
private const val RAINBOW_SUPPORT_REGION_HEIGHT_FRACTION = 0.75

/**
 * Whether the three rainbow-glow hue fractions indicate a rainbow ring. A ring is present only when all three pastel hues (green, cyan, pink) each exceed the presence floor, which is
 * the signature that separates a real rainbow glow from a background that happens to be one of those colors. Pure so it is unit-testable without OpenCV.
 *
 * @param greenFraction Fraction of glow-annulus pixels that are bright green.
 * @param cyanFraction Fraction that are bright cyan.
 * @param pinkFraction Fraction that are bright pink.
 * @param minFraction The per-hue presence floor (default 0.03).
 * @return The number of the three hues that are present (a ring needs all three).
 */
internal fun rainbowHuesPresent(greenFraction: Double, cyanFraction: Double, pinkFraction: Double, minFraction: Double = 0.03): Int =
    listOf(greenFraction, cyanFraction, pinkFraction).count { it > minFraction }

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Template argmax helper

/**
 * Returns the key with the highest score, but only if that score clears the floor. This is the argmax that replaces the old first-match-wins loop for aptitude letter detection, so a lower-ranked
 * letter that merely clears the confidence floor can no longer win over the true best match. Pure so it is unit-testable without OpenCV.
 *
 * @param scores Map of candidate keys to their template-match correlation scores.
 * @param floor The minimum score the winner must reach to be accepted.
 * @return The key with the highest score at or above [floor], or null if the map is empty or the best score is below the floor.
 */
internal fun <K> argMaxAboveFloor(scores: Map<K, Double>, floor: Double): K? {
    val best = scores.maxByOrNull { it.value } ?: return null
    return if (best.value >= floor) best.key else null
}

/**
 * Parse the stat cap from the OCR text of the "/NNNN" denominator shown under a stat on the career / training screen. The cap is the largest number present - it is always >= the
 * current value, so taking the max is robust even when the crop catches part of the value. Returns null when no plausible cap is found so the caller can fall back to the
 * per-scenario cap table. The caller additionally rejects any cap below the scenario's base cap, since sparks / inheritance / duels only ever raise caps.
 *
 * @param text Raw OCR text of the cap-denominator region.
 * @return The parsed stat cap in [1000, 2000], or null if none is plausible.
 */
internal fun parseStatCap(text: String): Int? {
    // Plausible OCR'd cap range: base scenario caps start at 1200 (a read below 1000 is a value misread), and the game hard-caps stats at 2000 (a read above it is an OCR misread).
    val minPlausibleCap = 1000
    val maxPlausibleCap = 2000
    val candidate = Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: return null
    return if (candidate in minPlausibleCap..maxPlausibleCap) candidate else null
}

/** Utility functions for image processing via CV like OpenCV. */
class CustomImageUtils(context: Context, private val game: Game) : ImageUtils(context) {
    /** OCR threshold for text recognition. */
    private val threshold: Int = SettingsHelper.getIntSetting("debug", "ocrThreshold")

    /** Whether debug mode is enabled for additional logging and saving debugging images to storage. */
    override var debugMode: Boolean = SettingsHelper.getBooleanSetting("debug", "enableDebugMode")

    /** Template matching confidence threshold. */
    override var confidence: Double = SettingsHelper.getDoubleSetting("debug", "templateMatchConfidence", 0.8)

    /** Custom scale factor for template matching. */
    override var customScale: Double = SettingsHelper.getDoubleSetting("debug", "templateMatchCustomScale", 1.0)

    /** Maximum allowed value for a single stat. */
    private val manualStatCap: Int = SettingsHelper.getIntSetting("training", "manualStatCap")

    /** Whether to use YOLOv8 for stat detection. */
    private val useYolo: Boolean = SettingsHelper.getBooleanSetting("training", "enableYoloStatDetection")

    /**
     * Defines the details of a race.
     *
     * @property fans The number of fans awarded by the race.
     * @property hasDoublePredictions Whether the race has double circle predictions.
     * @property isRival Whether the race features a rival Umamusume.
     */
    data class RaceDetails(val fans: Int, val hasDoublePredictions: Boolean, val isRival: Boolean = false)

    /**
     * Defines a block of stat information on the screen.
     *
     * @property name The name of the stat or icon.
     * @property point The screen coordinates of the stat block.
     * @property trainerName The name of the trainer associated with this block, if any.
     */
    data class StatBlock(val name: String, val point: Point, val trainerName: String? = null)

    /**
     * Defines the configuration for a single row of stat gain detection.
     *
     * @property startX The starting X-coordinate of the row.
     * @property startY The starting Y-coordinate of the row.
     * @property width The width of the detection region.
     * @property height The height of the detection region.
     * @property rowName The human-readable name of the row (e.g., "row 1").
     * @property templateSuffix The suffix to append to template names for this row (e.g., "_mini").
     */
    data class StatGainRowConfig(val startX: Int, val startY: Int, val width: Int, val height: Int, val rowName: String, val templateSuffix: String = "")

    /**
     * Defines the result of analyzing a relationship or energy bar.
     *
     * @property statName The name of the stat associated with this bar.
     * @property fillPercent The percentage of the bar that is filled.
     * @property filledSegments The number of discrete segments filled (0-5).
     * @property dominantColor The dominant color of the bar (e.g., "orange", "blue").
     * @property statBlock The underlying stat block used for analysis, if any.
     */
    data class BarFillResult(val statName: StatName, val fillPercent: Double, val filledSegments: Int, val dominantColor: String, val statBlock: StatBlock? = null) {
        /** Whether this bar belongs to a trainer support character. */
        val isTrainerSupport: Boolean
            get() = statBlock != null && statBlock.name == "trainer_support"

        /** The name of the trainer associated with this bar. */
        val trainerName: String?
            get() = statBlock?.trainerName
    }

    /**
     * Result of the rainbow-ring detector for one circular anchor (a support face circle or a training button).
     *
     * @property huesPresent How many of the three rainbow-glow hues (green, cyan, pink) are present in the glow annulus. A ring needs all three.
     * @property greenFraction Fraction of glow-annulus pixels that are bright green.
     * @property cyanFraction Fraction that are bright cyan.
     * @property pinkFraction Fraction that are bright pink.
     * @property brightChromaticFraction Fraction of annulus pixels that are bright and saturated (the overall glow strength).
     */
    data class RainbowRingResult(val huesPresent: Int, val greenFraction: Double, val cyanFraction: Double, val pinkFraction: Double, val brightChromaticFraction: Double) {
        /**
         * Whether the annulus shows a rainbow glow: all three pastel hues co-present (huesPresent == 3) and one of them vividly dominant (>= 0.08; measured true rings had a dominant hue
         * >= 0.096 versus a 0.058 false positive). Validated at 100% on the labeled training-screen dataset.
         */
        val isRainbow: Boolean
            get() = huesPresent >= 3 && maxOf(greenFraction, cyanFraction, pinkFraction) >= 0.08
    }

    /**
     * Defines the result of analyzing Spirit Explosion gauges.
     *
     * @property numGaugesCanFill Number of gauges that can be filled by this training.
     * @property numGaugesReadyToBurst Number of gauges that are already full and ready to burst (normal Spirit Burst).
     * @property numGaugesReadyToExtremeBurst Number of supports ready for an Extreme Spirit Burst (purple flames) - a bigger, cap-raising, 0% fail one-time burst.
     */
    data class SpiritGaugeResult(val numGaugesCanFill: Int, val numGaugesReadyToBurst: Int, val numGaugesReadyToExtremeBurst: Int)

    /**
     * Defines the result of detecting stat gains from a training session.
     *
     * @property statGains Mapping of stat names to their detected integer gain values.
     * @property rowValuesMap Mapping of stat names to individual row values (for multi-row scenarios).
     * @property correctedStats List of stats that required value correction during detection.
     */
    data class StatGainResult(val statGains: Map<StatName, Int>, val rowValuesMap: Map<StatName, List<Int>>, val correctedStats: List<StatName> = emptyList())

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]CustomImageUtils"

        /** Matches any run of whitespace. Used to collapse the newlines that OCR inserts when it wraps text onto more than one line. */
        private val WHITESPACE_REGEX = Regex("\\s+")

        @Volatile
        private var yoloDetectorInstance: YoloDetector? = null

        /**
         * Returns the singleton YoloDetector instance, initializing it if necessary.
         *
         * @param context Android context for asset loading.
         * @return The YoloDetector instance.
         */
        fun getYoloDetector(context: Context): YoloDetector =
            yoloDetectorInstance ?: synchronized(this) {
                yoloDetectorInstance ?: YoloDetector(context).also { yoloDetectorInstance = it }
            }
    }

    init {
        initTesseract("eng.traineddata")
        SharedData.templateSubfolderPathName = "images/"
    }

    /** Converts this bitmap to a new OpenCV [Mat]. */
    private fun Bitmap.toMat(): Mat {
        val mat = Mat()
        Utils.bitmapToMat(this, mat)
        return mat
    }

    /**
     * Converts this bitmap to a new HSV [Mat], releasing the intermediates. [toMat] yields an RGBA mat, so the alpha is dropped with `RGBA2RGB` and never `BGR2RGB` - the latter swaps red
     * and blue and would push a blue subject into the red hues, which is the recurring footgun in every hue range in this file.
     */
    private fun Bitmap.toHsvMat(): Mat {
        val rgbaMat = toMat()
        val rgbMat = Mat()
        Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        val hsvMat = Mat()
        Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)
        rgbaMat.release()
        rgbMat.release()
        return hsvMat
    }

    /**
     * Groups the column sums of a mask into runs of consecutive "filled" columns, dropping any run narrower than [minRunWidth]. Each run is one shape when the shapes are separated by
     * background gaps, so this counts adjacent shapes that template matching cannot (see [countEventProgressChevrons]). Pure so the segmentation can be reasoned about on its own.
     *
     * @param columnSums Per-column sums of a 0/255 mask, as produced by `Core.reduce(mask, ..., Core.REDUCE_SUM, CvType.CV_32S)`.
     * @param minColumnPixels The minimum number of mask pixels a column needs before it counts as part of a shape.
     * @param minRunWidth The minimum width in columns for a run to be a real shape rather than a border or shadow line.
     * @return The column ranges of the detected shapes, left to right.
     */
    private fun segmentRuns(columnSums: IntArray, minColumnPixels: Int, minRunWidth: Int): List<IntRange> {
        // REDUCE_SUM over a 0/255 mask yields 255 per set pixel, so scale the pixel threshold instead of dividing every column.
        val minColumnSum = minColumnPixels * 255
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < columnSums.size) {
            if (columnSums[x] < minColumnSum) {
                x++
                continue
            }
            val start = x
            while (x < columnSums.size && columnSums[x] >= minColumnSum) x++
            if (x - start >= minRunWidth) runs.add(start until x)
        }
        return runs
    }

    /** Converts this [Mat] to a new ARGB bitmap of matching dimensions. */
    private fun Mat.toBitmap(): Bitmap {
        val bitmap = createBitmap(cols(), rows())
        Utils.matToBitmap(this, bitmap)
        return bitmap
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Finds all occurrences of an image using a provided source bitmap.
     *
     * Useful for parallel processing to avoid exceeding the maximum image buffer.
     *
     * @param templateName The filename of the template image.
     * @param sourceBitmap The source bitmap to search within.
     * @param region Region (x, y, width, height) of the source to match. Defaults to (0, 0, 0, 0) for full image.
     * @param customConfidence Optional confidence threshold override. Defaults to 0.0.
     * @return An array of points for all occurrences found.
     */
    fun findAllWithBitmap(templateName: String, sourceBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): ArrayList<Point> {
        var templateBitmap: Bitmap?
        context.assets?.open("images/$templateName.png").use { inputStream ->
            templateBitmap = BitmapFactory.decodeStream(inputStream)
        }

        if (templateBitmap != null) {
            val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = customConfidence)

            // Sort the match locations by ascending x and y coordinates.
            matchLocations.sortBy { it.x }
            matchLocations.sortBy { it.y }

            if (debugMode) {
                MessageLog.d(TAG, "[DEBUG] findAllWithBitmap:: Found match locations for $templateName: $matchLocations.")
            } else {
                Log.d(TAG, "[DEBUG] findAllWithBitmap:: Found match locations for $templateName: $matchLocations.")
            }

            return matchLocations
        }

        return arrayListOf()
    }

    /**
     * Finds a single occurrence of an image using a provided source bitmap.
     *
     * Useful for parallel processing to avoid exceeding the maximum image buffer.
     *
     * @param templateName The filename of the template image.
     * @param sourceBitmap The source bitmap to search within.
     * @param region Region (x, y, width, height) of the source to match. Defaults to (0, 0, 0, 0) for full search.
     * @param customConfidence Optional confidence threshold override. Defaults to 0.0.
     * @param suppressError Whether to suppress error logging if not found. Defaults to false.
     * @return The location of the first occurrence found, or null if missing.
     */
    fun findImageWithBitmap(templateName: String, sourceBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0, suppressError: Boolean = false): Point? {
        var templateBitmap: Bitmap?
        context.assets?.open("images/$templateName.png").use { inputStream ->
            templateBitmap = BitmapFactory.decodeStream(inputStream)
        }

        if (templateBitmap != null) {
            val matchLocation = match(sourceBitmap, templateBitmap, templateName, region = region, customConfidence = customConfidence).second
            if (matchLocation == null && !suppressError) {
                if (debugMode) {
                    MessageLog.e(TAG, "[ERROR] findImageWithBitmap:: Could not find $templateName in the provided source bitmap.")
                } else {
                    Log.e(TAG, "[ERROR] findImageWithBitmap:: Could not find $templateName in the provided source bitmap.")
                }
            }
            return matchLocation
        }
        return null
    }

    /**
     * Finds the best-matching candidate template within a cropped cell by argmax over correlation scores.
     *
     * Unlike the first-match-wins loop it replaces, this scores every candidate template and returns the one with the highest TM_CCOEFF_NORMED correlation, so a lower-ranked template that merely
     * clears the confidence floor can no longer shadow the true best match. The cell is normalized to the 1080p reference crop size so the reference-sized templates match at scale 1.0 on any device.
     *
     * @param sourceBitmap The cropped cell bitmap containing a single glyph.
     * @param candidates Map of candidate keys to the [ComponentInterface] whose template represents each.
     * @param referenceWidth The 1080p reference width of the crop, used to normalize the cell before matching.
     * @param referenceHeight The 1080p reference height of the crop, used to normalize the cell before matching.
     * @param minConfidence The minimum correlation the winner must reach. Defaults to the global template-match confidence.
     * @return The best-matching key, or null if no candidate template reached [minConfidence].
     */
    fun <K> findBestTemplateMatch(sourceBitmap: Bitmap, candidates: Map<K, ComponentInterface>, referenceWidth: Int, referenceHeight: Int, minConfidence: Double = confidence): K? {
        // Normalize the cell to the reference crop size so the fixed reference-sized templates match at scale 1.0 regardless of device resolution.
        val normalizedCell: Bitmap =
            if (sourceBitmap.width != referenceWidth || sourceBitmap.height != referenceHeight) sourceBitmap.scale(referenceWidth, referenceHeight) else sourceBitmap

        val sourceMat: Mat = normalizedCell.toMat()
        Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)

        val scores = mutableMapOf<K, Double>()
        for ((key, component) in candidates) {
            val templateBitmap: Bitmap = component.template.getBitmap(this) ?: continue
            val templateMat: Mat = templateBitmap.toMat()
            Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)

            // Skip templates larger than the cell to avoid an invalid matchTemplate call.
            if (templateMat.cols() <= sourceMat.cols() && templateMat.rows() <= sourceMat.rows()) {
                val resultMat = Mat()
                Imgproc.matchTemplate(sourceMat, templateMat, resultMat, Imgproc.TM_CCOEFF_NORMED)
                scores[key] = Core.minMaxLoc(resultMat).maxVal
                resultMat.release()
            }
            templateMat.release()
        }
        sourceMat.release()

        return argMaxAboveFloor(scores, minConfidence)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Training Event Helper Functions

    /**
     * Performs OCR detection on the training event title.
     *
     * @param increment Optional threshold adjustment value. Defaults to 0.0.
     * @return The detected event title, or an empty string if detection fails.
     */
    fun findEventTitle(increment: Double = 0.0): String {
        val sourceBitmap: Bitmap = getSourceBitmap()

        // Acquire the location of the energy text image.
        val matchLocation: Point? = LabelEnergy.findImageWithBitmap(this, sourceBitmap = sourceBitmap)
        if (matchLocation == null) {
            MessageLog.w(TAG, "[WARN] findEventTitle:: Could not proceed with OCR text detection due to not being able to find the energy template on the source image.")
            return ""
        }

        // Use the match location acquired from finding the energy text image and acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
        val newX: Int = max(0, matchLocation.x.toInt() - relWidth(125))
        val newY: Int = max(0, matchLocation.y.toInt() + relHeight(116))
        var croppedBitmap: Bitmap? = createSafeBitmap(sourceBitmap, newX, newY, relWidth(645), relHeight(65), "findEventTitle crop")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] findEventTitle:: Failed to create cropped bitmap for text detection")
            return ""
        }

        val tempImage = croppedBitmap.toMat()
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText.png", tempImage)

        // Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
        croppedBitmap =
            if (IconEventTitleSpacer.check(this, sourceBitmap = croppedBitmap, region = intArrayOf(0, 0, 0, 0))) {
                Log.d(TAG, "[DEBUG] findEventTitle:: Shifting the region over by 70 pixels!")
                createSafeBitmap(sourceBitmap, relX(newX.toDouble(), 70), newY, 645 - 70, 65, "findEventTitle shifted crop") ?: croppedBitmap
            } else {
                Log.d(TAG, "[DEBUG] findEventTitle:: Do not need to shift.")
                croppedBitmap
            }

        // Make the cropped screenshot grayscale.
        val cvImage = croppedBitmap.toMat()
        Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)

        // Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterCrop.png", cvImage)

        // Thresh the grayscale cropped image to make it black and white.
        val bwImage = Mat()
        Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterThreshold.png", bwImage)

        // Convert the Mat directly to Bitmap and then pass it to the text reader.
        val resultBitmap = bwImage.toBitmap()
        tessBaseAPI.setImage(resultBitmap)

        var result = ""
        try {
            // Finally, detect text on the cropped region.
            result = tessBaseAPI.utF8Text
            MessageLog.i(TAG, "[INFO] Detected event title text with Tesseract: $result")
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] Cannot perform OCR: ${e.stackTraceToString()}")
        }

        tessBaseAPI.clear()
        tempImage.release()
        cvImage.release()
        bwImage.release()

        return result
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Training Helper Functions

    /**
     * Finds the failure percentage chance for the currently selected training.
     *
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param trainingSelectionLocation Optional point location of the training selection icon. Defaults to null.
     * @param tries The number of retry attempts to make. Defaults to 1.
     * @return Integer representing the failure percentage, or -1 if detection fails.
     */
    fun findTrainingFailureChance(sourceBitmap: Bitmap? = null, trainingSelectionLocation: Point? = null, tries: Int = 1): Int {
        fun detectTrainingFailureChance(sourceBitmap: Bitmap? = null, trainingSelectionLocation: Point? = null): Int {
            // Crop the source screenshot to hold the success percentage only.
            // Delay for failure bubble tween.
            game.waitForLoading()

            val (trainingSelectionLocation, sourceBitmap) =
                if (sourceBitmap == null && trainingSelectionLocation == null) {
                    LabelTrainingFailureChance.find(this)
                } else {
                    Pair(trainingSelectionLocation, sourceBitmap)
                }

            if (trainingSelectionLocation == null) {
                return -1
            }

            // Determine crop region and small adjustments for improved OCR rates.
            val (offsetX, offsetY, width, height) = listOf(-50, 10, relWidth(100), relHeight(55))

            // Perform OCR with 2x scaling and no thresholding.
            val detectedText =
                performOCROnRegion(
                    sourceBitmap!!,
                    relX(trainingSelectionLocation.x, offsetX),
                    relY(trainingSelectionLocation.y, offsetY),
                    width,
                    height,
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "tesseract_digits",
                    debugName = "TrainingFailureChance",
                )

            // Parse the result. Take the digit run immediately before the "%" sign (falling back to the first digit run) instead of concatenating every digit - a low-contrast 0% bubble can
            // make the general recognizer emit stray digits, and blind concatenation fused them into phantom values like 10 / 12. The "o" -> "0" swap and the > 100 guard stay as backstops.
            return try {
                val normalized = detectedText.lowercase().replace("o", "0").replace("failure", "")
                val cleanedResult = (Regex("(\\d+)\\s*%").find(normalized)?.groupValues?.get(1) ?: Regex("\\d+").find(normalized)?.value)?.trim().orEmpty()

                val value = cleanedResult.toInt()

                // A failure chance above 100% is an impossible OCR misread (e.g. a stray extra digit). Reject it as invalid rather than
                // fabricating a value by dropping a digit; the retry gets a fresh bitmap and the caller's fallback + energy clamp handle it.
                if (value > 100) {
                    Log.w(TAG, "[WARN] findTrainingFailureChance:: Failure chance $value% exceeds 100%; treating as an invalid read.")
                    return -1
                }

                value
            } catch (_: NumberFormatException) {
                MessageLog.e(TAG, "[ERROR] findTrainingFailureChance:: Could not convert \"$detectedText\" to integer for training failure chance.")
                -1
            }
        }

        val tries: Int = maxOf(1, tries)
        var result: Int = -1
        for (i in 1..tries) {
            // Use the passed parameters on the first attempt only; a retry wants a fresh source bitmap.
            val attempt =
                if (i == 1) {
                    detectTrainingFailureChance(sourceBitmap, trainingSelectionLocation)
                } else {
                    detectTrainingFailureChance()
                }

            // Keep the first valid read and stop; an invalid one (-1, or a > 100 read rejected above) is retried with a fresh bitmap.
            if (attempt in 0..100) {
                result = attempt
                break
            }
            MessageLog.w(TAG, "[WARN] findTrainingFailureChance:: Failed to detect a valid training failure chance (attempt $i of $tries, read=$attempt)")
        }

        if (debugMode) {
            MessageLog.i(TAG, "[INFO] Failure chance of '$result'% at $trainingSelectionLocation")
        } else {
            Log.i(TAG, "[INFO] Failure chance of '$result'% at $trainingSelectionLocation")
        }
        return result
    }

    /**
     * Analyzes relationship bars for the currently selected training.
     *
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param statName The stat name associated with the selected training.
     * @param scenario The current game scenario for specialized trainer detection.
     * @return A list of BarFillResult objects for each detected relationship bar.
     */
    fun analyzeRelationshipBars(sourceBitmap: Bitmap? = null, statName: StatName, scenario: String? = null): ArrayList<BarFillResult> {
        // Take a single screenshot first to avoid buffer overflow.
        val sourceBitmap = sourceBitmap ?: getSourceBitmap()

        val latch = CountDownLatch(6)

        val statBlockComponentMap: Map<String, ComponentInterface> =
            mapOf(
                StatName.SPEED.name to IconStatBlockSpeed,
                StatName.STAMINA.name to IconStatBlockStamina,
                StatName.POWER.name to IconStatBlockPower,
                StatName.GUTS.name to IconStatBlockGuts,
                StatName.WIT.name to IconStatBlockWit,
                "trainer" to IconStatBlockTrainer,
                "group" to IconStatBlockGroup,
            )

        val statSupportComponentMap: Map<String, ComponentInterface> =
            mapOf(
                "stat_support_etsuko_otonashi" to IconStatSupportEtsukoOtonashi,
                "stat_support_light_hello" to IconStatSupportLightHello,
                "stat_support_riko_kashimoto" to IconStatSupportRikoKashimoto,
                "stat_support_yayoi_akikawa" to IconStatSupportYayoiAkikawa,
            )

        var allStatBlocks: MutableList<StatBlock> = mutableListOf()
        val blockMap = ConcurrentHashMap<String, ArrayList<Point>>()
        val threads = mutableListOf<Thread>()

        for ((name, component) in statBlockComponentMap) {
            val thread =
                Thread {
                    try {
                        blockMap[name] = component.findAll(this, sourceBitmap = sourceBitmap, region = Region.topRightThird)
                    } catch (_: InterruptedException) {
                    } finally {
                        latch.countDown()
                    }
                }.apply { isDaemon = true }
            threads.add(thread)
            thread.start()
        }

        // Wait for all threads to complete.
        try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            MessageLog.e(TAG, "[ERROR] analyzeRelationshipBars:: Parallel findAll operations timed out.")
        }

        threads.forEach { it.join() }

        // Combine all results.
        for ((blockName, blocks) in blockMap) {
            blocks.forEach { block ->
                allStatBlocks.add(StatBlock(blockName, block))
            }
        }

        // Check for scenario-specific trainer supports that do NOT show up with stat_trainer_block.
        // At most one trainer support can appear per training option.
        val foundTrainerBlock = blockMap["trainer"]?.isNotEmpty() == true
        if (scenario != null) {
            // Define scenario-specific trainer support assets.
            val trainerSupportAssets: List<Triple<String, String, Boolean>> =
                when (scenario) {
                    "URA Finale" -> {
                        listOf(
                            Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
                            Triple("stat_support_yayoi_akikawa", "Yayoi Akikawa", false),
                        )
                    }

                    "Unity Cup" -> {
                        listOf(
                            Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
                            // Riko Kashimoto can also show up as a support card support with stat_trainer_block.
                            Triple("stat_support_riko_kashimoto", "Riko Kashimoto", true),
                        )
                    }

                    "Trackblazer" -> {
                        listOf(
                            Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
                            Triple("stat_support_yayoi_akikawa", "Yayoi Akikawa", false),
                        )
                    }

                    "Grand Live" -> {
                        listOf(
                            Triple("stat_support_light_hello", "Light Hello", false),
                            Triple("stat_support_etsuko_otonashi", "Etsuko Otonashi", false),
                            Triple("stat_support_yayoi_akikawa", "Yayoi Akikawa", false),
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }

            // Filter out trainers that also show with stat_trainer_block if one was already found.
            val trainersToSearch =
                if (foundTrainerBlock) {
                    trainerSupportAssets.filter { !it.third }
                } else {
                    trainerSupportAssets
                }

            // Search for trainer supports. At most one can appear at a time for any one training option.
            for ((assetName, trainerName, _) in trainersToSearch) {
                // We need to hardcode these values so if we fail to fetch the value then that is a programmer error.
                val component: ComponentInterface = statSupportComponentMap[assetName]!!
                val trainerLocation = component.findImageWithBitmap(this, sourceBitmap = sourceBitmap, region = Region.topRightThird)
                if (trainerLocation != null) {
                    // Store the actual center location. The processing loop will use a different offset for trainer_support.
                    allStatBlocks.add(StatBlock("trainer_support", trainerLocation, trainerName))

                    // Only one trainer support can appear per training option.
                    break
                }
            }
        }

        // Filter out duplicates based on exact coordinate matches.
        allStatBlocks =
            allStatBlocks.distinctBy {
                "${it.point.x},${it.point.y}"
            }.toMutableList()

        // Sort the combined stat blocks by ascending y-coordinate.
        allStatBlocks.sortBy { it.point.y }

        return analyzeRelationshipBarFills(sourceBitmap, allStatBlocks, statName)
    }

    /**
     * Computes the relationship-bar fill result for each detected stat block.
     *
     * @param sourceBitmap The screenshot the stat blocks were detected in.
     * @param allStatBlocks The detected, de-duplicated, y-sorted stat blocks to analyze.
     * @param statName The stat these bars belong to, stamped onto each result.
     * @return One [BarFillResult] per analyzable stat block.
     */
    private fun analyzeRelationshipBarFills(sourceBitmap: Bitmap, allStatBlocks: List<StatBlock>, statName: StatName): ArrayList<BarFillResult> {
        // Define HSV color ranges.
        val blueLower = Scalar(10.0, 150.0, 150.0)
        val blueUpper = Scalar(25.0, 255.0, 255.0)
        val greenLower = Scalar(40.0, 150.0, 150.0)
        val greenUpper = Scalar(80.0, 255.0, 255.0)
        val orangeLower = Scalar(100.0, 150.0, 150.0)
        val orangeUpper = Scalar(130.0, 255.0, 255.0)

        val results = arrayListOf<BarFillResult>()

        for ((index, statBlock) in allStatBlocks.withIndex()) {
            if (debugMode) MessageLog.d(TAG, "[DEBUG] analyzeRelationshipBars:: Processing stat block #${index + 1} (${statBlock.name}) at position: (${statBlock.point.x}, ${statBlock.point.y})")

            // Use different offsets based on block type.
            // Stat blocks: relationship bar is at offset (-9, 107) from detected location.
            // Trainer supports: relationship bar is at offset (-50, 55) from icon center.
            val (offsetX, offsetY) =
                if (statBlock.name == "trainer_support") {
                    Pair(-50, 55)
                } else {
                    Pair(-9, 107)
                }

            val croppedBitmap = createSafeBitmap(sourceBitmap, relX(statBlock.point.x, offsetX), relY(statBlock.point.y, offsetY), 111, 13, "analyzeRelationshipBars stat block ${index + 1}")
            if (croppedBitmap == null) {
                MessageLog.e(TAG, "[ERROR] analyzeRelationshipBars:: Failed to create cropped bitmap for stat block #${index + 1}.")
                continue
            }

            if (LabelStatMaxed.check(this, sourceBitmap = croppedBitmap)) {
                // Skip if the relationship bar is already maxed.
                if (debugMode) {
                    MessageLog.d(TAG, "[DEBUG] analyzeRelationshipBars:: Relationship bar #${index + 1} is full.")
                }
                results.add(BarFillResult(statName, 100.0, 5, "orange", statBlock))
                continue
            }

            val barMat = croppedBitmap.toMat()

            // Convert to RGB and then to HSV for better color detection.
            val rgbMat = Mat()
            Imgproc.cvtColor(barMat, rgbMat, Imgproc.COLOR_BGR2RGB)
            if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug_relationshipBar${index + 1}AfterRGB.png", rgbMat)
            val hsvMat = Mat()
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            val blueMask = Mat()
            val greenMask = Mat()
            val orangeMask = Mat()

            // Count the pixels for each color.
            Core.inRange(hsvMat, blueLower, blueUpper, blueMask)
            Core.inRange(hsvMat, greenLower, greenUpper, greenMask)
            Core.inRange(hsvMat, orangeLower, orangeUpper, orangeMask)
            val bluePixels = Core.countNonZero(blueMask)
            val greenPixels = Core.countNonZero(greenMask)
            val orangePixels = Core.countNonZero(orangeMask)

            // Sum the colored pixels.
            val totalColoredPixels = bluePixels + greenPixels + orangePixels
            val totalPixels = barMat.rows() * barMat.cols()

            // Estimate the fill percentage based on the total colored pixels.
            val fillPercent =
                if (totalPixels > 0) {
                    (totalColoredPixels.toDouble() / totalPixels.toDouble()) * 100.0
                } else {
                    0.0
                }

            // Estimate the filled segments (each segment is about 20% of the whole bar).
            val filledSegments = (fillPercent / 20).coerceAtMost(5.0).toInt()

            // Determine dominant color, but normalize to "none" if fill is essentially 0%.
            val dominantColor =
                if (fillPercent < 1.0) {
                    "none"
                } else {
                    when {
                        orangePixels > greenPixels && orangePixels > bluePixels -> "orange"
                        greenPixels > bluePixels -> "green"
                        bluePixels > 0 -> "blue"
                        else -> "unknown"
                    }
                }

            blueMask.release()
            greenMask.release()
            orangeMask.release()
            hsvMat.release()
            barMat.release()

            if (debugMode) {
                MessageLog.d(
                    TAG,
                    "[DEBUG] analyzeRelationshipBars:: Relationship bar #${index + 1} is ${
                        decimalFormat.format(
                            fillPercent,
                        )
                    }% filled with $filledSegments filled segments and the dominant color is $dominantColor",
                )
            }
            results.add(BarFillResult(statName, fillPercent, filledSegments, dominantColor, statBlock))
        }

        return results
    }

    /**
     * Detects a rainbow glow ring in the annulus just outside a circular anchor (a support face circle or a training button). Rainbow trainings pulse a bright pastel green/cyan/pink
     * halo around the circle, so requiring all three hues to co-occur in the annulus separates a real rainbow from a background that merely happens to be one of those colors.
     *
     * @param sourceBitmap The screenshot to analyze.
     * @param centerX The circle center x in screen pixels.
     * @param centerY The circle center y in screen pixels.
     * @param radius The circle radius in screen pixels (the glow sits just outside this).
     * @return A [RainbowRingResult] with the per-hue fractions and whether a ring is present. All-zero when the crop could not be taken.
     */
    fun detectRainbowRing(sourceBitmap: Bitmap, centerX: Int, centerY: Int, radius: Int): RainbowRingResult {
        // Rainbow glow calibration. The glow sweeps bright pastel green, cyan, and pink, so require a minimum saturation/value and match each hue band in the annulus. Hue bands are true
        // OpenCV HSV (0-179) from the RGBA->RGB->HSV path below (NOT the BGR->RGB->HSV path the relationship-bar code uses, which swaps red and blue and is not what these bands expect).
        val annulusInnerPad = 1
        val annulusOuterPad = 22
        val minSaturation = 50.0
        val minValue = 165.0
        val greenHueLow = 45.0
        val greenHueHigh = 82.0
        val cyanHueLow = 84.0
        val cyanHueHigh = 104.0
        val pinkHueLow = 148.0
        val pinkHueHigh = 173.0

        val outerRadius = radius + annulusOuterPad
        val side = outerRadius * 2
        val cropped =
            createSafeBitmap(sourceBitmap, centerX - outerRadius, centerY - outerRadius, side, side, "detectRainbowRing") ?: return RainbowRingResult(0, 0.0, 0.0, 0.0, 0.0)

        val rgbaMat = cropped.toMat()
        val rgbMat = Mat()
        Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        val hsvMat = Mat()
        Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

        // Build the glow annulus mask (outer filled circle minus inner filled circle), centered in the crop.
        val cropCenter = Point(outerRadius.toDouble(), outerRadius.toDouble())
        val annulusMask = Mat.zeros(hsvMat.size(), CvType.CV_8UC1)
        Imgproc.circle(annulusMask, cropCenter, outerRadius, Scalar(255.0), -1)
        Imgproc.circle(annulusMask, cropCenter, radius + annulusInnerPad, Scalar(0.0), -1)
        val annulusTotal = Core.countNonZero(annulusMask)
        if (annulusTotal <= 0) {
            listOf(rgbaMat, rgbMat, hsvMat, annulusMask).forEach { it.release() }
            return RainbowRingResult(0, 0.0, 0.0, 0.0, 0.0)
        }

        // Bright and saturated pixels only (the glow is luminous), intersected with the annulus. This fraction feeds only the debug log and an unused result field, so compute it only in
        // debug mode - it is an extra inRange + bitwise_and + countNonZero per circle on the per-turn hot path otherwise.
        val brightChromaticFraction =
            if (debugMode) {
                val brightMask = Mat()
                Core.inRange(hsvMat, Scalar(0.0, minSaturation, minValue), Scalar(179.0, 255.0, 255.0), brightMask)
                Core.bitwise_and(brightMask, annulusMask, brightMask)
                val fraction = Core.countNonZero(brightMask).toDouble() / annulusTotal
                brightMask.release()
                fraction
            } else {
                0.0
            }

        // Fraction of annulus pixels in each rainbow hue band (bright and saturated).
        fun hueFraction(low: Double, high: Double): Double {
            val hueMask = Mat()
            Core.inRange(hsvMat, Scalar(low, minSaturation, minValue), Scalar(high, 255.0, 255.0), hueMask)
            Core.bitwise_and(hueMask, annulusMask, hueMask)
            val fraction = Core.countNonZero(hueMask).toDouble() / annulusTotal
            hueMask.release()
            return fraction
        }
        val greenFraction = hueFraction(greenHueLow, greenHueHigh)
        val cyanFraction = hueFraction(cyanHueLow, cyanHueHigh)
        val pinkFraction = hueFraction(pinkHueLow, pinkHueHigh)

        listOf(rgbaMat, rgbMat, hsvMat, annulusMask).forEach { it.release() }

        val huesPresent = rainbowHuesPresent(greenFraction, cyanFraction, pinkFraction)
        val result = RainbowRingResult(huesPresent, greenFraction, cyanFraction, pinkFraction, brightChromaticFraction)
        if (debugMode) {
            MessageLog.d(
                TAG,
                "[DEBUG] detectRainbowRing:: center=($centerX,$centerY) r=$radius green=${decimalFormat.format(
                    greenFraction,
                )} cyan=${decimalFormat.format(
                    cyanFraction,
                )} pink=${decimalFormat.format(pinkFraction)} bright=${decimalFormat.format(brightChromaticFraction)} huesPresent=$huesPresent isRainbow=${result.isRainbow}",
            )
        }
        return result
    }

    /**
     * Locates the support face circles in the top-right of the training screen using HoughCircles. The stat-block badges only match a fraction of the supports, so the round faces are
     * detected directly. A few spurious circles may be returned, but the rainbow-ring test rejects any that are not glowing.
     *
     * @param sourceBitmap The screenshot to search.
     * @return A list of (centerX, centerY, radius) in full-screen pixels for each detected circle.
     */
    private fun findSupportFaceCircles(sourceBitmap: Bitmap): List<IntArray> {
        val regionX = displayWidth - displayWidth / 3
        val regionHeight = (displayHeight * RAINBOW_SUPPORT_REGION_HEIGHT_FRACTION).toInt()
        val roi = createSafeBitmap(sourceBitmap, regionX, 0, displayWidth / 3, regionHeight, "findSupportFaceCircles") ?: return emptyList()

        val rgbaMat = roi.toMat()
        val grayMat = Mat()
        Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.medianBlur(grayMat, grayMat, 5)

        // HoughCircles tuning for the support face circles. Radii and spacing are for the 1080-wide baseline and scaled by the actual display width.
        val houghDp = 1.2
        val minDist = 70.0
        val houghParam1 = 90.0
        val houghParam2 = 42.0
        val minRadius = 44
        val maxRadius = 72

        val scale = displayWidth.toDouble() / 1080.0
        val circles = Mat()
        Imgproc.HoughCircles(
            grayMat,
            circles,
            Imgproc.HOUGH_GRADIENT,
            houghDp,
            minDist * scale,
            houghParam1,
            houghParam2,
            (minRadius * scale).toInt(),
            (maxRadius * scale).toInt(),
        )

        val result = mutableListOf<IntArray>()
        for (i in 0 until circles.cols()) {
            val circle = circles.get(0, i) ?: continue
            result.add(intArrayOf((circle[0] + regionX).toInt(), circle[1].toInt(), circle[2].toInt()))
        }
        listOf(rgbaMat, grayMat, circles).forEach { it.release() }
        return result
    }

    /**
     * Counts the rainbow trainings on the current training screen. Each support face circle in the top-right is checked for the rainbow glow ring, which is the direct signal that a
     * maxed-friendship support is giving a friendship (rainbow) training. This replaces the old orange-relationship-bar inference, which also fired on maxed supports that are not glowing.
     *
     * @param sourceBitmap Optional source bitmap. Defaults to a fresh screenshot.
     * @return The number of support face circles showing a rainbow glow ring.
     */
    fun countRainbowTrainings(sourceBitmap: Bitmap? = null): Int {
        val bitmap = sourceBitmap ?: getSourceBitmap()
        return findSupportFaceCircles(bitmap).count { circle -> detectRainbowRing(bitmap, circle[0], circle[1], circle[2]).isRainbow }
    }

    /**
     * Debug helper that runs the rainbow-ring detector on every support face circle it can find in the top-right, logs the per-circle metrics, and saves an annotated crop (green circle
     * with the hue count for a rainbow, red otherwise). Used by the on-device rainbow detection test to verify geometry and thresholds. Not used in the bot loop.
     *
     * @param sourceBitmap Optional source bitmap. Defaults to a fresh screenshot.
     * @return A human-readable summary of the per-circle ring metrics and the derived rainbow count.
     */
    fun debugRainbowDetection(sourceBitmap: Bitmap? = null): String {
        val bitmap = sourceBitmap ?: getSourceBitmap()
        val regionX = displayWidth - displayWidth / 3
        val annotated = createSafeBitmap(bitmap, regionX, 0, displayWidth / 3, (displayHeight * RAINBOW_SUPPORT_REGION_HEIGHT_FRACTION).toInt(), "debugRainbowDetection annotate")?.toMat()

        val sb = StringBuilder("\n========== Rainbow Detection Debug ==========\n")
        var rainbowCount = 0
        for (circle in findSupportFaceCircles(bitmap)) {
            val (centerX, centerY, radius) = Triple(circle[0], circle[1], circle[2])
            val ring = detectRainbowRing(bitmap, centerX, centerY, radius)
            if (ring.isRainbow) rainbowCount++
            sb.appendLine(
                "circle=($centerX,$centerY r$radius) green=${decimalFormat.format(
                    ring.greenFraction,
                )} cyan=${decimalFormat.format(ring.cyanFraction)} pink=${decimalFormat.format(ring.pinkFraction)} huesPresent=${ring.huesPresent} -> rainbow=${ring.isRainbow}",
            )
            if (annotated != null) {
                val color = if (ring.isRainbow) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                val drawCenter = Point((centerX - regionX).toDouble(), centerY.toDouble())
                Imgproc.circle(annotated, drawCenter, radius, color, 3)
                Imgproc.putText(annotated, "${ring.huesPresent}", Point(drawCenter.x - 10, drawCenter.y + 8), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, color, 3)
            }
        }
        sb.appendLine("Derived numRainbow (support circles with a ring): $rainbowCount")
        if (annotated != null) {
            Imgcodecs.imwrite("$matchFilePath/debugRainbowDetection.png", annotated)
            annotated.release()
        }
        // Also persist the metrics as text so they can be retrieved without the in-app log (the annotated PNG shows geometry; the text shows the exact fractions).
        try {
            File("$matchFilePath/debugRainbowDetection.txt").writeText(sb.toString())
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] debugRainbowDetection:: Failed to write metrics text: ${e.message}")
        }
        MessageLog.i(TAG, sb.toString())
        return sb.toString()
    }

    /**
     * Saves a bitmap to the temp folder for offline debugging (e.g. calibrating the spirit gauge detection against a known screen or crop). No-op unless debug mode is on.
     *
     * @param bitmap The bitmap to save.
     * @param name The file name (without extension) under the temp folder.
     */
    fun saveDebugScreenshot(bitmap: Bitmap, name: String) {
        if (!debugMode) return
        // toMat() yields an RGBA Mat but imwrite writes it as BGRA, so convert first or the saved PNG has red and blue swapped.
        val rgba = bitmap.toMat()
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgcodecs.imwrite("$matchFilePath/$name.png", bgr)
        rgba.release()
        bgr.release()
    }

    /**
     * Run the three Spirit Explosion gauge template matches (fill anchor, normal burst chevron, extreme burst chevron) against a screenshot. Extracted so
     * [analyzeSpiritExplosionGauges]'s initial attempt and its retry-after-sleep attempt share one call instead of repeating all three matches verbatim.
     *
     * @param bitmap The screenshot to match against.
     * @param anchorConfidence Confidence threshold for the spirit-training fill anchor.
     * @param chevronConfidence Confidence threshold for the normal and extreme burst chevrons.
     * @return A [Triple] of (fill anchors, normal burst chevrons, extreme burst chevrons).
     */
    private fun findSpiritGaugeIcons(bitmap: Bitmap, anchorConfidence: Double, chevronConfidence: Double): Triple<ArrayList<Point>, ArrayList<Point>, ArrayList<Point>> =
        Triple(
            IconUnityCupSpiritTraining.findAll(this, sourceBitmap = bitmap, confidence = anchorConfidence),
            IconUnityCupSpiritExplosion.findAll(this, sourceBitmap = bitmap, confidence = chevronConfidence),
            IconUnityCupExtremeSpiritExplosion.findAll(this, sourceBitmap = bitmap, confidence = chevronConfidence),
        )

    /**
     * Analyzes Spirit Explosion gauges for the Unity Cup scenario.
     *
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @return A [SpiritGaugeResult] containing fill status and readiness, or null if no gauges found.
     */
    fun analyzeSpiritExplosionGauges(sourceBitmap: Bitmap? = null): SpiritGaugeResult? {
        // Take a single screenshot first to avoid buffer overflow.
        var currentBitmap = sourceBitmap ?: getSourceBitmap()

        // Detect all three templates unconditionally. The fill anchor (spirit-training icon), the normal teal burst flame, and the purple Extreme flame are independent - a support that
        // is ready to burst shows only the flame with no in-progress fill anchor, so gating burst detection on the anchors would miss it. All three are matched at a lower confidence (0.80)
        // because their grayscale match scores sit around 0.82-0.98 - at 0.9 they coin-flip and get missed (the fill anchor undercounted fillable gauges at 0.9). Kept separate so the
        // anchor can be tuned independently if lowering it surfaces facility-button false positives.
        val chevronConfidence = 0.80
        val anchorConfidence = 0.80
        var (spiritTrainingIcons, spiritExplosionIcons, extremeSpiritExplosionIcons) = findSpiritGaugeIcons(currentBitmap, anchorConfidence, chevronConfidence)

        // If nothing at all was found, try one more time after a short delay in case the icons were still animating in. Only bail when all three are empty (no gauges/bursts on screen).
        if (spiritTrainingIcons.isEmpty() && spiritExplosionIcons.isEmpty() && extremeSpiritExplosionIcons.isEmpty()) {
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }

            // Take a new screenshot for the retry.
            currentBitmap = getSourceBitmap()

            val (retryTrainingIcons, retryExplosionIcons, retryExtremeIcons) = findSpiritGaugeIcons(currentBitmap, anchorConfidence, chevronConfidence)
            spiritTrainingIcons = retryTrainingIcons
            spiritExplosionIcons = retryExplosionIcons
            extremeSpiritExplosionIcons = retryExtremeIcons
            if (spiritTrainingIcons.isEmpty() && spiritExplosionIcons.isEmpty() && extremeSpiritExplosionIcons.isEmpty()) {
                return null
            }
        }

        // Spirit gauge calibration. The gauge is a small droplet at a fixed slot to the LEFT of the training icon (offsets for the 1080-wide baseline, scaled by display width). "Fillable"
        // = a gauge is present (empty or partial), signalled by its bright-blue OUTLINE ring in HSV (0-179 hue from the crop's RGBA->RGB->HSV path) - robust vs pale sky and grayish rooms.
        val gaugeOffsetX = -199
        val gaugeOffsetY = 62
        val gaugeSlotWidth = 62
        val gaugeSlotHeight = 78
        val outlineHueLow = 100.0
        val outlineHueHigh = 120.0
        val outlineSatMin = 185.0
        val outlineValueLow = 120.0
        val outlineValueHigh = 225.0
        val fillableOutlineFraction = 0.005

        // The anchor and the burst / extreme flames all live in the upper-right support column. The same chevron shapes also badge the bottom facility buttons (level-up indicators) and
        // match every template at high confidence, so keep only points above this fraction of the screen height. Without it every training screen reads a phantom extreme burst off the
        // Guts button's chevron badge and can over-count fillable gauges.
        val supportColumnMaxYFraction = 0.55
        val maxAnchorY = displayHeight * supportColumnMaxYFraction
        val supportAnchors = spiritTrainingIcons.filter { it.y <= maxAnchorY }
        val burstIcons = spiritExplosionIcons.filter { it.y <= maxAnchorY }
        val extremeBurstIcons = extremeSpiritExplosionIcons.filter { it.y <= maxAnchorY }

        // Count how many supports have a fillable gauge. The gauge is a droplet at a fixed slot to the LEFT of its training icon
        // (see the gauge calibration locals above). A support is fillable when a gauge is present (empty or partial), detected by the
        // gauge's bright-blue outline ring - reliable across sky and grayish classroom backgrounds where a raw gray-count is not.
        var numGaugesCanFill = 0
        for ((index, iconLocation) in supportAnchors.withIndex()) {
            val gaugeStartX = relX(iconLocation.x, gaugeOffsetX)
            val gaugeStartY = relY(iconLocation.y, gaugeOffsetY)
            val gaugeWidth = relWidth(gaugeSlotWidth)
            val gaugeHeight = relHeight(gaugeSlotHeight)

            val gaugeBitmap = createSafeBitmap(currentBitmap, gaugeStartX, gaugeStartY, gaugeWidth, gaugeHeight, "analyzeSpiritExplosionGauges") ?: continue

            val gaugeMat = gaugeBitmap.toMat()

            // toMat gives an RGBA mat, so drop alpha to true RGB (not COLOR_BGR2RGB, which would swap red/blue and push the blue outline to a red hue that the outline range misses).
            val rgbMat = Mat()
            Imgproc.cvtColor(gaugeMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            val hsvMat = Mat()
            Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            saveDebugScreenshot(gaugeBitmap, "debug_spiritExplosionGauge${index + 1}")

            val outlineMask = Mat()
            Core.inRange(
                hsvMat,
                Scalar(outlineHueLow, outlineSatMin, outlineValueLow),
                Scalar(outlineHueHigh, 255.0, outlineValueHigh),
                outlineMask,
            )
            val outlinePixels = Core.countNonZero(outlineMask)
            val totalPixels = gaugeMat.rows() * gaugeMat.cols()
            val outlineFraction = if (totalPixels > 0) outlinePixels.toDouble() / totalPixels else 0.0

            val fillable = outlineFraction > fillableOutlineFraction
            if (fillable) numGaugesCanFill++

            // Per-anchor detail. A debug run (and the on-screen debug test) gets the richer MessageLog line showing WHY a gauge was or was not counted (blue-outline ratio at the gauge
            // slot). A normal run gets the same event as a plain logcat trace, so it is never logged twice.
            if (debugMode) {
                MessageLog.i(
                    TAG,
                    "[GAUGE] anchor ${index + 1} at (${iconLocation.x.toInt()}, ${iconLocation.y.toInt()}): outline=$outlinePixels/$totalPixels (${decimalFormat.format(
                        outlineFraction * 100,
                    )}%) -> ${if (fillable) "FILLABLE" else "none"}",
                )
            } else {
                Log.d(
                    TAG,
                    "[DEBUG] analyzeSpiritExplosionGauges:: Gauge at (${iconLocation.x}, ${iconLocation.y}) outline ${decimalFormat.format(
                        outlineFraction * 100,
                    )}% -> ${if (fillable) "fillable" else "none"}",
                )
            }

            outlineMask.release()
            hsvMat.release()
            rgbMat.release()
            gaugeMat.release()
        }

        // A purple Extreme chevron also matches the teal template at nearly the same location (identical shape, only the color differs), so drop any teal match that overlaps a purple one -
        // it is the same chevron and is already counted as an extreme. The purple template only matches true Extreme chevrons, so location overlap alone classifies correctly with no
        // double-counting of a single support as both a normal burst and an extreme.
        val overlapTolerance = relWidth(40)
        val normalBurstIcons = burstIcons.filterNot { teal -> extremeBurstIcons.any { extreme -> abs(teal.x - extreme.x) <= overlapTolerance && abs(teal.y - extreme.y) <= overlapTolerance } }

        return SpiritGaugeResult(numGaugesCanFill, normalBurstIcons.size, extremeBurstIcons.size)
    }

    /**
     * Reads the recreation "Event Progress" chain position by segmenting the chevron row to the right of the [LabelEventProgress] pill. Each outing is one chevron: a filled (blue) chevron
     * is a completed / current step and a hollow (gray) one is pending, so the counts give (completed, total) - e.g. a leading blue chevron before four gray ones is (1, 5). This works for
     * the chevron-only recreation dates that show no "X/Y" text and is more robust than OCR-ing the number.
     *
     * The chevrons are counted by color segmentation rather than template matching. `matchAll` blanks every hit with a thick rectangle that bleeds ~10px past the match, and the chevrons sit
     * only ~13px apart, so each hit destroys its neighbour and a tightly packed row gets undercounted (a 5-chevron row reads as 3). Masking the row for "not the near-white card background"
     * instead makes each chevron a run of columns separated by the white gaps, which counts adjacent shapes correctly. Note this only rules template matching out for COUNTING the packed
     * row - matching a single chevron to LOCATE and click it (as `ScrollList` still does with `ButtonEventProgressChevron`) is unaffected, since one hit never suppresses itself.
     *
     * @param sourceBitmap Optional pre-captured screen; a fresh capture is taken when null.
     * @return The (completed, total) outing counts, or null when the progress row could not be read.
     */
    fun countEventProgressChevrons(sourceBitmap: Bitmap? = null): Pair<Int, Int>? {
        val currentBitmap = sourceBitmap ?: getSourceBitmap()

        // Anchor on the "Event Progress" pill. The chevron row sits on the same line strictly to its right, so start past the pill's right edge to keep the pill's own text out of the mask.
        val pill = LabelEventProgress.findImageWithBitmap(this, sourceBitmap = currentBitmap)
        if (pill == null) {
            MessageLog.w(TAG, "[WARN] countEventProgressChevrons:: Could not find the Event Progress pill. Falling back to the per-run counter.")
            return null
        }
        val pillTemplate = LabelEventProgress.template.getBitmap(this)
        if (pillTemplate == null) {
            MessageLog.w(TAG, "[WARN] countEventProgressChevrons:: Could not load the Event Progress template. Falling back to the per-run counter.")
            return null
        }

        val rowStartX = (pill.x + pillTemplate.width / 2).toInt() + relWidth(12)
        val rowStartY = relY(pill.y, -48).coerceAtLeast(0)
        val rowWidth = (displayWidth - rowStartX - relWidth(16)).coerceAtLeast(1)
        val rowHeight = relHeight(96)
        val rowBitmap = createSafeBitmap(currentBitmap, rowStartX, rowStartY, rowWidth, rowHeight, "countEventProgressChevrons") ?: return null
        saveDebugScreenshot(rowBitmap, "debug_eventProgressRow")

        // A chevron stands out from the near-white card background either by being darker than it (the hollow gray chevron's outline) or by being saturated (the filled blue chevron).
        // Thresholds estimated from 1080p crops - tune here if the run count is off.
        val backgroundValueFloor = 225.0
        val blueLower = Scalar(90.0, 80.0, 80.0)
        val blueUpper = Scalar(125.0, 255.0, 255.0)
        val minColumnPixels = 3
        val minRunWidth = relWidth(24)

        val hsvMat = rowBitmap.toHsvMat()
        val chevronMask = Mat()
        val blueMask = Mat()
        val chevronColumns = Mat()
        val blueColumns = Mat()
        val sums: IntArray
        val blueSums: IntArray
        try {
            Core.inRange(hsvMat, Scalar(0.0, 0.0, 0.0), Scalar(179.0, 255.0, backgroundValueFloor), chevronMask)
            Core.inRange(hsvMat, blueLower, blueUpper, blueMask)
            // The filled blue may sit above the darkness floor, so fold it in. Done in place - the darkness mask has no other consumer.
            Core.bitwise_or(chevronMask, blueMask, chevronMask)

            // Column projection. Each chevron is one run of columns carrying mask pixels and the white gaps between them read as empty columns, so the runs count the chevrons directly.
            // This replaces template matching: matchAll blanks every hit with a thick rectangle that bleeds into the neighbouring chevron, so a tightly packed row of identical shapes
            // gets undercounted (a 5-chevron row reads as 3).
            Core.reduce(chevronMask, chevronColumns, 0, Core.REDUCE_SUM, CvType.CV_32S)
            sums = IntArray(chevronMask.cols()).also { chevronColumns.get(0, 0, it) }
            Core.reduce(blueMask, blueColumns, 0, Core.REDUCE_SUM, CvType.CV_32S)
            blueSums = IntArray(blueMask.cols()).also { blueColumns.get(0, 0, it) }
        } finally {
            listOf(hsvMat, chevronMask, blueMask, chevronColumns, blueColumns).forEach { it.release() }
        }

        val runs = segmentRuns(sums, minColumnPixels, minRunWidth)
        if (runs.isEmpty()) {
            MessageLog.w(
                TAG,
                "[WARN] countEventProgressChevrons:: Found the Event Progress pill at (${pill.x.toInt()}, ${pill.y.toInt()}) but segmented no chevrons in row " +
                    "[$rowStartX, $rowStartY, $rowWidth, $rowHeight]. Falling back to the per-run counter. Enable debugMode to dump the debug_eventProgressRow crop.",
            )
            return null
        }

        // A chevron is done when its run is mostly the filled blue; the hollow gray one carries almost no blue. Both sums are 255-per-pixel, so the scale cancels in the ratio.
        val blueRunFractionMin = 0.5
        val blueFlags = runs.map { run -> run.sumOf { blueSums[it] }.toDouble() / run.sumOf { sums[it] } >= blueRunFractionMin }

        // Completed = the position of the rightmost filled chevron. Using the position (not the raw blue count) is robust whether the game keeps completed steps highlighted or advances a
        // single marker. Total = every chevron in the row. The caller owns the user-facing progress log; this is the vision-level detail for calibrating the row.
        val completed = blueFlags.indexOfLast { it } + 1
        val total = runs.size
        MessageLog.d(
            TAG,
            "[DEBUG] countEventProgressChevrons:: chevron runs ${runs.map { "${it.first}-${it.last}" }}, filled $blueFlags -> $completed/$total.",
        )
        return completed to total
    }

    /**
     * Reads a single stat value from the Main screen or Aptitude dialog.
     *
     * @param statName The name of the stat to read.
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param skillPointsLocation Optional point location of the skill points icon. Defaults to null.
     * @param isAptitudeDialog Whether reading from the Aptitude dialog instead of the Main screen.
     * @return The integer value of the stat, or -1 if detection fails.
     */
    fun determineSingleStatValue(statName: StatName, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null, isAptitudeDialog: Boolean = false): Int {
        val (finalLocation, finalSourceBitmap) =
            if (sourceBitmap == null && skillPointsLocation == null) {
                if (isAptitudeDialog) {
                    LabelStatTrackSurface.find(this)
                } else {
                    LabelStatTableHeaderSkillPoints.find(this)
                }
            } else {
                Pair(skillPointsLocation, sourceBitmap)
            }

        if (finalLocation == null || finalSourceBitmap == null) {
            MessageLog.e(TAG, "[ERROR] determineSingleStatValue:: Could not start the process of detecting stat value for $statName.")
            return -1
        }

        // Each stat is evenly spaced at 170 pixel intervals starting at offset -862 for the main screen.
        // For the aptitude dialog, each stat is spaced at 200 pixel intervals starting at offset 10 from LabelStatTrackSurface top-left.
        val index = statName.ordinal
        val offsetX: Int
        val offsetY: Int
        val width: Int
        val height: Int

        if (isAptitudeDialog) {
            // Get the template bitmap to find its top-left corner from the center point.
            val templateBitmap = LabelStatTrackSurface.template.getBitmap(this)
            if (templateBitmap == null) {
                MessageLog.e(TAG, "[ERROR] determineSingleStatValue:: Could not get template bitmap for LabelStatTrackSurface.")
                return -1
            }

            val halfW = templateBitmap.width / 2
            val halfH = templateBitmap.height / 2

            offsetX = -halfW + 10 + (index * 200)
            offsetY = -halfH - 110
            width = 105
            height = 40
        } else {
            offsetX = -860 + (index * 170)
            offsetY = 20
            width = 100
            height = 50
        }

        // Perform OCR with no thresholding (stats are on solid background).
        val text =
            performOCROnRegion(
                finalSourceBitmap,
                relX(finalLocation.x, offsetX),
                relY(finalLocation.y, offsetY),
                relWidth(width),
                relHeight(height),
                useThreshold = false,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "tesseract_digits",
                debugName = "${statName}StatValue",
            )

        // Parse the text.
        Log.d(TAG, "[DEBUG] determineSingleStatValue:: Detected number of stats for $statName from Tesseract before formatting: $text")
        if (text.lowercase().contains("max") || text.lowercase().contains("ax")) {
            Log.d(TAG, "[DEBUG] determineSingleStatValue:: $statName seems to be maxed out. Setting it to $manualStatCap.")
            val cleanedText = text.replace(Regex("[^0-9]"), "")
            return try {
                val parsed = cleanedText.toInt()
                if (manualStatCap > 0) parsed.coerceIn(0, manualStatCap) else parsed.coerceAtLeast(0)
            } catch (_: NumberFormatException) {
                if (manualStatCap > 0) manualStatCap else 1200
            }
        } else {
            try {
                Log.d(TAG, "[DEBUG] determineSingleStatValue:: Converting $text to integer for $statName stat value")
                val cleanedText = text.replace(Regex("[^0-9]"), "")
                val parsed = cleanedText.toInt()
                if (manualStatCap > 0 && parsed > manualStatCap) {
                    Log.d(TAG, "[DEBUG] determineSingleStatValue:: Parsed value $parsed for $statName exceeds stat cap $manualStatCap, likely an OCR misread. Rejecting.")
                    return -1
                }
                return parsed.coerceAtLeast(0)
            } catch (_: NumberFormatException) {
                return -1
            }
        }
    }

    /**
     * Reads all five stat values from the Main screen or Aptitude dialog.
     *
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param skillPointsLocation Optional point location of the skill points icon. Defaults to null.
     * @param isAptitudeDialog Whether reading from the Aptitude dialog instead of the Main screen.
     * @return A map of stat names to their detected integer values.
     */
    fun determineStatValues(sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null, isAptitudeDialog: Boolean = false): Map<StatName, Int> {
        val (finalLocation, finalSourceBitmap) =
            if (sourceBitmap == null && skillPointsLocation == null) {
                if (isAptitudeDialog) {
                    LabelStatTrackSurface.find(this)
                } else {
                    LabelStatTableHeaderSkillPoints.find(this)
                }
            } else {
                Pair(skillPointsLocation, sourceBitmap)
            }

        val result: MutableMap<StatName, Int> = mutableMapOf()

        if (finalLocation != null && finalSourceBitmap != null) {
            // Process all stats at once using the mapping.
            StatName.entries.forEachIndexed { index, statName ->
                // Each stat is evenly spaced.
                val offsetX: Int
                val offsetY: Int
                val width: Int
                val height: Int

                if (isAptitudeDialog) {
                    // Get the template bitmap to find its top-left corner from the center point.
                    val templateBitmap = LabelStatTrackSurface.template.getBitmap(this)
                    if (templateBitmap == null) {
                        MessageLog.e(TAG, "[ERROR] determineStatValues:: Could not get template bitmap for LabelStatTrackSurface.")
                        return@forEachIndexed
                    }

                    val halfW = templateBitmap.width / 2
                    val halfH = templateBitmap.height / 2

                    offsetX = -halfW + 10 + (index * 200)
                    offsetY = -halfH - 110
                    width = 105
                    height = 40
                } else {
                    // Minor adjustments for OCR accuracy.
                    offsetX = -862 + (index * 170)
                    offsetY = 20
                    width = 98
                    height = 50
                }

                // Perform OCR with no thresholding (stats are on solid background).
                val text =
                    performOCROnRegion(
                        finalSourceBitmap,
                        relX(finalLocation.x, offsetX),
                        relY(finalLocation.y, offsetY),
                        relWidth(width),
                        relHeight(height),
                        useThreshold = false,
                        useGrayscale = true,
                        scale = 2.0,
                        ocrEngine = "tesseract_digits",
                        debugName = "${statName}StatValue",
                    )

                // Parse the text.
                MessageLog.d(TAG, "[DEBUG] determineStatValues:: Raw OCR text for $statName: '$text' (length: ${text.length})")

                if (text.lowercase().contains("max") || text.lowercase().contains("ax")) {
                    Log.d(TAG, "[DEBUG] determineStatValues:: $statName seems to be maxed out. Setting it to $manualStatCap.")
                    result[statName] = if (manualStatCap > 0) manualStatCap else 1200
                } else {
                    try {
                        // Extract all numbers from the text
                        val numbers = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()

                        if (numbers.isEmpty()) {
                            MessageLog.w(TAG, "[WARN] determineStatValues:: No numbers found in '$text' for $statName")
                            result[statName] = -1
                        } else if (isAptitudeDialog) {
                            // The value crop covers only the value line (the cap below is read separately), but OCR can split the digits (e.g. "1279" -> "1 279"), so rebuild the value
                            // by concatenating every digit in reading order rather than treating the pieces as separate numbers. The 2500 ceiling drops a clearly-bogus read.
                            val value = text.replace(Regex("[^0-9]"), "").toIntOrNull() ?: -1
                            result[statName] = if (value in 1..2500) value else -1
                        } else {
                            // The Main screen shows only the value, so anything over the cap is an OCR misread.
                            val cap = if (manualStatCap > 0) manualStatCap else 1200
                            val validNumbers = numbers.filter { it in 0..cap }
                            if (validNumbers.isNotEmpty()) {
                                result[statName] = validNumbers.max()
                            } else {
                                Log.d(TAG, "[DEBUG] determineStatValues:: All parsed numbers $numbers for $statName exceed stat cap $cap, likely an OCR misread. Rejecting.")
                                result[statName] = -1
                            }
                        }
                    } catch (e: Exception) {
                        MessageLog.e(TAG, "[ERROR] determineStatValues:: Failed to parse '$text' for $statName: ${e.message}")
                        result[statName] = -1
                    }
                }
            }
        } else {
            MessageLog.e(TAG, "[ERROR] determineStatValues:: Could not start the process of detecting stat values.")
        }

        return result.toMap()
    }

    /**
     * Read the per-stat cap denominators ("/NNNN") from the Umamusume Details dialog, one line below each stat value. Anchored on the same LabelStatTrackSurface as the dialog value
     * read, so it only works while that dialog is open. Each cap is parsed via `parseStatCap`, with implausible reads rejected. Used at end of run to stamp the trainee's true final
     * caps for the log and dashboard, independent of the live main-screen cap reads.
     *
     * @return A map of stat names to their detected cap, omitting any stat whose cap could not be read plausibly.
     */
    fun determineStatCapsFromDialog(): Map<StatName, Int> {
        val (finalLocation, finalSourceBitmap) = LabelStatTrackSurface.find(this)
        val result: MutableMap<StatName, Int> = mutableMapOf()

        if (finalLocation == null) {
            MessageLog.e(TAG, "[ERROR] determineStatCapsFromDialog:: Could not anchor the cap reads on LabelStatTrackSurface.")
            return result.toMap()
        }

        val templateBitmap = LabelStatTrackSurface.template.getBitmap(this)
        if (templateBitmap == null) {
            MessageLog.e(TAG, "[ERROR] determineStatCapsFromDialog:: Could not get template bitmap for LabelStatTrackSurface.")
            return result.toMap()
        }

        val halfW = templateBitmap.width / 2
        val halfH = templateBitmap.height / 2

        StatName.entries.forEachIndexed { index, statName ->
            // The cap ("/NNNN") sits one line below the value in the same column: same offsetX as the value read, 44px lower (measured on-device against the saved cap crop).
            val text =
                performOCROnRegion(
                    finalSourceBitmap,
                    relX(finalLocation.x, -halfW + 10 + (index * 200)),
                    relY(finalLocation.y, -halfH - 66),
                    relWidth(105),
                    relHeight(26),
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "tesseract_digits",
                    debugName = "${statName}StatCapDialog",
                )

            MessageLog.d(TAG, "[DEBUG] determineStatCapsFromDialog:: Raw OCR text for $statName cap: '$text'")

            val cap = parseStatCap(text)
            if (cap != null) {
                result[statName] = cap
            } else {
                MessageLog.w(TAG, "[WARN] determineStatCapsFromDialog:: Implausible cap '$text' for $statName. Skipping.")
            }
        }

        return result.toMap()
    }

    /**
     * Read the cap denominator ("/NNNN") shown under a single stat on the career / training main screen. Anchored on the same skill-points label as the value read, offset one line
     * down. Caps appear only on the main / training screen (not the aptitude dialog), so this reads the main-screen layout. Returns the parsed cap, or -1 when no plausible cap is
     * found so the caller keeps the per-scenario table cap. NOTE: the cap-region vertical offset is a starting point tuned on-device against the saved `${statName}StatCap` crop.
     *
     * @param statName The stat whose cap to read.
     * @param sourceBitmap Optional shared source bitmap (enables thread-safe parallel reads).
     * @param skillPointsLocation Optional pre-found skill-points label location.
     * @return The parsed stat cap, or -1 if none is plausible.
     */
    fun determineSingleStatCap(statName: StatName, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): Int {
        val (finalLocation, finalSourceBitmap) =
            if (sourceBitmap == null && skillPointsLocation == null) {
                LabelStatTableHeaderSkillPoints.find(this)
            } else {
                Pair(skillPointsLocation, sourceBitmap)
            }

        if (finalLocation == null || finalSourceBitmap == null) {
            MessageLog.e(TAG, "[ERROR] determineSingleStatCap:: Could not start the process of detecting the stat cap for $statName.")
            return -1
        }

        // The cap sits one line below the stat value (value region is offsetY 20, height 50). Same 170px horizontal spacing; the vertical offset is tuned on-device via the crop.
        val index = statName.ordinal
        val offsetX = -862 + (index * 170)
        val offsetY = 64
        val width = 110
        val height = 44

        val text =
            performOCROnRegion(
                finalSourceBitmap,
                relX(finalLocation.x, offsetX),
                relY(finalLocation.y, offsetY),
                relWidth(width),
                relHeight(height),
                useThreshold = false,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "tesseract_digits",
                debugName = "${statName}StatCap",
            )

        Log.d(TAG, "[DEBUG] determineSingleStatCap:: Raw OCR text for $statName cap: '$text'")
        return parseStatCap(text) ?: -1
    }

    /**
     * Determines the stat gain values from a training session.
     *
     * Uses template matching to identify individual digits and the "+" sign in the stat gain area. Supports multi-row detection for specialized scenarios.
     *
     * @param trainingName The name of the training being analyzed.
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param skillPointsLocation Optional point location of the skill points icon. Defaults to null.
     * @return A StatGainResult containing detected gains per stat.
     */
    fun determineStatGainFromTraining(trainingName: StatName, sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): StatGainResult {
        // Scenario-specific checks.
        val useTwoRows = game.scenario != "URA Finale"

        // Determine all template suffixes needed for this scenario.
        val templateSuffixes =
            if (useTwoRows) {
                listOf("_mini", "_mini_bold")
            } else {
                listOf("")
            }
        val baseTemplates = listOf("+", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
        // Define a mapping of training types to their stat indices
        val trainingStatMap =
            mapOf(
                StatName.SPEED to listOf(StatName.SPEED, StatName.POWER),
                StatName.STAMINA to listOf(StatName.STAMINA, StatName.GUTS),
                StatName.POWER to listOf(StatName.STAMINA, StatName.POWER),
                StatName.GUTS to listOf(StatName.SPEED, StatName.POWER, StatName.GUTS),
                StatName.WIT to listOf(StatName.SPEED, StatName.WIT),
            )

        val (skillPointsLocation, sourceBitmap) =
            if (sourceBitmap == null && skillPointsLocation == null) {
                LabelStatTableHeaderSkillPoints.find(this)
            } else {
                Pair(skillPointsLocation, sourceBitmap)
            }

        val threadSafeResults = ConcurrentHashMap<StatName, Int>()
        // Initialize all stat keys with default value 0 to ensure map completeness even if threads return early.
        StatName.entries.forEach { statName ->
            threadSafeResults[statName] = 0
        }
        // Store row values to log them sequentially after threads complete.
        val rowValuesMap = Collections.synchronizedMap(mutableMapOf<StatName, List<Int>>())

        if (skillPointsLocation != null) {
            // Preload all template bitmaps for all suffixes to avoid thread contention.
            val templateBitmaps = mutableMapOf<String, Bitmap?>()
            for (suffix in templateSuffixes) {
                for (baseTemplate in baseTemplates) {
                    val templateName = baseTemplate + suffix
                    context.assets?.open("images/$templateName.png").use { inputStream ->
                        templateBitmaps[templateName] = BitmapFactory.decodeStream(inputStream)
                    }
                }
            }

            // Process all stats in parallel using threads.
            // Fetch the shared YOLO detector once so the per-stat threads do not race the lazy-init lock.
            val yolo = if (useYolo) getYoloDetector(context) else null
            val statLatch = CountDownLatch(5)
            for (statName in StatName.entries) {
                Thread {
                    try {
                        // Stop the Thread early if the selected Training would not offer stats for the stat to be checked.
                        // Speed gives Speed and Power
                        // Stamina gives Stamina and Guts
                        // Power gives Stamina and Power
                        // Guts gives Speed, Power and Guts
                        // Wits gives Speed and Wits
                        val validStatNames: List<StatName> = trainingStatMap[trainingName] ?: return@Thread
                        if (statName !in validStatNames) {
                            return@Thread
                        }

                        // Check if bot is still running before starting work.
                        if (!BotService.isRunning) {
                            return@Thread
                        }

                        // All stats are evenly spaced at 180 pixel intervals.
                        val xOffset = statName.ordinal * 180

                        // Determine crop regions based on campaign.
                        val firstRowStartX = relX(skillPointsLocation.x, -934 + xOffset)
                        val firstRowStartY =
                            if (useTwoRows) {
                                relY(skillPointsLocation.y, -65)
                            } else {
                                relY(skillPointsLocation.y, -103)
                            }

                        // Build the row configurations based on the current scenario.
                        val rows =
                            if (useTwoRows) {
                                // For some scenarios, stats are in two rows on top of each other.
                                // First row uses "_mini" suffix, second row uses "_mini_bold" suffix.
                                val row2Offset = if (game.scenario == "Trackblazer") -60 else -55
                                val secondRowStartY = relY(firstRowStartY.toDouble(), row2Offset)
                                listOf(
                                    StatGainRowConfig(firstRowStartX, firstRowStartY, relWidth(150), relHeight(55), "row 1", "_mini"),
                                    StatGainRowConfig(firstRowStartX, secondRowStartY, relWidth(150), relHeight(55), "row 2", "_mini_bold"),
                                )
                            } else {
                                // Default: single row.
                                listOf(
                                    StatGainRowConfig(firstRowStartX, firstRowStartY, relWidth(150), relHeight(82), "", ""),
                                )
                            }

                        // Track all Mat objects for cleanup.
                        val matObjects = mutableListOf<Mat>()
                        var processingFailed = false

                        // Track row information and matches for debug visualization.
                        data class RowDebugInfo(val bitmap: Bitmap, val config: StatGainRowConfig, val matches: MutableMap<String, MutableList<Point>>)

                        val rowDebugInfo = mutableListOf<RowDebugInfo>()

                        try {
                            // Process each row.
                            for (row in rows) {
                                if (!BotService.isRunning) {
                                    processingFailed = true
                                    break
                                }

                                // Create bitmap for this row.
                                val rowBitmap = createSafeBitmap(sourceBitmap!!, row.startX, row.startY, row.width, row.height, "determineStatGainFromTraining $statName ${row.rowName}".trim())
                                if (rowBitmap == null) {
                                    Log.e(TAG, "[ERROR] determineStatGainFromTraining:: Failed to create cropped bitmap for $statName stat gain detection from $trainingName training ${row.rowName}.")
                                    threadSafeResults[statName] = 0
                                    processingFailed = true
                                    return@Thread
                                }

                                // Initialize row-specific matches for debug visualization.
                                // Use templates with the row's specific suffix.
                                val rowTemplates = baseTemplates.map { it + row.templateSuffix }
                                val rowMatches = mutableMapOf<String, MutableList<Point>>()
                                rowTemplates.forEach { template ->
                                    rowMatches[template] = mutableListOf()
                                }

                                // Check again before expensive operations.
                                if (!BotService.isRunning) {
                                    processingFailed = true
                                    return@Thread
                                }

                                // Convert to Mat and then turn it to grayscale.
                                val rowMat = rowBitmap.toMat()
                                matObjects.add(rowMat)

                                val rowGray = Mat()
                                Imgproc.cvtColor(rowMat, rowGray, Imgproc.COLOR_BGR2GRAY)
                                matObjects.add(rowGray)

                                val rowWorking = Mat()
                                rowGray.copyTo(rowWorking)
                                matObjects.add(rowWorking)

                                // Check again before starting template processing loop.
                                if (!BotService.isRunning) {
                                    threadSafeResults[statName] = 0
                                    processingFailed = true
                                    return@Thread
                                }

                                val effectType = if (statName == trainingName) "main-effect" else "side-effect"
                                val trainingContext = "${trainingName.name} training for ${statName.name} $effectType"

                                // Process results based on detection method.
                                if (useYolo && yolo != null) {
                                    val detections = yolo.detect(rowBitmap)

                                    // Parse YOLO detections into rowMatches for compatibility with constructIntegerFromMatches and debug visualization.
                                    val sortedDetections = detections.sortedBy { it.x }
                                    var resultString = ""
                                    for (detection in sortedDetections) {
                                        val label = detection.label
                                        resultString += label

                                        // Fake a template matching result by populating rowMatches.
                                        // The constructIntegerFromMatches logic expects a template name including suffix.
                                        val templateName = label + row.templateSuffix
                                        if (!rowMatches.containsKey(templateName)) {
                                            rowMatches[templateName] = mutableListOf()
                                        }
                                        // We use the coordinates from YOLO (scaled back to rowBitmap space or just the raw detection coords).
                                        rowMatches[templateName]?.add(Point(detection.x.toDouble(), detection.y.toDouble()))
                                    }

                                    if (resultString.isNotEmpty()) {
                                        Log.i(TAG, "[YOLO] Detections for $statName ${row.rowName}: $resultString")
                                    }
                                } else {
                                    // Process templates for this row using the row's specific suffix.
                                    for (templateName in rowTemplates) {
                                        // Check before each template processing operation.
                                        if (!BotService.isRunning) {
                                            processingFailed = true
                                            break
                                        }
                                        val templateBitmap = templateBitmaps[templateName]
                                        if (templateBitmap != null) {
                                            val processedMatches =
                                                processStatGainTemplateWithTransparency(
                                                    templateName,
                                                    templateBitmap,
                                                    rowWorking,
                                                    mutableMapOf<String, MutableList<Point>>().apply {
                                                        rowTemplates.forEach { t -> this[t] = mutableListOf() }
                                                    },
                                                    row.rowName,
                                                    trainingContext,
                                                )
                                            // Store original matches for this row (for debug visualization).
                                            processedMatches[templateName]?.forEach { point ->
                                                rowMatches[templateName]?.add(point)
                                            }
                                        } else {
                                            Log.e(TAG, "[ERROR] determineStatGainFromTraining:: Could not load template \"$templateName\" to process stat gains for $trainingName training.")
                                        }
                                    }
                                }

                                // Store row bitmap, config, and matches for debug visualization.
                                rowDebugInfo.add(RowDebugInfo(rowBitmap, row, rowMatches))
                            }
                        } finally {
                            // Clean up all Mat objects.
                            matObjects.forEach { it.release() }
                        }

                        if (processingFailed) {
                            return@Thread
                        }

                        // Analyze results and construct the final integer value for this region.
                        val finalValue =
                            if (rows.size > 1) {
                                // For scenarios with multiple rows, sum the values from each row.
                                val rowValues =
                                    rowDebugInfo.mapIndexed { index, rowInfo ->
                                        constructIntegerFromMatches(rowInfo.matches, "for ${rowInfo.config.rowName}")
                                    }
                                // Store row values for sequential logging after threads complete.
                                rowValuesMap[statName] = rowValues
                                rowValues.sum()
                            } else {
                                // For single row scenarios, use the existing behavior.
                                constructIntegerFromMatches(rowDebugInfo[0].matches, "for stat $statName")
                            }
                        threadSafeResults[statName] = finalValue

                        // Draw final visualization with all matches for this region.
                        if (debugMode) {
                            // Save separate debug images for each row.
                            for ((rowIndex, rowInfo) in rowDebugInfo.withIndex()) {
                                val resultMat = Mat()
                                Utils.bitmapToMat(rowInfo.bitmap, resultMat)

                                // Draw matches for this row using the stored row-specific matches.
                                // Use the row's template suffix to get the correct templates.
                                val rowTemplates = baseTemplates.map { it + rowInfo.config.templateSuffix }
                                rowTemplates.forEach { templateName ->
                                    rowInfo.matches[templateName]?.forEach { point ->
                                        val templateBitmap = templateBitmaps[templateName]
                                        if (templateBitmap != null) {
                                            val templateWidth = templateBitmap.width
                                            val templateHeight = templateBitmap.height

                                            // Calculate the bounding box coordinates.
                                            val x1 = (point.x - templateWidth / 2).toInt()
                                            val y1 = (point.y - templateHeight / 2).toInt()
                                            val x2 = (point.x + templateWidth / 2).toInt()
                                            val y2 = (point.y + templateHeight / 2).toInt()

                                            // Draw the bounding box.
                                            Imgproc.rectangle(resultMat, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), Scalar(0.0, 0.0, 0.0), 2)

                                            // Add text label.
                                            Imgproc.putText(resultMat, templateName, point, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 0.0, 0.0), 1)
                                        }
                                    }
                                }

                                // Generate filename with row identifier if multiple rows exist.
                                val rowSuffix =
                                    if (rows.size > 1) {
                                        if (rowInfo.config.rowName.isNotEmpty()) {
                                            "_${rowInfo.config.rowName}"
                                        } else {
                                            "_row${rowIndex + 1}"
                                        }
                                    } else {
                                        ""
                                    }
                                Imgcodecs.imwrite("$matchFilePath/debug_${trainingName}TrainingStatGain_${statName}$rowSuffix.png", resultMat)
                                resultMat.release()
                            }
                        }
                    } catch (_: InterruptedException) {
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERROR] determineStatGainFromTraining:: Error processing stat $statName for $trainingName training: ${e.stackTraceToString()}")
                        threadSafeResults[statName] = 0
                    } finally {
                        // Always clean up resources, even if interrupted.
                        statLatch.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }

            // Wait for all threads to complete.
            try {
                statLatch.await(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                MessageLog.e(TAG, "[ERROR] determineStatGainFromTraining:: Stat processing timed out for $trainingName training.")
            }

            // Check if bot is still running.
            if (!BotService.isRunning) {
                return StatGainResult(threadSafeResults.toMap(), rowValuesMap.toMap())
            }

            // Return results with row values map for logging in Training.kt after threads complete.
            return StatGainResult(threadSafeResults.toMap(), rowValuesMap.toMap())
        } else {
            MessageLog.e(TAG, "[ERROR] determineStatGainFromTraining:: Could not find the skill points location to start determining stat gains for $trainingName training.")
        }

        return StatGainResult(threadSafeResults.toMap(), emptyMap())
    }

    /**
     * Processes a single template with transparency to find all valid matches in the working matrix through a multi-stage algorithm.
     *
     * The algorithm uses two validation criteria:
     * - Pixel match ratio: Ensures sufficient pixel-level similarity.
     * - Correlation coefficient: Validates statistical correlation between template and matched region.
     *
     * @param templateName Name of the template being processed (used for logging and debugging).
     * @param templateBitmap Bitmap of the template image (must have 4-channel RGBA format with transparency).
     * @param workingMat Working matrix to search in (grayscale source image).
     * @param matchResults Map to store match results, organized by template name.
     * @param rowName The name of the row being processed (e.g., "row 1", "row 2").
     * @param trainingContext The training context (e.g., "SPEED training for POWER side effect").
     * @return The modified matchResults mapping containing all valid matches found for this template
     */
    private fun processStatGainTemplateWithTransparency(
        templateName: String,
        templateBitmap: Bitmap,
        workingMat: Mat,
        matchResults: MutableMap<String, MutableList<Point>>,
        rowName: String = "",
        trainingContext: String = "",
    ): MutableMap<String, MutableList<Point>> {
        // These values have been tested for the best results against the dynamic background.
        val matchConfidence = 0.9
        val minPixelMatchRatio = 0.1
        val minPixelCorrelation = 0.85

        // Convert template to Mat and then to grayscale.
        val templateMat = Mat()
        val templateGray = Mat()
        Utils.bitmapToMat(templateBitmap, templateMat)
        Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_BGR2GRAY)

        // Check if template has an alpha channel (transparency).
        if (templateMat.channels() != 4) {
            Log.e(TAG, "[ERROR] processStatGainTemplateWithTransparency:: Template \"$templateName\" is not transparent and is a requirement.")
            templateMat.release()
            templateGray.release()
            return matchResults
        }

        // Extract alpha channel for the alpha mask.
        val alphaChannels = ArrayList<Mat>()
        Core.split(templateMat, alphaChannels)
        val alphaMask = alphaChannels[3] // Alpha channel is the 4th channel.

        // Create binary mask for non-transparent pixels.
        val validPixels = Mat()
        Core.compare(alphaMask, Scalar(0.0), validPixels, Core.CMP_GT)

        // Check transparency ratio.
        val nonZeroPixels = Core.countNonZero(alphaMask)
        val totalPixels = alphaMask.rows() * alphaMask.cols()
        val transparencyRatio = nonZeroPixels.toDouble() / totalPixels
        if (transparencyRatio < 0.1) {
            Log.w(TAG, "[WARN] processStatGainTemplateWithTransparency:: Template \"$templateName\" appears to be mostly transparent!")
            alphaChannels.forEach { it.release() }
            validPixels.release()
            alphaMask.release()
            templateMat.release()
            templateGray.release()
            return matchResults
        }

        // //////////////////////////////////////////////////////////////////
        // //////////////////////////////////////////////////////////////////

        var continueSearching = true
        var searchMat = Mat()
        var xOffset = 0
        workingMat.copyTo(searchMat)

        try {
            while (continueSearching) {
                var failedPixelMatchRatio = false
                var failedPixelCorrelation = false

                // Template match with the alpha mask.
                val result = Mat()
                Imgproc.matchTemplate(searchMat, templateGray, result, Imgproc.TM_CCORR_NORMED, alphaMask)
                val mmr = Core.minMaxLoc(result)
                val matchVal = mmr.maxVal
                val matchLocation = mmr.maxLoc

                if (matchVal >= matchConfidence) {
                    val x = matchLocation.x.toInt()
                    val y = matchLocation.y.toInt()
                    val h = templateGray.rows()
                    val w = templateGray.cols()

                    // Validate that the match location is within bounds.
                    if (x >= 0 && y >= 0 && x + w <= searchMat.cols() && y + h <= searchMat.rows()) {
                        // Extract the matched region from the source image.
                        val matchedRegion = Mat(searchMat, Rect(x, y, w, h))

                        // Create masked versions of the template and matched region using only non-transparent pixels.
                        val templateValid = Mat()
                        val regionValid = Mat()
                        templateGray.copyTo(templateValid, validPixels)
                        matchedRegion.copyTo(regionValid, validPixels)

                        // For the first test, compare pixel-by-pixel equality between the matched region and template to calculate match ratio.
                        val templateComparison = Mat()
                        Core.compare(matchedRegion, templateGray, templateComparison, Core.CMP_EQ)
                        val matchingPixels = Core.countNonZero(templateComparison)
                        val pixelMatchRatio = matchingPixels.toDouble() / (w * h)
                        if (pixelMatchRatio < minPixelMatchRatio) {
                            failedPixelMatchRatio = true
                        }

                        // Extract pixel values into double arrays for correlation calculation.
                        val templateValidMat = Mat()
                        val regionValidMat = Mat()
                        templateValid.convertTo(templateValidMat, CvType.CV_64F)
                        regionValid.convertTo(regionValidMat, CvType.CV_64F)
                        val templateArray = DoubleArray(templateValid.total().toInt())
                        val regionArray = DoubleArray(regionValid.total().toInt())
                        templateValidMat.get(0, 0, templateArray)
                        regionValidMat.get(0, 0, regionArray)

                        // For the second test, validate the match quality by performing correlation calculation.
                        val pixelCorrelation = calculateCorrelation(templateArray, regionArray)
                        if (pixelCorrelation < minPixelCorrelation) {
                            failedPixelCorrelation = true
                        }

                        // If both tests passed, then the match is valid.
                        if (!failedPixelMatchRatio && !failedPixelCorrelation) {
                            val centerX = (x + xOffset) + (w / 2)
                            val centerY = y + (h / 2)

                            // Check for overlap with existing matches within 10 pixels on both axes.
                            val hasOverlap =
                                matchResults.values.flatten().any { existingPoint ->
                                    val existingX = existingPoint.x
                                    val existingY = existingPoint.y

                                    // Check if the new match overlaps with existing match within 10 pixels.
                                    val xOverlap = kotlin.math.abs(centerX - existingX) < 10
                                    val yOverlap = kotlin.math.abs(centerY - existingY) < 10

                                    xOverlap && yOverlap
                                }

                            if (!hasOverlap) {
                                val rowSuffix =
                                    if (trainingContext.isNotEmpty() && rowName.isNotEmpty()) {
                                        " for $trainingContext $rowName"
                                    } else if (trainingContext.isNotEmpty()) {
                                        " for $trainingContext"
                                    } else if (rowName.isNotEmpty()) {
                                        " for $rowName"
                                    } else {
                                        ""
                                    }
                                Log.d(TAG, "[DEBUG] processStatGainTemplateWithTransparency:: Found valid match for template \"$templateName\" at ($centerX, $centerY)$rowSuffix.")
                                matchResults[templateName]?.add(Point(centerX.toDouble(), centerY.toDouble()))

                                // If it found the + symbol, then there is no need to look for additional pluses.
                                if (templateName in listOf("+", "+_mini")) {
                                    continueSearching = false
                                }
                            }
                        }

                        // Draw a box to prevent re-detection in the next loop iteration.
                        Imgproc.rectangle(searchMat, Point(x.toDouble(), y.toDouble()), Point((x + w).toDouble(), (y + h).toDouble()), Scalar(0.0, 0.0, 0.0), 10)

                        templateComparison.release()
                        matchedRegion.release()
                        templateValid.release()
                        regionValid.release()
                        templateValidMat.release()
                        regionValidMat.release()

                        // Crop the Mat horizontally to exclude the supposed matched area.
                        val cropX = x + w
                        val remainingWidth = searchMat.cols() - cropX
                        when {
                            remainingWidth < templateGray.cols() -> {
                                continueSearching = false
                            }

                            else -> {
                                val newSearchMat = Mat(searchMat, Rect(cropX, 0, remainingWidth, searchMat.rows()))
                                searchMat.release()
                                searchMat = newSearchMat
                                xOffset += cropX
                            }
                        }
                    } else {
                        // Stop searching when the source has been traversed.
                        continueSearching = false
                    }
                } else {
                    // No match found above threshold, stop searching for this template.
                    continueSearching = false
                }

                result.release()

                // Safety check to prevent infinite loops.
                if ((matchResults[templateName]?.size ?: 0) > 10) {
                    continueSearching = false
                }
                if (!BotService.isRunning) {
                    throw InterruptedException()
                }
            }
        } finally {
            // Always clean up resources, even if InterruptedException is thrown.
            searchMat.release()
            alphaChannels.forEach { it.release() }
            validPixels.release()
            alphaMask.release()
            templateMat.release()
            templateGray.release()
        }

        // //////////////////////////////////////////////////////////////////
        // //////////////////////////////////////////////////////////////////

        return matchResults
    }

    /**
     * Constructs an integer value from a set of template matching results.
     *
     * Analyzes the spatial arrangement of detected digits and symbols to reconstruct the final numeric value.
     *
     * @param matchResults Map of template names to their match locations.
     * @param logLabel Optional label for logging purposes.
     * @return The constructed integer value, or 0 if no matches found.
     */
    private fun constructIntegerFromMatches(matchResults: Map<String, MutableList<Point>>, logLabel: String = ""): Int {
        // Collect all matches with their template names.
        val allMatches = mutableListOf<Pair<String, Point>>()
        matchResults.forEach { (templateName, points) ->
            points.forEach { point ->
                allMatches.add(Pair(templateName, point))
            }
        }

        val logSuffix = if (logLabel.isNotEmpty()) " $logLabel" else ""

        if (allMatches.isEmpty()) {
            Log.w(TAG, "[WARN] constructIntegerFromMatches:: No matches found to construct integer value$logSuffix.")
            return 0
        }

        // Sort matches by x-coordinate (left to right).
        allMatches.sortBy { it.second.x }
        Log.d(TAG, "[DEBUG] constructIntegerFromMatches:: Sorted matches$logSuffix: ${allMatches.map { "${it.first}@(${it.second.x}, ${it.second.y})" }}")

        // Construct the string representation by extracting the character part from template names (removing suffixes like "_mini").
        // Template names can be "+", "0"-"9" or "+_mini", "0_mini"-"9_mini", so we extract the first character.
        val constructedString = allMatches.joinToString("") { it.first[0].toString() }
        Log.d(TAG, "[DEBUG] constructIntegerFromMatches:: Constructed string$logSuffix: \"$constructedString\".")

        // Extract the numeric part and convert to integer.
        return try {
            if (constructedString == "+") {
                Log.w(TAG, "[WARN] constructIntegerFromMatches:: Constructed string$logSuffix was just the plus sign. Setting the result to 0.")
                return 0
            }

            val plusIndex = constructedString.indexOf('+')
            val numericPart =
                if (plusIndex != -1 && plusIndex < constructedString.length - 1) {
                    constructedString.substring(plusIndex + 1)
                } else {
                    constructedString
                }

            val result = numericPart.toInt()

            // Correct stat gains that exceed +100 by dropping the third digit.
            // The max stat gain per training is +100, so higher values indicate a false 3rd digit detection.
            val correctedResult =
                if (result > 100) {
                    val corrected = result / 10
                    Log.d(TAG, "[DEBUG] constructIntegerFromMatches:: Corrected stat gain$logSuffix from $result to $corrected (dropped false 3rd digit).")
                    corrected
                } else {
                    result
                }

            Log.d(TAG, "[DEBUG] constructIntegerFromMatches:: Successfully constructed integer value: $correctedResult from \"$constructedString\"$logSuffix.")
            correctedResult
        } catch (e: NumberFormatException) {
            Log.e(TAG, "[ERROR] constructIntegerFromMatches:: Could not convert \"$constructedString\" to integer for stat gain$logSuffix: ${e.stackTraceToString()}")
            0
        }
    }

    /**
     * Calculates the Pearson correlation coefficient between two arrays of pixel values.
     *
     * The Pearson correlation coefficient measures the linear correlation between two variables, ranging from -1 (perfect negative correlation) to +1 (perfect positive correlation). A value of 0
     * indicates no linear correlation.
     *
     * @param array1 First array of pixel values from the template image.
     * @param array2 Second array of pixel values from the matched region.
     * @return Correlation coefficient between -1.0 and +1.0, or 0.0 if arrays are invalid
     */
    private fun calculateCorrelation(array1: DoubleArray, array2: DoubleArray): Double {
        if (array1.size != array2.size || array1.isEmpty()) {
            return 0.0
        }

        val n = array1.size
        val sum1 = array1.sum()
        val sum2 = array2.sum()
        val sum1Sq = array1.sumOf { it * it }
        val sum2Sq = array2.sumOf { it * it }
        val pSum = array1.zip(array2).sumOf { it.first * it.second }

        // Calculate the numerator: n*Σ(xy) - Σx*Σy
        val num = pSum - (sum1 * sum2 / n)
        // Calculate the denominator: sqrt((n*Σx² - (Σx)²) * (n*Σy² - (Σy)²))
        val den = sqrt((sum1Sq - sum1 * sum1 / n) * (sum2Sq - sum2 * sum2 / n))

        // Return the correlation coefficient, handling division by zero.
        return if (den == 0.0) 0.0 else num / den
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Date Helper Functions

    /**
     * Determines the number of turns remaining before the next scenario goal.
     *
     * @return The number of turns remaining, or -1 if detection fails.
     */
    fun determineTurnsRemainingBeforeNextGoal(): Int {
        val (energyTextLocation, sourceBitmap) = LabelEnergy.find(this)

        if (energyTextLocation != null) {
            // Determine crop region based on the current scenario.
            val (offsetX, offsetY, width, height) =
                if (game.scenario == "Unity Cup") {
                    listOf(-260, -137, relWidth(100), relHeight(80))
                } else {
                    listOf(-246, -100, relWidth(140), relHeight(95))
                }

            // Perform OCR with 2x scaling.
            val detectedText =
                performOCROnRegion(
                    sourceBitmap,
                    relX(energyTextLocation.x, offsetX),
                    relY(energyTextLocation.y, offsetY),
                    width,
                    height,
                    useThreshold = true,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "tesseract_digits",
                    debugName = "DayForExtraRace",
                )

            // Parse the result.
            val result =
                try {
                    if (detectedText.lowercase().contains("ace") || detectedText.lowercase().contains("da")) {
                        // This is "Race Day", so there are 0 turns left before the mandatory race.
                        MessageLog.i(TAG, "[INFO] Detected Race Day for extra racing: $detectedText")
                        0
                    } else {
                        val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
                        MessageLog.i(TAG, "[INFO] Detected day for extra racing: $detectedText")
                        cleanedResult.toInt()
                    }
                } catch (_: NumberFormatException) {
                    MessageLog.e(TAG, "[ERROR] determineTurnsRemainingBeforeNextGoal:: Could not convert \"$detectedText\" to integer for the turns remaining.")
                    -1
                }

            return result
        }

        return -1
    }

    /**
     * Extracts the current date string from the screen.
     *
     * @param isOnMainScreen Whether detection is performed on the Main screen.
     * @return The detected date string, or an empty string if detection fails.
     */
    fun determineDayString(isOnMainScreen: Boolean = false): String {
        var result = ""

        // Skip this check if we know we're on the Main screen.
        if (!isOnMainScreen) {
            val (raceStatusLocation, sourceBitmap) = ButtonRaceListFullStats.find(this)
            if (raceStatusLocation != null) {
                // Perform OCR with thresholding (date text is on solid white background).
                MessageLog.i(TAG, "[INFO] Detecting date from the Race List screen.")
                result =
                    performOCROnRegion(
                        sourceBitmap,
                        relX(raceStatusLocation.x, -170),
                        relY(raceStatusLocation.y, 105),
                        relWidth(640),
                        relHeight(70),
                        useThreshold = true,
                        useGrayscale = true,
                        scale = 1.0,
                        ocrEngine = "mlkit",
                        debugName = "dateString",
                    )
                if (result != "") {
                    MessageLog.v(TAG, "[INFO] Detected date: $result")

                    if (debugMode) {
                        MessageLog.d(TAG, "[DEBUG] determineDayString:: Date string detected to be at \"$result\".")
                    } else {
                        Log.d(TAG, "[DEBUG] determineDayString:: Date string detected to be at \"$result\".")
                    }

                    return result
                }
            }
        }

        // Main screen detection path.
        val (energyLocation, sourceBitmap) = LabelEnergy.find(this)
        val offsetX =
            if (game.scenario == "Unity Cup") {
                -40
            } else {
                -268
            }

        if (energyLocation != null) {
            // Perform OCR with no thresholding (date text is on moving background).
            MessageLog.i(TAG, "[INFO] Detecting date from the Main screen.")
            result =
                performOCROnRegion(
                    sourceBitmap,
                    relX(energyLocation.x, offsetX),
                    relY(energyLocation.y, -180),
                    relWidth(308),
                    relHeight(35),
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 1.0,
                    ocrEngine = "mlkit",
                    debugName = "dateString",
                )
        }

        if (result != "") {
            MessageLog.v(TAG, "[INFO] Detected date: $result")

            if (debugMode) {
                MessageLog.d(TAG, "[DEBUG] determineDayString:: Date string detected to be at \"$result\".")
            } else {
                Log.d(TAG, "[DEBUG] determineDayString:: Date string detected to be at \"$result\".")
            }

            return result
        } else {
            MessageLog.e(TAG, "[ERROR] determineDayString:: Could not start the process of detecting the date string.")
        }

        return ""
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Main Screen Helper Functions

    /**
     * Determines the current number of skill points.
     *
     * @param sourceBitmap Optional source bitmap to use. Defaults to null.
     * @param skillPointsLocation Optional point location of the skill points icon. Defaults to null.
     * @return The number of skill points, or -1 if detection fails.
     */
    fun determineSkillPoints(sourceBitmap: Bitmap? = null, skillPointsLocation: Point? = null): Int {
        val (skillPointsLocation, sourceBitmap) =
            if (skillPointsLocation == null) {
                LabelStatTableHeaderSkillPoints.find(this)
            } else if (sourceBitmap == null) {
                Pair(skillPointsLocation, getSourceBitmap())
            } else {
                Pair(skillPointsLocation, sourceBitmap)
            }

        if (skillPointsLocation == null) {
            MessageLog.e(TAG, "[ERROR] determineSkillPoints:: skillPointsLocation is null.")
            return -1
        }

        // Determine crop region.
        val (offsetX, offsetY, width, height) = listOf(-70, 28, relWidth(135), relHeight(70))

        // Perform OCR with thresholding.
        val detectedText =
            performOCROnRegion(
                sourceBitmap,
                relX(skillPointsLocation.x, offsetX),
                relY(skillPointsLocation.y, offsetY),
                width,
                height,
                useThreshold = true,
                useGrayscale = true,
                scale = 1.0,
                ocrEngine = "mlkit",
                debugName = "SkillPoints",
            )

        // Parse the result.
        Log.d(TAG, "[DEBUG] determineSkillPoints:: Detected number of skill points before formatting: $detectedText")
        return try {
            Log.d(TAG, "[DEBUG] determineSkillPoints:: Converting $detectedText to integer for skill points")
            val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
            cleanedResult.toInt()
        } catch (_: NumberFormatException) {
            -1
        }
    }

    /**
     * Detects the number of fans from the Umamusume Class dialog.
     *
     * @param bitmap The source bitmap to analyze.
     * @return The number of fans if successful, or null if detection fails.
     */
    fun getUmamusumeClassDialogFanCount(bitmap: Bitmap): Int? {
        val cvImage = bitmap.toMat()
        // Convert to grayscale.
        Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugGetUmamusumeClassDialogFanCount_afterCrop.png", cvImage)

        // Convert the Mat directly to Bitmap and then pass it to the text reader.
        var resultBitmap = cvImage.toBitmap()

        // Thresh the grayscale cropped image to make it black and white.
        val bwImage = Mat()
        Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugGetUmamusumeClassDialogFanCount_afterThreshold.png", bwImage)

        resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
        Utils.matToBitmap(bwImage, resultBitmap)
        tessDigitsBaseAPI.setImage(resultBitmap)

        var result = "empty!"
        try {
            // Finally, detect text on the cropped region.
            result = tessDigitsBaseAPI.utF8Text
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] getUmamusumeClassDialogFanCount:: Cannot perform OCR with Tesseract: ${e.stackTraceToString()}")
        }

        tessDigitsBaseAPI.clear()
        cvImage.release()
        bwImage.release()

        // Format the string to be converted to an integer.
        MessageLog.d(TAG, "[DEBUG] getUmamusumeClassDialogFanCount:: Detected number of fans from Tesseract before formatting: $result")
        result =
            result
                .replace(",", "")
                .replace(".", "")
                .replace("+", "")
                .replace("-", "")
                .replace(">", "")
                .replace("<", "")
                .replace("(", "")
                .replace("人", "")
                .replace("ォ", "")
                .replace("fans", "").trim()

        try {
            Log.d(TAG, "[DEBUG] getUmamusumeClassDialogFanCount:: Converting $result to integer for fans")
            val cleanedResult = result.replace(Regex("[^0-9]"), "").toInt()
            return cleanedResult
        } catch (_: NumberFormatException) {
            return null
        }
    }

    /**
     * Calculates the filled percentage of the energy bar.
     *
     * @return The filled percentage (0-100), or null if the bar is not detected.
     */
    fun analyzeEnergyBar(): Int? {
        val templateBitmap: Bitmap = LabelEnergy.template.getBitmap(this)!!
        val (energyTextLocation, sourceBitmap) = LabelEnergy.find(this)
        if (energyTextLocation == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to find the text location of the energy bar.")
            return null
        }

        // Get top right of energyText.
        var x: Int = relX(energyTextLocation.x, templateBitmap.width / 2)
        var y: Int = relY(energyTextLocation.y, -(templateBitmap.height / 2))
        var w: Int = relWidth(550)
        var h: Int = relHeight(75)

        // Crop just the energy bar in the image.
        // This crop extends to the right beyond the energy bar a bit since the bar is able to grow.
        var croppedBitmap = createSafeBitmap(sourceBitmap, x, y, w, h, "analyzeEnergyBar:: Crop energy bar.")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to crop the bitmap of the energy bar.")
            return null
        }

        // Now find the left and right brackets of the energy bar to refine our cropped region.
        val energyBarLeftPartTemplateBitmap: Bitmap? = IconEnergyBarLeftPart.template.getBitmap(this)
        if (energyBarLeftPartTemplateBitmap == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to find the template bitmap for the left part of the energy bar.")
            return null
        }

        val leftPartLocation: Point? = IconEnergyBarLeftPart.findImageWithBitmap(this, sourceBitmap = croppedBitmap, region = intArrayOf(0, 0, 0, 0))
        if (leftPartLocation == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to find the location of the left part of the energy bar.")
            return null
        }

        // The right side of the energy bar looks very different depending on whether the max energy has been increased. Thus, we need to look for one of two bitmaps.
        var energyBarRightPartTemplateBitmap: Bitmap? = IconEnergyBarRightPart0.template.getBitmap(this)
        var rightPartLocation: Point?
        if (energyBarRightPartTemplateBitmap == null) {
            energyBarRightPartTemplateBitmap = IconEnergyBarRightPart1.template.getBitmap(this)
            if (energyBarRightPartTemplateBitmap == null) {
                MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to find the template bitmap for the right part of the energy bar.")
                return null
            }
            rightPartLocation = IconEnergyBarRightPart1.findImageWithBitmap(this, sourceBitmap = croppedBitmap, region = intArrayOf(0, 0, 0, 0))
        } else {
            rightPartLocation = IconEnergyBarRightPart0.findImageWithBitmap(this, sourceBitmap = croppedBitmap, region = intArrayOf(0, 0, 0, 0))
        }

        if (rightPartLocation == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to find the location of the right part of the energy bar.")
            return null
        }

        // Crop the energy bar further to refine the cropped region so that we can measure the length of the bar.
        // This crop is just a single pixel high line at the center of the bounding region.
        val left: Int = relX(leftPartLocation.x, energyBarLeftPartTemplateBitmap.width / 2)
        val right: Int = relX(rightPartLocation.x, -(energyBarRightPartTemplateBitmap.width / 2))
        x = left
        y = relHeight(croppedBitmap.height / 2)
        w = relWidth(right - left)
        h = 1

        croppedBitmap = createSafeBitmap(croppedBitmap, x, y, w, h, "analyzeEnergyBar:: Refine cropped energy bar.")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] analyzeEnergyBar:: Failed to refine the cropped bitmap region of the energy bar.")
            return null
        }

        // HSV color range for gray portion of energy bar.
        val grayLower = Scalar(0.0, 0.0, 116.0)
        val grayUpper = Scalar(180.0, 255.0, 118.0)
        val colorLower = Scalar(5.0, 0.0, 120.0)
        val colorUpper = Scalar(180.0, 255.0, 255.0)

        // Convert the cropped region to HSV
        val barMat = croppedBitmap.toMat()
        val hsvMat = Mat()
        Imgproc.cvtColor(barMat, hsvMat, Imgproc.COLOR_BGR2HSV)

        // Create masks for the gray and color portions of the image.
        val grayMask = Mat()
        val colorMask = Mat()
        Core.inRange(hsvMat, grayLower, grayUpper, grayMask)
        Core.inRange(hsvMat, colorLower, colorUpper, colorMask)

        // Calculate ratio of color and gray pixels.
        val grayPixels = Core.countNonZero(grayMask)
        val colorPixels = Core.countNonZero(colorMask)
        val totalPixels = grayPixels + colorPixels

        var fillPercent = 0.0
        if (totalPixels > 0) {
            fillPercent = (colorPixels.toDouble() / totalPixels.toDouble()) * 100.0
        }
        val result: Int = fillPercent.toInt().coerceIn(0, 100)

        barMat.release()
        hsvMat.release()
        grayMask.release()
        colorMask.release()

        Log.d(TAG, "[DEBUG] analyzeEnergyBar:: Results of energy bar analysis: Gray pixels=$grayPixels, Color pixels=$colorPixels, Energy=$result")
        return result
    }

    /**
     * Detects the current scenario goal text using OCR.
     *
     * @return The detected goal text, or an empty string if detection fails.
     */
    fun getGoalText(): String {
        val bbox =
            BoundingBox(
                x = relX(0.0, 365),
                y = relY(0.0, 110),
                w = relWidth(550),
                h = relHeight(40),
            )
        val sourceBitmap = getSourceBitmap()

        // Perform OCR with 2x scaling and no thresholding.
        val result =
            performOCROnRegion(
                sourceBitmap,
                bbox.x,
                bbox.y,
                bbox.w,
                bbox.h,
                useThreshold = false,
                useGrayscale = true,
                scale = 1.0,
                ocrEngine = "mlkit",
                debugName = "GoalText",
            )

        if (debugMode) {
            MessageLog.d(TAG, "[DEBUG] getGoalText:: Detected text: $result")
        } else {
            Log.d(TAG, "[DEBUG] getGoalText:: Detected text: $result")
        }

        return result
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Racing Helper Functions

    /**
     * Extracts the race name from the race selection screen using OCR.
     *
     * @param extraRaceLocation The screen location of the race entry.
     * @return The detected race name, or an empty string if detection fails.
     */
    fun extractRaceName(extraRaceLocation: Point): String {
        try {
            val detectedText =
                performOCRFromReference(
                    referencePoint = extraRaceLocation,
                    offsetX = -455,
                    offsetY = -105,
                    width = relWidth(585),
                    height = relHeight(45),
                    useThreshold = true,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "mlkit",
                    debugName = "extractRaceName",
                )

            // Ensure forward slashes are surrounded by spaces.
            val refinedResult = detectedText.replace(Regex("""\s*/\s*"""), " / ").trim()
            MessageLog.i(TAG, "[INFO] Extracted race name: \"$refinedResult\"")
            return refinedResult
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] extractRaceName:: Exception during race name extraction: ${e.message}")
            return ""
        }
    }

    /**
     * Determines the agenda header text associated with each race list button.
     *
     * @param sourceBitmap The source bitmap to analyze.
     * @param loadListButtonLocations List of screen locations for the buttons.
     * @return A map of button locations to their corresponding agenda text (e.g., "Agenda 1").
     */
    fun determineAgendaHeaderMappings(sourceBitmap: Bitmap, loadListButtonLocations: ArrayList<Point>): Map<Point, String> {
        val mappings = mutableMapOf<Point, String>()

        // Offset from button to header text (relative to 1080x2340 baseline).
        val offsetX = -830
        val offsetY = -190
        val cropWidth = relWidth(250)
        val cropHeight = relHeight(35)

        for ((index, buttonLocation) in loadListButtonLocations.withIndex()) {
            val headerX = relX(buttonLocation.x, offsetX)
            val headerY = relY(buttonLocation.y, offsetY)

            // Perform OCR on the header region.
            val detectedText =
                performOCROnRegion(
                    sourceBitmap,
                    headerX,
                    headerY,
                    cropWidth,
                    cropHeight,
                    useThreshold = true,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "mlkit",
                    debugName = "AgendaHeader${index + 1}",
                )

            // Clean up the detected text and remove any OCR noise characters like '|' or '!'.
            var cleanedText = detectedText.replace("|", "").replace("!", "").trim()

            // Handle OCR edge case: "Agenda I" should be "Agenda 1".
            if (cleanedText.equals("Agenda I", ignoreCase = true)) {
                cleanedText = "Agenda 1"
            }

            if (cleanedText.isNotEmpty()) {
                mappings[buttonLocation] = cleanedText
                if (debugMode) {
                    MessageLog.d(TAG, "[DEBUG] determineAgendaHeaderMappings:: Agenda header #${index + 1} at button ($buttonLocation): \"$cleanedText\"")
                } else {
                    Log.d(TAG, "[DEBUG] determineAgendaHeaderMappings:: Agenda header #${index + 1} at button ($buttonLocation): \"$cleanedText\"")
                }
            }
        }

        return mappings
    }

    /**
     * Determines the number of fans given by an extra race if it matches predictions.
     *
     * @param extraRaceLocation The screen location of the extra race.
     * @param sourceBitmap The source bitmap to analyze.
     * @param forceRacing Whether to skip the double star prediction check. Defaults to false.
     * @return RaceDetails containing the detected fan count and other race info.
     */
    fun determineExtraRaceFans(extraRaceLocation: Point, sourceBitmap: Bitmap, forceRacing: Boolean = false): RaceDetails {
        // Check for Rival status.
        val rivalCheck =
            if (game.scenario == "Trackblazer") {
                val rivalBitmap =
                    createSafeBitmap(
                        sourceBitmap,
                        relX(extraRaceLocation.x, -165),
                        relY(extraRaceLocation.y, -165),
                        relWidth(320),
                        relHeight(80),
                        "determineExtraRaceFans rival",
                    )
                if (rivalBitmap != null) {
                    LabelRivalRacer.check(this, sourceBitmap = rivalBitmap, region = intArrayOf(0, 0, 0, 0))
                } else {
                    false
                }
            } else {
                false
            }

        // Crop the source screenshot to show only the fan amount and the predictions.
        val croppedBitmap =
            createSafeBitmap(
                sourceBitmap,
                relX(extraRaceLocation.x, -173),
                relY(extraRaceLocation.y, -106),
                relWidth(163),
                relHeight(96),
                "determineExtraRaceFans prediction",
            )
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] determineExtraRaceFans:: Failed to create cropped bitmap for extra race prediction detection.")
            return RaceDetails(-1, false, rivalCheck)
        }

        val cvImage = croppedBitmap.toMat()
        if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRacePrediction.png", cvImage)

        // Determine if the extra race has double star prediction.
        val predictionCheck = IconRaceListPredictionDoubleStar.check(this, sourceBitmap = croppedBitmap, region = intArrayOf(0, 0, 0, 0))

        return if (forceRacing || predictionCheck) {
            if (debugMode && !forceRacing) {
                MessageLog.d(TAG, "[DEBUG] determineExtraRaceFans:: This race has double predictions. Now checking how many fans this race gives.")
            } else if (debugMode) {
                MessageLog.d(
                    TAG,
                    "[DEBUG] determineExtraRaceFans:: Check for double predictions was skipped due to the force racing flag being enabled. Now checking how many fans this race gives.",
                )
            }

            // Crop the source screenshot to show only the fans.
            var xOffset = -625
            var yOffset = -75
            if (game.scenario == "Trackblazer") {
                xOffset = -580
                yOffset = -50
            }
            val croppedBitmap2 =
                createSafeBitmap(
                    sourceBitmap,
                    relX(extraRaceLocation.x, xOffset),
                    relY(extraRaceLocation.y, yOffset),
                    relWidth(250),
                    relHeight(35),
                    "determineExtraRaceFans fans",
                )
            if (croppedBitmap2 == null) {
                MessageLog.e(TAG, "[ERROR] determineExtraRaceFans:: Failed to create cropped bitmap for extra race fans detection.")
                return RaceDetails(-1, predictionCheck, rivalCheck)
            }

            // Make the cropped screenshot grayscale.
            Utils.bitmapToMat(croppedBitmap2, cvImage)
            Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
            if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterCrop.png", cvImage)

            // Convert the Mat directly to Bitmap and then pass it to the text reader.
            var resultBitmap = cvImage.toBitmap()

            // Thresh the grayscale cropped image to make it black and white.
            val bwImage = Mat()
            Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterThreshold.png", bwImage)

            resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
            Utils.matToBitmap(bwImage, resultBitmap)
            tessDigitsBaseAPI.setImage(resultBitmap)

            var result = ""
            try {
                // Finally, detect text on the cropped region.
                result = tessDigitsBaseAPI.utF8Text
            } catch (e: Exception) {
                MessageLog.e(TAG, "[ERROR] determineExtraRaceFans:: Cannot perform OCR with Tesseract: ${e.stackTraceToString()}")
            }

            tessDigitsBaseAPI.clear()
            cvImage.release()
            bwImage.release()

            // Format the string to be converted to an integer.
            MessageLog.i(TAG, "[INFO] determineExtraRaceFans:: Detected number of fans from Tesseract before formatting: $result")
            result =
                result
                    .replace(",", "")
                    .replace(".", "")
                    .replace("+", "")
                    .replace("-", "")
                    .replace(">", "")
                    .replace("<", "")
                    .replace("(", "")
                    .replace("人", "")
                    .replace("ォ", "")
                    .replace("fans", "").trim()

            try {
                Log.d(TAG, "[DEBUG] determineExtraRaceFans:: Converting $result to integer for fans")
                val cleanedResult = result.replace(Regex("[^0-9]"), "")
                RaceDetails(cleanedResult.toInt(), predictionCheck, rivalCheck)
            } catch (_: NumberFormatException) {
                RaceDetails(-1, predictionCheck, rivalCheck)
            }
        } else {
            Log.d(TAG, "[DEBUG] determineExtraRaceFans:: This race has no double prediction.")
            RaceDetails(-1, false, rivalCheck)
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // OCR Helper Functions

    /**
     * Performs OCR on a cropped region by filtering pixels matching a specific RGB color.
     *
     * Instead of the standard grayscale + threshold preprocessing, this method isolates text by selecting only pixels within a tolerance of the target color.
     *
     * Matching pixels become black (text) and all other pixels become white (background). This is useful for reading text that has a uniform character color but may be surrounded by borders or
     * backgrounds of different colors.
     *
     * @param sourceBitmap The source image to crop from.
     * @param x The x-coordinate of the crop region.
     * @param y The y-coordinate of the crop region.
     * @param width The width of the crop region.
     * @param height The height of the crop region.
     * @param targetR The red component (0-255) of the target text color.
     * @param targetG The green component (0-255) of the target text color.
     * @param targetB The blue component (0-255) of the target text color.
     * @param tolerance The per-channel tolerance for color matching. Defaults to 15.
     * @param scale Scale factor to apply before OCR. Defaults to 2.0.
     * @param debugName Optional name for debug image saving. Defaults to "colorOCR".
     * @return The detected text string or empty string if OCR fails.
     */
    fun findTextByColor(
        sourceBitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        targetR: Int,
        targetG: Int,
        targetB: Int,
        tolerance: Int = 15,
        scale: Double = 2.0,
        debugName: String = "colorOCR",
    ): String {
        var result = ""

        // Crop the source bitmap.
        val croppedBitmap = createSafeBitmap(sourceBitmap, x, y, width, height, "findTextByColor crop")
        if (croppedBitmap == null) {
            MessageLog.e(TAG, "[ERROR] findTextByColor:: Failed to create cropped bitmap.")
            return ""
        }

        // Convert to Mat.
        val cvImage = croppedBitmap.toMat()

        // Save the cropped image for debugging.
        if (debugMode) {
            Imgcodecs.imwrite("$matchFilePath/debug_${debugName}_cropped.png", cvImage)
        }

        // Convert from RGBA to RGB for consistent color processing.
        val rgbImage = Mat()
        Imgproc.cvtColor(cvImage, rgbImage, Imgproc.COLOR_RGBA2RGB)

        // Define the lower and upper bounds for the target color with tolerance.
        val lowerBound =
            Scalar(
                maxOf(0, targetR - tolerance).toDouble(),
                maxOf(0, targetG - tolerance).toDouble(),
                maxOf(0, targetB - tolerance).toDouble(),
            )
        val upperBound =
            Scalar(
                minOf(255, targetR + tolerance).toDouble(),
                minOf(255, targetG + tolerance).toDouble(),
                minOf(255, targetB + tolerance).toDouble(),
            )

        // Create a mask where pixels matching the target color are white (255) and everything else is black (0).
        val colorMask = Mat()
        Core.inRange(rgbImage, lowerBound, upperBound, colorMask)

        // Invert the mask so text pixels become black and background becomes white.
        // This matches the expected input format for OCR engines.
        val invertedMask = Mat()
        Core.bitwise_not(colorMask, invertedMask)

        // Save the filtered image for debugging.
        if (debugMode) {
            Imgcodecs.imwrite("$matchFilePath/debug_${debugName}_colorFiltered.png", invertedMask)
        }

        // Convert the processed Mat to Bitmap and apply scaling.
        val clampedScale = max(0, scale.toInt()).toDouble().coerceAtLeast(1.0)
        val baseBitmap = invertedMask.toBitmap()
        val finalBitmap =
            if (clampedScale != 1.0) {
                baseBitmap.scale((baseBitmap.width * clampedScale).toInt(), (baseBitmap.height * clampedScale).toInt())
            } else {
                baseBitmap
            }

        // Run ML Kit text recognition.
        val inputImage: InputImage = InputImage.fromBitmap(finalBitmap, 0)
        val latch = CountDownLatch(1)
        var mlKitFailed = false

        googleTextRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                if (text.textBlocks.isNotEmpty()) {
                    // Concatenate all detected text blocks.
                    result = text.textBlocks.joinToString(" ") { it.text }
                }
                latch.countDown()
            }
            .addOnFailureListener { exception ->
                MessageLog.e(TAG, "[ERROR] findTextByColor:: ML Kit failed: ${exception.message}")
                mlKitFailed = true
                latch.countDown()
            }

        // Wait for the async operation to complete.
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            MessageLog.e(TAG, "[ERROR] findTextByColor:: ML Kit operation timed out.")
        }

        // Fallback to Tesseract if ML Kit failed.
        if (mlKitFailed || result.isEmpty()) {
            Log.d(TAG, "[DEBUG] findTextByColor:: Falling back to Tesseract.")
            tessBaseAPI.setImage(finalBitmap)
            try {
                result = tessBaseAPI.utF8Text
            } catch (e: Exception) {
                MessageLog.e(TAG, "[ERROR] findTextByColor:: Tesseract OCR failed: ${e.message}")
            }
            tessBaseAPI.stop()
            tessBaseAPI.clear()
        }

        if (debugMode) {
            Log.d(TAG, "[DEBUG] findTextByColor:: Detected text: \"$result\".")
        }

        // Clean up Mats.
        cvImage.release()
        rgbImage.release()
        colorMask.release()
        invertedMask.release()

        return result.trim()
    }

    /**
     * Performs OCR on a specific region of a bitmap with optional preprocessing.
     *
     * @param sourceBitmap The source image to analyze.
     * @param x The starting X-coordinate of the region.
     * @param y The starting Y-coordinate of the region.
     * @param width The width of the region.
     * @param height The height of the region.
     * @param useThreshold Whether to apply binary thresholding. Defaults to true.
     * @param useGrayscale Whether to convert to grayscale. Defaults to true.
     * @param scale Scaling factor to apply to the region. Defaults to 1.0.
     * @param ocrEngine The OCR engine to use ("tesseract", "mlkit", "tesseract_digits").
     * @param debugName Optional name for debug image logging.
     * @return The detected text, or an empty string if OCR fails.
     */
    fun performOCROnRegion(
        sourceBitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        useThreshold: Boolean = true,
        useGrayscale: Boolean = true,
        scale: Double = 1.0,
        ocrEngine: String = "tesseract",
        debugName: String = "",
    ): String {
        // Clamp the crop region to the bitmap bounds. relX/relY can produce negative coordinates
        // which would otherwise crash Bitmap.createBitmap with "y must be >= 0".
        val safeX = x.coerceIn(0, (sourceBitmap.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (sourceBitmap.height - 1).coerceAtLeast(0))
        val safeWidth = width.coerceAtLeast(1).coerceAtMost(sourceBitmap.width - safeX)
        val safeHeight = height.coerceAtLeast(1).coerceAtMost(sourceBitmap.height - safeY)
        if (safeX != x || safeY != y || safeWidth != width || safeHeight != height) {
            MessageLog.w(
                TAG,
                "[WARN] performOCROnRegion:: Crop region ($x, $y, ${width}x$height) clamped to ($safeX, $safeY, ${safeWidth}x$safeHeight) for bitmap ${sourceBitmap.width}x${sourceBitmap.height}${if (debugName.isNotEmpty()) " [$debugName]" else ""}.",
            )
        }

        // Perform OCR using findText() from ImageUtils. ML Kit joins the lines of a wrapped text block with newlines, so collapse every run of whitespace down to a single space.
        // Callers tokenize the result on spaces and match multi-word phrases against it, and both of those break on a stray newline.
        return findText(
            cropRegion = intArrayOf(safeX, safeY, safeWidth, safeHeight),
            grayscale = useGrayscale,
            thresh = useThreshold,
            threshold = threshold.toDouble(),
            thresholdMax = 255.0,
            scale = scale,
            sourceBitmap = sourceBitmap,
            detectDigitsOnly = ocrEngine == "tesseract_digits",
            debugName = debugName,
        ).trim().replace(WHITESPACE_REGEX, " ")
    }

    /**
     * Performs OCR on a custom region using a reference point.
     *
     * @param referencePoint The point to base the relative coordinates on.
     * @param offsetX The X-offset from the reference point.
     * @param offsetY The Y-offset from the reference point.
     * @param width The width of the region.
     * @param height The height of the region.
     * @param useThreshold Whether to apply binary thresholding. Defaults to true.
     * @param useGrayscale Whether to convert to grayscale. Defaults to true.
     * @param scale The scaling factor to apply. Defaults to 1.0.
     * @param ocrEngine The OCR engine to use. Defaults to "tesseract".
     * @param debugName Optional name for debug image logging.
     * @return The detected text, or an empty string if OCR fails.
     */
    fun performOCRFromReference(
        referencePoint: Point,
        offsetX: Int,
        offsetY: Int,
        width: Int,
        height: Int,
        useThreshold: Boolean = true,
        useGrayscale: Boolean = true,
        scale: Double = 1.0,
        ocrEngine: String = "tesseract",
        debugName: String = "",
    ): String {
        val sourceBitmap = getSourceBitmap()
        val finalX = relX(referencePoint.x, offsetX)
        val finalY = relY(referencePoint.y, offsetY)

        return performOCROnRegion(
            sourceBitmap,
            finalX,
            finalY,
            width,
            height,
            useThreshold,
            useGrayscale,
            scale,
            ocrEngine,
            debugName,
        )
    }

    /**
     * Extract the displayed training level (1-5) from the currently selected training panel.
     *
     * The label appears as "Speed Lvl 4", "Stamina Lvl 2", etc., in white characters on a solid colored
     * background at a fixed offset from the Energy label.
     *
     * @param sourceBitmap The current screenshot.
     * @param energyAnchor The Point returned by `LabelEnergy.find().first` for the same bitmap.
     * @return The parsed level (1-5), or null if OCR or parsing failed.
     */
    fun extractTrainingLevel(sourceBitmap: Bitmap, energyAnchor: Point): Int? {
        val x = relX(energyAnchor.x, -140)
        val y = relY(energyAnchor.y, 65)
        val width = relWidth(240)
        val height = relHeight(40)
        val rawText =
            findTextByColor(
                sourceBitmap = sourceBitmap,
                x = x,
                y = y,
                width = width,
                height = height,
                targetR = 255,
                targetG = 255,
                targetB = 255,
                tolerance = 30,
                scale = 2.0,
                debugName = "trainingLevel",
            )
        if (rawText.isEmpty()) return null
        val match = Regex("""Lvl\s*([1-5])""", RegexOption.IGNORE_CASE).find(rawText) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Misc Helper Functions

    /**
     * Saves a bitmap for debugging purposes.
     *
     * @param bitmap The bitmap to save. If null, a new screenshot is taken.
     * @param filename The name of the file to save (without extension).
     * @param fullRes Whether to save at full JPEG quality (100). Defaults to false.
     */
    fun saveBitmap(bitmap: Bitmap? = null, filename: String, fullRes: Boolean = false) {
        val bitmap = bitmap ?: getSourceBitmap()
        val tempImage = bitmap.toMat()
        if (fullRes) {
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 100)
            Imgcodecs.imwrite("$matchFilePath/$filename.png", tempImage, params)
            params.release()
        } else {
            Imgcodecs.imwrite("$matchFilePath/$filename.png", tempImage)
        }
        tempImage.release()
    }

    /**
     * Draws bounding boxes on a bitmap and saves it as a debug image.
     *
     * @param bitmap The source bitmap to draw on.
     * @param bboxes The list of bounding boxes to visualize.
     * @param filename The output filename.
     */
    fun saveDebugImageWithBboxes(bitmap: Bitmap, bboxes: List<BoundingBox>, filename: String) {
        val mat = bitmap.toMat()

        for (bbox in bboxes) {
            val pt1 = Point(bbox.x.toDouble(), bbox.y.toDouble())
            val pt2 = Point((bbox.x + bbox.w).toDouble(), (bbox.y + bbox.h).toDouble())
            // Draw a green rectangle.
            Imgproc.rectangle(mat, pt1, pt2, Scalar(0.0, 255.0, 0.0), 5)
        }

        Imgcodecs.imwrite("$matchFilePath/$filename.png", mat)
        mat.release()
    }

    /**
     * Saves a cropped portion of a bitmap for debugging.
     *
     * @param bitmap The bitmap to crop and save. If null, a new screenshot is taken.
     * @param filename The output filename.
     * @param bbox The region to crop.
     */
    fun saveBitmapWithBbox(bitmap: Bitmap? = null, filename: String, bbox: BoundingBox) {
        val bitmap = bitmap ?: getSourceBitmap()
        val croppedBitmap = createSafeBitmap(bitmap, bbox.x, bbox.y, bbox.w, bbox.h, "saveBitmapWithBbox(filename=$filename, bbox=$bbox)")
        saveBitmap(bitmap = croppedBitmap, filename = filename)
    }

    /**
     * Crops a bitmap to the specified bounding box.
     *
     * This is a wrapper around [ImageUtils.createSafeBitmap].
     *
     * @param sourceBitmap The bitmap to crop.
     * @param bbox The bounding box defining the crop region.
     * @param context Debugging context string for error logging.
     * @return The cropped bitmap, or null if cropping fails.
     */
    fun createSafeBitmap(sourceBitmap: Bitmap, bbox: BoundingBox, context: String): Bitmap? {
        return createSafeBitmap(sourceBitmap, bbox.x, bbox.y, bbox.w, bbox.h, context)
    }

    /**
     * Captures a screenshot of the specified region.
     *
     * This is a wrapper around [ImageUtils.getRegionBitmap].
     *
     * @param bbox The bounding box defining the screenshot region.
     * @return The captured bitmap region.
     */
    fun getRegionBitmap(bbox: BoundingBox): Bitmap {
        return getRegionBitmap(x = bbox.x, y = bbox.y, w = bbox.w, h = bbox.h)
    }

    /**
     * Compares two bitmaps using Structural Similarity Index (SSIM).
     *
     * @param bitmap1 The first bitmap for comparison.
     * @param bitmap2 The bitmap to compare against.
     * @return Similarity score between 0.0 and 1.0 with higher values indicating greater similarity.
     */
    fun compareBitmapsSSIM(bitmap1: Bitmap, bitmap2: Bitmap): Double {
        // Ensure bitmaps are same size for SSIM comparison.
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            return 0.0
        }

        val mat1 = Mat()
        val mat2 = Mat()
        Utils.bitmapToMat(bitmap1, mat1)
        Utils.bitmapToMat(bitmap2, mat2)

        val grayMat1 = Mat()
        val grayMat2 = Mat()
        Imgproc.cvtColor(mat1, grayMat1, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(mat2, grayMat2, Imgproc.COLOR_BGR2GRAY)

        // A direct SSIM function isn't readily available in the core Java/Kotlin bindings,
        // so you'd need a custom implementation or use a different metric like MSE
        // for a quick calculation. A simplified pixel difference is shown below.

        val diff = Mat()
        Core.absdiff(grayMat1, grayMat2, diff)
        val nonZeroPixels = Core.countNonZero(diff)

        val totalPixels = grayMat1.rows() * grayMat1.cols()
        val similarityScore = 1.0 - (nonZeroPixels.toDouble() / totalPixels.toDouble())

        // A score near 1.0 means very similar.

        mat1.release()
        mat2.release()
        grayMat1.release()
        grayMat2.release()
        diff.release()

        return similarityScore
    }

    /**
     * Converts Convex Hull indices to actual points.
     *
     * @param contour The contour to apply this translation to.
     * @param hullIndices The indices to convert.
     * @return The [MatOfPoint] containing the converted points.
     */
    private fun getHullFromIndices(contour: MatOfPoint, hullIndices: MatOfInt): MatOfPoint {
        val points = contour.toArray()
        val hullPoints = hullIndices.toArray().map { points[it] }
        return MatOfPoint(*hullPoints.toTypedArray())
    }

    /**
     * Finds four-vertex (quad) contours in a binary image, filtered by area and approximated via convex hull.
     *
     * @param image The single-channel image to find contours within.
     * @param minArea The smallest contour area to keep.
     * @param maxArea The largest contour area to keep.
     * @param epsilonScalar The polygon-approximation tolerance, scaled by each contour's perimeter.
     * @return The bounding rectangles of the detected quad contours.
     */
    private fun findQuadRects(image: Mat, minArea: Int, maxArea: Int, epsilonScalar: Double): List<Rect> {
        val rects = mutableListOf<Rect>()
        val contours: MutableList<MatOfPoint> = mutableListOf()
        val hierarchy = Mat()
        Imgproc.findContours(
            image,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)

            // Filter out contours with invalid areas.
            if (area < minArea || area > maxArea) {
                continue
            }

            // Use convex hull to ignore rounded corners.
            val hullPoints = MatOfInt()
            Imgproc.convexHull(cnt, hullPoints)
            // Convert hull indices back to MatOfPoint.
            val hullContour = getHullFromIndices(cnt, hullPoints)

            // Approximate shape.
            val approx = MatOfPoint2f()
            val cnt2f = MatOfPoint2f(*hullContour.toArray())
            val peri = Imgproc.arcLength(cnt2f, true)
            Imgproc.approxPolyDP(cnt2f, approx, epsilonScalar * peri, true)

            // Check for four vertices.
            if (approx.total() == 4L) {
                rects.add(Imgproc.boundingRect(cnt))
            }

            // Free memory for each mat.
            hullPoints.release()
            hullContour.release()
            approx.release()
            cnt2f.release()
        }

        contours.forEach { it.release() }
        contours.clear()
        hierarchy.release()
        return rects
    }

    /**
     * Detects rectangles with rounded corners on the screen.
     *
     * @param bitmap Optional bitmap to analyze. If null, a screenshot is used.
     * @param region Optional bounding box to limit detection area.
     * @param minArea Minimum area for detected rectangles.
     * @param maxArea Maximum area for detected rectangles.
     * @param blurSize Gaussian blur kernel size (positive odd integer). Defaults to 5.
     * @param epsilonScalar Precision factor for shape approximation. Defaults to 0.02.
     * @param cannyLowerThreshold Lower threshold for Canny edge detection.
     * @param cannyUpperThreshold Upper threshold for Canny edge detection.
     * @param bUseAdaptiveThreshold Whether to use adaptive thresholding instead of Canny.
     * @param adaptiveThresholdBlockSize Neighborhood size for adaptive thresholding.
     * @param adaptiveThresholdConstant Constant subtracted from adaptive threshold mean.
     * @return A list of BoundingBox objects for detected rectangles, sorted by Y-position.
     */
    fun detectRoundedRectangles(
        bitmap: Bitmap? = null,
        region: BoundingBox? = null,
        minArea: Int? = null,
        maxArea: Int? = null,
        blurSize: Int = 5,
        epsilonScalar: Double = 0.02,
        cannyLowerThreshold: Int = 30,
        cannyUpperThreshold: Int = 50,
        bUseAdaptiveThreshold: Boolean = false,
        adaptiveThresholdBlockSize: Int = 11,
        adaptiveThresholdConstant: Double = 2.0,
    ): List<BoundingBox> {
        val bitmap: Bitmap =
            if (region == null) {
                bitmap ?: getSourceBitmap()
            } else if (bitmap == null) {
                createSafeBitmap(
                    getSourceBitmap(),
                    region,
                    "detectRoundedRectangles",
                )!!
            } else {
                createSafeBitmap(bitmap, region, "detectRoundedRectangles") ?: getSourceBitmap()
            }

        // Input sanitization

        val cannyLowerThreshold: Double = cannyLowerThreshold.coerceIn(0, 255).toDouble()
        val cannyUpperThreshold: Double = cannyUpperThreshold.coerceIn(0, 255).toDouble()

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        if (blurSize <= 0 || blurSize % 2 == 0) {
            throw IllegalArgumentException("blurSize must be a positive odd integer. Got: $blurSize.")
        }

        val blurKernel = Size(blurSize.toDouble(), blurSize.toDouble())

        val result: MutableList<BoundingBox> = mutableListOf()

        val srcImage = bitmap.toMat()

        val image = Mat()
        Imgproc.cvtColor(srcImage, image, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(image, image, blurKernel, 0.0)
        if (bUseAdaptiveThreshold) {
            Imgproc.adaptiveThreshold(
                image,
                image,
                255.0, // maxValue
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                adaptiveThresholdBlockSize, // blockSize (must be odd)
                adaptiveThresholdConstant, // C (constant to subtract)
            )
        } else {
            Imgproc.Canny(
                image,
                image,
                cannyLowerThreshold,
                cannyUpperThreshold,
                3,
                false,
            )
        }

        if (debugMode) {
            val resultBitmap = image.toBitmap()
            saveBitmap(resultBitmap, "detectRoundedRectangles_canny", fullRes = true)
        }

        for (rect in findQuadRects(image, minArea, maxArea, epsilonScalar)) {
            if (debugMode) {
                Imgproc.rectangle(srcImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
            }
            result.add(BoundingBox(rect.x, rect.y, rect.width, rect.height))
        }

        if (debugMode) {
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectRoundedRectangles", fullRes = true)
        }

        // Free memory for each mat.
        srcImage.release()

        return result.toList()
    }

    /**
     * Robustly detects generic rectangles on the screen.
     *
     * Useful for detecting items against a uniform background using flood fill.
     *
     * @param bitmap Optional bitmap to analyze. If null, a screenshot is used.
     * @param region Optional bounding box to limit detection area.
     * @param minArea Minimum area for detected rectangles.
     * @param maxArea Maximum area for detected rectangles.
     * @param blurSize Gaussian blur kernel size (positive odd integer). Defaults to 7.
     * @param epsilonScalar Precision factor for shape approximation. Defaults to 0.02.
     * @param fillSeedPoint Seed point for the flood fill background removal.
     * @param fillLoDiffValue Lower difference threshold for flood fill.
     * @param fillUpDiffValue Upper difference threshold for flood fill.
     * @param morphKernelSize Kernel size for morphology operations.
     * @param bIgnoreOverflowYAxis Whether to ignore rectangles overflowing the Y-axis.
     * @param bIgnoreOverflowXAxis Whether to ignore rectangles overflowing the X-axis.
     * @return A list of BoundingBox objects for detected rectangles, sorted by Y-position.
     */
    fun detectRectanglesGeneric(
        bitmap: Bitmap? = null,
        region: BoundingBox? = null,
        minArea: Int? = null,
        maxArea: Int? = null,
        blurSize: Int = 7,
        epsilonScalar: Double = 0.02,
        fillSeedPoint: Point = Point(10.0, 10.0),
        fillLoDiffValue: Int = 1,
        fillUpDiffValue: Int = 1,
        morphKernelSize: Int = 100,
        bIgnoreOverflowYAxis: Boolean = true,
        bIgnoreOverflowXAxis: Boolean = true,
    ): List<BoundingBox> {
        val bitmap: Bitmap =
            if (region == null) {
                bitmap ?: getSourceBitmap()
            } else if (bitmap == null) {
                createSafeBitmap(
                    getSourceBitmap(),
                    region,
                    "detectRectanglesGeneric",
                )!!
            } else {
                createSafeBitmap(bitmap, region, "detectRectanglesGeneric") ?: getSourceBitmap()
            }

        // Input sanitization

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        if (blurSize <= 0 || blurSize % 2 == 0) {
            throw IllegalArgumentException("blurSize must be a positive odd integer. Got: $blurSize.")
        }

        val blurKernel = Size(blurSize.toDouble(), blurSize.toDouble())

        val fillLoDiffValue: Int = fillLoDiffValue.coerceIn(0, 255)
        val loDiff = Scalar(fillLoDiffValue.toDouble(), fillLoDiffValue.toDouble(), fillLoDiffValue.toDouble())

        val fillUpDiffValue: Int = fillUpDiffValue.coerceIn(0, 255)
        val upDiff = Scalar(fillUpDiffValue.toDouble(), fillUpDiffValue.toDouble(), fillUpDiffValue.toDouble())

        val morphKernelSize: Int = morphKernelSize.coerceIn(0, 250)
        val morphKernel =
            Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(morphKernelSize.toDouble(), morphKernelSize.toDouble()),
            )

        val result: MutableList<BoundingBox> = mutableListOf()

        val srcImage = bitmap.toMat()

        val image = Mat()
        Imgproc.cvtColor(srcImage, image, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(image, image, blurKernel, 0.0)

        val rect = Rect()
        val fillColor = Scalar(0.0, 0.0, 0.0)
        Imgproc.floodFill(
            image,
            Mat(),
            fillSeedPoint,
            fillColor,
            rect,
            loDiff,
            upDiff,
        )

        if (debugMode) {
            val resultBitmap = image.toBitmap()
            saveBitmap(resultBitmap, "detectRectanglesGeneric_floodFill", fullRes = true)
        }

        // Set all non-black pixels to white.
        val blackMask = Mat()
        Core.compare(image, fillColor, blackMask, Core.CMP_EQ)
        val nonBlackMask = Mat()
        Core.bitwise_not(blackMask, nonBlackMask)
        image.setTo(Scalar(255.0, 255.0, 255.0), nonBlackMask)
        blackMask.release()
        nonBlackMask.release()

        if (debugMode) {
            val resultBitmap = image.toBitmap()
            saveBitmap(resultBitmap, "detectRectanglesGeneric_masked", fullRes = true)
        }

        Imgproc.morphologyEx(image, image, Imgproc.MORPH_OPEN, morphKernel)

        if (debugMode) {
            val resultBitmap = image.toBitmap()
            saveBitmap(resultBitmap, "detectRectanglesGeneric_opened", fullRes = true)
        }

        // Invert binary image.
        // Core.bitwise_not(image, image)

        for (rect in findQuadRects(image, minArea, maxArea, epsilonScalar)) {
            // Do not include any rects that are touching the bounding region.
            if (bIgnoreOverflowYAxis) {
                if (rect.y <= 0 || rect.y + rect.height >= bitmap.height - 1) {
                    continue
                }
            }

            if (bIgnoreOverflowXAxis) {
                if (rect.x <= 0 || rect.x + rect.width >= bitmap.width - 1) {
                    continue
                }
            }

            if (debugMode) {
                Imgproc.rectangle(srcImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
            }
            result.add(BoundingBox(rect.x, rect.y, rect.width, rect.height))
        }

        if (debugMode) {
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectRectanglesGeneric_result", fullRes = true)
        }

        // Free memory for each mat.
        srcImage.release()

        return result.toList()
    }

    /**
     * Converts an RGB hex string to an HSV Scalar.
     *
     * Supports colors in "#RRGGBB" format.
     *
     * @return An OpenCV Scalar containing HSV values (H: 0-179, S: 0-255, V: 0-255).
     */
    fun String.hexRGBToHSVScalar(): Scalar {
        val colorInt = Color.parseColor(this)
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)

        val bgrColor = Scalar(b.toDouble(), g.toDouble(), r.toDouble())

        val bgrMat = Mat(1, 1, CvType.CV_8UC3, bgrColor)
        val hsvMat = Mat()

        Imgproc.cvtColor(bgrMat, hsvMat, Imgproc.COLOR_BGR2HSV)
        val res = Scalar(hsvMat.get(0, 0))

        bgrMat.release()
        hsvMat.release()

        return res
    }

    /**
     * Converts standardized HSV ranges to OpenCV's internal range.
     *
     * Normalizes H (0-360) to 0-179, and S/V (0-100) to 0-255.
     *
     * @param h The standardized hue value (0-360).
     * @param s The standardized saturation value (0-100).
     * @param v The standardized value/brightness value (0-100).
     * @return An HSV Scalar optimized for OpenCV processing.
     */
    fun standardHsvToOpenCvHsvScalar(h: Int, s: Int, v: Int): Scalar {
        val newH: Int = (h / 2.0).toInt().coerceIn(0, 179)
        val newS: Int = ((s / 100.0) * 255.0).toInt().coerceIn(0, 255)
        val newV: Int = ((v / 100.0) * 255.0).toInt().coerceIn(0, 255)

        return Scalar(newH.toDouble(), newS.toDouble(), newV.toDouble())
    }

    /**
     * Detects a scrollbar and its thumb on the screen.
     *
     * @param bitmap Optional bitmap to analyze. If null, a screenshot is used.
     * @param region Optional bounding box to limit detection area.
     * @param minArea Minimum area for detected components.
     * @param maxArea Maximum area for detected components.
     * @param morphCloseKernelSize Kernel size for morphology operations.
     * @return A pair containing the scrollbar's full [BoundingBox] and its thumb's [BoundingBox].
     */
    fun detectScrollBar(bitmap: Bitmap? = null, region: BoundingBox? = null, minArea: Int? = null, maxArea: Int? = null, morphCloseKernelSize: Int = 10): Pair<BoundingBox?, BoundingBox?> {
        val bitmap: Bitmap =
            if (region == null) {
                bitmap ?: getSourceBitmap()
            } else if (bitmap == null) {
                createSafeBitmap(
                    getSourceBitmap(),
                    region,
                    "detectScrollBar",
                )!!
            } else {
                createSafeBitmap(bitmap, region, "detectScrollBar") ?: getSourceBitmap()
            }

        // Input sanitization

        val screenArea: Int = SharedData.displayWidth * SharedData.displayHeight
        val minArea: Int = (minArea ?: 0).coerceIn(0, screenArea)
        val maxArea: Int = (maxArea ?: screenArea).coerceIn(minArea, screenArea)

        if (minArea > maxArea) {
            throw IllegalArgumentException("minArea ($minArea) > maxArea ($maxArea)")
        }

        val morphCloseKernelSize: Int = morphCloseKernelSize.coerceIn(0, 250)
        val morphCloseKernel =
            Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(morphCloseKernelSize.toDouble(), morphCloseKernelSize.toDouble()),
            )

        val morphOpenKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

        val srcImage = bitmap.toMat()

        val hsvImage = Mat()
        Imgproc.cvtColor(srcImage, hsvImage, Imgproc.COLOR_RGB2HSV)

        if (debugMode) {
            val resultBitmap = hsvImage.toBitmap()
            saveBitmap(resultBitmap, "detectScrollBar_hsvImage", fullRes = true)
        }

        // The rail and the thumb are both near-greys, and hue on a near-grey is numerically unstable - it is a ratio over a channel spread of only 10-22, so a single unit of difference swings it
        // by several degrees. The old windows pinned hue to a 2-degree band and pinned saturation and value to the exact values one device produced, leaving ZERO margin: the rail measured H=126
        // S=12 V=219 against a window of H[125..126] S[10..12] V[216..219], sitting on the upper edge of all three. Any device rendering a single unit higher - a wide-gamut panel, a night-light
        // filter, vendor colour processing - lost the scrollbar entirely, which is why detection worked for some users and not others on identical screen sizes.
        //
        // So hue is dropped: it carries no information here. Value does the work instead, and it separates all three things cleanly - the panel behind the scrollbar is near-white (measured V=241
        // and V=249), the rail sits at V=219 and the thumb at V=142, so the two windows below are disjoint from each other and from the backdrop. Saturation is left wide open on purpose: it is as
        // unstable as hue on a near-grey (the rail's brightest channel is its blue one, so a blue-reducing night filter collapses its channel spread and its saturation with it), so pinning it would
        // just reintroduce the same cliff on a different axis. Shape is what rejects anything else that happens to be this bright - see detectFromMask.
        val thumbColorRange: Pair<Scalar, Scalar> =
            Pair(
                Scalar(0.0, 25.0, 115.0), // approx #787388, measured H=127 S=40 V=142
                Scalar(179.0, 60.0, 170.0),
            )

        val barColorRange: Pair<Scalar, Scalar> =
            Pair(
                Scalar(0.0, 5.0, 205.0), // approx #d3d1db, measured H=126 S=12 V=219
                Scalar(179.0, 70.0, 232.0),
            )

        val combinedColorRange: List<Pair<Scalar, Scalar>> =
            listOf(
                thumbColorRange,
                barColorRange,
            )

        /**
         * Generates a mask from an HSV image using the given color range.
         *
         * @param hsvImage The HSV image used to generate the mask.
         * @param colorRanges A list of pairs of RGB hex color strings. The first item in each pair is the lower bound and the second item is the upper bound. Colors within this range in [hsvImage]
         *    will be masked.
         * @return The generated mask Mat. Make sure to release this Mat when done.
         */
        fun extractMask(hsvImage: Mat, colorRanges: List<Pair<Scalar, Scalar>>): Mat {
            val mask = Mat.zeros(hsvImage.size(), CvType.CV_8UC1)
            for ((lower, upper) in colorRanges) {
                val tmpMask = Mat()
                Core.inRange(hsvImage, lower, upper, tmpMask)
                Core.bitwise_or(mask, tmpMask, mask)
                tmpMask.release()
            }

            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, morphOpenKernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, morphCloseKernel)
            return mask
        }

        val barMask: Mat =
            extractMask(
                hsvImage,
                combinedColorRange,
            )
        if (debugMode) {
            val resultBitmap = barMask.toBitmap()
            saveBitmap(resultBitmap, "detectScrollBar_barMask", fullRes = true)
        }

        val thumbMask: Mat =
            extractMask(
                hsvImage,
                listOf(thumbColorRange),
            )
        if (debugMode) {
            val resultBitmap = thumbMask.toBitmap()
            saveBitmap(resultBitmap, "detectScrollBar_thumbMask", fullRes = true)
        }

        /**
         * Detects part of a scrollbar in the given mask.
         *
         * @param mask The masked image to find a scrollbar within.
         * @param minArea The smallest area allowed for the scrollbar.
         * @param maxArea The largest area allowed for the scrollbar.
         * @param debugString String used for debugging and saving debug images.
         * @return The BoundingBox of the detected scrollbar on success. Otherwise, null.
         */
        fun detectFromMask(mask: Mat, minArea: Int, maxArea: Int, debugString: String = ""): BoundingBox? {
            val debugImage = bitmap.toMat()

            val contours: MutableList<MatOfPoint> = mutableListOf()
            val hierarchy = Mat()
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val result: MutableList<Pair<BoundingBox, Double>> = mutableListOf()
            for (cnt in contours) {
                val area = Imgproc.contourArea(cnt)

                // Filter out contours with invalid areas.
                if (area < minArea || area > maxArea) {
                    continue
                }

                val rect = Imgproc.boundingRect(cnt)

                // A scrollbar is a tall, narrow, vertical sliver, and nothing else in its column is. With hue and saturation deliberately unconstrained, this is what keeps a pale badge or a rounded
                // card corner from being crowned the largest contour and returned as the bar. Measured: the rail is 10x607 and the thumb 10x425, so both clear this by a wide margin.
                if (rect.height <= rect.width * 2) {
                    continue
                }

                // Do not include any rects that are touching the bounding region.
                if (rect.x <= 0 ||
                    rect.y <= 0 ||
                    rect.x + rect.width >= bitmap.width - 1 ||
                    rect.y + rect.height >= bitmap.height - 1
                ) {
                    continue
                }

                if (debugMode) {
                    Imgproc.rectangle(debugImage, rect.tl(), rect.br(), Scalar(0.0, 255.0, 0.0), 2)
                }

                result.add(
                    Pair(
                        BoundingBox(rect.x, rect.y, rect.width, rect.height),
                        area,
                    ),
                )
            }

            if (debugMode) {
                val resultBitmap = createBitmap(debugImage.cols(), debugImage.rows())
                Imgproc.cvtColor(debugImage, debugImage, Imgproc.COLOR_BGR2RGB)
                Utils.matToBitmap(debugImage, resultBitmap)
                saveBitmap(resultBitmap, "detectScrollBar_$debugString", fullRes = true)
            }

            contours.forEach { it.release() }
            contours.clear()
            hierarchy.release()
            debugImage.release()

            return result.maxByOrNull { it.second }?.first
        }

        val bboxBar: BoundingBox? =
            detectFromMask(
                barMask,
                minArea = minArea,
                maxArea = maxArea,
                debugString = "bar",
            )
        if (bboxBar == null && debugMode) {
            MessageLog.i(TAG, "[INFO] No scrollbar detected.")
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_FAILED", fullRes = true)
        }

        val bboxThumb: BoundingBox? =
            detectFromMask(
                thumbMask,
                minArea = 100,
                maxArea = maxArea,
                debugString = "thumb",
            )
        if (bboxThumb == null && debugMode) {
            MessageLog.i(TAG, "[INFO] No scrollbar thumb detected.")
            val resultBitmap = createBitmap(srcImage.cols(), srcImage.rows())
            Imgproc.cvtColor(srcImage, srcImage, Imgproc.COLOR_BGR2RGB)
            Utils.matToBitmap(srcImage, resultBitmap)
            saveBitmap(resultBitmap, "detectScrollBar_thumb_FAILED", fullRes = true)
        }

        // Free memory for each mat.
        barMask.release()
        thumbMask.release()
        hsvImage.release()
        srcImage.release()

        if (debugMode) {
            MessageLog.d(TAG, "[DEBUG] detectScrollBar:: Results: bboxBar=$bboxBar, bboxThumb=$bboxThumb")
        }
        return Pair(bboxBar, bboxThumb)
    }

    /**
     * Calculates the relative luminance at a specific pixel in a bitmap.
     *
     * @param x The X-coordinate of the pixel.
     * @param y The Y-coordinate of the pixel.
     * @param bitmap The bitmap to analyze. If null, a screenshot is used.
     * @return The luminance value between 0.0 (dark) and 1.0 (bright).
     */
    fun getLuminanceAtCoordinates(x: Int, y: Int, bitmap: Bitmap? = null): Double {
        val bitmap: Bitmap = bitmap ?: getSourceBitmap()
        val pixel = bitmap.getPixel(x, y)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // https://en.wikipedia.org/wiki/Relative_luminance
        val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return luminance
    }

    /**
     * Compares the average luminance of two bitmaps using random sampling.
     *
     * @param a The first bitmap for comparison.
     * @param b The bitmap to compare against.
     * @param samples The number of pixel samples to take. Defaults to 100.
     * @param tolerance The margin of error for the comparison. Defaults to 0.05.
     * @return -1 if [a] is brighter, 1 if [b] is brighter, or 0 if they are similar.
     */
    fun compareBitmapLuminance(a: Bitmap, b: Bitmap, samples: Int = 100, tolerance: Double = 0.05): Int {
        if (a.width != b.width || a.height != b.height) {
            return 0
        }

        var lumA = 0.0
        var lumB = 0.0

        // Clamp number of samples based on bitmap size.
        val samples: Int = minOf(samples, a.width * a.height)

        for (i in 0 until samples) {
            // We want to sample at the same coordinates for each bitmap.
            val x = Random.nextInt(0, a.width)
            val y = Random.nextInt(0, a.height)

            lumA += getLuminanceAtCoordinates(x, y, a)
            lumB += getLuminanceAtCoordinates(x, y, b)
        }

        lumA /= samples
        lumB /= samples

        return if (lumA < lumB - tolerance) {
            1
        } else if (lumB < lumA - tolerance) {
            -1
        } else {
            0
        }
    }
}
