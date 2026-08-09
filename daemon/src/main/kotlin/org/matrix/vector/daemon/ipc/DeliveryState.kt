package org.matrix.vector.daemon.ipc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The complete lifecycle of one module-app UID.
 *
 * Failure accounting and the active bit live in every state rather than in side maps. The
 * [DeliveryStateStore] changes one UID only through [ConcurrentHashMap.compute], so a worker result
 * cannot pass an ownership check and then be invalidated before its state is committed.
 */
internal sealed interface DeliveryState<Provider, Recipient> {
  val active: Boolean
  val lastAttemptId: Long

  data class Idle<Provider, Recipient>(
      override val active: Boolean,
      override val lastAttemptId: Long = 0L,
      val failureCount: Int = 0,
      val lastFailureAt: Long = 0L,
  ) : DeliveryState<Provider, Recipient>

  data class Sending<Provider, Recipient>(
      override val active: Boolean,
      val attemptId: Long,
      val failureCount: Int = 0,
      val lastFailureAt: Long = 0L,
  ) : DeliveryState<Provider, Recipient> {
    override val lastAttemptId: Long
      get() = attemptId
  }

  data class Delivered<Provider, Recipient>(
      override val active: Boolean,
      val attemptId: Long,
      val provider: Provider,
      val recipient: Recipient,
  ) : DeliveryState<Provider, Recipient> {
    override val lastAttemptId: Long
      get() = attemptId
  }

  data class Throttled<Provider, Recipient>(
      override val active: Boolean,
      override val lastAttemptId: Long,
      val count: Int,
      val lastFailureAt: Long,
      val cooldownUntil: Long,
  ) : DeliveryState<Provider, Recipient>
}

/**
 * Atomic state machine for module-app binder delivery.
 *
 * There is deliberately one map and no companion attempt/registry/failure maps. A tombstoned
 * [Sending] state is retained after [uidGone] until its worker completes, which lets a failure be
 * counted while still preventing that old worker from publishing a replacement.
 */
