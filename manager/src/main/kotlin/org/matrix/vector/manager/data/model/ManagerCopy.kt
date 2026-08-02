package org.matrix.vector.manager.data.model

/**
 * Which copy of the manager, if any, is installed beside the one that is running.
 *
 * Parasitically the manager is not a package at all — it is loaded into the host from the daemon's
 * own APK — so installing it as an app puts a second copy of that code on the device. The two are
 * meant to be the same build and nothing on the device keeps them so: an install is a moment, and
 * the module behind it is reflashed whenever the framework is updated.
 *
 * The version code cannot tell them apart when they drift. It is `git rev-list --count
 * origin/master`, so a branch build and the official build at the same depth carry the same number,
 * and a copy installed from either reports the number the other would. Only the bytes settle it,
 * which is why the check that produces this reaches for a digest whenever the numbers agree.
 */
enum class ManagerCopy {

    /** No such package on this device. Installing it is the whole of the offer. */
    Absent,

    /**
     * Installed, and nothing has shown it to be a different build.
     *
     * This is also where "could not tell" lands — a daemon that will not hand over its APK, an
     * install whose file could not be read — because a check that did not complete has found no
     * difference, and a failed check reported as a wrong build would send the reader to replace a
     * copy that is very probably fine. The card then reads exactly as it did before there was
     * anything to compare, which is the state this state is deliberately indistinguishable from.
     */
    Present,

    /**
     * Installed, and it is a different build of Vector: another version code, or the same code over
     * different bytes.
     */
    Diverged;

    /**
     * Whether the launcher has a Vector icon to open, whichever build is behind it.
     *
     * A diverged copy is still a way back in — an older or a branch build of the same manager opens
     * and talks to the same daemon — so anything asking "can this device reach the manager" must
     * count it, and only the offer to install reads the difference.
     */
    val installed: Boolean
        get() = this != Absent
}
