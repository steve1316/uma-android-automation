package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.components.ButtonTrainingGutsAlt
import com.steve1316.uma_android_automation.components.ButtonTrainingPowerAlt
import com.steve1316.uma_android_automation.components.ButtonTrainingSpeedAlt
import com.steve1316.uma_android_automation.components.ButtonTrainingStaminaAlt
import com.steve1316.uma_android_automation.components.ButtonTrainingWitAlt
import com.steve1316.uma_android_automation.components.ComponentInterface
import com.steve1316.uma_android_automation.components.LabelGrandLivePerformancePoints
import com.steve1316.uma_android_automation.types.StatName

/**
 * Grand Live-specific Training subclass. Reads the Performance-Point token gains off each facility during analysis and surfaces
 * them in the training analysis log. Scoring still defers to super (a token-bias-toward-needed-techniques pass is future work).
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign state.
 */
class GrandLiveTraining(game: Game, campaign: Campaign) : Training(game, campaign) {
    /** Grand Live's facility buttons do not match the standard templates, so the wider-crop alternate set is used instead. */
    override val trainingButtons: Map<StatName, ComponentInterface> =
        mapOf(
            StatName.SPEED to ButtonTrainingSpeedAlt,
            StatName.STAMINA to ButtonTrainingStaminaAlt,
            StatName.POWER to ButtonTrainingPowerAlt,
            StatName.GUTS to ButtonTrainingGutsAlt,
            StatName.WIT to ButtonTrainingWitAlt,
        )

    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        // Always read on a thread: the token read (a template match + five YOLO inferences) is too slow for the pre-thread critical
        // path in comprehensive mode, and latch slot 5 is reserved for this analysis in both modes.
        Thread {
            try {
                result.extras["tokenGains"] = readTokenGains(sourceBitmap, result.name)
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Error reading Grand Live token gains: ${e.stackTraceToString()}")
            } finally {
                result.latch.countDown()
            }
        }.start()
    }

    /**
     * Read the five Performance-Point token gains ("+N" overlays) off a facility's screen, anchored to the matched Performance
     * Points panel. Uses the shared YOLO digit reader with the "+"-required gate, so a token the facility does not grant reads 0.
     *
     * @param sourceBitmap The facility screen to read from.
     * @param name The facility being analyzed, used for debug labels.
     * @return The (label -> gain) map in Da/Pa/Vo/Vi/Co order, or an empty map when the panel was not found.
     */
    private fun readTokenGains(sourceBitmap: Bitmap, name: StatName): Map<String, Int> {
        val center = LabelGrandLivePerformancePoints.findImageWithBitmap(game.imageUtils, sourceBitmap) ?: return emptyMap()
        return GRAND_LIVE_TOKEN_LABELS.mapIndexed { i, label ->
            val (_, gainCrop) = tokenCrops(i)
            label to (readTokenNumber(game.imageUtils, sourceBitmap, center, gainCrop, requirePlus = true, debugName = "grandlive_${name.name.lowercase()}_${label}_gain") ?: 0)
        }.toMap()
    }

    override fun getExtraLogFields(training: TrainingOption): List<String> {
        @Suppress("UNCHECKED_CAST")
        val gains = training.extras["tokenGains"] as? Map<String, Int> ?: return emptyList()
        if (gains.isEmpty()) return emptyList()
        return listOf("Token gains: ${GRAND_LIVE_TOKEN_LABELS.joinToString(", ") { "$it +${gains[it] ?: 0}" }}")
    }
}
