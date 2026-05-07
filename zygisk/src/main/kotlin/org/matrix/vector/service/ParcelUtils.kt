package org.matrix.vector.service

import android.os.Parcel
import java.lang.reflect.Method

/**
 * Internal utilities for raw [Parcel] manipulation. Used primarily for IPC transactions that bypass
 * standard AIDL.
 */
object ParcelUtils {

    private val obtainMethod: Method by lazy {
        Parcel::class.java.getDeclaredMethod("obtain", Long::class.java).apply {
            isAccessible = true
        }
    }

    /**
     * Reconstructs a Java [Parcel] object from a native C++ parcel pointer. Required for manual
     * Binder transaction interception in [BridgeService].
     *
     * @param ptr The native pointer address (long).
     * @return A Java Parcel instance wrapping the native pointer, or null if pointer is 0.
     */
    @JvmStatic
    fun fromNativePointer(ptr: Long): Parcel? {
        if (ptr == 0L) return null
        return try {
            obtainMethod.invoke(null, ptr) as? Parcel
        } catch (e: Throwable) {
            throw RuntimeException("Failed to obtain Parcel from native pointer: $ptr", e)
        }
    }
}

/** Extension to allow [Long] native pointers to be treated as Parcels. */
fun Long.asParcel(): Parcel? = ParcelUtils.fromNativePointer(this)
