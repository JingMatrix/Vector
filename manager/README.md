# Vector Manager Application

## What this module is

`:manager` is the user-facing surface of the Vector framework: a single-activity Jetpack Compose
application that configures the root daemon over Binder. It is the only module a user ever sees, and
it holds no privilege of its own — everything it does to the device it asks the daemon to do. The
older `app/` directory is still on disk but is no longer listed in `settings.gradle.kts`, so nothing
builds it.

Almost every structural decision below follows from one fact: **the manager normally runs
parasitically.** The Zygisk layer transplants `manager.apk` into a host process — `com.android.shell`
— so this module's `AndroidManifest.xml` is never registered with the package manager. The same APK
is also installable as an ordinary application for development, and has to behave identically in
both modes. Read *The parasitic model* below before anything else; the rest of the file assumes it.

## Directory structure

```text
src/main/kotlin/org/matrix/vector/manager/
├── Constants.kt          # TAG, and the reflection entry point the framework calls
├── data/
│   ├── github/           # Activity feed, commit archive, contributor resolution, device-flow auth
│   ├── log/              # Byte-offset log index, line parser, crash recorder
│   ├── model/            # Module detection, app, store and API-level models
│   └── repository/       # Apps, modules, settings, backup, store catalogue, installers
├── di/ServiceLocator.kt  # Hand-rolled service location; no DI framework
├── ipc/                  # DaemonClient (suspending Binder wrapper), package broadcasts
├── net/                  # OkHttp factory and the DNS resolver
└── ui/
    ├── components/       # Shared surfaces: panel header, search field, snackbar, ambience
    ├── navigation/       # Navigation 3 route keys and back stack
    ├── screens/          # home, modules, logs, repo (store), canary, update, report, web, splash
    └── theme/            # Seeded colour scheme generation, typography, in-composition locale

src/debug/kotlin/org/matrix/vector/manager/demo/
                          # Scripted device states; compiled into debug builds only
```

## The parasitic model, and what it forbids

`ParasiticManagerSystemHooker` hooks `ActivityStackSupervisor.resolveActivity` in `system_server`.
When a launch intent carries the category `org.matrix.vector.manager.LAUNCH_MANAGER` and resolves to
`com.android.shell`, the hooker rewrites the resolved `ActivityInfo` to point at
`org.matrix.vector.manager.ui.MainActivity`, moves it into a process named after the manager package,
and assigns it `android.R.style.Theme_DeviceDefault_Settings`. `ParasiticManagerHooker` then loads
the injected DEX into that process.

Consequences a newcomer will otherwise trip over:

* **No component declared in the manifest exists at runtime.** No `ContentProvider`, so no
  `androidx.startup` and nothing that self-registers through `InitializationProvider` — Coil's image
  loader is configured by hand in `MainActivity` for exactly this reason. No `WorkManager`. No
  `FileProvider`, so exports go through the Storage Access Framework and installs stream into a
  `PackageInstaller` session rather than passing a `content://` URI to `ACTION_INSTALL_PACKAGE`.
* **No permission listed in the manifest is granted.** The host's permissions are what apply.
  Inside `com.android.shell` that means the manager inherits `INSTALL_PACKAGES`, which changes the
  consent story for module installs (see below).
* **No per-app language.** `LocaleManager.setApplicationLocales` keys the preference on an installed
  package; ours is not installed, and the host's language is not ours to set.
* **The manifest's `Theme.Vector` only applies standalone.** Parasitically the window theme is
  whatever the hooker assigned. All colour and typography come from `VectorTheme` at composition
  time in both modes, so the two look the same.
* **A single activity is a requirement, not a preference.** `system_server` does not know these
  spoofed activities exist, so the hooker tracks them and captures and restores their saved state
  itself.
* **Process death is routine**, far more so than in an ordinary app, because the host is
  `com.android.shell`. Every reading preference — word wrap, header ambience, activity window,
  colour seed — is persisted in `SettingsRepository`'s `SharedPreferences` rather than held in a
  `ViewModel`.

The manifest is not dead weight: it governs the standalone install, and the `LAUNCH_MANAGER`
category declared on `MainActivity` is what the hooker matches on.

