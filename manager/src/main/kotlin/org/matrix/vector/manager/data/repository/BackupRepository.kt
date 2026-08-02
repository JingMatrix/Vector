package org.matrix.vector.manager.data.repository
import android.content.Context
import android.net.Uri
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lsposed.lspd.models.Application
import org.matrix.vector.manager.data.log.archiveBuildStamp
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/**
 * Backup and restore of which modules are on and what each may hook.
 *
 * This is the configuration a user would most hate to rebuild by hand — a dozen modules each with
 * a hand-picked scope — and it is exactly what a bad flash destroys.
 *
 * Deliberately *not* backed up: anything the manager can rediscover. The module APKs themselves
 * belong to the package manager, and a restore proceeds module by module, so one the daemon
 * refuses costs that module alone rather than the whole operation.
 */
class BackupRepository(private val context: Context, private val daemon: DaemonClient) {

    @Serializable
    private data class BackupFile(
        val version: Int = FORMAT_VERSION,
        // Which build wrote this. gzip's own comment field is not reachable through
        // GZIPOutputStream, and this document is ours, so it says so itself -- `zcat file | head`
        // answers "where did this come from" without a restore.
        val build: String = archiveBuildStamp(),
        val createdAt: Long,
        val modules: List<BackupModule>,
    )

    @Serializable
    private data class BackupModule(
        val packageName: String,
        val enabled: Boolean,
        val scope: List<BackupTarget>,
    )

    @Serializable private data class BackupTarget(val packageName: String, val userId: Int)

    private val json = Json { ignoreUnknownKeys = true }

    /** Result of a restore, so the UI can say what actually happened rather than "done". */
    data class RestoreOutcome(val restored: Int, val skipped: Int)

    /**
     * Writes a backup.
     *
     * [only] narrows it to a chosen set of packages, which is what the module list's selection mode
     * hands in; empty means everything currently enabled. The file format is identical either way,
     * so a partial backup restores through exactly the same path as a whole one.
     */
    suspend fun backupTo(uri: Uri, only: Set<String> = emptySet()): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val enabled =
                    daemon.getEnabledModules().getOrThrow().let {
                        if (only.isEmpty()) it else it.filter { pkg -> pkg in only }
                    }
                val modules =
                    enabled.map { packageName ->
                        val scope =
                            daemon
                                .getModuleScope(packageName)
                                .onFailure { e ->
                                    logW(
                                        "backup: scope of $packageName unreadable, saved as empty",
                                        e,
                                    )
                                }
                                .getOrDefault(emptyList())
                                .map { BackupTarget(it.packageName, it.userId) }
                        BackupModule(packageName, enabled = true, scope = scope)
                    }

                val payload =
                    BackupFile(createdAt = System.currentTimeMillis() / 1000, modules = modules)

                // Gzipped: a scope list for a device with hundreds of apps is mostly repeated
                // package prefixes and compresses to a fraction of its size.
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    GZIPOutputStream(out).use { gzip ->
                        gzip.write(json.encodeToString(payload).toByteArray())
                    }
                } ?: error("could not open the chosen file for writing")

                modules.size
            }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    logE(
                        "backup: writing a backup of " +
                            (if (only.isEmpty()) "all enabled" else "${only.size} selected") +
                            " modules failed",
                        e,
                    )
                }
        }

    suspend fun restoreFrom(uri: Uri): Result<RestoreOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text =
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        GZIPInputStream(input).use { it.readBytes().decodeToString() }
                    } ?: error("could not open the chosen file for reading")

                val payload = json.decodeFromString<BackupFile>(text)

                var restored = 0
                var skipped = 0
                payload.modules.forEach { module ->
                    // A refusal is counted and stepped over rather than failing the restore — a
                    // backup is routinely carried between devices. It is not the missing-module
                    // case: the daemon accepts an enable for a package this device does not have,
                    // and drops the row itself on its next cache update.
                    val enabledOk =
                        daemon
                            .setModuleEnabled(module.packageName, module.enabled)
                            .onFailure { e ->
                                logW("restore: enabling ${module.packageName} failed, skipping", e)
                            }
                            .getOrDefault(false)
                    if (!enabledOk) {
                        skipped++
                        return@forEach
                    }
                    if (module.scope.isNotEmpty()) {
                        val scope =
                            module.scope.map { target ->
                                Application().apply {
                                    packageName = target.packageName
                                    userId = target.userId
                                }
                            }
                        val scopeResult = daemon.setModuleScope(module.packageName, scope)
                        if (!scopeResult.getOrDefault(false)) {
                            logE(
                                "restore: scope of ${module.packageName} not applied " +
                                    "(${scope.size} targets)",
                                scopeResult.exceptionOrNull(),
                            )
                        }
                    }
                    restored++
                }
                RestoreOutcome(restored, skipped)
            }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    logE(
                        "restore: reading or parsing the backup file from ${uri.authority} failed",
                        e,
                    )
                }
        }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
