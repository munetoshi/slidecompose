package io.github.munetoshi.slidecompose.player.export

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.SlideExport
import io.github.munetoshi.slidecompose.deck.allFrames
import io.github.munetoshi.slidecompose.deck.components.Bullet
import io.github.munetoshi.slidecompose.deck.components.Reveal
import io.github.munetoshi.slidecompose.deck.components.TitleAndContent
import io.github.munetoshi.slidecompose.deck.components.TitleSlide
import io.github.munetoshi.slidecompose.deck.exportedFrames
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.representativeFrames
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.skia.Bitmap

/** A title slide, and a slide with a step that [Reveal] counts. */
private val deck = presentation(title = "Export") {
    slide { TitleSlide("Hello SlideCompose", "日本語のスライド") }
    slide {
        TitleAndContent("Agenda") {
            Bullet("First")
            Reveal(atStep = 1) { Bullet("Second") }
        }
    }
}

/** skpdf is built only on macOS; elsewhere every page is an image. */
private val isMac = System.getProperty("os.name").startsWith("Mac")

private fun textOf(file: File): String = Loader.loadPDF(file).use { PDFTextStripper().getText(it) }

private fun pagesOf(file: File): Int = Loader.loadPDF(file).use { it.numberOfPages }

class ExportTest : FunSpec({
    test("slides count their steps from their Reveals") {
        deck.slides.map { it.steps } shouldBe listOf(1, 2)
        deck.allFrames().size shouldBe 3
    }

    test("a PDF has a page per slide, or per step with build steps") {
        val directory = tempdir()
        deck.exportPdf(File(directory, "slides.pdf"))
        deck.exportPdf(File(directory, "builds.pdf"), PdfExportOptions(includeBuildSteps = true))
        pagesOf(File(directory, "slides.pdf")) shouldBe 2
        pagesOf(File(directory, "builds.pdf")) shouldBe 3
    }

    test("a vector PDF keeps the text, with ideographs as ideographs").config(enabled = isMac) {
        val file = File(tempdir(), "vector.pdf")
        val result = deck.exportPdf(file)
        result.vector shouldBe true
        result.imagePages.shouldBeEmpty()
        val text = textOf(file)
        text shouldContain "Hello SlideCompose"
        text shouldContain "日本語のスライド"
        text shouldContain "Second"
        text shouldNotContain "⽇"
    }

    test("an image PDF has no text") {
        val file = File(tempdir(), "images.pdf")
        deck.exportPdf(file, PdfExportOptions(vector = false, scale = 0.5f)).vector shouldBe false
        textOf(file).trim() shouldBe ""
    }
})

/** A deck of four slides of three steps each, exported in four ways. */
private val selective = presentation(title = "Selective") {
    slide(steps = 3) { TitleSlide("Final") }
    slide(steps = 3, export = SlideExport.step(0)) { TitleSlide("First step") }
    slide(steps = 3, export = SlideExport.steps(0, 2)) { TitleSlide("Two steps") }
    slide(steps = 3, export = SlideExport.None) { TitleSlide("Left out") }
}

class SlideExportTest : FunSpec({
    test("a slide is represented by its last step unless it says which") {
        selective.slides.map { it.representativeStep } shouldBe listOf(2, 0, 2, 2)
        selective.representativeFrames() shouldBe listOf(Frame(0, 2), Frame(1, 0), Frame(2, 2), Frame(3, 2))
    }

    test("an export shows the steps each slide gives, and leaves out the slides that say so") {
        selective.exportedFrames(includeBuildSteps = false) shouldBe listOf(Frame(0, 2), Frame(1, 0), Frame(2, 0), Frame(2, 2))
        selective.exportedFrames(includeBuildSteps = true) shouldBe (0..2).asSequence().flatMap { slide -> (0..2).asSequence().map { Frame(slide, it) } }.toList()
    }

    test("steps past the last count as the last") {
        val deck = presentation {
            slide(steps = 2, export = SlideExport.step(9)) {}
            slide(steps = 2, export = SlideExport.steps(1, 9)) {}
        }
        deck.slides.map { it.representativeStep } shouldBe listOf(1, 1)
        deck.exportedFrames(includeBuildSteps = false) shouldBe listOf(Frame(0, 1), Frame(1, 1))
    }

    test("steps are counted from 0, and steps() needs one") {
        shouldThrow<IllegalArgumentException> { SlideExport.step(-1) }
        shouldThrow<IllegalArgumentException> { SlideExport.steps() }
    }

    test("the PDF has a page for each exported step, and PNGs are named after their steps where a slide has several") {
        val directory = tempdir()
        selective.exportPdf(File(directory, "selective.pdf"), PdfExportOptions(vector = false, scale = 0.25f))
        pagesOf(File(directory, "selective.pdf")) shouldBe 4
        selective.exportPngs(File(directory, "pngs"), scale = 0.25f).map { it.name } shouldBe
            listOf("slide-001.png", "slide-002.png", "slide-003-01.png", "slide-003-03.png")
    }

    test("a still frame shows the end of an animation that an effect of its first frame starts") {
        val deck = presentation(title = "Settle") {
            slide {
                var shown by remember { mutableStateOf(false) }
                SideEffect { shown = true }
                val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(1000), label = "alpha")
                Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = alpha)))
            }
        }
        val image = deck.renderFrame(Frame(0, 0), scale = 0.1f)
        val pixels = Bitmap.makeFromImage(image)
        pixels.getColor(pixels.width / 2, pixels.height / 2) shouldBe Color.Red.toArgb()
    }

    test("a slide with a theme of its own draws its background in it, and the presentation's background over it") {
        val deck = presentation(theme = SlideTheme.Light) {
            background { Box(Modifier.size(20.dp).background(Color.Red)) }
            slide {}
            slide(theme = SlideTheme.Dark) {}
        }
        fun pixelsOf(slide: Int) = Bitmap.makeFromImage(deck.renderFrame(Frame(slide, 0), scale = 0.1f))
        val light = pixelsOf(0)
        val dark = pixelsOf(1)
        light.getColor(light.width / 2, light.height / 2) shouldBe SlideTheme.Light.background.toArgb()
        dark.getColor(dark.width / 2, dark.height / 2) shouldBe SlideTheme.Dark.background.toArgb()
        // The presentation's background, at the top left, shows on both.
        dark.getColor(0, 0) shouldBe Color.Red.toArgb()
        light.getColor(0, 0) shouldBe Color.Red.toArgb()
    }

    test("a deck that leaves out every slide is not exported") {
        val deck = presentation { slide(export = SlideExport.None) {} }
        shouldThrow<IllegalArgumentException> { deck.exportPdf(File(tempdir(), "none.pdf")) }
    }
})
