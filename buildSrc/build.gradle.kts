plugins { `kotlin-dsl` }

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Supplies the Material color science (HCT, tonal palettes) and XML generators used by
    // GenerateMaterialThemeTask. Only the library classes are referenced; the plugin is not
    // applied.
    implementation("dev.rikka.tools.materialthemebuilder:gradle-plugin:1.5.1")
}
