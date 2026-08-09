package org.matrix.vector.daemon.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryRegistryTest {

  @Test
  fun lateDeathOfOldProviderCannotRemoveReplacement() {
    val registry = DeliveryRegistry<Any, Any>()
    val oldProvider = Any()
    val oldRecipient = Any()
    val newProvider = Any()
    val newRecipient = Any()

    registry.put(42, oldProvider, oldRecipient)
    registry.put(42, newProvider, newRecipient)

    assertFalse(registry.removeIfCurrent(42, oldProvider, oldRecipient))
    assertTrue(registry.isCurrent(42, newProvider, newRecipient))
    assertNotNull(registry.remove(42))
  }

  @Test
  fun scopedRemovalDoesNotTouchOtherUids() {
    val registry = DeliveryRegistry<Any, Any>()
    val provider = Any()
    val recipient = Any()
    registry.put(10042, provider, recipient)
    registry.put(20043, provider, recipient)

    val removed = registry.removeMatching { it == 10042 }

    assertTrue(removed.any { it.first == 10042 })
    assertFalse(registry.isCurrent(10042, provider, recipient))
    assertTrue(registry.isCurrent(20043, provider, recipient))
  }
}
