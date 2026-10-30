package org.matrix.vector.ipc;

import rikka.parcelablelist.ParcelableListSlice;

import org.matrix.vector.ipc.DeviceUser;
import org.matrix.vector.ipc.IFrameworkInstallReceiver;
import org.matrix.vector.ipc.ModuleLoadFailure;
import org.matrix.vector.ipc.ScopeEntry;

/**
 * What the manager app asks the daemon for, once the framework has pushed it a binder.
 *
 * <p>Runs in the daemon, as root. Everything here is here because the manager cannot do it for
 * itself: it is either the framework's own configuration and state, which nothing else holds, or a
 * call into a system service that an ordinary app is not allowed to make.</p>
 *
 * <p><b>Authenticated once, by possession.</b> This binder is registered nowhere.
 * {@code IFrameworkService.requestManagerService} answers with it only for the pid the daemon
 * launched the manager into, or for the uid of the installed manager package, and the injected
 * framework then pushes it into that process by reflection. No method below re-checks its caller,
 * and none needs to - the decision was taken when the binder was handed over. The consequence is
 * that a process holding this binder holds the daemon's authority over the whole device, so it must
 * never be published to servicemanager and never passed on.</p>
 *
 * <p><b>The fully qualified name of this interface is its binder descriptor, and the two ends can
 * be different builds.</b> {@link #getManagerApk} exists so the manager can be installed as an
 * ordinary app, and an installed copy survives every later flash of the framework. Nothing about
 * that failure is loud: the generated {@code Stub.asInterface} wraps any binder in a proxy without
 * checking anything, the binder stays alive so {@code isBinderAlive()} keeps answering true, and
 * every transaction then throws {@code SecurityException} out of {@code Parcel.enforceInterface}
 * before its transaction code is even read - so the manager draws a framework that is plainly
 * running as one that answers nothing, on every screen, with nothing said. The manager therefore
 * compares {@code IBinder.getInterfaceDescriptor()} against its own compiled {@code DESCRIPTOR} the
 * moment the binder arrives and before any transaction. That one question is exempt by
 * construction - {@code INTERFACE_TRANSACTION} sits outside
 * {@code FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION}, which is the range the generated
 * dispatcher checks the interface token for - so it is answered across any mismatch, and it names
 * the build on the other end.</p>
 *
 * <p><b>Transaction ids are implicit, and {@link #getProtocolVersion} is what makes that safe.</b>
 * They are assigned in declaration order, so adding, removing or reordering a method shifts every
 * id below it - and unlike the descriptor, nothing about that shift is visible to a peer built
 * against a different revision. It would simply call a different method than it meant to. That is
 * what numbering the methods by hand used to guard against, at the price of a number beside every
 * one of them and a hole beside every one retired.</p>
 *
 * <p>A version handshake guards the same thing better. {@link #getProtocolVersion} is declared
 * first, so it is transaction zero whatever else changes, and it is the first call the manager
 * makes. A peer that disagrees is refused outright rather than left to call methods whose meaning
 * has moved under it - which is what the numbers permitted: id 33 of the interface this replaces
 * carried {@code setHiddenIcon(boolean hide)} and then {@code setForcedLauncherIcons(boolean force)},
 * the same number with the argument's sense inverted, and every old peer went on calling it and
 * asking for the opposite of what it meant.</p>
 *
 * <p>So the rule is not "append only". It is: change this file however the design wants, and bump
 * {@link #PROTOCOL_VERSION} in the same commit.</p>
 *
 * <p>A {@code boolean} returned by a write means <i>the daemon stored it</i>, not <i>the call
 * arrived</i>. Each such method says what a {@code false} means; ignoring it turns a refusal into a
 * silent success. None of them means "the value was already that": writing a value a row already
 * holds still answers true.</p>
 */
interface IManagerService {

    // ---- what this file is ---------------------------------------------------------------------

    /**
     * The generation of this interface a build was compiled from. Bump it whenever the method list
     * changes in any way - added, removed, reordered, or a signature altered.
     *
     * <p>Compiled into the manager as well as the daemon, so each side carries the number of the
     * source it was built from, and {@link #getProtocolVersion} is how one asks the other. Since
     * transaction ids follow declaration order, this number is the only thing standing between a
     * mismatched pair and a call that lands on the wrong method.</p>
     */
    const int PROTOCOL_VERSION = 2;

