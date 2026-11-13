package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.SlideExport
import io.github.munetoshi.slidecompose.deck.components.TitleSlide
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking

/** Five slides: two steps each, the third standing for itself by its first step; ten minutes planned. */
private fun deck() = presentation(duration = 10.minutes) {
    repeat(5) { index ->
        slide(steps = 2, export = if (index == 2) SlideExport.step(0) else SlideExport.Final) { TitleSlide("Slide ${index + 1}") }
    }
}

/** Draws no slides, and a one-pixel picture for each thumbnail asked for, which it records. */
private class PictureRenderer : SlideRenderer {
    val asked = mutableListOf<Frame>()

    @Composable
    override fun Stage(controller: PresentationController, onClick: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun Current(controller: PresentationController, modifier: Modifier) = Unit

    @Composable
    override fun Still(controller: PresentationController, frame: Frame, letterboxColor: Color, modifier: Modifier) = Unit

    override suspend fun thumbnail(controller: PresentationController, frame: Frame, width: Int, assetDirectory: File?): ImageBitmap {
        asked += frame
        return ImageBitmap(1, 1)
    }
}

/** The middle of the number under a picture in the list: the lowest text that says [number]. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.centerOf(number: String): Float =
    onAllNodesWithText(number).fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.boundsInRoot.center.x

@OptIn(ExperimentalTestApi::class)
class SlideListTest : FunSpec({
    test("the time bar is above the slide bar") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            setContent { PresenterView(controller, Modifier.fillMaxSize(), renderer = PictureRenderer()) }
            // The time left and planned, on the time bar's row, and the number of slides, on the slide bar's.
            val time = onAllNodesWithText("10:00")[0].fetchSemanticsNode().boundsInRoot.top
            val slides = onNodeWithText("5").fetchSemanticsNode().boundsInRoot.top
            time shouldBeLessThan slides
        }
    }

    test("the slide list opens and closes, showing each slide's representative step, the ones in view first") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            controller.goToSlide(1)
            val renderer = PictureRenderer()
            setContent { PresenterView(controller, Modifier.fillMaxSize(), renderer = renderer) }
            // Closed, it asks for no pictures.
            waitForIdle()
            renderer.asked shouldBe emptyList()
            onNodeWithText("SLIDES ▸").performClick()
            waitUntil(timeoutMillis = 5_000) { renderer.asked.size == 5 }
            // The current slide, the one before, then the ones after.
            renderer.asked shouldContainExactly listOf(Frame(1, 1), Frame(0, 1), Frame(2, 0), Frame(3, 1), Frame(4, 1))
            onNodeWithText("SLIDES ▾").performClick()
            onNodeWithText("SLIDES ▸").assertExists()
        }
    }

    test("the list shows the slide before the current one, and ends with the last slide at its right edge") {
        runComposeUiTest {
            val controller = PresentationController(presentation { repeat(30) { slide { TitleSlide("${it + 1}") } } })
            controller.goToSlide(14)
            setContent { PresenterView(controller, Modifier.fillMaxSize(), renderer = PictureRenderer(), showsSlideList = true) }
            waitForIdle()
            // Slide 14 is first in view, then 15, the current one; the slide bar says 15, 15 left and 30.
            onAllNodesWithText("13").fetchSemanticsNodes() shouldBe emptyList()
            centerOf("14") shouldBeLessThan centerOf("15")
            controller.goToSlide(29)
            waitForIdle()
            // The view's padding, then half a picture, to the middle of the last one.
            val middle = onRoot().fetchSemanticsNode().boundsInRoot.right - with(density) { (28.dp + 64.dp).toPx() }
            centerOf("30") shouldBe (middle plusOrMinus 1f)
            centerOf("25") shouldBeLessThan middle
        }
    }

    test("over the slide bar, a picture of the slide there shows above it") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            val renderer = PictureRenderer()
            setContent { PresenterView(controller, Modifier.fillMaxSize(), renderer = renderer) }
            // The third slide: as it stands for itself, its first step.
            onNodeWithTag(SlideBarTag).performMouseInput { moveTo(Offset(width * 0.5f, height / 2f)) }
            waitUntil(timeoutMillis = 5_000) { Frame(2, 0) in renderer.asked }
            onNodeWithText("3").assertExists()
            // In the current slide's part of the bar, the build there.
            onNodeWithTag(SlideBarTag).performMouseInput { moveTo(Offset(width * 0.05f, height / 2f)) }
            waitUntil(timeoutMillis = 5_000) { Frame(0, 0) in renderer.asked }
            onNodeWithTag(SlideBarTag).performMouseInput { exit() }
            waitForIdle()
            onAllNodesWithText("3").fetchSemanticsNodes() shouldBe emptyList()
        }
    }

    test("the preview shows a build of the current slide, and other slides as they stand for themselves") {
        val presentation = deck()
        previewFrame(Frame(1, 0), current = 1, presentation) shouldBe Frame(1, 0)
        previewFrame(Frame(2, 0), current = 1, presentation) shouldBe Frame(2, 0)
        previewFrame(Frame(3, 0), current = 1, presentation) shouldBe Frame(3, 1)
    }

    test("clicking a slide in the list goes to it") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            setContent { PresenterView(controller, Modifier.fillMaxSize(), renderer = PictureRenderer(), showsSlideList = true) }
            // "3" is only in the list: the slide bar says 1, 4 left and 5.
            onNodeWithText("3").performClick()
            controller.frame shouldBe Frame(2, 0)
        }
    }

    test("the slides drawn here make thumbnails as wide as asked") {
        val controller = PresentationController(deck())
        val picture = runBlocking { SlideRenderer.InProcess.thumbnail(controller, Frame(0, 1), 192, null) }!!
        picture.width shouldBe 192
        picture.height shouldBe 108
    }
})
