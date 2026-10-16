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

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Scoring

/** Pure scoring for picking legacy parents from their sparks and affinity. */
object SparkScoring {
    /**
     * Scores a card's blue and pink sparks against the ranked wish list. The green unique spark only breaks ties, see [uniqueStars].
     *
     * @param sparks The sparks read off the card.
     * @param wishList Spark labels in priority order. The first entry is worth the most per star.
     * @returns The spark score.
     */
    fun sparkScore(sparks: List<Spark>, wishList: List<String>): Int {
        val ranks = wishList.map { normalize(it) }
        return sparks.filter { it.type != SparkType.UNIQUE }.sumOf { spark ->
            val rank = ranks.indexOf(normalize(spark.label))
            if (rank == -1) 0 else (ranks.size - rank) * spark.stars.coerceIn(0, 3)
        }
    }

    /**
     * Scores a candidate including its affinity bonus.
     *
     * @param candidate The card to score.
     * @param wishList Spark labels in priority order.
     * @returns The spark score plus the affinity bonus.
     */
    fun totalScore(candidate: LegacyCandidate, wishList: List<String>): Int = sparkScore(candidate.sparks, wishList) + (candidate.affinity?.bonus ?: 0)

    /**
     * Picks the cards worth tapping to read their affinity.
     *
     * @param candidates Every card found in the scan.
     * @param wishList Spark labels in priority order.
     * @param size How many cards to keep.
     * @returns The best cards by spark score, then unique stars, with remaining ties kept in scan order.
     */
    fun shortlist(candidates: List<LegacyCandidate>, wishList: List<String>, size: Int): List<LegacyCandidate> =
        candidates.sortedWith(compareByDescending<LegacyCandidate> { sparkScore(it.sparks, wishList) }.thenByDescending { uniqueStars(it) }).take(size)

    /**
     * Picks the best card for a slot.
     *
     * @param candidates The cards to choose from, usually the shortlist with affinity filled in.
     * @param wishList Spark labels in priority order.
     * @returns The highest scoring card that can be used, with unique stars breaking a tie, or null if none can. Paid guests are never chosen.
     */
    fun pick(candidates: List<LegacyCandidate>, wishList: List<String>): LegacyCandidate? =
        candidates.filter { !it.isGuest || it.isFree }.maxWithOrNull(compareBy<LegacyCandidate> { totalScore(it, wishList) }.thenBy { uniqueStars(it) })

    /**
     * Counts the stars of a card's green unique spark.
     *
     * @param candidate The card.
     * @returns 0 to 3.
     */
    private fun uniqueStars(candidate: LegacyCandidate): Int = candidate.sparks.filter { it.type == SparkType.UNIQUE }.sumOf { it.stars.coerceIn(0, 3) }

    /**
     * Normalizes a spark label for comparison.
     *
     * @param label The raw label.
     * @returns The label trimmed and uppercased.
     */
    private fun normalize(label: String): String = label.trim().uppercase()
}

/**
 * Derives the spark wish list for Smart Auto-Select from a trainee outfit's aptitudes and growth bonuses.
 *
 * Pink sparks come first, because raising a B aptitude to A matters more than a few stat points. Blue sparks follow.
 */
object SmartSparks {
    /**
     * Builds the ranked wish list for one outfit.
     *
     * @param aptitudes The outfit's grades keyed by "turf", "dirt", "sprint", "mile", "medium", "long", "front", "pace", "late" and "end".
     * @param growth The outfit's growth bonuses in percent keyed by "speed", "stamina", "power", "guts" and "wit".
     * @param hasLongGoal Whether one of the trainee's goal races is always a Long race. Ranks Stamina first when the outfit's Long grade is C or better.
     * @returns Spark labels, most wanted first.
     */
    fun wishList(aptitudes: Map<String, String>, growth: Map<String, Int>, hasLongGoal: Boolean = false): List<String> {
        val wanted = mutableListOf<String>()

        // A distance or surface graded B is one the trainee can use, so sparks that lift it to A pay off. C or lower is out of reach.
        (DISTANCES + SURFACES).filter { aptitudes[it] == "B" }.forEach { wanted.add(APTITUDE_LABELS.getValue(it)) }
        val bestStyle = STYLES.minByOrNull { rank(aptitudes[it]) }
        if (bestStyle != null && rank(aptitudes[bestStyle]) > rank("A")) wanted.add(APTITUDE_LABELS.getValue(bestStyle))

        // A Long goal race needs far more Stamina than a Speed-focused build trains, and failing it ends the career. The Senior Year inheritance lands two turns
        // before the first one (Tenno Sho (Spring) on Turn 56), so Stamina sparks pay off right when they are needed. A Long grade of D or worse cannot win it anyway.
        if (hasLongGoal && rank(aptitudes["long"]) <= rank("C")) wanted.add("STA")
        wanted.add("SPD")
        val bestDistance = DISTANCES.minByOrNull { rank(aptitudes[it]) }
        val bestDistanceRank = rank(aptitudes[bestDistance])
        if (DISTANCES.filter { rank(aptitudes[it]) == bestDistanceRank }.any { it == "medium" || it == "long" }) wanted.add("STA")
        growth.filter { it.value > 0 }.entries.sortedByDescending { it.value }.forEach { wanted.add(STAT_LABELS.getValue(it.key)) }
        wanted.add("PWR")
        return wanted.distinct()
    }

