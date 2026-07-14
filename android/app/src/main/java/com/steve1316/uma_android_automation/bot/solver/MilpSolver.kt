package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.types.RaceGrade
import org.ojalgo.optimisation.ExpressionsBasedModel
import org.ojalgo.optimisation.Variable

/**
 * Exact Mixed-Integer Linear Programming backend for the Smart Race Solver, mirroring the
 * reference Trackblazer site's `solver-browser.js` GLPK formulation.
 *
 * Decision variables (all binary):
 *  - `x[turn]` - race vs train at each turn from `currentTurn` to LAST_TURN.
 *  - `r[turn][raceKey]` - which specific race is picked. sum(r[turn][*]) = x[turn].
 *  - `y[epithet]` - whether the epithet is completed by the end of the schedule.
 *  - `z[turn]` - third-or-later consecutive race indicator (turns currentTurn+2..LAST_TURN).
 *
 * Objective (maximize):
 *   sum(r[turn][race] * raceValue(race))
 *   + sum(y[epithet] * epithetContribution(epithet))
 *   - sum(z[turn] * consecutiveRacePenalty)   (zero on Late-Dec turns 24, 48, 72)
 *   - sum(x[summer turn] * summerPenalty)
 *
 * Each [EpithetMatcher] becomes one or two linear inequalities tying y[e] to the relevant
 * sum of r-variables and a history-derived constant.
 */
object MilpSolver {
    /** Classic + Senior summer race-blocked turns (Early Jul -> Late Aug). */
    private val CLASSIC_SENIOR_SUMMER_TURNS: Set<TurnNumber> = setOf(37, 38, 39, 40, 61, 62, 63, 64)

    /** Graded races (G1/G2/G3). Used by [EpithetFilter.gradedOnly]. */
    private val GRADED: Set<RaceGrade> = setOf(RaceGrade.G1, RaceGrade.G2, RaceGrade.G3)

    /** Graded plus Open-class races (G1/G2/G3/OP/Pre-OP). Used by [EpithetFilter.gradeAtLeastOpen]. */
    private val GRADED_OR_OPEN: Set<RaceGrade> =
        setOf(RaceGrade.G1, RaceGrade.G2, RaceGrade.G3, RaceGrade.OP, RaceGrade.PRE_OP)

