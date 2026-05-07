package org.matrix.vector.impl

import android.content.SharedPreferences
import android.os.Bundle
import android.os.RemoteException
import android.util.ArraySet
import io.github.libxposed.api.error.XposedFrameworkError
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.service.IRemotePreferenceCallback
import org.lsposed.lspd.util.Utils.Log

@Suppress("DEPRECATION", "UNCHECKED_CAST")
private inline fun <reified T> Bundle.getSerializableCompat(key: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getSerializable(key, java.io.Serializable::class.java) as? T
    } else {
        getSerializable(key) as? T
    }
}

@Suppress("UNCHECKED_CAST")
internal class VectorRemotePreferences(service: ILSPInjectedModuleService, group: String) :
    SharedPreferences {

    private val map = ConcurrentHashMap<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    private val callback =
        object : IRemotePreferenceCallback.Stub() {
            @Synchronized
            override fun onUpdate(bundle: Bundle) {
                val changes = ArraySet<String>()

                if (bundle.containsKey("delete")) {
                    val deletes =
                        bundle.getSerializableCompat<Set<String>>("delete") as? Set<String>
                    if (deletes != null) {
                        changes.addAll(deletes)
                        for (key in deletes) {
                            map.remove(key)
                        }
                    }
                }

                if (bundle.containsKey("put")) {
                    val puts =
                        bundle.getSerializableCompat<Map<String, Any>>("put") as? Map<String, Any>
                    if (puts != null) {
                        map.putAll(puts)
                        changes.addAll(puts.keys)
                    }
                }

                synchronized(listeners) {
                    for (key in changes) {
                        listeners.forEach { listener ->
                            listener.onSharedPreferenceChanged(this@VectorRemotePreferences, key)
                        }
                    }
                }
            }
        }

    init {
        try {
            val output = service.requestRemotePreferences(group, callback)
            if (output.containsKey("map")) {
                val initialMap =
                    output.getSerializableCompat<Map<String, Any>>("map") as? Map<String, Any>
                if (initialMap != null) {
                    map.putAll(initialMap)
                }
            }
        } catch (e: RemoteException) {
            Log.e("VectorContext", "Failed to request remote preferences for group: $group", e)
            throw XposedFrameworkError("Remote preferences IPC failure", e)
        }
    }

    override fun getAll(): Map<String, *> = TreeMap(map)

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        map[key] as? Set<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor =
        throw UnsupportedOperationException("Read only implementation")

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners.remove(listener) }
    }
}
