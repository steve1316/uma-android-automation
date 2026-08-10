package com.steve1316.uma_android_automation.bot

import android.content.Context
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.UserStorageManager
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.Trainee
import com.steve1316.uma_android_automation.utils.LogStreamServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Per-run analytics aggregator for the Remote Log Viewer's Analytics tab.
 *
 * Accumulates per-turn trainee/training records and per-race outcomes as a career runs, then serializes the whole run into a single JSON snapshot and
 * broadcasts it over the WebSocket with an `ANALYTICS:` prefix. Mirrors the Race History calendar snapshot mechanism: the snapshot is a full replace
 * (not a delta) so a newly-connected viewer paints immediately from the cached copy on connect.
 *
 * The snapshot is also persisted to disk each turn so a pause/restart can resume the same in-progress run instead of starting over. On restart the saved
 * snapshot is loaded as a candidate and, on the first recorded turn (once the trainee/turn/scenario are known), adopted when it plausibly matches the
 * current run or discarded when it looks like a new career. The viewer's "start fresh" control discards it manually via [discardHistory].
 */
object RunAnalytics {
    private const val TAG: String = "[ANALYTICS]"

    /** Schema version stamped on every snapshot so the viewer can guard against shape drift. */
    private const val SCHEMA_VERSION: Int = 1

    /** Default trainable career length in turns, used as the viewer's progress denominator. */
    private const val DEFAULT_TOTAL_TURNS: Int = 72

    /** Subdirectory and filename of the persisted snapshot that survives a pause/restart. */
    private const val PERSIST_SUBDIR: String = "analytics"
    private const val PERSIST_FILE: String = "current_run.json"

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // State

    /** Campaign scenario name (e.g. "URA Finale"). */
    private var scenarioName: String = ""

    /** Wall-clock start of the run for elapsed-time reporting. 0 until `onRunStart`. */
    private var startTimeMs: Long = 0L

    /** Turn number of the most recent record, used to drop the duplicate `emit()` some campaigns issue per turn. */
    private var lastRecordedTurn: Int = -1

    /** Latest trainee block (stats, aptitudes, mood, etc.) captured at the last recorded turn. Null until the first turn. */
    private var traineeJson: JSONObject? = null

    /** Turn number of the most recent record, surfaced as the viewer's "current turn". */
    private var currentTurn: Int = 0

    /** Human-readable date label of the most recent record. */
    private var currentDate: String = ""

    /** Application context for reading/writing the persisted snapshot. Null in unit tests, where file I/O is skipped. */
    private var appContext: Context? = null

    /** Snapshot loaded from disk at run start, awaiting the resume-vs-fresh decision on the first recorded turn. */
    private var pendingCandidate: Candidate? = null

    /** True once the resume-vs-fresh decision has been made for this run. */
    private var resumeEvaluated: Boolean = false

    /** Last non-empty trainee name seen, used as a fallback when the live OCR name is briefly empty after a restart. */
    private var rememberedName: String = ""

    /** Guards the off-thread snapshot file write so concurrent persists never interleave. */
    private val persistLock = Any()

    /** True once the first successful persist of a run has been logged, to confirm writes are happening without spamming every turn. */
    @Volatile private var persistAnnounced: Boolean = false

    /** One entry per completed turn, in turn order. */
    private val perTurn = mutableListOf<TurnRecord>()

    /** One entry per committed race outcome, in turn order. */
    private val races = mutableListOf<RaceRecord>()