    /**
     * Which generation of this interface the daemon implements, never below 1.
     *
     * <p>Answers the question the descriptor cannot. A matching descriptor means the two ends agree
     * on what every id <i>means</i>; it does not mean the daemon <i>has</i> every id, and a call to
     * a transaction the daemon does not implement is not an error - the driver answers
     * {@code UNKNOWN_TRANSACTION}, {@code transact()} returns false, and the generated proxy then
     * reads its result out of a reply parcel nothing wrote to. A missing {@code int} therefore
     * arrives as 0, a missing {@code boolean} as false and a missing object as null, none of them
     * distinguishable from a real answer. That silence is what {@link #ROOT_UNKNOWN} was given the
     * value 0 to survive, one method at a time; this replaces the guessing for every method
     * appended from here on.</p>
     *
     * <p><b>Must stay the first method declared.</b> That is what pins it to transaction zero while
     * everything below it is free to move, and it is the whole mechanism: a peer whose method list
     * differs still agrees on where to ask what generation it speaks.</p>
     *
     * <p>It needs no fallback of its own, which is the one thing a version handshake usually cannot
     * arrange: this descriptor is new, so every daemon answering to it was built from a file that
     * already carries this method. A daemon too old to implement it answers 0 from an untouched
     * reply parcel, and 0 is below the floor, so it is refused for the right reason anyway.</p>
     */
    int getProtocolVersion();

    // ---- what this framework is -----------------------------------------------------------------

    /**
     * This daemon's version code, which is the commit count on origin/master.
     *
     * <p>A branch build and the official build at the same depth therefore wear the same number;
     * {@link #getBuildStamp} is what tells them apart.</p>
     */
    long getFrameworkVersionCode();

    /** This daemon's version name. */
    String getFrameworkVersionName();

    /**
     * The build stamp, or null when this build recorded none.
     *
     * <p>Names where the build came from as well as what commit it was made from -
     * {@code 93d66473-JingMatrix-Vector} from CI, {@code 93d66473} from a clean local tree,
     * {@code 93d66473+thinkpad} from a modified one. The commit always leads, so a caller that
     * wants only that takes the head and not the whole string; {@code -} is followed by the
     * repository holding that commit, {@code +} by the machine holding changes that no repository
     * does.</p>
     *
     * <p>Was called {@code getFrameworkCommit}, and its own documentation had to open by saying it
     * was not a commit.</p>
     */
    @nullable String getBuildStamp();

    /**
     * The libxposed API level this framework implements, verbatim from
     * {@code IXposedService.LIB_API}.
     *
     * <p>The one version number here that is not this framework's own, which is why it says
     * libxposed and the three above say framework. The contrast is the point: a reader who sees the
     * word once in the identity block knows which of the four is not about this build.</p>
     */
    int getLibxposedApiVersion();

    // ---- whether the framework is actually working ----------------------------------------------

    /**
     * Whether system_server reached the daemon and was given its framework service.
     *
     * <p>Latched once system_server has identified itself to the bootstrap bridge as uid 1000,
     * process {@code system}, with a life token, <b>and</b> its registration has succeeded - and
     * never cleared afterwards, because the framework is in that process for as long as it lives.
     * Both halves matter: registration fails when the life token cannot be linked to death, and a
     * true here that only meant "it asked" would claim the framework is in system_server while no
     * module hooking the system loads.</p>
     *
     * <p>False is unambiguous: the framework is not in system_server, and nothing hooking the
     * system will run. It is also the answer for the whole of boot before system_server gets
     * there, so a false read early is not yet evidence of a fault.</p>
     */
    boolean isSystemServerAttached();

    /**
     * Whether the framework's SELinux policy is in force.
     *
     * <p>One {@code checkSELinuxAccess}: may {@code u:r:dex2oat:s0} execute
     * {@code u:object_r:dex2oat_exec:s0} without transitioning. That is the first line of the
     * module's own {@code sepolicy.rule} and is not allowed by stock policy, so it is used as a
     * canary for the whole file - the daemon does not enumerate its rules, it asks whether the
     * first one took. A false therefore means the root implementation did not apply the file, and
     * the rest of what it grants is missing too, rather than meaning this one rule is absent.</p>
     */
    boolean isSepolicyLoaded();

    /**
     * One of the {@code DEX2OAT_*} constants: what the dex2oat wrapper is doing.
     *
     * <p>A state, not a yes or no, which is why it is no longer called a compatibility. Maintained
     * by an observer on {@code /sys/fs/selinux/enforce} that mounts and unmounts the wrapper as the
     * device's SELinux state moves, so it changes without anyone asking.</p>
     *
     * <p>Read together with {@link #isDex2OatInliningDisabled}: they are two routes to one end, not
     * two independent facts.</p>
     */
    int getDex2OatWrapperState();

