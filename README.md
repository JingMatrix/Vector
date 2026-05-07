<div align="center">

# Vector Framework

**A high-performance ART hooking framework for modern Android**

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/Vector/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix)
[![Download](https://img.shields.io/github/v/release/JingMatrix/Vector?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/Vector/releases/latest)
[![Total](https://shields.io/github/downloads/JingMatrix/Vector/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/Vector/releases)

</div>

---

### Introduction

Vector is a Zygisk module providing an ART hooking framework that maintains API consistency with the original Xposed. It is engineered on top of [LSPlant](https://github.com/JingMatrix/LSPlant) to deliver a stable, native-level instrumentation environment.

The framework allows modules to modify system and application behavior in-memory. Because no APK files are modified, changes are non-destructive, easily reversible via reboot, and compatible across various ROMs and Android versions.

---

### Compatibility

Vector supports devices running **Android 8.1 through Android 17 Beta**.

> [!TIP]
> This framework requires a recent installation of Magisk or KernelSU with Zygisk enabled.

---

### Installation

1. Download the latest release as a system module.
2. Install the module via your root manager (Magisk/KernelSU).
3. Ensure a Zygisk environment (e.g., [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)).
4. Reboot the device.
5. Access management settings via the system notification.

---

### Downloads

| Channel | Source |
| :--- | :--- |
| **Stable Releases** | [GitHub Releases](https://github.com/JingMatrix/Vector/releases) |
| **Canary (CI) Builds** | [GitHub Actions](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=branch%3Amaster) |

> [!NOTE]
> Debug builds are recommended for users encountering issues or performing troubleshooting.
> We encourage users to test CI builds to help us identify bugs and accelerate development.

> [!CAUTION]
> GitHub requires users to be **logged in** to download CI artifacts.
>
> The link above is filtered to show only `master` branch builds.
> Please note that builds from Pull Requests (PRs) are often unstable and potentially unsafe (depending on the authors); we recommend staying on the `master` branch for verified builds, unless you are asked to help our debugging sessions.

---

### Support and Contribution

If you encounter issues or wish to help improve the project, please refer to the resources below.

*   **Troubleshooting:** Consult the [guide](https://github.com/JingMatrix/Vector/issues/123) before reporting bugs.
*   **Discussions:** Join our community on [GitHub Discussions](https://github.com/JingMatrix/Vector/discussions).
*   **Localization:** Help translate the project via [Crowdin](https://crowdin.com/project/lsposed_jingmatrix).

> [!IMPORTANT]
> Bug reports are only accepted if they are based on the **latest debug build**.
>
> *Notice for Chinese speakers:*
>
> 为了提高沟通效率，本项目仅接受英文 Issue。请使用 [DeepL](https://www.deepl.com/zh/translator) 或其他翻译工具提交您的反馈。

---

### Developer Resources

Vector supports both legacy and modern hooking standards to ensure broad module compatibility.

*   [Legacy Xposed API](https://api.xposed.info/)
*   [Modern libxposed API](https://libxposed.github.io/api/)
*   [Xposed Module Repository](https://github.com/Xposed-Modules-Repo)

> [!NOTE]
> Vector supports the `libxposed` API via two git submodules: the [module API](./xposed/) and the [service API](./services/).
>
> A successful GitHub Actions build of the [master](https://github.com/JingMatrix/Vector/tree/master) branch indicates that Vector fully supports these APIs at those specific commits.
> Developers are suggested to check out the same commits as Vector.

---

### Credits

This project is made possible by the following open-source contributions:

*   [Magisk](https://github.com/topjohnwu/Magisk/): The foundation of Android customization.
*   [LSPlant](https://github.com/JingMatrix/LSPlant): The core ART hooking engine.
*   [XposedBridge](https://github.com/rovo89/XposedBridge): The standard Xposed APIs.
*   [Dobby](https://github.com/JingMatrix/Dobby): Inline hooking implementation.
*   [LSPosed](https://github.com/LSPosed/LSPosed): Upstream source.
*   [xz-embedded](https://github.com/tukaani-project/xz-embedded): Library decompression utilities.

<details>
<summary>Legacy and Historical Dependencies</summary>

- ~~[Riru](https://github.com/RikkaApps/Riru)~~
- ~~[SandHook](https://github.com/ganyao114/SandHook/)~~
- ~~[YAHFA](https://github.com/rk700/YAHFA)~~
- ~~[dexmaker](https://github.com/linkedin/dexmaker)~~
- ~~[DexBuilder](https://github.com/LSPosed/DexBuilder)~~
</details>

---

### License

Vector is licensed under the [GNU General Public License v3](http://www.gnu.org/copyleft/gpl.html).
