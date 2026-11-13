package io.github.munetoshi.slidecompose.viewer.home

import io.github.munetoshi.slidecompose.viewer.MemoryPreferences
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File

class DeckTrustTest : FunSpec({
    test("trusting a folder trusts what is in it") {
        val directory = tempdir()
        val trust = DeckTrust(MemoryPreferences())
        trust.add(directory)
        trust.contains(write(directory, "parts/Intro.kt", "")) shouldBe true
        trust.contains(directory.parentFile) shouldBe false
    }

    test("trusting a file does not trust the files next to it") {
        val directory = tempdir()
        val trust = DeckTrust(MemoryPreferences())
        trust.add(write(directory, "Slides.kt", ""))
        trust.contains(File(directory, "Slides.kt")) shouldBe true
        trust.contains(write(directory, "Other.kt", "")) shouldBe false
    }

    test("keys fit in Preferences, however long the path") {
        keyOf(File("/" + "folder/".repeat(40) + "Slides.kt")).length shouldBeLessThanOrEqual 80
    }
})
