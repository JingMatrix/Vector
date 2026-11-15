package org.matrix.vector.ipc;

import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.SettingsShortcutInfo;

/**
 * What an injected process asks the framework for, once the zygisk handshake has given it one.
 *
 * <p>Every call that answers with something about the caller - its modules, its preference path, the
 * manager - is authenticated by {@code Binder.getCallingUid()/getCallingPid()} against the process
 * registry the handshake built, so a caller can only ever act as itself. {@link #isLogMuted} is the
 * exception and is deliberately unauthenticated: it discloses one global boolean about log
 * verbosity, and it is asked before a process has anything to be authenticated as.</p>
 *
 * <p><b>Transaction ids are implicit in this file.</b> A new method must be appended; inserting one
 * anywhere above renumbers every method after it, and the daemon and the injected processes are
 * only guaranteed to agree because they ship in the same zip.</p>
 */
interface IFrameworkService {
    /** Whether the user has asked the framework to keep quiet in the log. */
    boolean isLogMuted();

    /** The legacy (de.robv) modules in scope for this process. */
    List<LoadedModule> getLegacyModules();

    /**
     * The libxposed modules in scope for this process.
     *
     * <p>Answering this is also what makes the calling process a hot reload target for each module
     * returned: the daemon knows it served module M to process P, which is the whole of what
     * {@code getRunningTargets()} needs, so nothing has to be reported back afterwards.</p>
     *
     * <p>That is deliberate rather than incidental. Deriving targets from a registration call made
     * by the injected process is what made system_server unreachable in the first attempt at hot
     * reload: system_server loads its modules before the daemon's module cache exists, so any
     * registration that had to look a module up there failed and was swallowed.</p>
     */
    List<LoadedModule> getModules();

    /** Where this process should look for a module's XSharedPreferences files. */
    String getPrefsPath(String packageName);

    /**
     * The manager APK, opened read-only once its signature has been verified against the one this
     * framework was built with. Null when it is missing or does not verify.
     */
    @nullable ParcelFileDescriptor openManagerApk();

    /**
     * The manager's service binder, if this process is the one hosting the manager.
     *
     * <p>Null for every other process, which is nearly all of them. This only reports a decision
     * already taken - the daemon recorded which pid it was launching the manager into when it
     * started it, and this compares the caller against that - so asking is free and a process that
     * asks and then does nothing has cost nothing.</p>
     */
    @nullable IBinder requestManagerService();

    /**
     * Hands the daemon the channel it needs to call back into this process.
     *
     * <p>Called once while the framework bootstraps, before any module is loaded, and carrying no
     * module identity at all - so it cannot depend on the daemon's module cache being populated.
     * That dependency is exactly what stopped system_server becoming a hot reload target before.
     * The daemon files the channel against the (uid, pid) it authenticated, so a process can only
     * ever attach its own.</p>
     *
     * <p><b>Not oneway, and must not become oneway.</b> The binder driver only records the sending
     * thread for synchronous transactions - {@code binder_transaction()} sets {@code t->from} only
     * when {@code TF_ONE_WAY} is clear - so an async call arrives with the caller's euid but a
     * {@code getCallingPid()} of <b>0</b>. The daemon keys its process registry on (uid, pid), so
     * making this oneway makes every attach fail authentication, and the only symptom is that
     * every later hot reload answers UNSUPPORTED with "no hot reload entry point".</p>
     */
    void attachProcessChannel(IProcessChannel channel);

    /**
     * Returns the configured shortcut state. Only the registered Settings process for user 0
     * may call this. A missing reply does not give permission to remove a shortcut.
     */
    @nullable SettingsShortcutInfo getSettingsShortcut();
}
