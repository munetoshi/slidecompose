package io.github.munetoshi.slidecompose.viewer.home

import io.github.munetoshi.slidecompose.viewer.TWO_SLIDES
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class TrustPromptTest : FunSpec({
    test("only a .kt file opens in the editor, links followed; anything else shows in Finder") {
        val directory = tempdir()
        val kotlin = write(directory, "Slides.kt", TWO_SLIDES).canonicalFile
        val command = write(directory, "x.command", "#!/bin/sh\n")
        editorFileOf(kotlin) shouldBe kotlin
        editorFileOf(command) shouldBe null
        val toCommand = Files.createSymbolicLink(File(directory, "linked/x.kt").apply { parentFile.mkdirs() }.toPath(), command.toPath()).toFile()
        editorFileOf(toCommand) shouldBe null
        val toKotlin = Files.createSymbolicLink(File(directory, "other/x.kt").apply { parentFile.mkdirs() }.toPath(), kotlin.toPath()).toFile()
        editorFileOf(toKotlin) shouldBe kotlin

        val folder = tempdir()
        editorFileOf(folder) shouldBe null
        val intro = write(folder, "Intro.kt", "").canonicalFile
        write(folder, "Slides.kt", "")
        editorFileOf(folder) shouldBe intro
    }
})
