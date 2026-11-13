package io.github.munetoshi.slidecompose.player.stage

import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.presentation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Three slides with 2, 1 and 3 build steps. */
private fun deck(vararg steps: Int = intArrayOf(2, 1, 3)) = presentation {
    for (count in steps) slide(steps = count) {}
}

class PresentationControllerTest : FunSpec({
    test("next goes through each step, then on to the next slide, and stops at the end") {
        val controller = PresentationController(deck())
        val frames = generateSequence(controller.frame) { controller.nextFrame?.also { controller.next() } }
        frames.toList() shouldBe listOf(Frame(0, 0), Frame(0, 1), Frame(1, 0), Frame(2, 0), Frame(2, 1), Frame(2, 2))
        controller.frame shouldBe Frame(2, 2)
        controller.next()
        controller.frame shouldBe Frame(2, 2)
    }

    test("previous goes back to the last step of the slide before") {
        val controller = PresentationController(deck())
        controller.goTo(Frame(2, 0))
        controller.previous()
        controller.frame shouldBe Frame(1, 0)
        controller.previous()
        controller.frame shouldBe Frame(0, 1)
        controller.first()
        controller.previousFrame shouldBe null
    }

    test("first, last and goToSlide jump, clamped to the deck") {
        val controller = PresentationController(deck())
        controller.last()
        controller.frame shouldBe Frame(2, 2)
        controller.first()
        controller.frame shouldBe Frame(0, 0)
        controller.goToSlide(7)
        controller.frame shouldBe Frame(2, 0)
        controller.goTo(Frame(1, 5))
        controller.frame shouldBe Frame(1, 0)
    }

    test("moving starts the timer and marks when the slide was entered") {
        val controller = PresentationController(deck())
        controller.timer.isRunning shouldBe false
        controller.next()
        controller.timer.isRunning shouldBe true
    }

    test("reset stops the timer at zero, and moving on no longer starts it: it starts when started") {
        val controller = PresentationController(deck())
        controller.next()
        controller.timer.isRunning shouldBe true
        controller.resetTimer()
        controller.timer.isRunning shouldBe false
        controller.timer.elapsedMillis() shouldBe 0L
        controller.next()
        controller.previous()
        controller.timer.isRunning shouldBe false
        controller.timer.elapsedMillis() shouldBe 0L
        controller.timer.toggle()
        controller.timer.isRunning shouldBe true
    }

    test("reset before the timer ever ran keeps it from starting with the slides too") {
        val controller = PresentationController(deck())
        controller.resetTimer()
        controller.next()
        controller.timer.isRunning shouldBe false
    }

    test("replacePresentation keeps the position as far as the new deck reaches") {
        val controller = PresentationController(deck())
        controller.goTo(Frame(2, 2))
        val revision = controller.revision
        controller.replacePresentation(deck(2, 2))
        controller.frame shouldBe Frame(1, 1)
        controller.revision shouldBe revision + 1
    }

    test("an empty deck has nowhere to go") {
        val controller = PresentationController(presentation {})
        controller.next()
        controller.last()
        controller.goToSlide(3)
        controller.frame shouldBe Frame(0, 0)
        controller.currentSlide shouldBe null
        controller.nextFrame shouldBe null
    }
})
