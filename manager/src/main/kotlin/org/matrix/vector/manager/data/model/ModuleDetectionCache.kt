package org.matrix.vector.manager.data.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Base64
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.manager.logW

/**
 * Remembers which installed packages are modules, so the answer is computed once per APK.
 *
 * Deciding whether a package is a module means opening its APK — and its splits — as a zip and
 * looking for a marker entry. On the device this was written against that is 363 packages and 193
 * split APKs, roughly 550 zip opens, and uncached the Modules panel pays it in full on every visit
 * rather than only the first.
 *
 * The answer only changes when the APK does, so it is keyed by the package's version code and
 * install time. A package that has been updated re-inspects itself; everything else is a map
 * lookup. That makes the expensive scan a one-off after an install or an update rather than a
 * per-visit cost, and it is why the cache is persistent: a cold start would otherwise pay the full
 * 550 again.
 *
 * It lives in the cache directory on purpose. Losing it costs one slow scan and nothing else, so it
 * is never a source of truth and never needs migrating.
 */
/**
 * What separates one scope entry from the next inside a field.
 *
 * NUL, because a package name cannot contain one and the scope list is Base64'd into a
 * tab-separated record where a space or a comma would be ambiguous. Written as an escape rather
 * than as the byte itself: a raw NUL in source is invisible in an editor, invisible to grep, and
 * one whitespace-normalising tool away from silently changing the file format.
 */
private const val SCOPE_SEPARATOR = "\u0000"

class ModuleDetectionCache(private val file: File) {

    private data class Key(val packageName: String, val versionCode: Long, val updatedAt: Long)

    private val entries = ConcurrentHashMap<Key, ModuleManifest>()

    /** The keys this process has asked about, whether or not the answer had to be computed. */
    private val touched = ConcurrentHashMap.newKeySet<Key>()

    @Volatile private var loaded = false

    @Volatile private var dirty = false

    /** How many packages this run actually had to open, for the scan's own log line. */
    @Volatile var inspectedThisRun = 0
        private set

    /**
     * The manifest for [info], from the cache when the APK has not changed.
     *
     * [versionCode] and [updatedAt] come from the PackageInfo the caller already holds — asking the
     * package manager again here would reintroduce a per-package cost to avoid a per-package cost.
     */
    fun inspect(
        info: ApplicationInfo,
        packageManager: PackageManager,
        versionCode: Long,
        updatedAt: Long,
    ): ModuleManifest {
        load()
        val key = Key(info.packageName, versionCode, updatedAt)
        // Recorded before the early return: a package answered from the cache must still count as
        // touched, or [flush] would throw it away for having been cheap.
        touched += key
        entries[key]?.let {
            return it
        }
        val manifest = ModuleDetection.inspect(info, packageManager)
        inspectedThisRun++
        entries[key] = manifest
        dirty = true
        return manifest
    }

    /**
     * Writes the cache out, dropping every key this run did not look at.
     *
     * Called once at the end of a scan rather than per entry: the scan is the only writer, and
     * rewriting the file 363 times would cost more than the zip opens it is saving.
     */
    @Synchronized
    fun flush(seen: Set<String>) {
        if (!dirty) return
        // Retained by key rather than by package name, which is what drops the stale entry an
        // update leaves behind: both keys name the same package and only the newer one was
        // touched. [seen] is still needed, because [touched] accumulates for the life of the
        // process — after an uninstall the caller's set is the only one that knows the package is
        // gone.
        entries.keys.retainAll { it in touched && it.packageName in seen }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                entries.entries.joinToString("\n") { (key, manifest) ->
                    listOf(
                            key.packageName,
                            key.versionCode.toString(),
                            key.updatedAt.toString(),
                            if (manifest.isModule) "1" else "0",
                            if (manifest.isLegacy) "1" else "0",
                            manifest.minApiVersion.toString(),
                            manifest.targetApiVersion.toString(),
                            if (manifest.staticScope) "1" else "0",
                            encode(manifest.scope.joinToString(SCOPE_SEPARATOR)),
                            encode(manifest.description),
                        )
                        .joinToString("\t")
                }
            )
        }
            .onFailure { e -> logW("modules: detection cache write failed", e) }
        dirty = false
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return@runCatching
            file.forEachLine { line ->
                val f = line.split('\t')
                if (f.size != 10) return@forEachLine
                entries[Key(f[0], f[1].toLong(), f[2].toLong())] =
                    ModuleManifest(
                        isModule = f[3] == "1",
                        isLegacy = f[4] == "1",
                        minApiVersion = f[5].toInt(),
                        targetApiVersion = f[6].toInt(),
                        staticScope = f[7] == "1",
                        scope = decode(f[8]).split(SCOPE_SEPARATOR).filter { it.isNotEmpty() },
                        description = decode(f[9]),
                    )
            }
        }
    }

    // Descriptions carry newlines and tabs of their own, which would otherwise end the record.
    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)

    private fun decode(value: String): String =
        runCatching { String(Base64.decode(value, Base64.NO_WRAP)) }.getOrDefault("")
}
