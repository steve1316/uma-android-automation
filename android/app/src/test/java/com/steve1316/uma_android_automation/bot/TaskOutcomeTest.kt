package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.BotStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for mapping a task's result to the outcome the overlay and the notification show. */
@DisplayName("Task outcome mapping")
class TaskOutcomeTest {
    @Test
    fun completeIsFinished() {
        assertEquals(BotStatus.Outcome.FINISHED to "Career complete", outcomeFor(TaskResult.Success()))
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
}
