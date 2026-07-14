package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.RaceGrade

/**
 * Pure scoring helpers consumed by the heuristic. Each function takes the minimum required
 * inputs and returns an additive contribution to the beam's objective. The heuristic sums them.
 *
 * Race value is grade-and-fans-driven. Epithet contribution is reward-magnitude-driven. Penalties
 * (summer block, 3-consecutive-race conditioning) subtract. Aptitude eligibility is a hard filter
 * applied upstream - see [isEligible].
 */
object ScoringFunctions {
    /**
     * Base stat reward per race grade, mirroring the reference Trackblazer solver's
     * `BASE_REWARD` table. Grades not in the table (Maiden, Debut, Finale, EX) score zero
     * gross reward, leaving them well below cost so the solver always trains over them.
     *
     * @param grade Race grade to look up.
     * @return Base stat reward for [grade]. Returns 0.0 for grades outside the BASE_REWARD table.
     */
    private fun baseStat(grade: RaceGrade): Double =
        when (grade) {
            RaceGrade.G1 -> 10.0
            RaceGrade.G2, RaceGrade.G3 -> 8.0
            RaceGrade.OP -> 5.0
            RaceGrade.PRE_OP -> 5.0
            else -> 0.0
        }

    /**
     * Base skill-point reward per race grade. See [baseStat] for the gating semantics.
     *
     * @param grade Race grade to look up.
     * @return Base skill-point reward for [grade]. Returns 0.0 for grades outside the BASE_REWARD table.
     */
    private fun baseSp(grade: RaceGrade): Double =
        when (grade) {
            RaceGrade.G1 -> 35.0
            RaceGrade.G2, RaceGrade.G3 -> 25.0
            RaceGrade.OP -> 15.0
            RaceGrade.PRE_OP -> 10.0
            else -> 0.0
        }

    /**
     * Baseline used to scale [Weights.raceCostPct] into a concrete subtracted cost. Graded races
     * (G1/G2/G3) compare against the G2 baseline (matching the reference solver's `g2g3Baseline`)
     * so G1 races net positive and G2/G3 net zero. OP/Pre-OP races compare against their own
     * grade's baseline so they net zero by default instead of strongly negative. Without this,
     * weak presets like Haru Urara whose only eligible races are OP/Pre-OP would never schedule
     * any race even with [Weights.includeOpAndPreOp] enabled, since cost-vs-G2 dominates the
     * tiny OP reward. Epithet contributions can still tip OP races positive.
     *
     * With default weights:
     *  - G2 baseline (used by G1/G2/G3): `1*12 + 1*37 = 49`
     *  - OP baseline:    `1*7 + 1*22 = 29`
     *  - Pre-OP baseline:`1*7 + 1*15 = 22`
     *
     * @param grade Race grade whose cost basis to compute.
     * @param weights Weights providing [Weights.raceBonusPct], [Weights.statWeight], and
     *   [Weights.spWeight].
     * @return Weighted cost baseline in score units.
     */
    private fun costBaseline(grade: RaceGrade, weights: Weights): Double {
        val rb = weights.raceBonusPct.coerceAtLeast(0.0) / 100.0
        val baselineGrade =
            when (grade) {
                RaceGrade.OP -> RaceGrade.OP
                RaceGrade.PRE_OP -> RaceGrade.PRE_OP
                else -> RaceGrade.G2
            }
        val stat = Math.floor(baseStat(baselineGrade) * (1.0 + rb))
        val sp = Math.floor(baseSp(baselineGrade) * (1.0 + rb))
        return weights.statWeight * stat + weights.spWeight * sp
    }

    /**
     * Returns the value of running a single race, ignoring epithet contributions.
     *
     * Direct port of the reference Trackblazer solver's `weightedRaceValue`, extended with a
     * tunable per-fan term so users can opt into a fan-weighted optimization mode:
     *   gross = statWeight * floor(baseStat * (1 + raceBonus)) + spWeight * floor(baseSp * (1 + raceBonus))
     *   cost  = (raceCostPct / 100) * costBaseline(grade)
     *   value = (gross - cost) * raceValue + fans * fanWeight
     *
     * With defaults (raceBonus 50, raceCost 100, fanWeight 0) G2/G3 net to zero gross-cost - they
     * tie with Train (which carries a tiny positive anti-race bias in [trainValue]) and are skipped
     * unless an epithet pushes them positive. Maiden/Debut/Finale/EX have zero gross reward so they
     * always score well below Train. With `fanWeight = 0.0` the legacy "Stat Epitaphs" behavior is
     * preserved; with a non-zero `fanWeight` (the "Fans + Epitaphs" preset uses 1e-3) fan-rich races
     * such as G1s become more attractive without dominating epithet contributions.
     *
     * @param race Race candidate to score.
     * @param weights Active scoring weights.
     * @return Net contribution to the objective if [race] is picked. Positive values prefer this
     *   race over Train. Negative values prefer Train.
     */
    fun raceValue(race: RaceCandidate, weights: Weights): Double {
        val rb = weights.raceBonusPct.coerceAtLeast(0.0) / 100.0
        val stat = Math.floor(baseStat(race.grade) * (1.0 + rb))
        val sp = Math.floor(baseSp(race.grade) * (1.0 + rb))
        val gross = weights.statWeight * stat + weights.spWeight * sp
        val cost = weights.raceCostPct / 100.0 * costBaseline(race.grade, weights)
        return (gross - cost) * weights.raceValue + race.fans * weights.fanWeight
    }

