package com.steve1316.uma_android_automation.utils

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.IconDialogScrollListBottomRight
import com.steve1316.uma_android_automation.components.IconDialogScrollListTopLeft
import com.steve1316.uma_android_automation.components.IconScrollListBottomRight
import com.steve1316.uma_android_automation.components.IconScrollListTopLeft
import com.steve1316.uma_android_automation.types.BoundingBox
import org.opencv.core.Point

/** Default maximum processing time in milliseconds. */
const val MAX_PROCESS_TIME_DEFAULT_MS = 60000

/** Bound on the swipes [ScrollList.scrollToTopBySwiping] will spend, so a scrollbar it cannot read cannot make it spin forever. */
private const val MAX_SCROLL_TO_TOP_SWIPES = 8

/** How far the scrollbar thumb may sit below the top of its track and still count as being at the top. */
private const val SCROLL_TOP_TOLERANCE = 4

/**
 * The smallest share of the screen height a detected list may cover before it is rejected as a false positive.
 *
 * The corner markers are 11x11 and 16x16 near-featureless arcs, so they match rounded edges all over the UI - measured against one screen, the full-screen top-left marker scored above 0.80 in
 * 76 different places. On a screen that really does hold a list the true corner still wins the argmax, but on a screen that does NOT, two unrelated edges win it between them and the result is a
 * confidently wrong box rather than no box: run against the Umamusume Details dialog, the full-screen markers returned a 585x68 sliver of the stats panel as "the list".
 *
 * Height separates the two by a wide margin: that sliver is 4% of the screen height, while the shallowest real list measured is the Details skill grid at 34% (the skill shop is 44%).
 */
private const val MIN_LIST_HEIGHT_FRACTION = 0.12

/**
 * Creates a [ScrollList] for a list that lives inside a dialog. A dialog draws its own rounded corner markers rather than the full-screen ones, so it needs its own pair of corner templates.
 *
 * The inertia tap is off for every dialog list. A dialog's rows are tappable right out to the panel edge, so there is nowhere in the list to put the tap that does not open a row - on the
 * Umamusume Details skill grid it opens the Skill Details popup.
 *
 * @param game Reference to the bot's [Game] instance.
 * @param bitmap Optional bitmap to detect against, so a caller holding the current frame does not force another screenshot.
 * @return The [ScrollList], or null when no dialog list is on screen - in which case there was nothing scrollable there to begin with.
 */
fun createDialogScrollList(game: Game, bitmap: Bitmap? = null): ScrollList? =
    ScrollList.create(
        game,
        bitmap = bitmap,
        listTopLeftComponent = IconDialogScrollListTopLeft,
        listBottomRightComponent = IconDialogScrollListBottomRight,
        bAllowInertiaTap = false,
    )

/** Functional interface for a callback that is called whenever an entry is detected while processing the list. */
fun interface OnEntryDetectedCallback {
    /**
     * Called whenever an entry is detected while processing the list.
     *
     * @param scrollList A reference to this class instance.
     * @param entry The [ScrollListEntry] instance that we detected.
     * @return True to stop the [ScrollList.process] loop early (e.g., after finding a specific entry).
     */
    fun onEntryDetected(scrollList: ScrollList, entry: ScrollListEntry): Boolean
}

/**
 * Stores a single entry's information in the scroll list.
 *
 * @param index The index of this entry in the list.
 * @param bitmap A single entry's bitmap, extracted from the screen.
 * @param bbox The bounding box for the [bitmap], in screen coordinates.
 * @property refX The reference X-coordinate for the entry.
 * @property refY The reference Y-coordinate for the entry.
 */
data class ScrollListEntry(val index: Int, val bitmap: Bitmap, val bbox: BoundingBox, val refX: Int? = null, val refY: Int? = null)

/**
 * Stores configuration for entry image detection.
 *
 * See [CustomImageUtils.detectRoundedRectangles] or [CustomImageUtils.detectRectanglesGeneric] for more information.
 *
 * @property bUseGeneric Whether to use generic rectangle detection.
 * @property minArea The minimum area for a detected rectangle.
 * @property maxArea The maximum area for a detected rectangle.
 * @property blurSize The size of the blur kernel.
 * @property epsilonScalar The epsilon scalar for contour approximation.
 * @property cannyLowerThreshold The lower threshold for Canny edge detection.
 * @property cannyUpperThreshold The upper threshold for Canny edge detection.
 * @property bUseAdaptiveThreshold Whether to use adaptive thresholding.
 * @property adaptiveThresholdBlockSize The block size for adaptive thresholding.
 * @property adaptiveThresholdConstant The constant for adaptive thresholding.
 * @property fillSeedPoint The seed point for flood fill.
 * @property fillLoDiffValue The low difference value for flood fill.
 * @property fillUpDiffValue The high difference value for flood fill.
 * @property morphKernelSize The kernel size for morphological operations.
 * @property bIgnoreOverflowYAxis Whether to ignore overflow on the Y-axis.
 * @property bIgnoreOverflowXAxis Whether to ignore overflow on the X-axis.
 */
data class ScrollListEntryDetectionConfig(
    val bUseGeneric: Boolean = true,
    // The area parameters can be updated later to fit the scroll list's dims.
    var minArea: Int? = null,
    var maxArea: Int? = null,
    val blurSize: Int = if (bUseGeneric) 7 else 5,
    val epsilonScalar: Double = 0.02,
    // CustomImageUtils.detectRoundedRectangles params.
    val cannyLowerThreshold: Int = 30,
    val cannyUpperThreshold: Int = 50,
    val bUseAdaptiveThreshold: Boolean = true,
    val adaptiveThresholdBlockSize: Int = 11,
    val adaptiveThresholdConstant: Double = 2.0,
    // CustomImageUtils.detectRectanglesGeneric params.
    val fillSeedPoint: Point = Point(10.0, 10.0),
    val fillLoDiffValue: Int = 1,
    val fillUpDiffValue: Int = 1,
    val morphKernelSize: Int = 100,
    val bIgnoreOverflowYAxis: Boolean = true,
    // Ignoring X overflow helps for things like the race list, where the selected
    // race has angle brackets around it that overflow with the scroll bar.
    val bIgnoreOverflowXAxis: Boolean = false,
)

