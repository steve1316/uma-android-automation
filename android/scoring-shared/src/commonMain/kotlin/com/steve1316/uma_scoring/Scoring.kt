package com.steve1316.uma_scoring

import kotlin.math.pow

/**
 * The shared, canonical training-scoring math. This file is the single source of truth for the four score functions, the level-boost amplifier, the stat-cap and finale-bonus
 * helpers, and the `scoringConstantsFromMap` settings hydrator. The Android bot and the React Native sandbox both consume this same code (via the `:scoring-shared` KMP
 * module's jvm and js targets respectively). No Android types, no logging, no JVM-only APIs are referenced here.
 */

/** Stats gained per finale race win, per stat. Slightly above the actual +10 to account for misc event/card gains. */
private const val FINALE_RACE_STAT_BONUS = 15

/**
 * Retrieve the scenario-specific cap for a given stat. Currently a stub returning a flat 1200 for every scenario - kept as a function so callers thread through the scenario
 * name and we have a hook to differentiate per-scenario caps later without a signature change.
 *
 * @param scenario The campaign name.
 * @param statName The stat being capped.
 * @return The maximum value for the specified stat in the given scenario.
 */
@JsExport
fun getScenarioStatCap(scenario: String, statName: StatName): Int = 1200

/**
 * Retrieve the current stat cap given a scoring config.
 *
 * @param statName The stat name.
 * @param config The scoring config (only `config.scenario` is consulted).
 * @return Stat cap.
 */
@JsExport
fun getCurrentStatCap(statName: StatName, config: TrainingConfig): Int = getScenarioStatCap(config.scenario, statName)

/**
 * Number of remaining finale races based on the current turn. Finale races occur on turns 73, 74, and 75. Before the finale (turn <= 72), all 3 races remain.
 *
 * @param currentDay Current turn (1-75).
 * @return Remaining finale races, in [0, 3].
 */
@JsExport
fun getRemainingFinaleRaces(currentDay: Int): Int = (75 - maxOf(currentDay, 72)).coerceAtLeast(0)

/**
 * Expected total stat bonus from remaining finale race wins.
 *
 * @param currentDay Current turn (1-75).
 * @return Expected per-stat gain from remaining finale races.
 */
@JsExport
fun getFinaleStatBonus(currentDay: Int): Int = getRemainingFinaleRaces(currentDay) * FINALE_RACE_STAT_BONUS

/**
 * Level-based amplifier for a stat's priority weight. Returns 1.0 when the feature is disabled or has no effect. Only ranks 1-3 receive any boost. At Lvl 5: rank 1 = 1.75x,
 * rank 2 = 1.25x, rank 3 = 1.10x. The fade keeps the boost heavily concentrated on the user's top priority while still rewarding investment in their secondary.
 *
 * @param priorityRank The 1-indexed position of the stat in the active priority list (1 = highest priority).
 * @param trainingLevel The detected training level (1-5), or null if OCR was unavailable.
 * @param constants The `TrainingScoringConstants` supplying the per-rank boost factors.
 * @return Multiplier in [1.0, 1.75].
 */
@JsExport
fun levelBoostMultiplier(priorityRank: Int, trainingLevel: Int?, constants: TrainingScoringConstants = TrainingScoringConstants()): Double {
    val level = trainingLevel ?: 1
    if (level <= 1) return 1.0
    val priorityFactor =
        when (priorityRank) {
            1 -> constants.levelBoostRank1Factor
            2 -> constants.levelBoostRank2Factor
            3 -> constants.levelBoostRank3Factor
            else -> 0.0
        }
    val levelFactor = (level - 1) / 4.0
    return 1.0 + priorityFactor * levelFactor
}

/**
 * Stat-efficiency score: how well the training advances the trainee's stats toward their targets, weighted by priority position and (optionally) training-facility level.
 *
 * @param config The shared scoring config.
 * @param training The shared training option.
 * @return Raw stat-efficiency score (no normalization; downstream weights handle that).
 */
