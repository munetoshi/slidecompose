package io.github.munetoshi.slidecompose.viewer.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class OptionsTest : FunSpec({
    test("reads a deck and export options") {
        val options = parseOptions(arrayOf("--pdf", "out.pdf", "--builds", "--scale", "3", "--theme", "Dark", "deck"))
        options.deck shouldBe File("deck").absoluteFile
        options.pdf shouldBe File("out.pdf").absoluteFile
        options.builds shouldBe true
        options.images shouldBe false
        options.scale shouldBe 3f
        options.theme shouldBe "Dark"
    }

    test("ignores the process serial number macOS may pass") {
        parseOptions(arrayOf("-psn_0_12345")).deck shouldBe null
    }
})
