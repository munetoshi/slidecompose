package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** Fills [pixels] with [color], as the child draws, and returns their checksum. */
private fun draw(pixels: MappedPixels, color: Color): Long {
    val scene = ImageComposeScene(4, 2, Density(1f)) { Box(Modifier.fillMaxSize().background(color)) }
    scene.render(0L).use { pixels.write(it) }
    scene.close()
    return pixels.checksum()
}

class SharedPixelsTest : FunSpec({
    test("what the child draws, the parent reads") {
        val work = tempdir()
        val parent = SharedPixels.create(work, "stage", 4, 2)!!
        val child = MappedPixels.open(work, "stage", 4, 2)!!
        val scene = ImageComposeScene(4, 2, Density(1f)) { Box(Modifier.fillMaxSize().background(Color.Red)) }
        scene.render(0L).use { child.write(it) }
        scene.close()
        // Opaque red, BGRA premultiplied: blue, green, red, alpha.
        parent.read()!!.asSequence().chunked(4).distinct().toList() shouldBe listOf(listOf<Byte>(0, 0, -1, -1))
    }

    test("the child only opens a file the parent made at that size") {
        val work = tempdir()
        MappedPixels.open(work, "stage", 4, 2) shouldBe null
        SharedPixels.create(work, "stage", 4, 2)!!
        MappedPixels.open(work, "stage", 4, 3) shouldBe null
        MappedPixels.open(work, "stage", 4, 2) shouldNotBe null
    }

    test("a file the child shrank reads as nothing, many times over, without a crash") {
        val work = tempdir()
        val parent = SharedPixels.create(work, "stage", 640, 360)!!
        parent.read() shouldNotBe null
        RandomAccessFile(parent.file, "rw").use { it.setLength(1000) }
        // Enough reads for the JIT to compile the copy: a mapped read here raised SIGBUS and killed the JVM.
        repeat(5_000) { parent.read() shouldBe null }
    }

    test("a pixels file is not made through a link the child put in its place, and what the link points to is untouched") {
        val work = tempdir()
        val users = File(tempdir(), "notes.txt").apply { writeText("the user's") }
        val path = SharedPixels.fileOf(work, "stage", 4, 2).toPath()
        Files.createSymbolicLink(path, users.toPath())
        createExclusively(path, 32) shouldBe null
        users.readText() shouldBe "the user's"
        // Made afresh, it is the size asked for.
        Files.delete(path)
        createExclusively(path, 32)!!.use { it.size() shouldBe 32L }
    }

    test("the checksum follows the pixels") {
        val work = tempdir()
        SharedPixels.create(work, "still", 4, 2)!!
        val child = MappedPixels.open(work, "still", 4, 2)!!
        val red = draw(child, Color.Red)
        draw(child, Color.Red) shouldBe red
        draw(child, Color.Blue) shouldNotBe red
    }
})
