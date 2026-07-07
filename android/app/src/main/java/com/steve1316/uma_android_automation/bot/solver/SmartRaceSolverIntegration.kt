package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Racing.RaceData
import com.steve1316.uma_android_automation.bot.RunAnalytics
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integration layer between Racing.kt and the pure [SmartRaceSolver].
 *
 * Owns:
 *  - Lazy parsing of epithets / character-preset JSON pushed in by the React Native side via
 *    [SettingsHelper] string settings.
 *  - In-memory race-win history accumulated during a single bot run.
 *  - Translating the solver's [Decision] into a concrete on-screen [RaceData] pick.
 *
 * Stateless from the bot's perspective except for [raceHistory] and the cached parsed data;
 * [reset] clears that state on a new run.
 */
object SmartRaceSolverIntegration {
    private const val TAG: String = "[SMART_RACE_SOLVER]"

    /** Junior turns 1..13 (Early Jan -> Early Jul) are the in-game pre-debut period with no
     *  races, so the OCR-driven Career -> Race History scrape is skipped at or below this
     *  turn and the existing Preview-based seed runs instead. */
    private const val PRE_DEBUT_TURN_THRESHOLD: TurnNumber = 13

    /** Calendar turn used for the synthetic Junior Make Debut display row. The viewer's date-label scheme renders this turn as "Late Jun",
     *  matching the in-game date the Make Debut race actually occurs on. The cell sits inside the pre-debut block; the renderer suppresses
     *  the pre-debut style when a synthetic result is attached. */
    private const val MAKE_DEBUT_DISPLAY_TURN: TurnNumber = 12

    /** Wins for the current run - both confirmed in-game finishes and Preview-assumed wins
     *  added on a mid-run restart. Cleared by [reset]. Guarded by its own monitor since the
     *  bot and UI threads can read/write here concurrently. */
    private val raceHistory: MutableList<RaceWin> = mutableListOf()

    /** Sibling collection to [raceHistory] that holds confirmed losses for the Remote Log
     *  Viewer calendar. The solver itself never reads this list - losses do not count toward
     *  epithet eligibility. Always synchronize on [raceLosses] before mutating or copying. */
    private val raceLosses: MutableList<RaceLossRecord> = mutableListOf()

    /** Memoised result of [parseEpithets] applied to the persisted `epithetsData` setting.
     *  Populated lazily on the first solver call and reused thereafter. */
    @Volatile private var cachedEpithets: List<Epithet>? = null

    /** Memoised result of [parsePresets] applied to the persisted `characterPresetsData`
     *  setting. Populated lazily by [loadCharacterPresets]. */
    @Volatile private var cachedPresets: Map<String, Aptitudes>? = null

    /** Memoised result of [parseRacesData] applied to the persisted `racesData` setting.
     *  Used as a fallback when the JS bridge does not ship inline races JSON. */
    @Volatile private var cachedRaces: Map<TurnNumber, List<RaceCandidate>>? = null

    /** Caches the most recent inline races payload from the JS bridge so subsequent preview
     *  calls can omit the field. Keyed by hashCode of the JSON to invalidate when content
     *  changes. The pair is `(hash, parsedValue)`. */
    @Volatile private var cachedInlineRaces: Pair<Int, Map<TurnNumber, List<RaceCandidate>>>? = null

    /** Same idea as [cachedInlineRaces] but for the inline epithets payload. The pair is
     *  `(hash, parsedValue)`. */
    @Volatile private var cachedInlineEpithets: Pair<Int, List<Epithet>>? = null

    @Volatile private var cachedObjectives: Map<String, List<CharacterMandatoryRace>>? = null

    @Volatile private var cachedInlineObjectives: Pair<Int, Map<String, List<CharacterMandatoryRace>>>? = null

    /** Race staged by [markPendingRace] awaiting an outcome confirmation via [commitPendingRace]. */
    @Volatile private var pendingRace: RaceWin? = null

    /** True after [seedHistoryFromPreview] has populated [raceHistory] for the current run. */
    @Volatile private var historySeeded: Boolean = false

    /** True after the synthetic Junior Make Debut display log line has fired for the current run. Cleared by [reset]. The synthetic
     *  calendar entry itself is generated on-the-fly inside [buildCalendarSnapshotJson] and is not retained anywhere else. */
    private val debutDisplayLogged: AtomicBoolean = AtomicBoolean(false)

    /** OCR-scraped Junior Make Debut row, captured by [seedHistoryFromCareerScrape] when available. Null when the scrape was
     *  skipped or the row was missing. The formatted name and won/lost outcome are surfaced in the Remote Log Viewer tooltip via the
     *  synthetic JSON entry built in [buildCalendarSnapshotJson]. Cleared by [reset]. */
    @Volatile private var scrapedDebutEntry: RaceHistory.RaceHistoryEntry? = null

    /** Most recent currentTurn observed by [runStartupHooks] or [markPendingRace]. Used as the
     *  pivot when building a calendar snapshot for the Remote Log Viewer. */
    @Volatile private var currentRunTurn: TurnNumber = 1

    /** Scenario captured at [runStartupHooks] time, fed back into the solver when re-running a
     *  preview for the Remote Log Viewer calendar. */
    @Volatile private var currentRunScenario: String = ""

    /** Schedule produced by the most recent [buildCalendarSnapshotJson] solve. The plan is locked in once and reused by every peek
     *  and broadcast for the rest of the run. Cleared by [reset] and refreshed only by a loss commit (see [commitPendingRace]). */
    @Volatile private var cachedSchedule: Schedule? = null

    /** Turn that [cachedSchedule] was solved against. Used to invalidate the cache when the bot has advanced to a new turn. */
    @Volatile private var cachedScheduleTurn: TurnNumber = -1

    /** Clears in-memory race history and pending state. Call at the start of a fresh bot run. */
    fun reset() {
        synchronized(raceHistory) { raceHistory.clear() }
        synchronized(raceLosses) { raceLosses.clear() }
        pendingRace = null
        historySeeded = false
        debutDisplayLogged.set(false)
        scrapedDebutEntry = null
        cachedSchedule = null
        cachedScheduleTurn = -1
    }

    /**
     * Adds a loss to the run's race-loss collection. Used for confirmed in-run losses
     * (the trainee finished outside 1st) and for OCR career-scrape entries with `won=false`.
     * Idempotent on `(raceKey, turnNumber)` so retries do not duplicate.
     *
     * @param raceKey Race key (matches [RaceCandidate.key]).
     * @param raceName Race name (matches [RaceCandidate.name]).
     * @param classYear Class-year prefix at the time of the race.
     * @param turnNumber Turn the loss occurred on.
     * @param strategy Running style OCR'd from the Race History scrape. Empty for live in-run losses.
     */
    fun recordRaceLost(raceKey: String, raceName: String, classYear: String, turnNumber: TurnNumber, strategy: String = "") {
        synchronized(raceLosses) {
            if (raceLosses.none { it.raceKey == raceKey && it.turnNumber == turnNumber }) {
                raceLosses.add(RaceLossRecord(raceKey, raceName, classYear, turnNumber, strategy))
            }
        }
    }

