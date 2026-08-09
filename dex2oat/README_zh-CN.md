# VectorDex2Oat

[英文文档（English）](README.md)

VectorDex2Oat 是面向 Android `dex2oat`（Ahead-of-Time 编译器）二进制的专项 wrapper 与插桩套件。它用于拦截编译流程，
强制特定编译行为（主要是禁用方法内联），并在产物 OAT 元数据中进行透明伪造，以隐藏 wrapper 存在痕迹。

## 概述

在 Android Runtime（ART）中，`dex2oat` 会把 DEX 文件编译为 OAT。现代 ART 优化会频繁进行内联，导致插桩工具难以 hook 到具体函数调用。

该工程由两个核心组件组成：
1. **dex2oat（Wrapper）**：一个替代二进制，拦截执行流程，通过 Unix Domain Socket 获取原始编译器并以强制参数执行。
2. **liboat_hook.so（Hooker）**：通过 `LD_PRELOAD` 注入到 `dex2oat` 进程的共享库，使用 PLT hook 清理最终 OAT 元数据中的 wrapper 迹象。

## 关键特性

*   **抑制内联：** 向编译参数追加 `--inline-max-code-units=0`，确保方法在运行期保持可 hook。
*   **FD 驱动执行：** 通过 `/proc/self/fd/` 路径从系统 linker 执行原始 `dex2oat`，避免直接执行磁盘文件。
*   **元数据伪造：** 拦截 `art::OatHeader::ComputeChecksum` 或 `art::OatHeader::GetKeyValueStore`，移除最终 `.oat` 中 wrapper 与注入参数痕迹。
*   **抽象 Socket 通信：** 使用 Linux Abstract Namespace 协调 wrapper 与控制端的文件描述符传递。

## 架构

### Wrapper [dex2oat.cpp](src/main/cpp/dex2oat.cpp)

wrapper 充当编译器前置代理，工作流程如下：

1. 连接预定义的 Unix socket（安装 Vector 时会替换占位名 `5291374ceda0...`）。
2. 识别目标架构（32/64 位）与调试状态。
3. 获取原始 `dex2oat` 与 `oat_hook` 库的两个文件描述符（FD）。
4. 重组命令行，将 wrapper 路径替换为原始二进制路径并追加 `--inline-max-code-units=0`。
5. 清空 `LD_LIBRARY_PATH`，把 `LD_PRELOAD` 指向 hooker 库 FD。
6. 通过动态 linker（`linker64`）执行编译器。

### Hooker [oat_hook.cpp](src/main/cpp/oat_hook.cpp)

hooker 库在编译器进程地址空间中预加载。它通过 [LSPlt](https://github.com/JingMatrix/LSPlt)：

1. 扫描内存映射定位 `dex2oat` 二进制；
2. 查找并 Hook ART 内部函数：
    * [art::OatHeader::GetKeyValueStore](https://cs.android.com/android/platform/superproject/+/android-latest-release:art/runtime/oat/oat.cc;l=366)
    * [art::OatHeader::ComputeChecksum](https://cs.android.com/android/platform/superproject/+/android-latest-release:art/runtime/oat/oat.cc;l=366)
3. 当编译器尝试向 OAT header 写入 `dex2oat-cmdline` 时，hooker 拦截该调用、解析 key-value store，并移除 wrapper 专用 flags 与路径。
