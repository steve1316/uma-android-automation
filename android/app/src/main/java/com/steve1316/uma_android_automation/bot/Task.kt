package com.steve1316.uma_android_automation.bot

import android.graphics.Bitmap
import com.steve1316.automation_library.utils.BotHold
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.BotStatus
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.utils.FocusWatch
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.DialogHandler
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogUtils

/** The result a run ends with when the user stops it. Its message is the one the task-end log line has always printed. */
internal val MANUALLY_STOPPED_RESULT: TaskResult = TaskResult.Success(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, "Bot was manually stopped by the user.")

/** Package names of the game. The library pauses the run when any other app takes the screen. Only the Global release is supported. */
internal val UMA_GAME_PACKAGES: Set<String> = setOf("com.cygames.umamusume")

/** Pause reason shown when the bot was resumed while another app is in front. */
private const val GAME_NOT_IN_FRONT_REASON = "Open the game, then tap Resume"

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
 * Maps a task's result to the outcome the overlay and the notification show.
 *
 * @param result The result the task ended with.
 * @return The outcome and the reason text shown with it.
 */
fun outcomeFor(result: TaskResult): Pair<BotStatus.Outcome, String> =
    when (result.code) {
        TaskResultCode.TASK_RESULT_COMPLETE -> BotStatus.Outcome.FINISHED to "Career complete"
        TaskResultCode.TASK_RESULT_BREAKPOINT_REACHED -> BotStatus.Outcome.STOPPED_BY_BOT to result.message
        TaskResultCode.TASK_RESULT_MANUALLY_STOPPED -> BotStatus.Outcome.STOPPED_BY_USER to "You stopped the bot"
        TaskResultCode.TASK_RESULT_UNHANDLED_EXCEPTION -> BotStatus.Outcome.CRASHED to result.message
        TaskResultCode.TASK_RESULT_CONNECTION_ERROR -> BotStatus.Outcome.STOPPED_BY_BOT to result.message
    }

/** What the task loop needs from the bot, so the loop itself can be unit tested with fakes. */
internal interface TaskLoopHooks {
    /**
     * Waits at the safe point while the user has paused the bot.
     *
     * @return True if the bot was paused there.
     */
    fun awaitIfPaused(): Boolean

    /**
     * Clears an abort raised by a pause.
     *
     * @return True if the interrupt or failure came from a pause abort.
     */
    fun acknowledgeAbort(): Boolean

    /**
     * Whether the user or the system asked the run to stop.
     *
     * @return True once a stop was requested. A stop always wins over a restart.
     */
    fun isStopRequested(): Boolean

    /**
     * Clears per-turn state so the next step decides afresh from the screen.
     */
    fun onResumeAfterAbort()

    /**
     * Checks that the game is in front after a pause, and asks for a new pause when it is not.
     *
     * @return True when the game is in front or unknown, false when a new pause was requested.
     */
    fun ensureGameInFront(): Boolean

    /**
     * Runs one step of the task.
     *
     * @return A result to end the loop, or null to keep going.
     */
    fun process(): TaskResult?
}

/**
 * Runs a task's steps until one returns a result. A pause can abort a step midway. The loop then waits at the safe point, clears per-turn
 * state, checks the game is in front, and starts the next step from the current screen. A stop always ends the run, even mid-pause.
 *
 * @param hooks What the loop needs from the bot.
 * @return The result that ended the loop.
 */
internal fun runTaskLoop(hooks: TaskLoopHooks): TaskResult {
    var bPendingResync = false
    while (true) {
        try {
            val bPaused = hooks.awaitIfPaused()
            if (bPaused || bPendingResync) {
                bPendingResync = false
                hooks.onResumeAfterAbort()
            }
            if (bPaused && !hooks.ensureGameInFront()) continue
            val result = hooks.process()
            if (result != null) return result
        } catch (e: Exception) {
            // StepAbortedException is an InterruptedException and a stop always wins. Other abort fallout, like a missing screenshot, restarts the same way.
            if (hooks.isStopRequested() || !hooks.acknowledgeAbort()) {
                if (e is InterruptedException) return MANUALLY_STOPPED_RESULT else throw e
            }
            if (hooks.isStopRequested()) return MANUALLY_STOPPED_RESULT
            bPendingResync = true
        }
    }
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
        val (outcome, reason) = outcomeFor(result)
        BotStatus.setOutcome(outcome, reason)
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
     * A pause can abort a step midway. The loop then waits for Resume, clears per-turn state, and carries on from whatever screen the game
     * is on. Mid-step pause is on for the bot thread only while the loop runs, so the end-of-run cleanup below is never aborted.
     *
     * @return The final [TaskResult] of the task's execution.
     */
    open fun start(): TaskResult {
        BotHold.enableMidStepPause(UMA_GAME_PACKAGES)
        val result =
            try {
                runTaskLoop(loopHooks())
            } finally {
                BotHold.disableMidStepPause()
            }

        handleTaskEnd(result)
        return result
    }

    /**
     * Called after a pause landed or aborted a step, before the next step runs. Clears per-turn state so the next step decides afresh from
     * the screen. Default no-op.
     */
    open fun onResumeAfterAbort() {
        return
    }

    /**
     * Connects the task loop to the library's pause state and to this task.
     *
     * @return The hooks for `runTaskLoop()`.
     */
    private fun loopHooks(): TaskLoopHooks =
        object : TaskLoopHooks {
            override fun awaitIfPaused(): Boolean = BotHold.awaitIfPaused()

            override fun acknowledgeAbort(): Boolean = BotHold.acknowledgeAbort()

            // A stop sets its outcome before it interrupts the bot thread, and BotService.isRunning turns false during the stop's cleanup.
            override fun isStopRequested(): Boolean = BotStatus.snapshot().outcome != null || !BotService.isRunning

            override fun onResumeAfterAbort() = this@Task.onResumeAfterAbort()

            override fun ensureGameInFront(): Boolean = this@Task.ensureGameInFront()

            override fun process(): TaskResult? = this@Task.process()
        }

    /**
     * After a pause, checks that the game is the app in front. When another app is, asks for a new pause so the bot never taps over it.
     *
     * @return True when the game is in front or the app in front is unknown, false when a new pause was requested.
     */
    private fun ensureGameInFront(): Boolean {
        val foreground = FocusWatch.foregroundPackage
        if (foreground == null || foreground in UMA_GAME_PACKAGES) return true
        MessageLog.i(TAG, "[PAUSE] $foreground is in front instead of the game. Pausing again until the game is open.")
        BotHold.requestPause(GAME_NOT_IN_FRONT_REASON)
        return false
    }
}
