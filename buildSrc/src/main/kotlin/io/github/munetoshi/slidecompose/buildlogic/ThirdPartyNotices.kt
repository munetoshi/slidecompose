package io.github.munetoshi.slidecompose.buildlogic

import groovy.json.JsonSlurper
import java.io.File
import java.io.Serializable
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A library from a Maven repository that the app bundles: one jar on its runtime classpath. */
data class BundledLibrary(
    val group: String,
    val artifact: String,
    val version: String,
    /** Its licenses as SPDX ids, or as named in its POM when they have none. */
    val licenses: List<String>,
    /** What its jar adds to the license texts: NOTICE files, and licenses of parts it includes. */
    val texts: List<JarText>,
) {
    /** "group:artifact", without the platform, as the tables show it. */
    val module: String get() = "$group:${withoutPlatform(artifact)}"
}

/** A license or notice file in a jar, or the part of it that the license texts in `licenses/` leave out. */
data class JarText(val kind: Kind, val text: String) {
    enum class Kind { Notice, License }
}

/**
 * Software the app bundles that does not come from a Maven repository, such as the Skia in skpdf,
 * described in the build script.
 *
 * @param license an SPDX license expression.
 * @param textFile the file in `licenses/` with its copyright lines and license.
 * @param appTexts names of texts that the build adds to the app for its About window only, in
 *   `texts/<name>.txt`, as they depend on the computer that builds it: such as where the source of
 *   the bundled JDK is.
 */
data class Component(
    val name: String,
    val version: String,
    val license: String,
    val textFile: String,
    val appTexts: List<String> = emptyList(),
) : Serializable

/** A row of the libraries table: the artifacts of one group in one version, under the same licenses. */
data class LibraryRow(val group: String, val artifacts: List<String>, val version: String, val licenses: List<String>) {
    val modules: Set<String> get() = artifacts.asSequence().map { "$group:$it" }.toSet()
}

/** A text from the jars, and the modules ("group:artifact") whose jars have it. */
data class LibraryText(val kind: JarText.Kind, val text: String, val modules: List<String>)

/** The licenses of each artifact in Licensee's `artifacts.json`, by "group:artifact:version". */
fun licensesInReport(json: String): Map<String, List<String>> {
    @Suppress("UNCHECKED_CAST")
    val artifacts = JsonSlurper().parseText(json) as List<Map<String, Any?>>
    return artifacts.associate { it.toCoordinatesAndLicenses() }
}

