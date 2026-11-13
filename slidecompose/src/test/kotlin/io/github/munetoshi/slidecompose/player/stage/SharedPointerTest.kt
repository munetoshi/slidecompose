package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.Cursor

/** A deck whose pointer is black, to tell it from the white background. */
private fun deck() = presentation {
    style { pointer(PointerColor.Black) }
    slide {}
}

/** Where, in a 200 × 200 box with the pointer's tip at its middle, the arrow is filled. */
private const val InsideArrowX = 103
private const val InsideArrowY = 120

/** Whether the black arrow shows over the white box. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.showsPointer(): Boolean = isDark(InsideArrowX, InsideArrowY)

/** Where only the arrow is filled, below the I-beam's foot, and where only the I-beam is, above the arrow's tip. */
private const val OnlyArrowX = 110
private const val OnlyArrowY = 130
private const val OnlyIBeamX = 100
private const val OnlyIBeamY = 90

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.isDark(x: Int, y: Int): Boolean =
    onRoot().captureToImage().toPixelMap()[x, y].red < 0.5f

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.showPointer(controller: PresentationController) {
    mainClock.autoAdvance = false
    setContent {
        Box(Modifier.size(200.dp).background(Color.White)) { SharedPointer(controller) }
    }
    mainClock.advanceTimeByFrame()
}

@OptIn(ExperimentalTestApi::class)
class SharedPointerTest : FunSpec({
    test("a pointer that stays still fades out, and comes back as soon as it moves") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            controller.pointer = Offset(0.5f, 0.5f)
            showPointer(controller)
            showsPointer() shouldBe true

            mainClock.advanceTimeBy(PointerIdleTime.inWholeMilliseconds - 500)
            showsPointer() shouldBe true

            mainClock.advanceTimeBy(500L + PointerFadeMillis + 100)
            showsPointer() shouldBe false

            controller.pointer = Offset(0.5f, 0.501f)
            mainClock.advanceTimeBy(100)
            showsPointer() shouldBe true
        }
    }

    test("a still pointer stays when the presenter turns hiding off") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            controller.hidesStillPointer = false
            controller.pointer = Offset(0.5f, 0.5f)
            showPointer(controller)

            mainClock.advanceTimeBy(PointerIdleTime.inWholeMilliseconds * 2)
            showsPointer() shouldBe true
        }
    }

    test("the pointer is an I-beam while the presenter's cursor is the text one") {
        runComposeUiTest {
            val controller = PresentationController(deck())
            controller.pointer = Offset(0.5f, 0.5f)
            showPointer(controller)
            isDark(OnlyArrowX, OnlyArrowY) shouldBe true
            isDark(OnlyIBeamX, OnlyIBeamY) shouldBe false

            controller.pointerShape = PointerShape.Text
            mainClock.advanceTimeByFrame()
            isDark(OnlyArrowX, OnlyArrowY) shouldBe false
            isDark(OnlyIBeamX, OnlyIBeamY) shouldBe true
        }
    }

    test("only the text cursor turns the pointer into an I-beam") {
        pointerShapeOf(Cursor(Cursor.TEXT_CURSOR)) shouldBe PointerShape.Text
        pointerShapeOf(Cursor(Cursor.HAND_CURSOR)) shouldBe PointerShape.Arrow
        pointerShapeOf(Cursor(Cursor.DEFAULT_CURSOR)) shouldBe PointerShape.Arrow
        pointerShapeOf(null) shouldBe PointerShape.Arrow
    }
})
