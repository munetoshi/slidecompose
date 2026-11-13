package io.github.munetoshi.slidecompose.deck.code

import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CodeHighlightTest : FunSpec({
    test("with focus ranges, all but those parts of the code are dimmed, across lines too") {
        val code = "val first = 1\nval second = 2"
        val second = code.indexOf("second").let { it until it + "second".length }
        val text = highlightCode(code, "kotlin", SlideTheme.Light.code, focusRanges = listOf(second))
        val dimmed = text.spanStyles.filter { it.item.color.alpha < 1f }
        fun isDimmed(offset: Int) = dimmed.any { offset in it.start until it.end }
        code.indices.filter { code[it] != '\n' && !isDimmed(it) } shouldBe second.toList()
        // And the keyword out of focus gets no color of its own, which would win over the dimming.
        text.spanStyles.filter { it.start == code.indexOf("val second") && it.item.color.alpha == 1f } shouldBe emptyList()
    }
})
