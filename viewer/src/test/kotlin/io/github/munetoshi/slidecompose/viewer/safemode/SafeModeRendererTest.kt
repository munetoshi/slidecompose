package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition
import io.github.munetoshi.slidecompose.player.presenter.PresenterView
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes

private const val DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Renderer") {
    slide(notes = "Say hello") { TitleSlide("One") }
    slide(notes = "Say goodbye") { TitleSlide("Two") }
}
"""

private val deckInfo = DeckInfo(
    title = "Stand-in",
    width = 1440,
    height = 1080,
    durationMillis = 10.minutes.inWholeMilliseconds,
    slides = listOf(
        SlideInfo(steps = 3, durationMillis = null, weight = 2f, pointer = PointerColor.White, notes = listOf("a"), representativeStep = 1),
        SlideInfo(steps = 1, durationMillis = 60_000, weight = 1f, pointer = PointerColor.Black, notes = emptyList()),
    ),
)

@OptIn(ExperimentalTestApi::class)
class SafeModeRendererTest : FunSpec({
    test("the stand-in has the deck's slides, steps, timing and pointer colors") {
        val standIn = standIn(deckInfo) { emptyList() }
        standIn.title shouldBe "Stand-in"
        standIn.size.width shouldBe 1440
        standIn.duration shouldBe 10.minutes
        standIn.defaultTransition shouldBe SlideTransition.None
        standIn.slides.map { it.steps } shouldBe listOf(3, 1)
        standIn.slides.map { it.duration } shouldBe listOf(null, 1.minutes)
        standIn.slides.map { it.weight } shouldBe listOf(2f, 1f)
        standIn.slides.map { it.style.pointer } shouldBe listOf(PointerColor.White, PointerColor.Black)
        standIn.slides.map { it.representativeStep } shouldBe listOf(1, 0)
    }

    test("the stand-in reads its notes each time, so they follow what the child sends") {
        val notes = mutableListOf(listOf("first"), emptyList())
        val standIn = standIn(deckInfo) { notes[it] }
        standIn.slides[0].notes shouldBe listOf("first")
        notes[0] = listOf("changed")
        standIn.slides[0].notes shouldBe listOf("changed")
    }

    test("the windows show the child's frames and notes, and act on the clicks it passes back") {
        val session = inProcessSession(write(tempdir(), "Slides.kt", DECK))
        session.await { session.status is SafeModeSession.Status.Loaded }
        val renderer = SafeModeRenderer(session, session.deck!!)
        val controller = renderer.controller
        try {
            runComposeUiTest {
                // Side by side in the test's 1024 x 768 window, with room left for the notes.
                setContent {
                    Row {
                        renderer.Stage(controller, onClick = controller::next, Modifier.size(400.dp, 225.dp).testTag("stage"))
                        PresenterView(controller, Modifier.size(600.dp, 768.dp), renderer = renderer)
                    }
                }
                waitUntil(timeoutMillis = 20_000) { session.frame != null && session.still != null }
                session.still!!.frame shouldBe Frame(1, 0)
                onNodeWithText("Say hello").assertExists()
                onNodeWithTag("stage").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.frame == Frame(1, 0) }
                onNodeWithText("Say goodbye").assertExists()
            }
        } finally {
            session.close()
        }
    }
})