@JsExport
fun calculateStatEfficiencyScore(config: TrainingConfig, training: TrainingOption): Double {
    var score = 0.0
    val activePriority = if (config.currentDate.isSummer) config.summerTrainingStatPriority else config.statPrioritization

    for (statName in StatName.entries) {
        val currentStat = config.currentStats[statName] ?: 0
        val targetStat = config.statTargets[statName] ?: 0
        val statGain = training.statGains[statName] ?: 0

        if (statGain > 0 && targetStat > 0) {
            val priorityIndex = activePriority.indexOf(statName)
            val completionPercent = (currentStat.toDouble() / targetStat) * 100.0

            val ratioMultiplier =
                run {
                    val breakpoints = config.scoring.ratioBreakpoints
                    val multipliers = config.scoring.ratioMultipliers
                    val bucket = breakpoints.indexOfFirst { completionPercent < it }
                    if (bucket == -1) multipliers.last() else multipliers[bucket]
                }

            val priorityMultiplier =
                if (priorityIndex != -1) {
                    1.0 + (config.scoring.priorityCoefficient * (activePriority.size - priorityIndex))
                } else {
                    1.0
                }

            val levelMultiplier =
                if (config.enableTrainingLevelWeighting && statName == training.name && priorityIndex != -1) {
                    levelBoostMultiplier(priorityIndex + 1, training.trainingLevel, config.scoring)
                } else {
                    1.0
                }

            val isMainStat = training.name == statName
            val mainStatBonus =
                if (isMainStat && statGain >= (config.scoring.mainStatThresholds[statName] ?: error("No mainStatThresholds entry for $statName"))) {
                    config.scoring.mainStatBonusMagnitude
                } else {
                    1.0
                }

            var statScore = statGain.toDouble()
            statScore *= ratioMultiplier
            statScore *= priorityMultiplier
            statScore *= levelMultiplier
            statScore *= mainStatBonus
            score += statScore
        }
    }
    return score
}

/**
 * Relationship-building score with diminishing returns. Normalized to roughly [0, 100] by dividing accumulated value by the per-bar theoretical max so the downstream weight
 * can treat it the same as the misc score.
 *
 * @param config The shared scoring config.
 * @param training The shared training option.
 * @return Normalized score in [0.0, ~100.0].
 */
@JsExport
fun calculateRelationshipScore(config: TrainingConfig, training: TrainingOption): Double {
    if (training.relationshipBars.isEmpty()) return 0.0

    var score = 0.0
    var maxScore = 0.0

    for (bar in training.relationshipBars) {
        val baseValue =
            when (bar.dominantColor) {
                "orange" -> config.scoring.relationshipOrangeValue
                "green" -> config.scoring.relationshipGreenValue
                "blue" -> config.scoring.relationshipBlueValue
                else -> 0.0
            }

        if (baseValue > 0) {
            val fillLevel = bar.fillPercent / 100.0
            val diminishingFactor = 1.0 - (fillLevel * config.scoring.relationshipDiminishingFactor)
            val earlyGameBonus = if (config.currentDate.year == DateYear.JUNIOR || config.currentDate.bIsPreDebut) config.scoring.relationshipEarlyGameBonus else 1.0
            val trainerSupportBonus = if (bar.isTrainerSupport) config.scoring.relationshipTrainerSupportBonus else 1.0
            score += baseValue * diminishingFactor * earlyGameBonus * trainerSupportBonus
            maxScore += config.scoring.relationshipBlueValue * config.scoring.relationshipEarlyGameBonus
        }
    }

    return if (maxScore > 0) (score / maxScore * 100.0) else 0.0
}

