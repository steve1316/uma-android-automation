package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.components.LabelDuelSmall

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

    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        try {
            // The duel badge sits on one facility button, so its column identifies the duel facility regardless of which facility is currently selected.
            val badge = LabelDuelSmall.findImageWithBitmap(game.imageUtils, sourceBitmap)
            result.extras["hasDuel"] = badge != null && duelFacilityForBadgeX(badge.x.toInt(), SharedData.displayWidth) == result.name
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Error in Happy Meek duel badge detection: ${e.stackTraceToString()}")
            result.extras["hasDuel"] = false
        } finally {
            result.latch.countDown()
        }
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