## How the binder arrives

`ParasiticManagerHooker.sendBinderToManager` reflects `<managerPackage>.Constants` out of the
injected DEX and invokes the static `setBinder(IBinder)` on it. Nothing inside this APK calls that
method, so `proguard-rules.pro` keeps both the class and the method by name; renaming either breaks
the handshake silently, at runtime, with no compile error anywhere. The same file keeps
`MainActivity`'s name, which the hooker also resolves reflectively.

`Constants.setBinder` hands the interface to `ServiceLocator.bind`, then calls `linkToDeath`. If the
daemon dies the manager exits the process, because a dead binder makes every screen render empty
state — which reads as "you have no modules" rather than "the framework is gone".

Ordering is not fixed. The binder may arrive before the activity exists, or the activity may start
with no daemon at all. `ServiceLocator.attach(context)` and `ServiceLocator.bind(service)` are both
idempotent and safe in either order.

## Where state lives

`ServiceLocator` is a hand-rolled object, deliberately not a DI framework: there is no guaranteed
initialisation point to hang one off. Everything is `by lazy`, and the daemon binder is exposed as a
`StateFlow` rather than a setter, so a repository constructed before the binder arrived re-reads when
it does.

Three things worth knowing before adding to it:

* `appScope` is a `SupervisorJob` on `Dispatchers.Default`, and outlives every activity and view
  model. Work that must survive a closed bottom sheet — the module update queue — runs there.
* `storeEntries` is the single join of the catalogue against installed versions, with muting already
  applied. Three screens read it: the Modules list's update mark, a module's own sheet, and the
  Store's count. Computing that answer a second time is how those numbers end up disagreeing on one
  device.
* `prefetch()` is called from `MainActivity` while the splash is still on screen. It is a head start,
  not a load-bearing step: every call it makes is idempotent and cached, and failures are ignored.

## Localisation

The chosen language is applied inside the composition, in `ui/theme/AppLocale.kt`, because the
platform API is unavailable (above). `LocalizedContent` provides `LocalConfiguration`, `LocalContext`
and `LocalLayoutDirection` together — providing the configuration alone would translate the text and
leave a right-to-left language laid out left to right.

Two constraints hold it together:

* The provided context is a `ContextWrapper` around the activity (`LocalizedContext`), not the result
  of `createConfigurationContext` alone. The latter is detached from the activity, so anything
  reached through `LocalContext` by walking `getBaseContext()` — `LocalActivity`, an activity result
  launcher, the back-press dispatcher — fails to find its owner.
* Every popup gets its own `AndroidComposeView`, which re-provides the Android composition locals
  from the window's base context. Sheets, dialogs and dropdown menus therefore render in the *system*
  language unless they re-apply the override, which is what `LocalizedOverlay` is for.

Dates and month names are formatted at draw time, never in a repository: `Locale.getDefault()` in
parasitic mode is the host application's.

## Strings and translations

Base strings live in `src/main/res/values/` and are split across `strings.xml`, `strings_logs.xml`
and `strings_store.xml`. `crowdin.yml` uploads them with the glob `values/strings*.xml`, so a new
`strings_*.xml` file is picked up without touching the config, and pulls translations back into
`values-%android_code%/`. `%android_code%` is the only placeholder that produces the region-qualified
folders Android actually uses (`values-zh-rCN`, `values-pt-rBR`); with the two-letter form Crowdin
cannot even find the existing folders.

There are 18 translated locales plus the English base. Of 402 base strings, four are marked
`translatable="false"` — the app name and three API-level labels that must not be localised.

The language picker is built from `BuildConfig.TRANSLATIONS`, which `build.gradle.kts` computes at
configuration time by listing `values-*` folders that contain a `strings.xml`, then adding `en` by
hand. English is added rather than found because it lives in `values/`, the base the others fall back
to. `AssetManager.getLocales()` cannot answer this question: it reports every locale any dependency
ships a resource for, plus the pseudo-locales.

## Logging

