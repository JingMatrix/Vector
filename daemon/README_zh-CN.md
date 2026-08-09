# Vector Daemon 子系统

[英文文档（English）](README.md)

Vector daemon 是一个独立的、具 root 权限的 Dalvik 可执行程序，通过 `app_process` 引导启动。
它完全运行在标准 Android 应用沙箱之外，承担 Vector 框架的中心协调、状态管理以及 IPC 资产服务。

在严格的 Android 沙箱和 SELinux 约束下，目标进程不能安全访问外部配置文件或 SQLite 数据库。
daemon 代替这些操作，提供 IPC 后端，向目标应用安全高效地下发内存映射资源、状态与 native 文件描述符。

## 目录结构

daemon 按职责划分为多个包，覆盖 IPC、状态管理、系统交互与 native 环境。

```text
src/main/
├── jni/                      # Native C++ 实现（dex2oat wrapper、logcat parser）
└── kotlin/org/matrix/vector/daemon/
    ├── data/                 # SQLite schema、不可变状态缓存、文件操作
    ├── env/                  # UNIX 域套接字服务器与 native 进程监听
    ├── ipc/                  # AIDL 端点（Framework、Manager、ModuleApp、InjectedModule、SystemServer）
    ├── system/               # 系统 binder 代理与通知 UI
    ├── utils/                # Context 伪装、签名校验与 JNI 桥
    ├── Cli.kt                # 命令行接口定义
    ├── VectorDaemon.kt       # 主入口与 looper 初始化
    └── VectorService.kt      # 主要的 IVectorDaemon 实现
```

## 并发与状态管理

为避免并发 IPC 请求耗尽 Android Binder 线程池，daemon 将后台 I/O 与状态读取分离。

* 不可变状态容器：`DaemonState` 数据类持有一个冻结快照，包含全部启用模块与进程作用域。IPC 线程读取该对象无需加锁。
* 原子替换：底层 SQLite 变更后，daemon 会触发一次 conflated channel 请求。后台协程查询数据库，计算新模块拓扑，创建新的 `DaemonState`，并原子性替换 `ConfigCache` 中的引用。
* 偏好隔离：高频模块偏好读写与核心状态分离，由 `PreferenceStore` 管理。偏好被序列化为二进制 blob，并以差量更新方式下发模块，避免不必要的缓存重建。

## IPC 架构

daemon 实现了多层 IPC 设计，使用 Android Binder 与 UNIX 域套接字。它不向 `ServiceManager` 注册标准 AIDL 服务，而是通过 Zygisk 模块拦截 Binder 事务，
并主动将 Binder 引用推送给目标进程。

### 1. System Server 初始化

设备启动期间，daemon 与驻留在 `system_server` 的本地 Vector Zygisk 模块建立通信。

* daemon 注册 `IServiceCallback`，监听硬件代理服务（通常是 `serial` 服务）的注册；一旦被拦截，daemon 使用自身 binder 替换该代理服务。
* Zygisk 模块向该代理请求框架加载 dex 与混淆映射，通过 `SharedMemory` 获取 `framework loader DEX` 和类混淆映射表。
* 同时，daemon 向 `activity` 服务发送原始 `ACTION_SEND_BINDER` 事务。Zygisk 的 JNI hook 在该事务进入 Activity Manager 前拦截并提取、保存 daemon 主入口 `VectorService` 的 binder，以备后续使用。

### 2. 目标应用握手

标准用户应用启动时会向 daemon 请求框架访问。

* 目标应用查询 `activity` 服务。位于 `system_server` 的 Zygisk 模块会拦截该查询。
* `system_server` 利用已保存的 `VectorService` 引用，将应用的 UID、PID、进程名以及新建的 heartbeat `BBinder` 发送给 daemon。
* daemon 基于 `ConfigCache` 校验该应用是否在任意已启用模块的作用域内。
* 若通过验证，daemon 返回 `FrameworkService` binder，由 `system_server` 回写给目标应用。
* daemon 将 `DeathRecipient` 挂载到 heartbeat binder；当应用进程退出时自动清理内部追踪表。
* 目标应用使用 `FrameworkService` binder 获取其模块列表、框架 DEX 与混淆映射。

### 3. Libxposed 模块注入

与目标应用主动请求不同，daemon 会主动把 API binder 推送到模块进程。该机制仅对使用现代 libxposed API 的模块生效。

