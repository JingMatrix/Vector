# 外部依赖说明

[英文文档（English）](README.md)

本目录包含构建 Vector 框架所需的全部外部依赖。全部通过 git submodule 管理，以保证版本一致性和可控更新。

## Native 依赖

-   [Dobby](https://github.com/JingMatrix/Dobby): 
    轻量级跨平台 inline hook 框架。它是所有 native 函数 hook（`HookInline`）的底层实现。

-   [fmt](https://github.com/fmtlib/fmt):
    现代化的格式化库，用于 native 代码中的高性能、类型安全日志输出。

-   [LSPlant](https://github.com/JingMatrix/LSPlant):
    Android Runtime（ART）hook 框架，提供核心能力以拦截并修改 Java 方法。

-   [xz-embedded](https://github.com/tukaani-project/xz-embedded):
    轻量级压缩库，常驻内存占用低。ELF 解析器使用它解压 stripped 库中的 `.gnu_debugdata`。

-   [LSPlt](https://github.com/JingMatrix/LSPlt):
    PLT（Procedure Linkage Table）hook 库。用于 `dex2oat` 子项目中的某个检测点绕过。**说明：** 该依赖作为子模块引入仅为项目便利，
    本身不作为 `external` 的 C++ 库进行编译。

## Java 依赖

-   [apache/commons-lang](https://github.com/apache/commons-lang):
    Java 工具类集合，部分类用于实现 `java.lang` 体系下相关功能；部分类重命名后用于实现 `XposedHelpers` API。

-   [axml/manifest-editor](https://github.com/JingMatrix/ManifestEditor):
    Android Manifest 二进制解析/修改工具，用于解析 Xposed 模块的 manifest 文件。
