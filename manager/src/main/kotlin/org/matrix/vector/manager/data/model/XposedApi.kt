package org.matrix.vector.manager.data.model

/**
 * Which API a module was built against, and whether this framework still honours it.
 *
 * Two scales share one number, which is the source of most of the confusion here. Anything below
 * [LIBXPOSED_FLOOR] is a *legacy Xposed* API version — 54, 82, 93 — and anything at or above it is
 * a *libxposed* one. They are not comparable: a module declaring 93 is not "eight behind" a
 * framework declaring 101, it is on the other scale entirely. The app therefore names the scale
 * wherever it states a number, rather than saying "API 101" and leaving the reader to know which
 * API is meant.
 */
object XposedApi {

    /** Where the modern scale begins. Below this is legacy Xposed, and always supported. */
    const val LIBXPOSED_FLOOR = 100

    /**
     * libxposed versions that changed the interface incompatibly.
     *
     * A version listed here means: everything built against a version *below* it may not work on a
     * framework at or above it. So `101` in this list is a statement about **100** — modules built
     * against libxposed API 100 are not reliably supported once the framework implements 101.
     *
     * Legacy Xposed is deliberately absent. That interface stopped changing long ago and the
     * framework still implements all of it, so a legacy module's declared version says how old it
     * is and nothing about whether it works.
     *
     * Add a version here when a release breaks what came before it. [brokenSince] is the only
     * reader, and the warning the module list shows derives from what it returns.
     */
    val BREAKING = listOf(101)

    /** True for the modern scale, where a version number is a libxposed version. */
    fun isLibxposed(api: Int): Boolean = api >= LIBXPOSED_FLOOR

    /**
     * The break that a module has fallen behind, or null if it has not.
     *
     * Returns the *breaking version* rather than a boolean, because that number is the whole
     * explanation: "built for 100, and 101 changed it" says what went wrong and when, where "may
     * be incompatible" says only that someone is worried.
     *
     * Only applies within the modern scale, and only when the framework is actually past the
     * break — a module built for 100 running on a framework that also implements 100 is fine, and
     * saying otherwise would warn every user of every module about a future they are not in.
     */
    fun brokenSince(moduleApi: Int, frameworkApi: Int): Int? {
        if (!isLibxposed(moduleApi) || !isLibxposed(frameworkApi)) return null
        return BREAKING.firstOrNull { breaking -> moduleApi < breaking && breaking <= frameworkApi }
    }
}
