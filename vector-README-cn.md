<div align="center">

# Vector 框架

**一个为现代安卓而建立的高效框架**

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/Vector/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix)
[![下载版本](https://img.shields.io/github/v/release/JingMatrix/Vector?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/Vector/releases/latest)
[![总共](https://shields.io/github/downloads/JingMatrix/Vector/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/Vector/releases)

</div>

---

### 介绍

Vector 是一个 Zygisk 模块,提供了一个与原版 Xposed 保持 API 一致的 ART hook 框架。它基于 [LSPlant](https://github.com/JingMatrix/LSPlant) 构建,以提供稳定的原生层插桩环境。

该框架允许模块在内存中修改系统和应用的行为。由于不会修改任何 APK 文件,这些更改是非破坏性的,重启即可轻松还原,并且可兼容各种 ROM 和 Android 版本。

---

### 支持性

Vector 支持使用 **Android 8.1 至 Android 17 Beta** 的设备。

> [!注]
> 这个框架要求安装 Magisk 或 KernelSU 并启用了 Zygisk 以便各项功能可以被正常使用。

---

### 安装

1. 下载最新发布的模块。
2. 用您的超级用户管理软件来安装此模块 (Magisk/KernelSU)。
3. 确保您的手机已有Zygisk的环境 (e.g., [NeoZygisk](https://github.com/JingMatrix/NeoZygisk))。
4. 重启您的设备。
5. 开机后请查看通知来使用Vector。

---

### 下载

| 频道 | 来源 |
| :--- | :--- |
| **稳定版本发布** | [GitHub Releases](https://github.com/JingMatrix/Vector/releases) |
| **测试版本 (金丝雀构建)** | [GitHub Actions](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=branch%3Amaster) |

> [!备注]
> 建议遇到问题或需要进行故障排查的用户使用测试版本(金丝雀构建)。
> 我们推荐用户使用测试版本版本以便能够帮助我们找到Vector的漏洞或者能够改进的地方。

> [!注意]
> GitHub 要求用户须 **登陆** 才能下载测试版本。
>
> 上方链接已筛选为仅显示 `master` 分支的构建。
> 请注意,来自拉取请求(PR)的构建通常不稳定,并且可能存在安全风险(取决于作者);我们建议使用 `master` 分支上经过验证的构建,除非有人请你协助我们进行调试。

---

### 支持与贡献

如果你遇到了任何的问题或者希望帮助我们改进这个项目，请查看以下资源

*   **排除故障:** 在报备任何漏洞之前请先咨询 [引导](https://github.com/JingMatrix/Vector/issues/123) 。
*   **讨论:** 加入我们的社区 [GitHub Discussions](https://github.com/JingMatrix/Vector/discussions).
*   **本地化:** 帮忙翻译这个项目 [Crowdin](https://crowdin.com/project/lsposed_jingmatrix).

> [!重要事项]
> 只有基于**最新的测试版本**的漏洞反馈才会被接受
>
> *中文使用者注明:*
>
> 为了提高沟通效率，本项目仅接受英文 Issue。请使用 [DeepL](https://www.deepl.com/zh/translator) 或其他翻译工具提交您的反馈。

---

### 开发者资源

Vector 同时支持传统和现代的 hook 标准,以确保模块具有广泛的兼容性。

*   [传统 Xposed API](https://api.xposed.info/)
*   [现代 libxposed API](https://github.com/JingMatrix/libxposed-api)
*   [Xposed 模块仓库](https://github.com/Xposed-Modules-Repo)

> [!注明]
> Vector 通过两个 git 子模块支持 `libxposed` API:[模块 API](./xposed/) 和[服务 API](./services/)。
>
> [master](https://github.com/JingMatrix/Vector/tree/master) 分支上一次成功的 GitHub Actions 构建,表明 Vector 在对应的具体提交上完全支持这些 API。
> 建议开发者检出与 Vector 相同的提交。

---

### 感谢

这个项目的所有可能都来自于以下开源贡献者：

*   [Magisk](https://github.com/topjohnwu/Magisk/): 安卓定制化的基石。
*   [LSPlant](https://github.com/JingMatrix/LSPlant): 核心 ART hook 引擎。
*   [XposedBridge](https://github.com/rovo89/XposedBridge): 标准的 Xposed API。
*   [Dobby](https://github.com/JingMatrix/Dobby): 内联 hook 实现。
*   [LSPosed](https://github.com/LSPosed/LSPosed): 上游源码。
*   [EdXposed](https://github.com/ElderDrivers/EdXposed): 上游源码,早于 LSPosed。
*   [xz-embedded](https://github.com/tukaani-project/xz-embedded): 库解压工具。

<details>
<summary>旧版与历史依赖软件</summary>

- ~~[Riru](https://github.com/RikkaApps/Riru)~~
- ~~[SandHook](https://github.com/ganyao114/SandHook/)~~
- ~~[YAHFA](https://github.com/rk700/YAHFA)~~
- ~~[dexmaker](https://github.com/linkedin/dexmaker)~~
- ~~[DexBuilder](https://github.com/LSPosed/DexBuilder)~~
</details>

---

### 许可证

Vector 采用 [GNU 通用公共许可证 v3](http://www.gnu.org/copyleft/gpl.html) 授权。
