package io.github.munetoshi.slidecompose.buildlogic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `shasum -a 256` of a file holding "abc", which FIPS 180-2 also gives. */
private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

/** Writes a zip of [entries], names to texts, as [file]. */
private fun writeZip(file: File, entries: Map<String, String>): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, text) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return file
}

/** A zip's entries laid out like a Skia archive: headers and libraries to take, and a file to leave. */
private val skiaLikeEntries = mapOf("include/core/SkCanvas.h" to "header", "out/libskia.a" to "library", "README" to "left out")

/** A [DownloadZip] in a project in [projectDirectory] that takes [zip] into [directory] if it has [sha256]. */
private fun downloadZip(projectDirectory: File, zip: File, sha256: String, directory: File): DownloadZip {
    val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
    val task = project.tasks.register("downloadZip", DownloadZip::class.java).get()
    task.url.set(zip.toURI().toString())
    task.sha256.set(sha256)
    task.include.set(listOf("include/**", "out/**/*.a"))
    task.directory.set(directory)
    return task
}

/** The paths of the files under this directory, relative to it, or none when it does not exist. */
private fun File.relativeFilePaths(): List<String> =
    walk().filter { it.isFile }.map { it.relativeTo(this).invariantSeparatorsPath }.toList()

class DownloadsTest : FunSpec({
    test("sha256 is the file's SHA-256 in lowercase hex") {
        tempdir().resolve("abc").apply { writeText("abc") }.sha256() shouldBe ABC_SHA256
    }

    test("verifySha256 accepts the sum in either case") {
        val file = tempdir().resolve("abc").apply { writeText("abc") }
        file.verifySha256("https://example.com/abc", ABC_SHA256.uppercase())
        file.shouldExist()
    }

    test("verifySha256 fails giving the actual sum, and deletes the file") {
        val file = tempdir().resolve("abc").apply { writeText("abd") }
        val actual = file.sha256()
        val error = shouldThrow<GradleException> { file.verifySha256("https://example.com/abc", ABC_SHA256) }
        error.message shouldBe "https://example.com/abc has SHA-256 $actual, but $ABC_SHA256 was expected"
        file.shouldNotExist()
    }

    context("DownloadZip") {
        test("extracts the files it includes when the sum is right") {
            val zip = writeZip(tempdir().resolve("skia.zip"), skiaLikeEntries)
            val directory = tempdir().resolve("skia")
            downloadZip(tempdir(), zip, zip.sha256(), directory).download()
            directory.relativeFilePaths() shouldContainExactlyInAnyOrder listOf("include/core/SkCanvas.h", "out/libskia.a")
        }

        test("fails before extracting anything when the sum is wrong") {
            val zip = writeZip(tempdir().resolve("skia.zip"), skiaLikeEntries)
            val directory = tempdir().resolve("skia")
            val task = downloadZip(tempdir(), zip, ABC_SHA256, directory)
            val error = shouldThrow<GradleException> { task.download() }
            error.message shouldContain "has SHA-256 ${zip.sha256()}"
            directory.relativeFilePaths() shouldBe emptyList()
        }
    }
})
