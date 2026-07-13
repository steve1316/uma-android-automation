package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import com.steve1316.uma_scoring.GameDateSnapshot
import com.steve1316.uma_scoring.BarFillResult as ScoringBarFillResult
import com.steve1316.uma_scoring.TrainingConfig as ScoringTrainingConfig
import com.steve1316.uma_scoring.TrainingOption as ScoringTrainingOption

/**
 * Boundary adapters that convert the Android-rich analysis types (`Training.TrainingConfig`, `Training.TrainingOption`, `GameDate`, `CustomImageUtils.BarFillResult`) into the
 * slim shared types consumed by the scoring math in `:scoring-shared` commonMain. Kept in their own file so Training.kt stays focused on bot orchestration.
 */

/** Project the rich `GameDate` down to a `GameDateSnapshot` carrying only the fields the scoring math reads. */
fun GameDate.toSnapshot(): GameDateSnapshot =
    GameDateSnapshot(
        year = year,
        day = day,
        bIsPreDebut = bIsPreDebut,
        isSummer = isSummer(),
    )

/** Project a `CustomImageUtils.BarFillResult` (Android, carries the `StatBlock`) into the slim shared `BarFillResult` the scoring math reads. */
fun CustomImageUtils.BarFillResult.toScoring(): ScoringBarFillResult =
    ScoringBarFillResult(
        dominantColor = dominantColor,
        fillPercent = fillPercent,
        isTrainerSupport = isTrainerSupport,
    )

/** Project the Android-rich `Training.TrainingConfig` into the shared `TrainingConfig` the scoring math reads. Drops fields irrelevant to per-training scoring (event-choice priority, `disableStatTargets`, `trainingOptions`). */
fun Training.TrainingConfig.toScoring(): ScoringTrainingConfig =
    ScoringTrainingConfig(
        currentStats = currentStats,
        statPrioritization = statPrioritization,
        summerTrainingStatPriority = summerTrainingStatPriority,
        statTargets = statTargets,
        currentDate = currentDate.toSnapshot(),
        scenario = scenario,
        enableRainbowTrainingBonus = enableRainbowTrainingBonus,
        blacklist = blacklist,
        disableTrainingOnMaxedStat = disableTrainingOnMaxedStat,
        skillHintsPerLocation = skillHintsPerLocation,
        enablePrioritizeSkillHints = enablePrioritizeSkillHints,
        enableTrainingLevelWeighting = enableTrainingLevelWeighting,
        enablePrioritizeNearMaxFriendship = enablePrioritizeNearMaxFriendship,
        statsTrainedOverBuffer = statsTrainedOverBuffer,
        scoring = scoring,
        statCaps = statCaps,
    )

/** Project the Android-rich `Training.TrainingOption` into the shared `TrainingOption` the scoring math reads. Drops OCR-correction data, scenario `extras`, and the skip-reason string. */
fun Training.TrainingOption.toScoring(): ScoringTrainingOption =
    ScoringTrainingOption(
        name = name,
        statGains = statGains,
        relationshipBars = relationshipBars.map { it.toScoring() },
        numRainbow = numRainbow,
        numSkillHints = numSkillHints,
        trainingLevel = trainingLevel,
    )
