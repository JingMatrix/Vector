package org.matrix.vector.daemon.ipc

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
}
