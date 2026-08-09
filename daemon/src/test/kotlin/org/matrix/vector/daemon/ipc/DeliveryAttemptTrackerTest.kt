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

  @Test
  fun staleCompletionAfterClearCannotReleaseReplacementOwnership() {
    val tracker = DeliveryAttemptTracker()
    val first = tracker.begin(7) ?: error("first attempt was not created")

    tracker.clear()
    val replacement = tracker.begin(7) ?: error("replacement attempt was not created")

    tracker.finish(7, first)

    assertTrue(tracker.isCurrent(7, replacement))
  }

  @Test
  fun scopedInvalidationLeavesOtherModuleAttemptCurrent() {
    val tracker = DeliveryAttemptTracker()
    val changedModule = tracker.begin(10042) ?: error("changed-module attempt was not created")
    val otherModule = tracker.begin(20043) ?: error("other-module attempt was not created")

    tracker.invalidateMatching { it == 10042 }

    assertFalse(tracker.isCurrent(10042, changedModule))
    assertTrue(tracker.isCurrent(20043, otherModule))
  }

  @Test
  fun queuedObsoleteAttemptDoesNotStartProviderLookup() {
    val tracker = DeliveryAttemptTracker()
    val attempt = tracker.begin(42) ?: error("attempt was not created")
    tracker.invalidate(42)

    var lookupStarted = false
    if (tracker.isCurrent(42, attempt)) lookupStarted = true

    assertFalse(lookupStarted)
  }
}
