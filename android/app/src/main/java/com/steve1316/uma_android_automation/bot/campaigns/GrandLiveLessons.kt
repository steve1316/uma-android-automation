package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Token-gain geometry (Training screen)

/** The five Performance-Point token labels, top-to-bottom, as shown on the Performance Points panel. */
val GRAND_LIVE_TOKEN_LABELS: List<String> = listOf("Da", "Pa", "Vo", "Vi", "Co")

/** Vertical pitch between token rows, in reference (1080-wide) pixels. */
private const val TOKEN_ROW_STEP = 100

/**
 * A crop rectangle expressed as an offset from a matched anchor center (the Performance Points header or a lesson cost pill), in reference (1080-wide) pixels.
 *
 * @property dx X offset from the anchor center to the crop's left edge.
 * @property dy Y offset from the anchor center to the crop's top edge.
 * @property width Crop width.
 * @property height Crop height.
 */
data class TokenCrop(val dx: Int, val dy: Int, val width: Int, val height: Int)

/**
 * Value and gain crop offsets for a Performance-Point token, relative to the matched `grandlive_performance_points` center. Tune on device via the token-gain debug test.
 * The value crop is kept tall enough to hold the full digit height at the bottom (Co) row where the pitch drift is largest.
 *
 * @param tokenIndex 0-based token position (0 = Da ... 4 = Co).
 * @return A pair of (value crop, gain crop) offsets for that token row.
 */
fun tokenCrops(tokenIndex: Int): Pair<TokenCrop, TokenCrop> {
    val rowY = TOKEN_ROW_STEP * tokenIndex
    val value = TokenCrop(-10, 27 + rowY, 80, 58)
    val gain = TokenCrop(70, 30 + rowY, 120, 55)
    return value to gain
}

/**
 * Read one anchored number crop via the shared YOLO digit reader. Single home for the anchor-relative scaling math, shared by the
 * main-screen totals read, the per-facility gain read, and the token-gain debug test.
 *
 * @param imageUtils The image utils to read with.
 * @param sourceBitmap The screenshot to read from.
 * @param center The matched anchor center the crop offsets are relative to.
 * @param crop The crop offsets from the anchor.
 * @param requirePlus When true (gain crops), require a "+" glyph so an empty slot reads as 0 instead of a stray digit.
 * @param debugName Debug label for the OCR fallback path.
 * @return The parsed number, or null when nothing was read.
 */
fun readTokenNumber(imageUtils: CustomImageUtils, sourceBitmap: Bitmap, center: Point, crop: TokenCrop, requirePlus: Boolean, debugName: String): Int? =
    imageUtils.readNumberFromRegion(
        sourceBitmap,
        imageUtils.relX(center.x, crop.dx),
        imageUtils.relY(center.y, crop.dy),
        imageUtils.relWidth(crop.width),
        imageUtils.relHeight(crop.height),
        requirePlus = requirePlus,
        debugName = debugName,
    )

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Lessons card geometry (Lessons screen)

/** How far above a card's cost pill to look for its "Learnable" banner (the banner sits near the top of the card), in reference (1080-wide) pixels. Kept below the card pitch so a banner maps to exactly one card. */
const val LESSON_CARD_HEIGHT = 350

/** Match confidence for the "Learnable" banners. Kept high so a non-learnable card is never mistaken as purchasable. */
const val LESSON_BANNER_CONFIDENCE = 0.90

/**
 * Crop offsets for one Lessons card, all relative to the matched `grandlive_lesson_cost` ("Performance Point Cost") pill center, in reference (1080-wide) pixels.
 *
 * @property name The card's title-bar name region.
 * @property kind The card's kind-tag region ("Songs" / "Technique"), top-right of the title bar.
 * @property effect1 The first effect line region.
 * @property effect2 The second effect line region ("None" when the card has only one effect).
 */