    /**
     * Adds a win to the run's race history. Used for confirmed in-run wins and for
     * Preview-assumed wins on a mid-run restart. Idempotent on `(raceKey, turnNumber)` so
     * retries don't double-count.
     *
     * @param raceKey Race key (matches [RaceCandidate.key]).
     * @param raceName Race name (matches [RaceCandidate.name]).
     * @param classYear Class-year prefix at the time of the win.
     * @param turnNumber Turn the win occurred on.
     * @param strategy Running style OCR'd from the Race History scrape. Empty for live in-run wins and preview-assumed wins.
     */
    fun recordRaceWon(raceKey: String, raceName: String, classYear: String, turnNumber: TurnNumber, strategy: String = "") {
        synchronized(raceHistory) {
            if (raceHistory.none { it.raceKey == raceKey && it.turnNumber == turnNumber }) {
                raceHistory.add(RaceWin(raceKey, raceName, classYear, turnNumber, strategy))
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Junior Make Debut display-only entry

    /**
     * Emits a single MessageLog line announcing the synthetic Junior Make Debut entry shown in the Remote Log Viewer calendar. Idempotent
     * for the lifetime of the current run via [debutDisplayLogged]. Skips when the run is still inside the pre-debut window so brand-new
     * runs do not log a future event. The synthetic entry is display-only and never enters [raceHistory], so it has no effect on the
     * solver, epithets, or schedule preview.
     *
     * @param currentTurn The turn the bot is currently observing.
     */
    private fun logSyntheticDebutOnce(currentTurn: TurnNumber) {
        if (currentTurn <= PRE_DEBUT_TURN_THRESHOLD) return
        if (!debutDisplayLogged.compareAndSet(false, true)) return
        MessageLog.i(TAG, "Race History: Junior Year Late Jun - Make Debut (Won)")
    }

    /**
     * Stages an in-progress race so the runtime can decide later whether to commit it to
     * [raceHistory] based on the post-race results screen. Overwrites any prior pending entry
     * (a stale pending entry is treated as "lost" by the next [commitPendingRace] call).
     *
     * @param raceKey Race key (matches [RaceCandidate.key]).
     * @param raceName Race name (matches [RaceCandidate.name]).
     * @param classYear Class-year prefix at the time of the attempt.
     * @param turnNumber Turn the race is being attempted on.
     */
    fun markPendingRace(raceKey: String, raceName: String, classYear: String, turnNumber: TurnNumber) {
        pendingRace = RaceWin(raceKey, raceName, classYear, turnNumber)
        currentRunTurn = turnNumber
    }

    /**
     * Once-per-run hook that logs the Preview schedule and seeds [raceHistory] for the
     * current run. Safe to call every turn - only the first call after [reset] does any
     * work. Intended to run right after the date is detected so the schedule log appears
     * before shop/item dialogs.
     *
     * When [currentTurn] is past the in-game pre-debut period,
     * this opens the Career -> Race History dialog and reads the trainee's actual past
     * wins via OCR. On any failure, it falls back to assuming every previewed race
     * was won.
     *
     * @param game Active Game instance for tap/screenshot/OCR access. Null for callers
     *   that cannot drive the screen (the OCR scrape is skipped in that case).
     * @param currentTurn The turn the bot is on.
     * @param scenario Active scenario name from `settings.general.scenario`.
     */
    fun runStartupHooks(game: Game?, currentTurn: TurnNumber, scenario: String) {
        if (!SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver")) return

        // Refresh the pivot turn on every call so the Race History snapshot stays in sync with the bot's current turn. The history-seeding
        // work below is gated by historySeeded and only runs once, but the Remote Log Viewer's calendar badge depends on currentRunTurn
        // matching whatever turn the bot is observing right now.
        val turnChanged = currentRunTurn != currentTurn
        currentRunTurn = currentTurn
        currentRunScenario = scenario

        if (historySeeded) {
            // Seeding already happened on an earlier turn. Re-emit the snapshot reusing the cached schedule when the turn ticks forward
            // so the Race History panel updates the badge without re-running the solver - the plan stays locked in until a loss forces
            // a replan via [commitPendingRace].
            if (turnChanged) broadcastCalendarSnapshot(reuseSchedule = true)
            return
        }

        val epithets = loadEpithets() ?: return
        val racesByTurn = loadAllRaces() ?: return

        val seededFromOcr =
            if (game != null && currentTurn > PRE_DEBUT_TURN_THRESHOLD) {
                seedHistoryFromCareerScrape(game, racesByTurn)
            } else {
                false
            }

        if (!seededFromOcr) {
            seedHistoryFromPreview(currentTurn, scenario, epithets, racesByTurn)
        }
        historySeeded = true
        broadcastCalendarSnapshot()
        logSyntheticDebutOnce(currentTurn)
    }

    /**
     * Records the staged race as a win when [won] is true, or drops it otherwise. No-op
     * when nothing is pending.
     *
     * @param won True when the race finished 1st (LabelCongratulations detected).
     */
    fun commitPendingRace(won: Boolean) {
        val pending = pendingRace ?: return
        pendingRace = null
        recordRaceForAnalytics(pending, won)
        if (!won) {
            recordRaceLost(pending.raceKey, pending.name, pending.classYear, pending.turnNumber)
            MessageLog.i(TAG, "Race \"${pending.name}\" on turn ${pending.turnNumber} did not finish 1st; recorded as a loss.")
            // When the user disables re-planning on a loss, keep the originally locked-in schedule instead of re-solving the remaining turns.
            val keepSchedule = SettingsHelper.getBooleanSetting("racing", "disableScheduleReplanOnRaceLoss")
            if (keepSchedule) MessageLog.i(TAG, "Schedule re-plan on loss is disabled; keeping the original plan.")
            broadcastCalendarSnapshot(reuseSchedule = keepSchedule)
            return
        }
        val historyBefore = synchronized(raceHistory) { raceHistory.toList() }
        recordRaceWon(pending.raceKey, pending.name, pending.classYear, pending.turnNumber)
        val historyAfter = synchronized(raceHistory) { raceHistory.toList() }
        MessageLog.i(TAG, "Race \"${pending.name}\" on turn ${pending.turnNumber} confirmed 1st; added to history.")
        logEpithetProgressAfterWin(pending.name, pending.turnNumber, historyBefore, historyAfter)
        broadcastCalendarSnapshot(reuseSchedule = true)
        logSyntheticDebutOnce(pending.turnNumber)
    }

    /**
     * Mirror a committed race outcome into [RunAnalytics] for the Analytics tab. Looks up the full race details (grade, surface, distance, fans) from the
     * cached candidate pool so the analytics snapshot carries the same data the calendar shows.
     *
     * @param pending The race that was just confirmed won or lost.
     * @param won True when the trainee finished 1st.
     */
    private fun recordRaceForAnalytics(pending: RaceWin, won: Boolean) {
        val candidate = cachedRaces?.let { findCandidate(pending.turnNumber, pending.raceKey, pending.name, it) }
        RunAnalytics.recordRace(
            turn = pending.turnNumber,
            name = pending.name,
            grade = candidate?.grade?.name ?: "",
            surface = candidate?.terrain?.name ?: "",
            distance = candidate?.distanceType?.name ?: "",
            fans = candidate?.fans ?: 0,
            won = won,
            mandatory = candidate?.isMandatory == true,
        )
    }

    /**
     * Logs the epithets the just-confirmed race progresses, with each epithet's aggregated
     * `(satisfied / required)` count both before and after the win so the contribution of
     * this race is obvious. Aggregation matches the frontend popover (sums per-matcher `(current, required)` across non-dependency matchers).
     *
     * @param raceName The just-confirmed race name (matches [RaceCandidate.name]).
     * @param turnNumber Turn the race ran on. Used to look up the [RaceCandidate] for filter checks.
     * @param historyBefore Snapshot of [raceHistory] before [recordRaceWon] added this win.
     * @param historyAfter Snapshot of [raceHistory] after [recordRaceWon] added this win.
     */
    private fun logEpithetProgressAfterWin(raceName: String, turnNumber: TurnNumber, historyBefore: List<RaceWin>, historyAfter: List<RaceWin>) {
        val epithets = epithetsForActiveContext(cachedEpithets ?: loadEpithets() ?: return, currentRunScenario, SettingsHelper.getStringSetting("racing", "smartRaceSolverCharacterPreset"))
        val racesByTurn = cachedRaces ?: loadAllRaces() ?: return
        val race = racesByTurn[turnNumber]?.firstOrNull { it.name == raceName }
        val candidates = epithets.filter { epi -> epi.matchers.any { matcherReferencesRace(it, raceName, race) } }
        if (candidates.isEmpty()) return
        val stateBefore = newSolverState(currentTurn = 1, scenario = "", epithets = epithets, racesByTurn = racesByTurn, raceHistorySnapshot = historyBefore, lockedDecisions = emptyMap())
        val stateAfter = newSolverState(currentTurn = 1, scenario = "", epithets = epithets, racesByTurn = racesByTurn, raceHistorySnapshot = historyAfter, lockedDecisions = emptyMap())
        val affected = candidates.filter { epi -> epithetFraction(epi, stateBefore) != epithetFraction(epi, stateAfter) }
        if (affected.isEmpty()) return
        val sb = StringBuilder()
        sb.append("Race \"$raceName\" updated ${affected.size} epithet(s):")
        for (epi in affected) {
            val status = EpithetTracker.classify(epi, stateAfter)
            val before = epithetFraction(epi, stateBefore)
            val after = epithetFraction(epi, stateAfter)
            val total = after?.second ?: before?.second ?: 0
            val beforeC = before?.first ?: 0
            val afterC = after?.first ?: 0
            sb.append("\n  - \"${epi.name}\" -> $status ($beforeC/$total) -> ($afterC/$total)")
            val seenLabels = mutableSetOf<String>()
            for (m in epi.matchers) {
                if (!matcherReferencesRace(m, raceName, race)) continue
                val mBefore = matcherFraction(m, stateBefore) ?: continue
                val mAfter = matcherFraction(m, stateAfter) ?: continue
                if (mBefore == mAfter) continue
                val label = if (race != null) matcherConditionLabel(m, race, epi.bullets) else null
                if (label != null && seenLabels.add(label)) sb.append("\n      * $label")
            }
            if (status != EpithetStatus.COMPLETED) {
                for (line in pendingPrerequisitesFor(epi, epithets, stateAfter)) {
                    sb.append("\n      * Still pending: $line")
                }
            }
        }
        MessageLog.i(TAG, sb.toString())
    }

    /**
     * Lists the unmet dependency-prerequisite phrases for [epi] under [state]. Each entry is the verbatim bullet from the epithet's
     * `bullet_points` whose text references the prerequisite name, falling back to `"Get the <name> epithet"` when no bullet matches.
     * Used by both the win log and the contribution JSON so the popover, tooltip, and Kotlin log show identical pending text.
     *
     * @param epi Epithet whose dependency matchers to inspect.
     * @param allEpithets All epithets, used for completion lookups.
     * @param state Solver state in which to evaluate dependency completion.
     * @return Pending-prerequisite phrases in matcher order, deduplicated by referenced epithet name. Empty when [epi] has no unmet prerequisites.
     */
    private fun pendingPrerequisitesFor(epi: Epithet, allEpithets: List<Epithet>, state: SolverState): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (m in epi.matchers) {
            val pending: List<String> =
                when (m) {
                    is EpithetMatcher.EpithetAnyOf -> if (m.names.any { isDepCompleted(it, allEpithets, state) }) emptyList() else m.names
                    is EpithetMatcher.EpithetAll -> m.names.filter { !isDepCompleted(it, allEpithets, state) }
                    else -> emptyList()
                }
            for (name in pending) {
                if (!seen.add(name)) continue
                val bullet = epi.bullets.firstOrNull { it.lowercase().contains(name.lowercase()) }
                out.add(bullet ?: "Get the $name epithet")
            }
        }
        return out
    }

    /**
     * Resolves a dependency epithet by name and reports whether it is currently complete in [state].
     *
     * @param depName Epithet name referenced by an [EpithetMatcher.EpithetAnyOf] / [EpithetMatcher.EpithetAll] matcher.
     * @param epithets All epithets, used for the name lookup.
     * @param state State in which to evaluate the dependency.
     * @return True when [depName] resolves to an epithet currently classified as [EpithetStatus.COMPLETED].
     */
    private fun isDepCompleted(depName: String, epithets: List<Epithet>, state: SolverState): Boolean {
        val depEpi = epithets.firstOrNull { it.name == depName } ?: return false
        return EpithetTracker.classify(depEpi, state) == EpithetStatus.COMPLETED
    }

    /**
     * True when the matcher progresses on this specific race. Direct-name matchers compare
     * by name. [EpithetMatcher.WinCount] matchers evaluate their filter against [race] when
     * available. Dependency matchers ([EpithetMatcher.EpithetAnyOf] / [EpithetMatcher.EpithetAll])
     * are skipped because they hinge on other epithets, not this race.
     *
     * @param matcher Matcher to inspect.
     * @param raceName Race name (matches [RaceCandidate.name]).
     * @param race Looked-up [RaceCandidate] for the win, or null when the lookup missed.
     * @return True when the matcher counts this race as progress.
     */
    private fun matcherReferencesRace(matcher: EpithetMatcher, raceName: String, race: RaceCandidate?): Boolean =
        when (matcher) {
            is EpithetMatcher.WinRace -> matcher.name == raceName
            is EpithetMatcher.WinRaceTimes -> matcher.name == raceName
            is EpithetMatcher.WinAnyOf -> raceName in matcher.names
            is EpithetMatcher.WinAtLeast -> raceName in matcher.names
            is EpithetMatcher.WinCount -> race != null && raceMatchesFilter(race, matcher.filter)
            is EpithetMatcher.EpithetAnyOf -> false
            is EpithetMatcher.EpithetAll -> false
        }

    /**
     * Mirrors `EpithetTracker.matchesFilter` and `MilpSolver.matchesFilter` for the win-progress
     * log. Keep the three copies in sync - visibility on the originals is `private` so they
     * cannot be called from here directly.
     *
     * @param race Race to test.
     * @param filter Filter predicate.
     * @return True when every non-null / non-empty field of [filter] accepts [race].
     */
    private fun raceMatchesFilter(race: RaceCandidate, filter: EpithetFilter): Boolean {
        if (filter.terrain != null && race.terrain != filter.terrain) return false
        if (filter.grade != null && race.grade != filter.grade) return false
        if (filter.gradeAtLeastOpen && race.grade.ordinal < RaceGrade.OP.ordinal) return false
        if (filter.gradedOnly && race.grade !in setOf(RaceGrade.G1, RaceGrade.G2, RaceGrade.G3)) return false
        if (filter.distanceTypes.isNotEmpty() && race.distanceType !in filter.distanceTypes) return false
        if (filter.raceTracks.isNotEmpty() && race.raceTrack !in filter.raceTracks) return false
        if (filter.nameContains != null && !race.name.contains(filter.nameContains, ignoreCase = true)) return false
        if (filter.nameContainsCountry && !EpithetFilters.nameContainsCountry(race.name)) return false
        return true
    }

    /**
     * Returns the (current, required) tally this matcher contributes toward its epithet's
     * aggregated progress, or null for dependency matchers ([EpithetMatcher.EpithetAnyOf] /
     * [EpithetMatcher.EpithetAll]) which gate on other epithets rather than on race wins.
     *
     * @param matcher Matcher to evaluate.
     * @param state Solver state whose [SolverState.raceHistory] supplies the win counts.
     * @return Pair of (current, required), capped so current never exceeds required.
     */
    private fun matcherFraction(matcher: EpithetMatcher, state: SolverState): Pair<Int, Int>? =
        when (matcher) {
            is EpithetMatcher.WinRace ->
                if (EpithetTracker.isMatcherSatisfied(matcher, state)) 1 to 1 else 0 to 1
            is EpithetMatcher.WinRaceTimes -> {
                val have = state.raceHistory.count { it.name == matcher.name }.coerceAtMost(matcher.times)
                have to matcher.times
            }
            is EpithetMatcher.WinAnyOf -> {
                val have =
                    state.raceHistory
                        .count { win ->
                            win.name in matcher.names &&
                                (matcher.atClass == null || win.classYear.equals(matcher.atClass, ignoreCase = true))
                        }.coerceAtMost(matcher.count)
                have to matcher.count
            }
            is EpithetMatcher.WinAtLeast -> {
                val have = state.raceHistory.map { it.name }.toSet().intersect(matcher.names.toSet()).size.coerceAtMost(matcher.count)
                have to matcher.count
            }
            is EpithetMatcher.WinCount -> {
                val have =
                    state.raceHistory
                        .count { win ->
                            val race = state.racesByTurn[win.turnNumber]?.firstOrNull { it.name == win.name }
                            race != null && raceMatchesFilter(race, matcher.filter)
                        }.coerceAtMost(matcher.count)
                have to matcher.count
            }
            is EpithetMatcher.EpithetAnyOf, is EpithetMatcher.EpithetAll -> null
        }

    /**
     * Sum of [matcherFraction] across this epithet's non-dependency matchers. Returns null when every matcher is a dependency (no race-win progress
     * to report). Mirrors the frontend's `epithetProgress` aggregation in `src/lib/solver/scoring.ts`.
     *
     * @param epi Epithet to aggregate.
     * @param state Solver state supplying win counts.
     * @return Pair of (sumCurrent, sumRequired), or null when no progress-trackable matchers exist.
     */
    private fun epithetFraction(epi: Epithet, state: SolverState): Pair<Int, Int>? {
        var sumCurrent = 0
        var sumTotal = 0
        for (m in epi.matchers) {
            val (c, t) = matcherFraction(m, state) ?: continue
            sumCurrent += c
            sumTotal += t
        }
        return if (sumTotal == 0) null else sumCurrent to sumTotal
    }

    /**
     * Picks the on-screen race the solver's schedule prefers for [currentTurn], or returns
     * null when the solver cannot or should not influence the decision (feature disabled, data missing, no schedule match).
     *
     * @param currentTurn The bot's current turn number.
     * @param scenario Active scenario name from `settings.general.scenario`.
     * @param candidates The on-screen [RaceData] list already matched by [Racing.lookupRaceInDatabase].
     * @return The chosen [RaceData] from [candidates], or null when the solver cannot or should
     *   not influence this turn (feature disabled, no on-screen candidates, missing data, the
     *   solver picked Train/Rest, or its chosen race is not on screen).
     */
    fun pickRace(currentTurn: TurnNumber, scenario: String, candidates: List<RaceData>): RaceData? {
        if (!SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver")) return null
        if (candidates.isEmpty()) return null

        val epithets =
            loadEpithets() ?: return null.also {
                MessageLog.w(TAG, "Solver enabled but epithets.json data is empty; skipping.")
            }
        val racesForTurn = candidates.map { it.toRaceCandidate(currentTurn) }
        val state = newSolverState(currentTurn, scenario, epithets, mapOf(currentTurn to racesForTurn))

        val schedule = SmartRaceSolver.solve(state)
        val decision = schedule.decisionAt(currentTurn)
        if (decision !is Decision.RaceDecision) {
            MessageLog.i(TAG, "Solver recommends a non-race decision for turn $currentTurn ($decision); skipping.")
            return null
        }
        // Map the solver's chosen race key back to one of the on-screen candidates.
        val pick = candidates.firstOrNull { rd -> rd.name == decision.raceKey || rd.name == raceNameFromKey(decision.raceKey) }
        if (pick == null) {
            MessageLog.i(TAG, "Solver chose ${decision.raceKey} but it is not on screen; falling through.")
        } else {
            MessageLog.i(TAG, "Solver picked ${pick.name} for turn $currentTurn (projected epithets: ${schedule.projectedEpithets}).")
        }
        return pick
    }

    /**
     * Convenience: returns the planned race key for [currentTurn], or null when the solver
     * picked Train/Rest or has no opinion.
     *
     * @param currentTurn The bot's current turn number.
     * @param scenario Active scenario name from `settings.general.scenario`.
     * @return The planned race key, or null.
     */
    fun peekRaceKeyForTurn(currentTurn: TurnNumber, scenario: String): String? =
        (peekDecisionForTurn(currentTurn, scenario) as? Decision.RaceDecision)?.raceKey

    /**
     * Peeks at the solver's planned [Decision] for [currentTurn] without requiring on-screen candidates. Returns the full decision shape
     * (`Train`, `Rest`, or `RaceDecision`) so the calendar snapshot can render Train/Rest cells. Reuses [cachedSchedule] populated by
     * the initial seeding broadcast (or refreshed on a loss); only re-solves defensively when no cache exists yet.
     *
     * @param currentTurn The bot's current turn number.
     * @param scenario Active scenario name from `settings.general.scenario`.
     * @return The planned [Decision], or null when the solver cannot influence this turn
     *   (feature disabled or required data missing).
     */
    fun peekDecisionForTurn(currentTurn: TurnNumber, scenario: String): Decision? {
        if (!SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver")) return null
        val epithets = loadEpithets() ?: return null
        val racesByTurn = loadAllRaces() ?: return null

        runStartupHooks(game = null, currentTurn = currentTurn, scenario = scenario)

        cachedSchedule?.let { return it.decisionAt(currentTurn) }

        val state = newSolverState(currentTurn, scenario, epithets, racesByTurn)
        return SmartRaceSolver.solve(state)
            .also {
                cachedSchedule = it
                cachedScheduleTurn = currentTurn
            }
            .decisionAt(currentTurn)
    }

    /**
     * True when the on-screen [raceData] matches the solver's [raceKey].
     *
     * @param raceData On-screen race resolved by [Racing.lookupRaceInDatabase].
     * @param raceKey Solver race key.
     * @return True when the on-screen race matches the solver's key.
     */
    fun isRaceKeyMatch(raceData: RaceData, raceKey: String): Boolean = raceData.name == raceKey || raceData.name == raceNameFromKey(raceKey)

    /**
     * Decides whether a scanned race row is the planned race when two races on a turn share the same on-screen track string.
     * Such siblings (e.g. Japanese Oaks and Tokyo Yushun are both "Tokyo Turf 2400m") differ in fan count, so the row's OCR'd fan count is the tie-breaker.
     * A failed OCR ([ocrFans] == -1) accepts the row so behavior never regresses below the prior tap-the-scanned-row logic.
     *
     * @param plannedFans Fan count of the solver-planned race from the database.
     * @param ocrFans Fan count OCR'd from the scanned row, or -1 if the OCR failed.
     * @return True when the scanned row is (or is assumed to be) the planned race.
     */
    fun isCorrectCollisionRow(plannedFans: Int, ocrFans: Int): Boolean = ocrFans == -1 || ocrFans == plannedFans

    /**
     * Computes a preview schedule from the user-supplied [configJson], without consulting any
     * runtime race history. Used by the settings UI to render a calendar preview of what the
     * solver would do if a fresh run started today with the current configuration.
     *
     * @param configJson Snapshot of the user's solver config: scenario, characterPreset, aptitudes, targetEpithets, forcedEpithets, manualLocks, weights.
     * @return JSON string of `{decisions, projectedEpithets, totalScore}`. Each decision entry is
     *   either `{type:"Train"}`, `{type:"Rest"}`, or `{type:"Race", raceKey, name, grade}`.
     */
    fun previewSchedule(configJson: String): String {
        val config = runCatching { JSONObject(configJson) }.getOrElse { JSONObject() }
        // Prefer the JS-provided races/epithets payload (always present from the bundled JSON
        // imports) and fall back to SettingsHelper. This avoids depending on persistence timing
        // for users whose profiles predate these settings.
        val racesByTurn =
            parseRacesJsonField(config.optStringOrNull("racesDataJson"))
                ?: loadAllRaces()
        if (racesByTurn == null) {
            return JSONObject()
                .put("decisions", JSONObject())
                .put("projectedEpithets", JSONArray())
                .put("totalScore", 0.0)
                .put("error", "races data unavailable")
                .toString()
        }
        val epithets =
            parseEpithetsJsonField(config.optStringOrNull("epithetsDataJson"))
                ?: loadEpithets()
                ?: emptyList()

        val scenario = config.optString("scenario", "Trackblazer")
        val characterPreset = config.optStringOrNull("characterPreset")
        val aptitudes = parseAptitudesObj(config.optJSONObject("aptitudes"))
        val manualLocks = parseManualLocks(config.optJSONObject("manualLocks"), racesByTurn)
        val objectives =
            parseObjectivesJsonField(config.optStringOrNull("objectivesDataJson"))
                ?: loadObjectives()
                ?: emptyMap()
        val applied = MandatoryRaces.apply(scenario, characterPreset, aptitudes, racesByTurn, manualLocks, objectives)

        val state =
            SolverState(
                currentTurn = 1,
                scenario = scenario,
                characterPreset = characterPreset,
                aptitudes = aptitudes,
                racesByTurn = applied.racesByTurn,
                epithets = epithetsForActiveContext(epithets, scenario, characterPreset ?: ""),
                forcedEpithets = jsonStringList(config.optJSONArray("forcedEpithets")).toSet(),
                targetEpithets = jsonStringList(config.optJSONArray("targetEpithets")).toSet(),
                lockedDecisions = applied.lockedDecisions,
                weights = parseWeightsObj(config.optJSONObject("weights")),
                maxRaces = config.optInt("maxRaces", -1).takeIf { it > 0 },
                maxConsecutiveRaces = config.optInt("maxConsecutiveRaces", 3).takeIf { it > 0 },
            )

        val schedule = SmartRaceSolver.solve(state)
        return serializeSchedule(schedule, applied.racesByTurn)
    }

    /**
     * Builds a [SolverState] for runtime calls. Settings-backed fields (character preset,
     * aptitudes, forced/target epithets, weights) are read here so callers only need to
     * supply the inputs that vary per call.
     *
     * @param currentTurn Turn the state targets.
     * @param scenario Active scenario name.
     * @param epithets Parsed epithet list.
     * @param racesByTurn Race calendar the solver may pick from.
     * @param raceHistorySnapshot History to feed the solver. Defaults to a snapshot of the current run's accumulated wins.
     * @param lockedDecisions Manual turn -> decision overrides. Null (default) loads locks from the
     *   `smartRaceSolverManualLocks` setting. Pass `emptyMap()` explicitly to skip lock loading.
     * @return Populated [SolverState].
     */
    private fun newSolverState(
        currentTurn: TurnNumber,
        scenario: String,
        epithets: List<Epithet>,
        racesByTurn: Map<TurnNumber, List<RaceCandidate>>,
        raceHistorySnapshot: List<RaceWin> = synchronized(raceHistory) { raceHistory.toList() },
        lockedDecisions: Map<TurnNumber, Decision>? = null,
    ): SolverState {
        val characterPreset = SettingsHelper.getStringSetting("racing", "smartRaceSolverCharacterPreset").ifEmpty { null }
        val aptitudes = readUserAptitudes()
        val baseLocks = lockedDecisions ?: loadManualLocksFromSettings(racesByTurn)
        val applied = MandatoryRaces.apply(scenario, characterPreset, aptitudes, racesByTurn, baseLocks, loadObjectives() ?: emptyMap())
        return SolverState(
            currentTurn = currentTurn,
            scenario = scenario,
            characterPreset = characterPreset,
            aptitudes = aptitudes,
            racesByTurn = applied.racesByTurn,
            epithets = epithetsForActiveContext(epithets, scenario, characterPreset ?: ""),
            raceHistory = raceHistorySnapshot,
            forcedEpithets = readStringSet("smartRaceSolverForcedEpithets"),
            targetEpithets = readStringSet("smartRaceSolverTargetEpithets"),
            lockedDecisions = applied.lockedDecisions,
            weights = readWeights(),
            maxRaces = readMaxRaces(),
            maxConsecutiveRaces = readMaxConsecutiveRaces(),
        )
    }

    /**
     * Filters [epithets] down to those obtainable in the active scenario AND for the active
     * character preset. Both gates are independent. An epithet must pass each one whose
     * restriction it carries. Restrictions are parsed from the bullet list - see [EpithetFilters.scenariosFromBullets]
     * and [EpithetFilters.charactersFromBullets].
     *
     * Defends against stale snapshots that still list a Trackblazer-only or character-only
     * target after the user switches scenario / preset.
     *
     * @param epithets Full epithet list parsed from epithets.json.
     * @param scenario Active scenario name (e.g. "Trackblazer", "URA Finale", "Unity Cup"). Blank
     *   disables the scenario gate so test contexts and the preview history seed keep working.
     * @param preset Active character preset. Blank disables the character gate.
     * @return Subset of [epithets] usable for the active scenario / preset.
     */
    internal fun epithetsForActiveContext(epithets: List<Epithet>, scenario: String, preset: String): List<Epithet> {
        return epithets.filter {
            val scenarioRestrictions = EpithetFilters.scenariosFor(it)
            val scenarioOk =
                scenarioRestrictions.isEmpty() ||
                    scenario.isBlank() ||
                    scenarioRestrictions.any { s -> s.equals(scenario, ignoreCase = true) }
            if (!scenarioOk) return@filter false

            val characterRestrictions = EpithetFilters.charactersFor(it)
            characterRestrictions.isEmpty() ||
                preset.isBlank() ||
                characterRestrictions.any { c -> c.equals(preset, ignoreCase = true) }
        }
    }

    /**
     * Computes the Preview-equivalent schedule for the current configuration, logs it, and
     * (when [currentTurn] > 1) replays its Race-decisions for turns before [currentTurn]
     * into [raceHistory] as assumed wins for mid-run restart recovery. The schedule is
     * computed with the same inputs [previewSchedule] uses (currentTurn=1, empty raceHistory,
     * `manualLocks` from settings) so what the runtime expects matches what the user has been watching in the UI.
     *
     * @param currentTurn The turn the bot is currently on. Only turns strictly before this are seeded into history.
     * @param scenario Active scenario name from `settings.general.scenario`.
     * @param epithets Full list of epithets parsed from settings.
     * @param racesByTurn Full race calendar from settings.
     */
    private fun seedHistoryFromPreview(currentTurn: TurnNumber, scenario: String, epithets: List<Epithet>, racesByTurn: Map<TurnNumber, List<RaceCandidate>>) {
        val state =
            newSolverState(
                currentTurn = 1,
                scenario = scenario,
                epithets = epithets,
                racesByTurn = racesByTurn,
                raceHistorySnapshot = emptyList(),
            )
        val schedule = SmartRaceSolver.solve(state)
        logPreviewSchedule(schedule, racesByTurn)
        if (currentTurn <= 1) return
        var seeded = 0
        for ((turn, decision) in schedule.decisions) {
            if (turn >= currentTurn) continue
            if (decision !is Decision.RaceDecision) continue
            val candidate = racesByTurn[turn]?.firstOrNull { it.key == decision.raceKey }
            val raceName = candidate?.name ?: raceNameFromKey(decision.raceKey)
            val classYear = candidate?.classYear ?: ""
            recordRaceWon(decision.raceKey, raceName, classYear, turn)
            seeded += 1
        }
        if (seeded > 0) {
            MessageLog.i(TAG, "Seeded raceHistory with $seeded assumed wins from Preview for turns 1..${currentTurn - 1} (mid-run restart recovery).")
        }
    }

    /**
     * Seeds [raceHistory] from the in-game Career -> Race History dialog. Reads each
     * row's race name, in-game date, and 1st-place icon presence. Only entries where
     * the trainee placed 1st become a [RaceWin]. A successful scrape - even one that
     * produces zero wins - is authoritative ("the trainee genuinely has no past wins")
     * and prevents the caller from falling back to the preview-as-wins seed.
     *
     * @param game Active Game instance for tap/screenshot/OCR access.
     * @param racesByTurn Race calendar from settings, used to resolve OCR'd race names
     *   into the canonical [RaceCandidate.key] that downstream consumers expect.
     * @return True if the scrape ran end-to-end. False if navigation or OCR failed and
     *   the caller should fall back to preview-based seeding.
     */
    private fun seedHistoryFromCareerScrape(game: Game, racesByTurn: Map<TurnNumber, List<RaceCandidate>>): Boolean {
        val entries = RaceHistory.scrape(game) ?: return false

        // Career -> Race History is sorted newest-first, so turns must strictly decrease as
        // we iterate. Any entry that breaks that invariant is OCR garbage from a
        // mid-scroll frame and is safely dropped.
        data class MatchedEntry(val candidate: RaceCandidate, val dateString: String, val won: Boolean, val strategy: String)
        val matched = mutableListOf<MatchedEntry>()
        var lastTurnSeen: TurnNumber = Int.MAX_VALUE
        for (entry in entries) {
            val gameDate =
                GameDate.fromDateString(s = entry.dateString, imageUtils = game.imageUtils, scenario = game.scenario)
                    ?: continue
            val turnNumber = gameDate.day
            if (turnNumber >= lastTurnSeen) continue
            lastTurnSeen = turnNumber

            // The Junior Make Debut row sits on Turn 12 and never matches a races.json candidate (no
            // real race on that turn). Capture its OCR-scraped formatted-name string so the Remote
            // Log Viewer tooltip can surface the in-game track details, then skip the rest of the
            // matching pipeline.
            if (turnNumber == MAKE_DEBUT_DISPLAY_TURN) {
                scrapedDebutEntry = entry
                continue
            }

            val candidates = racesByTurn[turnNumber] ?: continue
            val matchedFormatted =
                TextUtils.matchStringInList(entry.nameFormatted, candidates.map { it.nameFormatted }, threshold = 0.85)
                    ?: continue
            val candidate = candidates.firstOrNull { it.nameFormatted == matchedFormatted } ?: continue

            matched.add(MatchedEntry(candidate, entry.dateString, entry.won, entry.strategy))
            if (entry.won) {
                recordRaceWon(candidate.key, candidate.name, candidate.classYear, turnNumber, entry.strategy)
            } else {
                recordRaceLost(candidate.key, candidate.name, candidate.classYear, turnNumber, entry.strategy)
            }
        }

        val seeded = matched.count { it.won }
        val sb = StringBuilder()
        sb.append("Seeded raceHistory from Career → Race History scrape (${matched.size} races, $seeded wins):")
        if (matched.isEmpty()) {
            sb.append("\n  (no matched entries)")
        } else {
            for (m in matched) {
                val outcome = if (m.won) "Won" else "Lost"
                sb.append("\n  Turn ${m.candidate.turnNumber} (${m.dateString}): $outcome - ${m.candidate.name} [${m.strategy.ifBlank { "?" }}]")
            }
        }
        // Append the synthetic Junior Make Debut row to the scrape recap so the operator sees a
        // single, ordered list. Use the OCR-scraped date and outcome when [scrapedDebutEntry] was
        // captured above; the displayed race name stays as "Make Debut" rather than the OCR'd
        // track-formatted string for consistency with the calendar cell. Marking
        // [debutDisplayLogged] true here prevents [logSyntheticDebutOnce] from emitting a
        // duplicate trailing line later in [runStartupHooks].
        val debutDate = scrapedDebutEntry?.dateString ?: "Junior Year Late Jun"
        val debutOutcome = if (scrapedDebutEntry?.won == false) "Lost" else "Won"
        val debutStrategy = scrapedDebutEntry?.strategy?.ifBlank { "?" } ?: "?"
        sb.append("\n  Turn $MAKE_DEBUT_DISPLAY_TURN ($debutDate): $debutOutcome - Make Debut [$debutStrategy] (Does not affect epithets)")
        debutDisplayLogged.set(true)
        MessageLog.i(TAG, sb.toString())
        return true
    }

    /**
     * Logs a one-line-per-race summary of the Preview's schedule plus the projected epithets
     * the solver expects to complete. Intended to fire once per bot run so the operator can
     * compare actual play to the planned schedule.
     *
     * @param schedule The solved schedule to summarize.
     * @param racesByTurn Race calendar used to resolve race keys back to names.
     */
    private fun logPreviewSchedule(schedule: Schedule, racesByTurn: Map<TurnNumber, List<RaceCandidate>>) {
        val raceTurns = schedule.decisions.entries.filter { it.value is Decision.RaceDecision }.sortedBy { it.key }
        val sb = StringBuilder()
        sb.append("Smart Race Solver Preview Schedule (${raceTurns.size} races, score=${"%.2f".format(schedule.totalScore)}, projected epithets: ${schedule.projectedEpithets}):")
        if (raceTurns.isEmpty()) {
            sb.append("\n  (no races planned)")
        } else {
            for ((turn, decision) in raceTurns) {
                val raceKey = (decision as Decision.RaceDecision).raceKey
                val candidate = racesByTurn[turn]?.firstOrNull { it.key == raceKey }
                val name = candidate?.name ?: raceNameFromKey(raceKey)
                val grade = candidate?.grade?.name ?: "?"
                val date = candidate?.date?.takeIf { it.isNotBlank() }
                val datePrefix = if (date != null) "$date - " else ""
                sb.append("\n  Turn $turn (${datePrefix}$name, $grade)")
            }
        }
        MessageLog.i(TAG, sb.toString())
    }

    /**
     * Reads the user's saved aptitude configuration from settings.
     *
     * @return Parsed [Aptitudes]. Returns [Aptitudes.DEFAULT_A] when the setting is empty or invalid.
     */
    private fun readUserAptitudes(): Aptitudes {
        val json = SettingsHelper.getStringSetting("racing", "smartRaceSolverAptitudes")
        if (json.isEmpty()) return Aptitudes.DEFAULT_A
        return runCatching { parseAptitudesObj(JSONObject(json)) }.getOrElse { Aptitudes.DEFAULT_A }
    }

    /**
     * Parses an aptitudes JSON object with the keys `Sprint`, `Mile`, `Medium`, `Long`, `Turf`,
     * `Dirt`. Missing keys default to "A".
     *
     * @param obj JSON object to parse, or null.
     * @return Parsed [Aptitudes]. Returns [Aptitudes.DEFAULT_A] when [obj] is null.
     */
    private fun parseAptitudesObj(obj: JSONObject?): Aptitudes {
        if (obj == null) return Aptitudes.DEFAULT_A
        return Aptitudes(
            sprint = parseAptitude(obj.optString("Sprint", "A")),
            mile = parseAptitude(obj.optString("Mile", "A")),
            medium = parseAptitude(obj.optString("Medium", "A")),
            long = parseAptitude(obj.optString("Long", "A")),
            turf = parseAptitude(obj.optString("Turf", "A")),
            dirt = parseAptitude(obj.optString("Dirt", "A")),
        )
    }

    /**
     * Reads a JSON-array-of-strings setting and returns it as a [Set].
     *
     * @param key Settings key under the `racing` namespace.
     * @return Parsed string set, or an empty set when missing or unparseable.
     */
    private fun readStringSet(key: String): Set<String> {
        val json = SettingsHelper.getStringSetting("racing", key)
        if (json.isEmpty()) return emptySet()
        return runCatching { jsonStringList(JSONArray(json)).toSet() }.getOrElse { emptySet() }
    }

    /**
     * Reads the user's saved scoring weights from settings.
     *
     * @return Parsed [Weights], or default [Weights] when empty or unparseable.
     */
    private fun readWeights(): Weights {
        val json = SettingsHelper.getStringSetting("racing", "smartRaceSolverWeights")
        if (json.isEmpty()) return Weights()
        return runCatching { parseWeightsObj(JSONObject(json)) }.getOrElse { Weights() }
    }

    /**
     * Reads the user's maximum-optional-races cap. Returns null (no limit) when unset or non-positive.
     *
     * @return The cap as a positive Int, or null when the solver should plan races without an upper bound.
     */
    private fun readMaxRaces(): Int? = SettingsHelper.getIntSetting("racing", "smartRaceSolverMaxRaces", -1).takeIf { it > 0 }

    /**
     * Reads the user's maximum consecutive-races cap. Defaults to 3 when unset so the cap is active out of the box.
     * Returns null (no limit) only when the user explicitly sets 0 or a negative value.
     *
     * @return The cap as a positive Int, or null when consecutive races should be unbounded.
     */
    private fun readMaxConsecutiveRaces(): Int? = SettingsHelper.getIntSetting("racing", "smartRaceSolverMaxConsecutiveRaces", 3).takeIf { it > 0 }

    /**
     * Parses a weights JSON object. Each field falls back to the corresponding [Weights] default when missing.
     *
     * @param obj JSON object to parse, or null.
     * @return Parsed [Weights]. Returns default [Weights] when [obj] is null.
     */
    private fun parseWeightsObj(obj: JSONObject?): Weights {
        if (obj == null) return Weights()
        return Weights(
            raceValue = obj.optDouble("raceValue", 1.0),
            epithetValue = obj.optDouble("epithetValue", 1.0),
            statWeight = obj.optDouble("statWeight", 1.0),
            spWeight = obj.optDouble("spWeight", 1.0),
            hintWeight = obj.optDouble("hintWeight", 8.0),
            consecutiveRacePenalty = obj.optDouble("consecutiveRacePenalty", 3.0),
            summerPenalty = obj.optDouble("summerPenalty", 5.0),
            raceBonusPct = obj.optDouble("raceBonusPct", 50.0),
            raceCostPct = obj.optDouble("raceCostPct", 100.0),
            fanWeight = obj.optDouble("fanWeight", 0.0),
            aptitudeThreshold = parseAptitude(obj.optString("aptitudeThreshold", "C")),
            includeOpAndPreOp = obj.optBoolean("includeOpAndPreOp", false),
            allowSummerRacing = obj.optBoolean("allowSummerRacing", false),
        )
    }

    /**
     * Parses a single aptitude letter (e.g. "S", "A", "C") into an [Aptitude] enum.
     *
     * @param s Aptitude letter.
     * @return Parsed [Aptitude]. Returns [Aptitude.A] on unrecognised input.
     */
    private fun parseAptitude(s: String): Aptitude =
        Aptitude.fromName(s) ?: Aptitude.A

    /**
     * Lazy, cached parse of the `epithetsData` setting. Cached on first success so repeated
     * solver runs don't re-parse the same JSON.
     *
     * @return Parsed epithet list, or null when the setting is empty or unparseable.
     */
    private fun loadEpithets(): List<Epithet>? {
        cachedEpithets?.let { return it }
        val json = SettingsHelper.getStringSetting("racing", "epithetsData")
        if (json.isEmpty()) return null
        return runCatching { parseEpithets(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse epithetsData: ${it.message}") }
            .getOrNull()
            ?.also { cachedEpithets = it }
    }

    /**
     * Lazy, cached parse of the `racesData` setting into a turn-keyed candidate pool.
     *
     * @return Map of turn -> eligible races, or null when the setting is empty or unparseable.
     */
    private fun loadAllRaces(): Map<TurnNumber, List<RaceCandidate>>? {
        cachedRaces?.let { return it }
        val json = SettingsHelper.getStringSetting("racing", "racesData")
        if (json.isEmpty()) return null
        return runCatching { parseRacesData(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse racesData: ${it.message}") }
            .getOrNull()
            ?.also { cachedRaces = it }
    }

    /**
     * Lazy, cached parse of the `characterPresetsData` setting. Invoked from JS via the React
     * Native bridge, so static analysis cannot see the callers.
     *
     * @return Map of preset name -> aptitudes, or null when the setting is empty or unparseable.
     */
    fun loadCharacterPresets(): Map<String, Aptitudes>? {
        cachedPresets?.let { return it }
        val json = SettingsHelper.getStringSetting("racing", "characterPresetsData")
        if (json.isEmpty()) return null
        return runCatching { parsePresets(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse characterPresetsData: ${it.message}") }
            .getOrNull()
            ?.also { cachedPresets = it }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // JSON parsers

    /**
     * Parses an epithets JSON document into a list of [Epithet]. The JSON is expected to be a
     * top-level object whose values are per-epithet entries with the schema produced by the GameTora scraper.
     *
     * @param json Raw JSON string.
     * @return Parsed epithet list. Throws if the JSON is structurally invalid. Callers wrap
     *   in `runCatching` to convert errors into a null fallback.
     */
    internal fun parseEpithets(json: String): List<Epithet> {
        val obj = JSONObject(json)
        val out = ArrayList<Epithet>(obj.length())
        for (name in obj.keys()) {
            val e = obj.getJSONObject(name)
            out.add(
                Epithet(
                    name = e.optString("name", name),
                    bullets = jsonStringList(e.optJSONArray("bullet_points")),
                    matchers = parseMatchers(e.optJSONArray("matchers")),
                    scenarios = jsonStringList(e.optJSONArray("scenarios")),
                    characters = jsonStringList(e.optJSONArray("characters")),
                ),
            )
        }
        return out
    }

    /**
     * Parses an epithet's matcher array. Unrecognised matcher types are dropped silently.
     *
     * @param arr JSON array of matcher objects, or null.
     * @return Parsed list of matchers. Empty when [arr] is null.
     */
    private fun parseMatchers(arr: JSONArray?): List<EpithetMatcher> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { parseMatcher(arr.getJSONObject(it)) }
    }

    /**
     * Parses a single matcher JSON object into the matching [EpithetMatcher] subtype.
     *
     * @param m JSON object with at least a `type` field.
     * @return Parsed matcher, or null when the type is unrecognised.
     */
    private fun parseMatcher(m: JSONObject): EpithetMatcher? {
        val displayLabel = m.optStringOrNull("displayLabel")
        val displayLabelTemplate = m.optStringOrNull("displayLabelTemplate")
        return when (m.optString("type")) {
            "winRace" ->
                EpithetMatcher.WinRace(
                    name = m.getString("name"),
                    atClass = m.optStringOrNull("atClass"),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "winRaceTimes" ->
                EpithetMatcher.WinRaceTimes(
                    name = m.getString("name"),
                    times = m.getInt("times"),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "winAnyOf" ->
                EpithetMatcher.WinAnyOf(
                    names = jsonStringList(m.getJSONArray("names")),
                    count = m.optInt("count", 1),
                    atClass = m.optStringOrNull("atClass"),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "winAtLeast" ->
                EpithetMatcher.WinAtLeast(
                    names = jsonStringList(m.getJSONArray("names")),
                    count = m.getInt("count"),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "winCount" ->
                EpithetMatcher.WinCount(
                    count = m.getInt("count"),
                    filter = parseFilter(m.getJSONObject("filter")),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "epithetAnyOf" ->
                EpithetMatcher.EpithetAnyOf(
                    names = jsonStringList(m.getJSONArray("names")),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            "epithetAll" ->
                EpithetMatcher.EpithetAll(
                    names = jsonStringList(m.getJSONArray("names")),
                    displayLabel = displayLabel,
                    displayLabelTemplate = displayLabelTemplate,
                )
            else -> null
        }
    }

    /**
     * Parses a `winCount` matcher's filter object. Each field defaults to the matching [EpithetFilter] default when missing.
     *
     * @param o Filter JSON object.
     * @return Parsed [EpithetFilter].
     */
    private fun parseFilter(o: JSONObject): EpithetFilter =
        EpithetFilter(
            terrain = o.optStringOrNull("terrain")?.let { TrackSurface.fromName(it) },
            grade = o.optStringOrNull("grade")?.let { RaceGrade.fromName(it) },
            gradeAtLeastOpen = o.optBoolean("gradeAtLeastOpen", false),
            gradedOnly = o.optBoolean("gradedOnly", false),
            distanceTypes =
                jsonStringList(o.optJSONArray("distanceTypes"))
                    .mapNotNull { TrackDistance.fromName(it) }.toSet(),
            raceTracks = jsonStringList(o.optJSONArray("raceTracks")).toSet(),
            nameContains = o.optStringOrNull("nameContains"),
            nameContainsCountry = o.optBoolean("nameContainsCountry", false),
        )

    /**
     * Parses a character-presets JSON document into a name -> aptitudes map.
     *
     * @param json Raw JSON string.
     * @return Map of preset name to [Aptitudes]. Entries missing aptitude fields are skipped.
     */
    private fun parsePresets(json: String): Map<String, Aptitudes> {
        val obj = JSONObject(json)
        val out = HashMap<String, Aptitudes>(obj.length())
        for (name in obj.keys()) {
            val p = obj.getJSONObject(name)
            val dist = p.optJSONObject("distanceAptitudes")
            val surf = p.optJSONObject("surfaceAptitudes")
            if (dist == null || surf == null) continue
            out[name] =
                Aptitudes(
                    sprint = parseAptitude(dist.optString("Sprint", "A")),
                    mile = parseAptitude(dist.optString("Mile", "A")),
                    medium = parseAptitude(dist.optString("Medium", "A")),
                    long = parseAptitude(dist.optString("Long", "A")),
                    turf = parseAptitude(surf.optString("Turf", "A")),
                    dirt = parseAptitude(surf.optString("Dirt", "A")),
                )
        }
        return out
    }

    /**
     * Parses inline races JSON if shipped, otherwise returns the most recent cached inline result.
     * The JS layer omits `racesDataJson` after the first successful preview to save ~150KB of
     * marshalling per debounced re-solve, so once we've parsed any inline payload we keep using it
     * even when subsequent calls drop the field. Returns null only when nothing has ever been
     * shipped - callers fall back to [loadAllRaces] (SettingsHelper) in that case.
     *
     * @param json Inline races JSON, or null/empty to use the cached payload.
     * @return Parsed turn-keyed candidate pool. Null when no payload has ever been parsed.
     */
    private fun parseRacesJsonField(json: String?): Map<TurnNumber, List<RaceCandidate>>? {
        if (json.isNullOrEmpty()) return cachedInlineRaces?.second
        val hash = json.hashCode()
        cachedInlineRaces?.let { (cachedHash, value) -> if (cachedHash == hash) return value }
        return runCatching { parseRacesData(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse inline racesDataJson: ${it.message}") }
            .getOrNull()
            ?.also { cachedInlineRaces = hash to it }
    }

    /**
     * See [parseRacesJsonField] - same omit-after-prime contract for epithets data.
     *
     * @param json Inline epithets JSON, or null/empty to use the cached payload.
     * @return Parsed epithet list. Null when no payload has ever been parsed.
     */
    private fun parseEpithetsJsonField(json: String?): List<Epithet>? {
        if (json.isNullOrEmpty()) return cachedInlineEpithets?.second
        val hash = json.hashCode()
        cachedInlineEpithets?.let { (cachedHash, value) -> if (cachedHash == hash) return value }
        return runCatching { parseEpithets(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse inline epithetsDataJson: ${it.message}") }
            .getOrNull()
            ?.also { cachedInlineEpithets = hash to it }
    }

    /**
     * Lazily loads and caches the per-character mandatory races from the `characterObjectivesData` setting.
     *
     * @return Map of character name to its mandatory races, or null when the setting is empty or unparseable.
     */
    private fun loadObjectives(): Map<String, List<CharacterMandatoryRace>>? {
        cachedObjectives?.let { return it }
        val json = SettingsHelper.getStringSetting("racing", "characterObjectivesData")
        if (json.isEmpty()) return null
        return runCatching { MandatoryRaces.parse(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse characterObjectivesData: ${it.message}") }
            .getOrNull()
            ?.also { cachedObjectives = it }
    }

    /**
     * Parses the inline objectives payload from a preview config, hash-caching the result like the races and epithets fields.
     *
     * @param json The inline objectives JSON, or null/empty to reuse the last cached payload.
     * @return Parsed objectives map, or null when nothing has been parsed yet.
     */
    private fun parseObjectivesJsonField(json: String?): Map<String, List<CharacterMandatoryRace>>? {
        if (json.isNullOrEmpty()) return cachedInlineObjectives?.second
        val hash = json.hashCode()
        cachedInlineObjectives?.let { (cachedHash, value) -> if (cachedHash == hash) return value }
        return runCatching { MandatoryRaces.parse(json) }
            .onFailure { MessageLog.e(TAG, "Failed to parse inline objectivesDataJson: ${it.message}") }
            .getOrNull()
            ?.also { cachedInlineObjectives = hash to it }
    }

    /**
     * Parses a races JSON document into a turn-keyed candidate pool. Each entry must include
     * a `turnNumber` field - entries with `turnNumber <= 0` are silently dropped.
     *
     * @param json Raw races JSON string.
     * @return Map of turn -> list of [RaceCandidate] for that turn.
     */
    internal fun parseRacesData(json: String): Map<TurnNumber, List<RaceCandidate>> {
        val obj = JSONObject(json)
        val out = HashMap<TurnNumber, MutableList<RaceCandidate>>()
        for (key in obj.keys()) {
            val r = obj.getJSONObject(key)
            val turn = r.optInt("turnNumber", -1)
            if (turn <= 0) continue
            val date = r.optString("date", "")
            val classYear = if (date.contains(" Class")) date.substringBefore(" Class").trim() else ""
            // races.json uses "Pre-OP" but the RaceGrade enum is PRE_OP, so normalise dashes to underscores.
            val gradeStr = r.optString("grade", "OP").replace("-", "_")
            val candidate =
                RaceCandidate(
                    key = key,
                    name = r.optString("name", ""),
                    nameFormatted = r.optString("nameFormatted", ""),
                    date = date,
                    classYear = classYear,
                    raceTrack = r.optString("raceTrack", ""),
                    grade = RaceGrade.fromName(gradeStr) ?: RaceGrade.OP,
                    terrain = TrackSurface.fromName(r.optString("terrain", "TURF")) ?: TrackSurface.TURF,
                    distanceType = TrackDistance.fromName(r.optString("distanceType", "MEDIUM")) ?: TrackDistance.MEDIUM,
                    distanceMeters = r.optInt("distanceMeters", 0),
                    fans = r.optInt("fans", 0),
                    turnNumber = turn,
                )
            out.getOrPut(turn) { mutableListOf() }.add(candidate)
        }
        return out.mapValues { it.value.toList() }
    }

    /**
     * Parses the manual-lock map from the snapshot. Each entry maps a turn number to either a
     * race name (forces that race) or the sentinel `"__TRAIN__"` (forces a Train turn). The
     * latter is used by the inline calendar UI to "delete" a scheduled race or lock a turn that
     * had no race so the solver leaves it alone.
     *
     * @param obj JSON object of `{turnNumber: raceName | "__TRAIN__"}` pairs, or null.
     * @param racesByTurn Candidate pool used to resolve race-name locks back to keys.
     * @return Map of turn -> [Decision]. Entries referencing unknown race names are logged and dropped.
     */
    private fun parseManualLocks(
        obj: JSONObject?,
        racesByTurn: Map<TurnNumber, List<RaceCandidate>>,
    ): Map<TurnNumber, Decision> {
        if (obj == null) return emptyMap()
        val out = HashMap<TurnNumber, Decision>()
        for (turnStr in obj.keys()) {
            val turn = turnStr.toIntOrNull() ?: continue
            val value = obj.optString(turnStr, "")
            if (value.isEmpty()) continue
            if (value == TRAIN_LOCK_SENTINEL) {
                out[turn] = Decision.Train
                continue
            }
            val candidate = racesByTurn[turn]?.firstOrNull { it.name == value }
            if (candidate != null) {
                out[turn] = Decision.RaceDecision(candidate.key)
            } else {
                MessageLog.w(TAG, "Manual lock for turn $turn references unknown race \"$value\"; ignoring.")
            }
        }
        return out
    }

    /** Sentinel value the JS side writes to `manualLocks[turn]` when locking a turn to Train. */
    private const val TRAIN_LOCK_SENTINEL: String = "__TRAIN__"

    /**
     * Reads `smartRaceSolverManualLocks` from settings and parses it into a turn -> decision map.
     *
     * @param racesByTurn Candidate pool used by [parseManualLocks] to resolve race names to keys.
     * @return Parsed manual locks, or an empty map when the setting is absent or unparseable.
     */
    private fun loadManualLocksFromSettings(racesByTurn: Map<TurnNumber, List<RaceCandidate>>): Map<TurnNumber, Decision> {
        val json = SettingsHelper.getStringSetting("racing", "smartRaceSolverManualLocks")
        val obj = runCatching { if (json.isEmpty()) null else JSONObject(json) }.getOrNull()
        return parseManualLocks(obj, racesByTurn)
    }

    /**
     * Serialises a [Schedule] into the JSON shape the React Native preview UI expects.
     *
     * @param schedule Schedule to serialise.
     * @param racesByTurn Candidate pool used to enrich race decisions with name and grade fields.
     * @return JSON string of `{decisions, projectedEpithets, totalScore}`.
     */
    private fun serializeSchedule(schedule: Schedule, racesByTurn: Map<TurnNumber, List<RaceCandidate>>): String {
        val decisions = JSONObject()
        for ((turn, decision) in schedule.decisions) {
            val entry = JSONObject()
            when (decision) {
                is Decision.RaceDecision -> {
                    val race = racesByTurn[turn]?.firstOrNull { it.key == decision.raceKey }
                    entry.put("type", "Race")
                    entry.put("raceKey", decision.raceKey)
                    entry.put("name", race?.name ?: decision.raceKey)
                    entry.put("grade", race?.grade?.name ?: "")
                    if (race?.isMandatory == true) entry.put("mandatory", true)
                }
                Decision.Train -> entry.put("type", "Train")
                Decision.Rest -> entry.put("type", "Rest")
            }
            decisions.put(turn.toString(), entry)
        }
        return JSONObject()
            .put("decisions", decisions)
            .put("projectedEpithets", JSONArray(schedule.projectedEpithets.toList()))
            .put("totalScore", schedule.totalScore)
            .toString()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Remote Log Viewer calendar broadcasting

    /**
     * Builds a calendar snapshot from the current cached run state (turn, scenario, history)
     * and pushes it to [com.steve1316.uma_android_automation.utils.LogStreamServer] so connected
     * Remote Log Viewers paint the Race History panel. Runs the solver on a background thread
     * so the bot loop is never blocked; safe to call after every race result.
     *
     * No-op when the Smart Race Solver feature flag is off, when required parsed data is
     * missing, or when [LogStreamServer] is not running. Errors are logged at warn level
     * and swallowed so a viewer hiccup never crashes the bot.
     *
     * @param reuseSchedule When true and a [cachedSchedule] exists, skip the MILP solve and reuse the cached [Schedule]. Used by
     *   turn-change broadcasts and by the win path of [commitPendingRace] - both cases keep the originally locked-in plan.
     */
    private fun broadcastCalendarSnapshot(reuseSchedule: Boolean = false) {
        if (!SettingsHelper.getBooleanSetting("racing", "enableSmartRaceSolver")) return
        kotlin.concurrent.thread(name = "calendar-snapshot", isDaemon = true) {
            try {
                val json = buildCalendarSnapshotJson(reuseSchedule) ?: return@thread
                com.steve1316.uma_android_automation.utils.LogStreamServer.broadcastCalendarSnapshot(json)
            } catch (t: Throwable) {
                MessageLog.w(TAG, "Calendar snapshot broadcast failed: ${t.message}")
            }
        }
    }

    /**
     * Builds the calendar snapshot JSON consumed by `log_viewer.html`. Re-runs the solver with the cached run inputs by default,
     * or reuses [cachedSchedule] when [reuseSchedule] is true and the cache is populated.
     *
     * @param reuseSchedule When true and a [cachedSchedule] exists, skip the MILP solve and use the cached [Schedule]. Falls back to
     *   a fresh solve when no cache is available.
     * @return JSON `{ currentTurn, decisions{turn -> entry}, results[ {turn, raceKey, name, grade, outcome} ] }`
     *   or null when required data is unavailable.
     */
    private fun buildCalendarSnapshotJson(reuseSchedule: Boolean = false): String? {
        val racesByTurn = loadAllRaces() ?: return null
        val epithets = loadEpithets() ?: emptyList()
        val state = newSolverState(currentRunTurn, currentRunScenario, epithets, racesByTurn)

        val cached = cachedSchedule
        val schedule: Schedule =
            if (reuseSchedule && cached != null) {
                MessageLog.d(TAG, "Calendar snapshot reusing cached schedule from turn $cachedScheduleTurn.")
                cached
            } else {
                SmartRaceSolver.solve(state).also {
                    cachedSchedule = it
                    cachedScheduleTurn = currentRunTurn
                }
            }

        val winsSnapshot = synchronized(raceHistory) { raceHistory.toList() }
        val lossesSnapshot = synchronized(raceLosses) { raceLosses.toList() }
        val contributionsByTurn = computeEpithetContributionsByTurn(state.epithets, state.racesByTurn, schedule, winsSnapshot)

        val decisions = JSONObject()
        for ((turn, decision) in schedule.decisions) {
            val entry = JSONObject()
            when (decision) {
                is Decision.RaceDecision -> {
                    val race = findCandidate(turn, decision.raceKey, decision.raceKey, state.racesByTurn)
                    entry.put("type", "Race")
                    entry.put("raceKey", decision.raceKey)
                    entry.put("name", race?.name ?: decision.raceKey)
                    entry.put("grade", race?.grade?.name ?: "")
                    if (race?.isMandatory == true) entry.put("mandatory", true)
                    if (race != null) addRaceDetails(entry, race)
                    contributionsByTurn[turn]?.let { entry.put("contributions", it) }
                }
                Decision.Train -> entry.put("type", "Train")
                Decision.Rest -> entry.put("type", "Rest")
            }
            decisions.put(turn.toString(), entry)
        }

        val results = JSONArray()
        for (win in winsSnapshot) {
            results.put(buildResultEntry(win.turnNumber, win.raceKey, win.name, state.racesByTurn, RaceOutcome.WIN, contributionsByTurn[win.turnNumber], win.strategy))
        }
        for (loss in lossesSnapshot) {
            results.put(buildResultEntry(loss.turnNumber, loss.raceKey, loss.name, state.racesByTurn, RaceOutcome.LOSE, null, loss.strategy))
        }

        // Always append a synthetic entry for the in-game Junior Make Debut race. This row is purely a visual breadcrumb in the Remote Log
        // Viewer calendar. It is never part of raceHistory and therefore cannot influence epithet eligibility, the next-race decision, or
        // schedule preview. Before the bot exits the pre-debut window the row renders as "pending" (no scrape data yet, no win/lose styling);
        // once the OCR scrape captures the result the outcome flips to WIN/LOSE. The "synthetic" marker swaps the tooltip's epithet section
        // for a "Does not affect solver" notice in either case.
        val scrapedDebut = scrapedDebutEntry
        val hasRealDebut = currentRunTurn > PRE_DEBUT_TURN_THRESHOLD
        val debutOutcomeName =
            when {
                !hasRealDebut -> "PENDING"
                scrapedDebut?.won == false -> RaceOutcome.LOSE.name
                else -> RaceOutcome.WIN.name
            }
        val syntheticEntry =
            JSONObject()
                .put("turn", MAKE_DEBUT_DISPLAY_TURN)
                .put("raceKey", "synthetic-make-debut")
                .put("name", "Make Debut")
                .put("classYear", "Junior")
                .put("grade", "DEBUT")
                .put("outcome", debutOutcomeName)
                .put("fans", 500)
                .put("synthetic", true)
                .put("pending", !hasRealDebut)
        // The OCR-scraped formatted name (e.g. "Nakayama Turf 1800m (Mile) Right / Inner") is
        // surfaced via the raceTrack field so the viewer's existing tooltip renders it in the
        // parts row alongside the grade and fan count. Skipped when the scrape was bypassed or
        // the row was missing (pre-debut runs).
        scrapedDebut?.nameFormatted?.takeIf { it.isNotBlank() }?.let { syntheticEntry.put("raceTrack", it) }
        // Carry the OCR-scraped running style through so the Make Debut tooltip shows it after the fan count, matching every other race cell.
        scrapedDebut?.strategy?.takeIf { it.isNotBlank() }?.let { syntheticEntry.put("strategy", it) }
        results.put(syntheticEntry)

        return JSONObject()
            .put("currentTurn", currentRunTurn)
            .put("decisions", decisions)
            .put("results", results)
            .toString()
    }

    /**
     * Builds a single race-result entry for the calendar snapshot, looking up race details
     * from the candidate pool when possible. Wins also carry a `contributions` array if this
     * race advances any tracked epithet at this turn.
     *
     * @param turn Turn number the race ran on.
     * @param raceKey Race key.
     * @param raceName Display name (used as fallback when no candidate is found).
     * @param racesByTurn Candidate pool used to resolve race details.
     * @param outcome Win or loss marker.
     * @param contributions Pre-computed epithet contributions for this turn, or null.
     * @param strategy Running style OCR'd from the Race History scrape. Omitted from the JSON when blank.
     * @return JSON `{turn, raceKey, name, grade, outcome, raceTrack?, terrain?, distanceType?, distanceMeters?, fans?, strategy?, contributions?}`.
     */
    private fun buildResultEntry(
        turn: TurnNumber,
        raceKey: String,
        raceName: String,
        racesByTurn: Map<TurnNumber, List<RaceCandidate>>,
        outcome: RaceOutcome,
        contributions: JSONArray?,
        strategy: String = "",
    ): JSONObject {
        val race = findCandidate(turn, raceKey, raceName, racesByTurn)
        val obj =
            JSONObject()
                .put("turn", turn)
                .put("raceKey", raceKey)
                .put("name", race?.name ?: raceName)
                .put("grade", race?.grade?.name ?: "")
                .put("outcome", outcome.name)
        if (race?.isMandatory == true) obj.put("mandatory", true)
        if (race != null) addRaceDetails(obj, race)
        if (strategy.isNotBlank()) obj.put("strategy", strategy)
        if (contributions != null) obj.put("contributions", contributions)
        return obj
    }

    /**
     * Looks up a [RaceCandidate] for the given turn by key first, then by name. The key-first path matches OCR-seeded history (which already records
     * `candidate.key`). The name fallback recovers in-run commits that pass `match.name` / `solverPick.name` as the raceKey.
     *
     * @param turn Turn the race ran on.
     * @param raceKey raceKey recorded at write time. May be the canonical dated key or the bare race name.
     * @param raceName Race display name recorded alongside the key.
     * @param racesByTurn Candidate pool.
     * @return The matching candidate, or null if neither lookup found anything.
     */
    private fun findCandidate(turn: TurnNumber, raceKey: String, raceName: String, racesByTurn: Map<TurnNumber, List<RaceCandidate>>): RaceCandidate? {
        val pool = racesByTurn[turn] ?: return null
        return pool.firstOrNull { it.key == raceKey } ?: pool.firstOrNull { it.name == raceName }
    }

    /**
     * Stamps the race-detail fields used by the viewer's hover tooltip onto an existing JSON
     * object. Mirrors the popover meta line on the Smart Race Solver page.
     *
     * @param obj Target JSON object (mutated in place).
     * @param race Source race candidate.
     */
    private fun addRaceDetails(obj: JSONObject, race: RaceCandidate) {
        obj.put("raceTrack", race.raceTrack)
        obj.put("terrain", race.terrain.name)
        obj.put("distanceType", race.distanceType.name)
        obj.put("distanceMeters", race.distanceMeters)
        obj.put("fans", race.fans)
    }

    /**
     * Walks a unified wins-by-turn timeline (past wins from `raceHistory` plus the solver's
     * planned wins for the remaining turns) and computes, for each race-turn, the list of
     * tracked epithets whose `(current, required)` aggregate increased on that turn. The diff
     * mirrors the React Native Smart Race Solver popover's "Progresses these epithets" section.
     *
     * @param epithets Tracked epithets used for the diff.
     * @param racesByTurn Candidate pool used to resolve race keys to candidates.
     * @param schedule Solver schedule supplying the planned future wins.
     * @param winsSnapshot Authoritative past wins.
     * @return Map of turn -> JSON array of `{name, beforeCurrent, beforeRequired, afterCurrent, afterRequired, conditions, pending}` entries.
     */
    private fun computeEpithetContributionsByTurn(
        epithets: List<Epithet>,
        racesByTurn: Map<TurnNumber, List<RaceCandidate>>,
        schedule: Schedule,
        winsSnapshot: List<RaceWin>,
    ): Map<TurnNumber, JSONArray> {
        if (epithets.isEmpty()) return emptyMap()

        val winsByTurn = mutableMapOf<TurnNumber, RaceCandidate>()
        for (win in winsSnapshot) {
            val race = findCandidate(win.turnNumber, win.raceKey, win.name, racesByTurn) ?: continue
            winsByTurn[win.turnNumber] = race
        }
        for ((turn, decision) in schedule.decisions) {
            if (turn in winsByTurn || decision !is Decision.RaceDecision) continue
            if (turn < currentRunTurn) continue
            val race = findCandidate(turn, decision.raceKey, decision.raceKey, racesByTurn) ?: continue
            winsByTurn[turn] = race
        }

        val contributions = mutableMapOf<TurnNumber, JSONArray>()
        val cumulativeWins = mutableListOf<RaceWin>()
        var statePrev =
            newSolverState(
                currentTurn = 1,
                scenario = currentRunScenario,
                epithets = epithets,
                racesByTurn = racesByTurn,
                raceHistorySnapshot = emptyList(),
                lockedDecisions = emptyMap(),
            )
        for (turn in 1..72) {
            val race = winsByTurn[turn] ?: continue
            cumulativeWins.add(RaceWin(race.key, race.name, race.classYear, turn))
            val stateNow =
                newSolverState(
                    currentTurn = 1,
                    scenario = currentRunScenario,
                    epithets = epithets,
                    racesByTurn = racesByTurn,
                    raceHistorySnapshot = cumulativeWins.toList(),
                    lockedDecisions = emptyMap(),
                )

            val arr = JSONArray()
            for (epi in epithets) {
                val aggBefore = epithetFraction(epi, statePrev) ?: (0 to 0)
                val aggAfter = epithetFraction(epi, stateNow) ?: (0 to 0)
                if (aggBefore == aggAfter) continue

                // Identify which specific matchers this race advanced and surface their condition text.
                val conditions = JSONArray()
                val seen = mutableSetOf<String>()
                for (m in epi.matchers) {
                    val mBefore = matcherFraction(m, statePrev) ?: continue
                    val mAfter = matcherFraction(m, stateNow) ?: continue
                    if (mBefore == mAfter) continue
                    val label = matcherConditionLabel(m, race, epi.bullets) ?: continue
                    if (seen.add(label)) conditions.put(label)
                }

                val pending = JSONArray()
                if (EpithetTracker.classify(epi, stateNow) != EpithetStatus.COMPLETED) {
                    for (line in pendingPrerequisitesFor(epi, epithets, stateNow)) pending.put(line)
                }

                arr.put(
                    JSONObject()
                        .put("name", epi.name)
                        .put("beforeCurrent", aggBefore.first)
                        .put("beforeRequired", aggBefore.second)
                        .put("afterCurrent", aggAfter.first)
                        .put("afterRequired", aggAfter.second)
                        .put("conditions", conditions)
                        .put("pending", pending),
                )
            }
            if (arr.length() > 0) contributions[turn] = arr
            statePrev = stateNow
        }
        return contributions
    }

    /**
     * Builds a short, human-readable label describing which condition of an epithet a race advanced.
     * Prefers a verbatim bullet from [bullets] so the label matches gametora's authored phrasing in the rest of the UI.
     * Falls back to the pre-computed `displayLabel` / `displayLabelTemplate` carried on the matcher
     * (populated by `scripts/precompute-epithet-labels.ts`), so the React popover and Race History tooltip render identical text.
     *
     * @param matcher The matcher whose count just incremented.
     * @param race The contributing race.
     * @param bullets The same epithet's `bullet_points` list.
     * @return Display label, or null when [matcher] is a prerequisite type with no race-condition meaning.
     */
    private fun matcherConditionLabel(matcher: EpithetMatcher, race: RaceCandidate, bullets: List<String>): String? {
        fun findBulletContaining(needle: String): String? {
            if (needle.isEmpty()) return null
            val lower = needle.lowercase()
            return bullets.firstOrNull {
                val l = it.lowercase()
                // Inheritance-prereq bullets often contain the matcher's race name (e.g. "Inherit memories from a parent that won the Arima Kinen") but
                // describe an unverifiable parent condition, not the matcher's actual race. Skip them so the matcher's own displayLabel wins.
                if (l.startsWith("inherit memories") || l.startsWith("inherit the memories")) return@firstOrNull false
                l.contains(lower)
            }
        }
        val keywords: List<String> =
            when (matcher) {
                is EpithetMatcher.WinRace -> listOf(matcher.name)
                is EpithetMatcher.WinRaceTimes -> listOf(matcher.name)
                is EpithetMatcher.WinAnyOf, is EpithetMatcher.WinAtLeast -> listOf(race.name)
                is EpithetMatcher.WinCount ->
                    buildList {
                        matcher.filter.terrain?.let { add(it.name.lowercase()) }
                        matcher.filter.grade?.let { add(it.name) }
                        matcher.filter.distanceTypes.forEach { add(it.name.lowercase()) }
                    }
                is EpithetMatcher.EpithetAnyOf, is EpithetMatcher.EpithetAll -> return null
            }
        keywords.firstNotNullOfOrNull { findBulletContaining(it) }?.let { return it }
        matcher.displayLabelTemplate?.let { return it.replace("{race}", race.name) }
        return matcher.displayLabel
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /**
     * Converts a JSON array of strings into a Kotlin list.
     *
     * @param arr JSON array, or null.
     * @return List of strings. Empty when [arr] is null.
     */
    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Like [JSONObject.optString] but returns null instead of an empty string when the key is
     * missing, JSON null, or the empty string. Avoids the empty-string-as-sentinel ambiguity.
     *
     * @param key Field name to read.
     * @return Non-empty string value, or null.
     */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotEmpty() } else null

    /**
     * Best-effort recovery of the human-readable race name from a races.json key. Race keys
     * follow the pattern `"<name> (<date>)"`, so we trim everything starting at the first ` (`.
     *
     * @param key Race key.
     * @return Recovered race name.
     */
    private fun raceNameFromKey(key: String): String =
        key.substringBefore(" (").trim()

    /**
     * Adapts an in-game [RaceData] (sparse schema produced by Racing.kt's existing OCR + DB lookup)
     * into a [RaceCandidate] suitable for the solver. Several fields are best-effort: `key` falls
     * back to `name` when the bot lacks date context, and `classYear`/`raceTrack`/`distanceMeters`
     * are inferred or defaulted because the in-game [RaceData] does not carry them.
     *
     * @param turn Turn the on-screen race is being mapped to.
     * @return A [RaceCandidate] suitable for the solver.
     */
    private fun RaceData.toRaceCandidate(turn: TurnNumber): RaceCandidate =
        RaceCandidate(
            key = name,
            name = name,
            nameFormatted = "",
            date = "",
            classYear = "",
            raceTrack = "",
            grade = grade,
            terrain = trackSurface,
            distanceType = trackDistance,
            distanceMeters = 0,
            fans = fans,
            turnNumber = turn,
        )
}
