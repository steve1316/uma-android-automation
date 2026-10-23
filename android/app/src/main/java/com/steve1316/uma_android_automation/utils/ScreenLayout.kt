package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager

/** Width of the 1080x1920 baseline every hardcoded coordinate was measured on. */
private const val BASELINE_WIDTH = 1080

/** Height of the 1080x1920 baseline every hardcoded coordinate was measured on. */
private const val BASELINE_HEIGHT = 1920

/** A channel value at or below this counts as black when checking the band the game leaves around a camera cutout. */
private const val BLACK_CHANNEL_MAX = 8

/**
 * Which screen edge a piece of UI is pinned to.
 *
 * On a screen taller than the baseline the game draws everything at the same size and only moves it. Each element keeps its distance to the edge it is
 * pinned to, so the anchor alone says where a baseline coordinate lands.
 */
enum class Anchor {
    /** Pinned to the top edge, like the energy bar and the stat column. It only moves when a cutout band pushes the whole game down. */
    TOP,

    /** Centered, like dialogs and the Career Setup panels. It moves by half of the extra height. */
    CENTER,

    /** Pinned to the bottom edge, like the training buttons and the menu bar. It moves by all of the extra height. */
    BOTTOM,
}

/**
 * Maps coordinates measured on the 1080x1920 baseline onto the screen the game is actually laid out on.
 *
 * Only screens 1080 pixels wide are mapped. Every other width keeps the proportional scaling `relHeight` already applies, so the mapping is the identity
 * there and on the baseline itself. Results stay in baseline pixels, so callers still pass them through `relX` / `relY` / `relHeight` as before.
 */
object ScreenLayout {
    /** Height of the black band the game leaves above itself around a camera cutout, in baseline pixels. */
    private var topInset: Int = 0

    /** Height the game lays out beyond the 1920 baseline below the cutout band, in baseline pixels. */
    private var extraHeight: Int = 0

    /** Whether the game is laid out taller than the 1920 baseline, so its UI has moved. */
    val isTall: Boolean get() = extraHeight > 0

    /**
     * Recomputes the layout for the current screen. Call it once per run, after the display size is known.
     *
     * @param displayWidth The screen width in pixels.
     * @param displayHeight The screen height in pixels.
     * @param topInset The height of the black band above the game, in pixels.
     */
    fun refresh(displayWidth: Int, displayHeight: Int, topInset: Int) {
        if (displayWidth != BASELINE_WIDTH) {
            this.topInset = 0
            extraHeight = 0
            return
        }

        this.topInset = topInset
        extraHeight = (displayHeight - topInset - BASELINE_HEIGHT).coerceAtLeast(0)
    }

    /**
     * How far UI with the given anchor sits below where it sat on the baseline.
     *
     * @param anchor The edge the UI is pinned to.
     * @returns The downward shift in baseline pixels.
     */
    fun shift(anchor: Anchor): Int =
        when (anchor) {
            Anchor.TOP -> topInset
            Anchor.CENTER -> topInset + extraHeight / 2
            Anchor.BOTTOM -> topInset + extraHeight
        }

    /**
     * Maps a baseline y-coordinate onto the current screen.
     *
     * @param baselineY The y-coordinate measured on the 1080x1920 baseline.
     * @param anchor The edge the UI at that coordinate is pinned to.
     * @returns The y-coordinate on the current layout, in baseline pixels.
     */
    fun y(baselineY: Int, anchor: Anchor): Int = baselineY + shift(anchor)

    /**
     * Maps a baseline region onto the current screen. Only its top edge moves, since the game does not resize UI on a taller screen.
     *
     * @param baselineRegion The (x, y, width, height) region measured on the 1080x1920 baseline. It is not modified.
     * @param anchor The edge the UI in that region is pinned to.
     * @param relativeTo The edge the element an offset region starts from is pinned to, or null for a region measured from the screen's top-left.
     * @returns A new region on the current layout, in baseline pixels.
     */
    fun region(baselineRegion: IntArray, anchor: Anchor, relativeTo: Anchor? = null): IntArray =
        baselineRegion.copyOf().also { it[1] += shift(anchor) - (relativeTo?.let { shift(it) } ?: 0) }

    /**
     * Measures the black band some phones leave above the game around a camera cutout.
     *
     * The display reports how tall the cutout is. The screenshot then confirms the game really left that band black, since a game drawn under the cutout
     * would not be pushed down at all.
     *
     * @param context The context used to read the display cutout.
     * @param takeScreenshot Takes a screenshot of the game. It only runs when the display reports a cutout.
     * @returns The height of the black band in pixels, or 0 if there is none.
     */
    fun detectTopInset(context: Context, takeScreenshot: () -> Bitmap): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val cutoutInset = display.cutout?.safeInsetTop ?: 0
        if (cutoutInset <= 0) return 0
        val sourceBitmap = takeScreenshot()
        if (cutoutInset >= sourceBitmap.height) return 0

        for (y in 0 until cutoutInset) {
            for (x in 0 until sourceBitmap.width step 8) {
                val pixel = sourceBitmap.getPixel(x, y)
                if (maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF) > BLACK_CHANNEL_MAX) return 0
            }
        }

        return cutoutInset
    }
}
