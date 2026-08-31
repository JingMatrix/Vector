package org.matrix.vector.daemon.ipc

internal enum class DeliveryCompletion {
  COMMIT,
  RECORD_FAILURE,
  IGNORE_STALE,
}

/**
 * Serializes one Binder delivery attempt per uid and invalidates work after lifecycle changes.
 *
 * The observer callbacks and delivery workers run on different threads. Keeping the attempt
 * ownership and generations behind one synchronized boundary prevents a duplicate callback from
 * invalidating the worker that already owns the uid, and prevents a stale worker from removing a
 * replacement attempt when it finishes.
 */
internal class DeliveryAttemptTracker {

  internal data class Attempt(val cacheGeneration: Long, val uidGeneration: Long)

  private var cacheGeneration = 0L
  private val uidGenerations = mutableMapOf<Int, Long>()
  private val successfulUidGenerations = mutableMapOf<Int, Long>()
  private val active = mutableMapOf<Int, Attempt>()

  @Synchronized
  fun begin(uid: Int): Attempt? {
    if (active.containsKey(uid)) return null
    val attempt = Attempt(cacheGeneration, nextUidGeneration(uid))
    active[uid] = attempt
    return attempt
  }

  @Synchronized
  fun isCurrent(uid: Int, attempt: Attempt): Boolean =
      cacheGeneration == attempt.cacheGeneration && active[uid] == attempt &&
          uidGenerations[uid] == attempt.uidGeneration

  /**
   * Applies failure accounting while holding the same generation lock as lifecycle invalidation.
   * A uidGone-invalidated failure still counts until a newer delivery succeeds. Cache clears and
   * failures older than a successful replacement are ignored, so they cannot recreate throttling.
   */
  @Synchronized
  fun complete(
      uid: Int,
      attempt: Attempt,
      delivered: Boolean,
      clearFailures: () -> Unit,
      recordFailure: () -> Unit,
  ): DeliveryCompletion {
    if (cacheGeneration != attempt.cacheGeneration) {
      return DeliveryCompletion.IGNORE_STALE
    }

    val current = active[uid] == attempt && uidGenerations[uid] == attempt.uidGeneration
    if (delivered) {
      if (!current) return DeliveryCompletion.IGNORE_STALE
      successfulUidGenerations[uid] = attempt.uidGeneration
      clearFailures()
      return DeliveryCompletion.COMMIT
    }

    if ((successfulUidGenerations[uid] ?: Long.MIN_VALUE) > attempt.uidGeneration) {
      return DeliveryCompletion.IGNORE_STALE
    }
    recordFailure()
    return DeliveryCompletion.RECORD_FAILURE
  }

  @Synchronized
  fun finish(uid: Int, attempt: Attempt) {
    if (active[uid] == attempt) active.remove(uid)
  }

  @Synchronized
  fun invalidate(uid: Int) {
    nextUidGeneration(uid)
    active.remove(uid)
  }

  @Synchronized
  fun clear() {
    cacheGeneration++
    active.clear()
    uidGenerations.clear()
    successfulUidGenerations.clear()
  }

  private fun nextUidGeneration(uid: Int): Long {
    val next = (uidGenerations[uid] ?: 0L) + 1L
    uidGenerations[uid] = next
    return next
  }
}
