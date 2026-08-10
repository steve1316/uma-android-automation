package com.steve1316.uma_android_automation.bot

import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.Trainee
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RunAnalytics], the per-run aggregator that feeds the Remote Log Viewer's Analytics tab. The tests drive `recordTurn` / `recordRace`
 * and assert the serialized snapshot (via `buildSnapshotJson`) so the dedup guard, totals, per-year bucketing, and JSON shape stay correct.
 */
@DisplayName("RunAnalytics Tests")
class RunAnalyticsTest {
    @BeforeEach
    fun setUp() {
        RunAnalytics.reset()
        RunAnalytics.onRunStart("URA Finale", 1000L, null)
    }

    private fun gameDate(year: DateYear, month: DateMonth, phase: DatePhase, day: Int): GameDate =
        GameDate(year, month, phase).apply { this.day = day }

    private fun trainee(name: String, speed: Int, stamina: Int, power: Int, guts: Int, wit: Int, energy: Int, fans: Int): Trainee =
        Trainee().apply {
            this.name = name
            this.stats.speed = speed
            this.stats.stamina = stamina
            this.stats.power = power
            this.stats.guts = guts
            this.stats.wit = wit
            this.energy = energy
            this.fans = fans
        }

    private fun recordTraining(date: GameDate, t: Trainee, stat: StatName, gains: Map<StatName, Int>, fail: Int) {
        val tracer = DecisionTracer()
        tracer.startTurn(date, t)
        tracer.recordTrainingSelection(stat, null, "test", emptyList(), fail, gains)
        RunAnalytics.recordTurn(t, tracer, MainScreenAction.TRAIN)
    }

    private fun recordAction(date: GameDate, t: Trainee, action: MainScreenAction) {
        val tracer = DecisionTracer()
        tracer.startTurn(date, t)
        RunAnalytics.recordTurn(t, tracer, action)
    }

    private fun snapshot(ended: Boolean): JSONObject = JSONObject(RunAnalytics.buildSnapshotJson(ended))

