package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonHomeFansInfo
import com.steve1316.uma_android_automation.types.StatName
import kotlin.math.abs

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
    return when {
        "speed" in lower -> StatName.SPEED
        "stamina" in lower -> StatName.STAMINA
        "power" in lower -> StatName.POWER
        "guts" in lower -> StatName.GUTS
        "wit" in lower -> StatName.WIT
        else -> null
    }
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
 * Handles the URA Finale scenario with scenario-specific logic and handling.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class UraFinale(game: Game) : Campaign(game) {
    override fun openFansDialog() {
        ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionTopHalf, tries = 10)
        bHasTriedCheckingFansToday = true
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
    }
}
