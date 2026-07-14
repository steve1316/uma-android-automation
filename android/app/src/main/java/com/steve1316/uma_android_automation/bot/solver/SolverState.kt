package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface

/**
 * Six aptitude rankings (S..G) covering the four distance categories and two surfaces.
 *
 * Higher [Aptitude] ordinal = better in-game grade. The existing enum is ordered G,F,E,D,C,B,A,S
 * so `aptitude.ordinal >= other.ordinal` reads as "at least as good as [other]".
 *
 * @property sprint Aptitude for Sprint-distance races.
 * @property mile Aptitude for Mile-distance races.
 * @property medium Aptitude for Medium-distance races.
 * @property long Aptitude for Long-distance races.
 * @property turf Aptitude on Turf surface.
 * @property dirt Aptitude on Dirt surface.
 */
data class Aptitudes(
    val sprint: Aptitude,
    val mile: Aptitude,
    val medium: Aptitude,
    val long: Aptitude,
    val turf: Aptitude,
    val dirt: Aptitude,
) {
    /**
     * Returns the aptitude that applies to [d].
     *
     * @param d Distance category.
     * @return The matching distance aptitude.
     */
    fun forDistance(d: TrackDistance): Aptitude =
        when (d) {
            TrackDistance.SPRINT -> sprint
            TrackDistance.MILE -> mile
            TrackDistance.MEDIUM -> medium
            TrackDistance.LONG -> long
        }

    /**
     * Returns the aptitude that applies to [s].
     *
     * @param s Track surface.
     * @return The matching surface aptitude.
     */
    fun forSurface(s: TrackSurface): Aptitude =
        when (s) {
            TrackSurface.TURF -> turf
            TrackSurface.DIRT -> dirt
        }

    companion object {
        /** All-A baseline used by tests and as a safe default when no preset is selected. */
        val DEFAULT_A: Aptitudes =
            Aptitudes(
                Aptitude.A,
                Aptitude.A,
                Aptitude.A,
                Aptitude.A,
                Aptitude.A,
                Aptitude.A,
            )
    }
}

/**
 * Tunable scoring weights consumed by the heuristic. Defaults mirror the reference Trackblazer
 * solver: a 50% race-bonus uplift on top of the BASE_REWARD table and a per-race cost equal to
 * the weighted G2 baseline. With these defaults G2/G3 races score zero - they tie with Train and
 * only get picked when an epithet, fans tiebreaker, or Late-Dec window pushes them positive.
 *
 * @property raceValue Multiplier applied to the per-race net (gross - cost) value.
 * @property epithetValue Multiplier applied to every epithet completion's reward.
 * @property statWeight Coefficient on the stat component of gross race reward and cost baseline.
 * @property spWeight Coefficient on the skill-point component of gross race reward and cost baseline.
 * @property hintWeight Score awarded per completed hint-reward epithet.
 * @property targetEpithetBonus Score added to any epithet named in [SolverState.targetEpithets], on top of whatever its reward is worth. Most epithets grant no
 *   listed reward at all, so without this a target is worth exactly 0 and the solver has no reason to pursue it. 0.0 makes target selection purely informational.
 * @property consecutiveRacePenalty Penalty per third+ consecutive race outside Late-Dec windows.
 * @property summerPenalty Penalty for racing on a turn in [SolverState.summerBlockTurns].
 * @property raceBonusPct Percentage uplift applied to base stat/sp rewards before weighting.
 * @property raceCostPct Per-race cost expressed as a percentage of the weighted G2 baseline.
 * @property fanWeight Per-fan score contribution applied to a race's reward fans. 0.0 means fans are ignored entirely (Stat Epitaphs preset default).
 *   1e-3 (Fans + Epitaphs preset) makes a 25k-fan G1 contribute ~25 score points - meaningful but not dominant. Above 5e-3 the solver will
 *   race almost every eligible turn.
 * @property aptitudeThreshold Minimum aptitude grade required for both distance and surface
 *   for a race to be eligible.
 * @property includeOpAndPreOp When true, OP/Pre-OP races are eligible (subject to the threshold).
 * @property allowSummerRacing When true, Classic/Senior summer turns are not hard-blocked.
 */
data class Weights(
    val raceValue: Double = 1.0,
    val epithetValue: Double = 1.0,
    val statWeight: Double = 1.0,
    val spWeight: Double = 1.0,
    val hintWeight: Double = 8.0,
    val targetEpithetBonus: Double = 25.0,
    val consecutiveRacePenalty: Double = 3.0,
    val summerPenalty: Double = 5.0,
    val raceBonusPct: Double = 50.0,
    val raceCostPct: Double = 100.0,
    val fanWeight: Double = 0.0,
    val aptitudeThreshold: Aptitude = Aptitude.C,
    val includeOpAndPreOp: Boolean = false,
    val allowSummerRacing: Boolean = false,
)

/**
 * A race in the candidate pool the solver chooses among. Sourced from races.json.
 *
 * @property key Unique key matching the top-level key in races.json (`"<name> (<date>)"`).
 * @property name Human-readable race name (without the date suffix).
 * @property date Free-text date label from races.json.
 * @property classYear Class-year prefix extracted from [date]: "Junior", "Classic", or "Senior".
 *   Used by matchers that gate on class (e.g. "Japan Cup (Classic)").
 * @property raceTrack In-game track name (e.g. "Tokyo", "Nakayama").
 * @property grade Race grade (G1, G2, G3, OP, Pre-OP, Maiden, Debut, Finale, EX).
 * @property terrain Track surface: Turf or Dirt.
 * @property distanceType Distance category: Sprint, Mile, Medium, or Long.
 * @property distanceMeters Race distance in meters.
 * @property fans Reward fans count. Scaled by [Weights.fanWeight] in [ScoringFunctions.raceValue].
 * @property turnNumber Turn the race takes place on (1..72).
 * @property isMandatory True when this candidate is a forced career-objective race the solver
 *   locked onto its turn (not an optional race from the pool). Drives the calendar's mandatory styling.
 */
