package com.steve1316.uma_android_automation.bot.solver

/**
 * Beam-search heuristic. Explores the schedule space turn by turn, keeping the top [DEFAULT_BEAM_WIDTH]
 * partial schedules at each step. Each step expands every active beam into one child per legal
 * decision (locked decision, available race, Train, Rest), scores the child, and re-prunes.
 *
 * The heuristic is deterministic - same [SolverState] in, same beams out - provided the candidate
 * pool ordering is stable. We rely on `state.racesByTurn` already being deterministically sorted
 * by the race key string when constructed by the wiring layer.
 */
object Heuristic {
    /** Default number of beams retained per turn. Higher values trade CPU for schedule quality. */
    const val DEFAULT_BEAM_WIDTH: Int = 32

    /** Last turn of the 72-turn career used as the search horizon. */
    const val LAST_TURN: TurnNumber = 72

    /**
     * Runs the search and returns the highest-scoring schedule found.
     *
     * @param state Initial solver state. The search plans from `state.currentTurn` to [LAST_TURN].
     * @param beamWidth Maximum number of partial schedules retained per step.
     * @return Best [Schedule] found, or an empty one when every beam dies (e.g. an unreachable forced epithet pruned all candidates).
     */
    fun search(state: SolverState, beamWidth: Int = DEFAULT_BEAM_WIDTH): Schedule {
        var beams: List<Beam> = listOf(initialBeam(state))
        for (turn in state.currentTurn..LAST_TURN) {
            val expanded = beams.flatMap { expand(it, turn, state) }
            beams = keepTopK(expanded, beamWidth, state)
            if (beams.isEmpty()) break
        }
        val best =
            beams.maxByOrNull { it.score }
                ?: return Schedule(emptyMap(), emptySet(), 0.0)
        return Schedule(
            decisions = best.decisions.toMap(),
            projectedEpithets = best.completedEpithets,
            totalScore = best.score,
        )
    }

    /**
     * Builds the seed beam at `state.currentTurn` from the inputs in [state]. Any pre-existing
     * race history and completed epithets are carried forward so the search continues from where
     * the run left off rather than re-planning the past.
     *
     * @param state Solver state used to seed the initial beam.
     * @return A [Beam] with empty decisions, the existing history/completions, and a populated `consecutiveRaces` count.
     */
    private fun initialBeam(state: SolverState): Beam =
        Beam(
            decisions = emptyList(),
            raceHistory = state.raceHistory,
            completedEpithets = state.completedEpithets,
            consecutiveRaces = countTrailingRaces(state.raceHistory, state.currentTurn - 1),
            score = 0.0,
        )

    /**
     * Counts how many consecutive races end at [endTurn] in the existing history. The walk stops
     * at the first non-race turn, so a sequence like turns 60, 61, 63 with `endTurn = 63` returns 1.
     *
     * @param history Race-win history. Only turn numbers are inspected.
     * @param endTurn Turn at which to start the walk backward (typically `currentTurn - 1`).
     * @return Number of contiguous race turns ending at [endTurn]. Returns 0 when [endTurn] is < 1 or when
     *   the immediate prior turn has no race.
     */
    private fun countTrailingRaces(history: List<RaceWin>, endTurn: TurnNumber): Int {
        if (endTurn <= 0) return 0
        val turns = history.map { it.turnNumber }.toSet()
        var count = 0
        var t = endTurn
        while (t > 0 && t in turns) {
            count++
            t--
        }
        return count
    }