    /**
     * Whether {@code dalvik.vm.dex2oat-flags} carries {@code --inline-max-code-units=0}.
     *
     * <p>The fallback route, and the reason it is asked at all. The framework needs the platform's
     * dex2oat not to inline across the methods a module may hook. It gets that from the wrapper
     * while the wrapper is mounted; when the wrapper is taken down the daemon sets this property
     * instead, and deletes it again when the wrapper comes back. So this being true while
     * {@link #getDex2OatWrapperState} is not {@link #DEX2OAT_OK} is the healthy fallback, and both
     * being unhealthy at once is the only case worth reporting - neither on its own is.</p>
     */
    boolean isDex2OatInliningDisabled();

    /**
     * The dex2oat wrapper is mounted and serving.
     *
     * <p>Also the answer below Android 10, where there is no wrapper at all: the daemon only starts
     * the machinery that would report on one from Android 10, and answers with this literal before
     * then. "Working" and "not applicable on this release" are therefore the same value, so a
     * caller must not render it as a supported feature without checking the release first - the
     * manager drops the row entirely below Android 10 rather than claim anything about it.</p>
     */
    const int DEX2OAT_OK = 0;

    /**
     * The daemon's own socket server for the wrapper died, and the wrapper was unmounted.
     *
     * <p>The wrapper is a small binary bind-mounted over the platform's dex2oat, which asks the
     * daemon over a unix socket for a descriptor to the real one. When that server throws, the
     * mounts are taken down and this is latched: the SELinux observer that would otherwise re-mount
     * them stops watching, so this state never recovers while the daemon lives.</p>
     */
    const int DEX2OAT_CRASHED = 1;

    /**
     * The bind mounts over the platform's dex2oat binaries could not be established, or did not
     * survive being checked. Latched, and for the same reason as {@link #DEX2OAT_CRASHED}: the
     * observer stops watching, so this does not recover by itself either.
     */
    const int DEX2OAT_MOUNT_FAILED = 2;

    /**
     * SELinux is permissive, so the wrapper was unmounted.
     *
     * <p>Not a failure and not latched - the daemon keeps watching
     * {@code /sys/fs/selinux/enforce} and re-mounts when the device goes back to enforcing.</p>
     */
    const int DEX2OAT_SELINUX_PERMISSIVE = 3;

    /**
     * An untrusted app can reach {@code dex2oat_exec}, so the wrapper was unmounted.
     *
     * <p>The probe is two {@code checkSELinuxAccess} calls asking whether
     * {@code u:r:untrusted_app:s0} may {@code execute} or {@code execute_no_trans}
     * {@code u:object_r:dex2oat_exec:s0}. That it may is evidence the policy on this device is more
     * permissive than the one the wrapper assumes, and hooking dex2oat under it would expose the
     * wrapper to every app on the device. Re-checked on every SELinux event, so this recovers by
     * itself.</p>
     */
    const int DEX2OAT_SEPOLICY_INCORRECT = 4;

    // ---- module configuration ---------------------------------------------------------------------

    /**
     * The enabled modules, by package name.
     *
     * <p>Straight from the database and deliberately not from the module cache, which is rebuilt
     * asynchronously: a caller that enables a module and reads back immediately - which the manager
     * does, to confirm its own write - was told the state from before its own write, and then wrote
     * that back over what it had correctly recorded. The row sat in the wrong section until the app
     * restarted, by which time the cache had caught up and nothing looked wrong.</p>
     *
     * <p>The framework keeps a pseudo-module row of its own in the same table, so that its own
     * settings have a foreign key to hang from. It can never be enabled, so it can never appear
     * here.</p>
     */
    List<String> getEnabledModules();

    /**
     * Switches a module on or off.
     *
     * <p>Enabling inserts the row when the package has never been seen, with an empty APK path for
     * the next cache rebuild to fill in, and takes down the shade's "not activated yet" notice for
     * that package - which nothing else was ever going to do. Disabling only updates, and takes
     * nothing down. Both ask the daemon to rebuild its module cache, so the effect on a running
     * process arrives later and only when that process next starts.</p>
     *
     * @return whether the daemon stored it, which is not whether the call arrived. False means the
     *         package is the framework's own pseudo-module, or - when disabling - that no module row
     *         exists for it. Enabling a module that was already enabled still answers true
     */
    boolean setModuleEnabled(String packageName, boolean enabled);

