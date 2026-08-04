package org.matrix.vector.ipc;

/**
 * One line of a module's scope: an app the module is to be loaded into, and whose copy of it.
 *
 * <p>Named for what it is. It was called {@code Application}, which says nothing about scope and
 * collides with {@code android.app.Application} - both callers that handled a list of these had to
 * write the type out fully qualified to say which one they meant, and both then mirrored it into a
 * local class of the same shape under a name that did say.</p>
 *
 * <p>A structured parcelable, so it arrives as a bean with no equality of its own. A caller doing
 * set arithmetic over scopes still needs a value type to do it with, and keeps one.</p>
 */
parcelable ScopeEntry {
    /**
     * The app to load the module into.
     *
     * <p>{@code system} is not a package but the system framework, and is the one target that
     * belongs to no user.</p>
     */
    String packageName;

    /**
     * Which installed copy of {@link #packageName} is meant.
     *
     * <p>The <i>target's</i> user, not the module's: a module is one package, one APK and one scope
     * set for the whole device, because Android cannot hold two different builds under one package
     * name. The daemon refuses to expand a row whose user does not hold the module, which is what
     * keeps a module installed for one user out of another user's processes.</p>
     *
     * <p>Stored as 0 whatever is written here when {@link #packageName} is the system framework:
     * there is one system_server for the whole device, so a module in a work profile hooking the
     * framework is hooking the same process as everyone else. Normalised rather than refused -
     * refusing silently lost the one target a module may have cared about when a backup written by
     * an older manager, which recorded the framework under the module's own user, was restored.</p>
     */
    int userId;
}
