package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.bot.TrainingScoringMode
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.utils.CustomImageUtils.SpiritGaugeResult

/**
 * Unity Cup-specific Training subclass that customizes scoring and analysis behavior.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign state.
 */
class UnityCupTraining(game: Game, campaign: Campaign) : Training(game, campaign) {
    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        if (singleTraining) {
            Thread {
                val startTime = System.currentTimeMillis()
                try {
                    writeGaugeExtras(result, game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap))
                } catch (e: Exception) {
                    Log.e(TAG, "[ERROR] Error in Spirit Explosion Gauge analysis: ${e.stackTraceToString()}")
                    writeGaugeExtras(result, null)
                } finally {
                    result.latch.countDown()
                    Log.d(TAG, "[DEBUG] Total time to analyze Spirit Explosion Gauge for ${result.name}: ${System.currentTimeMillis() - startTime}ms")
                }
            }.start()
        } else {
            val startTime = System.currentTimeMillis()
            try {
                writeGaugeExtras(result, game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap))
            } finally {
                result.latch.countDown()
                Log.d(TAG, "[DEBUG] Total time to analyze Spirit Explosion Gauge for ${result.name}: ${System.currentTimeMillis() - startTime}ms")
            }
        }
    }

    /**
     * Write the Spirit Explosion gauge counts into the training analysis extras, defaulting every count to 0 when analysis returned nothing (no gauges found or detection failed).
     *
     * @param result The training analysis result whose extras receive the gauge counts.
     * @param gaugeResult The analyzed gauge counts, or null when detection failed or found no gauges.
     */
    private fun writeGaugeExtras(result: TrainingAnalysisResult, gaugeResult: SpiritGaugeResult?) {
        result.extras["spiritGaugesCanFill"] = gaugeResult?.numGaugesCanFill ?: 0
        result.extras["spiritGaugesReadyToBurst"] = gaugeResult?.numGaugesReadyToBurst ?: 0
        result.extras["spiritGaugesReadyToExtremeBurst"] = gaugeResult?.numGaugesReadyToExtremeBurst ?: 0
    }

    override fun getTrainingScoringMode(): TrainingScoringMode {
        return if (campaign.date.year < DateYear.SENIOR) {
            TrainingScoringMode.UNITY_CUP
        } else {
            super.getTrainingScoringMode()
        }
    }

    override fun scoreTraining(config: TrainingConfig, option: TrainingOption): Double {
        return if (campaign.date.year < DateYear.SENIOR) {
            scoreUnityCupTraining(config, option)
        } else {
            super.scoreTraining(config, option)
        }
    }

    override fun getExtraLogFields(training: TrainingOption): List<String> {
        val canFill = training.extras["spiritGaugesCanFill"] as? Int ?: 0
        val readyToBurst = training.extras["spiritGaugesReadyToBurst"] as? Int ?: 0
        val readyToExtremeBurst = training.extras["spiritGaugesReadyToExtremeBurst"] as? Int ?: 0
        return if (canFill > 0 || readyToBurst > 0 || readyToExtremeBurst > 0) {
            listOf("Spirit Gauges: fillable=$canFill, ready to burst=$readyToBurst, ready to extreme burst=$readyToExtremeBurst")
        } else {
            emptyList()
        }
    }
}