    /** A single completed turn's trainee snapshot plus the training decision made that turn. */
    private data class TurnRecord(
        /** Career turn number (1-based). */
        val turn: Int,
        /** Human-readable date label (e.g. "Classic Early February"). */
        val date: String,
        /** Year bucket enum name (JUNIOR / CLASSIC / SENIOR). */
        val year: String,
        /** Speed stat at turn end. */
        val speed: Int,
        /** Stamina stat at turn end. */
        val stamina: Int,
        /** Power stat at turn end. */
        val power: Int,
        /** Guts stat at turn end. */
        val guts: Int,
        /** Wit stat at turn end. */
        val wit: Int,
        /** Energy percentage at turn end. */
        val energy: Int,
        /** Mood enum name at turn end. */
        val mood: String,
        /** Cumulative fan total at turn end. */
        val fans: Int,
        /** Skill point pool at turn end. */
        val skillPoints: Int,
        /** Main-screen action enum name executed this turn. */
        val action: String,
        /** Lowercase stat name trained this turn, or null when the turn was not a training turn. */
        val trainingStat: String?,
        /** Stat-gain map (lowercase stat -> gain) for the training, or null when unavailable. */
        val trainingGains: Map<String, Int>?,
        /** Failure chance percentage for the training, or null when unavailable. */
        val trainingFailureChance: Int?,
    )

    /** A single committed race outcome. */
    private data class RaceRecord(
        /** Turn the race ran on. */
        val turn: Int,
        /** Race name. */
        val name: String,
        /** Race grade enum name (e.g. "G1", "PRE_OP"). */
        val grade: String,
        /** Track surface label. */
        val surface: String,
        /** Track distance label. */
        val distance: String,
        /** Fans awarded by the race. */
        val fans: Int,
        /** True when the trainee finished 1st. */
        val won: Boolean,
        /** True when the race was a locked mandatory race. */
        val mandatory: Boolean,
    )

    /** A snapshot loaded from disk, used to decide whether to resume the same run after a restart. */
    private data class Candidate(
        /** Scenario the saved run was in. */
        val scenario: String,
        /** Trainee name from the saved run (may be empty). */
        val name: String,
        /** Last turn the saved run reached. */
        val currentTurn: Int,
        /** Accumulated active runtime (ms) of the saved run, used to rebase the start time so runtime stays continuous across a break. */
        val runtimeMs: Long,
        /** Saved per-turn records. */
        val perTurn: List<TurnRecord>,
        /** Saved race records. */
        val races: List<RaceRecord>,
    )

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle

    /** Reset all accumulated state so a new career does not inherit the previous run's data. */
    fun reset() {
        scenarioName = ""
        startTimeMs = 0L
        lastRecordedTurn = -1
        traineeJson = null
        currentTurn = 0
        currentDate = ""
        perTurn.clear()
        races.clear()
        pendingCandidate = null
        resumeEvaluated = false
        rememberedName = ""
        persistAnnounced = false
    }

    /**
     * Begin a new run. Clears prior state, records run metadata, and loads any persisted snapshot as a resume candidate. Does not broadcast - the first
     * snapshot goes out at the first `recordTurn`, which also decides whether to adopt the candidate (resume) or discard it (fresh run).
     *
     * @param scenario Campaign scenario name shown in the viewer header.
     * @param startTimeMs Wall-clock run start in millis, for elapsed-time reporting.
     * @param context Application context for persistence, or null to disable file I/O (unit tests).
     */
    fun onRunStart(scenario: String, startTimeMs: Long, context: Context?) {
        reset()
        this.scenarioName = scenario
        this.startTimeMs = startTimeMs
        this.appContext = context?.applicationContext
        loadCandidate()
        val candidate = pendingCandidate
        MessageLog.i(
            TAG,
            "[ANALYTICS] Run start: persistence ${if (appContext == null) "disabled (no context)" else "enabled"}; " +
                if (candidate == null) {
                    "no saved run to resume."
                } else {
                    "saved run found (scenario=${candidate.scenario}, lastTurn=${candidate.currentTurn}, name=${candidate.name}, ${candidate.perTurn.size} turns)."
                },
        )
    }