    /**
     * Returns child beams for [beam] at [turn]. When [turn] has a manual lock in
     * [SolverState.lockedDecisions] the only child is the locked decision. Otherwise children are
     * produced for every eligible unique-win race plus Train and Rest.
     *
     * @param beam Parent beam being expanded.
     * @param turn Turn whose decision is being explored.
     * @param state Solver state providing the candidate pool, locks, and eligibility rules.
     * @return List of child beams (one per legal decision). Never empty in normal operation since
     *   Train and Rest are always available.
     */
    private fun expand(beam: Beam, turn: TurnNumber, state: SolverState): List<Beam> {
        val lock = state.lockedDecisions[turn]
        if (lock != null) return listOf(applyDecision(beam, turn, lock, state))

        val alreadyWon = beam.raceHistory.mapTo(HashSet()) { it.raceKey }
        val racesHere =
            state.racesByTurn[turn].orEmpty()
                .filter { ScoringFunctions.isEligible(it, state) }
                .filter { it.key !in alreadyWon }

        val children = ArrayList<Beam>(racesHere.size + 2)
        // Count only optional races already scheduled (a race turn that is not a locked RaceDecision). Locked/mandatory
        // races short-circuit above and so never count toward the cap.
        val cap = state.maxRaces
        val optionalRacesSoFar =
            if (cap == null) {
                0
            } else {
                beam.decisions.count { (t, d) -> d is Decision.RaceDecision && state.lockedDecisions[t] !is Decision.RaceDecision }
            }
        // Hard cap on back-to-back races: skip race candidates once the chain would exceed maxConsecutiveRaces, unless the
        // landing turn is a Late-Dec free turn (where the cap is waived so a chain may run into year-end).
        val consecutiveCap = state.maxConsecutiveRaces
        val consecutiveCapBlocks =
            consecutiveCap != null && beam.consecutiveRaces + 1 > consecutiveCap && turn !in ScoringFunctions.LATE_DEC_FREE_TURNS
        if ((cap == null || optionalRacesSoFar < cap) && !consecutiveCapBlocks) {
            for (race in racesHere) {
                children += applyDecision(beam, turn, Decision.RaceDecision(race.key), state)
            }
        }
        children += applyDecision(beam, turn, Decision.Train, state)
        children += applyDecision(beam, turn, Decision.Rest, state)
        return children
    }

    /**
     * Produces a child beam by applying [decision] at [turn]. Race decisions delegate to
     * [applyRace]. Train and Rest reset `consecutiveRaces` and add their respective scoring contributions.
     *
     * @param beam Parent beam.
     * @param turn Turn the decision lands on.
     * @param decision The chosen action for this turn.
     * @param state Solver state providing scoring weights.
     * @return New [Beam] reflecting the decision.
     */
    private fun applyDecision(
        beam: Beam,
        turn: TurnNumber,
        decision: Decision,
        state: SolverState,
    ): Beam =
        when (decision) {
            is Decision.RaceDecision -> applyRace(beam, turn, decision, state)
            Decision.Train ->
                beam.copy(
                    decisions = beam.decisions + (turn to decision),
                    consecutiveRaces = 0,
                    score = beam.score + ScoringFunctions.trainValue(state.weights),
                )
            Decision.Rest ->
                beam.copy(
                    decisions = beam.decisions + (turn to decision),
                    consecutiveRaces = 0,
                    score = beam.score + ScoringFunctions.restValue(state.weights),
                )
        }

