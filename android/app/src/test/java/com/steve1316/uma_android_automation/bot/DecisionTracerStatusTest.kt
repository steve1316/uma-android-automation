package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.BotStatus
import com.steve1316.uma_android_automation.types.StatName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the overlay status text that `DecisionTracer` pushes to the automation library each turn. */
@DisplayName("DecisionTracer status text")
class DecisionTracerStatusTest {
    @Test
    fun labelsCareerTurns() {
        assertEquals("Turn 1/72", DecisionTracer.statusLabelFor(1))
        assertEquals("Turn 34/72", DecisionTracer.statusLabelFor(34))
        assertEquals("Turn 72/72", DecisionTracer.statusLabelFor(72))
    }

    @Test
    fun labelsFinaleTurns() {
        assertEquals("Finale 1/3", DecisionTracer.statusLabelFor(73))
        assertEquals("Finale 3/3", DecisionTracer.statusLabelFor(75))
        assertEquals("Finale 3/3", DecisionTracer.statusLabelFor(76))
    }

    @Test
    fun pushesTheTurnToTheStatus() {
        DecisionTracer.pushProgress(59)
        val snapshot = BotStatus.snapshot()
        assertEquals(59, snapshot.current)
        assertEquals(72, snapshot.total)
        assertEquals("Turn 59/72", snapshot.label)
    }

    @Test
    fun describesTraining() {
        assertEquals("Trained Speed", DecisionTracer.statusDetailFor(MainScreenAction.TRAIN, StatName.SPEED))
        assertEquals("Trained Wit", DecisionTracer.statusDetailFor(MainScreenAction.TRAIN, StatName.WIT))
        assertEquals("Trained", DecisionTracer.statusDetailFor(MainScreenAction.TRAIN, null))
    }

    @Test
    fun describesOtherActions() {
        assertEquals("Raced", DecisionTracer.statusDetailFor(MainScreenAction.RACE, null))
        assertEquals("Rested", DecisionTracer.statusDetailFor(MainScreenAction.REST, null))
        assertEquals("Recovered mood", DecisionTracer.statusDetailFor(MainScreenAction.RECOVER_MOOD, null))
        assertEquals("Went on a date", DecisionTracer.statusDetailFor(MainScreenAction.DATE, null))
        assertEquals("", DecisionTracer.statusDetailFor(MainScreenAction.NONE, null))
        assertEquals("", DecisionTracer.statusDetailFor(null, null))
    }
}
