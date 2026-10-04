package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonHomeFansInfo
import com.steve1316.uma_android_automation.components.LabelDuel
import com.steve1316.uma_android_automation.types.StatName
import kotlin.math.abs

// Screen-width fractions of the five facility button centers, ordered Speed, Stamina, Power, Guts, Wit. Empirically measured so the duel badge maps to the correct facility column.
private val FACILITY_SLOT_FRACTIONS: List<Pair<StatName, Double>> =
    listOf(StatName.SPEED to 0.145, StatName.STAMINA to 0.324, StatName.POWER to 0.499, StatName.GUTS to 0.678, StatName.WIT to 0.853)

/** Happy Meek's duel level once she shows "Lvl MAX". One past the highest numbered level so a maxed read never equals [DUEL_LEVEL_BEFORE_MAX]. */
const val DUEL_LEVEL_MAX = 6

/** The duel level at which one more win maxes Happy Meek. */
const val DUEL_LEVEL_BEFORE_MAX = DUEL_LEVEL_MAX - 1

// Matches "Lvl 2" / "Lvl MAX" under Meek's support portrait, tolerating the "l" misread as "I", "1" or "|" or dropped.
// The "Lv" prefix keeps friendship "MAX" badges out.
private val DUEL_LEVEL_REGEX = Regex("""l\s*v\s*[l1i|]?\s*(max|[1-5])""", RegexOption.IGNORE_CASE)

// The level crop holds nothing but her label, so text that is only a level (the "Lvl" dropped by a split OCR block, plus any stray marks) still reads cleanly.
private val BARE_DUEL_LEVEL_REGEX = Regex("""^[^0-9a-z]*(max|[1-5])[^0-9a-z]*$""", RegexOption.IGNORE_CASE)

/** Win-prediction tier for a Happy Meek duel contest, best to worst. WORST is the untemplated X tier - a row that matches none of the great / good / bad prediction icons. */
enum class DuelPrediction { GREAT, GOOD, BAD, WORST }

/**
 * One "Contest of <stat>" option in a Happy Meek duel.
 *
 * @property statName The contested stat, or null for the "Contest of energy" option (energy is not a trainable stat).
 * @property prediction The win-prediction tier read from the row's icon.
 */
data class DuelContestOption(val statName: StatName?, val prediction: DuelPrediction)

/**
 * Pick which Happy Meek duel contest to enter. Prefers the highest-odds contest among the trainee's target stats (a prediction of GOOD or better counts as "good odds"); if no
 * target stat has good odds, falls back to the best-odds contest overall. A prediction tie breaks toward the higher-priority target stat, then the earliest option. Pure and
 * unit-tested so the policy is verified without a live duel.
 *
 * @param options The detected contest options in on-screen (top-to-bottom) order.
 * @param targetStats The trainee's stat priority list (earlier = higher priority).
 * @return The 0-based index of the chosen option, or 0 when there are no options.
 */
fun chooseDuelContest(options: List<DuelContestOption>, targetStats: List<StatName>): Int {
    if (options.isEmpty()) return 0
    val indexed = options.withIndex().toList()
    // A target stat "has good odds" when its prediction is GOOD or better (lower enum ordinal is better).
    val goodOddsTargets = indexed.filter { (_, option) -> option.statName != null && option.statName in targetStats && option.prediction.ordinal <= DuelPrediction.GOOD.ordinal }
    val pool = goodOddsTargets.ifEmpty { indexed }
    return pool
        .minWith(
            compareBy(
                { it.value.prediction.ordinal },
                { targetStats.indexOf(it.value.statName).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } },
                { it.index },
            ),
        ).index
}

/**
 * Parse the contested stat from a duel row's "Contest of <stat>!" OCR text. Matching is case-insensitive and substring-based so partial OCR still resolves.
 *
 * @param text The OCR'd label for one contest row.
 * @return The contested [StatName], or null for the "Contest of energy" option (energy is not a trainable stat) or unrecognized text.
 */
fun parseContestStat(text: String): StatName? {
    val lower = text.lowercase()
    return StatName.entries.firstOrNull { it.name.lowercase() in lower }
}

/**
 * Pick the win-prediction tier for a duel row by finding the prediction icon nearest to the row on the Y axis. A row whose nearest icon is farther than the tolerance (the X tier
 * has no template) is treated as WORST.
 *
 * @param rowY The Y coordinate of the row's horseshoe location.
 * @param predictionMatches Every detected prediction icon as (tier, iconCenterY), across all three templates.
 * @param tolerancePx The maximum Y distance (roughly half the row pitch) for an icon to count as belonging to the row.
 * @return The row's [DuelPrediction] tier, or WORST when no icon falls within tolerance.
 */
fun nearestDuelPrediction(rowY: Int, predictionMatches: List<Pair<DuelPrediction, Int>>, tolerancePx: Int): DuelPrediction {
    val nearest = predictionMatches.minByOrNull { abs(it.second - rowY) } ?: return DuelPrediction.WORST
    return if (abs(nearest.second - rowY) <= tolerancePx) nearest.first else DuelPrediction.WORST
}

/**
 * How strongly to bias URA Finale training toward a facility that carries a Happy Meek duel badge. The multiplier scales the facility's score: MODERATE wins when the duel facility
 * is within ~20% of the best pick, AGGRESSIVE within ~40%, and OFF applies no bias.
 *
 * @property multiplier The factor a duel facility's base score is multiplied by.
 */
