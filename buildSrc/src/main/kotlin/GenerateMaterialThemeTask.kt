import dev.rikka.tools.materialthemebuilder.MaterialThemeBuilderExtension
import dev.rikka.tools.materialthemebuilder.generator.ValuesAllGenerator
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generates the Material theme-overlay colors and styles that back the app's accent-color options.
 *
 * For each [seedColors] entry (theme name without the `Material` prefix mapped to a hex seed) it
 * computes a Material 3 tonal palette and emits a `ThemeOverlay.Light/Dark.Material<name>` style,
 * named via [lightThemeFormat] / [darkThemeFormat]. The color math and XML writer are provided by
 * the materialthemebuilder library through a [MaterialThemeBuilderExtension].
 */
abstract class GenerateMaterialThemeTask @Inject constructor(private val objects: ObjectFactory) :
    DefaultTask() {
    @get:Input abstract val seedColors: MapProperty<String, String>

    @get:Input abstract val generatePalette: Property<Boolean>

    @get:Input abstract val lightThemeFormat: Property<String>

    @get:Input abstract val darkThemeFormat: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val extension = objects.newInstance(MaterialThemeBuilderExtension::class.java)
        extension.isGeneratePalette = generatePalette.get()
        val light = lightThemeFormat.get()
        val dark = darkThemeFormat.get()
        seedColors.get().toSortedMap().forEach { (name, color) ->
            extension.themes.create("Material$name") {
                primaryColor = "#$color"
                lightThemeFormat = light
                darkThemeFormat = dark
            }
        }

        val root = outputDir.get().asFile
        // Wipe stale output so removed themes don't linger.
        root.walkBottomUp().forEach { if (it != root) it.delete() }
        val valuesDir = File(root, "values").apply { mkdirs() }
        ValuesAllGenerator(File(valuesDir, "values.xml"), extension).generate()
    }
}