    /**
     * Applies a race decision: appends the win to the history, re-checks epithet completions
     * against the new history, and folds race-value, epithet-gain, summer-block, and
     * consecutive-race contributions into the beam score.
     *
     * @param beam Parent beam.
     * @param turn Turn the race lands on.
     * @param decision Race decision identifying the chosen race by key.
     * @param state Solver state providing the candidate pool, epithets, and scoring weights.
     * @return New [Beam] with the race appended to history and the updated score. If the race
     *   key cannot be resolved against [state] the beam is returned with the decision recorded but no scoring change.
     */
    private fun applyRace(
        beam: Beam,
        turn: TurnNumber,
        decision: Decision.RaceDecision,
        state: SolverState,
    ): Beam {
        val race =
            state.racesByTurn[turn]?.firstOrNull { it.key == decision.raceKey }
                ?: return beam.copy(decisions = beam.decisions + (turn to decision))

        val newHistory = beam.raceHistory + RaceWin(race.key, race.name, race.classYear, turn)
        val newConsec = beam.consecutiveRaces + 1

        // Re-evaluate epithet completions with the new history (already-completed epithets are
        // not re-checked since their matchers are monotonically satisfied).
        val syntheticState =
            state.copy(
                raceHistory = newHistory,
                completedEpithets = beam.completedEpithets,
            )
        val newlyCompleted =
            state.epithets.filter { epithet ->
                epithet.name !in beam.completedEpithets &&
                    epithet.name !in state.deadEpithets &&
                    EpithetTracker.isCompleted(epithet, syntheticState)
            }

        val baseScore = ScoringFunctions.raceValue(race, state.weights)
        // Mirror the reference Trackblazer solver: every epithet completion contributes its
        // reward to the objective. This is what makes G2/G3 races (which net zero on grade-
        // and-cost alone) competitive - a free epithet reward tips the balance over Train.
        // Forced epithets are still surfaced via the feasibility check in [keepTopK]. Targeted epithets get [Weights.targetEpithetBonus] on top of their reward.
        val epithetGain =
            newlyCompleted.sumOf { ScoringFunctions.epithetContribution(it, state.weights, it.name in state.targetEpithets) }
        val summer = ScoringFunctions.summerBlockPenalty(turn, state)
        val consec = ScoringFunctions.consecutiveRacePenalty(newConsec, turn, state.weights)

        return beam.copy(
            decisions = beam.decisions + (turn to decision),
            raceHistory = newHistory,
            completedEpithets = beam.completedEpithets + newlyCompleted.map { it.name },
            consecutiveRaces = newConsec,
            score = beam.score + baseScore + epithetGain - summer - consec,
        )
    }

    /**
     * Prunes beams that can no longer satisfy a forced epithet, then sorts by score and keeps
     * the top [k]. Stable-sorted by score (desc), tiebroken by decision-list size (asc) to keep ordering deterministic across runs.
     *
     * @param beams Candidate beams produced by the most recent [expand] step.
     * @param k Maximum number of beams to retain.
     * @param state Solver state - used for the forced-epithet feasibility check.
     * @return Up to [k] surviving beams, or an empty list when [beams] is empty.
     */
    private fun keepTopK(beams: List<Beam>, k: Int, state: SolverState): List<Beam> {
        if (beams.isEmpty()) return beams
        val viable =
            beams.filter { beam ->
                state.forcedEpithets.all { name ->
                    name in beam.completedEpithets || canStillComplete(name, state)
                }
            }
        return viable.sortedWith(compareByDescending<Beam> { it.score }.thenBy { it.decisions.size })
            .take(k)
    }

    /**
     * Conservative reachability check: an epithet is considered still completable as long as it
     * is not flagged dead. The full forward feasibility analysis is intentionally deferred -
     * beams that pursue dead-ends are filtered out by the score-based prune in [keepTopK] over time.
     *
     * @param epithetName Epithet to test.
     * @param state Solver state providing the dead-epithet set.
     * @return True if [epithetName] is not in [SolverState.deadEpithets].
     */
    private fun canStillComplete(epithetName: String, state: SolverState): Boolean =
        epithetName !in state.deadEpithets

    /**
     * Internal beam representation. Kept private to discourage external mutation.
     *
     * @property decisions Turn -> decision pairs accumulated so far, in turn order.
     * @property raceHistory All race wins reflected in this beam's score, including the seed history from [SolverState.raceHistory].
     * @property completedEpithets Epithets the beam projects to complete given [raceHistory].
     * @property consecutiveRaces Number of contiguous race turns ending at the most recent
     *   decision. Reset to 0 by Train/Rest.
     * @property score Cumulative beam score: sum of per-turn scoring contributions.
     */
    private data class Beam(
        val decisions: List<Pair<TurnNumber, Decision>>,
        val raceHistory: List<RaceWin>,
        val completedEpithets: Set<String>,
        val consecutiveRaces: Int,
        val score: Double,
    )
}
