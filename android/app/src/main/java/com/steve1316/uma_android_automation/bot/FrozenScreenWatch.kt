package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.utils.isSameGrayPicture

/** How long the screen can stay the same while no known screen is found before the game is treated as frozen. */
internal const val FROZEN_SCREEN_LIMIT_MS = 60_000L

/** How far a gray pixel must move, 0-255, to count as changed. Matches the still-screen checks in Trackblazer. */
private const val FROZEN_SCREEN_DIFF_LEVEL = 25

/**
 * Tells when the game stopped drawing new frames. Normal stretches of unknown screens, like cutscenes and concerts, keep moving. A frozen game
 * keeps showing its last frame, and taps on it do nothing. Each frame is compared with the first frame of the current still stretch, so a slow
 * drift still counts as movement.
 *
 * @param limitMs How long the screen can stay the same before it counts as frozen.
 */
class FrozenScreenWatch(private val limitMs: Long = FROZEN_SCREEN_LIMIT_MS) {
    /** The gray pixels the current still stretch started with, or null before the first frame. */
    private var reference: IntArray? = null

    /** When the current still stretch started, in milliseconds. */
    private var stillSinceMs: Long = 0L

    /**
     * Records one frame and says whether the screen has stayed the same for the whole limit.
     *
     * @param gray The frame's gray pixels, as `screenGray` gives them.
     * @param nowMs The current time in milliseconds.
     * @return True when the screen has not changed for at least the limit.
     */
    fun onFrame(gray: IntArray, nowMs: Long): Boolean {
        val ref = reference
        if (ref == null || !isSameGrayPicture(ref, gray, FROZEN_SCREEN_DIFF_LEVEL, fitContrast = false)) {
            reference = gray
            stillSinceMs = nowMs
            return false
        }
        return nowMs - stillSinceMs >= limitMs
    }

    /**
     * Forgets the still stretch, as after a known screen or a resume.
     */
    fun reset() {
        reference = null
    }
}