/** An artifact's "group:artifact:version" and its licenses: SPDX ids, or the names or links of others. */
private fun Map<String, Any?>.toCoordinatesAndLicenses(): Pair<String, List<String>> {
    val spdx = objects("spdxLicenses").map { it["identifier"] as String }
    val unknown = objects("unknownLicenses").map { (it["name"] ?: it["url"]) as String }
    return "${this["groupId"]}:${this["artifactId"]}:${this["version"]}" to spdx + unknown
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>> = (this[key] as List<Map<String, Any?>>?).orEmpty()

/**
 * The license and notice files in [jar], at its root or under `META-INF`: LICENSE, NOTICE and
 * COPYING, with or without an extension. Licenses keep only what [licenseTextBeyondApache] leaves.
 */
fun textsInJar(jar: File): List<JarText> = ZipFile(jar).use(ZipFile::jarTexts)

private fun ZipFile.jarTexts(): List<JarText> =
    entries().asSequence()
        .filter { !it.isDirectory && isLicenseFile(it.name) }
        .sortedBy { it.name }
        .mapNotNull { textIn(it) }
        .distinct()
        .toList()

/** The text of [entry], a license or notice file, or null for a license that is only Apache's. */
private fun ZipFile.textIn(entry: ZipEntry): JarText? {
    val text = getInputStream(entry).use { it.readBytes().decodeToString() }
    return if (entry.name.substringAfterLast('/').startsWith("NOTICE", ignoreCase = true)) {
        JarText(JarText.Kind.Notice, text.trim())
    } else {
        licenseTextBeyondApache(text)?.let { JarText(JarText.Kind.License, it) }
    }
}

private val LICENSE_FILE = Regex("(?i)(licen[cs]e|notice|copying)([-._][^/]*)?")

internal fun isLicenseFile(path: String): Boolean =
    ('/' !in path || path.startsWith("META-INF/")) && LICENSE_FILE.matches(path.substringAfterLast('/'))

/**
 * What a jar's license file [text] adds to the full license texts in `licenses/`: nothing for the
 * Apache License 2.0 alone, with or without its appendix; what follows it when the jar appends the
 * licenses of parts it includes, as PDFBox does; or all of it for any other license, as it has
 * copyright lines of its own.
 */
fun licenseTextBeyondApache(text: String): String? {
    val lines = text.lines()
    val header = lines.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
    val end = lines.indexOfFirst { it.trim() == "END OF TERMS AND CONDITIONS" }
    if (header != listOf("Apache License", "Version 2.0, January 2004") || end < 0) return text.trim()
    val appendix = lines.indexOfFirst { it.trim().startsWith("APPENDIX: How to apply the Apache License") }
    val appendixEnd = if (appendix < 0) -1 else lines.drop(appendix).indexOfFirst { it.trim().endsWith("limitations under the License.") }
    val rest = if (appendixEnd < 0) lines.drop(end + 1) else lines.drop(appendix + appendixEnd + 1)
    return rest.joinToString("\n").trimIndent().trim().ifEmpty { null }
}

private val PLATFORM_SUFFIX = Regex("(-(jvm|desktop|(macos|linux|windows)-(arm64|x64)))+$")

/**
 * [artifact] without the platform that Kotlin Multiplatform and Skiko name their jars after:
 * `ui-desktop` is `ui`, `skiko-awt-runtime-macos-arm64` is `skiko-awt-runtime`. So the notices
 * read the same whichever computer builds the app.
 */
fun withoutPlatform(artifact: String): String = artifact.replace(PLATFORM_SUFFIX, "")

/** The rows of the libraries table, by group and then artifact. */
fun libraryRows(libraries: List<BundledLibrary>): List<LibraryRow> =
    libraries.groupBy { Triple(it.group, it.version, it.licenses) }
        .values
        .asSequence()
        .map { it.toLibraryRow() }
        .sortedWith(compareBy({ it.group }, { it.artifacts.first() }))
        .toList()

/** The row of these libraries, of one group, version and licenses. */
private fun List<BundledLibrary>.toLibraryRow(): LibraryRow {
    val artifacts = asSequence().map { withoutPlatform(it.artifact) }.distinct().sorted().toList()
    val first = first()
    return LibraryRow(first.group, artifacts, first.version, first.licenses)
}

/** The texts in the libraries' jars, each once, with the modules that have it, in the order of the first one. */
fun libraryTexts(libraries: List<BundledLibrary>): List<LibraryText> =
    libraries.asSequence()
        .sortedBy { it.module }
        .flatMap(BundledLibrary::textsWithModule)
        .groupBy({ it.first }, { it.second })
        .map { (text, modules) -> LibraryText(text.kind, text.text, modules.distinct()) }

private fun BundledLibrary.textsWithModule(): Sequence<Pair<JarText, String>> = texts.asSequence().map { it to module }

/** The license texts in `licenses/` that the libraries need, by SPDX id. Fails for a license without one. */
fun licenseTextsFor(rows: List<LibraryRow>, directory: File): Map<String, String> =
    rows.asSequence()
        .flatMap { it.licenses.asSequence() }
        .distinct()
        .sorted()
        .associateWith { licenseText(directory, it) }

private fun licenseText(directory: File, id: String): String {
    val file = File(directory, "$id.txt")
    check(file.isFile) { "No license text for $id: add it as licenses/$id.txt" }
    return file.readText().trim()
}

/** The libraries table in THIRD_PARTY_NOTICES.md. */
fun librariesTable(rows: List<LibraryRow>): String = buildString {
    appendLine("| Group | Artifacts | Version | License |")
    appendLine("|---|---|---|---|")
    for (row in rows) {
        appendLine("| `${row.group}` | ${row.artifacts.joinToString(", ")} | ${row.version} | ${row.licenses.joinToString(", ")} |")
    }
}.trimEnd()

/** The table of software from outside Maven repositories in THIRD_PARTY_NOTICES.md. */
fun componentsTable(components: List<Component>): String = buildString {
    appendLine("| Software | Version | License |")
    appendLine("|---|---|---|")
    for (component in components) {
        appendLine("| ${component.name} | ${component.version.ifEmpty { "—" }} | ${component.license} |")
    }
}.trimEnd()

/**
 * The license texts in THIRD_PARTY_NOTICES.md: the full texts of the libraries' licenses, the
 * texts in their jars, then the components' own ([componentTexts], by file name), each once.
 */
fun licenseTextsSection(
    licenses: Map<String, String>,
    texts: List<LibraryText>,
    components: List<Component>,
    componentTexts: Map<String, String>,
): String {
    val sections = licenses.asSequence().map { (id, text) -> "### $id" to text } +
        texts.asSequence().map { text -> "### ${text.modules.joinToString(", ")} — ${titleOf(text.kind)}" to text.text } +
        components.groupBy { it.textFile }.values.asSequence().map { group ->
            "### ${group.joinToString(", ") { it.name }}" to componentTexts.getValue(group.first().textFile)
        }
    return sections.joinToString("\n\n") { (heading, text) -> "$heading\n\n${codeBlock(text)}" }
}

private fun titleOf(kind: JarText.Kind): String = when (kind) {
    JarText.Kind.Notice -> "NOTICE"
    JarText.Kind.License -> "LICENSE (after the Apache License 2.0)"
}

/** [text] as a fenced code block, with a fence longer than any run of backticks in it. */
internal fun codeBlock(text: String): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "${fence}text\n$text\n$fence"
}

