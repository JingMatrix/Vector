# Vector Daemon Subsystem

The Vector daemon is a standalone, root-privileged Dalvik executable bootstrapped via `app_process`. Operating entirely outside the standard Android application sandbox, it serves as the central coordinator, state manager, and inter-process communication (IPC) asset server for the Vector framework. 

Target processes operating under strict Android sandbox and SELinux constraints cannot safely access external configuration files or SQLite databases. The daemon offloads these operations, providing an IPC backend that serves memory-mapped resources, configuration states, and native file descriptors to target applications securely and efficiently.

## Directory Structure

The daemon is organized into discrete packages handling IPC, state management, OS interfacing, and native environments.

```text
src/main/
├── jni/                      # Native C++ implementations (dex2oat wrapper, logcat parser)
└── kotlin/org/matrix/vector/daemon/
    ├── data/                 # SQLite schema, immutable state cache, and file operations
    ├── env/                  # UNIX domain socket servers and native process monitors
    ├── ipc/                  # AIDL endpoints (Application, Manager, Module, SystemServer)
    ├── system/               # System binder delegates and Notification UI
    ├── utils/                # Context forgery, signature verification, and JNI bridges
    ├── Cli.kt                # Command-line interface definitions
    ├── VectorDaemon.kt       # Main entry point and looper initialization
    └── VectorService.kt      # Primary IDaemonService implementation
```

## Concurrency and State Management

To handle concurrent IPC requests without starving Android Binder thread pools, the daemon separates background I/O operations from state reads.

* Immutable State Container: The `DaemonState` data class holds a frozen snapshot of all enabled modules and process scopes. IPC threads read from this object without acquiring locks.
* Atomic Swaps: When the underlying SQLite database changes, the daemon triggers a conflated channel request. A background coroutine queries the database, computes the new module topology, instantiates a new `DaemonState`, and atomically swaps the reference in `ConfigCache`.
* Preference Isolation: High-frequency module preference reads and writes are decoupled from the core state. Managed by `PreferenceStore`, preferences are serialized as binary blobs and pushed as differential updates to modules, preventing unnecessary cache rebuilds.

## IPC Architecture

The daemon implements a multi-layered IPC design utilizing Android's Binder mechanism and UNIX domain sockets. It avoids registering standard AIDL services with `ServiceManager`, relying instead on intercepting Binder transactions via the Zygisk module and actively pushing Binder references to target processes.

### 1. System Server Bootstrapping
During device boot, the daemon establishes a communication channel with the native Vector Zygisk module residing in `system_server`.

* The daemon registers an `IServiceCallback` to listen for the registration of a hardware proxy service (typically the `serial` service). Once intercepted, the daemon replaces the proxy service with its own binder.
* The Zygisk module queries this proxy service to retrieve the framework loader DEX via `SharedMemory` and the class obfuscation map.
* Concurrently, the daemon sends a raw `ACTION_SEND_BINDER` transaction to the `activity` service. The Zygisk module's JNI hook intercepts this transaction before it reaches the Activity Manager, extracting and storing the daemon's primary `VectorService` binder for future use.

### 2. Target Application Rendezvous
When a standard user application spawns, it requests framework access from the daemon.

* The target application queries the `activity` service. The Zygisk module inside `system_server` intercepts this query.
* The `system_server` forwards the application's UID, PID, process name, and a newly created heartbeat `BBinder` to the daemon using the previously stored `VectorService` reference.
* The daemon verifies the request against its `ConfigCache` to determine if the application is within the scope of any enabled modules.
* If approved, the daemon returns an `ApplicationService` binder, which the `system_server` passes back to the target application.
* The daemon links a `DeathRecipient` to the heartbeat binder to automatically clean up internal tracking maps when the application process dies.
* The target application uses the `ApplicationService` binder to fetch its specific module list, framework DEX, and obfuscation map.

### 3. Libxposed Module Injection
Unlike target applications which request access, the daemon actively pushes its API binder to module processes. This mechanism is strictly limited to modules utilizing the modern libxposed API.