/**
 * Misc score - currently dominated by skill hints. Starts at 50 (neutral), adds `skillHintPerHintScore` per detected hint, and if the user has enabled "prioritize skill hints"
 * and any hints exist, returns `skillHintOverrideScore` + score so hint trainings outscore everything else.
 *
 * @param config The shared scoring config.
 * @param training The shared training option.
 * @return Misc score in [0.0, 100.0] normally, or above when the skill-hint override fires.
 */
@JsExport
fun calculateMiscScore(config: TrainingConfig, training: TrainingOption): Double {
    var score = 50.0
    val numSkillHints: Int = config.skillHintsPerLocation[training.name] ?: 0
    score += config.scoring.skillHintPerHintScore * numSkillHints

    if (config.enablePrioritizeSkillHints && numSkillHints > 0) {
        return config.scoring.skillHintOverrideScore + score
    }

    return score.coerceIn(0.0, 100.0)
}

/**
 * Raw training score combining stat-efficiency, relationship, and misc with composition weights, then a rainbow / anticipatory multiplier. Returns 0 for blacklisted trainings
 * or trainings whose primary stat is at or past the cap (subject to the single rainbow allowance).
 *
 * @param config The shared scoring config.
 * @param training The shared training option.
 * @return Raw composite score, coerced to >= 0.
 */
@JsExport
fun calculateRawTrainingScore(config: TrainingConfig, training: TrainingOption): Double = rawTrainingScoreComponents(config, training).total

/**
 * Same computation as [calculateRawTrainingScore] but returns the ordered per-factor decomposition so callers can log exactly how the score was built. The composition order
 * (weighted stat + relationship + misc, then rainbow multiplier, then anticipatory multiplier) is identical to the scalar path, so `total` equals [calculateRawTrainingScore].
 *
 * @param config The shared scoring config.
 * @param training The shared training option.
 * @return The per-factor breakdown whose `total` is the raw composite score, coerced to >= 0.
 */
@JsExport
fun rawTrainingScoreComponents(config: TrainingConfig, training: TrainingOption): RawScoreBreakdown {
    val zero = RawScoreBreakdown(0.0, 0.0, 0.0, 1.0, 1.0, 0.0)
    if (training.name in config.blacklist) return zero

    val currentStat: Int = config.currentStats.getOrElse(training.name) { 0 }
    val potentialStat: Int = currentStat + training.statGains.getOrElse(training.name) { 0 }
    val statCap = getCurrentStatCap(training.name, config)
    val finaleBonus = getFinaleStatBonus(config.currentDate.day)
    val effectiveStatCap = statCap - 100 - finaleBonus

    if (currentStat >= statCap) return zero

    if (config.disableTrainingOnMaxedStat && currentStat >= effectiveStatCap) {
        val canUseAllowance = training.numRainbow > 0 && training.name !in config.statsTrainedOverBuffer
        if (!canUseAllowance) return zero
    }

    if (potentialStat >= effectiveStatCap) {
        val canUseAllowance = training.numRainbow > 0 && training.name !in config.statsTrainedOverBuffer
        if (!canUseAllowance) return zero
    }

    val statScore = calculateStatEfficiencyScore(config, training)
    val relationshipScore = calculateRelationshipScore(config, training)
    val miscScore = calculateMiscScore(config, training)

    val statWeight = if (training.relationshipBars.isNotEmpty()) config.scoring.statWeightWithBars else config.scoring.statWeightWithoutBars
    val relationshipWeight = if (training.relationshipBars.isNotEmpty()) config.scoring.relationshipWeightWithBars else 0.0
    val miscWeight = config.scoring.miscWeight

    // Composition order below mirrors the scalar path exactly (accumulate weighted scores, then apply each multiplier) so `total` stays byte-identical to the old code.
    var totalScore = 0.0
    val statScoreWeighted = statScore * statWeight
    val relationshipScoreWeighted = relationshipScore * relationshipWeight
    val miscScoreWeighted = miscScore * miscWeight
    totalScore += statScoreWeighted
    totalScore += relationshipScoreWeighted
    totalScore += miscScoreWeighted

    val rainbowMultiplier =
        if (training.numRainbow > 0 && config.currentDate.year > DateYear.JUNIOR) {
            if (config.enableRainbowTrainingBonus) config.scoring.rainbowMultiplierEnabled else config.scoring.rainbowMultiplierDisabled
        } else {
            1.0
        }
    totalScore *= rainbowMultiplier

    var anticipatoryMultiplier = 1.0
    if (
        config.enablePrioritizeNearMaxFriendship &&
        config.currentDate.year > DateYear.JUNIOR &&
        training.numRainbow == 0 &&
        training.relationshipBars.isNotEmpty()
    ) {
        var contributions = 0.0
        var qualifyingBars = 0
        for (bar in training.relationshipBars) {
            if ((bar.dominantColor == "green" || bar.dominantColor == "blue") && bar.fillPercent > config.scoring.anticipatoryMinFillPercent) {
                contributions += bar.fillPercent / 100.0
                qualifyingBars += 1
            }
        }
        if (qualifyingBars > 0) {
            anticipatoryMultiplier = 1.0 + minOf(config.scoring.anticipatoryCap, config.scoring.anticipatoryCoefficient * contributions)
            totalScore *= anticipatoryMultiplier
        }
    }

    return RawScoreBreakdown(statScoreWeighted, relationshipScoreWeighted, miscScoreWeighted, rainbowMultiplier, anticipatoryMultiplier, totalScore.coerceAtLeast(0.0))
}

