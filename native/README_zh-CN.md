# Vector Native 库（`native`）

[英文文档（English）](README.md)

## 目标与设计理念

该库提供 Android 系统级别的底层 hook 与修改能力。它不是独立应用，而是一组组件集合，通常需嵌入到更大的加载机制中使用（例如 Zygisk 模块）。

## 架构拆分

库内部按职责划分为清晰模块。

### `core` - 抽象引擎

该模块定义核心抽象并管理运行时状态，是整个库的“逻辑中枢”。

-   **`Context`**：注入生命周期基类。包含 `LoadDex` 与 `SetupEntryClass` 等纯虚方法。库使用方（如 `Zygisk` 模块）需继承该类并提供具体实现。
-   **`ConfigBridge`**：native 侧单例缓存，用于存储由使用方提供的配置（尤其是 class 混淆映射）数据。
-   **`native_api`**：实现 native 模块支持体系。通过 hook 系统的 `do_dlopen`，在发现已注册模块库加载时，调用其 `native_init` 并注入创建 native hook 所需的一组
   [API](include/core/native_api.h)。

### `elf` - 符号解析

该模块负责运行时符号查找，是 native hook 的关键路径。

-   **`ElfImage`**：解析当前进程映射内存中的 ELF 文件。可在去除符号表的二进制中定位 `.gnu_debugdata` 段并解压（使用 `xz-embedded`），解析后按
  GNU hash -> ELF hash -> 线性扫描的顺序查找符号。
-   **`ElfSymbolCache`**：线程安全的 lazy cache，管理 `ElfImage` 实例；用于安全访问常用库（如 `libart.so`、`linker`）。

### `jni` - 业务逻辑接口

这是最核心的模块，也是库的主要服务层。它包含一组 JNI bridge，将核心能力暴露给 Java 框架端。

-   **`jni_bridge.h`**：提供辅助宏（`VECTOR_NATIVE_METHOD`、`REGISTER_VECTOR_NATIVE_METHODS` 等），用于标准化和简化 JNI 样板代码。
-   **`HookBridge`**：ART 方法 hook 引擎，维护全部活跃 hook 的线程安全映射。包含稳定性控制，如用原子操作设置备份 trampoline，
  当调用失败 hook 的原始方法时抛 Java 异常而非触发 native 崩溃。
-   **`ResourcesHook`**：支持运行时拦截并改写 Android 二进制 XML 资源，依赖未公开的 `libandroidfw.so` 结构并通过 `elf` 模块定位运行时符号。
-   **`NativeApiBridge`**：`core/native_api` 的 JNI 对应实现，向 Java 框架暴露注册第三方 native 模块文件名的方法。

### `common` 与 `framework`

-   **`common`**：提供基础工具集合，包括基于 `fmt` 的日志、全局常量及常用辅助函数。
-   **`framework`**：提供最小化 C++ 结构体定义，镜像 Android 内部 `libandroidfw.so` 的定义，以便正确解析资源数据指针。

## 3. 构建系统

该库使用 CMake 构建为 **静态库（`libnative.a`）**。所有外部依赖也以静态方式链接，以获得更高移植性。
