package io.github.munetoshi.slidecompose.deck.components

import androidx.compose.ui.graphics.Color
import io.github.munetoshi.slidecompose.deck.code.DIMMED_CODE_ALPHA
import io.github.munetoshi.slidecompose.deck.code.highlightCode
import androidx.compose.ui.layout.layout
import io.github.munetoshi.slidecompose.deck.theme.CodeColors
import io.github.munetoshi.slidecompose.deck.theme.InlineCodeTheme
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.player.export.ideographOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** The colors of the [AnnotatedString][androidx.compose.ui.text.AnnotatedString]'s styled parts, with their text. */
private fun styledParts(code: String, language: String, colors: CodeColors, focusLines: IntRange? = null): List<Pair<String, Color>> =
    highlightCode(code, language, colors, focusLines).spanStyles.asSequence()
        .sortedBy { it.start }
        .map { code.substring(it.start, it.end) to it.item.color }
        .toList()

class TextTest : FunSpec({
    context("parseInlineCode") {
        test("drops the backticks and marks what was between them as code, set off by thin spaces") {
            val text = parseInlineCode("Read `step` in the slide", SlideTheme.Light)
            text.text shouldBe "Read  step  in the slide"
            text.getStringAnnotations(0, text.length).map { text.text.substring(it.start, it.end) } shouldContainExactly listOf("step")
            text.spanStyles.single().item.fontFamily shouldBe SlideTheme.Light.codeFontFamily
        }

        test("keeps a lone backtick") {
            parseInlineCode("it's a ` tick", SlideTheme.Light).text shouldBe "it's a ` tick"
        }
    }

    context("highlightCode") {
        val colors = SlideTheme.Light.code

        test("colors Kotlin keywords, types, calls, strings, numbers and comments") {
            styledParts("val x: Int = max(1, 2) // \"no\"\n\"hi\"", "kotlin", colors) shouldContainExactly listOf(
                "val" to colors.keyword,
                "Int" to colors.type,
                "max" to colors.function,
                "1" to colors.number,
                "2" to colors.number,
                "// \"no\"" to colors.comment,
                "\"hi\"" to colors.string,
            )
        }

        test("colors Python, whose comments start with #, and where // divides") {
            styledParts("def half(x): # c\n    return 'a' if x else x // 2", "python", colors) shouldContainExactly listOf(
                "def" to colors.keyword,
                "half" to colors.function,
                "# c" to colors.comment,
                "return" to colors.keyword,
                "'a'" to colors.string,
                "if" to colors.keyword,
                "else" to colors.keyword,
                "2" to colors.number,
            )
        }

        test("colors TypeScript, with strings between backticks over lines") {
            styledParts("const s: string = `a\n\${x}` // c", "typescript", colors) shouldContainExactly listOf(
                "const" to colors.keyword,
                "string" to colors.keyword,
                "`a\n\${x}`" to colors.string,
                "// c" to colors.comment,
            )
        }

        test("colors only strings, comments and numbers in languages it does not know") {
            styledParts("val x = \"s\" # 3", "ruby", colors) shouldContainExactly listOf("\"s\"" to colors.string, "3" to colors.number)
        }

        test("dims the lines outside the focus and leaves their tokens plain") {
            val parts = styledParts("val a = 1\nval b = 2", "kotlin", colors, focusLines = 2..2)
            parts.first() shouldBe ("val a = 1" to colors.text.copy(alpha = DIMMED_CODE_ALPHA))
            parts.drop(1) shouldContainExactly listOf("val" to colors.keyword, "2" to colors.number)
        }
    }

    context("SlideText") {
        test("a line with inline code is as tall as the line without, with its baseline where that line's is") {
            for (scale in listOf(0.88f, 0.94f)) {
                laidOut("abc `def`", scale) shouldBe laidOut("abc def", scale)
            }
        }

        test("follows the theme's inline code: no room for a background it does not draw, and its color") {
            val plain = SlideTheme.Light.copy(inlineCode = InlineCodeTheme(background = Color.Transparent, color = Color.Red))
            val code = parseInlineCode("a `b`", plain)
            code.text shouldBe "a b"
            code.spanStyles.single().item.color shouldBe Color.Red
            parseInlineCode("a `b`", SlideTheme.Light).text shouldBe "a \u2009b\u2009"
        }
    }

    context("ideographOf") {
        test("maps a Kangxi radical to its ideograph, unless the slides use the radical") {
            ideographOf("⽇", emptySet()) shouldBe "日"
            ideographOf("⽇", setOf(0x2F47)) shouldBe null
        }

        test("leaves other text alone") {
            ideographOf("日", emptySet()) shouldBe null
            ideographOf("A", emptySet()) shouldBe null
        }
    }
})

/** How tall a [SlideText] of [text] is, and where its first baseline is, with inline code [scale] times its size. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun laidOut(text: String, scale: Float): Pair<Int, Int> {
    var size = 0 to 0
    val theme = SlideTheme.Light.copy(inlineCode = InlineCodeTheme(scale = scale))
    val scene = androidx.compose.ui.ImageComposeScene(1600, 400, androidx.compose.ui.unit.Density(1f)) {
        androidx.compose.runtime.CompositionLocalProvider(io.github.munetoshi.slidecompose.deck.theme.LocalSlideTheme provides theme) {
            SlideText(
                text,
                androidx.compose.ui.Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    size = placeable.height to placeable[androidx.compose.ui.layout.FirstBaseline]
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
                style = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(80f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeightStyle = io.github.munetoshi.slidecompose.deck.theme.SlideLineHeightStyle),
            )
        }
    }
    scene.render().close()
    scene.close()
    return size
}
