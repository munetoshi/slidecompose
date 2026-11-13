package io.github.munetoshi.slidecompose.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Collects the licenses of what the app bundles: the libraries on its runtime classpath, with the
 * licenses Licensee found in their POMs and the license and notice files in their jars, and the
 * [components] from elsewhere. Writes THIRD_PARTY_NOTICES.md, its [template] with the generated
 * regions filled in, and the About window's list.
 */
@CacheableTask
abstract class GenerateNotices : DefaultTask() {
    /** Licensee's `artifacts.json` for the app's runtime classpath. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseeReport: RegularFileProperty

    /** The name of each library's jar in [jars], by "group:artifact:version". */
    @get:Input
    abstract val libraries: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    /** THIRD_PARTY_NOTICES.md as it is: the text around the generated regions. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val template: RegularFileProperty

    /** The full license texts by SPDX id (`Apache-2.0.txt`), and the [components]' texts. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTexts: DirectoryProperty

    @get:Input
    abstract val components: ListProperty<Component>

    @get:OutputFile
    abstract val notices: RegularFileProperty

    /** Java resources: `<aboutPath>/index.tsv` and `<aboutPath>/texts/`. */
    @get:OutputDirectory
    abstract val aboutResources: DirectoryProperty

    @get:Input
    abstract val aboutPath: Property<String>

    /** Takes the libraries from the resolved artifacts of a runtime classpath. */
    fun libraries(artifacts: Provider<Set<ResolvedArtifactResult>>) {
        val modules = artifacts.map { it.fromModules() }
        jars.from(modules.map { it.toFiles() })
        libraries.set(modules.map { it.toJarsByCoordinates() })
    }

    @TaskAction
    fun generate() {
        val licenses = licensesInReport(licenseeReport.get().asFile.readText())
        val jarsByName = jars.files.associateBy { it.name }
        val bundled = libraries.get().map { (coordinates, jar) ->
            val (group, artifact, version) = coordinates.split(':')
            val found = licenses[coordinates] ?: throw GradleException("Licensee's report has no $coordinates")
            BundledLibrary(group, artifact, version, found, textsInJar(jarsByName.getValue(jar)))
        }
        val rows = libraryRows(bundled)
        val texts = libraryTexts(bundled)
        val directory = licenseTexts.get().asFile
        val fullTexts = licenseTextsFor(rows, directory)
        val others = components.get()
        val componentTexts = others.asSequence().map { it.textFile }.distinct()
            .associateWith { File(directory, it).readText().trim() }

        val document = replaceRegions(
            template.get().asFile.readText(),
            mapOf(
                "libraries" to librariesTable(rows),
                "components" to componentsTable(others),
                "texts" to licenseTextsSection(fullTexts, texts, others, componentTexts),
            ),
        )
        notices.get().asFile.writeText(document)

        aboutResources.get().asFile.deleteRecursively()
        val about = aboutResources.get().asFile.resolve(aboutPath.get())
        about.resolve("texts").mkdirs()
        about.resolve("index.tsv").writeText(aboutEntries(rows, texts, others).joinToString("\n") { it.toLine() } + "\n")
        val files = fullTexts.asSequence().map { (id, text) -> licenseTextName(id) to text } +
            texts.asSequence().mapIndexed { index, text -> libraryTextName(index) to text.text } +
            componentTexts.asSequence().map { (file, text) -> componentTextName(file) to text }
        files.forEach { (name, text) -> about.resolve("texts/$name.txt").writeText(text + "\n") }
    }
}

/** The artifacts that come from Maven modules, which have coordinates, not files of a project. */
private fun Set<ResolvedArtifactResult>.fromModules(): List<ResolvedArtifactResult> =
    filter { it.id.componentIdentifier is ModuleComponentIdentifier }

private fun List<ResolvedArtifactResult>.toFiles(): List<File> = map { it.file }

/** Each artifact's jar name, by "group:artifact:version". */
private fun List<ResolvedArtifactResult>.toJarsByCoordinates(): Map<String, String> = associate(ResolvedArtifactResult::toCoordinatesAndJar)

private fun ResolvedArtifactResult.toCoordinatesAndJar(): Pair<String, String> {
    val module = id.componentIdentifier as ModuleComponentIdentifier
    return "${module.group}:${module.module}:${module.version}" to file.name
}

/** Fails when THIRD_PARTY_NOTICES.md differs from what [GenerateNotices] makes of it. */
@CacheableTask
abstract class CheckNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generated: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val committed: RegularFileProperty

    /** Written when they match, so that the check is up to date until either changes. */
    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        if (generated.get().asFile.readText() != committed.get().asFile.readText()) {
            throw GradleException("THIRD_PARTY_NOTICES.md is out of date: run ./gradlew updateNotices and check the changes")
        }
        result.get().asFile.writeText("THIRD_PARTY_NOTICES.md is up to date\n")
    }
}

/**
 * Copies the generated THIRD_PARTY_NOTICES.md over the one in the repository. Untracked, because
 * the file it writes is also what [GenerateNotices] reads.
 */
@UntrackedTask(because = "It writes the template of the task it copies from")
abstract class UpdateNotices : DefaultTask() {
    @get:InputFile
    abstract val generated: RegularFileProperty

    @get:Internal
    abstract val target: RegularFileProperty

    @TaskAction
    fun update() {
        val text = generated.get().asFile.readText()
        val file = target.get().asFile
        if (file.readText() == text) {
            logger.lifecycle("THIRD_PARTY_NOTICES.md is up to date")
        } else {
            file.writeText(text)
            logger.lifecycle("Updated THIRD_PARTY_NOTICES.md")
        }
    }
}
