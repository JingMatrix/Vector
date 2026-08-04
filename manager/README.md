# Vector Manager

The manager app: Jetpack Compose, one activity, configuring the root daemon over Binder. It holds
no privilege of its own — everything it does to the device, it asks the daemon to do. It replaces
`:app`, which is deleted.

This file covers what the code cannot tell you on its own: the constraints it is built around, and
the places where a mistake fails silently rather than loudly. The rest is in the code.

## The parasitic model

The manager normally runs injected into `com.android.shell` rather than installed. Its
`AndroidManifest.xml` is never registered, so nothing declared there exists at runtime: no
`ContentProvider`, and therefore no `androidx.startup` and nothing that self-registers through
`InitializationProvider`; no `FileProvider`; and no per-app language API, since
`setApplicationLocales` is keyed on an installed package. The language override is applied in
composition instead, which is why it takes effect without a restart.

Everything is therefore initialised explicitly, from the activity. The same APK also installs as an
ordinary app for development, and both modes have to work — so anything that assumes one of them is
a bug waiting for the other.

Its memory is `com.android.shell`'s memory. That is the reason behind decisions that would look
paranoid in a normal app: the log reader indexes byte offsets and pages a window rather than
holding a file, and the module scan is cached rather than repeated.

## How the binder arrives

The framework loads `<managerPackage>.Constants` out of the injected dex by reflection and calls the
static `setBinder(IBinder)`. Nothing in this APK calls it, so R8 is told to keep it in
`proguard-rules.pro`. Rename that class or method and the handshake breaks at runtime with no
compile error anywhere — the app simply comes up reporting no framework.

Order is not fixed. The binder can arrive before the activity exists, or the activity can start
before any binder does. `ServiceLocator.attach()` is idempotent and `bind()` is a plain assignment
to a `StateFlow`, so either order is safe, and repositories collect that flow rather than being
handed a binder — a late arrival, or a reconnection, makes them re-read instead of leaving them
with whatever they managed to fetch before there was a daemon.

## Talking to the daemon

`ipc/DaemonClient` wraps every AIDL call in `runIpc`, which moves it to `Dispatchers.IO` and returns
a `Result`. The interface is
`services/manager-service/src/main/aidl/org/matrix/vector/ipc/IManagerService.aidl`, and it is the
source of truth for what each call means: read the method's documentation there before calling it.

Two properties of Binder shape most of the mistakes made here, and the AIDL spells out what each
method does about them. A proxy returns a *default* for a transaction the daemon does not implement
rather than throwing, so `0`, `null` and empty are indistinguishable from real answers — see
`getProtocolVersion` and `ROOT_UNKNOWN` there. And a call that succeeded is not a call that did
anything: several of these return a `boolean` the daemon uses to refuse, and dropping it turns a
refusal into a silent success.

The daemon owns the truth. When a write and a read disagree, the read is usually coming from the
daemon's asynchronous cache while the write went to its database.

## Logging

Log under `Constants.TAG`, and only under it. `daemon/src/main/jni/logcat.cpp` routes any tag
beginning `Vector` into the daemon's verbose stream, so those lines reach the Logs screen and travel
in the zip export a user attaches to a report. A file-local tag is ordinary Android practice and
would land nowhere. The conventions — prefixes, levels, what never belongs in a message — are on
`Constants.TAG` itself.

Crashes are written to `cacheDir/crash` because that is where `FileSystem.getLogs` already collects
them from, in both of the manager's homes.

## Strings

`res/values/strings.xml`, `strings_logs.xml` and `strings_store.xml`, translated into 18 locales.
`crowdin.yml` points at this module and at the daemon's, and `manager/build.gradle.kts` merges
`../daemon/src/main/res`, so a name collision between the two is a build error.

Nothing user-visible is hard-coded in a composable, and identifiers that must not be translated
carry `translatable="false"`. The build scans `values-*` folders containing a `strings.xml` to
produce `BuildConfig.TRANSLATIONS`, so a locale Crowdin adds needs no code change.

Changing what a string *means* needs a new key. Reword it in place and eighteen translations go on
asserting the old meaning until someone notices, which can be a long time.

## Building and running

```sh
./gradlew :manager:assembleDebug   # the APK
./gradlew :zygisk:zipDebug         # the module zip, which contains it
./gradlew ktfmtFormat              # formatting is ktfmt; CI does not check it
```

The version code is `git rev-list --count refs/remotes/origin/master`, so a branch build and a
master build can share one; `module.prop` and the status page carry a build stamp, which is often
the only way to tell two builds apart on a device. It names where the build came from as well as
what it was built from. The commit leads and what follows says where: `93d66473-JingMatrix-Vector`
for a CI build, the bare `93d66473` for a local one, and `93d66473+thinkpad` — the machine that made
it — when the tree was not clean.

Debug builds add a second launcher activity — a demo mode with scripted device states, in
`src/debug` and absent from release builds, so it cannot be used to make a release report a healthy
framework. It does mean `monkey -c LAUNCHER` picks one of the two at random:

```sh
adb shell am start -n org.matrix.vector.manager/.ui.MainActivity
```

There is no test source set anywhere in this repository, and CI runs `zipAll` and nothing else. A
green tick means it compiles and packages. Everything else is verified by running it against a real
daemon on a device.
