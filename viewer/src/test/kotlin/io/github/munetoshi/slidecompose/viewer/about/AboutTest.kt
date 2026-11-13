package io.github.munetoshi.slidecompose.viewer.about

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank

@OptIn(ExperimentalTestApi::class)
class AboutTest : FunSpec({
    test("parseThirdPartyEntries reads the build's tab-separated list") {
        parseThirdPartyEntries("org.apache.pdfbox\tfontbox, pdfbox\t3.0.8\tApache-2.0\tlicense-Apache-2.0 library-1\nSkia\t\tm150\tBSD-3-Clause\tcomponent-Skia\n") shouldContainExactly listOf(
            ThirdPartyEntry("org.apache.pdfbox", "fontbox, pdfbox", "3.0.8", "Apache-2.0", listOf("license-Apache-2.0", "library-1")),
            ThirdPartyEntry("Skia", "", "m150", "BSD-3-Clause", listOf("component-Skia")),
        )
    }

    test("the app lists what it bundles, with every text it names") {
        val entries = thirdPartyEntries()
        entries.map { it.title } shouldContain "org.apache.pdfbox"
        entries.map { it.title } shouldContain "Skia"
        entries.asSequence().flatMap { it.texts }.forEach { thirdPartyText(it).shouldNotBeBlank() }
        val pdfbox = entries.first { it.title == "org.apache.pdfbox" }
        pdfbox.texts.joinToString("\n") { thirdPartyText(it) } shouldContain "EXTERNAL COMPONENTS"
        entries.first { it.title.startsWith("Eclipse Temurin") }.texts.joinToString("\n") { thirdPartyText(it) } shouldContain "jdk-sources"
        appVersion shouldBe System.getProperty("slidecompose.version")
        appCopyright shouldBe "Copyright 2026 munetoshi"
    }

    test("the app's icon comes with the About resources") {
        appIcon(Density(1f)).shouldNotBeNull().intrinsicSize shouldBe Size(1024f, 1024f)
    }

    test("clicking an entry shows its license texts, and clicking again hides them") {
        runComposeUiTest {
            setContent { AboutScreen(dark = false) }
            onNodeWithText("SlideCompose").assertExists()
            onNode(hasScrollAction()).performScrollToNode(hasText("org.apache.pdfbox"))
            onNodeWithText("EXTERNAL COMPONENTS", substring = true).assertDoesNotExist()
            onNodeWithText("org.apache.pdfbox").performClick()
            onNodeWithText("EXTERNAL COMPONENTS", substring = true).assertExists()
            onNodeWithText("org.apache.pdfbox").performClick()
            onNodeWithText("EXTERNAL COMPONENTS", substring = true).assertDoesNotExist()
        }
    }
})
