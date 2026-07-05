package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.types.StatName

/**
 * URA Finale-specific Training subclass. Detects which facility carries a Happy Meek duel badge and biases training toward it, so the bot enters and wins the duel (which uncaps and
 * boosts that stat). Scoring otherwise defers to the default URA algorithm.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign state.
 */
class UraFinaleTraining(game: Game, campaign: Campaign) : Training(game, campaign) {
    /** How strongly to bias training toward a facility carrying a duel badge. Default MODERATE. */
    private val duelBiasLevel: DuelBiasLevel = parseDuelBiasLevel(SettingsHelper.getStringSetting("scenarioOverrides", "uraHappyMeekDuelBias", "Moderate"))

    /** The facility carrying the Happy Meek duel badge this turn, or null when no duel is available. Resolved once per turn by UraFinale.onMainScreenEntry so the parallel per-facility analysis just reads it instead of re-matching the badge on all five. */
    var duelFacility: StatName? = null

    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        result.extras["hasDuel"] = duelFacility == result.name
        result.latch.countDown()
    }

    override fun scoreTraining(config: TrainingConfig, option: TrainingOption): Double {
        val base = super.scoreTraining(config, option)
        val hasDuel = option.extras["hasDuel"] as? Boolean ?: false
        return applyDuelTrainingBias(base, hasDuel, option.failureChance, duelBiasLevel, maximumFailureChance)
    }

    override fun getExtraLogFields(training: TrainingOption): List<String> {
        val hasDuel = training.extras["hasDuel"] as? Boolean ?: false
        return if (hasDuel) listOf("Happy Meek Duel available (bias: ${duelBiasLevel.name.lowercase()})") else emptyList()
    }
}
