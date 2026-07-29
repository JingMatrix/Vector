# Vector Manager

The manager app: Jetpack Compose, one activity, configuring the root daemon over Binder. It holds
no privilege of its own — everything it does to the device it asks the daemon to do. It replaces
the retired `:app`, which is still on disk but is no longer in `settings.gradle.kts`.

This file covers what the code does not tell you on its own: the constraints it is built around,
and the things that fail silently. Everything else is in the code.

## The parasitic model

The manager normally runs **injected into `com.android.shell`**, not as an installed app. Its
`AndroidManifest.xml` is never registered, so nothing declared there exists at runtime:

- no `ContentProvider`, so `androidx.startup` and anything that self-registers through
  `InitializationProvider` never runs;
- no `FileProvider`;
- no per-app language API — `setApplicationLocales` is keyed on an installed package, so the
  language override is applied in composition instead.

Anything that needs initialising is initialised explicitly, from the activity. The same APK also
installs as an ordinary app, and both modes have to work.

## How the binder arrives

The framework loads `<managerPackage>.Constants` out of the injected dex by reflection and calls the
static `setBinder(IBinder)`. Nothing in this APK calls it, so R8 is told to keep it in
`proguard-rules.pro`. **Renaming that class or method breaks the handshake silently at runtime, with
no compile error anywhere.**

Initialisation order is not fixed: the binder may arrive before the activity exists, or the activity
may start before any binder does. `ServiceLocator.attach()` and `bind()` are both idempotent and
safe in either order.

## Where state lives

`di/ServiceLocator` — hand-rolled and lazy, deliberately not a DI framework, for the reason above.
Repositories collect the binder as a `StateFlow` rather than being handed one, so a binder that
arrives late, or again after a reconnect, makes them re-read.

## Talking to the daemon

`ipc/DaemonClient` wraps every AIDL call in `runIpc`, which moves it off the main thread and returns
a `Result`. The interface is
`services/manager-service/src/main/aidl/org/lsposed/lspd/ILSPManagerService.aidl`.

Two things to know:

- A binder proxy returns a **default** for a transaction the daemon does not implement — it does not
  throw. So `0`, `null` and empty are indistinguishable from real answers, which is why
  `ROOT_UNKNOWN` is `0`.
- A call that succeeded is not a call that did something. Several of these return a `boolean` the
  daemon uses to refuse, and it has to be read.

## Logging

Log under `Constants.TAG`, and only under it. `daemon/src/main/jni/logcat.cpp` routes any tag
beginning `Vector` into the daemon's verbose stream, so those lines reach the Logs screen and travel
in the zip export. A file-local tag is ordinary Android practice and would land nowhere. The
convention — prefixes, levels, and what never belongs in a message — is documented on
`Constants.TAG`.

## Strings

`res/values/strings.xml`, `strings_logs.xml` and `strings_store.xml`, translated into 18 locales.
`crowdin.yml` points at this module and at the daemon's. `manager/build.gradle.kts` also merges
`../daemon/src/main/res`, so a name collision between the two is a build error.

- Nothing user-visible is hard-coded in a composable.
- `translatable="false"` for identifiers that must not be translated.
- The language picker scans `values-*` folders containing a `strings.xml`, so a locale Crowdin adds
  needs no code change.
- Changing what a string *means* needs a new key. Reword it in place and the existing translations
  go on asserting the old meaning.

## Building and running

```sh
./gradlew :manager:assembleDebug   # the APK
./gradlew :zygisk:zipDebug         # the module zip, which contains it
./gradlew ktfmtFormat              # formatting is ktfmt; CI does not check it
```

The version code is `git rev-list --count refs/remotes/origin/master`, so a branch build and a
master build can share one. `module.prop` carries the commit, marked `-dirty` when the tree was not
clean.

Debug builds add a **second launcher activity** — a demo mode with scripted device states, in
`src/debug`, absent from release builds. `monkey -c LAUNCHER` therefore picks one of the two at
random; start the real one explicitly:

```sh
adb shell am start -n org.matrix.vector.manager/.ui.MainActivity
```

## What is not here

There are **no tests** — no test source set anywhere in this repository — and CI runs `zipAll` and
nothing else. A green tick means it compiles and packages, never that it works. Changes are
verified by running the app against a real daemon on a device.