    /**
     * Training is the default action. Returns a constant `1.0` anti-race bias so that whenever a
     * race's `(gross - cost) * raceValue + fans * fanWeight` equals zero, Train wins the tie. With
     * the default Stat Epitaphs preset (`fanWeight = 0`) this matches legacy behavior; with a
     * non-zero `fanWeight` the user has explicitly asked the solver to weigh fans, so Train no
     * longer auto-wins fan-heavy ties. The reference solver achieves the same effect via GLPK's
     * MILP picking `NO_RACE` on ties.
     *
     * @param weights Active weights (currently unused, reserved for future tuning).
     * @return Constant `1.0`.
     */
    fun trainValue(
        @Suppress("UNUSED_PARAMETER") weights: Weights,
    ): Double = 1.0

    /**
     * Resting yields no scoring contribution. Energy is not modelled in the static preview.
     *
     * @param weights Active weights (currently unused).
     * @return Constant `0.0`.
     */
    fun restValue(
        @Suppress("UNUSED_PARAMETER") weights: Weights,
    ): Double = 0.0

    /**
     * Reward magnitude of completing [epithet]. Stat rewards return the amount derived from the reward bullet via [EpithetFilters.rewardFromBullets].
     * Hint rewards return [Weights.hintWeight]. Unknown rewards return zero. The result is then scaled by [Weights.epithetValue].
     *
     * A targeted epithet then gets [Weights.targetEpithetBonus] added on top. That bonus is what makes target selection mean anything: most epithets list no
     * reward bullet at all, so their reward is worth 0 and, without the bonus, the solver would have no reason to pursue one the user explicitly picked.
     *
     * @param epithet Completed epithet whose reward should be valued.
     * @param weights Active weights providing [Weights.hintWeight], [Weights.epithetValue], and [Weights.targetEpithetBonus].
     * @param isTargeted Whether the user named this epithet in [SolverState.targetEpithets].
     * @return Score contribution if [epithet] is completed.
     */
    fun epithetContribution(epithet: Epithet, weights: Weights, isTargeted: Boolean = false): Double {
        val (kind, amount) = EpithetFilters.rewardFromBullets(epithet.bullets)
        val base =
            when (kind) {
                "stat" -> amount.toDouble()
                "hint" -> weights.hintWeight
                else -> 0.0
            }
        return base * weights.epithetValue + if (isTargeted) weights.targetEpithetBonus else 0.0
    }

    /**
     * Turns where landing the third+ consecutive race incurs zero conditioning penalty, and where the consecutive-race
     * hard cap is waived. These are the Late-December halves (last turn) of each class year - Junior (24), Classic (48),
     * and Senior (72). Shared by the soft penalty, the MILP objective, and the consecutive-race hard cap.
     */
    val LATE_DEC_FREE_TURNS: Set<TurnNumber> = setOf(24, 48, 72)

    /**
     * Penalty applied when scheduling a third (or later) consecutive race. The reference
     * solver penalises the *start* of a 3-race chain. We apply it on every additional race
     * past the second to keep beams deterministic and incremental. Returns zero on Late-Dec
     * turns (24, 48, 72) to match the reference's end-of-year exemption.
     *
     * @param consecutiveRaceCount Number of consecutive races including the current one.
     * @param turn Turn the third+ race lands on. Checked against [LATE_DEC_FREE_TURNS].
     * @param weights Active weights providing [Weights.consecutiveRacePenalty].
     * @return The configured penalty when the chain is >= 3 and [turn] is not Late-Dec, else 0.0.
     */
    fun consecutiveRacePenalty(
        consecutiveRaceCount: Int,
        turn: TurnNumber,
        weights: Weights,
    ): Double {
        if (consecutiveRaceCount < 3) return 0.0
        if (turn in LATE_DEC_FREE_TURNS) return 0.0
        return weights.consecutiveRacePenalty
    }

    /**
     * Penalty for racing on a turn flagged as a summer training block.
     *
     * @param turn Turn the race lands on.
     * @param state Solver state providing [SolverState.summerBlockTurns] and [SolverState.weights].
     * @return [Weights.summerPenalty] when [turn] is in the summer block set, else 0.0.
     */
    fun summerBlockPenalty(turn: TurnNumber, state: SolverState): Double =
        if (turn in state.summerBlockTurns) state.weights.summerPenalty else 0.0

    /** Grades excluded by default unless [Weights.includeOpAndPreOp] is true. Mirrors the
     *  reference Trackblazer site's `OP_GRADES` set + `include_op` toggle (default false). */
    private val OP_GRADES: Set<RaceGrade> = setOf(RaceGrade.OP, RaceGrade.PRE_OP)

    /**
     * Hard eligibility check: a race is eligible only if both the matching distance aptitude
     * and surface aptitude meet [Weights.aptitudeThreshold]. OP/Pre-OP grades are also gated by
     * [Weights.includeOpAndPreOp]. Below threshold, the race is dropped from the candidate set
     * entirely.
     *
     * @param race Race candidate to test.
     * @param state Solver state providing aptitudes and weights.
     * @return True if the race passes the OP filter (when applicable) and both aptitudes meet
     *   the threshold. False otherwise.
     */
    fun isEligible(race: RaceCandidate, state: SolverState): Boolean {
        if (race.grade in OP_GRADES && !state.weights.includeOpAndPreOp) return false
        val distApt = state.aptitudes.forDistance(race.distanceType)
        val surfApt = state.aptitudes.forSurface(race.terrain)
        val threshold = state.weights.aptitudeThreshold
        return distApt.atLeast(threshold) && surfApt.atLeast(threshold)
    }

    /**
     * Returns true when the receiver aptitude is at least as good as [other]. Higher ordinal =
     * better grade since [Aptitude] is ordered G,F,E,D,C,B,A,S.
     *
     * @param other Threshold aptitude to compare against.
     * @return True if the receiver's ordinal is >= [other]'s ordinal.
     */
    private fun Aptitude.atLeast(other: Aptitude): Boolean = this.ordinal >= other.ordinal
}
