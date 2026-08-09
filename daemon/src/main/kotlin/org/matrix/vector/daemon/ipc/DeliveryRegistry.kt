package org.matrix.vector.daemon.ipc

/**
 * Stores the exact provider/recipient pair associated with a uid.
 *
 * Binder proxies and death recipients are identity-bearing objects. In particular, a late death
 * callback from an old provider must not remove a replacement entry for the same uid. Keeping the
 * identity check here makes that rule explicit and gives it a small, deterministic unit-test
 * surface.
 */
internal class DeliveryRegistry<Provider, Recipient> {

  internal data class Entry<Provider, Recipient>(
      val provider: Provider,
      val recipient: Recipient,
  )

  private val entries = mutableMapOf<Int, Entry<Provider, Recipient>>()

  @Synchronized
  fun put(uid: Int, provider: Provider, recipient: Recipient): Entry<Provider, Recipient>? =
      entries.put(uid, Entry(provider, recipient))

  @Synchronized
  fun isCurrent(uid: Int, provider: Provider, recipient: Recipient): Boolean =
      entries[uid]?.let { it.provider === provider && it.recipient === recipient } == true

  @Synchronized
  fun removeIfCurrent(uid: Int, provider: Provider, recipient: Recipient): Boolean {
    val current = entries[uid] ?: return false
    if (current.provider !== provider || current.recipient !== recipient) return false
    entries.remove(uid)
    return true
  }

  @Synchronized
  fun remove(uid: Int): Entry<Provider, Recipient>? = entries.remove(uid)

  @Synchronized
  fun removeMatching(predicate: (Int) -> Boolean): List<Pair<Int, Entry<Provider, Recipient>>> {
    val removed = mutableListOf<Pair<Int, Entry<Provider, Recipient>>>()
    entries.entries.removeIf { (uid, entry) ->
      if (!predicate(uid)) {
        false
      } else {
        removed += uid to entry
        true
      }
    }
    return removed
  }
}