* The daemon registers an `IUidObserver` with the Activity Manager to monitor process lifecycles.
* When a UID becomes active, `ModuleService` checks if the UID belongs to an enabled libxposed module.
* The daemon retrieves an `IXposedService` binder. To deliver it, the daemon calls `IActivityManager.getContentProviderExternal`, targeting a synthetic authority constructed from the module's package name.
* The daemon executes `IContentProvider.call` with the action `SEND_BINDER` and a `Bundle` containing the binder. This injects the binder into the module's process space before `Application.onCreate` executes, providing access to API verification, scope requests, and remote preferences.

### 4. Native Socket IPC
For native components that operate outside the Java Binder context, the daemon provisions two distinct types of UNIX domain sockets.

* Command-Line Interface: The `CliSocketServer` exposes a filesystem-based socket at `/data/adb/lspd/.cli_sock`. The CLI client authenticates using a compiled-in UUID token and communicates using structured JSON. For live log streaming, the daemon attaches the log file's raw `FileDescriptor` to the socket reply payload, allowing the client to read directly from the OS-level stream buffer.
* Dex2Oat Wrapper: The `Dex2OatServer` listens on an abstract UNIX domain socket. To prevent conflicts and detection, the exact name of this abstract socket is randomized during module installation. The C++ `dex2oat` wrapper connects to this socket to receive necessary file descriptors via `SCM_RIGHTS`.

## Native Environment Subsystems

The daemon relies on native C++ subsystems to intercept Android's compilation pipeline and parse system log buffers directly, avoiding the overhead and limitations of standard shell utilities.

### AOT Compilation Hijacking

Android's ART compiler aggressively inlines methods, which permanently prevents those methods from being hooked at runtime. To enforce the `--inline-max-code-units=0` flag system-wide, Vector utilizes a C++ binary wrapper mounted over the system's `dex2oat` and `dex2oat64` binaries. 

The daemon manages this interception entirely through its native JNI layer. To ensure the replaced compiler binaries are globally visible to all newly spawned application processes, the daemon forks a privileged child process and uses `setns` with `CLONE_NEWNS` to enter the `init` (`PID 1`) mount namespace via `/proc/1/ns/mnt`. It then performs read-only bind mounts (`MS_BIND | MS_REMOUNT | MS_RDONLY`) over the target compiler binaries located in the `/apex` mount points.

When the wrapper executes, it connects to the daemon's abstract UNIX domain socket to retrieve the original compiler binary and the hooking library (`liboat_hook.so`) via `SCM_RIGHTS`. To guarantee the wrapper can connect without SELinux denials, the daemon dynamically writes to `/proc/self/task/[tid]/attr/sockcreate` before binding the socket. This instructs the kernel to label the abstract socket with a specific context, such as `u:r:dex2oat:s0` or `u:r:installd:s0`, matching the strict domains under which the compiler operates.

If the wrapper is disabled or incompatible, the daemon unmounts the binaries and utilizes `resetprop` to inject the inline flag directly into the `dalvik.vm.dex2oat-flags` system property as a fallback. The Kotlin daemon continuously monitors SELinux states via a `FileObserver` on `/sys/fs/selinux/enforce` and its policy files. It dynamically remounts the wrappers if the system drops to permissive mode or alters policy, ensuring the interception persists across state changes.

### Native Logcat Monitoring
Instead of relying on standard logcat shell execution, the daemon runs a native C++ process that interfaces directly with Android's `liblog` buffers (`LOG_ID_MAIN` and `LOG_ID_CRASH`). 

The native parser performs zero-copy processing of log events, strictly filtering output by predefined exact tags (e.g., Magisk, KernelSU) and prefix tags (e.g., dex2oat, Vector, LSPosed). It writes the filtered output into two rotating log files: one for module frameworks and one for verbose system debugging, rotating them automatically when they reach 4MB.

To control this isolated native loop, the Kotlin daemon injects specific string triggers (such as `!!refresh_modules!!` or `!!start_verbose!!`) directly into the Android log stream. The C++ parser intercepts these specific messages originating from its own parent PID and dynamically rotates its file descriptors or alters its verbosity state without requiring additional IPC overhead.