    /**
     * Apply the shared turn-start state from the live trainee and date: run the one-time resume-vs-fresh decision, remember a non-empty name, and refresh
     * the current turn / date / trainee block. Called by both `onTurnStart` and `recordTurn`.
     *
     * @param trainee Live trainee whose stats / mood / aptitudes are snapshotted.
     * @param date Current turn date.
     */
    private fun updateTurnState(trainee: Trainee, date: GameDate) {
        if (!resumeEvaluated) {
            resumeEvaluated = true
            maybeAdoptCandidate(scenarioName, date.day, trainee.name)
        }
        if (trainee.name.isNotEmpty()) rememberedName = trainee.name
        currentTurn = date.day
        currentDate = formatDate(date)
        traineeJson = buildTraineeJson(trainee)
    }

    /**
     * Surface the run in the viewer at turn start (after the trainee's stats and date are read) so a restart immediately shows the resumed run instead of
     * an empty dashboard while the turn is worked out. Runs the resume-vs-fresh decision on the first turn; later turns just refresh the trainee block.
     * Broadcasts only - persistence happens at turn end via `recordTurn`.
     *
     * @param trainee Live trainee whose stats / mood / aptitudes are snapshotted.
     * @param date Current turn date, read straight from the campaign so this can fire before the decision tracer opens the turn.
     */
    fun onTurnStart(trainee: Trainee, date: GameDate) {
        updateTurnState(trainee, date)
        broadcastSnapshot(ended = false)
    }

    /**
     * Record one completed turn and broadcast + persist a fresh snapshot. On the first turn after a (re)start, decides whether this is the same run
     * resuming (adopt the saved candidate) or a fresh one. Appends only a genuinely new turn - campaigns can emit twice per turn - but always refreshes
     * the snapshot. No-op until the tracer has captured a date for the turn.
     *
     * @param trainee Live trainee whose stats / mood / aptitudes are snapshotted.
     * @param tracer Decision tracer for this turn, source of the turn date and the training selection.
     * @param action The main-screen action the bot executed this turn.
     */
    fun recordTurn(trainee: Trainee, tracer: DecisionTracer, action: MainScreenAction) {
        val date = tracer.currentTurnDate() ?: return
        val turn = date.day
        updateTurnState(trainee, date)

        if (turn != lastRecordedTurn) {
            lastRecordedTurn = turn
            val selection = tracer.lastTrainingSelection()
            val trainingStat = selection?.selected?.name?.lowercase()
            val trainingGains = selection?.pickedStatGains?.entries?.associate { it.key.name.lowercase() to it.value }
            perTurn.add(
                TurnRecord(
                    turn = turn,
                    date = formatDate(date),
                    year = date.year.name,
                    speed = trainee.stats.speed,
                    stamina = trainee.stats.stamina,
                    power = trainee.stats.power,
                    guts = trainee.stats.guts,
                    wit = trainee.stats.wit,
                    energy = trainee.energy,
                    mood = trainee.mood.name,
                    fans = trainee.fans,
                    skillPoints = trainee.skillPoints,
                    action = action.name,
                    trainingStat = trainingStat,
                    trainingGains = trainingGains,
                    trainingFailureChance = selection?.pickedFailureChance,
                ),
            )
        }

        broadcastSnapshot(ended = false, persist = true)
    }

    /**
     * Record one committed race outcome. Deduplicated by turn. Does not broadcast on its own - the turn-end `recordTurn` that follows picks it up.
     *
     * @param turn Turn the race ran on.
     * @param name Race name. Empty when the racing flow never matched the race against the database.
     * @param grade Race grade enum name (e.g. "G1", "PRE_OP"). Empty when unresolved, which keeps it out of the grade charts.
     * @param surface Track surface enum name (e.g. "TURF"). Empty when unresolved.
     * @param distance Track distance enum name (e.g. "MEDIUM"). Empty when unresolved.
     * @param fans Fans awarded by the race.
     * @param won True when the trainee finished 1st.
     * @param mandatory True when the race was a locked mandatory race.
     */
    fun recordRace(turn: Int, name: String, grade: String, surface: String, distance: String, fans: Int, won: Boolean, mandatory: Boolean) {
        // Only one career race can run per turn, so the turn alone identifies it. Guards against a second record for the same race arriving
        // under a different name, which OCR can easily produce.
        if (races.any { it.turn == turn }) return
        races.add(RaceRecord(turn, name, grade, surface, distance, fans, won, mandatory))
    }

