package io.github.munetoshi.slidecompose.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

/** The SHA-256 of this file, in lowercase hex as `shasum -a 256` prints it. */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
    return HexFormat.of().formatHex(digest.digest())
}

/**
 * Fails unless this file, downloaded from [url], has the SHA-256 [expected], and deletes it then, so
 * that a replaced or broken download is never used. The message gives the actual sum, to check
 * against the publisher's when the expected one is out of date.
 */
fun File.verifySha256(url: String, expected: String) {
    val actual = sha256()
    if (!actual.equals(expected, ignoreCase = true)) {
        delete()
        throw GradleException("$url has SHA-256 $actual, but $expected was expected")
    }
}

/** Downloads [url] to [file]. */
fun download(url: String, file: File) {
    URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
}

/** Downloads [url] to [file], unless it is there from the same URL, and checks it against [sha256]. */
abstract class DownloadFile : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val file: RegularFileProperty

    @TaskAction
    fun download() {
        val target = file.get().asFile
        download(url.get(), target)
        target.verifySha256(url.get(), sha256.get())
        logger.lifecycle("Downloaded ${target.path}")
    }
}

/**
 * Downloads the zip at [url], checks it against [sha256] before anything is taken out of it, and
 * extracts the files matching [include] into [directory].
 */
abstract class DownloadZip @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val include: ListProperty<String>

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun download() {
        val zip = temporaryDir.resolve("download.zip")
        download(url.get(), zip)
        zip.verifySha256(url.get(), sha256.get())
        files.sync {
            from(archives.zipTree(zip))
            into(directory)
            include(this@DownloadZip.include.get())
        }
        zip.delete()
    }
}