/**
 * Handles parsing entries in a scrollable list.
 *
 * Example:
 * ```
 * val list: ScrollList? = ScrollList.create(game)
 * if (list == null) throw InvalidStateException()
 * scrollList.process() { scrollList: ScrollList, entry: ScrollListEntry ->
 *      imageUtils.saveBitmap(entry.bitmap, "entry_${entry.index}")
 *      // Return true to stop the scrollList loop if we've read 5 entries.
 *      entry.index > 5
 * }
 * ```
 *
 * @param game Reference to the bot's [Game] instance.
 * @param bboxList The bounding region of the full list.
 * @param bboxEntries The refined [bboxList] with a buffer on the top and bottom to prevent partial entries.
 * @param entryDetectionConfig The configuration for image detection.
 */
class ScrollList private constructor(
    private val game: Game,
    private val bboxList: BoundingBox,
    entryDetectionConfig: ScrollListEntryDetectionConfig,
    private val bAllowInertiaTap: Boolean = true,
) {
    /** The detected bounding region of the list on screen. Exposed so a caller with its own row geometry can aim a swipe within the list without re-detecting it. */
    val listBoundingBox: BoundingBox get() = bboxList

    /** The minimum height for a single entry. */
    private val defaultMinEntryHeight: Int = game.imageUtils.relHeight((SharedData.displayHeight * 0.0781).toInt()) // 150px on 1920h

    /** The maximum height for a single entry. */
    private val defaultMaxEntryHeight: Int = game.imageUtils.relHeight((SharedData.displayHeight * 0.1302).toInt()) // 250px on 1920h

    /** The configuration used for image detection. */
    private val entryDetectionConfig =
        ScrollListEntryDetectionConfig(
            bUseGeneric = entryDetectionConfig.bUseGeneric,
            minArea = entryDetectionConfig.minArea ?: (defaultMinEntryHeight * (bboxList.w.toDouble() * 0.7).toInt()),
            maxArea = entryDetectionConfig.maxArea ?: (defaultMaxEntryHeight * bboxList.w),
            blurSize = entryDetectionConfig.blurSize,
            epsilonScalar = entryDetectionConfig.epsilonScalar,
            // detectRoundedRectangles params
            cannyLowerThreshold = entryDetectionConfig.cannyLowerThreshold,
            cannyUpperThreshold = entryDetectionConfig.cannyUpperThreshold,
            bUseAdaptiveThreshold = entryDetectionConfig.bUseAdaptiveThreshold,
            adaptiveThresholdBlockSize = entryDetectionConfig.adaptiveThresholdBlockSize,
            adaptiveThresholdConstant = entryDetectionConfig.adaptiveThresholdConstant,
            // detectRectanglesGeneric params
            fillSeedPoint = entryDetectionConfig.fillSeedPoint,
            fillLoDiffValue = entryDetectionConfig.fillLoDiffValue,
            fillUpDiffValue = entryDetectionConfig.fillUpDiffValue,
            morphKernelSize = entryDetectionConfig.morphKernelSize,
            bIgnoreOverflowYAxis = entryDetectionConfig.bIgnoreOverflowYAxis,
            bIgnoreOverflowXAxis = entryDetectionConfig.bIgnoreOverflowXAxis,
        )

    /**
     * The padding around the edge of the list.
     *
     * Create a small padding within the bboxList. This is where the list entries reside. This prevents us from accidentally clicking outside the list.
     */
    private val listPadding: Int = 5

    /** The bounding box for the entries in the list. */
    private val bboxEntries: BoundingBox =
        BoundingBox(
            x = bboxList.x + listPadding,
            y = bboxList.y + listPadding,
            w = bboxList.w - (listPadding * 2),
            h = bboxList.h - (listPadding * 2),
        )

    /**
     * The default width of the scroll bar.
     *
     * An estimate of the scrollbar's location within the list. Roughly 35px wide on a 1080 screen, scaled to screen width.
     */
    private val defaultScrollBarWidth: Int = (0.0325 * SharedData.displayWidth).toInt()

    /** The default region of the scroll bar. */
    val bboxScrollBarRegionDefault =
        BoundingBox(
            x = bboxList.x + (bboxList.w - defaultScrollBarWidth),
            y = bboxList.y + 10,
            w = defaultScrollBarWidth,
            h = bboxList.h - 20,
        )

    /**
     * The minimum area of the scroll bar.
     *
     * No known scrollbars that are anywhere near this small.
     */
    private val bboxScrollBarMinArea: Int = 100

    /** The maximum area of the scroll bar. */
    private val bboxScrollBarMaxArea: Int = bboxScrollBarRegionDefault.w * bboxScrollBarRegionDefault.h

    /** Whether the list is scrollable. */
    var bIsScrollable: Boolean = false
        private set

    /** Whether to scroll by blind swipes instead of relying on in-game scrollbar detection. Read from the user setting. */
    private val swipeMode: Boolean = SettingsHelper.getBooleanSetting("general", "enableSwipeBasedScrolling")

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]ScrollList"

        /**
         * Creates a new [ScrollList] instance.
         *
         * @param game Reference to the bot's [Game] instance.
         * @param bitmap Optional bitmap used for detecting list bounding region.
         * @param listTopLeftComponent An image component used to detect the top left corner of the list.
         * @param listBottomRightComponent An image component used to detect the bottom right corner of the list.
         * @param entryDetectionConfig Optional image detection configuration.
         * @param bAllowInertiaTap Whether the list may be tapped to kill its scroll inertia. Turn this off for a list whose rows are tappable to their very edge, since there is then nowhere
         *    safe to put the tap and it opens a row. See [stopScrolling].
         * @return On success, the [ScrollList] instance. Otherwise, null.
         */
        fun create(
            game: Game,
            bitmap: Bitmap? = null,
            listTopLeftComponent: ComponentInterface? = null,
            listBottomRightComponent: ComponentInterface? = null,
            entryDetectionConfig: ScrollListEntryDetectionConfig? = null,
            bAllowInertiaTap: Boolean = true,
        ): ScrollList? {
            val bboxList: BoundingBox = getListBoundingRegion(game, bitmap, listTopLeftComponent, listBottomRightComponent) ?: return null
            return ScrollList(game, bboxList, entryDetectionConfig ?: ScrollListEntryDetectionConfig(), bAllowInertiaTap)
        }

        /**
         * Gets the bounding region for the list on the screen.
         *
         * @param game Reference to the bot's [Game] instance.
         * @param bitmap Optional bitmap used for detecting list bounding region. If not specified, a screenshot will be taken and used instead. NOTE: This parameter must be specified in thread-safe
         *    contexts.
         * @param listTopLeftComponent The Component used to detect the top left corner of the list. Defaults to [IconScrollListTopLeft].
         * @param listBottomRightComponent The Component used to detect the bottom right corner of the list. Defaults to [IconScrollListBottomRight].
         * @param debugString Optional string used for naming debug screenshots.
         * @return On success, the bounding region. On failure, null.
         */
        private fun getListBoundingRegion(
            game: Game,
            bitmap: Bitmap? = null,
            listTopLeftComponent: ComponentInterface? = null,
            listBottomRightComponent: ComponentInterface? = null,
            debugString: String = "",
        ): BoundingBox? {
            val bitmap: Bitmap = bitmap ?: game.imageUtils.getSourceBitmap()

            val listTopLeftComponent: ComponentInterface = listTopLeftComponent ?: IconScrollListTopLeft
            val listBottomRightComponent: ComponentInterface = listBottomRightComponent ?: IconScrollListBottomRight

            val listTopLeftBitmap: Bitmap? = listTopLeftComponent.template.getBitmap(game.imageUtils)
            if (listTopLeftBitmap == null) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: Failed to load bitmap: ${listTopLeftComponent.template.path} ")
                return null
            }

            val listBottomRightBitmap: Bitmap? = listBottomRightComponent.template.getBitmap(game.imageUtils)
            if (listBottomRightBitmap == null) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: Failed to load bitmap: ${listBottomRightComponent.template.path}")
                return null
            }

            val listTopLeft: Point? = listTopLeftComponent.findImageWithBitmap(game.imageUtils, bitmap)
            if (listTopLeft == null) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: Failed to find top left corner of race list.")
                return null
            }
            val listBottomRight: Point? = listBottomRightComponent.findImageWithBitmap(game.imageUtils, bitmap)
            if (listBottomRight == null) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: Failed to find bottom right corner of race list.")
                return null
            }
            val x0 = (listTopLeft.x - (listTopLeftBitmap.width / 2)).toInt()
            val y0 = (listTopLeft.y - (listTopLeftBitmap.height / 2)).toInt()
            val x1 = (listBottomRight.x + (listBottomRightBitmap.width / 2)).toInt()
            val y1 = (listBottomRight.y + (listBottomRightBitmap.height / 2)).toInt()

            // The corners must be found in the right order. Normalizing an inverted pair with abs() only turns two false-positive matches into a plausible-looking box and hands it downstream.
            if (y1 < y0 || x1 < x0) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: The scroll list corner markers were found out of order (top-left at ($x0, $y0), bottom-right at ($x1, $y1)), so they are not a list.")
                return null
            }

            val bbox =
                BoundingBox(
                    x = x0,
                    y = y0,
                    w = x1 - x0,
                    h = y1 - y0,
                )

            if (bbox.w <= 0 || bbox.h <= 0) {
                MessageLog.e(TAG, "[ERROR] getListBoundingRegion:: Invalid bounding box (zero width or height): $bbox")
                return null
            }

            // Reject a box too short to be a list. See MIN_LIST_HEIGHT_FRACTION - the markers happily match unrelated rounded edges, so a "found" box is not proof a list is there.
            val minHeight: Int = (SharedData.displayHeight * MIN_LIST_HEIGHT_FRACTION).toInt()
            if (bbox.h < minHeight) {
                MessageLog.w(
                    TAG,
                    "[WARN] getListBoundingRegion:: The detected box $bbox is too short to be a list (needs at least $minHeight tall), so the corner markers matched something else.",
                )
                return null
            }

            if (game.debugMode) {
                game.imageUtils.saveBitmapWithBbox(bitmap, "getListBoundingRegion_$debugString", bbox)
            }

            return bbox
        }

        /**
         * Processes a list, automatically falling back to non-scrollable detection if the standard scroll region isn't found.
         *
         * @param game Reference to the bot's [Game] instance.
         * @param maxTimeMs Maximum processing time before timeout.
         * @param bScrollBottomToTop If true, process from bottom to top.
         * @param keyExtractor Optional callback to generate unique keys and skip duplicates.
         * @param fallbackComponent The component to use for identifying rows if the scroll list region isn't found.
         * @param bForceComponentDetection If true, force component-based detection even if the scroll list region is found.
         * @param listTopLeftComponent Optional top-left corner component for the scroll list.
         * @param listBottomRightComponent Optional bottom-right corner component for the scroll list.
         * @param entryDetectionConfig Optional config for entry detection.
         * @param onEntry Callback executed for each entry. Return true to exit early.
         * @return True if processing completed or exited early via callback.
         */
        fun processWithFallback(
            game: Game,
            maxTimeMs: Int = MAX_PROCESS_TIME_DEFAULT_MS,
            bScrollBottomToTop: Boolean = false,
            keyExtractor: ((ScrollListEntry) -> String?)? = null,
            fallbackComponent: ComponentInterface,
            bForceComponentDetection: Boolean = false,
            listTopLeftComponent: ComponentInterface? = null,
            listBottomRightComponent: ComponentInterface? = null,
            entryDetectionConfig: ScrollListEntryDetectionConfig? = null,
            onEntry: OnEntryDetectedCallback,
        ): Boolean {
            val sourceBitmap = game.imageUtils.getSourceBitmap()

            // Step 1: Attempt to create a standard ScrollList.
            val list = create(game, sourceBitmap, listTopLeftComponent, listBottomRightComponent, entryDetectionConfig)
            if (list != null) {
                MessageLog.d(TAG, "[DEBUG] processWithFallback:: Standard ScrollList detected. Processing...")
                return list.process(maxTimeMs, bScrollBottomToTop, keyExtractor, fallbackComponent, bForceComponentDetection, onEntry)
            }

            // Step 2: Fallback to component-based detection.
            MessageLog.d(TAG, "[DEBUG] processWithFallback:: ScrollList region not found. Falling back to ${fallbackComponent.template.basename} detection.")

            val swipeMode = SettingsHelper.getBooleanSetting("general", "enableSwipeBasedScrolling")
            if (swipeMode) {
                // The list region wasn't found, so swipe a full-screen pseudo-list and dedupe by key across frames.
                val fullScreen = ScrollList(game, BoundingBox(0, 0, sourceBitmap.width, sourceBitmap.height), entryDetectionConfig ?: ScrollListEntryDetectionConfig())
                val processedKeys = mutableSetOf<String>()
                val startTime: Long = System.currentTimeMillis()
                var consecutiveNoNewFrames = 0
                var scrollCount = 0
                var bitmap: Bitmap = sourceBitmap
                while (System.currentTimeMillis() - startTime < maxTimeMs && scrollCount < 30) {
                    val frameEntries = detectEntriesByComponent(game, bitmap, fallbackComponent)
                    if (frameEntries.isEmpty() && scrollCount == 0) {
                        MessageLog.w(TAG, "[WARN] processWithFallback:: Failed to detect any entries using fallback component.")
                        return false
                    }

                    var newThisFrame = 0
                    for (entry in frameEntries) {
                        // Fall back to a visual fingerprint when the keyExtractor can't read the row (e.g. greyed-out items) so duplicates still dedupe.
                        val key = if (keyExtractor != null) (keyExtractor(entry) ?: entrySignature(entry.bitmap)) else null
                        if (key != null && processedKeys.contains(key)) continue
                        if (key != null) processedKeys.add(key)
                        newThisFrame++
                        if (onEntry.onEntryDetected(fullScreen, entry)) {
                            return true
                        }
                    }

                    // An empty frame counts as no-new-content too, so a list that empties out terminates instead of swiping to the hard cap.
                    if (newThisFrame == 0) consecutiveNoNewFrames++ else consecutiveNoNewFrames = 0
                    if (consecutiveNoNewFrames >= 2) {
                        MessageLog.d(TAG, "[DEBUG] processWithFallback:: No new entries for 2 frames. Exiting (swipe mode).")
                        return true
                    }

                    if (bScrollBottomToTop) fullScreen.scrollUp() else fullScreen.scrollDown()
                    scrollCount++
                    game.wait(0.5, skipWaitingForLoading = true)
                    bitmap = game.imageUtils.getSourceBitmap()
                }
                return true
            }

            val entries = detectEntriesByComponent(game, sourceBitmap, fallbackComponent)
            if (entries.isEmpty()) {
                MessageLog.w(TAG, "[WARN] processWithFallback:: Failed to detect any entries using fallback component.")
                return false
            }

            // Static "list" for fallback mode (not scrollable).
            val processedKeys = mutableSetOf<String>()
            for (entry in entries) {
                if (keyExtractor != null) {
                    val key = keyExtractor(entry)
                    if (key != null) {
                        if (processedKeys.contains(key)) continue
                        processedKeys.add(key)
                    }
                }

                if (onEntry.onEntryDetected(ScrollList(game, BoundingBox(0, 0, sourceBitmap.width, sourceBitmap.height), ScrollListEntryDetectionConfig()), entry)) {
                    return true
                }
            }

            return true
        }

        /**
         * Computes a stable visual fingerprint of a row bitmap for deduplication when no text key is available (e.g. greyed-out / purchased rows).
         * Downscales to an 8x8 grayscale average hash so the same row yields the same key across frames despite minor capture jitter.
         *
         * @param bitmap The entry's cropped bitmap.
         * @return A stable "SIG_"-prefixed key derived from the bitmap's visual content.
         */
        private fun entrySignature(bitmap: Bitmap): String {
            val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            val pixels = IntArray(64)
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            val gray =
                IntArray(64) { i ->
                    val p = pixels[i]
                    (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                }
            val avg = gray.average()
            var hash = 0L
            for (i in 0 until 64) {
                if (gray[i] >= avg) hash = hash or (1L shl i)
            }
            return "SIG_$hash"
        }

        /**
         * Detects entries by finding all instances of a specific component on the screen.
         *
         * @param game Reference to the bot's [Game] instance.
         * @param sourceBitmap The bitmap to scan.
         * @param component The component whose locations identify the rows.
         * @return A list of pseudo-ScrollListEntry objects.
         */
        private fun detectEntriesByComponent(game: Game, sourceBitmap: Bitmap, component: ComponentInterface): List<ScrollListEntry> {
            val points = component.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
            MessageLog.d(TAG, "[DEBUG] detectEntriesByComponent:: Found ${points.size} instances of ${component.template.basename}.")

            // Sort points by Y-coordinate.
            val sortedPoints = points.sortedBy { it.y }

            return sortedPoints.mapIndexed { index, point ->
                // Estimate entry height based on screen size (roughly 220px on 1920h).
                val entryHeight = game.imageUtils.relHeight(220)
                val entryY = (point.y - (entryHeight / 2)).toInt().coerceIn(0, sourceBitmap.height - entryHeight)
                val entryBBox = BoundingBox(x = 0, y = entryY, w = sourceBitmap.width, h = entryHeight)
                val entryBitmap = game.imageUtils.createSafeBitmap(sourceBitmap, entryBBox, "PseudoEntry_$index")

                ScrollListEntry(
                    index = index,
                    bitmap = entryBitmap ?: sourceBitmap,
                    bbox = entryBBox,
                    refX = point.x.toInt(),
                    refY = (point.y - entryY).toInt(),
                )
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Detects locations of each entry in the visible portion of the list.
     *
     * @param bitmap An optional bitmap to use when detecting entries. If not specified, a screenshot will be taken.
     * @param entryComponent Optional component to use for identifying rows.
     * @param bForceComponentDetection If true, force component-based detection even if the scroll list region is found.
     * @return A list of [ScrollListEntry] objects for each entry that we detected.
     */
    private fun detectEntries(bitmap: Bitmap? = null, entryComponent: ComponentInterface? = null, bForceComponentDetection: Boolean = false): List<ScrollListEntry> {
        val sourceBitmap = bitmap ?: game.imageUtils.getSourceBitmap()
        val bboxBar: BoundingBox? = getListScrollBarBoundingRegion(sourceBitmap).first

        // We want to cut the scroll bar region out of the search region. This way, the scroll bar doesn't cause entries to merge together.
        // This is really only important for lists where entries can have overlay icons (such as selection brackets) around them that can overlap with the scrollbar.
        val bboxNoScrollbar =
            BoundingBox(
                x = bboxList.x,
                y = bboxList.y,
                w = if (bboxBar == null) bboxList.w else bboxBar.x - bboxList.x,
                h = bboxList.h,
            )

        // If a component is provided and we are forcing component detection, use it to identify entries.
        if (entryComponent != null && bForceComponentDetection) {
            // Find all instances of the landmark component in the current frame.
            val points = entryComponent.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
            val filteredPoints =
                points.filter { point ->
                    // Check if the landmark is within the horizontal bounds of the list.
                    val xInRange = point.x >= bboxNoScrollbar.x && point.x <= bboxNoScrollbar.x + bboxNoScrollbar.w
                    // Use a 150px vertical padding buffer to identify landmarks that may be partially outside the nominal scroll area.
                    val yInRange = point.y >= (bboxNoScrollbar.y - 150) && point.y <= (bboxNoScrollbar.y + bboxNoScrollbar.h + 150)

                    if (!xInRange || !yInRange) {
                        MessageLog.d(
                            TAG,
                            "[DEBUG] detectEntries:: Point at (${point.x.toInt()}, ${point.y.toInt()}) filtered out. List BBox: $bboxNoScrollbar. xInRange=$xInRange, yInRange=$yInRange (Padding: 150px)",
                        )
                    }

                    xInRange && yInRange
                }
            val sortedPoints = filteredPoints.sortedBy { it.y }

            return sortedPoints.mapIndexed { index, point ->
                // Estimate entry height based on screen size (roughly 220px on 1920h).
                val entryHeight = game.imageUtils.relHeight(220)
                // Relax clamping to the entire screen height to correctly position entries that are outside the nominal scroll area (e.g. at the top).
                val entryY = (point.y - (entryHeight / 2)).toInt().coerceIn(0, sourceBitmap.height - entryHeight)
                val entryBBox = BoundingBox(x = bboxNoScrollbar.x, y = entryY, w = bboxNoScrollbar.w, h = entryHeight)
                val entryBitmap = game.imageUtils.createSafeBitmap(sourceBitmap, entryBBox, "Entry_$index")

                ScrollListEntry(
                    index = -1, // To be filled by process().
                    bitmap = entryBitmap ?: sourceBitmap,
                    bbox = entryBBox,
                    // Preserve the landmark's coordinates relative to the entry bitmap for downstream components.
                    refX = point.x.toInt(),
                    refY = (point.y - entryY).toInt(),
                )
            }
        }

        // Extract a list of bounding boxes for each entry in the list.
        val rects: List<BoundingBox> =
            if (entryDetectionConfig.bUseGeneric) {
                game.imageUtils.detectRectanglesGeneric(
                    bitmap = sourceBitmap,
                    region = bboxNoScrollbar,
                    minArea = entryDetectionConfig.minArea,
                    maxArea = entryDetectionConfig.maxArea,
                    blurSize = entryDetectionConfig.blurSize,
                    epsilonScalar = entryDetectionConfig.epsilonScalar,
                    fillSeedPoint = entryDetectionConfig.fillSeedPoint,
                    fillLoDiffValue = entryDetectionConfig.fillLoDiffValue,
                    fillUpDiffValue = entryDetectionConfig.fillUpDiffValue,
                    morphKernelSize = entryDetectionConfig.morphKernelSize,
                    bIgnoreOverflowYAxis = entryDetectionConfig.bIgnoreOverflowYAxis,
                    bIgnoreOverflowXAxis = entryDetectionConfig.bIgnoreOverflowXAxis,
                )
            } else {
                game.imageUtils.detectRoundedRectangles(
                    bitmap = sourceBitmap,
                    region = bboxNoScrollbar,
                    minArea = entryDetectionConfig.minArea,
                    maxArea = entryDetectionConfig.maxArea,
                    blurSize = entryDetectionConfig.blurSize,
                    epsilonScalar = entryDetectionConfig.epsilonScalar,
                    cannyLowerThreshold = entryDetectionConfig.cannyLowerThreshold,
                    cannyUpperThreshold = entryDetectionConfig.cannyUpperThreshold,
                    bUseAdaptiveThreshold = entryDetectionConfig.bUseAdaptiveThreshold,
                    adaptiveThresholdBlockSize = entryDetectionConfig.adaptiveThresholdBlockSize,
                    adaptiveThresholdConstant = entryDetectionConfig.adaptiveThresholdConstant,
                )
            }

        // Adjust BoundingBox coordinates to be screen-relative and create ScrollListEntry objects.
        val result: List<ScrollListEntry> =
            rects.mapIndexed { index, it ->
                val entryBBox =
                    BoundingBox(
                        x = it.x + bboxList.x,
                        y = it.y + bboxList.y,
                        w = it.w,
                        h = it.h,
                    )
                val entryBitmap = game.imageUtils.createSafeBitmap(sourceBitmap, entryBBox, "Entry_$index")

                ScrollListEntry(
                    index = -1, // To be filled by process()
                    bitmap = entryBitmap ?: sourceBitmap,
                    bbox = entryBBox,
                )
            }

        // Sort by screen position top to bottom.
        return result.sortedBy { it.bbox.y }
    }

    /**
     * Gets the bounding region of the scroll bar on screen.
     *
     * @param bitmap Optional non-cropped bitmap to detect the scroll bar. Providing this avoids a new screenshot.
     * @return A pair containing the scrollbar BoundingBox and its thumb component.
     */
    fun getListScrollBarBoundingRegion(bitmap: Bitmap? = null): Pair<BoundingBox?, BoundingBox?> {
        val result: Pair<BoundingBox?, BoundingBox?> =
            game.imageUtils.detectScrollBar(
                bitmap = bitmap,
                region = bboxScrollBarRegionDefault,
                minArea = bboxScrollBarMinArea,
                maxArea = bboxScrollBarMaxArea,
                morphCloseKernelSize = 10,
            )

        val tmpBar: BoundingBox? = result.first
        val tmpThumb: BoundingBox? = result.second

        val bboxScrollBar: BoundingBox? =
            if (tmpBar == null) {
                null
            } else {
                // Add original region offsets to results.
                BoundingBox(
                    x = bboxScrollBarRegionDefault.x + tmpBar.x,
                    y = bboxScrollBarRegionDefault.y + tmpBar.y,
                    w = tmpBar.w,
                    h = tmpBar.h,
                )
            }

        val bboxThumb: BoundingBox? =
            if (tmpThumb == null) {
                null
            } else {
                BoundingBox(
                    x = bboxScrollBarRegionDefault.x + tmpThumb.x,
                    y = bboxScrollBarRegionDefault.y + tmpThumb.y,
                    w = tmpThumb.w,
                    h = tmpThumb.h,
                )
            }

        // Set a flag so we know if this list is scrollable.
        // We only ever set this to true. If we detect a scrollbar once, then the list will always be scrollable even if we fail to detect a scrollbar later.
        if (bboxScrollBar != null) {
            bIsScrollable = true
        }

        return Pair(bboxScrollBar, bboxThumb)
    }

    /**
     * Stops list inertia by clicking a safe location, so the list is not still drifting when it is read.
     *
     * The "safe" zone is the list's left edge, which is only safe on a list whose rows leave a margin there. It is not safe on every list: on the Umamusume Details skill grid the rows are
     * tappable right up to the panel edge, and a tap here opens the Skill Details popup for whichever skill it lands on. A list built with `bAllowInertiaTap = false` therefore waits the
     * inertia out instead of tapping it away - see [createDialogScrollList].
     *
     * @param bboxSafeZone Optional region for safe clicks.
     */
    private fun stopScrolling(bboxSafeZone: BoundingBox? = null) {
        if (!bAllowInertiaTap) {
            // Nowhere on this list is safe to tap, so let the inertia die down on its own.
            game.wait(0.5, skipWaitingForLoading = true)
            return
        }
        val bboxSafeZone: BoundingBox =
            bboxSafeZone ?: BoundingBox(
                x = bboxEntries.x,
                y = bboxEntries.y,
                w = 1,
                h = bboxEntries.h,
            )
        // Define tap region.
        val x0: Int = game.imageUtils.relX(bboxSafeZone.x.toDouble(), 0)
        val x1: Int = game.imageUtils.relX(bboxSafeZone.x.toDouble(), bboxSafeZone.w)
        val y0: Int = game.imageUtils.relY(bboxSafeZone.y.toDouble(), 0)
        val y1: Int = game.imageUtils.relY(bboxSafeZone.y.toDouble(), bboxSafeZone.h)

        // Select random tap point.
        val x: Double = (x0..x1).random().toDouble()
        val y: Double = (y0..y1).random().toDouble()

        // Execute tap.
        game.tap(x, y, taps = 1, ignoreWaiting = true)
        // Wait for list stabilization and animation to clear.
        game.wait(0.2, skipWaitingForLoading = true)
    }

    /**
     * Scrolls to the top of the list by dragging the scrollbar thumb, so it moves nothing on a list whose scrollbar only reports the position - see [isScrollBarDraggable]. Prefer
     * [scrollToTopBySwiping], which works either way.
     *
     * @param bitmap Optional source bitmap to use when detecting scrollbar.
     */
    private fun scrollToTop(bitmap: Bitmap? = null) {
        val bboxThumb: BoundingBox? = getListScrollBarBoundingRegion().second
        if (!bIsScrollable && !swipeMode) {
            MessageLog.d(TAG, "[DEBUG] scrollToTop:: List is not scrollable.")
            return
        }

        if (bboxThumb == null) {
            MessageLog.d(TAG, "[DEBUG] scrollToTop:: No scrollbar thumb detected. Falling back to lazy scrolling.")
            game.gestureUtils.swipe(
                (bboxList.x + (bboxList.w / 2)).toFloat(),
                (bboxList.y + (bboxList.h / 2)).toFloat(),
                (bboxList.x + (bboxList.w / 2)).toFloat(),
                // High value here ensures we go all the way to top of list.
                // We can't use this method in [scrollToBottom] since negative Y values aren't allowed by gestureUtils.
                (bboxList.y + (bboxList.h * 1000)).toFloat(),
            )
            stopScrolling()
        } else {
            game.gestureUtils.swipe(
                (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
                (bboxThumb.y + (bboxThumb.h.toFloat() / 2.0)).toFloat(),
                (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
                bboxList.y.toFloat(),
                duration = 1500L,
            )
        }

        // Small delay for list to stabilize.
        game.wait(1.0, skipWaitingForLoading = true)
    }

    /**
     * Scrolls to the bottom of the list. Like [scrollToTop], this drags the scrollbar thumb, so it moves nothing on a list whose scrollbar only reports the position.
     *
     * @param bitmap Optional source bitmap to use when detecting scrollbar.
     */
    private fun scrollToBottom(bitmap: Bitmap? = null) {
        val bboxThumb: BoundingBox? = getListScrollBarBoundingRegion().second
        if (!bIsScrollable && !swipeMode) {
            MessageLog.d(TAG, "[DEBUG] scrollToBottom:: List is not scrollable.")
            return
        }

        if (bboxThumb == null) {
            MessageLog.d(TAG, "[DEBUG] scrollToBottom:: No scrollbar thumb detected. Falling back to lazy scrolling.")
            for (i in 0 until 20) {
                scrollDown(durationMs = 250L)
            }
            stopScrolling()
        } else {
            game.gestureUtils.swipe(
                (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
                (bboxThumb.y + (bboxThumb.h.toFloat() / 2.0)).toFloat(),
                (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
                (bboxList.y + bboxList.h).toFloat(),
                duration = 1500L,
            )
        }

        // Small delay for list to stabilize.
        game.wait(1.0, skipWaitingForLoading = true)
    }

    /**
     * Scrolls to a specific percentage. Requires a detectable scrollbar.
     *
     * @param percent The list percentage (0-100) to scroll to.
     * @return True if scroll operation was attempted.
     */
    private fun scrollToPercent(percent: Int): Boolean {
        val percent: Int = percent.coerceIn(0, 100)

        val bboxes: Pair<BoundingBox?, BoundingBox?> = getListScrollBarBoundingRegion()
        if (!bIsScrollable) {
            MessageLog.d(TAG, "[DEBUG] scrollToPercent:: List is not scrollable.")
            return false
        }

        val bboxBar: BoundingBox? = bboxes.first
        val bboxThumb: BoundingBox? = bboxes.second

        if (bboxBar == null) {
            MessageLog.w(TAG, "[WARN] scrollToPercent:: Failed to detect scrollbar.")
            return false
        }

        if (bboxThumb == null) {
            MessageLog.d(TAG, "[DEBUG] scrollToPercent:: Failed to detect scrollbar thumb.")
            return false
        }

        val targetY: Int = bboxBar.y + (bboxBar.h.toDouble() * (percent.toDouble() / 100.0)).toInt()

        game.gestureUtils.swipe(
            (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
            (bboxThumb.y + (bboxThumb.h.toFloat() / 2.0)).toFloat(),
            (bboxThumb.x + (bboxThumb.w.toFloat() / 2.0)).toFloat(),
            targetY.toFloat(),
            duration = 1500L,
        )

        // Small delay for list to stabilize.
        game.wait(1.0, skipWaitingForLoading = true)

        return true
    }

    /**
     * Scrolls down the list.
     *
     * @param startLoc Optional swipe start location. Defaults to list center.
     * @param entryHeight Optional entry height to determine scroll distance.
     * @param durationMs Swipe duration. Minimum 250ms for Accessibility Service registration.
     */
    fun scrollDown(startLoc: Point? = null, entryHeight: Int = 0, durationMs: Long = 250L) {
        if (!bIsScrollable && !swipeMode) {
            MessageLog.d(TAG, "[DEBUG] scrollDown:: List is not scrollable.")
            return
        }

        val durationMs: Long = durationMs.coerceAtLeast(250L)
        val x0: Int = if (swipeMode) (bboxList.x + (bboxList.w / 2)) else ((startLoc?.x ?: (bboxList.x + (bboxList.w / 2)))).toInt()
        val y0: Int = ((startLoc?.y ?: (bboxList.y + (bboxList.h / 2)))).toInt()
        // Add some extra height since scrolling isn't accurate.
        val y1: Int = (bboxList.y - entryHeight).toInt().coerceAtLeast(0)
        game.gestureUtils.swipe(x0.toFloat(), y0.toFloat(), x0.toFloat(), y1.toFloat(), duration = durationMs)
        stopScrolling()
    }

    /**
     * Scrolls up the list.
     *
     * @param startLoc Optional swipe start location. Defaults to list center.
     * @param entryHeight Optional entry height to determine scroll distance.
     * @param durationMs Swipe duration. Minimum 250ms for Accessibility Service registration.
     */
    fun scrollUp(startLoc: Point? = null, entryHeight: Int = 0, durationMs: Long = 250L) {
        if (!bIsScrollable && !swipeMode) {
            MessageLog.d(TAG, "[DEBUG] scrollUp:: List is not scrollable.")
            return
        }

        val durationMs: Long = durationMs.coerceAtLeast(250L)
        val x0: Int = if (swipeMode) (bboxList.x + (bboxList.w / 2)) else ((startLoc?.x ?: (bboxList.x + (bboxList.w / 2)))).toInt()
        val y0: Int = ((startLoc?.y ?: (bboxList.y + (bboxList.h / 2)))).toInt()
        // Add some extra height since scrolling isn't accurate.
        val y1: Int = (bboxList.y + bboxList.h + (entryHeight * 1.5)).toInt().coerceAtLeast(0)
        game.gestureUtils.swipe(x0.toFloat(), y0.toFloat(), x0.toFloat(), y1.toFloat(), duration = durationMs)
        stopScrolling()
    }

    /**
     * Whether this list has a scrollbar, and so has anything below the fold at all.
     *
     * @return True when a scrollbar was detected.
     */
    fun hasScrollBar(): Boolean = getListScrollBarBoundingRegion().first != null

    /**
     * Whether this list's scrollbar can be dragged to scroll it, or only reports the position. Drags the thumb away from wherever it sits and checks whether it actually moved, so it leaves the
     * list scrolled to one end.
     *
     * @return True when dragging the thumb moved it.
     */
    fun isScrollBarDraggable(): Boolean {
        val (bboxBar: BoundingBox?, bboxThumb: BoundingBox?) = getListScrollBarBoundingRegion()
        if (bboxBar == null || bboxThumb == null) return false
        // Drag towards whichever end the thumb is further from, so there is always room for it to move.
        if (bboxThumb.y - bboxBar.y <= SCROLL_TOP_TOLERANCE) scrollToBottom() else scrollToTop()
        val bboxThumbAfter: BoundingBox = getListScrollBarBoundingRegion().second ?: return false
        return bboxThumbAfter.y != bboxThumb.y
    }

    /**
     * Swipes the list's content down by a set distance, leaving the scrollbar alone.
     *
     * [scrollDown] always ends its swipe at the top of the list, so starting only [travelPx] below the top is what keeps the travel short. Short and overlapping is usually what a caller wants:
     * a full-height swipe flings the list into settling mid-row, which straddles the crops of any reader working from fixed row offsets.
     *
     * @param travelPx How far the content should travel, in screen pixels.
     * @param durationMs Swipe duration. Longer is slower and flings less.
     */
    fun scrollContentDownBy(travelPx: Int, durationMs: Long = 700L) {
        val startLoc = Point((bboxList.x + (bboxList.w / 2)).toDouble(), (bboxList.y + travelPx).toDouble())
        scrollDown(startLoc = startLoc, durationMs = durationMs)
    }

    /**
     * Scrolls to the top of the list by swiping its content, watching the scrollbar to know when it has arrived.
     *
     * [scrollToTop] drags the scrollbar thumb, which only moves a list whose scrollbar is interactive. The Umamusume Details dialog draws one that is not - see [isScrollBarDraggable] - so that
     * path silently leaves the list wherever it already was. This swipes the content instead and reads the thumb purely as a position sensor, stopping once it reaches the top of its track.
     */
    fun scrollToTopBySwiping() {
        for (swipes in 0 until MAX_SCROLL_TO_TOP_SWIPES) {
            val (bboxBar: BoundingBox?, bboxThumb: BoundingBox?) = getListScrollBarBoundingRegion()
            if (bboxBar == null || bboxThumb == null) {
                MessageLog.d(TAG, "[DEBUG] scrollToTopBySwiping:: No scrollbar found, so the list has nothing below the fold and is already at its top.")
                return
            }
            if (bboxThumb.y - bboxBar.y <= SCROLL_TOP_TOLERANCE) {
                MessageLog.d(TAG, "[DEBUG] scrollToTopBySwiping:: The scrollbar thumb is at the top of its track after $swipes swipe(s).")
                return
            }
            scrollUp()
        }
        MessageLog.w(TAG, "[WARN] scrollToTopBySwiping:: Gave up after $MAX_SCROLL_TO_TOP_SWIPES swipes without the scrollbar thumb reaching the top of its track.")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Scrolls through the list and executes a callback for each entry.
     *
     * @param maxTimeMs Maximum processing time before timeout.
     * @param bScrollBottomToTop If true, process from bottom to top.
     * @param keyExtractor Optional callback to generate unique keys and skip duplicates.
     * @param entryComponent Optional component to use for identifying rows.
     * @param bForceComponentDetection If true, force component-based detection even if the scroll list region is found.
     * @param onEntry Callback executed for each entry. Return true to exit early.
     * @return True if processing completed or exited early via callback.
     */
    fun process(
        maxTimeMs: Int = MAX_PROCESS_TIME_DEFAULT_MS,
        bScrollBottomToTop: Boolean = false,
        keyExtractor: ((ScrollListEntry) -> String?)? = null,
        entryComponent: ComponentInterface? = null,
        bForceComponentDetection: Boolean = false,
        onEntry: OnEntryDetectedCallback,
    ): Boolean {
        var bitmap = game.imageUtils.getSourceBitmap()

        if (bScrollBottomToTop) scrollToBottom(bitmap) else scrollToTop(bitmap)

        // Max time limit for the while loop to scroll through the list.
        val startTime: Long = System.currentTimeMillis()
        val maxTimeMs: Long = 60000

        // Track bounding boxes for average entry height calculation.
        val entryBboxes: MutableList<BoundingBox> = mutableListOf()
        // Y position for termination check.
        var prevThumbY: Int? = null

        // Stores keys from the previous frame to identify the overlap with the current frame.
        var lastFrameKeys: List<String> = emptyList()

        // Swipe-mode termination: stop after consecutive frames with no new entries, bounded by a hard scroll cap.
        var consecutiveNoNewFrames = 0
        val maxNoNewFrames = 2
        var scrollCount = 0
        val maxScrollCount = 30

        var index = 0
        while (System.currentTimeMillis() - startTime < maxTimeMs) {
            var currentFrameEntries: List<ScrollListEntry> = emptyList()
            var retries = 3
            while (retries > 0) {
                bitmap = game.imageUtils.getSourceBitmap()
                val detectedEntries = detectEntries(bitmap, entryComponent, bForceComponentDetection)

                if (detectedEntries.isNotEmpty()) {
                    currentFrameEntries =
                        if (bScrollBottomToTop) {
                            // If scrolling bottom to top, reverse the entries to process them in meaningful order.
                            // Entries from detectEntries() are always sorted top to bottom on the screen.
                            detectedEntries.reversed().map { entry ->
                                // Assign a unique increasing index to each detected entry.
                                entry.copy(index = index++)
                            }
                        } else {
                            detectedEntries.map { entry ->
                                // Assign a unique increasing index to each detected entry.
                                entry.copy(index = index++)
                            }
                        }
                    break
                }

                retries--
                if (retries > 0) {
                    MessageLog.d(TAG, "[DEBUG] process:: No entries detected. Retrying ($retries left)...")
                    game.wait(0.2, skipWaitingForLoading = true)
                }
            }

            if (currentFrameEntries.isEmpty()) {
                MessageLog.d(TAG, "[DEBUG] process:: No entries detected in current frame after retries.")
                // An empty frame (after retries) is itself a no-new-content signal in swipe mode, so an empty list terminates instead of swiping to the hard cap.
                if (swipeMode) consecutiveNoNewFrames++
            } else {
                // Determine the overlap with the previous frame's entries using the provided keyExtractor.
                var skipCount = 0
                if (keyExtractor != null && lastFrameKeys.isNotEmpty()) {
                    val currentFrameKeys = currentFrameEntries.map { keyExtractor(it) ?: entrySignature(it.bitmap) }

                    // Find the largest suffix of lastFrameKeys that matches a prefix of currentFrameKeys.
                    for (i in lastFrameKeys.size.coerceAtMost(currentFrameKeys.size) downTo 1) {
                        val suffix = lastFrameKeys.takeLast(i)
                        val prefix = currentFrameKeys.take(i)
                        if (suffix == prefix) {
                            skipCount = i
                            break
                        }
                    }

                    if (game.debugMode && skipCount > 0) {
                        MessageLog.d(TAG, "[DEBUG] process:: Identified overlap of $skipCount items between frames. Matching sequence: ${currentFrameKeys.take(skipCount).joinToString(", ")}")
                    }
                }

                // In swipe mode, a frame whose entries are all duplicates means no new content scrolled in.
                if (swipeMode) {
                    if (currentFrameEntries.size - skipCount <= 0) consecutiveNoNewFrames++ else consecutiveNoNewFrames = 0
                }

                // Process only the new entries that weren't part of the overlap.
                for (i in skipCount until currentFrameEntries.size) {
                    val entry = currentFrameEntries[i]
                    if (onEntry.onEntryDetected(this, entry)) {
                        MessageLog.d(TAG, "[DEBUG] process:: onEntry callback returned TRUE for entry ${entry.index}. Exiting loop.")
                        return true
                    }
                }

                // Update the last frame's keys for the next iteration's overlap detection.
                if (keyExtractor != null) {
                    lastFrameKeys = currentFrameEntries.map { keyExtractor(it) ?: entrySignature(it.bitmap) }
                }
            }

            entryBboxes.addAll(currentFrameEntries.map { it.bbox })
            val avgEntryHeight: Int = if (entryBboxes.isEmpty()) 0 else entryBboxes.map { it.h }.average().toInt()
            val scrollStartLoc: Point? = if (currentFrameEntries.isEmpty()) null else Point(bboxEntries.x.toDouble(), currentFrameEntries.last().bbox.y.toDouble())

            if (bIsScrollable || swipeMode) {
                if (bScrollBottomToTop) {
                    scrollUp(startLoc = scrollStartLoc, entryHeight = avgEntryHeight)
                } else {
                    scrollDown(startLoc = scrollStartLoc, entryHeight = avgEntryHeight)
                }
                scrollCount++
                // Slight delay to allow screen to settle before next iteration.
                game.wait(0.5, skipWaitingForLoading = true)
            } else {
                MessageLog.d(TAG, "[DEBUG] process:: List is not scrollable. Exiting loop.")
                return true // Return true since we processed the only frame.
            }

            if (swipeMode) {
                // Swipe mode has no scrollbar to consult, so terminate on a content signal or the hard scroll cap.
                if (consecutiveNoNewFrames >= maxNoNewFrames) {
                    MessageLog.d(TAG, "[DEBUG] process:: No new entries for $maxNoNewFrames frames. Exiting loop (swipe mode).")
                    return true
                }
                if (scrollCount >= maxScrollCount) {
                    MessageLog.d(TAG, "[DEBUG] process:: Reached max scroll count of $maxScrollCount. Exiting loop (swipe mode).")
                    return true
                }
            } else {
                // SCROLLBAR CHANGE DETECTION LOGIC
                // Breaks loop if no change to Y position or no scrollbar detected.
                val bboxThumb: BoundingBox? = getListScrollBarBoundingRegion().second
                if (bboxThumb == null) {
                    MessageLog.d(TAG, "[DEBUG] process:: No scrollbar thumb detected. Exiting loop.")
                    return true
                }

                // If the scrollbar hasn't changed after scrolling, that means we've reached the end of the list.
                if (prevThumbY != null && bboxThumb.y == prevThumbY) {
                    MessageLog.d(TAG, "[DEBUG] process:: Reached end of scroll list. Exiting loop.")
                    return true
                }

                prevThumbY = bboxThumb.y
            }
        }

        MessageLog.e(TAG, "[ERROR] process:: Timed out.")
        return false
    }
}
