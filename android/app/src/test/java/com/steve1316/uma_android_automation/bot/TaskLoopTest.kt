package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.StepAbortedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** One scripted `process()` call. It runs with the fake hooks as its receiver so it can raise an abort or ask for a stop. */
private typealias Step = FakeHooks.() -> TaskResult?

/** The result a fake step ends the run with. */
private val DONE: TaskResult = TaskResult.Success()

/**
 * Scripted loop hooks. Each script is used up in order and then falls back to a default.
 *
 * @param steps What each `process()` call does, in order.
 * @param paused What each `awaitIfPaused()` call returns. False once used up.
 * @param inFront What each `ensureGameInFront()` call returns. True once used up.
 */
private class FakeHooks(steps: List<Step>, paused: List<Boolean> = emptyList(), inFront: List<Boolean> = emptyList()) : TaskLoopHooks {
    /** Steps left to run. */
    private val steps = ArrayDeque(steps)

    /** `awaitIfPaused()` answers left. */
    private val paused = ArrayDeque(paused)

    /** `ensureGameInFront()` answers left. */
    private val inFront = ArrayDeque(inFront)

    /** True once a step asked for a stop. */
    var stopRequested = false

    /** True while an abort is pending. */
    var abortRaised = false

    /** How many times `process()` ran. */
    var processCalls = 0

    /** How many times `onResumeAfterAbort()` ran. */
    var resyncCalls = 0

    /** How many times `acknowledgeAbort()` ran. */
    var ackCalls = 0

    /** How many times `ensureGameInFront()` ran. */
    var inFrontCalls = 0

    /** When true, a stop lands while `acknowledgeAbort()` runs. */
    var stopDuringAck = false

    /**
     * Aborts the running step the way a library checkpoint does.
     *
     * @return Never returns.
     */
    fun abort(): Nothing {
        abortRaised = true
        throw StepAbortedException()
    }

    override fun awaitIfPaused(): Boolean = paused.removeFirstOrNull() ?: false

    override fun acknowledgeAbort(): Boolean {
        ackCalls++
        val had = abortRaised
        abortRaised = false
        if (stopDuringAck) stopRequested = true
        return had
    }

    override fun isStopRequested(): Boolean = stopRequested

    override fun onResumeAfterAbort() {
        resyncCalls++
    }

    override fun ensureGameInFront(): Boolean {
        inFrontCalls++
        return inFront.removeFirstOrNull() ?: true
    }

    override fun process(): TaskResult? {
        processCalls++
        return steps.removeFirst().invoke(this)
    }
}

/** Unit tests for the task loop: the restart after a pause abort, the resync, the foreground re-pause, and Stop winning over a restart. */
@DisplayName("Task loop")
class TaskLoopTest {
    @Test
    fun resyncRunsOnceAfterAnAbort() {
        val hooks = FakeHooks(listOf<Step>({ abort() }, { DONE }), paused = listOf(false, true))
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(2, hooks.processCalls)
        assertEquals(1, hooks.resyncCalls)
        assertEquals(1, hooks.ackCalls)
    }

    @Test
    fun resyncRunsEvenWhenThePauseWasCancelledAfterTheAbort() {
        val hooks = FakeHooks(listOf<Step>({ abort() }, { DONE }))
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(1, hooks.resyncCalls)
        assertEquals(0, hooks.inFrontCalls)
    }

    @Test
    fun stopBeatsARestart() {
        val hooks =
            FakeHooks(
                listOf<Step>({
                    stopRequested = true
                    abort()
                }),
            )
        assertEquals(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, runTaskLoop(hooks).code)
        assertEquals(0, hooks.ackCalls)
        assertEquals(0, hooks.resyncCalls)
        assertEquals(1, hooks.processCalls)
    }

    @Test
    fun aPlainInterruptIsAManualStop() {
        val hooks = FakeHooks(listOf<Step>({ throw InterruptedException() }))
        val result = runTaskLoop(hooks)
        assertEquals(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, result.code)
        assertEquals("Bot was manually stopped by the user.", result.message)
        assertEquals(1, hooks.processCalls)
    }

    @Test
    fun falloutFromAnAbortRestarts() {
        val hooks =
            FakeHooks(
                listOf<Step>(
                    {
                        abortRaised = true
                        throw IllegalStateException("No screenshot after the abort")
                    },
                    { DONE },
                ),
            )
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(1, hooks.resyncCalls)
    }

    @Test
    fun otherFailuresStillPropagate() {
        val hooks = FakeHooks(listOf<Step>({ throw IllegalStateException("Unhandled dialog: mystery") }))
        assertThrows(IllegalStateException::class.java) { runTaskLoop(hooks) }
    }

    @Test
    fun aFailureDuringAStopIsNotRestarted() {
        val hooks =
            FakeHooks(
                listOf<Step>({
                    stopRequested = true
                    abortRaised = true
                    throw IllegalStateException("No screenshot after the stop")
                }),
            )
        assertThrows(IllegalStateException::class.java) { runTaskLoop(hooks) }
        assertEquals(0, hooks.ackCalls)
    }

    @Test
    fun aPauseAtTheSafePointAlsoResyncs() {
        val hooks = FakeHooks(listOf<Step>({ DONE }), paused = listOf(true))
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(1, hooks.resyncCalls)
        assertEquals(1, hooks.inFrontCalls)
    }

    @Test
    fun gameNotInFrontPausesAgainBeforeAnyStep() {
        val hooks = FakeHooks(listOf<Step>({ DONE }), paused = listOf(true, true), inFront = listOf(false, true))
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(1, hooks.processCalls)
        assertEquals(2, hooks.inFrontCalls)
        assertEquals(2, hooks.resyncCalls)
    }

    @Test
    fun foregroundIsOnlyCheckedAfterAPause() {
        val hooks = FakeHooks(listOf<Step>({ null }, { DONE }))
        assertSame(DONE, runTaskLoop(hooks))
        assertEquals(2, hooks.processCalls)
        assertEquals(0, hooks.inFrontCalls)
        assertEquals(0, hooks.resyncCalls)
    }

    @Test
    fun aStopDuringTheAcknowledgeEndsTheRunAfterAnInterrupt() {
        val hooks = FakeHooks(listOf<Step>({ stopDuringAck = true; abort() }, { DONE }))
        assertEquals(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, runTaskLoop(hooks).code)
        assertEquals(1, hooks.ackCalls)
        assertEquals(0, hooks.resyncCalls)
        assertEquals(1, hooks.processCalls)
    }

    @Test
    fun aStopDuringTheAcknowledgeEndsTheRunAfterFallout() {
        val hooks =
            FakeHooks(
                listOf<Step>(
                    {
                        stopDuringAck = true
                        abortRaised = true
                        throw IllegalStateException("No screenshot after the abort")
                    },
                    { DONE },
                ),
            )
        assertEquals(TaskResultCode.TASK_RESULT_MANUALLY_STOPPED, runTaskLoop(hooks).code)
        assertEquals(1, hooks.ackCalls)
        assertEquals(0, hooks.resyncCalls)
        assertEquals(1, hooks.processCalls)
    }
}
