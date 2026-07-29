package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/**
 * The single source of truth for which modules are enabled.
 *
 * It observes the binder rather than fetching once at construction. This is built before any daemon
 * connection exists, so a single fetch from `init` would run too early, fail, and have nothing to
 * retry it — the enabled set would stay empty for the life of the process.
 */
class ModuleRepository(
    private val daemonClient: DaemonClient,
    private val scope: CoroutineScope,
) {

    private val _enabledModulesState = MutableStateFlow<Set<String>>(emptySet())
    val enabledModulesState: StateFlow<Set<String>> = _enabledModulesState.asStateFlow()

    private val _scopeRevision = MutableStateFlow(0)

    /**
     * Bumped whenever a module's scope has been written.
     *
     * The scope editor is a separate screen with its own view model, so the list behind it has no
     * other way to learn that the thing it depicts has just been edited: without this, applying a
     * scope and pressing back leaves the row showing the old set of app icons until a manual pull
     * to refresh.
     */
    val scopeRevision: StateFlow<Int> = _scopeRevision.asStateFlow()

    /** Called once a scope write has gone through, not when one is started. */
    fun noteScopeChanged() {
        _scopeRevision.update { it + 1 }
    }

    private val _packageRevision = MutableStateFlow(0)

    /**
     * Bumped when a package is installed, updated or removed.
     *
     * The module list is cached across visits, which is what makes it fast — and what would make it
     * wrong, because a list that is never recomputed never notices a module being installed. The
     * platform's own package broadcasts feed this, so the rescan happens when something has changed
     * rather than on every visit. The scan it triggers is cheap: the detection cache is keyed by
     * version code and install time, so only the package that actually changed is opened again.
     */
    val packageRevision: StateFlow<Int> = _packageRevision.asStateFlow()

    fun notePackagesChanged() {
        _packageRevision.update { it + 1 }
    }

    init {
        scope.launch {
            // Re-reads whenever a binder arrives, including a reconnect.
            ServiceLocator.service.collect { service ->
                if (service == null) _enabledModulesState.update { emptySet() } else refresh()
            }
        }
    }

    fun refresh() {
        scope.launch {
            daemonClient
                .getEnabledModules()
                .onSuccess { enabled -> _enabledModulesState.update { enabled.toSet() } }
                .onFailure { e ->
                    logW("modules: enabled list unavailable, showing none enabled", e)
                }
        }
    }

    /**
     * Asks the daemon to enable or disable a module, and reports whether it agreed.
     *
     * The local set is only updated when the daemon confirms, so the switch can never show a state
     * the framework does not actually hold. Callers surface the `false` — silently snapping the
     * control back leaves the user with no idea what happened.
     */
    suspend fun toggleModule(packageName: String, enable: Boolean): Boolean {
        val verb = if (enable) "enable" else "disable"
        val result = daemonClient.setModuleEnabled(packageName, enable)
        val accepted = result.getOrDefault(false)
        if (!accepted) {
            val cause = result.exceptionOrNull()
            if (cause != null) {
                logE("modules: $verb of $packageName failed", cause)
            } else {
                logE("modules: daemon refused to $verb $packageName")
            }
            return false
        }

        _enabledModulesState.update { current ->
            if (enable) current + packageName else current - packageName
        }
        return true
    }
}
