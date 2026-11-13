package io.github.munetoshi.slidecompose.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.net.URLEncoder

/** The `KEY="value"` lines of a JDK's `release` file. */
fun parseRelease(text: String): Map<String, String> =
    text.lineSequence()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { (key, value) -> key.trim() to value.trim().removeSurrounding("\"") }

/**
 * Where Eclipse Adoptium publishes the complete source code of the Temurin build that [release]
 * describes, as one archive next to its binaries. Fails for a JDK from anyone else.
 */
fun jdkSourcesUrl(release: Map<String, String>): String {
    val version = release.getValue("SEMANTIC_VERSION")
    val feature = version.substringBefore('.')
    return "https://github.com/adoptium/temurin$feature-binaries/releases/download/${temurinReleaseName(release)}/OpenJDK${feature}U-jdk-sources_${version.replace('+', '_')}.tar.gz"
}

/** Adoptium's name for the release of the Temurin build that [release] describes, URL-encoded. Fails for a JDK from anyone else. */
private fun temurinReleaseName(release: Map<String, String>): String {
    check(release["IMPLEMENTOR"] == "Eclipse Adoptium") { "The bundled JDK is not Eclipse Temurin: its source would have to be found another way" }
    return URLEncoder.encode("jdk-${release.getValue("SEMANTIC_VERSION")}", Charsets.UTF_8)
}

/**
 * Where Adoptium's API lists the source archive of the Temurin build that [release] describes, with
 * its SHA-256. The archive is checked against the API rather than against the `.sha256.txt` next to
 * it on GitHub, so that whoever could replace the archive there could not replace its sum as well.
 */
fun jdkSourcesChecksumUrl(release: Map<String, String>): String =
    "https://api.adoptium.net/v3/assets/release_name/eclipse/${temurinReleaseName(release)}?image_type=sources"

/** The SHA-256 that [json], the answer of [jdkSourcesChecksumUrl], gives for the archive at [url]. */
fun parseJdkSourcesChecksum(json: String, url: String): String {
    val binaries = (JsonSlurper().parseText(json) as Map<*, *>)["binaries"] as List<*>
    val archive = binaries.asSequence()
        .map { (it as Map<*, *>)["package"] as Map<*, *> }
        .firstOrNull { it["link"] == url }
    checkNotNull(archive) { "Adoptium's API does not list $url" }
    return archive["checksum"] as String
}

/** The SHA-256 that Adoptium's API gives for the source archive of the Temurin build that [release] describes. */
fun jdkSourcesChecksum(release: Map<String, String>): String =
    parseJdkSourcesChecksum(URI(jdkSourcesChecksumUrl(release)).toURL().readText(), jdkSourcesUrl(release))

/**
 * What the app says about the Java runtime it bundles, made from it by jlink: which build of
 * OpenJDK it is, its license, and where its complete source code is, as GPL version 2 asks of
 * whoever distributes it.
 */
fun javaRuntimeNotice(release: Map<String, String>): String {
    val repository = release.getValue("SOURCE_REPO").removeSuffix(".git")
    val commit = release.getValue("SOURCE").substringAfter("git:")
    val buildRepository = release.getValue("BUILD_SOURCE_REPO").removeSuffix(".git")
    val buildCommit = release.getValue("BUILD_SOURCE").substringAfter("git:")
    return """
        Java runtime: Eclipse Temurin ${release.getValue("SEMANTIC_VERSION")}, a build of OpenJDK by Eclipse Adoptium.

        The app's Java runtime, SlideCompose.app/Contents/runtime, is made from it with jlink, which
        leaves out modules, debug information and the JDK's commands without changing its code. It is
        licensed under the GNU General Public License, version 2, with the Classpath Exception. Its
        license, and the notices of the software it includes, are in
        SlideCompose.app/Contents/runtime/Contents/Home/legal/.

        Its complete source code:
        ${jdkSourcesUrl(release)}
        It is also in $repository at commit $commit,
        built with $buildRepository at commit $buildCommit.
    """.trimIndent()
}

/** Writes [javaRuntimeNotice] for the JDK whose `release` file is [release]. */
@CacheableTask
abstract class JavaRuntimeNotice : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val release: RegularFileProperty

    @get:OutputFile
    abstract val notice: RegularFileProperty

    @TaskAction
    fun write() {
        notice.get().asFile.writeText(javaRuntimeNotice(parseRelease(release.get().asFile.readText())) + "\n")
    }
}