/**
 * Estimate the expected failure chance from current energy. WIT uses an exponential decay (it has lower base failure than other stats); every other stat uses a simple linear
 * formula. Kept here because the bot uses it to validate OCR-detected failure chances.
 *
 * @param currentEnergy Current energy (0-100).
 * @param statName Optional stat name (WIT uses a different formula).
 * @return Expected failure chance percentage in [0, 100].
 */
@JsExport
fun estimateFailureChanceFromEnergy(currentEnergy: Int, statName: StatName? = null): Int {
    val energy = currentEnergy.coerceIn(0, 100)
    val estimated =
        if (statName == StatName.WIT) {
            val raw = 161.4 * (0.9793.pow(energy.toDouble())) - 81.4
            raw.toInt()
        } else {
            if (energy >= 50) 0 else (50 - energy) * 2
        }
    return estimated.coerceIn(0, 100)
}

/**
 * Build a `TrainingScoringConstants` from an arbitrary settings map keyed by the same strings used on the TypeScript side (`scoringConstantsFromSettings()` in
 * `src/lib/training/scoring/scoringConstantsFromSettings.ts`). Any missing or non-numeric value falls back to the matching field in `defaults`. This pure function exists so
 * the Android `scoringConstantsFromSettings()` wrapper can materialize a `Map<String, Any?>` from `SettingsHelper` once and feed it here, sharing the mapping logic with the
 * React Native sandbox.
 *
 * @param settings Map of setting key to value. Numeric values are read as `Number` and converted to Double or Int as appropriate.
 * @param defaults Defaults used when a key is missing or non-numeric.
 * @return A fully populated `TrainingScoringConstants` mirroring the supplied overrides.
 */