data class LessonCardCrops(val name: TokenCrop, val kind: TokenCrop, val effect1: TokenCrop, val effect2: TokenCrop)

/** Fixed per-card crop geometry relative to the cost-pill anchor. Tune on device from the per-card scan log.
 * The effect crops are tall enough to hold an effect that wraps onto a second line (e.g. "Friendship Training Effectiveness" / "+10%"). ML Kit joins the lines. */
val LESSON_CARD_CROPS =
    LessonCardCrops(
        name = TokenCrop(-71, -315, 620, 45),
        kind = TokenCrop(630, -320, 200, 50),
        effect1 = TokenCrop(180, -245, 680, 95),
        effect2 = TokenCrop(180, -150, 680, 95),
    )

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Lessons purchase policy

/** Upper bound on purchases per Lessons visit, as a runaway guard on the buy-and-rescan loop. */
const val MAX_LESSON_PURCHASES_PER_VISIT = 20

/** A locked card counts as "sought after" (worth holding tokens for) when it matches a category ranked within this many top spots of the user's priority order. */
private const val SOUGHT_AFTER_TOP_RANKS = 2

/** Matches a raw stat-gain effect that leads with the stat (e.g. "Speed +5"), distinguishing it from passives like "Training Power Gain +1". */
private val RAW_STAT_GAIN_REGEX = Regex("^\\s*(speed|stamina|power|guts|wit)\\s*\\+?\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * The greyed-out warning the game overlays on a Song's effect line once the concert has passed. The effect crop picks it up instead of (or
 * appended to) the real effect, so it is stripped before the text is parsed. The leading "O" is the bullet glyph as ML Kit reads it.
 */
private val CONCERT_OVER_OVERLAY_REGEX = Regex("O?\\s*This bonus won't take effect.*", RegexOption.IGNORE_CASE)

/** Matches an effect line that OCR'd down to a bare percentage ("+10%"), losing the "Friendship Training Effectiveness" label in front of it. */
private val BARE_PERCENT_REGEX = Regex("(^|\\s)\\+\\s*\\d+\\s*%", RegexOption.IGNORE_CASE)

/**
 * Smallest raw stat gain that only ever appears on a Friendship Training Effectiveness Song. Techniques top out well below this, so a card
 * leading with a gain this large is a Training Effectiveness Song even when OCR lost the label.
 */
private const val TRAINING_EFFECTIVENESS_STAT_FLOOR = 20

