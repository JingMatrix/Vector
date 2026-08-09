# Vector 框架的 Xposed API 实现

[英文文档（English）](README.md)

该模块为 Vector 框架实现了 [libxposed](https://github.com/libxposed/api) API，是 native ART hook 引擎
(`lsplant`) 与模块开发者之间的主桥梁，提供类似 OkHttp 拦截链的类型安全链式调用架构。

## 架构概览

`xposed` 模块采用严格边界设计，以确保 Android 启动与应用生命周期中的稳定性。
整个模块完全使用 Kotlin 编写，并独立于 legacy Xposed API（`de.robv.android.xposed`）运行。
它定义了一个依赖注入契约（`LegacyFrameworkDelegate`），由 `legacy` 模块在启动期实现并注入。

## 核心组件

### 1. Hook 引擎

*   **`VectorHookBuilder`**：实现 `HookBuilder` 接口。校验目标 `Executable`，将模块的 `Hooker`、`priority`、`ExceptionMode`
    组装为 `VectorHookRecord`，再通过 JNI 注册到 native。
*   **`VectorNativeHooker`**：JNI trampoline 目标。被 hook 方法执行时，C++ 层会调用该类的 `callback(Array<Any?>)`。
  它从 native 注册表读取（含现代与 legacy）活跃 hook，并作为全局 `jobject` 保持引用，构造根级 `VectorChain` 后启动执行。
*   **`VectorChain`**：实现递归 `proceed()` 状态机。
    *   **异常处理：** 实现 `ExceptionMode` 语义。`PROTECTIVE` 模式下，若拦截器在调用 `proceed()` 前抛异常，链会跳过该拦截器；
      若在调用 `proceed()` 后抛异常，则链会捕获并恢复下游缓存结果/异常，保护宿主进程稳定。

### 2. 调用系统

`Invoker` 系统让模块在跳过 JVM 标准访问检查的同时，精细控制 hook 执行。

*   **`Type.Origin`**：直接走 JNI（`HookBridge.invokeOriginalMethod`）执行原始逻辑，跳过全部 hook。
*   **`Type.Chain`**：构建仅包含优先级 <= 指定 `maxPriority` 的本地 `VectorChain`，用于“部分链路”执行。
*   **`VectorCtorInvoker`**：处理构造函数调用。它将内存分配（`HookBridge.allocateObject`）与初始化
    （`invokeOriginalMethod` / `invokeSpecialMethod`）拆分，可安全实现 `newInstanceSpecial`。

### 3. 依赖注入契约

为保持职责边界，`xposed` 与 legacy 生态通过 `VectorBootstrap` 与 `LegacyFrameworkDelegate` 通信。

当 `xposed` 拦截 Android 生命周期事件（如 `LoadedApk.createClassLoader`）时，先由 `VectorLifecycleManager` 在内部分发事件，
再把原始参数委派给 `LegacyFrameworkDelegate`，由 `legacy` 构建并派发 `XC_LoadPackage` 回调。

### 4. 内存加载与隔离

模块严格在内存中运行并使用独立 ClassLoader，既避免落盘，又最大程度减少被反作弊识别。

- 模块 APK 使用 `SharedMemory`（ashmem）加载。ART 解析 DEX 后，ashmem 会立即解除映射，避免内存泄漏并清空残留 fd。
- `VectorModuleClassLoader` 仅挂载到 Xposed Framework 的 classloader 分支，防止目标应用通过反射或
  `ClassLoader.getParent()` 链式遍历发现模块。
- `VectorURLStreamHandler` 拦截标准 `jar:` 请求，直接从模块路径读取 assets 和资源，避免触发 Android 全局 `JarFile` 缓存，降低系统锁与可见性。