    /**
     * A module's scope as configured, or null.
     *
     * <p>Null only for the framework's own pseudo-module row, which is not a module and has no
     * scope; every other package answers with a list, empty when nothing is scoped to it. Null and
     * empty are different answers and a caller must keep them apart - treating a refusal as no rows
     * turns an unreadable scope into an erased one on the next write.</p>
     *
     * <p>What comes back is the configuration, not what the framework will actually inject. A
     * legacy module is additionally put into its own scope at cache-rebuild time - it reports
     * itself active by hooking a method in its own app, so it has to be there - and that row is
     * derived rather than stored, so it is not here and must not be written back as though it were.
     * {@link #getIncludeNewApps} is the opposite case and needs no allowance: it widens a scope by
     * writing ordinary rows through {@link #setModuleScope}, so they are here, and switching it off
     * does not take them away again.</p>
     */
    @nullable List<ScopeEntry> getModuleScope(String packageName);

    /**
     * Replaces a module's scope with exactly this set.
     *
     * <p>Not a merge: what is not in the list is removed. A caller that wants to add one entry must
     * read the current set first, and must expect it to have changed since it last looked - a
     * module can request scope for itself, and the daemon adds newly installed apps to a module
     * that asked for that.</p>
     *
     * <p><b>This also switches the module on.</b> A scope is meaningless on a module that is off,
     * and every route into this call - the manager, the socket CLI, a backup restore, a module's
     * own request - would otherwise have to remember to enable separately. The consequence to
     * account for is that a caller with its own idea of the enabled state has to re-read it
     * afterwards, or it will show a module that is off while its scope screen shows a scope that is
     * live.</p>
     *
     * @return false when the daemon refused or could not write: a module that fixes its own scope
     *         in its APK will not take a target outside it, and a database failure rolls the whole
     *         transaction back. A refusal is not a failed transaction, so a caller that only checks
     *         whether the call succeeded will show a scope the framework never took
     */
    boolean setModuleScope(String packageName, in List<ScopeEntry> scope);

    /**
     * Whether a module is given each newly installed app automatically.
     *
     * @return false for a package the daemon holds no module row for, and for the framework's own
     *         pseudo-module - so a false here is not evidence that a module exists
     */
    boolean getIncludeNewApps(String packageName);

    /**
     * Sets that flag.
     *
     * @return whether the daemon stored it, which is not whether the call arrived: no row is
     *         written for a package that is not a known module, or for the framework's own
     *         pseudo-module. Writing the value the row already held still answers true
     */
    boolean setIncludeNewApps(String packageName, boolean enable);

    /**
     * Every module that is installed and switched on and that the framework still cannot load, with
     * the reason for each.
     *
     * <p>One call for the whole set. This replaces a pair - a list of names, then one transaction
     * per name to ask why - whose caller had to seed every entry with a placeholder reason before
     * the second round could overwrite it, so a single dropped transaction left a module reported
     * as missing its APK, a claim nothing had established.</p>
     *
     * <p>Read out of the module cache, not the database, so unlike {@link #getEnabledModules} this
     * lags a write: a module switched on a moment ago is absent from this list until the rebuild
     * that write asked for has finished, whatever that rebuild will conclude. Presenting the two as
     * one snapshot tells a reader their module loaded and then contradicts it on the next
     * refresh.</p>
     */
    List<ModuleLoadFailure> getModuleLoadFailures();

    /** Installed and enabled, but no APK path could be resolved for it. */
    const int MODULE_LOAD_NO_APK = 1;

    /**
     * Installed and enabled, and the framework still would not load it.
     *
     * <p>Deliberately not more specific. The loader refuses a zip that will not parse, an APK with
     * no init files and one with no module classes in the same breath, and naming any single one of
     * those would be a guess.</p>
     */
    const int MODULE_LOAD_UNUSABLE = 2;

    /**
     * Built against libxposed API 100, which this framework no longer loads.
     *
     * <p>The one refusal the loader can name, and the one a reader can act on: the module is not
     * broken, it is old, and only its author can move it forward. It used to arrive as
     * {@link #MODULE_LOAD_UNUSABLE}, which reads as "your module is broken".</p>
     */
    const int MODULE_LOAD_UNSUPPORTED_API = 3;

    // ---- the framework's own settings ------------------------------------------------------------

    /**
     * Whether the framework posts its status notification. True on a device where nobody has said
     * otherwise.
     *
     * <p>Worth more than it looks on a parasitic install, where the manager has no launcher entry
     * and that notification can be the only way back into it.</p>
     *
     * <p>Was called {@code enableStatusNotification}, which reads as a command and was called as
     * one: the socket CLI invokes it in the branch that handles <i>reading</i> settings.</p>
     */
    boolean isStatusNotificationEnabled();

