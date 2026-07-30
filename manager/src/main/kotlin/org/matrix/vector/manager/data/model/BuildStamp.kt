package org.matrix.vector.manager.data.model

/**
 * A build stamp, taken apart: which commit a build came from, and where it was built.
 *
 * The framework and the manager both report one — `BuildConfig.VERSION_HASH`, and
 * `getFrameworkCommit()` across the binder — because the version code cannot tell two builds apart:
 * it is the commit count on origin/master, so every branch build at the same depth wears the same
 * number as the official build it was never made from.
 *
 * Only the commit is taken out. Where a build was made is for a person to read, and it is exactly
 * the rest of the string — a caller that wants it takes what follows the commit, separator and all.
 *
 * @property commit the commit the build was made from, abbreviated as git abbreviated it, or null
 *   when the stamp names none — "unknown" from a build made outside a git checkout, and anything
 *   else this does not recognise. That is a third answer, distinct from "these differ".
 * @property modified whether the tree had uncommitted changes. Such a build was made from no commit
 *   at all: [commit] names the one it departed from, not one the binary corresponds to.
 */
data class BuildStamp(val commit: String?, val modified: Boolean) {

    /**
     * Whether this build and [other] were made from the same commit.
     *
     * A modified tree matches nothing, including itself. Otherwise it is a prefix test in either
     * direction, because the two sides need not be abbreviated to the same length: a stamp carries
     * git's short form and a GitHub release carries the full SHA.
     *
     * **Where the build was made is deliberately not compared.** A fork building the same commit
     * builds the same code; the repository is in the stamp to identify a *binary* to someone
     * reading a bug report, which is a different question from "is this the build that release
     * published".
     */
    fun isCommit(other: String?): Boolean {
        if (modified || other == null) return false
        val ours = commit ?: return false
        return other.startsWith(ours) || ours.startsWith(other)
    }
}

/**
 * Reads a build stamp.
 *
 * The commit leads, always, and the separator says what follows it — `-` a repository that holds
 * this exact commit, `+` a machine that holds changes no repository does. So the commit is the head
 * and there is nothing to guess: neither character can occur inside a host name or an `owner/repo`,
 * and a hyphen inside either is harmless because everything after the first one is the origin.
 *
 * - `93d66473` — a local build of a clean tree.
 * - `93d66473-JingMatrix-Vector` — CI, from `JingMatrix/Vector`.
 * - `93d66473+thinkpad` — a local build with uncommitted changes, named by the machine that made it.
 *
 * A stamp that does not start with a commit yields a null one, which everywhere it is asked means
 * "I cannot tell" rather than "these differ". That covers the shape shipped between #809 and this
 * change, `JingMatrix-Vector-93d66473`, which is what the canaries already on people's devices
 * carry: they are shown as they were recorded and claimed to be neither installed nor divergent,
 * which is the truthful answer for a build whose stamp this cannot read.
 */
fun buildStamp(reported: String): BuildStamp {
    val stamp = reported.trim()
    val head = stamp.takeWhile { it != '-' && it != '+' }
    if (!head.isAbbreviatedSha()) return BuildStamp(null, modified = false)
    return BuildStamp(commit = head, modified = stamp.getOrNull(head.length) == '+')
}

/**
 * Whether this could be a commit as git abbreviates one.
 *
 * Seven is git's own floor for an abbreviation and forty is a full SHA. Lower case only, which git
 * emits and which is what keeps the word "unknown" — a build made where git could not be asked —
 * from being mistaken for one.
 */
private fun String.isAbbreviatedSha(): Boolean =
    length in 7..40 && all { it.isDigit() || it in 'a'..'f' }
