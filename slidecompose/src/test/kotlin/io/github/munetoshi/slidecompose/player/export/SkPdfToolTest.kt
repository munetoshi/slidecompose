package io.github.munetoshi.slidecompose.player.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** skpdf in the jar, which is built only on macOS. */
private val skPdfBytes: ByteArray? =
    SkPdfToolTest::class.java.getResourceAsStream(SK_PDF_RESOURCE)?.use { it.readBytes() }

private val isMacWithSkPdf = System.getProperty("os.name").startsWith("Mac") && skPdfBytes != null

/** Where skpdf was taken out to before, at a name anyone could know in advance. */
private fun oldSkPdfPath(bytes: ByteArray): File =
    File(File(System.getProperty("java.io.tmpdir"), "slidecompose-skpdf-%08x-%d".format(bytes.contentHashCode(), bytes.size)), "skpdf")

private fun File.permissions(): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(toPath()))

class SkPdfToolTest : FunSpec({
    test("a program is taken out into a new directory that only its user can open") {
        val first = extractTool(byteArrayOf(1, 2, 3), "tool")
        val second = extractTool(byteArrayOf(1, 2, 3), "tool")
        try {
            first.parentFile shouldNotBe second.parentFile
            first.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
            first.parentFile.permissions() shouldBe "rwx------"
            first.permissions() shouldBe "rwx------"
            first.canExecute() shouldBe true
        } finally {
            first.parentFile.deleteRecursively()
            second.parentFile.deleteRecursively()
        }
    }

    test("skpdf runs from a directory of its own, not from a file someone put where it used to be").config(enabled = isMacWithSkPdf) {
        val bytes = checkNotNull(skPdfBytes)
        val planted = oldSkPdfPath(bytes)
        val before = planted.takeIf(File::isFile)?.readBytes()
        planted.parentFile.mkdirs()
        // The old code ran any file there of the right length.
        planted.writeBytes(ByteArray(bytes.size))
        planted.setExecutable(true)
        try {
            val tool = skPdfTool.shouldNotBeNull()
            tool shouldNotBe planted
            tool.readBytes().contentEquals(bytes) shouldBe true
            tool.parentFile.permissions() shouldBe "rwx------"
        } finally {
            if (before != null) planted.writeBytes(before) else planted.parentFile.deleteRecursively()
        }
    }
})