/**
 * [document] with the content between each `<!-- generated:name -->` and `<!-- /generated:name -->`
 * replaced with [regions]`[name]`. Fails when a region is missing or unknown.
 */
fun replaceRegions(document: String, regions: Map<String, String>): String {
    val names = REGION.findAll(document).map { it.groupValues[1] }.toList()
    check(names.toSet() == regions.keys && names.size == regions.size) {
        "THIRD_PARTY_NOTICES.md has the generated regions $names, but should have each of ${regions.keys} once"
    }
    return REGION.replace(document) { match ->
        val name = match.groupValues[1]
        "<!-- generated:$name -->\n${regions.getValue(name)}\n<!-- /generated:$name -->"
    }
}

private val REGION = Regex("<!-- generated:([a-z]+) -->.*?<!-- /generated:\\1 -->", RegexOption.DOT_MATCHES_ALL)

/** An entry of the About window's list. [texts] are the names of its files under `texts/`. */
data class AboutEntry(val title: String, val detail: String, val version: String, val license: String, val texts: List<String>) {
    /** One line of `index.tsv`. */
    fun toLine(): String = listOf(title, detail, version, license, texts.joinToString(" ")).joinToString("\t")
}

/** The About entry of [row]: its licenses' texts, then the texts from its jars. */
private fun aboutEntry(row: LibraryRow, texts: List<LibraryText>): AboutEntry {
    val own = texts.asSequence().withIndex().filter { isTextOf(it.value, row) }.map { libraryTextName(it.index) }
    val names = (row.licenses.asSequence().map(::licenseTextName) + own).toList()
    return AboutEntry(row.group, row.artifacts.joinToString(", "), row.version, row.licenses.joinToString(", "), names)
}

private fun isTextOf(text: LibraryText, row: LibraryRow): Boolean = text.modules.any { it in row.modules }

/**
 * The About window's list: the library rows, each with its licenses' texts and the texts from its
 * jars, then the components. The texts are named after the files they are written to.
 */
fun aboutEntries(rows: List<LibraryRow>, texts: List<LibraryText>, components: List<Component>): List<AboutEntry> {
    val libraries = rows.asSequence().map { aboutEntry(it, texts) }
    val others = components.asSequence().map { AboutEntry(it.name, "", it.version, it.license, listOf(componentTextName(it.textFile)) + it.appTexts) }
    return (libraries + others).toList()
}

/** The name of the About window's text of the license with the SPDX [id]. */
fun licenseTextName(id: String): String = "license-$id"

/** The name of the About window's text at [index] in [libraryTexts]. */
fun libraryTextName(index: Int): String = "library-$index"

/** The name of the About window's text of the components whose text is in [textFile]. */
fun componentTextName(textFile: String): String = "component-${textFile.substringBeforeLast('.')}"
