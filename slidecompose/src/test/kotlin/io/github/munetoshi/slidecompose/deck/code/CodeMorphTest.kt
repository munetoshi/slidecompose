package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.theme.SlideLineHeightStyle
import io.github.munetoshi.slidecompose.player.export.renderTransition
import io.github.munetoshi.slidecompose.player.stage.StaticSlide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private val Size = 40.sp

/** Two slides, the first laying out its content at the top left, the second at the bottom right. */
private fun twoSlides(first: @Composable BoxScope.() -> Unit, second: @Composable BoxScope.() -> Unit) =
    presentation {
        slide { Box(Modifier.fillMaxSize().padding(80.dp)) { first() } }
        slide { Box(Modifier.fillMaxSize().padding(80.dp)) { second() } }
    }

/** The frames of moving from the first slide to the second at 0 ms, halfway and after the end. */
private fun Presentation.transitionFrames(): List<Image> = renderTransition(Frame(0, 0), Frame(1, 0), listOf(0, 450, 1500), scale = 0.5f)

/** [frame] of [presentation] drawn on its own, as [transitionFrames] draws it. */
private fun still(presentation: Presentation, frame: Frame): Image {
    val scene = ImageComposeScene(presentation.size.width / 2, presentation.size.height / 2, Density(0.5f)) { StaticSlide(presentation, frame, Modifier.fillMaxSize()) }
    return scene.render(0).also { scene.close() }
}

/** The pixels of [image] as BGRA. */
private fun pixelsOf(image: Image): ByteArray =
    Bitmap().use { bitmap ->
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(image.width, image.height)))
        check(image.readPixels(bitmap))
        checkNotNull(bitmap.readPixels())
    }

/**
 * The pixels of plain code text, faded or not: grey and clearly darker than the slide and the
 * boxes, unlike the colored keywords and the run button. Code drawn twice counts twice.
 */
private fun Image.ink(): Double =
    pixelsOf(this).asSequence().chunked(4) { (b, g, r) -> Triple(r.toInt() and 0xFF, g.toInt() and 0xFF, b.toInt() and 0xFF) }
        .count { (r, g, b) -> (r + g + b) / 3 < 190 && maxOf(r, g, b) - minOf(r, g, b) < 40 }
        .toDouble()

/** The share of pixels that differ clearly between [a] and [b]. */
private fun differingShare(a: Image, b: Image): Double {
    val one = pixelsOf(a)
    val other = pixelsOf(b)
    val differing = one.indices.step(4).count { i -> (0..2).any { kotlin.math.abs((one[i + it].toInt() and 0xFF) - (other[i + it].toInt() and 0xFF)) > 64 } }
    return differing.toDouble() / (one.size / 4)
}

/** The left edges of what differs from the slide's background (the block) and of the code's [ink] in [image], in pixels. */
private fun boxAndInkLefts(image: Image): Pair<Int, Int> {
    val pixels = pixelsOf(image)
    val rgb = { i: Int -> Triple(pixels[4 * i + 2].toInt() and 0xFF, pixels[4 * i + 1].toInt() and 0xFF, pixels[4 * i].toInt() and 0xFF) }
    val background = rgb(0)
    val columns = { test: (Triple<Int, Int, Int>) -> Boolean ->
        (0 until image.width * image.height).filter { test(rgb(it)) }.minOf { it % image.width }
    }
    val box = columns { (r, g, b) -> kotlin.math.abs(r - background.first) + kotlin.math.abs(g - background.second) + kotlin.math.abs(b - background.third) > 6 }
    val ink = columns { (r, g, b) -> (r + g + b) / 3 < 190 && maxOf(r, g, b) - minOf(r, g, b) < 40 }
    return box to ink
}

/** A part of an image, in fractions of its width and height. */
private class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Whether pixel [index] of an image [width] by [height] pixels is in it. */
    fun holds(index: Int, width: Int, height: Int): Boolean {
        val x = index % width
        val y = index / width
        return x >= width * left && x < width * right && y >= height * top && y < height * bottom
    }
}

