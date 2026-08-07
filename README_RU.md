<div align="center">

# Vector Framework
<p align="center">
<a href="/README.md"><img src="https://github.com/loanelly/EntropyShield/blob/main/img/Flags/EN1.png" height="21" align="center"/></a> <a href="/README_RU.md"><img src="https://github.com/loanelly/EntropyShield/blob/main/img/Flags/result.png" height="26" align="center"/></a>
</p>

**Высокопроизводительный фреймворк ART-хукинга для современного Android**

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/Vector/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix)
[![Download](https://img.shields.io/github/v/release/JingMatrix/Vector?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/Vector/releases/latest)
[![Total](https://shields.io/github/downloads/JingMatrix/Vector/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/Vector/releases)

</div>

---

### Введение

Vector — это Zygisk-модуль, предоставляющий фреймворк ART-хукинга, который сохраняет согласованность API с оригинальным Xposed. Он разработан на базе [LSPlant](https://github.com/JingMatrix/LSPlant) для обеспечения стабильной среды профилирования и инструментирования на нативном уровне.

Фреймворк позволяет модулям изменять поведение системы и приложений прямо в оперативной памяти. Поскольку файлы APK не модифицируются, вносимые изменения являются неразрушающими, легко отменяются путем перезагрузки и совместимы с различными прошивками и версиями Android.

---

### Совместимость

Vector поддерживает устройства под управлением **Android от 8.1 до Android 17 Beta**.

> [!TIP]
> Для работы этого фреймворка требуется актуальная версия Magisk или KernelSU с включенным Zygisk.

---

### Установка

1. Скачайте последнюю версию релиза в виде системного модуля.
2. Установите модуль через ваш менеджер root-прав (Magisk/KernelSU).
3. Убедитесь в наличии активной среды Zygisk (например, [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)).
4. Перезагрузите устройство.
5. Получите доступ к настройкам управления через системное уведомление.

---

### Загрузки

| Канал | Источник |
| :--- | :--- |
| **Стабильные релизы** | [GitHub Releases](https://github.com/JingMatrix/Vector/releases) |
| **Канареечные (CI) сборки** | [GitHub Actions](https://github.com/JingMatrix/Vector/actions/workflows/core.yml?query=branch%3Amaster)|

> [!NOTE]
> Отладочные (Debug) сборки рекомендуются пользователям, столкнувшимся с проблемами или выполняющим поиск неисправностей.
> Мы призываем пользователей тестировать CI-сборки, чтобы помочь нам выявлять ошибки и ускорять разработку.

> [!CAUTION]
> GitHub требует, чтобы пользователи были **авторизованы в системе** для загрузки артефактов CI.
>
> Ссылка выше отфильтрована для отображения сборок только из ветки `master`.
> Пожалуйста, обратите внимание, что сборки из Pull Requests (PR) часто нестабильны и потенциально небезопасны (в зависимости от авторов); мы рекомендуем оставаться на ветке `master` для проверенных сборок, если только вас не попросили помочь в сессиях отладки.

---

### Поддержка и вклад в развитие

Если вы столкнулись с проблемами или хотите помочь улучшить проект, пожалуйста, обратитесь к ресурсам ниже.

*   **Устранение неполадок:** Ознакомьтесь с [руководством](https://github.com/JingMatrix/Vector/issues/123) перед отправкой отчетов об ошибках.
*   **Обсуждения:** Присоединяйтесь к нашему сообществу в [GitHub Discussions](https://github.com/JingMatrix/Vector/discussions).
*   **Локализация:** Помогите перевести проект через [Crowdin](https://crowdin.com/project/lsposed_jingmatrix).

> [!IMPORTANT]
> Отчеты об ошибках принимаются только в том случае, если они основаны на **последней отладочной (debug) сборке**.
>
> *Notice for Chinese speakers:*
>
> 为了提高沟通效率，本项目仅接受英文 Issue。请使用 [DeepL](https://www.deepl.com/zh/translator) 或其他翻译工具提交您的反馈。

---

### Ресурсы для разработчиков

Vector поддерживает как устаревшие, так и современные стандарты хукинга для обеспечения широкой совместимости с модулями.

*   [Legacy Xposed API](https://api.xposed.info/)
*   [Modern libxposed API](https://libxposed.github.io/api/)
*   [Xposed Module Repository](https://github.com/Xposed-Modules-Repo)

> [!NOTE]
> Vector поддерживает API `libxposed` через два подмодуля git: [API модуля](./xposed/) и [API сервиса](./services/).
>
> Успешная сборка GitHub Actions для ветки [master](https://github.com/JingMatrix/Vector/tree/master) указывает на то, что Vector полностью поддерживает эти API на уровне данных конкретных коммитов.
> Разработчикам предлагается использовать те же коммиты, что и Vector.

---

### Благодарности

Этот проект стал возможным благодаря следующим проектам с открытым исходным кодом:

*   [Magisk](https://github.com/topjohnwu/Magisk/): Основа кастомизации Android.
*   [LSPlant](https://github.com/JingMatrix/LSPlant): Основной движок ART-хукинга.
*   [XposedBridge](https://github.com/rovo89/XposedBridge): Стандартные API Xposed.
*   [Dobby](https://github.com/JingMatrix/Dobby): Реализация инлайн-хукинга.
*   [LSPosed](https://github.com/LSPosed/LSPosed): Вышестоящий источник (upstream).
*   [EdXposed](https://github.com/ElderDrivers/EdXposed): Вышестоящий источник до LSPosed.
*   [xz-embedded](https://github.com/tukaani-project/xz-embedded): Утилиты для распаковки библиотек.

<details>
<summary>Устаревшие и исторические зависимости</summary>

- ~~[Riru](https://github.com/RikkaApps/Riru)~~
- ~~[SandHook](https://github.com/ganyao114/SandHook/)~~
- ~~[YAHFA](https://github.com/rk700/YAHFA)~~
- ~~[dexmaker](https://github.com/linkedin/dexmaker)~~
- ~~[DexBuilder](https://github.com/LSPosed/DexBuilder)~~
</details>

---

### Лицензия

Vector распространяется под лицензией [GNU General Public License v3](http://www.gnu.org/copyleft/gpl.html).