/** Matches an Energy gain effect (e.g. "Energy +20"). */
private val ENERGY_GAIN_REGEX = Regex("energy\\s*\\+\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * Matches the resulting-energy readout on the Lessons purchase confirmation ("78 / 100"), capturing both the projected new energy and the
 * cap. The cap is captured rather than hardcoded because OCR misreads it often enough to matter (a "100" read as "104" used to make the
 * whole readout unparseable, which silently disabled the overflow guard).
 */
private val PROJECTED_ENERGY_REGEX = Regex("(\\d+)\\s*/\\s*(\\d+)")

/** Kind of learnable item on the Grand Live Lessons screen. */
enum class LessonKind { TECHNIQUE, SONG }

/**
 * An effect category a Lessons card can match. The user ranks these via the Lesson Effect Priority setting (Scenario Overrides), and
 * purchases are ordered by that ranking. A category left out of the ranking is ignored, so it is only bought when nothing ranked is learnable.
 *
 * @property displayName The user-facing name stored in the setting.
 */
enum class LessonEffectCategory(val displayName: String) {
    TRAINING_EFFECTIVENESS("Training Effectiveness"),
    TRAINING_GAIN("Training Gain"),
    SUPPORT_EVENTS("Support Events"),
    STAT_GAINS("Stat Gains"),
    SKILL_HINTS("Skill Hints"),
    ENERGY("Energy"),
    ;

    companion object {
        /**
         * Resolve a setting string back to its category.
         *
         * @param value The stored display name.
         * @return The matching category, or null for an unknown string.
         */
        fun fromDisplayName(value: String): LessonEffectCategory? = entries.firstOrNull { it.displayName.equals(value.trim(), ignoreCase = true) }
    }
}

/** Default ranked category order, mirroring the original hardcoded weights. Energy is deliberately unranked so it stays a last-resort purchase. */
val DEFAULT_LESSON_EFFECT_PRIORITY: List<LessonEffectCategory> =
    listOf(
        LessonEffectCategory.TRAINING_EFFECTIVENESS,
        LessonEffectCategory.TRAINING_GAIN,
        LessonEffectCategory.SUPPORT_EVENTS,
        LessonEffectCategory.STAT_GAINS,
        LessonEffectCategory.SKILL_HINTS,
    )

/**
 * One item (Technique or Song) on the Grand Live Lessons screen, read via the per-card cost-pill anchor.
 *
 * @property kind Whether the card is a Technique or a Song.
 * @property name The card's OCR'd title (e.g. "Go This Way").
 * @property effectText The card's OCR'd effect line(s), concatenated (raw casing is fine; scoring lowercases).
 * @property purchasable Whether the card shows a "Learnable" banner (affordable now). Purchasability comes from the banner, never from a token-cost comparison.
 * @property rowIndex The card's 0-based on-screen position (top-to-bottom), used to tap it.
 */
data class LessonOption(
    val kind: LessonKind,
    val name: String,
    val effectText: String,
    val purchasable: Boolean,
    val rowIndex: Int,
)

/**
 * A card's effect text reduced to what the purchase ordering compares on.
 *
 * @property ranks The user-ranked positions of the categories the card matched, ascending, compared lexicographically.
 * @property statTieBreak Value of the card's raw stat gain, higher is better, used only once [ranks] ties.
 */
private data class LessonEffectProfile(val ranks: List<Int>, val statTieBreak: Double)

/**
 * Strip the post-concert warning overlay the game paints over a Song's effect line. The effect crop reads it as trailing noise, which would
 * otherwise mask the real effect.
 *
 * @param effectText The card's raw OCR'd effect line(s).
 * @return The effect text with the overlay removed.
 */
private fun stripConcertOverOverlay(effectText: String): String = CONCERT_OVER_OVERLAY_REGEX.replace(effectText, "").trim()

/**
 * Parse a raw stat-gain effect ("Speed +5") into its stat and amount. Passive effects that merely mention a stat
 * ("Training Power Gain +1") do not match, since the stat must lead the effect.
 *
 * @param effectText The card's effect line.
 * @return The (stat, amount) pair, or null when the effect is not a raw stat gain.
 */
fun parseStatGain(effectText: String): Pair<StatName, Int>? = parseStatGainIn(stripConcertOverOverlay(effectText))

/**
 * Parse a raw stat gain from text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The (stat, amount) pair, or null when the effect is not a raw stat gain.
 */
private fun parseStatGainIn(cleaned: String): Pair<StatName, Int>? {
    val match = RAW_STAT_GAIN_REGEX.find(cleaned) ?: return null
    val stat = StatName.fromName(match.groupValues[1]) ?: return null
    val amount = match.groupValues[2].toIntOrNull() ?: return null
    return stat to amount
}

/**
 * Parse an Energy gain effect ("Energy +20").
 *
 * @param effectText The card's effect line(s).
 * @return The energy gain amount, or null when the card grants no energy.
 */
fun parseEnergyGain(effectText: String): Int? = ENERGY_GAIN_REGEX.find(stripConcertOverOverlay(effectText))?.groupValues?.get(1)?.toIntOrNull()

/**
 * The running style named in a skill-hint effect (e.g. "Pace Chaser" in "Skill Hint Lvl +3 (Pace Chaser)"). Returns null for a
 * non-hint effect or a hint tied to a distance / generic category (e.g. "(Long)") rather than a running style.
 *
 * @param effectText The card's effect line(s).
 * @return The referenced [RunningStyle], or null.
 */
fun parseHintRunningStyle(effectText: String): RunningStyle? {
    val t = effectText.lowercase()
    if ("hint" !in t) return null
    return RunningStyle.entries.firstOrNull { style -> style.name.lowercase().split("_").all { it in t } }
}

/**
 * Parse the projected new energy the confirmation dialog prints as "<new> / <cap>" when buying an Energy card.
 *
 * @param text The OCR'd text of the dialog's energy readout.
 * @return The projected new energy and the cap it was read against, or null when the readout could not be parsed.
 */
fun parseProjectedEnergy(text: String): Pair<Int, Int>? {
    val match = PROJECTED_ENERGY_REGEX.find(text) ?: return null
    val projected = match.groupValues[1].toIntOrNull() ?: return null
    val cap = match.groupValues[2].toIntOrNull() ?: return null
    return projected to cap
}

/**
 * Detect which effect categories a card's effect text matches. A card with two effect lines can match several.
 *
 * Training Effectiveness is also inferred when OCR lost its label, which happens often enough to matter: the effect crop routinely reads
 * the "+10%" cards down to a bare percentage, and the post-concert overlay can replace the line outright. A bare percentage and a raw stat
 * gain at or above [TRAINING_EFFECTIVENESS_STAT_FLOOR] both only ever occur on these Songs, so either one stands in for the missing label.
 *
 * @param effectText The card's effect line(s).
 * @return The matched categories (empty when nothing recognizable was read).
 */
fun detectLessonCategories(effectText: String): Set<LessonEffectCategory> = detectLessonCategoriesIn(stripConcertOverOverlay(effectText))

/**
 * Match categories against text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The matched categories (empty when nothing recognizable was read).
 */
private fun detectLessonCategoriesIn(cleaned: String): Set<LessonEffectCategory> {
    val t = cleaned.lowercase()
    val statGain = parseStatGainIn(cleaned)
    val categories = mutableSetOf<LessonEffectCategory>()
    val labelLost = BARE_PERCENT_REGEX.containsMatchIn(cleaned) || (statGain?.second ?: 0) >= TRAINING_EFFECTIVENESS_STAT_FLOOR
    if ("friendship" in t || "training effectiveness" in t || labelLost) categories.add(LessonEffectCategory.TRAINING_EFFECTIVENESS)
    if ("training" in t && "gain" in t) categories.add(LessonEffectCategory.TRAINING_GAIN)
    if (("support" in t && "event" in t) || "specialty priority" in t) categories.add(LessonEffectCategory.SUPPORT_EVENTS)
    if (statGain != null) categories.add(LessonEffectCategory.STAT_GAINS)
    if ("hint" in t || "skill point" in t) categories.add(LessonEffectCategory.SKILL_HINTS)
    if (ENERGY_GAIN_REGEX.containsMatchIn(cleaned)) categories.add(LessonEffectCategory.ENERGY)
    return categories
}

/**
 * Reduce a Lessons card's effect text to everything the purchase ordering needs. Computed once per card so the comparator never re-parses.
 *
 * Categories left out of the ranking are dropped, as is a Stat Gains match whose stat is absent from the stat prioritization - such a card
 * ranks below anything with a ranked effect, but is still bought when it is the only option.
 *
 * @param effectText The card's effect line(s).
 * @param statPriority The bot's ordered stat prioritization (index 0 = highest).
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @return The card's ranking profile.
 */
private fun profileLessonEffect(effectText: String, statPriority: List<StatName>, categoryOrder: List<LessonEffectCategory>): LessonEffectProfile {
    val cleaned = stripConcertOverOverlay(effectText)
    val statGain = parseStatGainIn(cleaned)
    val statRank = statGain?.let { statPriority.indexOf(it.first) } ?: -1
    val ranks =
        detectLessonCategoriesIn(cleaned)
            .filterNot { it == LessonEffectCategory.STAT_GAINS && statRank < 0 }
            .mapNotNull { categoryOrder.indexOf(it).takeIf { rank -> rank >= 0 } }
            .sorted()
    val statTieBreak = if (statGain != null && statRank >= 0) (statPriority.size - statRank).toDouble() + statGain.second * 0.001 else 0.0
    return LessonEffectProfile(ranks, statTieBreak)
}

/**
 * Compare two cards' matched ranks. Element-wise, the lower rank wins. When one card's ranks are a prefix of the other's, the card
 * matching more categories wins, so a strictly better card is never passed over.
 *
 * @param a The first card's ranks, ascending.
 * @param b The second card's ranks, ascending.
 * @return Negative when `a` ranks worse than `b`, positive when better, zero when equal.
 */
private fun compareLessonRanks(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        if (a[i] != b[i]) return b[i] - a[i]
    }
    return a.size - b.size
}

