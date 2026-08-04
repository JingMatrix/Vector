package org.matrix.vector.ipc;

import org.matrix.vector.ipc.IFrameworkService;

/**
 * The daemon's front door, reached from a newly specialized process through the zygisk bridge.
 *
 * <p>This binder is pushed into a process by the daemon rather than looked up: the zygisk native
 * module intercepts {@code Binder.execTransact}, and the daemon transacts a magic code on any
 * binder to hand it over. Nothing here is registered in servicemanager.</p>
 *
 * <p><b>Transaction ids are implicit, as in {@link IFrameworkService}, and for the same reason.</b>
 * The daemon and the zygisk bridge that calls this ship in one zip, so they are always the same
 * build and nothing has to survive a peer of a different revision. A new method must still be
 * appended: inserting one anywhere above renumbers every method after it. Contrast
 * {@code IManagerService}, whose ids are explicit because the manager APK can be installed
 * separately and outlive a flash.</p>
 */
interface IVectorDaemon {
    /**
     * Announces a process to the daemon and asks for its framework service.
     *
     * <p>Null is the ordinary answer and covers four cases the caller cannot tell apart, because
     * there is nothing it could do differently about any of them: the call did not come from
     * uid 1000 and was refused; this (uid, pid) has already attached; no module is in scope for
     * this process, which is most of them; or the registration itself failed. A process that is
     * answered null simply carries on unhooked.</p>
     *
     * @param uid            the process uid, as the daemon will re-derive it from the binder call
     * @param pid            the process id
     * @param processName    the Android process name, which is what module scope is keyed on
     * @param processLifeToken a bare Binder owned by the calling process, used for nothing but
     *                       {@code linkToDeath} - it is how the daemon learns the process is gone,
     *                       and how a hot reload tells a dead target from an unreachable one. The
     *                       caller must keep a strong reference to it (the native side takes a JNI
     *                       global ref); letting it be collected looks exactly like dying.
     */
    @nullable IFrameworkService attachProcess(int uid, int pid, String processName,
                                              IBinder processLifeToken);

    /** Gives the daemon system_server's ActivityThread and activity token, once they exist. */
    oneway void dispatchSystemServerContext(in IBinder activityThread, in IBinder activityToken);

    /** Asks the daemon to bring the manager up before it is needed. */
    boolean preStartManager();
}
