# Vector Zygisk 模块与框架加载器

[英文文档（English）](README.md)

该子系统是 Vector 的注入引擎。它连接 Android Zygote 进程与上层 Java/Kotlin Xposed API，
架构上避免了标准 Android 服务注册和基于磁盘的类加载，完全依赖内存执行、JNI 级 Binder 拦截与进程身份注入。

系统划分为两层：
1. _Native Zygisk 层_（C++）：通过 Zygisk Hook 进程创建、过滤目标进程，建立初始 IPC 桥并从内存引导 Dalvik 环境。
2. _Framework Loader_（Kotlin）：处理高层框架注入、管理自定义 Binder 路由服务，并编排寄生式 Manager 的运行。

## IPC 架构与 Binder 转发

Vector 采用两阶段 IPC 路由机制，在被注入的应用与 root daemon 之间建立通信。它不在 `ServiceManager` 注册标准 AIDL 端点，
而是在 Dalvik VM 层拦截 Java Binder 的底层通信。

### JNI Binder Trap

在 `ipc_bridge.cpp` 中，模块调用 ART 内部函数 `SetTableOverride` 替换 JNI 函数 `CallBooleanMethodV`。
该覆写会在系统范围内劫持对 `android.os.Binder.execTransact` 的调用。

当事务发生时，Hook 会检查事务码；若匹配常量 `kBridgeTransactionCode`（`_VEC`），则将调用转发到 Kotlin 静态方法
`BridgeService.execTransact`；其他事务保持原样透传 Android 框架。

### 阶段一：System Server 初始化

`system_server` 是框架的主代理路由。`postServerSpecialize` 回调中的流程如下：

1. Native 模块先向 `ServiceManager` 查询 `serial` 服务（延迟注入场景下为 `serial_vector`），该服务作为临时接驳点。
2. 模块发送 `_VEC` 事务获取临时 binder，进而取回框架 DEX fd 与混淆映射表。
3. 模块安装 JNI Binder Trap（`HookBridge`）并通过 `Main.forkCommon` 引导 Kotlin 层。
4. 同时，root daemon 会直接向 `system_server` 发起 Binder 事务。JNI trap 拦截该事务后，`BridgeService` 处理 `SEND_BINDER` 动作，
   保存 daemon 的主 `IVectorDaemon` binder，并回写 `system_server` 上下文并绑定 `DeathRecipient`。

### 阶段二：用户应用握手

标准应用通过 `system_server` 建立 IPC 连接：

1. `postAppSpecialize` 中，应用向 `ServiceManager` 查询 `activity` 服务（位于 `system_server`）。
2. 应用发送 `_VEC` 事务，包含 `GET_BINDER` 动作、进程名与新分配 heartbeat `BBinder`。
3. 位于 `system_server` 的 JNI Trap 在 Activity Manager 处理前拦截该事务。
4. `system_server` 的 BridgeService 将应用 UID、PID 与 heartbeat binder 通过阶段一获得的 `IVectorDaemon` binder 发送给 daemon。
5. daemon 按内部作用域状态校验后，生成 `IFrameworkService` binder 并返回到 `system_server`，后者写回应用的 reply parcel。
6. 应用使用该专用 binder 获取自身框架 DEX 与混淆映射。

### 心跳机制

为避免轮询管理进程生命周期，native 模块在两个阶段都会创建一个虚拟 Binder 对象（`heartbeat_binder`）并传入 daemon。
该对象在应用进程中由 JNI 全局引用（`env->NewGlobalRef`）持有；若应用或 `system_server` 正常退出或被内核杀死，
全局引用会被销毁，binder 节点释放，daemon 的 `DeathRecipient` 立即触发资源清理。

## 内存执行与混淆同步

Vector 不向 `/data` 分区写入框架代码。

1. 资源分发：root daemon 通过 `kDexTransactionCode` 提供 framework DEX（SharedMemory fd）给 daemon；
   C++ 层再将该 fd 包装成 `java.nio.DirectByteBuffer` 并初始化 `dalvik.system.InMemoryDexClassLoader`。
2. 动态重链：daemon 每次启动都会随机化框架类名。native 模块通过 IPC 读取序列化字典，使用 `kObfuscationMapTransactionCode` 获取；
   `SetupEntryClass` 依赖该映射定位随机化入口点（如 `org.matrix.vector.core.Main`）和 BridgeService，保证运行期正确链接。

## 寄生式 Manager 与身份注入

Vector Manager 不是标准安装应用，而是以寄生模式注入到宿主进程（如 `com.android.shell`）运行。

### System Server 意图改写

在 `system_server` 内，`ParasiticManagerSystemHooker` 拦截 `ActivityTaskSupervisor.resolveActivity`。
当识别到带有 `LAUNCH_MANAGER` 分类的 Intent 时，它会动态改写返回的 `ActivityInfo`，
强制系统拉起宿主包，同时将 `processName` 设为 Manager 包名，并调整主题/最近任务标志以模拟独立应用。

### 应用宿主接管

native 模块在 `preAppSpecialize` 检测到宿主 UID 与 Manager 进程名后，会向进程 GID 列表注入 `GID_INET`（3003）以确保联网权限。
随后把控制权交给 `ParasiticManagerHooker.kt`，进行身份移植：

1. 代码注入：拦截 `LoadedApk.getClassLoader` 与 `ActivityThread.handleBindApplication`，将宿主 `ApplicationInfo` 替换为基于管理 APK 的混合对象（由 fd 提供），并将管理端 DEX 注入到宿主 `PathClassLoader`。
2. 状态伪造：系统 ActivityManager 不知晓伪造的 Manager 活动。为防生命周期转场丢失状态（如旋转屏幕），Hooker 拦截 `performStopActivityInner`，将 `Bundle` 与 `PersistableBundle` 状态手动落到静态并发 map，并在 `scheduleLaunchActivity` 中回注入。
3. 上下文伪造：拦截 `ActivityThread.installProvider` 与 `WebViewFactory.getProvider`，构造伪造的 `ContextImpl`，绕过 Android 与 Chromium 内部包名校验。
