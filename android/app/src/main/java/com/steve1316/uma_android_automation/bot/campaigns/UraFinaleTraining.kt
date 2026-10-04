package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.components.IconStatSupportHappyMeek
import com.steve1316.uma_android_automation.components.LabelDuel
import com.steve1316.uma_android_automation.components.LabelDuelSmall
import com.steve1316.uma_android_automation.components.Region
import com.steve1316.uma_android_automation.types.StatName

// Offsets of Happy Meek's "Lvl N" label line from her support portrait match center, at the 1080-wide baseline (measured from 1080x2340 captures).
private const val DUEL_LEVEL_LABEL_OFFSET_X = -150
private const val DUEL_LEVEL_LABEL_OFFSET_Y = 25
private const val DUEL_LEVEL_LABEL_WIDTH = 125
private const val DUEL_LEVEL_LABEL_HEIGHT = 48

/**
 * URA Finale-specific Training subclass. Detects which facility carries a Happy Meek duel badge and biases training toward it, so the bot enters
 * and wins the duel (which uncaps and boosts that stat). When "Avoid Maxing Happy Meek" is on, it also tracks her duel level and skips her facility
 * once one more win would max her. Scoring otherwise defers to the default URA algorithm.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign state.
 */
class UraFinaleTraining(game: Game, campaign: Campaign) : Training(game, campaign) {
    /** How strongly to bias training toward a facility carrying a duel badge. Default MODERATE. */
    private val duelBiasLevel: DuelBiasLevel = parseDuelBiasLevel(SettingsHelper.getStringSetting("scenarioOverrides", "uraHappyMeekDuelBias", "Moderate"))

    /** Whether to skip Happy Meek's facility once one more duel win would max her, for parent farming. Default off. */
    private val avoidMaxHappyMeek: Boolean = SettingsHelper.getBooleanSetting("scenarioOverrides", "uraAvoidMaxHappyMeek", false)

    /** Whether the Main screen showed a Happy Meek duel on offer this turn. Set each turn by UraFinale.onMainScreenEntry. */
    var duelAvailable: Boolean = false

    /** The facility carrying the duel badge this turn, or null when no duel is on offer or the badge was not found. Resolved on the training screen. */
    var duelFacility: StatName? = null

    /** Whether the badge was already looked for this turn. The badge cannot appear mid-turn, so one look per turn is enough. */
    var duelFacilityResolveAttempted: Boolean = false

    /** Happy Meek's duel level: read from her portrait when visible, else carried forward and advanced per duel entered. Null until the first read. */
    private var duelLevel: Int? = null

    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        try {
            if (duelAvailable && !duelFacilityResolveAttempted) resolveDuelFacility(sourceBitmap)
            result.extras["hasDuel"] = duelFacility == result.name
            if (avoidMaxHappyMeek && duelAvailable) result.exclusionReason = checkDuelLevel(result.name, sourceBitmap)
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

    override fun onDuelEntered() {
        duelLevel = advanceDuelLevel(duelLevel)
    }

    /**
     * Find the duel badge on the training screen's facility buttons and map it to its facility. The badge is visible no matter which facility is
     * selected, so the first training screen it is looked for on resolves the facility for the rest of the turn.
     *
     * @param sourceBitmap The screenshot of a training screen.
     */
    private fun resolveDuelFacility(sourceBitmap: Bitmap) {
        duelFacilityResolveAttempted = true
        val badge = LabelDuelSmall.findImageWithBitmap(game.imageUtils, sourceBitmap) ?: LabelDuel.findImageWithBitmap(game.imageUtils, sourceBitmap)
        if (badge == null) {
            MessageLog.w(TAG, "[WARN] resolveDuelFacility:: Could not find the Happy Meek duel badge on the training screen. Falling back to her portrait.")
            return
        }
        duelFacility = duelFacilityForBadgeX(badge.x.toInt(), SharedData.displayWidth)
        MessageLog.i(TAG, "[URA] Happy Meek duel is on the ${duelFacility?.name?.lowercase()} facility. Training will be biased toward it.")
    }

    /**
     * Track Happy Meek's duel level on one facility's training screen and decide whether to skip that facility. Her portrait anchors a small OCR
     * crop of the "Lvl N" line under it. When her portrait or that text is missed, the level carried forward from earlier turns is used instead.
     *
     * @param statName The facility on screen.
     * @param sourceBitmap The screenshot of that facility's training screen.
     * @return Why the facility should be skipped, or null to keep it.
     */
    private fun checkDuelLevel(statName: StatName, sourceBitmap: Bitmap): String? {
        // A resolved badge is authoritative. Her portrait only says where she is when the badge was not found.
        if (duelFacility != null && duelFacility != statName) return null
        val portrait = IconStatSupportHappyMeek.findImageWithBitmap(game.imageUtils, sourceBitmap, region = Region.topRightThird)
        if (duelFacility == null && portrait == null) return null

        val ocrText =
            portrait?.let {
                game.imageUtils.performOCROnRegion(
                    sourceBitmap,
                    game.imageUtils.relX(it.x, DUEL_LEVEL_LABEL_OFFSET_X),
                    game.imageUtils.relY(it.y, DUEL_LEVEL_LABEL_OFFSET_Y),
                    game.imageUtils.relWidth(DUEL_LEVEL_LABEL_WIDTH),
                    game.imageUtils.relHeight(DUEL_LEVEL_LABEL_HEIGHT),
                    useThreshold = false,
                    useGrayscale = true,
                    ocrEngine = "mlkit",
                    debugName = "checkDuelLevel_${statName.name.lowercase()}",
                )
            }
        val readLevel = ocrText?.let { parseDuelLevel(it) }
        if (readLevel != null) duelLevel = readLevel

        val levelText = if (duelLevel == DUEL_LEVEL_MAX) "MAX" else duelLevel?.toString() ?: "unknown"
        val source = if (readLevel != null) "read" else "carried forward"
        MessageLog.i(TAG, "[URA] Happy Meek duel on the ${statName.name.lowercase()} facility. Level OCR: ${ocrText?.let { "\"$it\"" } ?: "(portrait not found)"}, level: $levelText ($source).")

        if (!shouldAvoidDuelFacility(duelLevel)) return null
        return "Happy Meek's duel level is $levelText ($source), so training here could max her"
    }
}