/**
 * Pick the best card, profiling each one exactly once. Category ranks decide first, then a Technique edges out a Song of equal effect
 * value, then the better raw stat gain, and finally the earlier row.
 *
 * @param options The cards to choose between.
 * @param statPriority The bot's ordered stat prioritization (index 0 = highest).
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @return The best card, or null when `options` is empty.
 */
private fun bestLesson(options: List<LessonOption>, statPriority: List<StatName>, categoryOrder: List<LessonEffectCategory>): LessonOption? =
    options
        .map { it to profileLessonEffect(it.effectText, statPriority, categoryOrder) }
        .maxWithOrNull(
            Comparator<Pair<LessonOption, LessonEffectProfile>> { a, b -> compareLessonRanks(a.second.ranks, b.second.ranks) }
                .thenBy { it.first.kind == LessonKind.TECHNIQUE }
                .thenBy { it.second.statTieBreak }
                .thenByDescending { it.first.rowIndex },
        )?.first

/**
 * Whether a card's effect is worth holding tokens for while it is locked: true when it matches a category ranked within the user's top
 * [SOUGHT_AFTER_TOP_RANKS] priority spots.
 *
 * @param effectText The card's effect line(s).
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @return True when the card is sought after.
 */
fun isSoughtAfter(effectText: String, categoryOrder: List<LessonEffectCategory>): Boolean =
    detectLessonCategories(effectText).any { category -> categoryOrder.indexOf(category).let { it in 0 until SOUGHT_AFTER_TOP_RANKS } }

