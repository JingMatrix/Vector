package org.matrix.vector.manager.data.model
import android.util.Log
import org.matrix.vector.manager.Constants

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Everything the manager can learn about a package by looking at it.
 *
 * There are two generations of Xposed module and both have to be recognised. A legacy module
 * announces itself with `xposedminversion` manifest meta-data; a module written against API 100 or
 * later carries no such meta-data, only marker files inside its APK, so a manifest test alone
 * leaves the modern half of the module list empty.
 *
 * The whole inspection is one pass over the APK. Opening the zip is the expensive part, so the API
 * versions, the scope and the description are all read while it is open rather than in a pass each.
 */
data class ModuleManifest(
    val isModule: Boolean = false,
    val isLegacy: Boolean = false,
    /** The Xposed API the module needs, or 0 when it does not say. */
    val minApiVersion: Int = 0,
    val targetApiVersion: Int = 0,
    /** Packages the module asks to hook. */
    val scope: List<String> = emptyList(),
    /**
     * True when [scope] is the whole of it and the user cannot widen it.
     *
     * Never true while [scope] is empty, whatever module.prop says: see [ModuleDetection.inspect].
     */
    val staticScope: Boolean = false,
    /** The module's own description, which the two generations store in different places. */
    val description: String = "",
) {
    /**
     * Either number counts as declaring one.
     *
     * A module may state only `targetApiVersion` — the one the framework loads by — and nothing
     * about it is then undeclared, so testing `minApiVersion` alone would mark it unknown.
     */
    val declaresApiVersion: Boolean
        get() = minApiVersion > 0 || targetApiVersion > 0
}

object ModuleDetection {

    private const val MODERN_ENTRY = "META-INF/xposed/java_init.list"
    private const val SCOPE_ENTRY = "META-INF/xposed/scope.list"
    private const val MODULE_PROP = "META-INF/xposed/module.prop"
    private const val LEGACY_MIN_VERSION = "xposedminversion"
    private const val LEGACY_SCOPE = "xposedscope"
    private const val LEGACY_DESCRIPTION = "xposeddescription"

    /**
     * Reads a package's module metadata, opening each APK at most once.
     *
     * Returns [ModuleManifest] with `isModule = false` for anything that is not a module, so a
     * caller can filter and inspect in a single step.
     */
    fun inspect(info: ApplicationInfo, packageManager: PackageManager): ModuleManifest {
        val legacy = info.metaData?.containsKey(LEGACY_MIN_VERSION) == true

        val apks = buildList {
            info.splitSourceDirs?.let { addAll(it) }
            info.sourceDir?.let { add(it) }
        }

        for (apk in apks) {
            val modern =
                runCatching {
                        ZipFile(apk).use { zip ->
                            if (zip.getEntry(MODERN_ENTRY) == null) return@use null

                            var minApi = 0
                            var targetApi = 0
                            var static = false
                            zip.getEntry(MODULE_PROP)?.let { entry ->
                                // A malformed module.prop must cost these fields, not the whole
                                // module — Properties.load throws on a bad unicode escape.
                                runCatching {
                                        val props =
                                            Properties().apply { load(zip.getInputStream(entry)) }
                                        minApi = props.getProperty("minApiVersion").toIntOrZero()
                                        targetApi =
                                            props.getProperty("targetApiVersion").toIntOrZero()
                                        static = props.getProperty("staticScope") == "true"
                                    }
                                    .onFailure { e ->
                                        Log.w(
                                            Constants.TAG,
                                            "modules: ${info.packageName} module.prop unparsable, " +
                                                "api version and static scope unknown",
                                            e,
                                        )
                                    }
                            }

                            val scope =
                                zip.getEntry(SCOPE_ENTRY)?.let { entry ->
                                    zip.getInputStream(entry)
                                        .bufferedReader()
                                        .readLines()
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                } ?: emptyList()

                            // A module that fixes its scope and then names nothing has fixed it at
                            // "no apps at all": the picker would lock to an empty list with nothing
                            // to tick and no explanation, and the daemon would refuse every write
                            // and prune away the rows the user already has. It is a packaging
                            // mistake — staticScope=true with a scope.list that was never
                            // generated — so the flag is dropped and the scope stays the user's.
                            // FileSystem.readStaticScope ignores the same declaration, so the two
                            // sides agree on what such a module is allowed to hook.
                            if (static && scope.isEmpty()) {
                                Log.w(
                                    Constants.TAG,
                                    "modules: ${info.packageName} fixes its scope but names " +
                                        "nothing; ignoring staticScope and leaving the scope open",
                                )
                                static = false
                            }

                            ModuleManifest(
                                isModule = true,
                                isLegacy = false,
                                minApiVersion = minApi,
                                targetApiVersion = targetApi,
                                scope = scope,
                                staticScope = static,
                                // A modern module uses the ordinary manifest description.
                                description = info.loadDescription(packageManager)?.toString()?.trim().orEmpty(),
                            )
                        }
                    }
                    .onFailure { e ->
                        Log.w(
                            Constants.TAG,
                            "modules: reading ${info.packageName} " +
                                "${apk.substringAfterLast('/')} failed",
                            e,
                        )
                    }
                    .getOrNull()
            if (modern != null) return modern
        }

        if (!legacy) return ModuleManifest(isModule = false)

        return ModuleManifest(
            isModule = true,
            isLegacy = true,
            minApiVersion = legacyMinApiVersion(info),
            scope = legacyScope(info, packageManager),
            staticScope = false,
            description = legacyDescription(info, packageManager),
        )
    }