internal class DeliveryStateStore<Provider, Recipient>(
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxConsecutiveFailures: Int = 3,
    private val retryCooldownMs: Long = 60_000L,
    private val failureRunMs: Long = 10 * retryCooldownMs,
) {

  internal data class RemovedDelivery<Provider, Recipient>(
      val uid: Int,
      val provider: Provider,
      val recipient: Recipient,
  )

  internal data class Invalidation<Provider, Recipient>(
      val redeliveryUids: Set<Int>,
      val removedDeliveries: List<RemovedDelivery<Provider, Recipient>>,
  )

  private val states = ConcurrentHashMap<Int, DeliveryState<Provider, Recipient>>()
  private val nextAttemptId = AtomicLong()

  /** Marks the UID active and atomically claims a new attempt when eligible. */
  fun begin(uid: Int): Long? {
    var claimed: Long? = null
    states.compute(uid) { _, current ->
      when (current) {
        null ->
            DeliveryState.Sending<Provider, Recipient>(
                active = true,
                attemptId = nextAttemptId.incrementAndGet(),
            )
                .also { claimed = it.attemptId }
        is DeliveryState.Idle ->
            DeliveryState.Sending<Provider, Recipient>(
                active = true,
                attemptId = nextAttemptId.incrementAndGet(),
                failureCount = current.failureCount,
                lastFailureAt = current.lastFailureAt,
            )
                .also { claimed = it.attemptId }
        is DeliveryState.Sending ->
            if (!current.active) {
              DeliveryState.Sending<Provider, Recipient>(
                      active = true,
                      attemptId = nextAttemptId.incrementAndGet(),
                      failureCount = current.failureCount,
                      lastFailureAt = current.lastFailureAt,
                  )
                  .also { claimed = it.attemptId }
            } else {
              current
            }
        is DeliveryState.Delivered -> current.copy(active = true)
        is DeliveryState.Throttled ->
            if (now() >= current.cooldownUntil) {
              DeliveryState.Sending<Provider, Recipient>(
                      active = true,
                      attemptId = nextAttemptId.incrementAndGet(),
                      failureCount = current.count,
                      lastFailureAt = current.lastFailureAt,
                  )
                  .also { claimed = it.attemptId }
            } else {
              current.copy(active = true)
            }
      }
    }
    return claimed
  }

  fun isCurrentSending(uid: Int, attemptId: Long): Boolean =
      (states[uid] as? DeliveryState.Sending)?.let { it.active && it.attemptId == attemptId } == true

  /** Completes a non-delivery path without touching a newer state. */
  fun finish(uid: Int, attemptId: Long) {
    states.computeIfPresent(uid) { _, current ->
      val sending = current as? DeliveryState.Sending ?: return@computeIfPresent current
      if (sending.attemptId != attemptId) return@computeIfPresent current
      if (!sending.active && sending.failureCount == 0) return@computeIfPresent null
      DeliveryState.Idle(
          active = sending.active,
          lastAttemptId = attemptId,
          failureCount = sending.failureCount,
          lastFailureAt = sending.lastFailureAt,
      )
    }
  }

  /** Publishes a successful provider only if this worker still owns the UID. */
  fun commitSuccess(
      uid: Int,
      attemptId: Long,
      provider: Provider,
      recipient: Recipient,
  ): Boolean {
    var accepted = false
    states.computeIfPresent(uid) { _, current ->
      val sending = current as? DeliveryState.Sending ?: return@computeIfPresent current
      if (!sending.active || sending.attemptId != attemptId) return@computeIfPresent current
      accepted = true
      DeliveryState.Delivered(
          active = true,
          attemptId = attemptId,
          provider = provider,
          recipient = recipient,
      )
    }
    return accepted
  }

  /** Records a result only for this attempt or an invalidated tombstone for this same attempt. */
  fun recordFailure(uid: Int, attemptId: Long): Boolean {
    var crossed = false
    val timestamp = now()
    states.computeIfPresent(uid) { _, current ->
      val previousCount: Int
      val previousAt: Long
      val active: Boolean
      when (current) {
        is DeliveryState.Sending -> {
          if (current.attemptId != attemptId) return@computeIfPresent current
          previousCount = current.failureCount
          previousAt = current.lastFailureAt
          active = current.active
        }
        is DeliveryState.Idle -> {
          if (current.lastAttemptId != attemptId) return@computeIfPresent current
          previousCount = current.failureCount
          previousAt = current.lastFailureAt
          active = current.active
        }
        else -> return@computeIfPresent current
      }

      val count =
          if (previousCount == 0 || timestamp - previousAt >= failureRunMs) {
            1
          } else {
            minOf(previousCount + 1, maxConsecutiveFailures)
          }
      crossed = count == maxConsecutiveFailures && previousCount < count
      if (count >= maxConsecutiveFailures) {
        DeliveryState.Throttled(
            active = active,
            lastAttemptId = attemptId,
            count = count,
            lastFailureAt = timestamp,
            cooldownUntil = timestamp + retryCooldownMs,
        )
      } else {
        DeliveryState.Idle(
            active = active,
            lastAttemptId = attemptId,
            failureCount = count,
            lastFailureAt = timestamp,
        )
      }
    }
    return crossed
  }

  fun isCurrentDelivery(uid: Int, provider: Provider, recipient: Recipient): Boolean =
      (states[uid] as? DeliveryState.Delivered)?.let {
        it.provider === provider && it.recipient === recipient
      } == true

  internal fun failureCount(uid: Int): Int =
      when (val state = states[uid]) {
        is DeliveryState.Idle -> state.failureCount
        is DeliveryState.Sending -> state.failureCount
        is DeliveryState.Throttled -> state.count
        is DeliveryState.Delivered, null -> 0
      }

  internal fun isThrottled(uid: Int): Boolean =
      (states[uid] as? DeliveryState.Throttled)?.let { now() < it.cooldownUntil } == true

  /** Removes a delivery only when its provider and recipient are still the current pair. */
  fun removeIfCurrentDelivery(uid: Int, provider: Provider, recipient: Recipient): Boolean {
    var removed = false
    states.computeIfPresent(uid) { _, current ->
      val delivered = current as? DeliveryState.Delivered ?: return@computeIfPresent current
      if (delivered.provider !== provider || delivered.recipient !== recipient) {
        return@computeIfPresent current
      }
      removed = true
      DeliveryState.Idle(active = delivered.active, lastAttemptId = delivered.attemptId)
    }
    return removed
  }

  /**
   * Invalidates one module generation while preserving active UID observations and failure runs.
   * The returned provider pairs are unlinked by the caller outside the map operation.
   */
  fun invalidateMatching(predicate: (Int) -> Boolean): Invalidation<Provider, Recipient> {
    val redelivery = mutableSetOf<Int>()
    val removed = mutableListOf<RemovedDelivery<Provider, Recipient>>()
    states.keys.filter(predicate).forEach { uid ->
      states.computeIfPresent(uid) { _, current ->
        if (current.active) redelivery += uid
        when (current) {
          is DeliveryState.Delivered -> {
            removed += RemovedDelivery(uid, current.provider, current.recipient)
            DeliveryState.Idle(active = true, lastAttemptId = current.attemptId)
          }
          is DeliveryState.Sending ->
              DeliveryState.Idle(
                  active = current.active,
                  lastAttemptId = current.attemptId,
                  failureCount = current.failureCount,
                  lastFailureAt = current.lastFailureAt,
              )
          is DeliveryState.Idle -> current
          is DeliveryState.Throttled -> current
        }
      }
    }
    return Invalidation(redelivery, removed)
  }

  /** Marks a UID gone. A running worker gets a tombstone; an idle UID is removed immediately. */
  fun invalidateGone(uid: Int): RemovedDelivery<Provider, Recipient>? {
    var removed: RemovedDelivery<Provider, Recipient>? = null
    states.computeIfPresent(uid) { _, current ->
      when (current) {
        is DeliveryState.Sending -> current.copy(active = false)
        is DeliveryState.Delivered -> {
          removed = RemovedDelivery(uid, current.provider, current.recipient)
          null
        }
        is DeliveryState.Idle ->
            if (current.failureCount > 0) current.copy(active = false) else null
        is DeliveryState.Throttled -> current.copy(active = false)
      }
    }
    return removed
  }
}