private val TopLeft = Region(0f, 0f, 0.5f, 0.5f)
private val BottomRight = Region(0.5f, 0.5f, 1f, 1f)

/** The share of pixels in [region] that differ between [a] and [b], even as faintly as text fading in or out. */
private fun differingShareIn(a: Image, b: Image, region: Region): Double {
    val one = pixelsOf(a)
    val other = pixelsOf(b)
    val pixels = (0 until a.width * a.height).filter { region.holds(it, a.width, a.height) }
    return pixels.count { differsFaintly(one, other, it) }.toDouble() / pixels.size
}

/** Whether pixel [index] differs between [one] and [other], BGRA, by more than antialiasing noise. */
private fun differsFaintly(one: ByteArray, other: ByteArray, index: Int): Boolean =
    (0..2).any { kotlin.math.abs((one[index * 4 + it].toInt() and 0xFF) - (other[index * 4 + it].toInt() and 0xFF)) > 24 }

/** [code] with [folds] folded, as a block shows it at the top left, laid out by [measurer]. */
private fun placedCode(code: String, folds: List<IntRange>, measurer: TextMeasurer): PlacedCode {
    // Rows as tall as one another, as code blocks lay them out.
    val style = TextStyle(fontSize = Size, lineHeight = Size * 1.45f, lineHeightStyle = SlideLineHeightStyle)
    val folded = fold(AnnotatedString(code), foldsIn(code, code.split('\n'), folds), AnnotatedString(" ⋯"))
    return PlacedCode(shownCode(code, folded, measurer.measure(folded.text, style, softWrap = false)), style, Offset.Zero, null)
}

/** [code] with only line [focus] (from 0) in focus, the rest dimmed, as a block shows it, laid out by [measurer]. */
private fun focusedCode(code: String, focus: Int, measurer: TextMeasurer, style: TextStyle): ShownCode {
    val lines = code.split('\n')
    val dimmed = buildAnnotatedString {
        lines.forEachIndexed { row, line ->
            if (row > 0) append("\n")
            if (row == focus) append(line) else withStyle(SpanStyle(color = Color.Gray)) { append(line) }
        }
    }
    val folded = fold(dimmed, emptyList(), AnnotatedString(""))
    return shownCode(code, folded, measurer.measure(dimmed, style), inFocus = AnnotatedString(code))
}

/** [code] with all but [focus] (offsets) dimmed, as a build that picks the parts to show in focus has it. */
private fun partFocusedCode(code: String, focus: IntRange, measurer: TextMeasurer, style: TextStyle): ShownCode {
    val dimmed = buildAnnotatedString {
        withStyle(SpanStyle(color = Color.Gray)) { append(code.substring(0, focus.first)) }
        append(code.substring(focus))
        withStyle(SpanStyle(color = Color.Gray)) { append(code.substring(focus.last + 1)) }
    }
    val folded = fold(dimmed, emptyList(), AnnotatedString(""))
    return shownCode(code, folded, measurer.measure(dimmed, style), inFocus = AnnotatedString(code), focusesParts = true)
}

