package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.BotStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit tests for mapping a task's result to the outcome the overlay and the notification show. */
@DisplayName("Task outcome mapping")
class TaskOutcomeTest {
    @Test
    fun completeIsFinished() {
        assertEquals(BotStatus.Outcome.FINISHED to "Career complete", outcomeFor(TaskResult.Success()))
    }

    @Test
    fun completeUsesTheGivenReason() {
        assertEquals(BotStatus.Outcome.FINISHED to "Dailies complete", outcomeFor(TaskResult.Success(), "Dailies complete"))
    }

    @Test
    fun breakpointKeepsItsMessage() {
        val result = TaskResult.Success(TaskResultCode.TASK_RESULT_BREAKPOINT_REACHED, "Stopping bot at the specified date: Senior Year Early Jun (Turn 59)")
        assertEquals(BotStatus.Outcome.STOPPED_BY_BOT to "Stopping bot at the specified date: Senior Year Early Jun (Turn 59)", outcomeFor(result))
    }

    @Test
    fun manualStopIsTheUser() {
        val result = TaskResult.Success(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, "Bot was manually stopped by the user.")
        assertEquals(BotStatus.Outcome.STOPPED_BY_USER to "You stopped the bot", outcomeFor(result))
    }

    @Test
    fun unhandledExceptionIsACrash() {
        assertEquals(BotStatus.Outcome.CRASHED to "Task ended unexpectedly.", outcomeFor(TaskResult.Error(TaskResultCode.TASK_RESULT_UNHANDLED_EXCEPTION, "Task ended unexpectedly.")))
    }

    @Test
    fun connectionErrorIsStoppedByTheBot() {
        assertEquals(BotStatus.Outcome.STOPPED_BY_BOT to "Too many connection errors.", outcomeFor(TaskResult.Error(TaskResultCode.TASK_RESULT_CONNECTION_ERROR, "Too many connection errors.")))
    }

    /** Unit tests for deciding whether a career that reached its end screen finished or failed. */
    @DisplayName("Career end result")
    @Nested
    inner class CareerEndResultTest {
        @Test
        @DisplayName("A career that reached its last turns finished")
        fun lastTurnsFinish() {
            val result = careerEndResult(lostRaceTurn = null, endTurn = 75)
            assertEquals(TaskResultCode.TASK_RESULT_COMPLETE, result.code)
            assertEquals(BotStatus.Outcome.FINISHED, outcomeFor(result).first)
            // Grand Live has no finale turns. Its career ends with the final concert after Senior Late December, Turn 72.
            assertEquals(TaskResultCode.TASK_RESULT_COMPLETE, careerEndResult(lostRaceTurn = null, endTurn = 72).code)
        }

        @Test
        @DisplayName("Reaching the end screen before the last regular turn is a failed career")
        fun earlyEndFails() {
            // Seen on device: Tenno Sho (Spring) lost on Turn 56 with every retry used, which ended the career and was reported as complete.
            val result = careerEndResult(lostRaceTurn = null, endTurn = 56)
            assertTrue(result is TaskResult.Error)
            assertEquals(BotStatus.Outcome.STOPPED_BY_BOT, outcomeFor(result).first)
            assertTrue(result.message.contains("Turn 56"), result.message)
        }

        @Test
        @DisplayName("A lost career-ending race names the race loss as the cause")
        fun lostRaceFails() {
            val result = careerEndResult(lostRaceTurn = 12, endTurn = 12)
            assertEquals(BotStatus.Outcome.STOPPED_BY_BOT, outcomeFor(result).first)
            assertTrue(result.message.contains("mandatory race on Turn 12"), result.message)
        }

        @Test
        @DisplayName("A lost race fails the career even on the last turn, which the date alone cannot catch")
        fun lostRaceOnLastTurn() {
            val result = careerEndResult(lostRaceTurn = 72, endTurn = 72)
            assertTrue(result is TaskResult.Error)
        }

        @Test
        @DisplayName("Without a date read this run, the end screen alone does not fail the career")
        fun unknownDateFinishes() {
            // The bot may be started on the end screen itself, so no turn is known.
            val result = careerEndResult(lostRaceTurn = null, endTurn = null)
            assertEquals(TaskResultCode.TASK_RESULT_COMPLETE, result.code)
        }
    }
}
