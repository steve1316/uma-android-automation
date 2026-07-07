package com.steve1316.uma_android_automation.bot.solver

import com.steve1316.uma_android_automation.bot.solver.TestFixtures.epithet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Schedule-page preview path (`SmartRaceSolverIntegration.previewSchedule`) must gate its epithet catalog by the active scenario/preset the same way the runtime path
 * (`newSolverState`) does, via `epithetsForActiveContext`. When it did not, the solver targeted scenario-exclusive epithets on the wrong scenario (observed: "Dirty Work", a
 * Trackblazer-only epithet, selected on Unity Cup). These tests pin that gate.
 */
@DisplayName("Epithet catalog is gated by the active scenario")
class ScenarioEpithetFilterTest {
    private fun trackblazerOnly(name: String): Epithet =
        epithet(
            name = name,
            matchers = listOf(EpithetMatcher.WinCount(count = 5, filter = EpithetFilter())),
            scenarios = listOf("Trackblazer"),
        )

    @Test
    fun trackblazerOnlyEpithetIsDroppedOnOtherScenarios() {
        val catalog = listOf(trackblazerOnly("Dirty Work"))
        assertTrue(
            SmartRaceSolverIntegration.epithetsForActiveContext(catalog, "Unity Cup", "").isEmpty(),
            "A Trackblazer-only epithet must be dropped from the catalog when the active scenario is Unity Cup.",
        )
    }

    @Test
    fun trackblazerOnlyEpithetSurvivesOnTrackblazer() {
        val catalog = listOf(trackblazerOnly("Dirty Work"))
        assertEquals(
            listOf("Dirty Work"),
            SmartRaceSolverIntegration.epithetsForActiveContext(catalog, "Trackblazer", "").map { it.name },
            "A Trackblazer-only epithet stays in the catalog on its own scenario.",
        )
    }

    @Test
    fun universalEpithetSurvivesEveryScenario() {
        val catalog = listOf(epithet(name = "Speed Star", matchers = listOf(EpithetMatcher.WinCount(count = 3, filter = EpithetFilter()))))
        assertEquals(
            listOf("Speed Star"),
            SmartRaceSolverIntegration.epithetsForActiveContext(catalog, "Unity Cup", "").map { it.name },
            "An epithet with no scenario gate is available on every scenario.",
        )
        assertEquals(
            listOf("Speed Star"),
            SmartRaceSolverIntegration.epithetsForActiveContext(catalog, "Trackblazer", "").map { it.name },
        )
    }
}
