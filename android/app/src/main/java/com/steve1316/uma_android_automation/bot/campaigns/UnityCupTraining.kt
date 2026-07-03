package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Training
import com.steve1316.uma_android_automation.bot.TrainingScoringMode
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.GameDate

// Turn numbers of the five Unity Cup (Aoharu) scenario team races: Junior Late Dec, Classic Late Jun, Classic Late Dec, Senior Late Jun, Senior Late Dec. The schedule is a fixed game
// constant ("every six months: Late June and Late December"), so it is derived from readable date tuples via GameDate.toDay (= days 24, 36, 48, 60, 72) rather than hardcoded integers.
private val UNITY_SCENARIO_RACE_DAYS: List<Int> =
    listOf(
        Triple(DateYear.JUNIOR, DateMonth.DECEMBER, DatePhase.LATE),
        Triple(DateYear.CLASSIC, DateMonth.JUNE, DatePhase.LATE),
        Triple(DateYear.CLASSIC, DateMonth.DECEMBER, DatePhase.LATE),
        Triple(DateYear.SENIOR, DateMonth.JUNE, DatePhase.LATE),
        Triple(DateYear.SENIOR, DateMonth.DECEMBER, DatePhase.LATE),
    ).map { (year, month, phase) -> GameDate.toDay(year, month, phase) }

/** How many turns before a scenario race the pre-race stat focus kicks in. 0 means only the race-day training itself, which is the last training before the race runs as an extra turn. */
private const val UNITY_PRE_RACE_LOOKAHEAD: Int = 2

/**
 * Whether the current turn is within the pre-race window of a Unity Cup scenario race. The bias should apply on the last few trainings leading into a team race, including the race-day
 * training. Pure and unit-testable so the schedule math is verified without a live campaign.
 *
 * @param currentDay The current turn number.
 * @param lookahead How many turns ahead of a race day still count as pre-race.
 * @return True when a scenario race falls within `lookahead` turns at or after `currentDay`.
 */
internal fun isUnityPreRaceTurn(currentDay: Int, lookahead: Int): Boolean = UNITY_SCENARIO_RACE_DAYS.any { raceDay -> raceDay - currentDay in 0..lookahead }

/**
 * Unity Cup-specific Training subclass that customizes scoring and analysis behavior.
 *
 * @property game The [Game] instance for interacting with the game state.
 * @property campaign The [Campaign] instance for accessing campaign state.
 */
class UnityCupTraining(game: Game, campaign: Campaign) : Training(game, campaign) {
    /** Whether to bias training toward stat efficiency over gauge-filling in the turns just before a Unity Cup scenario race. Default off. */
    private val unityCupPreRaceStatFocus: Boolean = SettingsHelper.getBooleanSetting("scenarioOverrides", "unityCupPreRaceStatFocus")
    override fun runExtraTrainingAnalysis(result: TrainingAnalysisResult, sourceBitmap: Bitmap, singleTraining: Boolean) {
        if (singleTraining) {
            Thread {
                val startTime = System.currentTimeMillis()
                try {
                    val gaugeResult = game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap)
                    if (gaugeResult != null) {
                        result.extras["spiritGaugesCanFill"] = gaugeResult.numGaugesCanFill
                        result.extras["spiritGaugesReadyToBurst"] = gaugeResult.numGaugesReadyToBurst
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[ERROR] Error in Spirit Explosion Gauge analysis: ${e.stackTraceToString()}")
                    result.extras["spiritGaugesCanFill"] = 0
                    result.extras["spiritGaugesReadyToBurst"] = 0
                } finally {
                    result.latch.countDown()
                    Log.d(TAG, "[DEBUG] Total time to analyze Spirit Explosion Gauge for ${result.name}: ${System.currentTimeMillis() - startTime}ms")
                }
            }.start()
        } else {
            val startTime = System.currentTimeMillis()
            try {
                val gaugeResult = game.imageUtils.analyzeSpiritExplosionGauges(sourceBitmap)
                if (gaugeResult != null) {
                    result.extras["spiritGaugesCanFill"] = gaugeResult.numGaugesCanFill
                    result.extras["spiritGaugesReadyToBurst"] = gaugeResult.numGaugesReadyToBurst
                } else {
                    result.extras["spiritGaugesCanFill"] = 0
                    result.extras["spiritGaugesReadyToBurst"] = 0
                }
            } finally {
                result.latch.countDown()
                Log.d(TAG, "[DEBUG] Total time to analyze Spirit Explosion Gauge for ${result.name}: ${System.currentTimeMillis() - startTime}ms")
            }
        }
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
            val preRaceStatFocus = unityCupPreRaceStatFocus && isUnityPreRaceTurn(config.currentDate.day, UNITY_PRE_RACE_LOOKAHEAD)
            scoreUnityCupTraining(config, option, preRaceStatFocus)
        } else {
            super.scoreTraining(config, option)
        }
    }

    override fun getExtraLogFields(training: TrainingOption): List<String> {
        val canFill = training.extras["spiritGaugesCanFill"] as? Int ?: 0
        val readyToBurst = training.extras["spiritGaugesReadyToBurst"] as? Int ?: 0
        return if (canFill > 0 || readyToBurst > 0) {
            listOf("Spirit Gauges: fillable=$canFill, ready to burst=$readyToBurst")
        } else {
            emptyList()
        }
    }
}
