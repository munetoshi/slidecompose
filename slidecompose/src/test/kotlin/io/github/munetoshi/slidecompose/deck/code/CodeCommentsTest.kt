package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CodeCommentsTest : FunSpec({
    test("the caret's line is commented out at its indent, and the caret stays on the same code") {
        val value = TextFieldValue("fun a() {\n    one()\n}", TextRange(16))
        val toggled = toggledComments(value, "//")
        toggled.text shouldBe "fun a() {\n    // one()\n}"
        toggled.selection shouldBe TextRange(19)
    }

    test("the lines of a selection are commented out at the least indent among them, blank lines left as they are") {
        val code = "context(a) {\n    one()\n\n        two()\n}"
        val toggled = toggledComments(TextFieldValue(code, TextRange(13, code.indexOf("two") + 3)), "//")
        toggled.text shouldBe "context(a) {\n    // one()\n\n    //     two()\n}"
    }

    test("a selection that ends at the start of a line leaves that line alone") {
        val toggled = toggledComments(TextFieldValue("a()\nb()", TextRange(0, 4)), "//")
        toggled.text shouldBe "// a()\nb()"
    }

    test("lines that are all comments are commented in again, with the space after the mark") {
        val code = "    // one()\n    //two()\nthree()"
        toggledComments(TextFieldValue(code, TextRange(0, 24)), "//").text shouldBe "    one()\n    two()\nthree()"
        // With a line that is not a comment among them, they are all commented out.
        toggledComments(TextFieldValue(code, TextRange(0, code.length)), "//").text shouldBe "//     // one()\n//     //two()\n// three()"
    }

    test("commenting out and in again gives the code back, with the selection where it was") {
        val value = TextFieldValue("val a = 1\nval b = 2", TextRange(4, 15))
        toggledComments(toggledComments(value, "//"), "//") shouldBe value
    }

    test("Python comments with #, and the languages with C's comments with //") {
        lineCommentOf("python") shouldBe "#"
        lineCommentOf("kotlin") shouldBe "//"
        lineCommentOf("typescript") shouldBe "//"
    }
})