* daemon 向 Activity Manager 注册 `IUidObserver`，监听进程生命周期。
* 当 UID 变为 active，`ModuleAppService` 检查该 UID 是否属于启用中的 libxposed 模块。
* daemon 获取 `IXposedService` binder。为交付该 binder，daemon 调用 `IActivityManager.getContentProviderExternal`，
  目标 authority 为模块包名构造的合成字符串。
* daemon 在 `IContentProvider.call` 中发送 `SEND_BINDER` 动作与包含 binder 的 `Bundle`；这会在 `Application.onCreate` 执行前把 binder 注入模块进程，进而提供 API 校验、作用域请求与远程偏好能力。

### 4. Native Socket IPC

对于不在 Java Binder 上下文内运行的 native 组件，daemon 会创建两种不同的 UNIX 域套接字。

* 命令行接口：`CliSocketServer` 在 `/data/adb/lspd/.cli_sock` 暴露基于文件系统的 socket。CLI 客户端使用内置 UUID 令牌认证，通过结构化 JSON 通讯。对于实时日志流，
  daemon 会在响应中附带日志文件原始 `FileDescriptor`，客户端可直接从 OS 级缓冲读取。
* Dex2Oat Wrapper：`Dex2OatServer` 监听抽象 UNIX socket。为避免冲突与检测，安装模块时该抽象 socket 名会随机化。C++ 的 `dex2oat`
  wrapper 通过 `SCM_RIGHTS` 连接该 socket 获取执行所需描述符。

## Native 环境子系统

daemon 借助 native C++ 子系统直接拦截 Android 编译链路并解析系统 log 缓冲，避免使用常规 shell 工具带来的额外开销与限制。

### AOT 编译劫持

Android ART 编译器会激进地进行内联，导致内联方法在运行期无法被 hook。为了全局强制使用
`--inline-max-code-units=0`，Vector 挂载了覆盖系统 `dex2oat` 与 `dex2oat64` 的 C++ wrapper。

daemon 完全通过 native JNI 层管理这套拦截。为确保替换后的编译器对新进程可见，daemon 会 fork 一个有权限的子进程，并通过 `setns` + `CLONE_NEWNS`
进入 `init`（PID 1）挂载命名空间（通过 `/proc/1/ns/mnt`）。随后它在 `/apex` 下的目标编译器二进制上执行只读绑定挂载
（`MS_BIND | MS_REMOUNT | MS_RDONLY`）。

wrapper 运行时会连接 daemon 的抽象 UNIX socket，通过 `SCM_RIGHTS` 拉取原始编译器与 hook 库（`liboat_hook.so`）。
为保证无 SELinux 拒绝，daemon 在绑定 socket 前会动态写入 `/proc/self/task/[tid]/attr/sockcreate`，
告诉内核为该抽象 socket 打上特定上下文（如 `u:r:dex2oat:s0` 或 `u:r:installd:s0`），与编译器的严格 domain 匹配。

若 wrapper 被禁用或不兼容，daemon 会卸载对应二进制，并通过 `resetprop` 直接把 inline flag 写入
`dalvik.vm.dex2oat-flags` 系统属性作为回退。Kotlin daemon 通过监听 `/sys/fs/selinux/enforce` 与策略文件的 `FileObserver` 持续监控 SELinux 状态；
当系统进入宽松模式或策略变化时自动重挂载 wrapper，确保拦截链路持续生效。

### Native Logcat 监控

daemon 不再依赖标准 logcat shell 命令，而是直接通过 C++ 进程对 Android `liblog` 缓冲区（`LOG_ID_MAIN` 与 `LOG_ID_CRASH`）进行接口层解析。

native 解析器对日志事件进行零拷贝处理，仅保留预定义精确 tag（如 Magisk、KernelSU）和前缀 tag（如 dex2oat、Vector、LSPosed）输出。最终会写入两类轮转文件：
模块框架日志与系统 verbose 调试日志，在达到 4MB 后分别自动切换。

为了控制这条隔离的 native 循环，Kotlin daemon 会向 Android log 流注入触发字符串（如 `!!refresh_modules!!`、`!!start_verbose!!`）。
该 C++ 解析器拦截自父进程 PID 发出的这些消息后，无需额外 IPC 开销即可动态切换文件描述符或日志级别。