class CodeMorphTest : FunSpec({
    test("a token goes with its neighbors, not to an earlier line that has it too") {
        val old = SourceCode("fun showTwice(x: ???) {\n    println(x.show())\n}")
        val new = SourceCode("interface Show<T> { fun show(value: T): String }\nfun <T> showTwice(x: T) { println(x.show()) }")
        val matches = matchTokens(old, new)
        val funs = new.tokens.indices.filter { new.tokens[it].text == "fun" }
        matches[funs.last()] shouldBe 0
        matches[funs.first()] shouldBe -1
        matches[new.tokens.indexOfFirst { it.text == "println" }] shouldBe old.tokens.indexOfFirst { it.text == "println" }
    }

    test("a token goes with its neighbors, not to a later place that has it too") {
        val matches = matchTokens(SourceCode("a b"), SourceCode("a b x b"))
        matches.toList() shouldBe listOf(0, 1, -1, -1)
    }

    test("a word moved before the call into a block around it moves, out of the order of the tokens") {
        val old = SourceCode("context() {\n    greet(\"Kotlin\", ConsoleLogger)\n}")
        val new = SourceCode("context(ConsoleLogger) {\n    greet(\"Kotlin\")\n}")
        val matches = matchTokens(old, new)
        matches[new.tokens.indexOfFirst { it.text == "ConsoleLogger" }] shouldBe old.tokens.indexOfFirst { it.text == "ConsoleLogger" }
        matches[new.tokens.indexOfFirst { it.text == "greet" }] shouldBe old.tokens.indexOfFirst { it.text == "greet" }
    }

    test("a parameter moved past a line that stays moves, but a symbol or a word left over twice does not") {
        val old = SourceCode("context()\nfun greet(\n    name: String,\n    logger: Logger\n) {\n    aa(x)\n    bb(x)\n}")
        val new = SourceCode("context(logger: Logger)\nfun greet(\n    name: String\n) {\n    val z = x + 1 + 2 + 3\n}")
        val matches = matchTokens(old, new)
        matches[new.tokens.indexOfFirst { it.text == "logger" }] shouldBe old.tokens.indexOfFirst { it.text == "logger" }
        matches[new.tokens.indexOfFirst { it.text == "Logger" }] shouldBe old.tokens.indexOfFirst { it.text == "Logger" }
        // The colon after logger goes, and comes anew: symbols are too many to tell apart.
        matches[new.tokens.indexOfFirst { it.row == 0 && it.text == ":" }] shouldBe -1
        // Two x go, so the one that comes is new.
        matches[new.tokens.indexOfFirst { it.text == "x" }] shouldBe -1
    }

    test("a part anchored to an id on both sides keeps to its counterpart, rather than to a line more alike") {
        // A build adds an extension with the same context as the function under it, whose context changes.
        val oldText = "}\n\ncontext(o: Order<T>)\nfun <T> first(a: T, b: T): T = if (o.compare(a, b) < 0) a else b"
        val newText = "}\ncontext(o: Order<T>) operator fun <T> T.compareTo(other: T) = o.compare(this, other)\n\ncontext(_: Order<T>)\nfun <T> first(a: T, b: T): T = if (a < b) a else b"
        val oldContext = oldText.lines()[2].let { oldText.indexOf(it) }.let { it until it + "context(o: Order<T>)".length }
        val newContext = newText.indexOf("context(_: Order<T>)").let { it until it + "context(_: Order<T>)".length }
        fun SourceCode.at(row: Int, text: String) = tokens.indexOfFirst { it.row == row && it.text == text }

        // Unanchored, the context goes up into the extension, which shares more with it.
        val unanchored = matchTokens(SourceCode(oldText), SourceCode(newText))
        val plainNew = SourceCode(newText)
        unanchored[plainNew.at(1, "context")] shouldBe SourceCode(oldText).at(2, "context")

        val old = SourceCode(oldText).anchoredBy(mapOf("first" to oldContext))
        val new = SourceCode(newText).anchoredBy(mapOf("first" to newContext))
        val matches = matchTokens(old, new)
        matches[new.at(3, "context")] shouldBe old.at(2, "context")
        matches[new.at(3, "Order")] shouldBe old.at(2, "Order")
        // The parameter's name changes: o goes, and _ comes.
        matches[new.at(3, "_")] shouldBe -1
        // The extension's context is all new.
        new.tokens.indices.filter { new.tokens[it].row == 1 && new.tokens[it].column < "context(o: Order<T>)".length }.all { matches[it] == -1 } shouldBe true
        // The rest matches as before.
        matches[new.at(4, "first")] shouldBe old.at(3, "first")
    }

    test("an id anchored on one side only changes nothing") {
        val oldText = "context(o: Order<T>)\nfun f() = 1"
        val newText = "context(o: Order<T>) fun g() = 2\ncontext(_: Order<T>)\nfun f() = 1"
        val old = SourceCode(oldText).anchoredBy(mapOf("f" to (0 until 20)))
        matchTokens(old, SourceCode(newText)).toList() shouldBe matchTokens(SourceCode(oldText), SourceCode(newText)).toList()
    }

    test("a folded block anchors the parts of its code where they are in the code, and the mark of a fold to nothing") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val code = "class A {\n    fun a() = 1\n}\nmain()"
        val style = TextStyle(fontSize = Size, lineHeight = Size * 1.45f, lineHeightStyle = SlideLineHeightStyle)
        val folded = fold(AnnotatedString(code), foldsIn(code, code.split('\n'), listOf(1..3)), AnnotatedString(" ⋯"))
        val main = code.indexOf("main").let { it until it + 4 }
        val shown = shownCode(code, folded, measurer.measure(folded.text, style, softWrap = false), anchors = mapOf("main" to main, "class" to (0 until 5)))
        val source = shown.source
        source.anchorOf(source.tokens.indexOfFirst { it.text == "main" }) shouldBe "main"
        source.anchorOf(source.tokens.indexOfFirst { it.text == "class" }) shouldBe "class"
        source.anchorOf(source.tokens.indexOfFirst { it.text == "⋯" }) shouldBe null
        source.anchorOf(source.tokens.indexOfFirst { it.text == "fun" }) shouldBe null
    }

    test("code moving into a block keeps up with the block as it moves") {
        // A token 10 into the box at the start and 10 into its text at the end, which starts at the box.
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val token = MorphToken(measurer.measure("a"), start = Offset(110f, 0f), end = Offset(10f, 0f), startScale = 1f)
        val boxAtStart = Offset(100f, 0f)
        // Halfway: the box has moved from 100 to 300 as far as the code has.
        val box = Offset(200f, 0f)
        val shifts = morphShifts(boxAtStart, box, text = box)
        val progress = MorphProgress(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, 0f)
        (box + token.placeAt(shifts.start, shifts.end, progress)) shouldBe Offset(210f, 0f)
    }

    test("a closing line goes with the lines before it, not with a blank line after another") {
        val old = SourceCode("a {\n    b\n}\n\nc")
        val new = SourceCode("a {\n    b\n}\nd {\n    e\n}\n\nc")
        val matches = matchTokens(old, new)
        val closings = new.tokens.indices.filter { new.tokens[it].text == "}" }
        matches[closings.first()] shouldBe old.tokens.indexOfFirst { it.text == "}" }
        matches[closings.last()] shouldBe -1
    }

    test("a word goes no further than a line alike, and a body folded into { … } takes its closing brace") {
        val old = SourceCode("interface Logger { fun log(message: String) }\n\ncontext(logger: Logger)\nfun greet(name: String) {\n    logger.log(\"Hello\")\n}")
        val new = SourceCode(
            "context(logger: Logger)\nfun greet(name: String) { … }\n\nobject ConsoleLogger : Logger {\n    override fun log(message: String) = println(message)\n}\n\n" +
                "context(ConsoleLogger) {\n    greet(\"Kotlin\")\n}",
        )
        val matches = matchTokens(old, new)
        // The log of greet's body goes rather than into ConsoleLogger, which is new.
        matches[new.tokens.indexOfFirst { it.row == 4 && it.text == "log" }] shouldBe -1
        // greet's closing brace moves up after …, and the other closing braces are new.
        val closings = new.tokens.indices.filter { new.tokens[it].text == "}" }
        closings.map { matches[it] } shouldBe listOf(old.tokens.indexOfLast { it.text == "}" }, -1, -1)
        new.tokens.indices.filter { new.tokens[it].row == 1 && new.tokens[it].text != "…" }.all { matches[it] >= 0 } shouldBe true
    }

    test("what a fold hides slides up under the line it folds into, rather than into a line added that looks like it") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val before = placedCode("class A {\n    fun a() = 1\n}\nmain()", emptyList(), measurer)
        val after = placedCode("class A {\n    fun a() = 1\n}\nfun a() = 1\nmain()", listOf(1..3), measurer)
        val tokens = morphTokens(listOf(before), after, measurer)
        // "class A {" and "main()" move.
        tokens.count { it.start != null && it.end != null && it.clip == null } shouldBe 6
        // What the fold hides, "fun a() = 1" and "}", moves up as far as main() and shows only
        // under the first line, in a part that closes.
        val folding = tokens.filter { it.clip != null }
        folding.size shouldBe 7
        val rowHeight = before.shown.rowHeight
        folding.forEach { token ->
            val clip = checkNotNull(token.clip)
            checkNotNull(token.end).y shouldBe (checkNotNull(token.start).y - 2 * rowHeight plusOrMinus 1.5f)
            clip.startTop shouldBe (rowHeight plusOrMinus 1.5f)
            clip.startBottom shouldBe (3 * rowHeight plusOrMinus 1.5f)
            clip.endBottom shouldBe clip.endTop
            // It fades out over a row under the line.
            clip.feather shouldBe rowHeight
        }
        // The line added, and the mark of the fold, come.
        tokens.filter { it.start == null }.map { it.layout.layoutInput.text.text } shouldBe listOf("⋯", "fun", "a", "(", ")", "=", "1")
        // Unfolding, it slides back down.
        val unfolding = morphTokens(listOf(after), placedCode("class A {\n    fun a() = 1\n}\nfun a() = 1\nmain()", emptyList(), measurer), measurer).filter { it.clip != null }
        unfolding.size shouldBe 7
        unfolding.forEach { checkNotNull(it.end).y shouldBe (checkNotNull(it.start).y + 2 * rowHeight plusOrMinus 1.5f) }
    }

    test("a token that moves and changes colors keeps its old colors to draw until the code that comes fades in") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = Size)
        val code = "val a = 1\nval b = 2"
        fun placed(colored: AnnotatedString) = PlacedCode(shownCode(code, colored, measurer.measure(colored, style)), style, Offset.Zero, null)
        val dimmed = buildAnnotatedString {
            append("val a = 1\n")
            withStyle(SpanStyle(color = Color.Gray)) { append("val b = 2") }
        }
        val tokens = morphTokens(listOf(placed(AnnotatedString(code))), placed(dimmed), measurer)
        tokens.filter { it.startLayout != null }.map { it.layout.layoutInput.text.text } shouldBe listOf("val", "b", "=", "2")
    }

    test("code a build changes shows in focus while it changes, and what leaves the focus dims after") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = Size)
        fun placed(code: String, focus: Int) = PlacedCode(focusedCode(code, focus, measurer, style), style, Offset.Zero, null)
        // Line 1 in focus, then line 3; line 2 changes, out of focus before and after.
        val tokens = morphTokens(listOf(placed("a()\nb()\nc()", 0)), placed("a()\nb(1)\nc()", 2), measurer, recolors = true)
        fun token(text: String) = tokens.single { it.layout.layoutInput.text.text == text }
        // b() shows in focus first, changes, and then dims again.
        token("b").startLayout shouldNotBe null
        token("b").midLayout shouldNotBe null
        token("1").midLayout shouldNotBe null
        // a() stays in focus while the code changes, and dims after; c() comes into focus first.
        token("a").startLayout shouldBe null
        token("a").midLayout shouldNotBe null
        token("c").startLayout shouldNotBe null
        token("c").midLayout shouldBe null
    }

    test("into code that shows parts of its lines in focus, only what changes shows in focus while it changes") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = Size)
        val code = "a()\nb(1)\nc()"
        val one = code.indexOf("1").let { it..it }
        // Line 1 in focus, then only the 1 that line 2 gets: b( and ) are out of focus before and after.
        val tokens = morphTokens(
            listOf(PlacedCode(focusedCode("a()\nb()\nc()", 0, measurer, style), style, Offset.Zero, null)),
            PlacedCode(partFocusedCode(code, one, measurer, style), style, Offset.Zero, null),
            measurer,
            recolors = true,
        )
        fun token(text: String) = tokens.single { it.layout.layoutInput.text.text == text }
        token("b").startLayout shouldBe null
        token("b").midLayout shouldBe null
        token("1").midLayout shouldBe null
    }

    test("into code that shows parts of its lines in focus, what goes fades out in its own colors") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = Size)
        val code = "a()\nb(1)\nc()"
        val one = code.indexOf("1").let { it..it }
        // x, out of focus, goes as 1 comes in its place.
        val tokens = morphTokens(
            listOf(PlacedCode(focusedCode("a()\nb(x)\nc()", 0, measurer, style), style, Offset.Zero, null)),
            PlacedCode(partFocusedCode(code, one, measurer, style), style, Offset.Zero, null),
            measurer,
            recolors = true,
        )
        val x = tokens.single { it.layout.layoutInput.text.text == "x" }
        x.end shouldBe null
        x.startLayout shouldBe null
    }

    test("a line that only folds changes no code: it keeps its colors while the code changes") {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = Size, lineHeight = Size * 1.45f, lineHeightStyle = SlideLineHeightStyle)
        val dimmed = SpanStyle(color = Color.Gray)
        fun placed(code: String, folds: List<IntRange>): PlacedCode {
            // Every line out of focus.
            val colored = buildAnnotatedString { withStyle(dimmed) { append(code) } }
            val folded = fold(colored, foldsIn(code, code.split('\n'), folds), AnnotatedString(" ⋯", dimmed))
            val inFocus = fold(AnnotatedString(code), folded.folds, AnnotatedString(" ⋯"))
            return PlacedCode(shownCode(code, folded, measurer.measure(folded.text, style, softWrap = false), inFocus.text), style, Offset.Zero, null)
        }
        val tokens = morphTokens(listOf(placed("class A {\n    a()\n}\nb()", emptyList())), placed("class A {\n    a()\n}\nb()", listOf(1..3)), measurer, recolors = true)
        tokens.filter { it.layout.layoutInput.text.text in setOf("class", "A", "{") }.forEach {
            it.startLayout shouldBe null
            it.midLayout shouldBe null
        }
    }

    test("code with the same id moves into the new block token by token, drawn once on the way") {
        val moving = twoSlides(
            { CodeBlock("val a = 1\nval b = 2", Modifier.align(Alignment.TopStart), fontSize = Size, id = "code") },
            { CodeBlock("val b = 2\n\n\nval a = 1", Modifier.align(Alignment.BottomEnd), fontSize = Size, id = "code") },
        )
        val (start, middle, end) = moving.transitionFrames()
        middle.ink() shouldBeLessThan 1.3 * maxOf(start.ink(), end.ink())
        // On its way: neither where it was nor where it goes.
        differingShare(middle, start) shouldBeGreaterThan 0.001
        differingShare(middle, end) shouldBeGreaterThan 0.001
        // Blocks that do not continue each other show both codes halfway, as the slides cross-fade.
        val fading = twoSlides(
            { CodeBlock("val a = 1\nval b = 2", Modifier.align(Alignment.TopStart), fontSize = Size, id = "one") },
            { CodeBlock("val b = 2\n\n\nval a = 1", Modifier.align(Alignment.BottomEnd), fontSize = Size, id = "other") },
        )
        // Without a match, the slides cross-fade for a shorter time: halfway, the first code still
        // shows where it was, at the top left, and the second already where it goes, at the bottom
        // right. (Counting dark pixels, as ink() does, depends on how thick the font is drawn.)
        val (fadingStart, fadingMiddle, fadingEnd) = fading.renderTransition(Frame(0, 0), Frame(1, 0), listOf(0, 200, 1500), scale = 0.5f)
        differingShareIn(fadingMiddle, fadingEnd, TopLeft) shouldBeGreaterThan 0.002
        differingShareIn(fadingMiddle, fadingStart, BottomRight) shouldBeGreaterThan 0.002
    }

    test("code moving into a block that moves keeps its place in the block all the way") {
        val deck = twoSlides(
            { CodeBlock("val a = 1", Modifier.align(Alignment.TopStart), fontSize = Size, id = "code") },
            { CodeBlock("val a = 1", Modifier.align(Alignment.BottomEnd), fontSize = Size, id = "code") },
        )
        val (start, middle, end) = deck.transitionFrames()
        val (startBox, startInk) = boxAndInkLefts(start)
        val (middleBox, middleInk) = boxAndInkLefts(middle)
        val (endBox, _) = boxAndInkLefts(end)
        // On its way, the block is neither where it was nor where it goes.
        (middleBox > startBox + 50 && middleBox < endBox - 50) shouldBe true
        // The code is as far into the block as at the start, rather than lagging behind it.
        kotlin.math.abs((middleInk - middleBox) - (startInk - startBox)) shouldBeLessThanOrEqual 3
    }

    test("the code of two blocks moves into one that continues both, and ends as it is") {
        val deck = twoSlides(
            {
                Row(Modifier.align(Alignment.TopStart)) {
                    CodeBlock("val a = 1", fontSize = Size, id = "a")
                    CodeBlock("val b = 2", Modifier.padding(start = 400.dp), fontSize = Size, id = "b")
                }
            },
            { CodeBlock("val a = 1\nval b = 2", Modifier.align(Alignment.BottomEnd), fontSize = Size, id = "a", from = listOf("b")) },
        )
        val (start, middle, end) = deck.transitionFrames()
        // The second block's code moves too: it does not stay behind, fading.
        middle.ink() shouldBeLessThan 1.3 * maxOf(start.ink(), end.ink())
        differingShare(end, still(deck, Frame(1, 0))) shouldBeLessThan 0.001
    }

    test("code moves into a block that continues it only through from, under an id of its own") {
        val deck = twoSlides(
            { CodeBlock("val a = 1", Modifier.align(Alignment.TopStart), fontSize = Size, id = "one") },
            { CodeBlock("val a = 1\nval b = 2", Modifier.align(Alignment.BottomEnd), fontSize = Size, id = "two", from = listOf("one")) },
        )
        val (start, middle, end) = deck.transitionFrames()
        // It starts where the block it continues is, rather than with that block's code gone.
        differingShare(start, still(deck, Frame(0, 0))) shouldBeLessThan 0.001
        // On its way, the block is neither where it was nor where it goes, and its code moves with it.
        val (startBox, _) = boxAndInkLefts(start)
        val (middleBox, _) = boxAndInkLefts(middle)
        val (endBox, _) = boxAndInkLefts(end)
        (middleBox > startBox + 50 && middleBox < endBox - 50) shouldBe true
        middle.ink() shouldBeLessThan 1.3 * maxOf(start.ink(), end.ink())
        differingShare(end, still(deck, Frame(1, 0))) shouldBeLessThan 0.001
    }

    test("code moves into an editable block's editor") {
        val deck = twoSlides(
            { CodeBlock("val a = 1\nval b = 2", Modifier.align(Alignment.TopStart), fontSize = Size, id = "code") },
            { CodeBlock("val b = 2\n\n\nval a = 1", Modifier.align(Alignment.BottomEnd), id = "code", fontSize = Size, editable = true) },
        )
        val (start, middle, end) = deck.transitionFrames()
        middle.ink() shouldBeLessThan 1.3 * maxOf(start.ink(), end.ink())
        differingShare(end, still(deck, Frame(1, 0))) shouldBeLessThan 0.001
    }
})
