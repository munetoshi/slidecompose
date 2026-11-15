package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.code.CodeBlock
import io.github.munetoshi.slidecompose.deck.code.CodeBlockStates
import io.github.munetoshi.slidecompose.deck.components.TitleSlide
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A slide with an editable block of [code] under [id], then a slide without it, changing by [transition]. */
private fun deckWithCode(id: String, code: String, transition: SlideTransition) = presentation(transition = transition) {
    slide { CodeBlock(code, id = id, editable = true) }
    slide { TitleSlide("Next") }
}

@OptIn(ExperimentalTestApi::class)
class CodeBlockOnStageTest : FunSpec({
    // Back before a frame showed the next slide, the block never went, and keeps what was typed.
    val backs = listOf("no transition" to 32L, "no transition" to 100L, "a fade" to 32L, "a fade" to 200L)
    for ((name, millis) in backs) {
        test("going to the next slide and back starts the code over, with $name, back after $millis ms") {
            runComposeUiTest {
                val transition = if (name == "a fade") SlideTransition.Fade(400) else SlideTransition.None
                val id = "back-$name-$millis"
                val controller = PresentationController(deckWithCode(id, "val a = 1", transition))
                setContent { SlideStage(controller, Modifier.fillMaxSize()) }
                waitForIdle()
                val state = CodeBlockStates.of(id, "val a = 1")
                state.text = TextFieldValue("val a = 2")
                mainClock.autoAdvance = false
                controller.next()
                mainClock.advanceTimeBy(millis)
                // A frame showed the next slide, a fade's halfway.
                onAllNodesWithText("Next").fetchSemanticsNodes().size shouldBe 1
                controller.previous()
                mainClock.autoAdvance = true
                waitForIdle()
                state.code shouldBe "val a = 1"
            }
        }
    }

    val stepTransitions = listOf<Pair<String, SlideTransition?>>("none set" to null, "None" to SlideTransition.None, "a fade" to SlideTransition.Fade(400))
    for ((name, stepTransition) in stepTransitions) {
        test("a build keeps what was typed, with the step transition $name") {
            runComposeUiTest {
                val id = "build-$name"
                val deck = presentation { slide(steps = 2, stepTransition = stepTransition) { CodeBlock("val b = 1", id = id, editable = true) } }
                val controller = PresentationController(deck)
                setContent { SlideStage(controller, Modifier.fillMaxSize()) }
                waitForIdle()
                val state = CodeBlockStates.of(id, "val b = 1")
                state.text = TextFieldValue("val b = 2")
                controller.next()
                waitForIdle()
                state.code shouldBe "val b = 2"
            }
        }
    }

    test("a reload starts the code over, on the slide it was on") {
        runComposeUiTest {
            val deck: () -> Presentation = { deckWithCode("reload", "val c = 1", SlideTransition.None) }
            val controller = PresentationController(deck())
            setContent { SlideStage(controller, Modifier.fillMaxSize()) }
            waitForIdle()
            val state = CodeBlockStates.of("reload", "val c = 1")
            state.text = TextFieldValue("val c = 2")
            controller.replacePresentation(deck())
            waitForIdle()
            (controller.frame.slide to state.code) shouldBe (0 to "val c = 1")
        }
    }
})
