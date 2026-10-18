package org.matrix.vector.impl.utils

import android.os.SharedMemory
import java.nio.ByteBuffer

/** Closes this process's received DEX handles, including handles never mapped by a loader. */
internal fun closeDexHandles(dexes: List<SharedMemory>?): Throwable? {
    var failure: Throwable? = null
    dexes?.forEach { dex ->
        try {
            dex.close()
        } catch (t: Throwable) {
            if (failure == null) failure = t else if (failure !== t) failure.addSuppressed(t)
        }
    }
    return failure
}

/**
 * Owns only the input mappings and handles. The loader retains its own ART-owned DEX memory. A
 * missing DEX fails the complete load; never construct a silently incomplete generation.
 */
internal fun <T> withConsumedDexBuffers(
    dexes: List<SharedMemory>,
    load: (Array<ByteBuffer>) -> T,
): T {
    val buffers = ArrayList<ByteBuffer>()
    var failure: Throwable? = null
    try {
        for (dex in dexes) {
            val buffer = dex.mapReadOnly()
            try {
                buffers.add(buffer)
            } catch (t: Throwable) {
                // Also cover an allocation failure while recording this successful mapping.
                try {
                    SharedMemory.unmap(buffer)
                } catch (cleanup: Throwable) {
                    if (cleanup !== t) t.addSuppressed(cleanup)
                }
                throw t
            }
        }
        return load(buffers.toTypedArray())
    } catch (t: Throwable) {
        failure = t
        throw t
    } finally {
        var cleanupFailure: Throwable? = null
        for (buffer in buffers) {
            try {
                SharedMemory.unmap(buffer)
            } catch (t: Throwable) {
                if (cleanupFailure == null) cleanupFailure = t
                else if (cleanupFailure !== t) cleanupFailure.addSuppressed(t)
            }
        }
        buffers.clear()
        val closeFailure = closeDexHandles(dexes)
        if (closeFailure != null) {
            if (cleanupFailure == null) cleanupFailure = closeFailure
            else if (cleanupFailure !== closeFailure) cleanupFailure.addSuppressed(closeFailure)
        }
        if (cleanupFailure != null) {
            if (failure == null) throw cleanupFailure
            if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
        }
    }
}
