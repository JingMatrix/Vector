package org.matrix.vector.daemon.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryAttemptTrackerTest {

  @Test
  fun duplicateStartsDoNotInvalidateTheActiveAttempt() {
    val tracker = DeliveryAttemptTracker()
    val first = tracker.begin(42) ?: error("first attempt was not created")

    assertNull(tracker.begin(42))
    assertTrue(tracker.isCurrent(42, first))
  }

  @Test
  fun uidGoneInvalidatesOldWorkAndKeepsReplacementOwnership() {
    val tracker = DeliveryAttemptTracker()
    val first = tracker.begin(42) ?: error("first attempt was not created")

    tracker.invalidate(42)
    val replacement = tracker.begin(42) ?: error("replacement attempt was not created")

    assertNotEquals(first, replacement)
    assertFalse(tracker.isCurrent(42, first))
    assertTrue(tracker.isCurrent(42, replacement))

    tracker.finish(42, first)
    assertTrue(tracker.isCurrent(42, replacement))
  }

  @Test
  fun clearInvalidatesEveryOldAttempt() {
    val tracker = DeliveryAttemptTracker()
    val first = tracker.begin(1) ?: error("first attempt was not created")
    val second = tracker.begin(2) ?: error("second attempt was not created")

    tracker.clear()

    assertFalse(tracker.isCurrent(1, first))
    assertFalse(tracker.isCurrent(2, second))
    val replacement = tracker.begin(1) ?: error("replacement attempt was not created")
    assertTrue(tracker.isCurrent(1, replacement))
  }

  @Test
  fun invalidatedFailuresStillCountButInvalidatedSuccessesDoNotCommit() {
    val tracker = DeliveryAttemptTracker()
    val attempt = tracker.begin(42) ?: error("attempt was not created")
    var failures = 0

    tracker.invalidate(42)

    assertEquals(
        DeliveryCompletion.RECORD_FAILURE,
        tracker.complete(
            uid = 42,
            attempt = attempt,
            delivered = false,
            clearFailures = {},
            recordFailure = { failures++ },
        ),
    )
    assertEquals(1, failures)
    assertEquals(
        DeliveryCompletion.IGNORE_STALE,
        tracker.complete(
            uid = 42,
            attempt = attempt,
            delivered = true,
            clearFailures = {},
            recordFailure = { failures++ },
        ),
    )
    assertEquals(1, failures)
  }

  @Test
  fun staleFailureCannotOverwriteNewerSuccessOrCacheClear() {
    val tracker = DeliveryAttemptTracker()
    val first = tracker.begin(42) ?: error("first attempt was not created")
    tracker.invalidate(42)
    val replacement = tracker.begin(42) ?: error("replacement attempt was not created")
    var failures = 0
    var clears = 0

    assertEquals(
        DeliveryCompletion.COMMIT,
        tracker.complete(
            uid = 42,
            attempt = replacement,
            delivered = true,
            clearFailures = { clears++ },
            recordFailure = { failures++ },
        ),
    )
    assertEquals(
        DeliveryCompletion.IGNORE_STALE,
        tracker.complete(
            uid = 42,
            attempt = first,
            delivered = false,
            clearFailures = { clears++ },
            recordFailure = { failures++ },
        ),
    )

    val beforeClear = tracker.begin(7) ?: error("attempt before clear was not created")
    tracker.clear()
    assertEquals(
        DeliveryCompletion.IGNORE_STALE,
        tracker.complete(
            uid = 7,
            attempt = beforeClear,
            delivered = false,
            clearFailures = { clears++ },
            recordFailure = { failures++ },
        ),
    )
    assertEquals(1, clears)
    assertEquals(0, failures)
  }
}
