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

/**
 * How far above a card's cost pill to look for its "Learnable!" ribbon, in reference (1080-wide) pixels.
 * The ribbon sits 338px up and the cards are 408px apart, so 338 through 745 reaches this card's own ribbon and no other card's.
 */
const val LESSON_CARD_HEIGHT = 380

/**
 * Match confidence for the "Learnable!" ribbon. A real ribbon scores 0.956+ on both card colours and the best false positive is 0.36.
 * The margin is wide on purpose: the bot's own capture is not pixel-identical to an adb screenshot, and Technique cards ran the old 0.90 too close.
 */
const val LESSON_RIBBON_CONFIDENCE = 0.85

/**
 * Whether one of the matched "Learnable!" ribbons belongs to the card anchored at [anchorY], meaning that card is purchasable.
 *
 * @param ribbonYs The y centers of every ribbon matched on the screen.
 * @param anchorY The y center of this card's cost pill.
 * @param cardHeight How far above the pill to look, normally [LESSON_CARD_HEIGHT] scaled to the device.
 * @return True when a ribbon falls in this card's band.
 */
fun hasLearnableRibbon(ribbonYs: List<Double>, anchorY: Double, cardHeight: Int): Boolean = ribbonYs.any { it in (anchorY - cardHeight)..anchorY }

/**
 * Crop offsets for one Lessons card, all relative to the matched `grandlive_lesson_cost` ("Performance Point Cost") pill center, in reference (1080-wide) pixels.
 *
 * @property name The card's title-bar name region.
 * @property kind The card's kind-tag region ("Song" / "Technique"), top-right of the title bar.
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

/** The stat names as a regex alternation, derived from [StatName] so the gain patterns below cannot drift from the enum. */
private val STAT_ALTERNATION = StatName.entries.joinToString("|") { it.name.lowercase() }

/**
 * Matches a raw stat-gain effect (e.g. "Speed +5"), including a second one on a card that grants two ("Guts +6 Wit +6"). What separates these from a
 * passive like "Training Power Gain +1" is the amount following the stat directly, so the stat is matched at a word boundary rather than at the start of
 * the effect - anchoring at the start would read only the first stat and make the second invisible.
 */
private val RAW_STAT_GAIN_REGEX = Regex("(?:^|\\s)($STAT_ALTERNATION)\\s*\\+?\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * The greyed-out warning the game overlays on a Song's effect line once the concert has passed. The effect crop picks it up instead of (or
 * appended to) the real effect, so it is stripped before the text is parsed. The leading "O" is the bullet glyph as ML Kit reads it.
 */
private val CONCERT_OVER_OVERLAY_REGEX = Regex("O?\\s*This bonus won't take effect.*", RegexOption.IGNORE_CASE)

/**
 * Matches the percentage on a Friendship / Training Effectiveness effect, capturing it so the same pattern both recognizes the category and measures it.
 * A percentage is also how the category is recovered when OCR truncated the line to a bare "+10%", losing the label in front of it.
 */
private val EFFECTIVENESS_PERCENT_REGEX = Regex("\\+\\s*(\\d+)\\s*%")

/**
 * Smallest raw stat gain that only ever appears on a Friendship Training Effectiveness Song. Techniques top out well below this, so a card
 * leading with a gain this large is a Training Effectiveness Song even when OCR lost the label.
 */
private const val TRAINING_EFFECTIVENESS_STAT_FLOOR = 20