    /**
     * ojAlgo variable names accept only `[A-Za-z0-9_]`. Replace anything else with `_` so that
     * race keys containing spaces, parentheses, or punctuation produce legal variable names.
     *
     * @param s String to sanitize.
     * @return [s] with every non-alphanumeric, non-underscore character replaced by `_`.
     */
    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_]"), "_")

    /**
     * Solve [state] via exact MILP. Returns a [Schedule] equivalent to [Heuristic]'s contract.
     * When the current turn is past [Heuristic.LAST_TURN] there is nothing to plan, so an empty
     * schedule is returned without invoking the solver.
     *
     * @param state Solver state describing aptitudes, races-by-turn, epithets, and weights.
     * @return Optimal [Schedule] under the modelled objective. Returns an empty schedule on infeasibility
     *   or when there are no remaining turns to plan.
     */
    fun solve(state: SolverState): Schedule {
        if (state.currentTurn > Heuristic.LAST_TURN) return Schedule(emptyMap(), emptySet(), 0.0)
        return ModelBuilder(state).build()
    }

    /** One-shot model construction. State is local to a single solve. */
    private class ModelBuilder(private val state: SolverState) {
        private val model = ExpressionsBasedModel()
        private val turns: IntRange = state.currentTurn..Heuristic.LAST_TURN

        // Eligible races per turn (after aptitude filter and removing already-won races).
        private val eligibleByTurn: Map<TurnNumber, List<RaceCandidate>> =
            run {
                val won = state.raceHistory.mapTo(HashSet()) { it.raceKey }
                turns.associateWith { t ->
                    state.racesByTurn[t].orEmpty()
                        .filter { ScoringFunctions.isEligible(it, state) }
                        .filter { it.key !in won }
                }
            }

        // x[turn] - 1 if any race is picked.
        private val xVars: Map<TurnNumber, Variable> =
            turns.associateWith { t ->
                model.newVariable("x_$t").binary()
            }

        // r[turn][raceKey] - picks the specific race.
        private val raceVars: Map<TurnNumber, Map<String, Variable>> =
            turns.associateWith { t ->
                eligibleByTurn[t].orEmpty().associate { race ->
                    race.key to model.newVariable("r_${t}_${sanitize(race.key)}").binary()
                }
            }

        // y[epithetName] - projected to complete. Dead epithets get no variable (forced to 0).
        private val epithetVars: Map<String, Variable> =
            state.epithets
                .filter { it.name !in state.deadEpithets }
                .associate { e -> e.name to model.newVariable("y_${sanitize(e.name)}").binary() }

        // z[turn] - third-or-later consecutive race indicator.
        private val zVars: Map<TurnNumber, Variable> =
            turns
                .filter { t -> (t - 2) in turns }
                .associateWith { t -> model.newVariable("z_$t").binary() }

        /**
         * Wires every constraint and the objective onto [model] then solves it.
         *
         * @return Optimal [Schedule] when the model is feasible. Returns an empty schedule otherwise.
         */
        fun build(): Schedule {
            wireXrConsistency()
            wireSummerHardBlock()
            wireManualLocks()
            wireMaxRaces()
            wireConsecutiveRaceHardCap()
            wireConsecutiveRaceIndicators()
            wireEpithetMatchers()
            wireDependsOn()
            wireForcedEpithets()
            wireObjective()

            val result = model.maximise()
            if (!result.state.isFeasible) {
                return Schedule(emptyMap(), emptySet(), 0.0)
            }
            return extractSchedule(result.value)
        }

        /**
         * Ties race-specific picks back to the per-turn race indicator: `sum(r[t][*]) - x[t] = 0`.
         * Turns with no eligible races have `x[t]` clamped to 0 so the solver cannot select a race there.
         */
        private fun wireXrConsistency() {
            for (t in turns) {
                val races = raceVars[t].orEmpty()
                if (races.isEmpty()) {
                    xVars[t]!!.upper(0.0)
                    continue
                }
                val expr = model.newExpression("xr_$t").lower(0.0).upper(0.0)
                expr.set(xVars[t]!!, -1.0)
                for ((_, v) in races) expr.set(v, 1.0)
            }
        }

        /** When [Weights.allowSummerRacing] is false, force x[t]=0 on Classic/Senior summer turns. */
        private fun wireSummerHardBlock() {
            if (state.weights.allowSummerRacing) return
            for (t in CLASSIC_SENIOR_SUMMER_TURNS) {
                if (t !in turns) continue
                // A mandatory career race can fall on a summer turn (e.g. Elm Stakes on turn 63). It is immovable and must run, so it overrides the
                // summer block. Blocking it here would force x[t] = 0 while wireManualLocks forces x[t] = 1, making the ENTIRE model infeasible -
                // which silently dropped every such preview from MILP to the greedy heuristic.
                if (state.lockedDecisions[t] is Decision.RaceDecision) continue
                xVars[t]!!.upper(0.0)
            }
        }

        /**
         * Applies user-provided manual locks from [SolverState.lockedDecisions]. A Race lock
         * forces `r[turn][raceKey] = 1` (and transitively `x[turn] = 1`). Train/Rest locks force
         * `x[turn] = 0`. A Race lock referencing a key that isn't in the eligible pool falls back
         * to forcing `x[turn] = 0` so the lock still suppresses any other race on that turn.
         */
        private fun wireManualLocks() {
            for ((turn, decision) in state.lockedDecisions) {
                if (turn !in turns) continue
                when (decision) {
                    is Decision.RaceDecision -> {
                        val v = raceVars[turn]?.get(decision.raceKey)
                        if (v != null) v.lower(1.0) else xVars[turn]!!.upper(0.0)
                    }
                    Decision.Train, Decision.Rest -> xVars[turn]!!.upper(0.0)
                }
            }
        }

        /**
         * Caps the number of optional races: `sum(x[t]) <= maxRaces + lockedRaceCount`. Locked Race turns are forced x=1
         * by [wireManualLocks], so they are added back into the bound and never consume the user's budget. No-op when
         * [SolverState.maxRaces] is null.
         */
        private fun wireMaxRaces() {
            val cap = state.maxRaces ?: return
            val lockedRaceTurns =
                state.lockedDecisions.count { (turn, decision) ->
                    turn in turns && decision is Decision.RaceDecision && raceVars[turn]?.get(decision.raceKey) != null
                }
            val expr = model.newExpression("max_races").upper((cap + lockedRaceTurns).toDouble())
            for ((_, v) in xVars) expr.set(v, 1.0)
        }

        /**
         * Hard cap on consecutive races. For every window of (cap + 1) turns ending at turn t, sum(x[t-cap..t]) <= cap, so
         * no chain exceeds [SolverState.maxConsecutiveRaces]. The window whose landing turn is a Late-Dec free turn is
         * skipped so a chain may run into year-end, matching the soft penalty's exemption. No-op when the cap is null.
         */
        private fun wireConsecutiveRaceHardCap() {
            val cap = state.maxConsecutiveRaces?.takeIf { it >= 1 } ?: return
            for (t in turns) {
                if (t - cap < turns.first) continue
                if (t in ScoringFunctions.LATE_DEC_FREE_TURNS) continue
                val expr = model.newExpression("consec_cap_$t").upper(cap.toDouble())
                for (i in (t - cap)..t) expr.set(xVars[i]!!, 1.0)
            }
        }

        /**
         * z[t] >= x[t] + x[t-1] + x[t-2] - 2. Pushed to 1 only when all three are 1, since the objective prefers z=0 (negative weight).
         */
        private fun wireConsecutiveRaceIndicators() {
            for ((t, z) in zVars) {
                val expr = model.newExpression("consec_$t")
                expr.set(z, 1.0)
                expr.set(xVars[t]!!, -1.0)
                expr.set(xVars[t - 1]!!, -1.0)
                expr.set(xVars[t - 2]!!, -1.0)
                // expr = z - x - x - x >= -2
                expr.lower(-2.0)
            }
        }

        /**
         * For every epithet, emits one linear inequality per matcher:
         * `required * y[epithet] <= sum(progress_terms) + history_constant`. With y binary, this
         * forces `y = 1` only when every matcher's running tally (history + future picks) clears its required threshold.
         */
        private fun wireEpithetMatchers() {
            for (epithet in state.epithets) {
                val y = epithetVars[epithet.name] ?: continue
                for ((idx, matcher) in epithet.matchers.withIndex()) {
                    val linearised = linearise(matcher)
                    if (linearised == null) {
                        // The matcher can never be satisfied (a prerequisite epithet was filtered out of the active pool), so the epithet can never
                        // complete - matchers are AND-ed. Force y = 0 and stop. The old code emitted a `y <= -1` bound here, which is infeasible for a
                        // binary variable and made the WHOLE model INFEASIBLE, silently dropping every preview from MILP to the greedy heuristic.
                        y.upper(0.0)
                        break
                    }
                    val (required, terms, constant) = linearised
                    val expr = model.newExpression("ep_${sanitize(epithet.name)}_$idx")
                    // required * y - sum(terms) <= constant
                    expr.set(y, required.toDouble())
                    for ((variable, coeff) in terms) expr.set(variable, expr.get(variable).toDouble() - coeff)
                    expr.upper(constant)
                }
            }
        }

        /**
         * Encodes prerequisite epithet edges so a child epithet cannot complete unless its
         * prerequisites do. Prereqs are read from each child's structured matchers - the
         * legacy `dependsOn` field has been retired now that `EpithetAll` / `EpithetAnyOf` are the source of truth.
         *
         * Each `EpithetAll(names)` adds `y[child] <= y[name]` per name (every prereq is
         * mandatory). Each `EpithetAnyOf(names)` adds the disjunctive `y[child] <= sum y[name]`
         * so completing any one of the candidates is enough. Treating those names individually
         * as hard prereqs would over-constrain the model.
         */
        private fun wireDependsOn() {
            for (epithet in state.epithets) {
                val y = epithetVars[epithet.name] ?: continue
                for (matcher in epithet.matchers) {
                    when (matcher) {
                        is EpithetMatcher.EpithetAll -> {
                            for (prereq in matcher.names) {
                                val parent = epithetVars[prereq] ?: continue
                                val expr = model.newExpression("dep_${sanitize(epithet.name)}_${sanitize(prereq)}")
                                expr.set(y, 1.0).set(parent, -1.0).upper(0.0)
                            }
                        }
                        is EpithetMatcher.EpithetAnyOf -> {
                            val parents = matcher.names.mapNotNull { epithetVars[it] }
                            if (parents.isEmpty()) continue
                            val expr = model.newExpression("depAny_${sanitize(epithet.name)}")
                            expr.set(y, 1.0)
                            for (parent in parents) {
                                expr.set(parent, -1.0)
                            }
                            expr.upper(0.0)
                        }
                        else -> Unit
                    }
                }
            }
        }

        /**
         * Forces `y[name] = 1` for every epithet in [SolverState.forcedEpithets]. Dead epithets
         * have no y-variable, so a forced-but-dead entry is silently skipped - that combination
         * ends up infeasible only when the dead epithet was load-bearing for the user's plan,
         * which the caller can detect via the empty schedule and surface.
         */
        private fun wireForcedEpithets() {
            for (name in state.forcedEpithets) epithetVars[name]?.lower(1.0)
        }

        /**
         * Builds the objective by setting per-variable weights. Each race r-variable gets its
         * [ScoringFunctions.raceValue] minus the summer penalty when applicable. Each epithet
         * y-variable gets its [ScoringFunctions.epithetContribution]. Each consecutive-race
         * z-variable gets a negative weight equal to the configured penalty (zero on Late-Dec
         * turns, mirroring the reference solver's exemption).
         */
        private fun wireObjective() {
            for ((t, races) in raceVars) {
                val byKey = state.racesByTurn[t]?.associateBy { it.key } ?: continue
                for ((key, v) in races) {
                    val race = byKey[key] ?: continue
                    var w = ScoringFunctions.raceValue(race, state.weights)
                    if (t in state.summerBlockTurns) w -= state.weights.summerPenalty
                    v.weight(w)
                }
            }
            for ((name, v) in epithetVars) {
                val epithet = state.epithetsByName[name] ?: continue
                v.weight(ScoringFunctions.epithetContribution(epithet, state.weights))
            }
            for ((t, v) in zVars) {
                if (t in ScoringFunctions.LATE_DEC_FREE_TURNS) continue
                v.weight(-state.weights.consecutiveRacePenalty)
            }
        }

        /**
         * Reads the solved variable values back out of the model and packages them as a
         * [Schedule]. Race turns whose r-variables didn't pin a specific race fall back to
         * Train, which can happen when a manual lock forced x=1 against an empty pool.
         *
         * @param objectiveValue Final objective value reported by ojAlgo, returned as the schedule's `totalScore`.
         * @return [Schedule] containing per-turn decisions, projected epithet completions
         *   (including any pre-existing completions from [SolverState.completedEpithets]),
         *   and the objective value as the score.
         */
        private fun extractSchedule(objectiveValue: Double): Schedule {
            val decisions = HashMap<TurnNumber, Decision>(turns.last - turns.first + 1)
            for (t in turns) {
                val raceOn = (xVars[t]?.value?.toDouble() ?: 0.0) > 0.5
                if (raceOn) {
                    val pickedKey =
                        raceVars[t].orEmpty().entries
                            .firstOrNull { (_, v) -> (v.value?.toDouble() ?: 0.0) > 0.5 }
                            ?.key
                    decisions[t] = if (pickedKey != null) Decision.RaceDecision(pickedKey) else Decision.Train
                } else {
                    decisions[t] = Decision.Train
                }
            }
            return Schedule(decisions, projectedEpithets(decisions), objectiveValue)
        }

        /**
         * Evaluates which epithets the schedule actually completes, against the realized win history rather than the solved `y` variables.
         *
         * The `y` variables cannot be trusted for this. [wireEpithetMatchers] only emits `required * y <= progress`, which *permits* `y = 1` once the
         * matchers are satisfied but never *forces* it, so `y` rises only when the objective pays for it. [ScoringFunctions.epithetContribution] pays
         * nothing for an epithet with no reward bullet, and 200 of the 236 entries in `epithets.json` have none - every reward-bearing epithet happens
         * to be Trackblazer-only. So on any other scenario every `y` carried zero weight and stayed at 0, and the solver reported zero completions even
         * when it had scheduled and won every required race.
         *
         * @param decisions The realized per-turn decisions just read out of the model.
         * @return Names of every epithet the schedule completes, including the pre-existing [SolverState.completedEpithets].
         */
        private fun projectedEpithets(decisions: Map<TurnNumber, Decision>): Set<String> {
            val wins =
                state.raceHistory +
                    decisions.entries.mapNotNull { (turn, decision) ->
                        val raceKey = (decision as? Decision.RaceDecision)?.raceKey ?: return@mapNotNull null
                        state.racesByTurn[turn]
                            ?.firstOrNull { it.key == raceKey }
                            ?.let { RaceWin(it.key, it.name, it.classYear, turn) }
                    }

            // Iterate to a fixed point: the epithetAll / epithetAnyOf matchers gate on other epithets being complete, so
            // completing one epithet can unlock another that depends on it.
            var projected = state.completedEpithets
            while (true) {
                val evaluated = state.copy(raceHistory = wins, completedEpithets = projected)
                val next =
                    projected +
                        state.epithets
                            .filter { it.name !in state.deadEpithets && EpithetTracker.isCompleted(it, evaluated) }
                            .map { it.name }
                if (next == projected) return projected
                projected = next
            }
        }

        // //////////////////////////////////////////////////////////////////////////////////////////////////
        // //////////////////////////////////////////////////////////////////////////////////////////////////
        // Linearisation helpers

        /**
         * Reduces an [EpithetMatcher] to a linear-inequality triple
         * `(required, terms, constantBound)` such that the matcher is satisfied iff
         * `required * y <= sum(coeff*var) + constantBound`. The constant absorbs the contribution
         * of pre-existing race history so the inequality only constrains future picks.
         *
         * Returns null when the matcher can never be satisfied - a dependency matcher (`epithetAnyOf`, `epithetAll`) whose prerequisite epithets are
         * all absent from the active pool. The caller forces `y = 0` for that epithet. (Do NOT encode this as a `y <= -1` bound: that is infeasible
         * for a binary variable and makes the entire model infeasible.)
         *
         * @param matcher Matcher to linearise.
         * @return Triple `(required, terms, constantBound)`, or null when the matcher is structurally unsatisfiable (see above).
         */
        private fun linearise(matcher: EpithetMatcher): Triple<Int, Map<Variable, Double>, Double>? {
            return when (matcher) {
                is EpithetMatcher.WinRace -> {
                    // Need 1 win of `name` (optionally at class).
                    val terms = sumRaceVars(matcher.name, matcher.atClass)
                    val historyHits =
                        state.raceHistory.count {
                            it.name == matcher.name && (matcher.atClass == null || it.classYear.equals(matcher.atClass, ignoreCase = true))
                        }
                    Triple(1, terms, historyHits.toDouble())
                }
                is EpithetMatcher.WinRaceTimes -> {
                    val terms = sumRaceVars(matcher.name)
                    val historyHits = state.raceHistory.count { it.name == matcher.name }
                    Triple(matcher.times, terms, historyHits.toDouble())
                }
                is EpithetMatcher.WinAnyOf -> {
                    val terms = HashMap<Variable, Double>()
                    var historyHits = 0
                    for (name in matcher.names) {
                        for ((v, c) in sumRaceVars(name, matcher.atClass)) {
                            terms[v] = (terms[v] ?: 0.0) + c
                        }
                        historyHits +=
                            state.raceHistory.count {
                                it.name == name && (matcher.atClass == null || it.classYear.equals(matcher.atClass, ignoreCase = true))
                            }
                    }
                    Triple(matcher.count, terms, historyHits.toDouble())
                }
                is EpithetMatcher.WinAtLeast -> {
                    val terms = HashMap<Variable, Double>()
                    var historyHits = 0
                    for (name in matcher.names) {
                        for ((v, c) in sumRaceVars(name)) terms[v] = (terms[v] ?: 0.0) + c
                        historyHits += state.raceHistory.count { it.name == name }
                    }
                    Triple(matcher.count, terms, historyHits.toDouble())
                }
                is EpithetMatcher.WinCount -> {
                    val terms = sumRaceVarsByFilter(matcher.filter)
                    val historyHits = state.raceHistory.count { historyMatchesFilter(it, matcher.filter) }
                    Triple(matcher.count, terms, historyHits.toDouble())
                }
                is EpithetMatcher.EpithetAnyOf -> {
                    // 1*y[e] <= sum(y[name]) - at least one of the named epithets must be completed.
                    val others = matcher.names.mapNotNull { epithetVars[it] }
                    if (others.isEmpty()) return null // no prerequisite is in the pool, so the epithet is unreachable - caller forces y = 0
                    val terms = others.associateWith { 1.0 }
                    Triple(1, terms, 0.0)
                }
                is EpithetMatcher.EpithetAll -> {
                    // N*y[e] <= sum(y[name]) - every prereq must be completed. A missing prereq makes the AND unsatisfiable.
                    val others = matcher.names.mapNotNull { epithetVars[it] }
                    if (others.size != matcher.names.size) return null // a prerequisite was filtered out of the pool, so the epithet is unreachable - caller forces y = 0
                    val terms = others.associateWith { 1.0 }
                    Triple(matcher.names.size, terms, 0.0)
                }
            }
        }

        /**
         * Collects every r-variable whose underlying race has the given [raceName] (and, when
         * provided, the matching [atClass] year). Coefficients are 1.0 - duplicates across turns
         * sum, which is what the matcher inequality wants for "win N races named X" semantics.
         *
         * @param raceName Race name to match against [RaceCandidate.name].
         * @param atClass Optional class-year filter ("Junior", "Classic", "Senior"). Null disables the filter.
         * @return Map of r-variable to its coefficient (always 1.0).
         */
        private fun sumRaceVars(raceName: String, atClass: String? = null): Map<Variable, Double> {
            val out = HashMap<Variable, Double>()
            for (t in turns) {
                val byKey = raceVars[t] ?: continue
                val candidates = state.racesByTurn[t] ?: continue
                for (race in candidates) {
                    if (race.name != raceName) continue
                    if (atClass != null && !race.classYear.equals(atClass, ignoreCase = true)) continue
                    val v = byKey[race.key] ?: continue
                    out[v] = (out[v] ?: 0.0) + 1.0
                }
            }
            return out
        }

        /**
         * Collects every r-variable whose underlying race satisfies the [filter] predicate. Used for [EpithetMatcher.WinCount] linearisation.
         *
         * @param filter Filter predicate from a `winCount` matcher.
         * @return Map of r-variable to its coefficient (always 1.0).
         */
        private fun sumRaceVarsByFilter(filter: EpithetFilter): Map<Variable, Double> {
            val out = HashMap<Variable, Double>()
            for (t in turns) {
                val byKey = raceVars[t] ?: continue
                val candidates = state.racesByTurn[t] ?: continue
                for (race in candidates) {
                    if (!matchesFilter(race, filter)) continue
                    val v = byKey[race.key] ?: continue
                    out[v] = (out[v] ?: 0.0) + 1.0
                }
            }
            return out
        }

        /**
         * Looks up the [RaceCandidate] for [win] and tests it against [filter]. Used to compute
         * the history-derived constant in `winCount` matcher linearisation.
         *
         * @param win Race win from [SolverState.raceHistory].
         * @param filter Filter predicate from a `winCount` matcher.
         * @return True if the win's underlying race matches. False when the candidate cannot be
         *   resolved or any field rejects it.
         */
        private fun historyMatchesFilter(win: RaceWin, filter: EpithetFilter): Boolean {
            val race = state.racesByTurn[win.turnNumber]?.firstOrNull { it.key == win.raceKey } ?: return false
            return matchesFilter(race, filter)
        }

        /**
         * Predicate equivalent to [EpithetTracker.matchesFilter] but operating directly on a
         * resolved [RaceCandidate] (callers in this class already know the candidate). Kept in
         * lockstep with the runtime tracker so the solver projects the same completions the runtime will actually award.
         *
         * @param c Race candidate to test.
         * @param f Filter predicate.
         * @return True if every non-null/non-empty field of [f] accepts [c].
         */
        private fun matchesFilter(c: RaceCandidate, f: EpithetFilter): Boolean {
            if (f.terrain != null && c.terrain != f.terrain) return false
            if (f.grade != null && c.grade != f.grade) return false
            if (f.gradeAtLeastOpen && c.grade !in GRADED_OR_OPEN) return false
            if (f.gradedOnly && c.grade !in GRADED) return false
            if (f.distanceTypes.isNotEmpty() && c.distanceType !in f.distanceTypes) return false
            if (f.raceTracks.isNotEmpty() && c.raceTrack !in f.raceTracks) return false
            if (f.nameContains != null && !c.name.contains(f.nameContains, ignoreCase = true)) return false
            if (f.nameContainsCountry && !EpithetFilters.nameContainsCountry(c.name)) return false
            return true
        }
    }
}