`Constants.TAG` is `"VectorManager"`, and that is not an arbitrary string.
`daemon/src/main/jni/logcat.cpp` matches tags by prefix against
`{"LSPosed", "Vector", "dex2oat", "zygisk"}` and routes
anything matching into its **verbose** stream. With verbose logging on, everything the manager logs
appears in the Verbose tab beside the daemon's own lines and travels in the zip export — the place a
reader already looks. A file-local tag would be ordinary Android practice and would land nowhere;
there are none in this app.

The conventions `Constants.TAG`'s KDoc sets out are worth following: an `area:` prefix naming the
operation and its subject, the `Throwable` always last, `e`/`w`/`i` only, nothing secret
interpolated, and `CancellationException` never logged (navigating away cancels a screen's scope, and
a log that fires on every back press is a log nobody reads).

## Daemon IPC

`DaemonClient` wraps `ILSPManagerService`, declared in
`services/manager-service/src/main/aidl/org/lsposed/lspd/ILSPManagerService.aidl` and shared with the
daemon. Every call suspends on `Dispatchers.IO` and returns a `Result`, so an unreachable or refusing
daemon is a value a screen can render.

`runIpc` reads the binder from the `StateFlow` **once** per call: checking one value for liveness and
invoking another is a race with the daemon dying. Failures are caught as `Exception`, not
`RemoteException` — a `SecurityException`, or a `RuntimeException` raised while unparcelling a large
`ParcelableListSlice`, is as reachable here as a dead binder, and any of them escaping fails the
calling coroutine, which in a `viewModelScope` takes the process down.

**A method the daemon does not have is not one of those failures.** The proxy sends a transaction
code the older `onTransact` does not recognise, reads back an empty reply, and returns the type's
default: `null`, an empty list, `0`. Nothing is thrown. That is why `ROOT_UNKNOWN` occupies `0` in
the AIDL rather than a real state — whatever sits at `0` is what an older daemon appears to say, and
`ROOT_NONE` sat there once, so a daemon too old to answer told a rooted user to install the root
manager they were already running. `getLogParts` is written the same way: an empty list from an old
daemon degrades to showing the live part alone.

Transaction ids in the AIDL are assigned explicitly (`= 2`, `= 3`, … `= 62`) rather than left
positional, so a method can be added without shifting the codes of the ones already there. Reusing or
renumbering one silently changes what an existing binary's call means; the defaulting behaviour above
is what makes that silence possible.

## Framework updates

The manager cannot run a privileged flash itself, so the daemon owns the install path:
`getRootImplementation`, `getRootImplementationVersion`, `installFrameworkZip`.

`RootImplementation` detects root by locating the implementation's own binary and running it —
`magisk -V`, `ksud -V`, `apd -V`, each tried first on `PATH` and then at its fixed location under
`/data/adb/` — rather than by looking for `su`. A binary that exists but exits non-zero counts as absent, which is what keeps a
leftover `/data/adb/magisk/magisk` from a previous root manager out of the answer. The path that
answered is remembered and reused for the flash. Two implementations answering at once is
`ROOT_MULTIPLE`, and the manager refuses rather than guessing which owns the module tree.

The flash runs through `ProcessBuilder` with an argument list, never a shell string, so a path can
never become a command. `ManagerService.installFrameworkZip` returns as soon as it has started a
thread named `vector-framework-install`; holding a binder thread for the seconds-to-minutes a flash
takes would starve everything else the manager asks meanwhile, including the log reads the install
screen is doing to show progress. Progress arrives as lines on an `IFrameworkInstallCallback`. If the
manager goes away mid-flash the install continues — stopping would leave the module tree half-written
— and the daemon's log becomes the only record.

**Two builds can share a version code.** `git rev-list --count` is always taken on
`refs/remotes/origin/master`, whatever is actually being built, so a branch build or a local one
wears whatever number master's tip happens to have. `BuildConfig.VERSION_HASH` (short commit, with a
`-dirty` marker) is what tells them apart, and the daemon answers the same question with
`getFrameworkCommit()`. A release's `target_commitish` is carried through and compared as well, so
when the codes match while the commits do not, the update screen says so instead of claiming the
device is up to date.

Every release publishes a Release zip and a Debug zip. Which one is flashed is the reader's choice,
presented as a segmented control with the size the release itself reports on each segment — the
troubleshooting flow elsewhere in this app asks people for a debug build, so the choice has to be
reachable.

