# Vector 框架

[英文文档（English）](README.md)

**面向现代 Android 的高性能 ART Hook 框架**

<div align="center">

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/Vector/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix)
[![Download](https://img.shields.io/github/v/release/JingMatrix/Vector?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/Vector/releases/latest)
[![Total](https://shields.io/github/downloads/JingMatrix/Vector/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/Vector/releases)

</div>

---

### 简介

Vector 是一个 Zygisk 模块，提供与原始 Xposed API 保持兼容的 ART Hook 框架。它基于
[LSPlant](https://github.com/JingMatrix/LSPlant) 构建，旨在提供稳定的原生级插桩环境。

该框架允许模块在内存中修改系统和应用行为。由于不会修改任何 APK 文件，修改是非破坏性的，重启即可轻松回滚，并且在不同 ROM 与 Android 版本间具有兼容性。

---

### 兼容性

Vector 支持运行 **Android 8.1 到 Android 17 Beta** 的设备。

> [!TIP]
> 本框架要求安装最近版本的 Magisk 或 KernelSU，并启用 Zygisk。

---

### 安装

1. 下载最新发布版本作为系统模块。
2. 通过你的 root 管理器（Magisk/KernelSU）安装该模块。
3. 确保 Zygisk 环境可用（例如 [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)）。
4. 重启设备。
5. 通过系统通知进入管理设置页。

---

### 下载

| 渠道 | 来源 |
| :--- | :--- |
| **稳定版（Stable Releases）** | [GitHub Releases](https://github.com/JingMatrix/Vector/releases) |
| **Canary（CI）构建** | [GitHub Actions](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=branch%3Amaster) |

> [!NOTE]
> 如果你正在遇到问题或进行故障排查，建议使用 Debug 构建。
> 我们鼓励用户测试 CI 构建，帮助我们快速定位 bug 并加快开发。

> [!CAUTION]
> GitHub 要求用户登录后才能下载 CI 构建产物。
>
> 上述链接仅显示 `master` 分支的构建。
> 注意，Pull Request（PR）构建通常不稳定且可能不安全（取决于贡献者），建议除非应 debug 调试要求，否则始终使用
> `master` 分支构建。

---

### 支持与贡献

如果你遇到问题，或希望帮助改进项目，请查看以下资源：

*   **故障排查：** 先阅读 [指南](https://github.com/JingMatrix/Vector/issues/123) 再提交 Bug。
*   **讨论区：** 在 [GitHub Discussions](https://github.com/JingMatrix/Vector/discussions) 参与讨论。
*   **本地化：** 通过 [Crowdin](https://crowdin.com/project/lsposed_jingmatrix) 帮助项目翻译。

> [!IMPORTANT]
> 仅接受基于 **最新版 Debug 构建** 的 Bug Report。
>
> *给中文用户的说明：*
>
> 为提高沟通效率，本项目仅接受英文 Issue。请使用 [DeepL](https://www.deepl.com/zh/translator) 或其他翻译工具提交反馈。

---

### 开发者资源

Vector 同时支持旧版与现代 Hook 标准，以保证更广泛的模块兼容性。

*   [Legacy Xposed API](https://api.xposed.info/)
*   [Modern libxposed API](https://libxposed.github.io/api/)
*   [Xposed Module Repository](https://github.com/Xposed-Modules-Repo)

> [!NOTE]
> Vector 通过两个 git 子模块提供 `libxposed` API：[module API](./xposed/) 与 [service API](./services/)。
>
> 成功通过 [master](https://github.com/JingMatrix/Vector/tree/master) 分支的 GitHub Actions 构建表示 Vector 在该提交点对这些 API 提供完整支持。
> 建议开发者同步检出与 Vector 相同提交。

---

### 致谢

本项目基于以下开源贡献完成：

*   [Magisk](https://github.com/topjohnwu/Magisk/): Android 客户化定制的基础。
*   [LSPlant](https://github.com/JingMatrix/LSPlant): 核心 ART Hook 引擎。
*   [XposedBridge](https://github.com/rovo89/XposedBridge): 标准的 Xposed API。
*   [Dobby](https://github.com/JingMatrix/Dobby): Inline Hook 实现。
*   [LSPosed](https://github.com/LSPosed/LSPosed): 上游源代码。
*   [EdXposed](https://github.com/ElderDrivers/EdXposed): LSPosed 之前的上游源。
*   [xz-embedded](https://github.com/tukaani-project/xz-embedded): 库解压工具库。

<details>
<summary>旧版和历史依赖</summary>

- ~~[Riru](https://github.com/RikkaApps/Riru)~~
- ~~[SandHook](https://github.com/ganyao114/SandHook/)~~
- ~~[YAHFA](https://github.com/rk700/YAHFA)~~
- ~~[dexmaker](https://github.com/linkedin/dexmaker)~~
- ~~[DexBuilder](https://github.com/LSPosed/DexBuilder)~~
</details>

---

### 许可证

Vector 采用 [GNU 通用公共许可证 v3](http://www.gnu.org/copyleft/gpl.html)。
