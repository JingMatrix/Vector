package org.matrix.vector.daemon.ipc

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

  @Synchronized
  fun finish(uid: Int, attempt: Attempt) {
    if (active[uid] == attempt) active.remove(uid)
  }

  @Synchronized
  fun invalidate(uid: Int) {
    nextUidGeneration(uid)
    active.remove(uid)
  }

  /** Invalidates only the active attempts selected by [predicate]. */
  @Synchronized
  fun invalidateMatching(predicate: (Int) -> Boolean): Set<Int> {
    val invalidated = active.keys.filter(predicate).toSet()
    invalidated.forEach { uid ->
      nextUidGeneration(uid)
      active.remove(uid)
    }
    return invalidated
  }

  @Synchronized
  fun clear() {
    cacheGeneration++
    active.clear()
    uidGenerations.clear()
  }

  private fun nextUidGeneration(uid: Int): Long {
    val next = (uidGenerations[uid] ?: 0L) + 1L
    uidGenerations[uid] = next
    return next
  }
}