    /**
     * Sets that, and reconciles the shade with it in the same call - posting the notification if it
     * was off and is now on, cancelling it if it was on and is now off - so the shade never
     * disagrees with the switch.
     */
    void setStatusNotificationEnabled(boolean enabled);

    /**
     * Whether the daemon is capturing the verbose log. True on a device where nobody has said
     * otherwise.
     *
     * <p>The stored value, not the value or'd with the build type. It used to be the latter, which
     * made the setting unwritable on a debug daemon: the manager could never read false, so its
     * switch snapped back on every tap and had to be greyed out.</p>
     */
    boolean isVerboseLogEnabled();

    /**
     * Sets that, and asks the daemon's log reader to start or stop capturing to match.
     *
     * <p>The reader acts on a sentinel written into the log rather than on this call returning, so
     * capture is not yet in step when this comes back. What is already written stays written; only
     * what is captured from here on changes.</p>
     */
    void setVerboseLogEnabled(boolean enabled);

    // ---- ART inline hook compatibility mode -----------------------------------------------------

    /**
     * Every package opted into ART inline-hook invalidation, sorted.
     *
     * <p>Also names {@code system} when the system UI (whose process is {@code system:ui}) is on
     * the list, and names nothing else synthetic: the configured set is returned verbatim. The
     * empty set is the ordinary answer on a device where nobody has touched the setting.</p>
     */
    List<String> getInvalidateArtInlineHookPackages();

    /**
     * Sets whether a package invalidates Vector's native ART inline hooks after injection.
     *
     * <p>Opting in makes a process restore libart.so's file-backed executable image and its own
     * pre-injection modifications after the framework bootstrap, leaving LSPlant/Dobby metadata
     * intact. It is a compatibility operation for apps whose protection rejects the temporary
     * patches; the framework's own maintenance hooks no longer run afterwards.</p>
     *
     * @return whether the daemon stored it, which is not whether the call arrived. False means the
     *         package name was blank - nothing else is refused
     */
    boolean setInvalidateArtInlineHooks(String packageName, boolean enabled);

    // ---- logs -------------------------------------------------------------------------------------

    /**
     * The part of one of the two logs that is being written right now, read-only, or null when the
     * daemon holds no descriptor for it.
     *
     * <p>One method rather than the two it replaces, because every other call in this group already
     * takes the same boolean and the single caller was choosing between them by hand.</p>
     *
     * <p>The two were not symmetric and still are not: only the modules stream asks the daemon's
     * log reader to re-open a descriptor it has lost. Levelling that would change when a lost
     * verbose descriptor is repaired, which is a decision about the log and not one this merge is
     * entitled to take.</p>
     */
    @nullable ParcelFileDescriptor getLiveLogPart(boolean verbose);

    /**
     * The parts of that log still on disk, oldest first, as bare file names.
     *
     * <p>{@link #getLiveLogPart} only ever hands over the part being written, and the daemon keeps
     * ten, so on a device that has been logging for an hour most of the history was unreachable.
     * Listed from the log directory rather than from the reader's own record of what it has opened,
     * so a part the reader never had in hand is still offered. The names carry an ISO-8601
     * timestamp, which is why this order is chronological.</p>
     *
     * <p>This daemon run's parts only. A restart moves the whole log directory aside and starts an
     * empty one, so the previous run's parts are reachable through {@link #writeBugReport} and
     * nowhere else.</p>
     */
    List<String> getLogParts(boolean verbose);

    /**
     * Opens one part by a name {@link #getLogParts} returned, read-only.
     *
     * <p>Any other name is refused. The name arrives from an unprivileged process and is used to
     * build a path inside a directory only root can read, so it is checked against that listing
     * rather than pattern-matched for {@code ..}: traversal and anything outside the log directory
     * are ruled out by construction.</p>
     *
     * @return null for a name that is not one of the current parts, which includes a part that
     *         rotated away between the two calls
     */
    @nullable ParcelFileDescriptor getLogPart(boolean verbose, String name);

    /**
     * Closes the part being written and opens a fresh one.
     *
     * <p><b>Nothing is deleted and nothing is truncated.</b> The closed part stays on disk under
     * the ten-part limit, stays reachable through {@link #getLogParts} and {@link #getLogPart}, and
     * still travels in {@link #writeBugReport}. This was called {@code clearLogs}, which is what a
     * caller offering it to a user will say, and the moment the part list was added that inaccuracy
     * became a visible contradiction: the user cleared the log and the cleared lines were still one
     * tap away.</p>
     *
     * <p>Answers nothing, and used not to: it returned a boolean that was the constant true, which
     * the manager read as a success signal, so a rotation that never happened was reported as one
     * that had. There is nothing truthful to answer - the daemon asks its reader to rotate by
     * writing a sentinel into the log and does not learn whether it acted. What did happen is
     * visible in {@link #getLogParts}.</p>
     */
    void startNewLogPart(boolean verbose);

