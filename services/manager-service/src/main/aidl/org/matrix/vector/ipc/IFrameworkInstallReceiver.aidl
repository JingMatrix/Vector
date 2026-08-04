package org.matrix.vector.ipc;

/**
 * Where a framework flash reports to, one line at a time and then once at the end.
 *
 * <p>Implemented by the manager and handed to {@code IManagerService.installFrameworkZip}, which is
 * the only thing that ever calls it - so it runs in the manager's process, on a daemon thread's
 * initiative. It carries a binder descriptor of its own, but one that can only ever arrive through
 * {@code IManagerService}, so a descriptor that matched there matches here.</p>
 *
 * <p><b>oneway throughout, and must stay so.</b> The daemon must never block on the manager while
 * an installer is running. The manager is a UI process that can be paused, killed or simply slow,
 * and a flash that stalled because nobody read a line would be a flash abandoned halfway through -
 * with the module tree in whatever state the installer had reached. A receiver that has gone away
 * is logged and the flash continues, for the same reason.</p>
 */
oneway interface IFrameworkInstallReceiver {
    /**
     * Nothing was flashed: no usable root implementation.
     *
     * <p>The three sentinels are negative so they cannot be confused with what they share a channel
     * with - a process exit status is 0 to 255, so nothing real ever lands here. They live on this
     * interface rather than on the one that starts the flash because this is the only place they
     * are ever delivered: {@code installFrameworkZip} answers with nothing and never returns
     * one.</p>
     */
    const int INSTALL_NO_ROOT = -1;

    /** The installer binary could not be started at all. */
    const int INSTALL_NOT_EXECUTED = -2;

    /** The zip named by the manager does not exist, or the daemon cannot read it. */
    const int INSTALL_NO_SUCH_FILE = -3;

    /**
     * One line of the installer's output, without its trailing newline.
     *
     * <p>stdout and stderr merged, because an installer sends its diagnostics to one and its
     * progress to the other, and reading them separately would interleave them in an order that is
     * not the order they happened in. When an installer is actually started the first line is the
     * command being run; the paths that refuse before that send a diagnostic instead, followed by
     * the matching {@code INSTALL_*} code.</p>
     *
     * <p>Also written to the daemon's own log as it is sent, so a flash is readable afterwards out
     * of a saved bug report even when nothing was watching at the time.</p>
     */
    void onLine(String line);

    /**
     * The flash is over, and this is the last thing that will be said.
     *
     * @param exitCode the installer process's own status, where 0 is success - or one of the
     *                 {@code INSTALL_*} values above, when there was no process to have a status. A
     *                 reader that does not recognise a value must assume it is an exit status and
     *                 show the number, rather than treat it as a failure it can name
     */
    void onFinished(int exitCode);
}
