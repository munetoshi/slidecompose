package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.ui.text.AnnotatedString
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val Code = listOf("fun a() {", "    one()", "    two()", "}", "a()")

private fun folded(text: String, vararg regions: IntRange): FoldedText = fold(AnnotatedString(text), foldsIn(text, Code, regions.toList()), AnnotatedString(" ⋯"))

private fun hidden(text: String, vararg regions: IntRange): FoldedText = fold(AnnotatedString(text), hiddenIn(text, Code, regions.toList()).withoutOverlaps(), AnnotatedString(" ⋯"))

class CodeFoldingTest : FunSpec({
    test("a folded region shows its first line and a mark in place of the lines after it") {
        folded(Code.joinToString("\n"), 1..4).text.text shouldBe "fun a() { ⋯\na()"
        folded(Code.joinToString("\n"), 2..3).text.text shouldBe "fun a() {\n    one() ⋯\n}\na()"
    }

    test("offsets map across a folded region both ways, and into it to after its mark") {
        val text = Code.joinToString("\n")
        val mapping = folded(text, 1..4).mapping
        val afterFold = text.indexOf("a()", startIndex = 10)
        mapping.originalToTransformed(afterFold) shouldBe "fun a() { ⋯\n".length
        mapping.transformedToOriginal("fun a() { ⋯\n".length) shouldBe afterFold
        // Hidden code maps to after the mark; the mark maps to after the hidden code.
        mapping.originalToTransformed(text.indexOf("two")) shouldBe "fun a() { ⋯".length
        mapping.transformedToOriginal("fun a() { ⋯".length) shouldBe text.indexOf("\na()")
        mapping.originalToTransformed(0) shouldBe 0
    }

    test("a region follows its lines when lines come before it, and unfolds once its own lines change") {
        val moved = (listOf("// new") + Code).joinToString("\n")
        folded(moved, 1..4).text.text shouldBe "// new\nfun a() { ⋯\na()"
        val changed = Code.toMutableList().apply { this[2] = "    three()" }.joinToString("\n")
        folded(changed, 1..4).text.text shouldBe changed
    }

    test("hidden lines leave nothing in their place, at the start, in the middle and at the end") {
        val text = Code.joinToString("\n")
        hidden(text, 1..1).text.text shouldBe "    one()\n    two()\n}\na()"
        hidden(text, 2..3).text.text shouldBe "fun a() {\n}\na()"
        hidden(text, 5..5).text.text shouldBe "fun a() {\n    one()\n    two()\n}"
        val mapping = hidden(text, 2..3).mapping
        mapping.originalToTransformed(text.indexOf("}")) shouldBe "fun a() {\n".length
        mapping.transformedToOriginal("fun a() {\n".length) shouldBe text.indexOf("}")
    }

    test("folded and hidden regions together: marks where they show, and offsets mapped past both") {
        val text = Code.joinToString("\n")
        val folds = foldsIn(text, Code, listOf(2..3)) + hiddenIn(text, Code, listOf(5..5))
        val shown = fold(AnnotatedString(text), folds, AnnotatedString(" ⋯"))
        shown.text.text shouldBe "fun a() {\n    one() ⋯\n}"
        val markStart = "fun a() {\n    one()".length
        shown.marks.map { it.region to (it.range.start until it.range.end) } shouldBe listOf(2..3 to (markStart until markStart + 2))
        val brace = text.indexOf("}")
        shown.mapping.originalToTransformed(brace) shouldBe "fun a() {\n    one() ⋯\n".length
        shown.mapping.transformedToOriginal("fun a() {\n    one() ⋯\n".length) shouldBe brace
        // In what the second hides: to where it was, after the brace.
        shown.mapping.originalToTransformed(text.indexOf("a()", startIndex = brace)) shouldBe shown.text.length
    }

    test("a hidden region hides its lines, and only them") {
        val text = Code.joinToString("\n")
        val starts = Code.runningFold(0) { start, line -> start + line.length + 1 }
        for ((region, shown) in listOf(1..1 to listOf(2, 3, 4, 5), 2..3 to listOf(1, 4, 5), 5..5 to listOf(1, 2, 3, 4))) {
            val fold = hiddenIn(text, Code, listOf(region)).single()
            (1..5).filter { !fold.hides(starts[it - 1]) } shouldBe shown
        }
    }

    test("a region of one line, or one in another folded region, does not fold") {
        val text = Code.joinToString("\n")
        folded(text, 2..2).text.text shouldBe text
        folded(text, 1..4, 2..3).text.text shouldBe "fun a() { ⋯\na()"
    }
})