data class RaceCandidate(
    val key: String,
    val name: String,
    val nameFormatted: String,
    val date: String,
    val classYear: String,
    val raceTrack: String,
    val grade: RaceGrade,
    val terrain: TrackSurface,
    val distanceType: TrackDistance,
    val distanceMeters: Int,
    val fans: Int,
    val turnNumber: TurnNumber,
    val isMandatory: Boolean = false,
)

/**
 * A historical race win used by [EpithetTracker] to evaluate matcher progress.
 *
 * @property raceKey Race key the win refers to (matches [RaceCandidate.key]).
 * @property name Race name (matches [RaceCandidate.name]).
 * @property classYear Class-year prefix at the time of the win.
 * @property turnNumber Turn the win occurred on.
 * @property strategy Running style OCR'd from the Race History scrape (e.g. "Pace"). Empty for live commits and the preview seed, which have no scrape data.
 */
data class RaceWin(
    val raceKey: String,
    val name: String,
    val classYear: String,
    val turnNumber: TurnNumber,
    val strategy: String = "",
)

/** Win/lose marker for a finished race surfaced to the Remote Log Viewer calendar. */
enum class RaceOutcome { WIN, LOSE }

/**
 * Record of a race the trainee entered but did not win. Lives outside [SolverState] (the solver
 * itself only consumes wins for epithet eligibility) and is tracked at the integration layer so the
 * viewer can paint LOSE pills on completed-but-not-won turns.
 *
 * @property raceKey Race key (matches [RaceCandidate.key]).
 * @property name Display name of the race (matches [RaceCandidate.name]).
 * @property classYear Class-year prefix at the time of the race.
 * @property turnNumber Turn the loss occurred on.
 * @property strategy Running style OCR'd from the Race History scrape (e.g. "Pace"). Empty for live commits, which have no scrape data.
 */
data class RaceLossRecord(
    val raceKey: String,
    val name: String,
    val classYear: String,
    val turnNumber: TurnNumber,
    val strategy: String = "",
)

/**
 * Immutable snapshot of everything the solver needs to compute a [Schedule].
 *
 * Re-solves are triggered by constructing a new [SolverState] (e.g. with a freshly-dead
 * epithet added to [deadEpithets] after a race loss). The solver itself is pure - given the
 * same state, it produces the same schedule.
 *
 * @property currentTurn Turn the bot is currently on. The solver plans from this turn forward.
 * @property scenario "URA Finale", "Unity Cup", or "Trackblazer". Read from settings.general.scenario.
 * @property characterPreset Selected character preset name, or null for none.
 * @property aptitudes User aptitudes used by [ScoringFunctions.isEligible].
 * @property racesByTurn Pre-grouped candidate pool: turn -> races available on that turn.
 * @property epithets Full list of epithets the player can pursue.
 * @property raceHistory Wins accumulated so far this run.
 * @property completedEpithets Epithet names already marked completed by the runtime tracker.
 * @property deadEpithets Epithet names known unreachable (e.g. a required race has been lost).
 * @property forcedEpithets Epithet names the solver MUST complete. Infeasibility flags the model.
 * @property targetEpithets Epithet names the solver should pursue when score-positive.
 * @property lockedDecisions User-locked turn -> decision overrides.
 * @property summerBlockTurns No-race turns. Defaults to [DEFAULT_SUMMER_BLOCKS].
 * @property weights Active scoring weights.
 * @property maxRaces Maximum number of optional races the solver may schedule, or null for no limit. Locked/mandatory races always run and do not count toward this cap.
 * @property maxConsecutiveRaces Maximum races allowed in back-to-back turns, or null for no limit. Late-December turns (24, 48, 72) are exempt so a chain may run into year-end.
 */
data class SolverState(
    val currentTurn: TurnNumber,
    val scenario: String,
    val characterPreset: String?,
    val aptitudes: Aptitudes,
    val racesByTurn: Map<TurnNumber, List<RaceCandidate>>,
    val epithets: List<Epithet>,
    val raceHistory: List<RaceWin> = emptyList(),
    val completedEpithets: Set<String> = emptySet(),
    val deadEpithets: Set<String> = emptySet(),
    val forcedEpithets: Set<String> = emptySet(),
    val targetEpithets: Set<String> = emptySet(),
    val lockedDecisions: Map<TurnNumber, Decision> = emptyMap(),
    val summerBlockTurns: Set<TurnNumber> = DEFAULT_SUMMER_BLOCKS,
    val weights: Weights = Weights(),
    val maxRaces: Int? = null,
    val maxConsecutiveRaces: Int? = null,
) {
    val epithetsByName: Map<String, Epithet> by lazy { epithets.associateBy { it.name } }

    companion object {
        /**
         * Default summer training blocks (no-race turns), as 1-based turn numbers. Summer camp runs Early Jul -> Late Aug
         * in Classic (37-40) and Senior (61-64) only. Junior year has no summer camp.
         */
        val DEFAULT_SUMMER_BLOCKS: Set<TurnNumber> =
            setOf(
                37,
                38,
                39,
                40,
                61,
                62,
                63,
                64,
            )
    }
}
