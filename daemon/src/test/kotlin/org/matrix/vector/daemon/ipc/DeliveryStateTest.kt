package org.matrix.vector.daemon.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryStateTest {

  @Test
  fun staleSuccessCannotPublishAfterUidGoneAndReplacement() {
    val states = DeliveryStateStore<Any, Any>(now = { 0L })
    val first = states.begin(42) ?: error("first attempt was not created")

    states.invalidateGone(42)
    val replacement = states.begin(42) ?: error("replacement attempt was not created")

    assertFalse(states.commitSuccess(42, first, Any(), Any()))
    assertTrue(states.isCurrentSending(42, replacement))
  }

  @Test
  fun failureAfterUidGoneIsCountedWithoutPublishing() {
    val states = DeliveryStateStore<Any, Any>(now = { 0L })
    val attempt = states.begin(43) ?: error("attempt was not created")

    states.invalidateGone(43)

    assertFalse(states.commitSuccess(43, attempt, Any(), Any()))
    assertFalse(states.recordFailure(43, attempt))
    assertEquals(1, states.failureCount(43))
  }

  @Test
  fun staleFailureCannotOverwriteReplacementSuccess() {
    val states = DeliveryStateStore<Any, Any>(now = { 0L })
    val first = states.begin(7) ?: error("first attempt was not created")
    states.invalidateMatching { it == 7 }
    val replacement = states.begin(7) ?: error("replacement attempt was not created")

    val provider = Any()
    val recipient = Any()
    assertTrue(states.commitSuccess(7, replacement, provider, recipient))
    assertFalse(states.recordFailure(7, first))
    assertTrue(states.isCurrentDelivery(7, provider, recipient))
  }

  @Test
  fun failedActiveUidIsRedeliveredOnGenerationChange() {
    val states = DeliveryStateStore<Any, Any>(now = { 0L })
    val attempt = states.begin(10042) ?: error("attempt was not created")
    states.recordFailure(10042, attempt)
    states.finish(10042, attempt)

    val invalidation = states.invalidateMatching { it == 10042 }

    assertEquals(setOf(10042), invalidation.redeliveryUids)
    val replacement = states.begin(10042) ?: error("replacement attempt was not created")
    assertNotNull(replacement)
  }

  @Test
  fun oldDeathRecipientCannotRemoveReplacementDelivery() {
    val states = DeliveryStateStore<Any, Any>(now = { 0L })
    val firstAttempt = states.begin(11) ?: error("first attempt was not created")
    val oldProvider = Any()
    val oldRecipient = Any()
    assertTrue(states.commitSuccess(11, firstAttempt, oldProvider, oldRecipient))

    states.invalidateMatching { it == 11 }
    val replacementAttempt = states.begin(11) ?: error("replacement attempt was not created")
    val newProvider = Any()
    val newRecipient = Any()
    assertTrue(states.commitSuccess(11, replacementAttempt, newProvider, newRecipient))

    assertFalse(states.removeIfCurrentDelivery(11, oldProvider, oldRecipient))
    assertTrue(states.isCurrentDelivery(11, newProvider, newRecipient))
  }

  @Test
  fun threeFailuresEnterCooldownAndCooldownAttemptKeepsRun() {
    var now = 0L
    val states =
        DeliveryStateStore<Any, Any>(
            now = { now },
            retryCooldownMs = 100,
            failureRunMs = 1_000,
        )

    repeat(3) {
      val attempt = states.begin(9) ?: error("attempt was not created")
      states.recordFailure(9, attempt)
      states.finish(9, attempt)
    }

    now = 100
    val cooldownAttempt = states.begin(9) ?: error("cooldown attempt was not created")
    states.recordFailure(9, cooldownAttempt)
    states.finish(9, cooldownAttempt)
    now = 101

    assertEquals(3, states.failureCount(9))
    assertTrue(states.isThrottled(9))
  }
}