/**
 * Choose the next Lessons purchase. We do not hoard tokens, so any affordable card is worth buying (spending tokens and refreshing
 * the static list to reveal new options). When `forceMaxHype` and Hype is not yet maxed, a Song is bought first (buying a Song raises the
 * Hype gauge). Otherwise the best-ranked card wins - see [bestLesson] for the ordering.
 *
 * @param options The affordable, learnable items currently on screen.
 * @param statPriority The bot's ordered stat prioritization, used to value raw stat-gain techniques.
 * @param forceMaxHype Whether the caller is at concert-day and wants Hype maxed before performing.
 * @param hypeMaxed Whether the Hype gauge is already full.
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @return The chosen option, or null when `options` is empty.
 */
fun chooseLessonPurchase(
    options: List<LessonOption>,
    statPriority: List<StatName>,
    forceMaxHype: Boolean,
    hypeMaxed: Boolean,
    categoryOrder: List<LessonEffectCategory> = DEFAULT_LESSON_EFFECT_PRIORITY,
): LessonOption? {
    if (forceMaxHype && !hypeMaxed) {
        val hypeSong = bestLesson(options.filter { it.kind == LessonKind.SONG }, statPriority, categoryOrder)
        if (hypeSong != null) return hypeSong
    }

    return bestLesson(options, statPriority, categoryOrder)
}