    /**
     * Writes a bug report into {@code zipFd} as a zip.
     *
     * <p>Far more than the logs, which is why it is no longer called {@code getLogs} and why the
     * logs are the last thing added: tombstones and ANR traces, both crash directories, a full
     * {@code logcat -b all -d} and {@code dmesg}, every root module's prop, remove, disable, update
     * and sepolicy files, the {@code /proc} maps, mountinfo and status of the daemon and of the
     * caller, the module database, and the resolved scopes rendered as text. The zip's comment
     * names the build type, version, version code and build stamp, so an attached archive can be
     * tied to a binary.</p>
     *
     * <p>Synchronous, and the slowest call here by a wide margin - it walks several directories and
     * forks two commands before it deflates anything. Each side owns its copy of the descriptor and
     * closes it.</p>
     *
     * <p>Errors met while filling the zip are logged and swallowed, so a partial archive arrives
     * looking exactly like a complete one: this transaction succeeding is not evidence that
     * everything is in it.</p>
     */
    void writeBugReport(in ParcelFileDescriptor zipFd);

    // ---- the device, as only a privileged process can see it ---------------------------------------

    /**
     * Every package installed for every real user on the device.
     *
     * <p>The manager's own package manager sees one user, and the whole point of the app list is
     * that a module may be scoped into another profile. Per user rather than merged: a device with
     * a work profile or a private space holds the same package twice, under two uids, and a scope
     * is chosen per copy. Each user is queried separately and an entry is kept only when its own
     * uid belongs to the user it was listed for, because the platform will otherwise answer for
     * packages that user does not hold.</p>
     *
     * <p>Also carries the clone users some Lenovo devices keep without reporting them in the user
     * list, which have to be probed for by id.</p>
     *
     * <p>A {@code ParcelableListSlice} rather than a plain {@code List} because this is hundreds of
     * {@code PackageInfo} objects on an ordinary device and does not fit in one binder transaction;
     * the slice sends it in chunks, as AOSP's own hidden {@code ParceledListSlice} does for the
     * same call.</p>
     *
     * @param flags           passed to the platform unchanged, widened to a long on Android 13 and
     *                        later where the hidden method takes one
     * @param filterNoProcess drops packages that declare no process, which can never be injected
     *                        into and are only noise in a picker - at the cost of a second
     *                        package-manager query per package
     */
    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess);

    /**
     * Resolves an intent against one user's activities.
     *
     * <p>Same reason as above: the manager cannot see another profile's activities at all, and the
     * screen it needs is a module's own settings activity in whatever user holds it. The same slice
     * wrapper, though this list is normally one entry.</p>
     */
    ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(in Intent intent, int flags, int userId);

    /**
     * The real users and profiles on this device, as id and name.
     *
     * <p>Includes the ones a manufacturer hides, by the same probe {@link
     * #getInstalledPackagesFromAllUsers} uses - a module can be installed in one, and would
     * otherwise be invisible to the manager.</p>
     */
    List<DeviceUser> getUsers();

    // ---- things done to the device on the manager's behalf ------------------------------------------

    /**
     * Starts an activity as another user.
     *
     * <p><b>Unless {@code noUserSwitch} is set, and unless the device is already on the target's
     * profile parent, it is first switched to that parent and the screen is locked.</b> That is the
     * surprising part of this call. It is
     * right for an activity that exists in one profile only, and a startling thing to do to someone
     * who pressed "open" on a module whose window shows for whichever user is current anyway - so
     * the caller decides, from the resolved activity's {@code FLAG_SHOW_FOR_ALL_USERS}.</p>
     *
     * <p>A parameter rather than the {@code lsp_no_switch_to_user} intent extra it replaces, and
     * carrying that extra's sense unchanged so that neither side inverts a test while adopting it.
     * The extra was a string agreed between two files in two APKs that can ship apart, which is a
     * skew nothing else here is exposed to and one that fails quietly: a manager not updated in
     * step spelled it differently, and the whole of the symptom was a device that changed user and
     * locked itself when somebody opened a module.</p>
     *
     * <p>Returns the activity manager's own start code, so that a refusal reaches the caller
     * instead of a flat success: a declined user switch, a disabled or unexported activity, an
     * activity that has gone since it was resolved. A start succeeded when the code is 0 to 99,
     * which is what {@code ActivityManager.isStartResultSuccessful} tests; those constants are
     * hidden, so the band has to be written out. -100 to -1 is the fatal refusal band and 100 to
     * 199 the non-fatal one, and nothing came up in either.</p>
     *
     * <p>Was called {@code startActivityAsUserWithFeature}, after the AOSP method of that name -
     * whose distinguishing feature is a calling feature id that this signature does not have and
     * the daemon never supplies.</p>
     */
    int startActivityAsUser(in Intent intent, int userId, boolean noUserSwitch);

    /**
     * Force-stops a package for one user.
     *
     * <p>Answers nothing, because the platform call answers nothing either: whether the transaction
     * arrived is the only verdict there is, and a package that was not running and a refusal are
     * the same silence.</p>
     */
    void forceStopPackage(String packageName, int userId);

    /**
     * Uninstalls a package, as the {@code android} installer.
     *
     * <p>{@link #ALL_USERS} for {@code userId} removes it from every user. That is what the manager
     * needs to replace itself: a copy left behind in another profile refuses an install exactly as
     * loudly as one in this profile.</p>
     *
     * <p>Blocks on a daemon binder thread until the package installer broadcasts its status, or for
     * a minute, whichever comes first. The bound is what keeps a status that never arrives from
     * holding that thread for the life of the daemon.</p>
     *
     * @return whether the installer reported success. A device-policy refusal, a user that does not
     *         exist, and a status that never arrived all come back as a plain false, which no
     *         exception would show - so this being false is not by itself evidence the package is
     *         still installed, only that nothing confirmed it is gone
     */
    boolean uninstallPackage(String packageName, int userId);

    /**
     * Clears an app's ART profiles and forces a profile-guided recompile.
     *
     * <p>What the manager offers after a module's scope changes: the app's compiled code can hold
     * decisions taken before it was hooked, and only recompiling takes them back out.</p>
     *
     * <p>Both steps, in that order, because {@code speed-profile} only compiles methods recorded in
     * the reference profile and recompiling without clearing re-bakes a profile captured before the
     * module set changed. Clearing is best effort and its failure is not reported: a recompile
     * against a stale profile still beats abandoning the action. From Android 14 this goes through
     * the ART Service shell, falling back to the hidden binder calls; on older releases it uses
     * those directly, and they no longer exist on Android 17.</p>
     *
     * @return whether the recompile succeeded
     */
    boolean optimizePackage(String packageName);

    /**
     * Restarts the framework without rebooting the device - the "soft reboot".
     *
     * <p>Restarts the primary zygote, which is what system_server is forked from, so the whole
     * framework goes. This is what "force stop" would mean for the framework, and everything on
     * screen dies with it: the caller is expected to have said so first. It is also the only way to
     * make system_server pick up a scope change, because it reads its module list once, when it
     * starts.</p>
     *
     * <p>Distinct from the daemon's own restart of the secondary zygote, which exists for 64/32
     * devices and is not reachable from here.</p>
     */
    void softReboot();

    /** Reboots the device. */
    void reboot();

    /**
     * Whether apps that declare no launcher entry are given one anyway.
     *
     * <p>Android 10 and later synthesise an entry for an installed app that declares none, and the
     * global setting {@code show_hidden_icon_apps_enabled} decides whether it appears. Here rather
     * than with the framework's own settings because the value is not the framework's: it lives in
     * Android's global settings, anything on the device can move it, and the daemon reads it back
     * rather than remembering what it wrote.</p>
     *
     * <p>Unset reads as true, which is the platform's own default - reading unset as "off" showed
     * the opposite of what the system was doing on every device where nobody had touched it. True
     * is also the answer when the read itself failed.</p>
     */
    boolean isForcedLauncherIcons();

    /**
     * Sets that. True shows the icons.
     *
     * <p>Answers nothing, and the write can fail without saying so: the daemon applies it by
     * running the {@code settings} command rather than going through a binder. That is neither
     * laziness nor a shortcut - two in-process routes were tried and both are closed to this
     * process, because the pre-Android-12 {@code IContentProvider.call} signature no longer exists
     * and going through the system context's content resolver fails at the far end, where the
     * daemon has an {@code ActivityThread} but no application record to be given a provider for. A
     * caller that needs to know whether the setting moved must read {@link #isForcedLauncherIcons}
     * back.</p>
     *
     * <p>The argument used to mean the opposite, under the name {@code setHiddenIcon(boolean
     * hide)}, at the same transaction id - see the note on this interface.</p>
     */
    void setForcedLauncherIcons(boolean force);

    /**
     * The user id {@link #uninstallPackage} reads as "every user this package is installed for".
     *
     * <p>The daemon's own convention rather than a platform one: it becomes
     * {@code PackageManager.DELETE_ALL_USERS} and a target user of 0. Declared here because it is
     * the only place it can be agreed - the manager had to define its own copy of this number, next
     * to a comment repeating what the daemon does with it.</p>
     */
    const int ALL_USERS = -1;

    // ---- installing and updating the framework -------------------------------------------------------

    /**
     * Which root implementation is managing this device, as one of the {@code ROOT_*} constants.
     *
     * <p>Presence only: whether the zygisk loader will run on this device is the loader's own
     * decision, taken before the daemon exists, so a daemon that is answering has already passed
     * it. Detected once and cached, because detecting it forks each candidate binary. A binary that
     * exists but exits non-zero is not counted - a leftover {@code magisk} from a previous root
     * manager answers "Cannot connect to daemon", and counting it would turn a working KernelSU
     * device into {@link #ROOT_MULTIPLE}.</p>
     */
    int getRootImplementation();

    /**
     * Flashes the framework's own root-module zip through whatever root implementation is managing
     * the device.
     *
     * <p>"Module" there means a Magisk, KernelSU or APatch module, which is what the framework
     * ships as - not an Xposed module, which is what the word means everywhere else in this
     * file.</p>
     *
     * <p>The daemon already runs as root, so this execs the implementation's own installer directly
     * rather than going through {@code su} - the same commands the project's gradle install tasks
     * use, so a zip that flashes from a developer's machine flashes the same way from the
     * device.</p>
     *
     * <p>Returns as soon as the work has been handed to a daemon thread. "The daemon accepted it"
     * and "the flash finished" are therefore two events on two channels, deliberately: a flash runs
     * for minutes and can end in a reboot, and a caller suspended until this returned would be
     * suspended across that. Output is streamed to {@code receiver} <b>and</b> written to the
     * daemon's log, so a flash that failed on a device that no longer boots can still be read out
     * of a saved bug report. A receiver that has gone away does not stop the flash: stopping
     * halfway would leave the module tree half written.</p>
     *
     * @param zipPath  a path the daemon can read; one it cannot is reported as
     *                 {@code IFrameworkInstallReceiver.INSTALL_NO_SUCH_FILE} rather than refused
     *                 here
     * @param receiver where the output and the exit status arrive
     */
    void installFrameworkZip(String zipPath, IFrameworkInstallReceiver receiver);

    /**
     * The manager APK this framework was flashed with, opened read-only, or null.
     *
     * <p>For installing the manager as an ordinary app. The manager cannot read this file itself:
     * parasitically it runs as its host process, whose uid has no business in the module directory,
     * and standalone it is the very thing being replaced. The daemon verifies the signature before
     * handing the descriptor over, so what comes back is the APK this framework would accept as its
     * own manager and not whatever happens to sit at that path - the same file and the same check
     * as {@code IFrameworkService.openManagerApk}, which serves it to a host process for
     * injection.</p>
     *
     * @return null for three cases that are deliberately not told apart, because none of them
     *         leaves anything to offer: the file is missing, its signature is not the one this
     *         framework accepts, or the daemon is too old to answer at all
     */
    @nullable ParcelFileDescriptor getManagerApk();

    /**
     * The daemon did not say which root implementation is installed.
     *
     * <p>Takes 0 because 0 is also what a binder proxy hands back for a transaction the daemon does
     * not implement. {@link #ROOT_NONE} used to sit here, so a daemon too old to answer read as "no
     * root installed", and the manager told a rooted user to go and install the root manager they
     * were already running. Never produced by the daemon itself.</p>
     */
    const int ROOT_UNKNOWN = 0;

    /** No root implementation was found, so nothing can be flashed. */
    const int ROOT_NONE = 1;

    /**
     * More than one was found.
     *
     * <p>Not a failure in any of them - a device with two root implementations installed, where
     * flashing through either would be guessing which one owns the module tree on the reader's
     * behalf.</p>
     */
    const int ROOT_MULTIPLE = 2;

    /** Magisk. */
    const int ROOT_MAGISK = 3;

    /** KernelSU. */
    const int ROOT_KERNELSU = 4;

    /** APatch. */
    const int ROOT_APATCH = 5;
}
