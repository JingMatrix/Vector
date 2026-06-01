<div align="center">

# Vector

**Wysokowydajny framework do obsługi ART dla nowoczesnego systemu Android**

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/Vector/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix)
[![Download](https://img.shields.io/github/v/release/JingMatrix/Vector?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/Vector/releases/latest)
[![Total](https://shields.io/github/downloads/JingMatrix/Vector/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/Vector/releases)

</div>

---

### Wprowadzenie

Vector to moduł Zygisk, który zapewnia framework do hakowania art, zachowując spójność API z oryginalnym xposed. Został on opracowany na bazie [lsplant](https://github.com/JingMatrix/LSPlant), aby zapewnić stabilne środowisko instrumentacji na poziomie natywnym.

Framework umożliwia modułom modyfikację działania systemu i aplikacji w pamięci. Ponieważ pliki APK nie są modyfikowane, zmiany są nieniszczące, łatwo odwracalne poprzez ponowne uruchomienie i kompatybilne z różnymi ROM-ami i wersjami Androida.

---

### Zgodność

Vector obsługuje urządzenia z systemem **Android 8.1 do Androida 17 Beta**.

> [!TIP]
> Ten framework wymaga najnowszej instalacji Magisk lub KernelSU z włączonym Zygiskiem.

---

### Instalacja

1. Pobierz najnowszą wersję jako moduł systemowy.
2. Zainstaluj moduł za pomocą konta root (magisk/kernelsu).
3. Upewnij się, że środowisko zygisk (np. [neozygisk](https://github.com/JingMatrix/NeoZygisk)).
4. Uruchom ponownie urządzenie.
5. Uzyskaj dostęp do ustawień zarządzania za pomocą powiadomienia systemowego.

---

### Pobieranie

| Kanał | Źródło |
| :--- | :--- |
| **Wersje stabilne** | [Wersje GitHub](https://github.com/JingMatrix/Vector/releases) |
| **Kompilacje Canary (CI)** | [Akcje GitHub](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=branch%3Amaster) |

> [!CAUTION]
> Kompilacje debugowania są zalecane dla użytkowników napotykających problemy lub rozwiązujących problemy.
> Zachęcamy użytkowników do testowania kompilacji CI, aby pomóc nam zidentyfikować błędy i przyspieszyć rozwój.

> [!CAUTION]
> GitHub wymaga od użytkowników **zalogowania**, aby mogli pobrać artefakty CI.
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