/** Matches an Energy gain effect (e.g. "Energy +20"). */
private val ENERGY_GAIN_REGEX = Regex("energy\\s*\\+\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * Matches a passive training-gain effect that names its stat ("Training Wit Gain +2"), which the leading-stat [RAW_STAT_GAIN_REGEX] deliberately refuses.
 * Only the stat tie-break reads this. It must never decide the Training Gain category, or every such card would start matching Stat Gains too.
 */
private val TRAINING_STAT_GAIN_REGEX = Regex("training\\s+($STAT_ALTERNATION)\\s+gain\\s*\\+?\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * Matches the amount on a training-gain effect even when OCR lost the stat word ("Training Gain +2"). Anchored on "gain" so it reads this effect's amount
 * and not a neighbouring line's, and kept as loose as the "training" plus "gain" keywords that recognize the category.
 */
private val TRAINING_GAIN_AMOUNT_REGEX = Regex("gain[^+]*\\+\\s*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * Matches the skill-point wording a Lessons card uses. The game always abbreviates it ("Skill Pts +22", "Training Skill Pt Gain +2") and never spells the
 * word out, so the original "skill point" keyword never fired and a card granting only skill points matched no category at all.
 */
private val SKILL_POINT_REGEX = Regex("skill\\s+p(?:ts?|oint)", RegexOption.IGNORE_CASE)

/**
 * Matches the amount on a skill-point or skill-hint effect ("Training Skill Pt Gain +3", "Skill Hint Lvl +2"), tolerating a lost leading word.
 * Anchored on the same keywords that recognize the category, so a card can never be read as a hint and then score no magnitude.
 */
private val SKILL_HINT_AMOUNT_REGEX = Regex("(?:hint|skill\\s+p(?:ts?|oint))[^+]*\\+\\s*(\\d+)", RegexOption.IGNORE_CASE)

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
 * A tag a skill-hint effect can name in parentheses (e.g. "Skill Hint Lvl +3 (Pace Chaser)"). The user ranks these via the Lesson Skill Hint Priority
 * setting (Scenario Overrides), which decides between two otherwise equal hint cards. An empty ranking leaves hint cards ordered as before.
 *
 * @property displayName The user-facing name stored in the setting.
 */
enum class LessonHintTag(val displayName: String) {
    FRONT_RUNNER("Front Runner"),
    PACE_CHASER("Pace Chaser"),
    LATE_SURGER("Late Surger"),
    END_CLOSER("End Closer"),
    SPRINT("Sprint"),
    MILE("Mile"),
    MEDIUM("Medium"),
    LONG("Long"),
    TURF("Turf"),
    DIRT("Dirt"),
    ;

    /** The display name's lowercased words, precomputed because every tag is scanned against every hint card. */
    private val lowerWords: List<String> = displayName.lowercase().split(" ")

    /** The running style this tag names, or null for a distance / surface tag. Read off the entry name, which matches [RunningStyle]'s. */
    val runningStyle: RunningStyle? get() = RunningStyle.fromName(name)

    /**
     * Whether an effect names this tag.
     *
     * @param lowercased The card's effect line(s), already lowercased.
     * @return True when every word of the display name appears.
     */
    fun matches(lowercased: String): Boolean = lowerWords.all { it in lowercased }

    companion object {
        /**
         * Resolve a setting string back to its tag.
         *
         * @param value The stored display name.
         * @return The matching tag, or null for an unknown string.
         */
        fun fromDisplayName(value: String): LessonHintTag? = entries.firstOrNull { it.displayName.equals(value.trim(), ignoreCase = true) }
    }
}

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
 * @property categoryValue How much of its best-ranked category the card grants (a "+2" gain, a "+10%" bonus), higher is better. Only ever compared between
 *   cards whose [ranks] already tied, which means they matched the same categories, so the value always compares like against like.
 * @property statTieBreak Value of the card's stat gain, higher is better, used only once the earlier tie-breaks tie.
 * @property hintTieBreak Value of the card's best user-ranked skill-hint tag, higher is better, zero when the card is not a ranked hint. Outranks
 *   [categoryValue], since Skill Hints mixes hint levels with skill-point totals and an explicit ranking beats comparing those two as numbers.
 */
private data class LessonEffectProfile(val ranks: List<Int>, val categoryValue: Double, val statTieBreak: Double, val hintTieBreak: Double)

/**
 * Strip the post-concert warning overlay the game paints over a Song's effect line. The effect crop reads it as trailing noise, which would
 * otherwise mask the real effect.
 *
 * @param effectText The card's raw OCR'd effect line(s).
 * @return The effect text with the overlay removed.
 */
private fun stripConcertOverOverlay(effectText: String): String = CONCERT_OVER_OVERLAY_REGEX.replace(effectText, "").trim()

/**
 * Parse the first raw stat-gain effect ("Speed +5") into its stat and amount. Passive effects that merely mention a stat ("Training Power Gain +1") do not
 * match, since a raw gain prints its amount directly after the stat.
 *
 * @param effectText The card's effect line.
 * @return The (stat, amount) pair, or null when the effect grants no raw stat gain.
 */
fun parseStatGain(effectText: String): Pair<StatName, Int>? = parseStatGains(effectText).firstOrNull()

/**
 * Parse every raw stat-gain effect on a card. A Technique can grant two ("Guts +6 Wit +6"), and scoring only the first would hide the second from the
 * stat prioritization entirely.
 *
 * @param effectText The card's effect line(s).
 * @return The (stat, amount) pairs in printed order, empty when the card grants no raw stat gain.
 */
fun parseStatGains(effectText: String): List<Pair<StatName, Int>> = parseStatGainsIn(stripConcertOverOverlay(effectText))

/**
 * Parse raw stat gains from text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The (stat, amount) pairs in printed order, empty when the card grants no raw stat gain.
 */
private fun parseStatGainsIn(cleaned: String): List<Pair<StatName, Int>> =
    RAW_STAT_GAIN_REGEX.findAll(cleaned).mapNotNull { match ->
        val stat = StatName.fromName(match.groupValues[1]) ?: return@mapNotNull null
        val amount = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        stat to amount
    }.toList()

/**
 * Parse a passive training-gain effect ("Training Wit Gain +2") into its stat and amount. This is the counterpart to [parseStatGain], which only accepts a
 * gain that leads the effect. A card matching here still belongs to Training Gain, never to Stat Gains.
 *
 * @param effectText The card's effect line(s).
 * @return The (stat, amount) pair, or null when the effect is not a named training gain.
 */
fun parseTrainingStatGain(effectText: String): Pair<StatName, Int>? = parseTrainingStatGainIn(stripConcertOverOverlay(effectText))

/**
 * Parse a named training gain from text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The (stat, amount) pair, or null when the effect is not a named training gain.
 */
private fun parseTrainingStatGainIn(cleaned: String): Pair<StatName, Int>? {
    val match = TRAINING_STAT_GAIN_REGEX.find(cleaned) ?: return null
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
fun parseEnergyGain(effectText: String): Int? = parseEnergyGainIn(stripConcertOverOverlay(effectText))

/**
 * Parse an Energy gain from text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The energy gain amount, or null when the card grants no energy.
 */
private fun parseEnergyGainIn(cleaned: String): Int? = ENERGY_GAIN_REGEX.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()

/**
 * The tags named in a skill-hint effect (e.g. "Pace Chaser" in "Skill Hint Lvl +3 (Pace Chaser)"). Returns empty for a non-hint effect or a hint whose tag
 * was not recognized, which OCR noise makes common enough that callers must treat empty as "unknown", never as "unwanted".
 *
 * @param effectText The card's effect line(s).
 * @return The referenced tags, in enum order.
 */
fun parseHintTags(effectText: String): Set<LessonHintTag> = parseHintTagsIn(stripConcertOverOverlay(effectText))

/**
 * Match hint tags against text the overlay has already been stripped from, so a caller that cleans once does not pay for it again.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The referenced tags, in enum order.
 */
private fun parseHintTagsIn(cleaned: String): Set<LessonHintTag> {
    val t = cleaned.lowercase()
    if ("hint" !in t) return emptySet()
    return LessonHintTag.entries.filterTo(linkedSetOf()) { it.matches(t) }
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
 * Everything one pass over a card's effect text yields, so neither the categories, the magnitudes, nor the comparator ever re-parse it.
 *
 * @property magnitudes The matched categories, each mapped to how much of it the card grants. Support Events is flat and Stat Gains is ordered by the
 *   stat prioritization instead, so both map to 0.0.
 * @property rawStatGains The card's raw stat gains ("Speed +26", or both of "Guts +6 Wit +6"), empty when it grants none.
 * @property trainingStatGain The card's named passive gain ("Training Wit Gain +2"), or null.
 */
private data class LessonEffectReading(
    val magnitudes: Map<LessonEffectCategory, Double>,
    val rawStatGains: List<Pair<StatName, Int>>,
    val trainingStatGain: Pair<StatName, Int>?,
)

/**
 * Detect which effect categories a card's effect text matches. A card with two effect lines can match several.
 *
 * @param effectText The card's effect line(s).
 * @return The matched categories (empty when nothing recognizable was read).
 */
fun detectLessonCategories(effectText: String): Set<LessonEffectCategory> = readLessonEffect(stripConcertOverOverlay(effectText)).magnitudes.keys

/**
 * The first capture group of [regex] in [text], as a number.
 *
 * @param regex A pattern whose first group holds an amount.
 * @param text The text to search.
 * @return The parsed amount, or null when the pattern did not match.
 */
private fun firstAmount(regex: Regex, text: String): Double? = regex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * Read a card's categories and how much of each it grants, in one pass. Recognition and measurement live on the same line on purpose: when they were
 * separate the magnitude patterns drifted stricter than the detection keywords, so a card read as "Hint Lvl +3" counted as a Skill Hint and then scored
 * zero, silently falling back to screen position. Every magnitude falls back to 0.0, so a lost amount only ever costs a tie-break, never the category.
 *
 * Training Effectiveness is also inferred when OCR lost its label, which happens often enough to matter: the effect crop routinely reads the "+10%" cards
 * down to a bare percentage, and the post-concert overlay can replace the line outright. A bare percentage and a raw stat gain at or above
 * [TRAINING_EFFECTIVENESS_STAT_FLOOR] both only ever occur on these Songs, so either one stands in for the missing label.
 *
 * @param cleaned The card's effect line(s), overlay already removed.
 * @return The card's reading, with empty magnitudes when nothing recognizable was read.
 */
private fun readLessonEffect(cleaned: String): LessonEffectReading {
    val t = cleaned.lowercase()
    val rawStatGains = parseStatGainsIn(cleaned)
    val trainingStatGain = parseTrainingStatGainIn(cleaned)
    val percent = firstAmount(EFFECTIVENESS_PERCENT_REGEX, cleaned)
    val energy = parseEnergyGainIn(cleaned)
    val magnitudes = mutableMapOf<LessonEffectCategory, Double>()
    val labelLost = percent != null || (rawStatGains.maxOfOrNull { it.second } ?: 0) >= TRAINING_EFFECTIVENESS_STAT_FLOOR
    if ("friendship" in t || "training effectiveness" in t || labelLost) magnitudes[LessonEffectCategory.TRAINING_EFFECTIVENESS] = percent ?: 0.0
    if ("training" in t && "gain" in t) {
        magnitudes[LessonEffectCategory.TRAINING_GAIN] = trainingStatGain?.second?.toDouble() ?: firstAmount(TRAINING_GAIN_AMOUNT_REGEX, cleaned) ?: 0.0
    }
    if (("support" in t && "event" in t) || "specialty priority" in t) magnitudes[LessonEffectCategory.SUPPORT_EVENTS] = 0.0
    if (rawStatGains.isNotEmpty()) magnitudes[LessonEffectCategory.STAT_GAINS] = 0.0
    if ("hint" in t || SKILL_POINT_REGEX.containsMatchIn(cleaned)) magnitudes[LessonEffectCategory.SKILL_HINTS] = firstAmount(SKILL_HINT_AMOUNT_REGEX, cleaned) ?: 0.0
    if (energy != null) magnitudes[LessonEffectCategory.ENERGY] = energy.toDouble()
    return LessonEffectReading(magnitudes, rawStatGains, trainingStatGain)
}

/**
 * Reduce a Lessons card's effect text to everything the purchase ordering needs. Computed once per card so the comparator never re-parses.
 *
 * Categories left out of the ranking are dropped, as is a Stat Gains match whose stat is absent from the stat prioritization, and a Skill Hints match
 * whose tag is absent from a non-empty hint ranking - such a card ranks below anything with a ranked effect, but is still bought when it is the only
 * option. A hint whose tag was not recognized at all is never dropped, so OCR noise cannot demote a card.
 *
 * @param effectText The card's effect line(s).
 * @param statPriority The bot's ordered stat prioritization (index 0 = highest).
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @param hintPriority The user's ranked skill-hint tags (index 0 = highest). Empty means no hint preference.
 * @return The card's ranking profile.
 */
private fun profileLessonEffect(
    effectText: String,
    statPriority: List<StatName>,
    categoryOrder: List<LessonEffectCategory>,
    hintPriority: List<LessonHintTag>,
): LessonEffectProfile {
    val cleaned = stripConcertOverOverlay(effectText)
    val reading = readLessonEffect(cleaned)
    // A card can grant two stats ("Guts +6 Wit +6"), so it is scored on the best-ranked one it grants rather than whichever prints first, with the larger
    // amount breaking a tie between two equally ranked stats. A raw gain is what puts a card in Stat Gains; the named passive only feeds the tie-break.
    val rankedStatGain =
        reading.rawStatGains
            .mapNotNull { gain -> statPriority.indexOf(gain.first).takeIf { it >= 0 }?.let { rank -> rank to gain } }
            .minWithOrNull(compareBy({ it.first }, { -it.second.second }))
    val statGain = rankedStatGain?.second ?: reading.trainingStatGain
    val statRank = rankedStatGain?.first ?: reading.trainingStatGain?.let { statPriority.indexOf(it.first) } ?: -1
    // Scanning the tag table is only worth it once the user has ranked something, and an unrecognized tag must never count as an unwanted one.
    val hintTags = if (hintPriority.isEmpty()) emptySet() else parseHintTagsIn(cleaned)
    val hintRank = hintPriority.indexOfFirst { it in hintTags }.takeIf { it >= 0 }
    // Both guards say the same thing: a card whose specific target the user ranked away is demoted below anything ranked, never blocked outright, so it
    // is still bought when it is the only option. Stat Gains needs no unrecognized-target escape, since detection already gates on the same parse.
    val hintUnwanted = hintTags.isNotEmpty() && hintRank == null
    val ranks =
        reading.magnitudes.keys
            .filterNot { (it == LessonEffectCategory.STAT_GAINS && rankedStatGain == null) || (it == LessonEffectCategory.SKILL_HINTS && hintUnwanted) }
            .mapNotNull { categoryOrder.indexOf(it).takeIf { rank -> rank >= 0 } }
            .sorted()
    val categoryValue = ranks.firstOrNull()?.let { reading.magnitudes[categoryOrder[it]] } ?: 0.0
    val statTieBreak = if (statGain != null && statRank >= 0) (statPriority.size - statRank).toDouble() + statGain.second * 0.001 else 0.0
    val hintTieBreak = hintRank?.let { (hintPriority.size - it).toDouble() } ?: 0.0
    return LessonEffectProfile(ranks, categoryValue, statTieBreak, hintTieBreak)
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
 * Pick the best card, profiling each one exactly once. Category ranks decide first, then the better-ranked skill hint, then how much of that category the
 * card grants, then a Technique edges out a Song of equal value, then the better stat gain, and finally the earlier row.
 *
 * Magnitude sits above the Technique preference on purpose: a "Training Wit Gain +2" Song is worth more than a "Training Guts Gain +1" Technique, and
 * before magnitude was read at all those two tied all the way down to screen position.
 *
 * The hint ranking sits above magnitude because Skill Hints is the one category holding two different units - a hint level ("Skill Hint Lvl +1") and a
 * skill-point total ("Skill Pts +5") - which are not comparable as numbers. An explicit tag ranking is the better signal, so it decides first.
 *
 * @param options The cards to choose between.
 * @param statPriority The bot's ordered stat prioritization (index 0 = highest).
 * @param categoryOrder The user's ranked effect categories (index 0 = highest).
 * @param hintPriority The user's ranked skill-hint tags (index 0 = highest).
 * @return The best card, or null when `options` is empty.
 */
private fun bestLesson(
    options: List<LessonOption>,
    statPriority: List<StatName>,
    categoryOrder: List<LessonEffectCategory>,
    hintPriority: List<LessonHintTag>,
): LessonOption? =
    options
        .map { it to profileLessonEffect(it.effectText, statPriority, categoryOrder, hintPriority) }
        .maxWithOrNull(
            Comparator<Pair<LessonOption, LessonEffectProfile>> { a, b -> compareLessonRanks(a.second.ranks, b.second.ranks) }
                .thenBy { it.second.hintTieBreak }
                .thenBy { it.second.categoryValue }
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
 * @param hintPriority The user's ranked skill-hint tags (index 0 = highest). Empty means no hint preference.
 * @return The chosen option, or null when `options` is empty.
 */
fun chooseLessonPurchase(
    options: List<LessonOption>,
    statPriority: List<StatName>,
    forceMaxHype: Boolean,
    hypeMaxed: Boolean,
    categoryOrder: List<LessonEffectCategory> = DEFAULT_LESSON_EFFECT_PRIORITY,
    hintPriority: List<LessonHintTag> = emptyList(),
): LessonOption? {
    if (forceMaxHype && !hypeMaxed) {
        val hypeSong = bestLesson(options.filter { it.kind == LessonKind.SONG }, statPriority, categoryOrder, hintPriority)
        if (hypeSong != null) return hypeSong
    }

    return bestLesson(options, statPriority, categoryOrder, hintPriority)
}
