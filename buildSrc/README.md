# Vector Build Logic

The `buildSrc` project holds build logic that Gradle compiles before configuring the main build. It currently contains two source generators consumed by the `:app` (manager) module. Both produce data that the manager references at compile time, so they participate in every application build rather than running as a manual pre-step.

The manager exposes two user-facing settings that require precomputed, per-build data: an in-app language selector and an accent-color selector. The language selector needs the concrete set of locales the application ships translations for, and the color selector needs a full Material 3 palette for each preset seed color. These generators derive that data from the resource tree and a fixed color table, so the settings stay consistent with the translations and palette definitions actually present in the source.

## Directory Structure

```text
buildSrc/
├── build.gradle.kts                    # kotlin-dsl module; declares the color-generation library
└── src/main/kotlin/
    ├── GenerateLangListTask.kt         # translated-locale enumeration
    └── GenerateMaterialThemeTask.kt    # accent-color theme overlays
```

## Integration

The task classes live in the default package and are therefore visible to the module build scripts without an import. The `:app` build registers them per variant through the AGP variant API:

```kotlin
androidComponents {
    onVariants { variant ->
        val langList = tasks.register<GenerateLangListTask>("generate${cap}LangList") { … }
        variant.sources.java?.addGeneratedSourceDirectory(langList, GenerateLangListTask::outputDir)

        val theme = tasks.register<GenerateMaterialThemeTask>("generate${cap}MaterialTheme") { … }
        variant.sources.res?.addGeneratedSourceDirectory(theme, GenerateMaterialThemeTask::outputDir)
    }
}
```

`addGeneratedSourceDirectory` binds each task's `outputDir` into the variant's Java or resource source set. AGP owns the output location, wires the task into the source-merge graph, and establishes the task dependency automatically, so the generated Java is compiled and the generated resources are merged like any hand-written source.

## Locale List Generation

`GenerateLangListTask` emits the `org.lsposed.manager.util.LangList` class, which the settings screen reads to populate the language dropdown (`LangList.LOCALES` for entry values, `LangList.DISPLAY_LOCALES` for the script-qualified display keys).

Inputs are the resource directories to scan (`resDirs`), the target `packageName` and `className`, and the leading `firstItem` (`SYSTEM`). The task:

* Collects every `values-<qualifier>` folder that contains a `strings.xml`, treating the qualifier as a translated locale and ignoring configuration-only qualifiers such as `values-night` or `values-v31`.
* Converts each Android qualifier to a BCP-47 tag: a `b+`-prefixed qualifier has its `+` separators replaced with `-`, and a trailing `-r<REGION>` segment drops its `r` marker (`zh-rCN` becomes `zh-CN`, `pt-rBR` becomes `pt-BR`).
* Adds `en` for the default `values/` resources, sorts the tags, and prepends `firstItem`.
* Produces `DISPLAY_LOCALES` as a parallel array in which the Simplified and Traditional Chinese tags are rewritten to their script forms (`zh-CN` to `zh-Hans`, `zh-TW` to `zh-Hant`); all other tags are identical to `LOCALES`.

Adding a new translation is therefore a matter of dropping a `values-<locale>/strings.xml` folder into the module; the list updates on the next build with no manual edits.

## Material Theme Generation

`GenerateMaterialThemeTask` emits a resource `values.xml` containing the color roles and theme-overlay styles that back the accent-color options. The manager maps each preset to a generated style through `ThemeUtil` (for example `MATERIAL_RED` to `R.style.ThemeOverlay_MaterialRed`) and applies it to recolor the interface.

Inputs are the seed color table (`seedColors`, mapping a theme name to a hex value), the `generatePalette` flag, and the `lightThemeFormat` / `darkThemeFormat` style-name templates. For each seed the task computes a Material 3 tonal palette (primary, secondary, tertiary, neutral, neutral-variant, and error ramps across the standard tone stops) and writes:

* The resolved color roles for the light and dark schemes (`colorPrimary`, `colorOnPrimary`, `colorPrimaryContainer`, and the remaining container, surface, and outline roles).
* A `ThemeOverlay.Light.Material<name>` and `ThemeOverlay.Dark.Material<name>` style that binds those roles, plus the discrete palette attributes (`palettePrimary0` through `palettePrimary100`, and the equivalent ramps for the other tonal palettes) when `generatePalette` is set.

The color science and XML serialization are provided by the `dev.rikka.tools.materialthemebuilder` artifact declared in `build.gradle.kts`. The task drives it directly: it instantiates a `MaterialThemeBuilderExtension` through the `ObjectFactory`, populates the theme container from `seedColors`, and invokes `ValuesAllGenerator`. Only the library classes are used; the artifact's Gradle plugin is not applied. Themes are keyed in a `NamedDomainObjectContainer`, so the output is ordered by theme name and is deterministic for a given seed table.