    /** End the run. Broadcasts and persists a final snapshot flagged `ended` so the viewer stops its refresh loop. */
    fun onRunEnd() {
        broadcastSnapshot(ended = true, persist = true)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Persistence and resume

    /**
     * Viewer hook for the "start fresh" control: drop the saved run's history but keep the current run going so it re-accumulates from the current turn.
     *
     * @param context Application context used to delete the persisted snapshot.
     */
    fun discardHistory(context: Context) {
        perTurn.clear()
        races.clear()
        lastRecordedTurn = -1
        traineeJson = null
        currentTurn = 0
        currentDate = ""
        pendingCandidate = null
        resumeEvaluated = true
        rememberedName = ""
        try {
            UserStorageManager.getInstance(context).deleteFile(PERSIST_SUBDIR, PERSIST_FILE)
        } catch (t: Throwable) {
            MessageLog.w(TAG, "[ANALYTICS] discardHistory:: Failed to delete persisted analytics: ${t.message}")
        }
        try {
            LogStreamServer.broadcastAnalyticsSnapshot(buildSnapshotJson(ended = false))
        } catch (t: Throwable) {
            MessageLog.w(TAG, "[ANALYTICS] discardHistory:: Failed to broadcast cleared analytics: ${t.message}")
        }
        MessageLog.i(TAG, "[ANALYTICS] Analytics history discarded by the viewer; re-accumulating from the current turn.")
    }

    /** Load the persisted snapshot (if any) into [pendingCandidate] for the resume decision. No-op without a context. */
    private fun loadCandidate() {
        val ctx = appContext ?: return
        val json =
            try {
                UserStorageManager.getInstance(ctx).openInputStream(PERSIST_SUBDIR, PERSIST_FILE)?.use { it.bufferedReader().readText() }
            } catch (t: Throwable) {
                MessageLog.w(TAG, "[ANALYTICS] loadCandidate:: Failed to read persisted analytics: ${t.message}")
                null
            } ?: return
        pendingCandidate = parsePersistedSnapshot(json)
    }

    /**
     * Decide whether the loaded candidate is the same run resuming and, if so, adopt its history. Eager: a scenario match plus a non-backward turn is
     * enough; the trainee name only rejects a definitive mismatch (it is often empty on a mid-run restart).
     *
     * @param scenario The current run's scenario.
     * @param currentTurn The current turn number.
     * @param currentName The current trainee name (may be empty).
     * @return True when the candidate was adopted (resumed).
     */
    private fun maybeAdoptCandidate(scenario: String, currentTurn: Int, currentName: String): Boolean {
        val candidate = pendingCandidate ?: return false
        pendingCandidate = null
        val namesConflict = candidate.name.isNotEmpty() && currentName.isNotEmpty() && candidate.name != currentName
        if (candidate.scenario != scenario || currentTurn < candidate.currentTurn || namesConflict) {
            MessageLog.i(
                TAG,
                "[ANALYTICS] Saved analytics did not match the current run; starting fresh. saved(scenario=${candidate.scenario}, turn=${candidate.currentTurn}, name=${candidate.name}) vs current(scenario=$scenario, turn=$currentTurn, name=$currentName).",
            )
            return false
        }
        perTurn.clear()
        perTurn.addAll(candidate.perTurn)
        races.clear()
        races.addAll(candidate.races)
        lastRecordedTurn = candidate.perTurn.lastOrNull()?.turn ?: -1
        // Rebase the start so runtime resumes from the saved elapsed time, excluding the break while the bot was stopped.
        if (candidate.runtimeMs > 0L) startTimeMs = System.currentTimeMillis() - candidate.runtimeMs
        if (candidate.name.isNotEmpty()) rememberedName = candidate.name
        MessageLog.i(TAG, "[ANALYTICS] Resumed analytics from a saved run (${candidate.perTurn.size} turns recovered).")
        return true
    }

    /**
     * Parse a persisted snapshot JSON back into a [Candidate]. Returns null when the JSON is empty or unparseable.
     *
     * @param json The persisted snapshot JSON.
     * @return The parsed candidate, or null.
     */
    private fun parsePersistedSnapshot(json: String): Candidate? =
        try {
            val root = JSONObject(json)
            val perTurnArr = root.optJSONArray("perTurn") ?: JSONArray()
            val turns = (0 until perTurnArr.length()).mapNotNull { parseTurnRecord(perTurnArr.optJSONObject(it)) }
            val racesArr = root.optJSONArray("races") ?: JSONArray()
            val raceRecords = (0 until racesArr.length()).mapNotNull { parseRaceRecord(racesArr.optJSONObject(it)) }
            if (turns.isEmpty() && raceRecords.isEmpty()) {
                null
            } else {
                Candidate(
                    scenario = root.optString("scenario", ""),
                    name = root.optJSONObject("trainee")?.optString("name", "").orEmpty(),
                    currentTurn = root.optInt("currentTurn", 0),
                    runtimeMs = root.optLong("runtimeMs", 0L),
                    perTurn = turns,
                    races = raceRecords,
                )
            }
        } catch (t: Throwable) {
            MessageLog.w(TAG, "[ANALYTICS] parsePersistedSnapshot:: Failed to parse persisted analytics: ${t.message}")
            null
        }

    /**
     * Parse one per-turn JSON object into a [TurnRecord].
     *
     * @param obj The per-turn JSON object, or null.
     * @return The parsed record, or null when the object is null.
     */
    private fun parseTurnRecord(obj: JSONObject?): TurnRecord? {
        obj ?: return null
        val stats = obj.optJSONObject("stats") ?: JSONObject()
        val training = obj.optJSONObject("training")
        val gainsObj = training?.optJSONObject("gains")
        val gains = gainsObj?.let { g -> g.keys().asSequence().associateWith { g.optInt(it, 0) } }
        return TurnRecord(
            turn = obj.optInt("turn"),
            date = obj.optString("date"),
            year = obj.optString("year"),
            speed = stats.optInt("speed"),
            stamina = stats.optInt("stamina"),
            power = stats.optInt("power"),
            guts = stats.optInt("guts"),
            wit = stats.optInt("wit"),
            energy = obj.optInt("energy"),
            mood = obj.optString("mood"),
            fans = obj.optInt("fans"),
            skillPoints = obj.optInt("skillPoints"),
            action = obj.optString("action"),
            trainingStat = training?.optString("stat")?.takeIf { it.isNotEmpty() },
            trainingGains = gains,
            trainingFailureChance = if (training != null && training.has("failureChance")) training.optInt("failureChance") else null,
        )
    }

    /**
     * Parse one race JSON object into a [RaceRecord].
     *
     * @param obj The race JSON object, or null.
     * @return The parsed record, or null when the object is null.
     */
    private fun parseRaceRecord(obj: JSONObject?): RaceRecord? {
        obj ?: return null
        return RaceRecord(
            turn = obj.optInt("turn"),
            name = obj.optString("name"),
            grade = obj.optString("grade"),
            surface = obj.optString("surface"),
            distance = obj.optString("distance"),
            fans = obj.optInt("fans"),
            won = obj.optBoolean("won"),
            mandatory = obj.optBoolean("mandatory"),
        )
    }

    /**
     * Test seam: inject a persisted snapshot as the resume candidate without touching disk.
     *
     * @param json A persisted snapshot JSON to load as the candidate.
     */
    internal fun loadCandidateForTest(json: String) {
        pendingCandidate = parsePersistedSnapshot(json)
        resumeEvaluated = false
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Serialization

    /**
     * Build the current snapshot, broadcast it to [LogStreamServer], and optionally persist it to disk. Failures are swallowed with a warning so analytics
     * never break the bot loop. Persistence is on for completed-turn and run-end frames, and skipped for the turn-start broadcast that only surfaces the run.
     *
     * @param ended True on the final run-end frame.
     * @param persist True to also write the snapshot to disk so a pause/restart can resume the run.
     */
    private fun broadcastSnapshot(ended: Boolean, persist: Boolean = false) {
        val json =
            try {
                buildSnapshotJson(ended)
            } catch (t: Throwable) {
                MessageLog.w(TAG, "[ANALYTICS] broadcastSnapshot:: Failed to build analytics snapshot: ${t.message}")
                return
            }
        try {
            LogStreamServer.broadcastAnalyticsSnapshot(json)
        } catch (t: Throwable) {
            MessageLog.w(TAG, "[ANALYTICS] broadcastSnapshot:: Failed to broadcast analytics snapshot: ${t.message}")
        }
        if (persist) persistJson(json)
    }

    /**
     * Write a snapshot JSON to disk off the bot thread so a pause/restart can resume the run. No-op without a context (unit tests).
     *
     * @param json The snapshot JSON to persist.
     */
    private fun persistJson(json: String) {
        val ctx = appContext ?: return
        thread(isDaemon = true, name = "analytics-persist") {
            synchronized(persistLock) {
                try {
                    val out = UserStorageManager.getInstance(ctx).openOutputStream(PERSIST_SUBDIR, PERSIST_FILE, "application/json")
                    if (out == null) {
                        if (!persistAnnounced) {
                            persistAnnounced = true
                            MessageLog.w(TAG, "[ANALYTICS] persistJson:: Could not open $PERSIST_SUBDIR/$PERSIST_FILE for writing; analytics will not survive a restart.")
                        }
                    } else {
                        out.use { stream -> stream.bufferedWriter().use { it.write(json) } }
                        if (!persistAnnounced) {
                            persistAnnounced = true
                            MessageLog.i(TAG, "[ANALYTICS] Persisting analytics to $PERSIST_SUBDIR/$PERSIST_FILE so the run can resume after a restart.")
                        }
                    }
                } catch (t: Throwable) {
                    MessageLog.w(TAG, "[ANALYTICS] persistJson:: Failed to persist analytics snapshot: ${t.message}")
                }
            }
        }
    }

    /**
     * Serialize the full run into the `ANALYTICS:` payload. `runtimeMs` is the accumulated active time and `startTimeMs` is the (possibly rebased) virtual
     * start the viewer's live ticker counts from. The viewer ignores unknown fields.
     *
     * @param ended True on the final run-end frame.
     * @return The snapshot JSON as a string.
     */
    internal fun buildSnapshotJson(ended: Boolean): String {
        val runtimeMs = if (startTimeMs > 0L) System.currentTimeMillis() - startTimeMs else 0L
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("ended", ended)
            .put("runtimeMs", runtimeMs)
            .put("startTimeMs", startTimeMs)
            .put("scenario", scenarioName)
            .put("currentTurn", currentTurn)
            .put("currentDate", currentDate)
            .put("totalTurns", DEFAULT_TOTAL_TURNS)
            .put("trainee", traineeJson ?: JSONObject())
            .put("perTurn", buildPerTurnArray())
            .put("races", buildRacesArray())
            .put("totals", buildTotalsJson())
            .put("perYear", buildPerYearArray())
            .toString()
    }

    /**
     * Build the trainee block (current stats, aptitudes, mood, energy, fans, skill points, statuses). Falls back to the remembered name when the live OCR
     * name is briefly empty after a restart.
     *
     * @param trainee The live trainee to snapshot.
     * @return The trainee JSON object.
     */
    private fun buildTraineeJson(trainee: Trainee): JSONObject {
        val surface = JSONObject()
        trainee.trackSurfaceAptitudes.forEach { (k, v) -> surface.put(pretty(k.name), v.name) }
        val distance = JSONObject()
        trainee.trackDistanceAptitudes.forEach { (k, v) -> distance.put(pretty(k.name), v.name) }
        val style = JSONObject()
        trainee.runningStyleAptitudes.forEach { (k, v) -> style.put(pretty(k.name), v.name) }

        val negativeStatuses = JSONArray()
        trainee.currentNegativeStatuses.forEach { negativeStatuses.put(it) }

        val ownedSkills = JSONArray()
        trainee.ownedSkillNames.forEach { ownedSkills.put(it) }

        return JSONObject()
            .put("name", trainee.name.ifEmpty { rememberedName })
            .put(
                "stats",
                JSONObject()
                    .put("speed", trainee.stats.speed)
                    .put("stamina", trainee.stats.stamina)
                    .put("power", trainee.stats.power)
                    .put("guts", trainee.stats.guts)
                    .put("wit", trainee.stats.wit),
            )
            .put("aptitudes", JSONObject().put("surface", surface).put("distance", distance).put("style", style))
            .put("energy", trainee.energy)
            .put("mood", trainee.mood.name)
            .put("fans", trainee.fans)
            .put("skillPoints", trainee.skillPoints)
            .put("negativeStatuses", negativeStatuses)
            .put("ownedSkills", ownedSkills)
            .put("uniqueSkillLevel", trainee.uniqueSkillLevel)
    }

    /**
     * Build the per-turn history array.
     *
     * @return A JSON array with one object per recorded turn.
     */
    private fun buildPerTurnArray(): JSONArray {
        val arr = JSONArray()
        for (r in perTurn) {
            val obj =
                JSONObject()
                    .put("turn", r.turn)
                    .put("date", r.date)
                    .put("year", r.year)
                    .put("stats", JSONObject().put("speed", r.speed).put("stamina", r.stamina).put("power", r.power).put("guts", r.guts).put("wit", r.wit))
                    .put("energy", r.energy)
                    .put("mood", r.mood)
                    .put("fans", r.fans)
                    .put("skillPoints", r.skillPoints)
                    .put("action", r.action)
            if (r.trainingStat != null) {
                val training = JSONObject().put("stat", r.trainingStat)
                r.trainingGains?.let { training.put("gains", statMapToJson(it)) }
                r.trainingFailureChance?.let { training.put("failureChance", it) }
                obj.put("training", training)
            }
            arr.put(obj)
        }
        return arr
    }

    /**
     * Build the race-outcomes array.
     *
     * @return A JSON array with one object per committed race.
     */
    private fun buildRacesArray(): JSONArray {
        val arr = JSONArray()
        for (r in races) {
            arr.put(
                JSONObject()
                    .put("turn", r.turn)
                    .put("name", r.name)
                    .put("grade", r.grade)
                    .put("surface", pretty(r.surface))
                    .put("distance", pretty(r.distance))
                    .put("fans", r.fans)
                    .put("won", r.won)
                    .put("mandatory", r.mandatory),
            )
        }
        return arr
    }

    /**
     * Build the whole-run totals (training mix, stat gains, race tallies, recovery counts).
     *
     * @return The totals JSON object.
     */
    private fun buildTotalsJson(): JSONObject {
        val (trainingByStat, statGainsByStat) = aggregateStats(perTurn)
        val energyRecoveries = perTurn.count { it.action == MainScreenAction.REST.name }
        val moodRecoveries = perTurn.count { it.action == MainScreenAction.RECOVER_MOOD.name }

        val racesByGrade = JSONObject()
        var wins = 0
        for (r in races) {
            racesByGrade.put(r.grade, racesByGrade.optInt(r.grade, 0) + 1)
            if (r.won) wins++
        }

        return JSONObject()
            .put("trainingByStat", statMapToJson(trainingByStat))
            .put("statGainsByStat", statMapToJson(statGainsByStat))
            .put("racesByGrade", racesByGrade)
            .put("wins", wins)
            .put("races", races.size)
            .put("fans", perTurn.lastOrNull()?.fans ?: 0)
            .put("energyRecoveries", energyRecoveries)
            .put("moodRecoveries", moodRecoveries)
            .put("trainingCount", trainingByStat.values.sum())
    }

    /**
     * Build the per-year summary array (one object per year that has any recorded turns).
     *
     * @return The per-year JSON array.
     */
    private fun buildPerYearArray(): JSONArray {
        val arr = JSONArray()
        for (year in listOf("JUNIOR", "CLASSIC", "SENIOR")) {
            val turnsInYear = perTurn.filter { it.year == year }
            if (turnsInYear.isEmpty()) continue
            val (trainingByStat, statGains) = aggregateStats(turnsInYear)
            val yearRaces = races.filter { yearForTurn(it.turn) == year }
            arr.put(
                JSONObject()
                    .put("year", year)
                    .put("turns", turnsInYear.size)
                    .put("statGains", statMapToJson(statGains))
                    .put("trainingByStat", statMapToJson(trainingByStat))
                    .put("races", yearRaces.size)
                    .put("wins", yearRaces.count { it.won }),
            )
        }
        return arr
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /** Returns a fresh ordered stat counter seeded to zero for all five stats. */
    private fun newStatCounter(): LinkedHashMap<String, Int> = linkedMapOf("speed" to 0, "stamina" to 0, "power" to 0, "guts" to 0, "wit" to 0)

    /**
     * Tally training picks and stat gains across the given turns. Unknown stat keys are ignored so the counters stay limited to the five canonical stats.
     *
     * @param turns The turns to aggregate.
     * @return A pair of (training-count-by-stat, stat-gains-by-stat), each an ordered five-stat counter.
     */
    private fun aggregateStats(turns: List<TurnRecord>): Pair<LinkedHashMap<String, Int>, LinkedHashMap<String, Int>> {
        val training = newStatCounter()
        val gains = newStatCounter()
        for (r in turns) {
            r.trainingStat?.let { if (training.containsKey(it)) training[it] = training.getValue(it) + 1 }
            r.trainingGains?.forEach { (k, v) -> if (gains.containsKey(k)) gains[k] = gains.getValue(k) + v }
        }
        return training to gains
    }

    /**
     * Serialize a stat-keyed counter to JSON.
     *
     * @param map The counter to serialize.
     * @return A JSON object mirroring the map.
     */
    private fun statMapToJson(map: Map<String, Int>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj
    }

    /**
     * Title-case an enum name for display (e.g. "FRONT_RUNNER" -> "Front Runner").
     *
     * @param enumName The raw enum name.
     * @return The display string.
     */
    private fun pretty(enumName: String): String = enumName.split('_').joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

    /**
     * Build a readable date label from a game date.
     *
     * @param date The game date.
     * @return A label such as "Classic Early February".
     */
    private fun formatDate(date: GameDate): String = "${pretty(date.year.name)} ${pretty(date.phase.name)} ${pretty(date.month.name)}"

    /**
     * Map a turn number to its year bucket. Finale turns (73-75) fold into SENIOR.
     *
     * @param turn The career turn number.
     * @return The year bucket enum name.
     */
    private fun yearForTurn(turn: Int): String =
        when {
            turn <= 24 -> "JUNIOR"
            turn <= 48 -> "CLASSIC"
            else -> "SENIOR"
        }
}
