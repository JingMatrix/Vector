package org.matrix.vector.daemon.ipc

import android.os.SystemClock

/**
 * Keeps the retry throttle for failed module-provider handshakes.
 *
 * The clock and limits are injectable so the lifecycle rules can be tested without waiting for
 * real time. A uid's failure run deliberately survives uidGone() and cache invalidation: those
 * events describe a process or module generation change, not a successful handshake.
 */
internal class BinderFailureTracker(
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val maxConsecutiveFailures: Int = 3,
    private val retryCooldownMs: Long = 60_000L,
    private val failureRunMs: Long = 10 * retryCooldownMs,
) {

  private data class FailureRun(val count: Int, val atElapsed: Long)

  private val failures = mutableMapOf<Int, FailureRun>()

  @Synchronized
  fun isThrottled(uid: Int): Boolean {
    val run = failures[uid] ?: return false
    if (run.count < maxConsecutiveFailures) return false
    return now() - run.atElapsed < retryCooldownMs
  }

  /** Records one failed send and reports whether this call crossed the throttle threshold. */
  @Synchronized
  fun recordFailure(uid: Int): Boolean {
    val timestamp = now()
    val previous = failures[uid]
    val count =
        when {
          previous == null || timestamp - previous.atElapsed >= failureRunMs -> 1
          else -> minOf(previous.count + 1, maxConsecutiveFailures)
        }
    failures[uid] = FailureRun(count, timestamp)
    return count == maxConsecutiveFailures && (previous?.count ?: 0) < count
  }

  @Synchronized
  fun clear(uid: Int) {
    failures.remove(uid)
  }

  internal fun count(uid: Int): Int? = synchronized(this) { failures[uid]?.count }
}
