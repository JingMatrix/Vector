package org.matrix.vector.impl.core

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory

/** One finite worker per non-empty batch; no idle thread, timer, or shared pool. */
internal class OnDemandSerialExecutor(
    private val threadFactory: ThreadFactory,
    private val onFailure: (Throwable) -> Unit,
) : Executor {
    private val lock = Any()
    private var queue: ArrayDeque<Runnable>? = null
    private var running = false

    override fun execute(command: Runnable) {
        synchronized(lock) {
            val pending = queue ?: ArrayDeque<Runnable>().also { queue = it }
            pending.addLast(command)
            if (running) return

            // Keep the idle -> running transition and start failure atomic with submissions.
            running = true
            try {
                val thread =
                    threadFactory.newThread(Runnable { drain() })
                        ?: throw IllegalStateException("Hot reload thread factory returned null")
                thread.start()
            } catch (failure: Throwable) {
                // There was no previous worker or pending batch when running was false.
                pending.removeLast()
                queue = null
                running = false
                throw failure
            }
        }
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(lock) {
                    val pending = queue
                    if (pending == null || pending.isEmpty()) {
                        // A later submission starts a new worker; this worker runs no more tasks.
                        // Release the backing array as well as its already-removed request objects.
                        queue = null
                        running = false
                        return
                    }
                    pending.removeFirst()
                }
            // Module code may set its worker's interrupt flag. Do not pass that to the next task.
            Thread.interrupted()
            try {
                next.run()
            } catch (failure: Throwable) {
                try {
                    onFailure(failure)
                } catch (_: Throwable) {
                    // Even a failing reporter must not strand already-accepted requests.
                }
            }
        }
    }
}