    /**
     * A legacy module's description lives in `xposeddescription`, not `android:description`.
     *
     * Legacy modules do not set the manifest attribute at all, so reading it for both generations
     * leaves every legacy row blank. The meta-data value is either a literal string or a
     * string-resource id.
     */
    private fun legacyDescription(info: ApplicationInfo, packageManager: PackageManager): String {
        val raw = info.metaData?.get(LEGACY_DESCRIPTION) ?: return ""
        return when (raw) {
            is String -> raw.trim()
            is Int ->
                runCatching {
                        if (raw == 0) ""
                        else packageManager.getResourcesForApplication(info).getString(raw).trim()
                    }
                    .getOrDefault("")
            else -> ""
        }
    }

    /** The `xposedminversion` a legacy module asks for, or 0 when it does not say. */
    private fun legacyMinApiVersion(info: ApplicationInfo): Int {
        val meta = info.metaData ?: return 0
        // Sometimes an int, sometimes a string like "93 (for Android 9)", so leading digits win.
        meta.getInt(LEGACY_MIN_VERSION, -1).let { if (it >= 0) return it }
        val text = meta.getString(LEGACY_MIN_VERSION) ?: return 0
        return text.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    /**
     * A legacy module's `xposedscope`: either a string-array resource id or a `;`-separated list.
     */
    private fun legacyScope(info: ApplicationInfo, packageManager: PackageManager): List<String> {
        val meta = info.metaData ?: return emptyList()
        val raw =
            runCatching {
                    val resourceId = meta.getInt(LEGACY_SCOPE, 0)
                    if (resourceId != 0) {
                        packageManager
                            .getResourcesForApplication(info)
                            .getStringArray(resourceId)
                            .toList()
                    } else {
                        meta.getString(LEGACY_SCOPE)?.split(';')?.map { it.trim() }
                    }
                }
                .onFailure { e ->
                    Log.w(
                        Constants.TAG,
                        "modules: ${info.packageName} legacy xposedscope unreadable",
                        e,
                    )
                }
                .getOrNull()
                ?.filter { it.isNotEmpty() } ?: return emptyList()

        // Legacy modules name the system server the other way round: their "android" is the
        // daemon's "system", and their "system" is the ordinary "android" package. The convention
        // is as old as XposedBridge and universal among legacy modules, so the swap is
        // unconditional.
        return raw.map {
            when (it) {
                "android" -> "system"
                "system" -> "android"
                else -> it
            }
        }
    }

    private fun String?.toIntOrZero(): Int =
        this?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}

/**
 * What a module says it wants to hook.
 *
 * [staticScope] means the module only ever applies to [packages] and the user cannot widen it —
 * the scope editor shows the list read-only rather than pretending the choice is theirs.
 */
data class RecommendedScope(val packages: List<String>, val staticScope: Boolean) {
    val isEmpty: Boolean
        get() = packages.isEmpty()

    companion object {
        val NONE = RecommendedScope(emptyList(), staticScope = false)
    }
}

/** User ids are encoded into the uid; this is AOSP's `UserHandle.PER_USER_RANGE`. */
const val PER_USER_RANGE = 100_000

/** `PackageManager.MATCH_ANY_USER`, which is a hidden constant on the public SDK. */
const val MATCH_ANY_USER = 0x00400000
