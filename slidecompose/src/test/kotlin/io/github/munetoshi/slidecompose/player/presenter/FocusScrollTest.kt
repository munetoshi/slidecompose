package io.github.munetoshi.slidecompose.player.presenter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FocusScrollTest : FunSpec({
    // Three items, 100 long each: the one before at 0, the current one at 100, the next at 200.
    fun scroll(viewport: Int) = focusScroll(previousStart = 0, currentStart = 100, nextEnd = 300, viewport = viewport)

    test("with room for all three, the one before is first") {
        scroll(400) shouldBe 0
        scroll(300) shouldBe 0
    }

    test("with less room, the one before goes first, little by little") {
        scroll(250) shouldBe 50
    }

    test("then the next, the current one staying first") {
        scroll(200) shouldBe 100
        scroll(150) shouldBe 100
        scroll(50) shouldBe 100
    }

    test("at either end, there is no item before or after to show") {
        focusScroll(previousStart = 0, currentStart = 0, nextEnd = 200, viewport = 400) shouldBe 0
        focusScroll(previousStart = 100, currentStart = 200, nextEnd = 300, viewport = 200) shouldBe 100
    }
})
