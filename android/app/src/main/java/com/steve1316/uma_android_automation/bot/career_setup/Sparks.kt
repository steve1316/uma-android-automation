package com.steve1316.uma_android_automation.bot.career_setup

import android.graphics.Bitmap
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.LabelCareerStartSparkAptitude
import com.steve1316.uma_android_automation.components.LabelCareerStartSparkStat
import com.steve1316.uma_android_automation.components.LabelCareerStartSparkUnique
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Smart spark labels

/** Spark labels for each aptitude key in the trainee catalog. */
private val APTITUDE_LABELS: Map<String, String> =
    mapOf(
        "turf" to "TURF",
        "dirt" to "DIRT",
        "sprint" to "SPRNT",
        "mile" to "MILE",
        "medium" to "MED",
        "long" to "LONG",
        "front" to "FRONT",
        "pace" to "PACE",
        "late" to "LATE",
        "end" to "END",
    )

/** Spark labels for each stat key in the trainee catalog. */
private val STAT_LABELS: Map<String, String> = mapOf("speed" to "SPD", "stamina" to "STA", "power" to "PWR", "guts" to "GUTS", "wit" to "WIT")

/** Surface aptitude keys in the trainee catalog. */
private val SURFACES = listOf("turf", "dirt")

/** Distance aptitude keys in the trainee catalog. */
private val DISTANCES = listOf("sprint", "mile", "medium", "long")

/** Running style aptitude keys in the trainee catalog. */
private val STYLES = listOf("front", "pace", "late", "end")

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Badge geometry and colors, measured at 1080x1920

/** A badge's width. Each badge has the label on top and the three stars below it. */
private const val BADGE_W = 62

/** A badge's height. */
private const val BADGE_H = 49

/** The smallest width a colored blob may have to count as a badge. */
private const val BADGE_MIN_W = 55

/** The smallest height a colored blob may have to count as a badge. */
private const val BADGE_MIN_H = 46

/** The largest width or height a colored blob may have to count as a badge. */
private const val BADGE_MAX_SIZE = 70

/** The label window matched against the label templates. */
private const val LABEL_WINDOW_H = 32

/** How far below the badge's top the star strip starts. */
private const val STARS_DY = 31

/** The star strip's height. */
private const val STARS_H = 17

/** The three badges of one card sit 64px apart. */
private const val BADGE_PITCH = 64

/** A star slot counts as lit when this share of it is gold. */
private const val LIT_STAR_SHARE = 0.18

/** HSV ranges (OpenCV scale) for the three badge colors. */
private val BADGE_COLORS: Map<SparkType, Pair<Scalar, Scalar>> =
    mapOf(
        SparkType.STAT to (Scalar(92.0, 120.0, 170.0) to Scalar(110.0, 255.0, 255.0)),
        SparkType.APTITUDE to (Scalar(158.0, 90.0, 170.0) to Scalar(176.0, 255.0, 255.0)),
        SparkType.UNIQUE to (Scalar(33.0, 140.0, 120.0) to Scalar(52.0, 255.0, 255.0)),
    )

/** HSV range for a lit gold star. */
private val GOLD_LOWER = Scalar(15.0, 150.0, 200.0)
private val GOLD_UPPER = Scalar(35.0, 255.0, 255.0)

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Spark types

/** The three spark colors shown under a legacy card. */
enum class SparkType {
    /** Blue spark for a base stat (SPD, STA, PWR, GUTS, WIT). */
    STAT,

    /** Pink spark for a track, distance or style aptitude. */
    APTITUDE,

    /** Green spark for the trainee's unique skill. */
    UNIQUE,
}

/** The affinity symbol shown at the top of Legacy Select, with the score bonus it is worth. */
enum class Affinity(
    /** Points added to a candidate's spark score for this affinity. */
    val bonus: Int,
) {
    DOUBLE_CIRCLE(6),
    CIRCLE(3),
    TRIANGLE(1),
    NONE(0),
}

/** One spark read off a legacy card. */
data class Spark(
    /** The spark color. */
    val type: SparkType,
    /** The badge label such as "SPD", "MILE" or "UNIQ". */
    val label: String,
    /** The number of lit stars, 1-3. */
    val stars: Int,
)

/** A legacy card found while scanning the Veteran or Guests list. */
data class LegacyCandidate(
    /** The card's position in scan order, used to find it again. */
    val index: Int,
    /** True if the card came from the Guests tab. */
    val isGuest: Boolean,
    /** The sparks read off the card. */
    val sparks: List<Spark>,
    /** The affinity read after tapping the card, or null if it was never tapped. */
    val affinity: Affinity? = null,
    /** False for a guest whose borrow would cost monies. */
    val isFree: Boolean = true,
)

/** One legacy card's sparks and where it sits on screen. */
data class SparkCard(
    /** Left edge of the card's blue badge. */
    val badgeX: Int,
    /** Top edge of the card's badge row. */
    val badgeY: Int,
    /** The sparks in badge order. */
    val sparks: List<Spark>,
)