## Module updates

Installing a module APK streams the download straight into a `PackageInstaller` session — no
temporary file, no `FileProvider`.

The consent story differs sharply between the two modes, which is why the app has a confirmation
dialog of its own. Inside `com.android.shell` the manager inherits `INSTALL_PACKAGES`, so the commit
installs a third-party APK with no system prompt whatsoever; standalone, the platform asks as usual.
`ConfirmInstall` checks `checkSelfPermission("android.permission.INSTALL_PACKAGES")` to tell which
mode it is in. In the mode most people run, Vector's dialog is the only consent gate there is, so it
names the module, the file and its size *before* anything is downloaded.

Whether a module is out of date is one answer shared by three screens — see `storeEntries` above.
Muting is folded into that answer rather than applied at each reader, because a mute only some of
them honoured would be worse than none. The two screens that show a module *by itself* deliberately
ignore it: someone who opened one module's page is asking, not being nagged.

Batch updates run one at a time on `appScope`. Sequential because concurrent sessions contend for the
same disk and, without `INSTALL_PACKAGES`, stack system dialogs in an order nobody chose; on
`appScope` because several modules take longer than anyone will hold a bottom sheet open.

The panel is told when an install lands rather than waiting to overhear it. A replaced package does
broadcast and `ServiceLocator.observePackageChanges` does listen, but delivery is the system's
business and this process is a guest in someone else's.

Deciding whether an installed package is a module means opening its APK *and its splits* as zips:
363 packages and 193 splits, roughly 550 zip opens, on the device this was measured against.
`ModuleDetectionCache` persists the answer in the cache directory, keyed by package, version code and
install time — the exact set of things whose change can change the answer.

## Log reader

The daemon rotates its logcat capture at 4 MB (`kMaxLogSize` in `logcat.cpp`). `LogcatMonitor` keeps
the ten most recent parts per stream and deletes the eldest, but that LRU lives in memory and is
rebuilt empty when the daemon restarts, so more than ten can be on disk. `FileSystem.listLogParts`
therefore reads the directory rather than the LRU, which is also what lets a manager opened after a
restart see the earlier history. `getLogPart` refuses any name that is not in the listing it just
produced, which rules out traversal by construction rather than by pattern-matching for `..`.

A naive reader that calls `readLines()` would retain several megabytes of `String` per stream inside
a process whose heap belongs to the host application. `data/log/LogFile.kt` therefore indexes rather
than loads: one sequential byte scan records the start offset of every line into a `LongArray`,
allocating no `String`, capped at `MAX_INDEXED_LINES` (400,000 — about 3.2 MB of offsets, and more
than ten times the lines in a full part). Filtering produces an `IntArray` of matching line numbers
and pages through that, so a filter costs one scan and no re-parse.

`LogsViewModel` holds at most `WINDOW` (2,000) rows around the viewport and pages outward as the
viewport approaches either edge. The window size is invariant, so peak memory is a function of
`WINDOW` alone and not of the file's size. Rows are keyed by absolute line number, which is what lets
the window extend upwards without the viewport lurching: the list re-resolves its first visible item
by key after rows are inserted above it.

## Colour generation

Android exposes no public API that converts a colour into a Material scheme; `dynamicColorScheme`
reads the wallpaper and nothing else. `ui/theme/SeedScheme.kt` generates one in CIE LCh — the same
principle as Google's HCT — by holding the seed's hue and chroma and walking L\* across the Material
tone scale.

The non-obvious part is gamut mapping. Most (lightness, hue) pairs cannot hold the seed's full chroma
in sRGB, so each tone binary-searches the highest chroma that converts in range. Clamping the
channels instead shifts hue as tones darken, which is why naive generators drift blue toward purple
down the ramp. The error ramp is fixed at a red hue regardless of the seed, so destructive actions do
not change meaning with the theme.

`ui/components/ColorWheel.kt` renders the hue/chroma disc by evaluating every pixel through the same
conversion, once per tone, off the main thread and cached as an `ImageBitmap`.

## Remote data

