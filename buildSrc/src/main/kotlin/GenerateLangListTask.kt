import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the `LangList` class enumerating the locales the app is translated into.
 *
 * Scans [resDirs] for `values-<qualifier>` folders containing a `strings.xml`, converts each
 * Android locale qualifier to a BCP-47 tag (`zh-rCN` -> `zh-CN`, `pt-rBR` -> `pt-BR`), adds `en`
 * for the default `values/` resources, sorts the result, and prepends [firstItem].
 * `DISPLAY_LOCALES` mirrors `LOCALES` with the script-qualified aliases the UI expects (`zh-CN` ->
 * `zh-Hans`, `zh-TW` -> `zh-Hant`).
 */
abstract class GenerateLangListTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:Input abstract val packageName: Property<String>

    @get:Input abstract val className: Property<String>

    @get:Input abstract val firstItem: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val tags = sortedSetOf<String>()
        tags.add("en") // the default `values/` resources are English
        resDirs.files.forEach { res ->
            (res.listFiles() ?: emptyArray()).forEach { dir ->
                if (
                    dir.isDirectory &&
                        dir.name.startsWith("values-") &&
                        File(dir, "strings.xml").exists()
                ) {
                    tags.add(toBcp47(dir.name.removePrefix("values-")))
                }
            }
        }

        val locales = buildList {
            add(firstItem.get())
            addAll(tags)
        }
        val display = locales.map { DISPLAY_ALIASES.getOrDefault(it, it) }

        val pkg = packageName.get()
        val cls = className.get()
        val out = outputDir.get().asFile.resolve(pkg.replace('.', '/')).resolve("$cls.java")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                append("package ").append(pkg).append(";\n\n")
                append("public final class ").append(cls).append(" {\n")
                append("    public static final String[] LOCALES = {")
                append(locales.joinToString(",") { "\"$it\"" })
                append("};\n")
                append("    public static final String[] DISPLAY_LOCALES = {")
                append(display.joinToString(",") { "\"$it\"" })
                append("};\n")
                append("}\n")
            }
        )
    }

    /** Converts an Android resource locale qualifier to a BCP-47 language tag. */
    private fun toBcp47(qualifier: String): String {
        if (qualifier.startsWith("b+")) return qualifier.substring(2).replace('+', '-')
        // A `-r<REGION>` segment carries a leading `r` marker to strip (e.g. `rCN` -> `CN`).
        return qualifier
            .split("-")
            .mapIndexed { index, seg ->
                if (index > 0 && seg.length == 3 && seg[0] == 'r') seg.substring(1) else seg
            }
            .joinToString("-")
    }

    private companion object {
        val DISPLAY_ALIASES = mapOf("zh-CN" to "zh-Hans", "zh-TW" to "zh-Hant")
    }
}