enum class DuelBiasLevel(val multiplier: Double) {
    OFF(1.0),
    MODERATE(1.25),
    AGGRESSIVE(1.6),
}

/**
 * Parse the `scenarioOverrides.uraHappyMeekDuelBias` setting string into a level. Unknown or empty values default to MODERATE so a missing setting still biases toward duels.
 *
 * @param setting The raw setting string.
 * @return The parsed [DuelBiasLevel].
 */
fun parseDuelBiasLevel(setting: String): DuelBiasLevel =
    when (setting.trim().lowercase()) {
        "off" -> DuelBiasLevel.OFF
        "aggressive" -> DuelBiasLevel.AGGRESSIVE
        else -> DuelBiasLevel.MODERATE
    }

/**
 * Bias a URA duel facility's score. A positive base score is multiplied by the level's multiplier when the facility carries the duel and its failure chance is acceptable, so the
 * duel is preferred when it is not clearly worse than the best normal pick. Missing duels, the OFF level, non-positive scores, and unreadable or too-high failure chances are left
 * unchanged so a bad or risky duel facility is never forced.
 *
 * @param baseScore The facility's score before the bias.
 * @param hasDuel Whether this facility carries the duel badge.
 * @param failureChance The facility's failure chance percent (-1 when OCR failed).
 * @param level The configured bias level.
 * @param maxFailureChance The highest failure chance considered acceptable for biasing.
 * @return The biased score.
 */
fun applyDuelTrainingBias(baseScore: Double, hasDuel: Boolean, failureChance: Int, level: DuelBiasLevel, maxFailureChance: Int): Double {
    if (!hasDuel || level == DuelBiasLevel.OFF || baseScore <= 0.0) return baseScore
    return if (failureChance in 0..maxFailureChance) baseScore * level.multiplier else baseScore
}

/**
 * Map the training-screen duel badge's X coordinate to its facility. The badge sits on one facility button, so it lands nearest that facility's column center. Returns the stat
 * whose button center is nearest the badge.
 *
 * @param badgeX The X coordinate of the detected duel badge center.
 * @param displayWidth The screen width the badge was detected on.
 * @return The [StatName] of the facility carrying the duel.
 */
fun duelFacilityForBadgeX(badgeX: Int, displayWidth: Int): StatName =
    FACILITY_SLOT_FRACTIONS.minByOrNull { abs(it.second * displayWidth - badgeX) }?.first ?: StatName.SPEED

/**
 * Parse Happy Meek's duel level from OCR text of the label under her support portrait, which reads "Duel Lvl N" or "Duel Lvl MAX". ML Kit sometimes
 * returns the number alone, so a text that is nothing but a level counts too.
 *
 * @param text The OCR'd label text.
 * @return The level 1-5, [DUEL_LEVEL_MAX] when maxed, or null when no level text is found.
 */
fun parseDuelLevel(text: String): Int? {
    val value = (DUEL_LEVEL_REGEX.find(text) ?: BARE_DUEL_LEVEL_REGEX.find(text.trim()))?.groupValues?.get(1) ?: return null
    return if (value.equals("max", ignoreCase = true)) DUEL_LEVEL_MAX else value.toInt()
}

/**
 * Decide whether to skip the facility Happy Meek is on so she never reaches MAX. Winning at [DUEL_LEVEL_BEFORE_MAX] maxes her, so that level is
 * skipped. An unknown level is skipped too, since one missed read at that level would max her. A maxed level is not skipped because there is
 * nothing left to protect.
 *
 * @param level Her current duel level, or null when it is unknown.
 * @return True when the facility should be skipped this turn.
 */
fun shouldAvoidDuelFacility(level: Int?): Boolean = level == null || level == DUEL_LEVEL_BEFORE_MAX

/**
 * Advance Happy Meek's tracked duel level after the bot enters a duel, since winning is the only way it rises. Capped at [DUEL_LEVEL_BEFORE_MAX]
 * so dead reckoning never claims MAX on its own, and left alone when the level is unknown or already maxed.
 *
 * @param level Her tracked duel level before the duel, or null when it is unknown.
 * @return The level to track after the duel.
 */
fun advanceDuelLevel(level: Int?): Int? =
    when (level) {
        null -> null
        DUEL_LEVEL_MAX -> DUEL_LEVEL_MAX
        else -> minOf(level + 1, DUEL_LEVEL_BEFORE_MAX)
    }

/**
 * Handles the URA Finale scenario with scenario-specific logic and handling.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class UraFinale(game: Game) : Campaign(game) {
    override val training: UraFinaleTraining = UraFinaleTraining(game, this)

    override fun openFansDialog() {
        ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionTopHalf, tries = 10)
        bHasTriedCheckingFansToday = true
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
    }

    override fun onMainScreenEntry() {
        // The Main screen only shows that a duel is on offer, not which facility carries it.
        // The facility is resolved from the training screen's buttons during analysis.
        training.duelFacility = null
        training.duelFacilityResolveAttempted = false
        training.duelAvailable = LabelDuel.check(game.imageUtils)
        if (training.duelAvailable) {
            MessageLog.i(TAG, "[URA] Happy Meek duel available this turn. Its facility will be resolved on the training screen.")
        }
    }
}