* **Store mirrors are two lists, deliberately.** The full `modules.json` is served by
  `backup.modules.lsposed.org` alone; `modules.lsposed.org` answers that path with 403. Per-module
  `module/<package>.json` *is* served by both, so `DETAIL_MIRRORS` has a genuine fallback and
  `LIST_MIRRORS` does not. Merging them would take the catalogue offline.
* **Freshness is declared per request.** The OkHttp disk cache is the offline story: when every
  mirror fails, the same request is replayed with `CacheControl.FORCE_CACHE`, so a cold start with no
  network renders the last known catalogue instead of an error.
* **DNS-over-HTTPS is a fallback, not a replacement.** `net/VectorDns.kt` attempts DoH, falls through
  to the system resolver on failure, latches that failure for the session so the timeout is paid
  once, and disables itself entirely when a proxy is configured. The setting is read per lookup,
  because OkHttp cannot have its DNS swapped on a live client and rebuilding the shared client would
  orphan the cache.
* **Activity feed.** `versionCode` equals `git rev-list --count`, so a commit's distance from the tip
  is its version number and the feed can name exactly which commits an update would bring, with no
  additional endpoint. The total commit count comes from the `Link: rel="last"` header and the
  repository statistics from a second request; both are cached in files of their own, because they
  are answers a cached read cannot reproduce.
* **Commit archive.** `/commits` returns at most a hundred per request, so a full history has to be
  walked backwards and kept. `data/github/CommitArchive.kt` is append-only NDJSON keyed by SHA:
  everything below the tip is immutable, so a chunk costs its own length rather than a rewrite, and
  the mutable head window is appended again with later lines winning on read.

  The walk is cursored on *date*, not page number — page numbers are relative to the tip and shift
  under any new commit — and on the **commit** date rather than the author date, because that is what
  `until` filters on, and about half of the newest hundred commits here differ, by as much as three
  weeks.

  A date cursor has one failure mode and this repository has it: 100+ commits share
  `2023-02-26T08:48:49Z`, and asking for commits at or before that second returns the same hundred
  forever. Inside such a plateau the walk pages by number, which is safe in exactly that position
  because the window is anchored by an `until` in the past. Completion is an *empty* page and nothing
  weaker; "nothing new" is what the plateau produces on every request, and "fewer than a hundred" is
  what a shared boundary second produces legitimately.

  `backfill` fetches three pages by default and leaves the cursor on disk. Sixty requests an hour is
  the anonymous budget, and a history that assembles over a few sessions is preferable to one that
  spends all of it on arrival.
* **Contributor resolution.** GitHub links commits to accounts by email and does not always succeed.
  A `@users.noreply.github.com` address encodes the account and needs only parsing. Otherwise the
  name is probed against `/users/{name}` *only if it is shaped like a handle* — containing a digit,
  hyphen or underscore — because `GET /users/Qing` returns a real and unrelated account, and
  crediting a contribution to a stranger is worse than leaving it uncredited.
* **Co-authors.** A `Co-authored-by:` trailer carries an address and no account, and no endpoint
  turns one into the other: the users search API refuses to index email and answers `total_count: 0`
  for a noreply address however it is phrased. But every commit GitHub *has* attributed is a verified
  email-to-login pair, and the archive is full of them, so trailers are resolved against history
  already in hand at no request cost. Names are indexed too, one tier weaker and first-wins.

## Canary distribution

`GET /actions/artifacts/<id>/zip` answers 401 to an anonymous caller; a release asset answers 206. CI
therefore attaches each `master` build to a `canary-<versionCode>` prerelease, and the canary screen
reads `/releases`. Prerelease, so `releases/latest` — which update checks read — keeps pointing at the
last stable tag. The five most recent canaries are kept, sorted by version code rather than by date,
since reruns and reverts can disorder dates. No account is required at any point, which matters for
users who cannot reach GitHub's sign-in at all.

Device-flow sign-in remains available and requests *no scopes* — it only raises the anonymous rate
limit from 60 to 5,000 requests an hour. When `BuildConfig.GITHUB_CLIENT_ID` is empty the app hides
sign-in entirely rather than presenting a control that cannot work.

## Build notes