    @Test
    @DisplayName("a repeated turn is recorded only once")
    fun `dedupes repeated turn`() {
        val date = gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.EARLY, 3)
        val t = trainee("Tester", 100, 100, 100, 100, 100, 80, 5)
        recordTraining(date, t, StatName.SPEED, mapOf(StatName.SPEED to 10, StatName.POWER to 4), 5)
        recordTraining(date, t, StatName.SPEED, mapOf(StatName.SPEED to 10, StatName.POWER to 4), 5)
        val json = snapshot(false)
        assertEquals(1, json.getJSONArray("perTurn").length())
        assertEquals(1, json.getJSONObject("totals").getJSONObject("trainingByStat").getInt("speed"))
    }

    @Test
    @DisplayName("training counts and stat gains sum across turns")
    fun `aggregates training totals`() {
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.EARLY, 1), trainee("A", 1, 1, 1, 1, 1, 80, 1), StatName.SPEED, mapOf(StatName.SPEED to 12, StatName.POWER to 4), 0)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 2), trainee("A", 1, 1, 1, 1, 1, 80, 1), StatName.SPEED, mapOf(StatName.SPEED to 10), 8)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.FEBRUARY, DatePhase.EARLY, 3), trainee("A", 1, 1, 1, 1, 1, 80, 1), StatName.WIT, mapOf(StatName.WIT to 9, StatName.SPEED to 2), 0)
        val totals = snapshot(false).getJSONObject("totals")
        assertEquals(2, totals.getJSONObject("trainingByStat").getInt("speed"))
        assertEquals(1, totals.getJSONObject("trainingByStat").getInt("wit"))
        assertEquals(3, totals.getInt("trainingCount"))
        assertEquals(24, totals.getJSONObject("statGainsByStat").getInt("speed"))
        assertEquals(9, totals.getJSONObject("statGainsByStat").getInt("wit"))
        assertEquals(4, totals.getJSONObject("statGainsByStat").getInt("power"))
    }

    @Test
    @DisplayName("a turn's race is recorded once even when a later record names it differently")
    fun `dedupes repeated race by turn`() {
        // OCR can read the same race under a different name, so the turn alone has to settle identity.
        RunAnalytics.recordRace(15, "Hopeful Stakes", "G3", "TURF", "MILE", 1200, true, false)
        RunAnalytics.recordRace(15, "hopeful stakes (OCR)", "", "", "", 0, true, false)

        val races = snapshot(false).getJSONArray("races")
        assertEquals(1, races.length())
        assertEquals("Hopeful Stakes", races.getJSONObject(0).getString("name"))
        assertEquals("G3", races.getJSONObject(0).getString("grade"))
    }

    @Test
    @DisplayName("races tally wins, grades, and bucket into the correct year")
    fun `aggregates races and per-year`() {
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.MARCH, DatePhase.EARLY, 5), trainee("A", 1, 1, 1, 1, 1, 80, 1), StatName.SPEED, mapOf(StatName.SPEED to 10), 0)
        recordTraining(gameDate(DateYear.CLASSIC, DateMonth.MARCH, DatePhase.EARLY, 30), trainee("A", 1, 1, 1, 1, 1, 80, 1), StatName.STAMINA, mapOf(StatName.STAMINA to 8), 0)
        RunAnalytics.recordRace(15, "Junior Race", "G3", "TURF", "MILE", 1200, true, false)
        RunAnalytics.recordRace(30, "Classic Race", "G1", "TURF", "MEDIUM", 9000, true, true)
        RunAnalytics.recordRace(44, "Classic Lost", "G2", "TURF", "LONG", 3000, false, false)

        val json = snapshot(true)
        assertTrue(json.getBoolean("ended"))
        val totals = json.getJSONObject("totals")
        assertEquals(3, totals.getInt("races"))
        assertEquals(2, totals.getInt("wins"))
        assertEquals(1, totals.getJSONObject("racesByGrade").getInt("G1"))
        assertEquals(1, totals.getJSONObject("racesByGrade").getInt("G3"))

        val perYear = json.getJSONArray("perYear")
        var juniorRaces = -1
        var classicRaces = -1
        for (i in 0 until perYear.length()) {
            val y = perYear.getJSONObject(i)
            if (y.getString("year") == "JUNIOR") juniorRaces = y.getInt("races")
            if (y.getString("year") == "CLASSIC") classicRaces = y.getInt("races")
        }
        assertEquals(1, juniorRaces)
        assertEquals(2, classicRaces)
    }

    @Test
    @DisplayName("snapshot carries the trainee block and recovery counts")
    fun `trainee block and recovery counts`() {
        val t = trainee("Oguri Cap", 1006, 671, 1048, 483, 813, 62, 18234)
        recordAction(gameDate(DateYear.SENIOR, DateMonth.JANUARY, DatePhase.EARLY, 50), t, MainScreenAction.REST)
        recordAction(gameDate(DateYear.SENIOR, DateMonth.JANUARY, DatePhase.LATE, 51), t, MainScreenAction.RECOVER_MOOD)

        val json = snapshot(false)
        val tr = json.getJSONObject("trainee")
        assertEquals("Oguri Cap", tr.getString("name"))
        assertEquals(1006, tr.getJSONObject("stats").getInt("speed"))
        assertEquals(813, tr.getJSONObject("stats").getInt("wit"))
        val totals = json.getJSONObject("totals")
        assertEquals(1, totals.getInt("energyRecoveries"))
        assertEquals(1, totals.getInt("moodRecoveries"))
        assertEquals(51, json.getInt("currentTurn"))
    }

    /** Build a saved-run snapshot by recording `turns` training turns, then serializing - used as a resume candidate. */
    private fun buildSavedSnapshot(scenario: String, name: String, turns: IntRange): String {
        RunAnalytics.onRunStart(scenario, 5000L, null)
        for (t in turns) {
            recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.EARLY, t), trainee(name, 100 + t, 100, 100, 100, 100, 80, 5), StatName.SPEED, mapOf(StatName.SPEED to 10), 5)
        }
        return RunAnalytics.buildSnapshotJson(false)
    }

    private fun perTurnCount(json: JSONObject): Int = json.getJSONArray("perTurn").length()

    @Test
    @DisplayName("resumes a saved run when scenario matches and the turn moved forward")
    fun `resumes matching run`() {
        val saved = buildSavedSnapshot("URA Finale", "Tester", 1..5)
        RunAnalytics.onRunStart("URA Finale", 9000L, null)
        RunAnalytics.loadCandidateForTest(saved)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 6), trainee("Tester", 200, 100, 100, 100, 100, 80, 5), StatName.WIT, mapOf(StatName.WIT to 8), 0)
        val json = snapshot(false)
        assertEquals(6, perTurnCount(json))
        assertEquals(6, json.getInt("currentTurn"))
    }

    @Test
    @DisplayName("starts fresh when the current turn is before the saved turn")
    fun `fresh on turn backward`() {
        val saved = buildSavedSnapshot("URA Finale", "Tester", 1..10)
        RunAnalytics.onRunStart("URA Finale", 9000L, null)
        RunAnalytics.loadCandidateForTest(saved)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 2), trainee("Tester", 50, 50, 50, 50, 50, 80, 1), StatName.SPEED, mapOf(StatName.SPEED to 10), 0)
        assertEquals(1, perTurnCount(snapshot(false)))
    }

    @Test
    @DisplayName("starts fresh when the scenario differs")
    fun `fresh on scenario mismatch`() {
        val saved = buildSavedSnapshot("URA Finale", "Tester", 1..5)
        RunAnalytics.onRunStart("Aoharu Cup", 9000L, null)
        RunAnalytics.loadCandidateForTest(saved)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 6), trainee("Tester", 200, 100, 100, 100, 100, 80, 5), StatName.SPEED, mapOf(StatName.SPEED to 10), 0)
        assertEquals(1, perTurnCount(snapshot(false)))
    }

    @Test
    @DisplayName("starts fresh on a definitive trainee-name conflict")
    fun `fresh on name conflict`() {
        val saved = buildSavedSnapshot("URA Finale", "Oguri Cap", 1..5)
        RunAnalytics.onRunStart("URA Finale", 9000L, null)
        RunAnalytics.loadCandidateForTest(saved)
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 6), trainee("Special Week", 200, 100, 100, 100, 100, 80, 5), StatName.SPEED, mapOf(StatName.SPEED to 10), 0)
        assertEquals(1, perTurnCount(snapshot(false)))
    }

    @Test
    @DisplayName("onTurnStart surfaces the resumed run before the turn completes")
    fun `onTurnStart resumes at turn start`() {
        val saved = buildSavedSnapshot("URA Finale", "Tester", 1..5)
        RunAnalytics.onRunStart("URA Finale", 9000L, null)
        RunAnalytics.loadCandidateForTest(saved)
        RunAnalytics.onTurnStart(trainee("Tester", 200, 100, 100, 100, 100, 80, 5), gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 6))
        val json = snapshot(false)
        assertEquals(5, perTurnCount(json))
        assertEquals(6, json.getInt("currentTurn"))
    }

    @Test
    @DisplayName("resumed runtime continues from the saved elapsed time, excluding the break")
    fun `resume rebases runtime to saved elapsed`() {
        // Saved run that had only accumulated 1 minute of bot time before it was paused.
        val savedJson = JSONObject(buildSavedSnapshot("URA Finale", "Tester", 1..5))
        savedJson.put("runtimeMs", 60_000L)
        RunAnalytics.onRunStart("URA Finale", 9000L, null)
        RunAnalytics.loadCandidateForTest(savedJson.toString())
        recordTraining(gameDate(DateYear.JUNIOR, DateMonth.JANUARY, DatePhase.LATE, 6), trainee("Tester", 200, 100, 100, 100, 100, 80, 5), StatName.WIT, mapOf(StatName.WIT to 8), 0)
        // Runtime resumes from the saved minute, not now - the original start (which would be epoch-scale).
        val runtimeMs = snapshot(false).getLong("runtimeMs")
        assertTrue(runtimeMs in 60_000L until 600_000L, "Expected resumed runtime to continue from ~60s, got $runtimeMs")
    }
}
