package org.matrix.vector.ipc;

/**
 * One Android user or profile on this device, reduced to what the manager displays.
 *
 * <p>Named to say which side of the boundary it is on. It was called {@code UserInfo}, which is
 * also the name of the platform's hidden {@code android.content.pm.UserInfo} that the daemon
 * converts <i>from</i> - the conversion has both types in scope at once, told apart by nothing but
 * an import line, and the manager had to write this one out fully qualified wherever it appeared.
 * </p>
 *
 * <p>Two fields, deliberately. The platform type also carries flags, a creation time, a profile
 * group and an icon path, none of which the manager reads and all of which would then have to be
 * kept in step with whatever the platform does to them next.</p>
 */
parcelable DeviceUser {
    /**
     * The user id, as everything about scope and package visibility is keyed on.
     *
     * <p>0 is the device owner. Not contiguous and not bounded by the number of users: profiles get
     * their own ids, and a device that has had one removed leaves a gap.</p>
     */
    int id;

    /**
     * What the platform calls this user, shown as-is.
     *
     * <p>Whatever the user or the manufacturer named it, so it is display text and nothing may be
     * parsed out of it. Neither unique nor stable - two profiles may carry one name, and a user can
     * be renamed. {@link #id} is the identity.</p>
     */
    String name;
}