    /**
     * Ranks a grade so a lower number is better.
     *
     * @param grade The grade letter, or null when missing.
     * @returns The grade's position, with a missing grade ranked worst.
     */
    private fun rank(grade: String?): Int = grade?.let { Aptitude.fromName(it) }?.let { Aptitude.S.ordinal - it.ordinal } ?: Aptitude.entries.size
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Reading

/**
 * Reads the spark badges under each legacy card on the Legacy Select picker.
 *
 * Each badge is found by its solid color, its label by matching small label crops, and its stars by counting gold in each third of the star strip.
 *
 * @property imageUtils A reference to a CustomImageUtils instance.
 */
class SparkReader(private val imageUtils: CustomImageUtils) {
    /** A badge found by color. */
    private data class Badge(val type: SparkType, val x: Int, val y: Int)

    /**
     * Reads every card whose badges are fully inside the region.
     *
     * @param bitmap The screenshot.
     * @param region The area to read, as x, y, width and height.
     * @returns The cards in reading order. A card with any unreadable badge is left out, since it will be read again from another scroll position.
     */
    fun readCards(bitmap: Bitmap, region: IntArray): List<SparkCard> {
        val badges = findBadges(bitmap, region)
        val pitch = imageUtils.relWidth(BADGE_PITCH)
        val tolerance = imageUtils.relWidth(8)
        return badges
            .filter { it.type == SparkType.STAT }
            .mapNotNull { blue ->
                // A card is its blue badge plus the pink and optional green badge one and two pitches to the right.
                val row =
                    listOf(blue) +
                        listOf(SparkType.APTITUDE to 1, SparkType.UNIQUE to 2).mapNotNull { (type, step) ->
                            badges.firstOrNull { it.type == type && abs(it.x - (blue.x + pitch * step)) <= tolerance && abs(it.y - blue.y) <= tolerance }
                        }
                if (row.none { it.type == SparkType.APTITUDE }) return@mapNotNull null
                val sparks = row.map { readBadge(bitmap, it) ?: return@mapNotNull null }
                SparkCard(blue.x, blue.y, sparks)
            }.sortedWith(compareBy({ it.badgeY / imageUtils.relHeight(40) }, { it.badgeX }))
    }

    /**
     * Finds the badges in a region by color.
     *
     * @param bitmap The screenshot.
     * @param region The area to search, as x, y, width and height.
     * @returns Each badge with its top-left corner in screen coordinates.
     */
    private fun findBadges(bitmap: Bitmap, region: IntArray): List<Badge> {
        val (rx, ry, rw, rh) = region.toList()
        val crop = imageUtils.createSafeBitmap(bitmap, rx, ry, rw, rh, "sparkBadges") ?: return emptyList()
        val hsv = with(imageUtils) { crop.toHsvMat() }

        val minW = imageUtils.relWidth(BADGE_MIN_W)
        val maxW = imageUtils.relWidth(BADGE_MAX_SIZE)
        val minH = imageUtils.relHeight(BADGE_MIN_H)
        val maxH = imageUtils.relHeight(BADGE_MAX_SIZE)
        val badgeH = imageUtils.relHeight(BADGE_H)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val found = mutableListOf<Badge>()
        for ((type, range) in BADGE_COLORS) {
            val mask = Mat()
            Core.inRange(hsv, range.first, range.second, mask)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            for (i in 1 until count) {
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (w !in minW..maxW || h !in minH..maxH || area < 0.45 * w * h) continue
                // Anchor on the bottom edge, since an item icon touching the top of a badge can stretch its box upward.
                val x = rx + stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = ry + stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt() + h - badgeH
                found.add(Badge(type, x, y))
            }
            mask.release()
            labels.release()
            stats.release()
            centroids.release()
        }
        kernel.release()
        hsv.release()
        return found
    }

    /**
     * Reads one badge's label and stars.
     *
     * @param bitmap The screenshot.
     * @param badge The badge to read.
     * @returns The spark, or null if the label matched no template.
     */
    private fun readBadge(bitmap: Bitmap, badge: Badge): Spark? {
        val candidates: Map<String, ComponentInterface> =
            when (badge.type) {
                SparkType.STAT -> LabelCareerStartSparkStat
                SparkType.APTITUDE -> LabelCareerStartSparkAptitude
                SparkType.UNIQUE -> LabelCareerStartSparkUnique
            }
        val window = imageUtils.createSafeBitmap(bitmap, badge.x, badge.y, imageUtils.relWidth(BADGE_W), imageUtils.relHeight(LABEL_WINDOW_H), "sparkLabel") ?: return null
        val label = imageUtils.findBestTemplateMatch(window, candidates, BADGE_W, LABEL_WINDOW_H, minConfidence = 0.7) ?: return null
        return Spark(badge.type, label, countStars(bitmap, badge))
    }

    /**
     * Counts the lit stars under a badge.
     *
     * @param bitmap The screenshot.
     * @param badge The badge whose stars to count.
     * @returns 0 to 3.
     */
    private fun countStars(bitmap: Bitmap, badge: Badge): Int {
        val w = imageUtils.relWidth(BADGE_W - 4)
        val strip = imageUtils.createSafeBitmap(bitmap, badge.x + imageUtils.relWidth(2), badge.y + imageUtils.relHeight(STARS_DY), w, imageUtils.relHeight(STARS_H), "sparkStars") ?: return 0
        val hsv = with(imageUtils) { strip.toHsvMat() }
        val gold = Mat()
        Core.inRange(hsv, GOLD_LOWER, GOLD_UPPER, gold)
        val third = w / 3
        val lit =
            (0 until 3).count { k ->
                val slot = gold.submat(0, gold.rows(), k * third, (k + 1) * third)
                val share = Core.countNonZero(slot).toDouble() / (slot.rows() * slot.cols())
                slot.release()
                share > LIT_STAR_SHARE
            }
        listOf(hsv, gold).forEach { it.release() }
        return lit
    }
}
