package org.matrix.vector.daemon.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderFailureTrackerTest {

  @Test
  fun failureDuringUidChurnStillReachesThrottle() {
    var now = 0L
    val failures =
        BinderFailureTracker(
            now = { now },
            maxConsecutiveFailures = 3,
            retryCooldownMs = 100,
            failureRunMs = 1_000,
        )
    val attempts = DeliveryAttemptTracker()
    val attempt = attempts.begin(42) ?: error("attempt was not created")

    assertFalse(failures.recordFailure(42))
    attempts.invalidate(42)
    now = 1
    assertFalse(failures.recordFailure(42))
    now = 2
    assertTrue(failures.recordFailure(42))

    assertEquals(3, failures.count(42))
    assertFalse(attempts.isCurrent(42, attempt))
    assertTrue(failures.isThrottled(42))
  }

  @Test
  fun cooldownAttemptDoesNotResetTheFailureRun() {
    var now = 0L
    val failures =
        BinderFailureTracker(
            now = { now },
            maxConsecutiveFailures = 3,
            retryCooldownMs = 100,
            failureRunMs = 1_000,
        )

    repeat(3) { failures.recordFailure(7) }
    now = 100
    assertFalse(failures.isThrottled(7))
    failures.recordFailure(7)

    assertEquals(3, failures.count(7))
    now = 101
    assertTrue(failures.isThrottled(7))
  }
}