@JsExport
fun scoringConstantsFromMap(settings: Map<String, Any?>, defaults: TrainingScoringConstants = TrainingScoringConstants()): TrainingScoringConstants {
    fun d(key: String, fallback: Double): Double = (settings[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fallback

    fun i(key: String, fallback: Int): Int = (settings[key] as? Number)?.toInt() ?: fallback
    return defaults.copy(
        // Breakpoints are fixed and not user-tunable; always sourced from defaults.
        ratioMultipliers =
            listOf(
                d("ratioMultiplier1", defaults.ratioMultipliers[0]),
                d("ratioMultiplier2", defaults.ratioMultipliers[1]),
                d("ratioMultiplier3", defaults.ratioMultipliers[2]),
                d("ratioMultiplier4", defaults.ratioMultipliers[3]),
                d("ratioMultiplier5", defaults.ratioMultipliers[4]),
                d("ratioMultiplier6", defaults.ratioMultipliers[5]),
                d("ratioMultiplier7", defaults.ratioMultipliers[6]),
            ),
        priorityCoefficient = d("priorityCoefficient", defaults.priorityCoefficient),
        levelBoostRank1Factor = d("levelBoostRank1Factor", defaults.levelBoostRank1Factor),
        levelBoostRank2Factor = d("levelBoostRank2Factor", defaults.levelBoostRank2Factor),
        levelBoostRank3Factor = d("levelBoostRank3Factor", defaults.levelBoostRank3Factor),
        mainStatThresholds =
            mapOf(
                StatName.SPEED to i("mainStatThresholdSpeed", defaults.mainStatThresholds[StatName.SPEED]!!),
                StatName.STAMINA to i("mainStatThresholdStamina", defaults.mainStatThresholds[StatName.STAMINA]!!),
                StatName.POWER to i("mainStatThresholdPower", defaults.mainStatThresholds[StatName.POWER]!!),
                StatName.GUTS to i("mainStatThresholdGuts", defaults.mainStatThresholds[StatName.GUTS]!!),
                StatName.WIT to i("mainStatThresholdWit", defaults.mainStatThresholds[StatName.WIT]!!),
            ),
        mainStatBonusMagnitude = d("mainStatBonusMagnitude", defaults.mainStatBonusMagnitude),
        relationshipOrangeValue = d("relationshipOrangeValue", defaults.relationshipOrangeValue),
        relationshipGreenValue = d("relationshipGreenValue", defaults.relationshipGreenValue),
        relationshipBlueValue = d("relationshipBlueValue", defaults.relationshipBlueValue),
        relationshipDiminishingFactor = d("relationshipDiminishingFactor", defaults.relationshipDiminishingFactor),
        relationshipEarlyGameBonus = d("relationshipEarlyGameBonus", defaults.relationshipEarlyGameBonus),
        relationshipTrainerSupportBonus = d("relationshipTrainerSupportBonus", defaults.relationshipTrainerSupportBonus),
        skillHintPerHintScore = d("skillHintPerHintScore", defaults.skillHintPerHintScore),
        skillHintOverrideScore = d("skillHintOverrideScore", defaults.skillHintOverrideScore),
        statWeightWithBars = d("statWeightWithBars", defaults.statWeightWithBars),
        statWeightWithoutBars = d("statWeightWithoutBars", defaults.statWeightWithoutBars),
        relationshipWeightWithBars = d("relationshipWeightWithBars", defaults.relationshipWeightWithBars),
        miscWeight = d("miscWeight", defaults.miscWeight),
        juniorEarlyGameFlatBonus = d("juniorEarlyGameFlatBonus", defaults.juniorEarlyGameFlatBonus),
        relationshipScale = d("relationshipScale", defaults.relationshipScale),
        rainbowMultiplierEnabled = d("rainbowMultiplierEnabled", defaults.rainbowMultiplierEnabled),
        rainbowMultiplierDisabled = d("rainbowMultiplierDisabled", defaults.rainbowMultiplierDisabled),
        rainbowPerInstanceBase = d("rainbowPerInstanceBase", defaults.rainbowPerInstanceBase),
        rainbowPerInstanceDecay = d("rainbowPerInstanceDecay", defaults.rainbowPerInstanceDecay),
        anticipatoryMinFillPercent = d("anticipatoryMinFillPercent", defaults.anticipatoryMinFillPercent),
        anticipatoryCoefficient = d("anticipatoryCoefficient", defaults.anticipatoryCoefficient),
        anticipatoryCap = d("anticipatoryCap", defaults.anticipatoryCap),
    )
}
