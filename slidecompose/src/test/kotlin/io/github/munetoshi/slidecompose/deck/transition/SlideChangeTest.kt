package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.components.SlideNumber
import io.github.munetoshi.slidecompose.deck.components.TitleSlide
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideStage
import io.github.munetoshi.slidecompose.player.stage.StaticSlide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** [count] slides, numbered in the corner by the background, changing by [transition]. */
private fun numbered(count: Int, transition: SlideTransition) = presentation(transition = transition) {
    background { SlideNumber(Modifier.fillMaxSize().padding(48.dp).wrapContentSize(Alignment.BottomEnd)) }
    repeat(count) { slide { TitleSlide("Slide ${it + 1}") } }
}

/** Where the text [text] is, or null when nothing says it. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.boundsOf(text: String): Rect? = onAllNodesWithText(text).fetchSemanticsNodes().singleOrNull()?.boundsInRoot

/** The old and new shifts of [look], x then y, with no negative zeros. */
private fun shiftsOf(look: ChangeLook): List<Float> =
    listOf(look.oldShift.x, look.oldShift.y, look.newShift.x, look.newShift.y).map { it + 0f }

@OptIn(ExperimentalTestApi::class)
class SlideChangeTest : FunSpec({
    test("the old and new slides' parts look as the slides do through each kind of change") {
        val staged = MoveTiming.Staged(moveMillis = 800, moveOnlyMillis = 200).timeline()
        val element = Motion(ResolvedTransition.ElementTransition(staged, LinearEasing, ResolvedTransition.None), forward = true)
        // The old fades out in the first 300 ms of 800, the new in in the last 300.
        element.lookAt(0f) shouldBe ChangeLook(1f, 0f, Offset.Zero, Offset.Zero)
        element.lookAt(0.5f) shouldBe ChangeLook(0f, 0f, Offset.Zero, Offset.Zero)
        element.lookAt(1f) shouldBe ChangeLook(0f, 1f, Offset.Zero, Offset.Zero)
        Motion(ResolvedTransition.Fade(400), forward = true).lookAt(0.25f) shouldBe ChangeLook(0.75f, 0.25f, Offset.Zero, Offset.Zero)
        Motion(ResolvedTransition.None, forward = true).lookAt(0f) shouldBe ChangeLook(0f, 1f, Offset.Zero, Offset.Zero)
        // Pushed to the left going forward, the new slide coming from the right; back, the other way.
        val push = ResolvedTransition.Push(PushDirection.Left, 600, LinearEasing)
        shiftsOf(Motion(push, forward = true).lookAt(0.25f)) shouldBe listOf(-0.25f, 0f, 0.75f, 0f)
        shiftsOf(Motion(push, forward = false).lookAt(0.25f)) shouldBe listOf(0.25f, 0f, -0.75f, 0f)
        shiftsOf(Motion(push.copy(direction = PushDirection.Up), forward = true).lookAt(0.25f)) shouldBe listOf(0f, -0.25f, 0f, 0.75f)
    }

    test("a number in the background changes with the slides, the old one staying where it was") {
        runComposeUiTest {
            // From 9 to 10: the new number is wider, and still the old one stays in place.
            val controller = PresentationController(numbered(10, SlideTransition.Fade(400)))
            controller.goTo(Frame(8, 0), animate = false)
            setContent { SlideStage(controller, Modifier.fillMaxSize()) }
            waitForIdle()
            val before = boundsOf("9 / 10")!!
            mainClock.autoAdvance = false
            controller.next()
            mainClock.advanceTimeBy(200)
            // Halfway, both show: the old where it was, the new in the corner.
            boundsOf("9 / 10") shouldBe before
            boundsOf("10 / 10")!!.right shouldBe before.right
            mainClock.autoAdvance = true
            waitForIdle()
            boundsOf("9 / 10") shouldBe null
        }
    }

    test("outside a stage, the number is the slide's") {
        val deck = numbered(3, SlideTransition.Fade())
        runComposeUiTest {
            setContent { StaticSlide(deck, Frame(1, 0), Modifier.fillMaxSize()) }
            boundsOf("2 / 3") shouldNotBe null
        }
    }

    test("without the count, the number is the slide's alone") {
        val deck = presentation {
            background { SlideNumber(showsCount = false) }
            repeat(3) { slide { TitleSlide("Slide ${it + 1}") } }
        }
        runComposeUiTest {
            setContent { StaticSlide(deck, Frame(1, 0), Modifier.fillMaxSize()) }
            boundsOf("2") shouldNotBe null
            boundsOf("2 / 3") shouldBe null
        }
    }
})
