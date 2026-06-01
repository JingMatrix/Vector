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

### Wsparcie i wkład

Jeśli napotkasz problemy lub chcesz pomóc w ulepszeniu projektu, skorzystaj z poniższych zasobów.

* **Rozwiązywanie problemów:** Przed zgłoszeniem błędów zapoznaj się z [poradnikiem](https://github.com/JingMatrix/Vector/issues/123).
* **Dyskusje:** Dołącz do naszej społeczności w [Dyskusjach GitHub](https://github.com/JingMatrix/Vector/discussions).
* **Lokalizacja:** Pomóż w tłumaczeniu projektu za pośrednictwem [Crowdin](https://crowdin.com/project/lsposed_jingmatrix).

[!IMPORTANT]

Zgłoszenia błędów są akceptowane tylko wtedy, gdy dotyczą **najnowszej wersji debugowej**.

*Uwaga dla osób mówiących po chińsku:*

Aby usprawnić komunikację, ten projekt akceptuje tylko zgłoszenia w języku angielskim. Prosimy o korzystanie z [DeepL](https://www.deepl.com/zh/translator) lub innych narzędzi do tłumaczeń, aby przesłać swoją opinię.

---

### Zasoby dla programistów

Vector obsługuje zarówno starsze, jak i nowsze standardy przechwytywania, aby zapewnić szeroką kompatybilność modułów.

* [starsze API Xposed](https://api.xposed.info/)
* [Nowoczesne API libxposed](https://libxposed.github.io/api/)
* [Repozytorium modułów Xposed](https://github.com/Xposed-Modules-Repo)


> [!NOTE]
> Vector obsługuje API `libxposed` za pośrednictwem dwóch podmodułów Git: [API modułów](./xposed/) i [API usług](./services/).
>
> Udana kompilacja gałęzi [master](https://github.com/JingMatrix/Vector/tree/master) w GitHub Actions wskazuje, że Vector w pełni obsługuje te API w tych konkretnych zatwierdzeniach.
> Zaleca się, aby programiści sprawdzili te same zatwierdzenia, co Vector.

---

### Podziękowania

Ten projekt jest możliwy dzięki następującym projektom open source:

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

### Licencja

Vector jest licencjonowany na podstawie [GNU General Public License v3](http://www.gnu.org/copyleft/gpl.html).
