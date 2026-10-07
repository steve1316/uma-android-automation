package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.DialogHandler
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogUtils

/** The possible result codes for a task's execution. */
enum class TaskResultCode {
    /** The task completed all its objectives successfully. */
    TASK_RESULT_COMPLETE,

    /** The task reached a predefined breakpoint and stopped. */
    TASK_RESULT_BREAKPOINT_REACHED,

    /** The task was manually stopped by the user. */
    TASK_RESULT_MANUALLY_STOPPED,

    /** An unhandled exception occurred during task execution. */
    TASK_RESULT_UNHANDLED_EXCEPTION,

    /** A connection error occurred during task execution. */
    TASK_RESULT_CONNECTION_ERROR,
}

/** Represents the final result of a task's execution. */
sealed interface TaskResult {
    val code: TaskResultCode
    val message: String

    /**
     * Indicates a successful task completion.
     *
     * @property code The [TaskResultCode] associated with the result.
     * @property message A descriptive message about the result.
     */
    data class Success(override val code: TaskResultCode = TaskResultCode.TASK_RESULT_COMPLETE, override val message: String = "Task completed successfully.") : TaskResult

    /**
     * Indicates a task completion with errors.
     *
     * @property code The [TaskResultCode] associated with the result.
     * @property message A descriptive message about the error.
     */
    data class Error(override val code: TaskResultCode = TaskResultCode.TASK_RESULT_UNHANDLED_EXCEPTION, override val message: String = "Task completed with errors.") : TaskResult
}

/**
 * Base class for all automation tasks.
 *
 * @property game The [Game] instance used for bot interaction.
 */
abstract class Task(game: Game) : DialogHandler(game) {
    companion object {
        val TAG: String = "[${MainActivity.loggerTag}]${this::class.simpleName}"
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /**
     * Run all tests for this task.
     *
     * @return Whether any tests were executed.
     */
    open fun startTests(): Boolean {
        return false
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Process a single iteration of the task's main loop.
     *
     * @return A [TaskResult] if the main loop should stop, or null to continue iterating.
     */
    abstract fun process(): TaskResult?

    /**
     * Attempt to handle all active dialog boxes.
     *
     * This method continuously handles dialogs until no more are detected or the timeout is reached.
     *
     * @param timeoutMs The maximum time (in milliseconds) allowed for this operation.
     * @param sourceBitmap Optional screenshot to use for the first dialog check only. Later checks capture fresh screenshots since handling a dialog changes the screen.
     * @return True if at least one dialog was successfully handled, false otherwise.
     * @throws IllegalStateException If an unhandled dialog is detected.
     */
    fun tryHandleAllDialogs(timeoutMs: Int = 15000, sourceBitmap: Bitmap? = null): Boolean {
        var bWasDialogHandled = false
        var dialogResult: DialogHandlerResult = DialogHandlerResult.NoDialogDetected
        // Detect the first dialog on the passed screenshot so handleDialogs() does not capture again. Later dialogs are detected on fresh screenshots.
        var pendingDialog: DialogInterface? = sourceBitmap?.let { DialogUtils.getDialog(game.imageUtils, it) ?: return false }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            dialogResult = handleDialogs(pendingDialog)
            pendingDialog = null

            if (dialogResult !is DialogHandlerResult.Handled) {
                break
            }
            bWasDialogHandled = true
        }

        if (dialogResult is DialogHandlerResult.Unhandled) {
            throw IllegalStateException("Unhandled dialog: ${dialogResult.dialog.name}")
        }

        return bWasDialogHandled
    }

    /**
     * Handle cleanup actions when the task's main loop finishes.
     *
     * This method logs the result and sends a Discord notification if enabled.
     *
     * @param result The [TaskResult] that caused the task to end.
     */
    private fun handleTaskEnd(result: TaskResult) {
        val logMessage = "${result.javaClass.simpleName} (${result.code}): ${result.message}"
        game.notificationMessage = logMessage
        val discordMessage = "${this::class.simpleName}:: ${result.javaClass.simpleName} (${result.code}): ${result.message}"
        var diffChar: String
        when (result) {
            is TaskResult.Success -> {
                MessageLog.i(TAG, logMessage)
                diffChar = "+"
            }

            is TaskResult.Error -> {
                MessageLog.e(TAG, logMessage)
                diffChar = "-"
            }
        }

        if (DiscordUtils.enableDiscordNotifications) {
            DiscordUtils.queue.add("```diff\n$diffChar ${MessageLog.getSystemTimeString()} $discordMessage.\n```")
            // Wait to ensure the Discord message queue is processed.
            game.wait(1.0, skipWaitingForLoading = true)
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Run the task's main loop until completion or manual stop.
     *
     * @return The final [TaskResult] of the task's execution.
     */
    open fun start(): TaskResult {
        var result: TaskResult =
            TaskResult.Error(
                TaskResultCode.TASK_RESULT_UNHANDLED_EXCEPTION,
                "Task ended unexpectedly.",
            )

        while (true) {
            try {
                val tmpResult: TaskResult? = process()
                // Stop the task if a non-null result is received.
                if (tmpResult != null) {
                    result = tmpResult
                    break
                }
            } catch (e: InterruptedException) {
                result =
                    TaskResult.Success(
                        TaskResultCode.TASK_RESULT_MANUALLY_STOPPED,
                        "Bot was manually stopped by the user.",
                    )
                break
            }
        }

        handleTaskEnd(result)
        return result
    }
}