* `githubClientId` is read with `providers.gradleProperty`, so set it in `~/.gradle/gradle.properties`
  or pass `-PgithubClientId=...`. It defaults to empty. **`local.properties` is not consulted for
  it** — AGP reads that file for the SDK location, and its contents never reach
  `providers.gradleProperty`.
* Kotlin comes from AGP 9's built-in support; applying `org.jetbrains.kotlin.android` here is an
  error. The *version* is pinned by the Kotlin plugin declared `apply false` in the root build,
  because AGP 9 otherwise supplies an older one, against which Coil's class metadata fails to load.
* Material 3 Expressive has not landed in a stable `material3` release, so `material3` is pinned
  above the Compose BOM rather than resolved from it, and the expressive opt-ins are declared once in
  `kotlin.compilerOptions` rather than sprinkled through every screen.
* This module's resources include `../daemon/src/main/res`, because `ic_launcher.xml` references a
  drawable that lives there. Any name collision between the two sets is a build error, so keep
  additions on the daemon side namespaced.
* The daemon compiles this module's signing certificate into `SignInfo.kt` and verifies the
  `manager.apk` it serves against it, so `:manager` must be signed with the same key as the rest of
  the module or `InstallerVerifier` rejects it. CI fails the run outright when the signing secret is
  missing on `master` or a tag; forks build unsigned and publish nothing.

## Running and verifying it

**Parasitically**, from a flashed module:

```
./gradlew :zygisk:installMagiskAndRebootDebug     # or installKsu… / installApatch…
```

The install tasks come in `push…Module`, `install…` and `install…AndReboot` forms for each of
`Magisk`, `Ksu` and `Apatch`, in `Debug` and `Release`. Once the device is up, the module's action
button — `zygisk/module/action.sh` — opens the manager with

```
am start -c org.matrix.vector.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity
```

which is the intent the hooker redirects. There is no launcher icon in this mode, because there is no
installed package to give one to.

**Standalone**, as an ordinary app:

```
./gradlew :manager:installDebug
adb shell am start -n org.matrix.vector.manager/.ui.MainActivity
```

The explicit component matters: `src/debug/AndroidManifest.xml` contributes a *second* launcher
activity (`demo.DemoActivity`), so a debug build has two, and launching the package by name is
ambiguous.

**The demo harness** lives in `src/debug/kotlin/.../demo/` and reaches states that cannot be produced
on a working phone: SELinux policy not loaded, the system server not injected, the dex2oat wrapper
unavailable, a framework below the API level installed modules need, no root implementation, two root
implementations fighting, one too old, an update available, a flash that dies halfway, every module a
version behind. `DemoScenario` lists them; `FakeManagerService` scripts the answers it has an opinion
about and delegates the rest to the real daemon.

It is a source set rather than a runtime flag. A demo mode that could be switched on in a release
build would be a way to make the manager report a healthy framework when it is not, which is the one
lie this app must never be able to tell; a reviewer can confirm by finding no
`org.matrix.vector.manager.demo` classes in a release APK.

`DemoActivity` renders `VectorApp()` itself rather than launching `MainActivity`. Launching it lets
`ParasiticManagerHooker` hand over the real binder a moment later, which silently undoes every
scenario — including "no daemon at all", which then comes up reporting a healthy framework. For the
same reason the activity re-asserts its chosen binder whenever something else replaces it, and
restores the real one on the way out.

The scenarios that lie about a *version* are the most useful, because that is what every update
decision is made against: the framework's version code comes from the daemon, and so do the installed
modules', so reporting an old one turns a real release into a real update with nothing else faked —
the catalogue, the release list, the APK and the install are all genuine.

## What is not here

**There is no test source set anywhere in this repository.** No `src/test`, no `src/androidTest`, no
`testImplementation` or `androidTestImplementation` in any build script; the only `test` directories
under this tree belong to vendored dependencies in `external/`. Nothing in this module is covered by
an automated test, and a change that compiles has been verified only by whoever ran it on a device.

CI (`.github/workflows/core.yml`) runs `./gradlew zipAll` and nothing else. It builds, packages,
uploads artifacts and mappings, and publishes canaries; it does not run a single assertion. Treat a
green tick as "it compiles and packages", never as "it works".
