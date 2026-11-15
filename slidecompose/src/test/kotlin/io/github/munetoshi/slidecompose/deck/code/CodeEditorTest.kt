package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.click
import io.github.munetoshi.slidecompose.keyPress
import io.github.munetoshi.slidecompose.pixelsIn
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import io.github.munetoshi.slidecompose.press
import io.github.munetoshi.slidecompose.typing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.awt.event.InputEvent
import kotlin.math.abs
import java.awt.event.KeyEvent as AwtKeyEvent

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CodeEditorTest : FunSpec({
    test("every window showing the block shows the same code") {
        runComposeUiTest {
            // Two windows showing the slide compose it each on their own.
            setContent {
                Column {
                    CodeBlock("val shared = 1", id = "shared", editable = true)
                    CodeBlock("val shared = 1", id = "shared", editable = true)
                }
            }
            onAllNodesWithTag(CodeEditorTag)[0].performTextReplacement("val shared = 2")
            onAllNodesWithTag(CodeEditorTag)[1].assertTextEquals("val shared = 2")
            CodeBlockStates.of("shared", "val shared = 1").code shouldBe "val shared = 2"
        }
    }

    test("the editor keeps the keys it types, and leaves the others to the window") {
        val windowKeys = mutableListOf<Key>()
        val scene = ImageComposeScene(800, 400, Density(1f)) {
            Box(Modifier.fillMaxSize().onKeyEvent { keptBy(windowKeys, it) }) { CodeBlock("val keys = 1", id = "keys", editable = true) }
        }
        scene.render()
        scene.click(Offset(100f, 50f))
        scene.render()
        // Letters, Space and digits come as a pressed key the text field leaves, then a character it types.
        scene.press(typing("f 1") + keyPress(AwtKeyEvent.VK_RIGHT) + keyPress(AwtKeyEvent.VK_E, 'e', InputEvent.META_DOWN_MASK))
        CodeBlockStates.of("keys", "val keys = 1").text.text.length shouldBe "val keys = 1".length + 3
        windowKeys shouldBe listOf(Key.E)
        scene.close()
    }

    test("⌘/ (Ctrl+/ off macOS) comments the caret's line out, and stays in the editor") {
        val windowKeys = mutableListOf<Key>()
        val scene = ImageComposeScene(800, 400, Density(1f)) {
            Box(Modifier.fillMaxSize().onKeyEvent { keptBy(windowKeys, it) }) { CodeBlock("val comment = 1", id = "comment", editable = true) }
        }
        scene.render()
        scene.click(Offset(100f, 50f))
        scene.render()
        val command = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        scene.press(keyPress(AwtKeyEvent.VK_SLASH, '/', command))
        CodeBlockStates.of("comment", "val comment = 1").text.text shouldBe "// val comment = 1"
        windowKeys shouldBe emptyList()
        scene.close()
    }

    test("a character that comes with Ctrl held types nothing, as an input method switching modes sends one") {
        val scene = ImageComposeScene(800, 400, Density(1f)) { CodeBlock("val x = 1", id = "ctrl typed", editable = true) }
        scene.render()
        scene.click(Offset(100f, 50f))
        scene.render()
        // Google 日本語入力 switches modes with Ctrl+Shift+:, and the JVM still gets ";" typed.
        scene.press(keyPress(AwtKeyEvent.VK_SEMICOLON, ';', InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))
        scene.press(keyPress(AwtKeyEvent.VK_SPACE, ' ', InputEvent.CTRL_DOWN_MASK))
        CodeBlockStates.of("ctrl typed", "val x = 1").code shouldBe "val x = 1"
        // Without Ctrl, it types; with Ctrl and Option, as AltGr comes on Windows, too.
        scene.press(keyPress(AwtKeyEvent.VK_SEMICOLON, ';'))
        scene.press(keyPress(AwtKeyEvent.VK_Q, '@', InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK))
        CodeBlockStates.of("ctrl typed", "val x = 1").code.count { it == ';' || it == '@' } shouldBe 2
        scene.close()
    }

    test("until it is editable, it takes no keys") {
        val scene = ImageComposeScene(800, 400, Density(1f)) { CodeBlock("val locked = 1", id = "locked", editable = false) }
        scene.render()
        scene.click(Offset(100f, 50f))
        scene.render()
        // Clicked, the editor takes no focus: the scene leaves the keys, and the window gets them.
        typing("f").map(scene::sendKeyEvent) shouldBe listOf(false, false, false)
        scene.render()
        CodeBlockStates.of("locked", "val locked = 1").code shouldBe "val locked = 1"
        scene.close()
    }

    test("what was typed starts over once no window shows the block, unless it is to stay") {
        val state = CodeBlockState("restart", "val x = 1")
        state.hold()
        state.text = TextFieldValue("val x = 2")
        // A second window's copy comes while the first shows: nothing went.
        state.comeBack(resets = true, deck = null)
        state.hold()
        state.release()
        (state.code to state.restarts) shouldBe ("val x = 2" to 0)
        // The last copy goes, and the block is to stay.
        state.release()
        state.comeBack(resets = false, deck = null)
        state.hold()
        (state.code to state.restarts) shouldBe ("val x = 2" to 0)
        // The last copy goes again: the next copy starts over, and only once for two windows.
        state.release()
        state.comeBack(resets = true, deck = null)
        state.comeBack(resets = true, deck = null)
        (state.code to state.restarts) shouldBe ("val x = 1" to 1)
    }

    test("a block shown again after it went starts over, however soon") {
        runComposeUiTest {
            var shown by mutableStateOf(true)
            setContent { if (shown) CodeBlock("val y = 1", id = "again", editable = true) }
            val state = CodeBlockStates.of("again", "val y = 1")
            state.text = TextFieldValue("val y = 2")
            shown = false
            waitForIdle()
            shown = true
            waitForIdle()
            state.text.text shouldBe "val y = 1"
        }
    }

    test("with visibleLines it shows that many lines, and scrolls to a build's line or to bring its focus into view") {
        runComposeUiTest {
            val code = (1..20).joinToString("\n") { "val v$it = $it" }
            var build by mutableStateOf(0)
            setContent {
                CodeBlock(code, id = "scroll", visibleLines = 5, scrollTo = 11.takeIf { build == 0 }, focusLines = (18..19).takeIf { build == 1 }, editable = false)
            }
            waitForIdle()
            val state = CodeBlockStates.of("scroll", code)
            state.scrollLines shouldBe 10f
            val lineHeight = onNodeWithTag(CodeEditorTag).fetchSemanticsNode().size.height / 20f
            onNodeWithTag(CodeViewportTag).fetchSemanticsNode().size.height.toFloat() shouldBe (5 * lineHeight plusOrMinus 2.0f)
            // Lines 11 to 15 show: 18 and 19 come into view at the top.
            build = 1
            waitForIdle()
            state.scrollLines shouldBe 17f
        }
    }

    test("a click on a folded region's mark unfolds it, until a build folds others") {
        val code = "fun a() {\n    one()\n    two()\n}\na()"
        var build by mutableStateOf(listOf(1..4))
        val scene = ImageComposeScene(900, 400, Density(1f)) {
            Box(Modifier.background(Color.White).fillMaxSize()) { CodeBlock(code, id = "fold click", folded = build, editable = false) }
        }
        val state = CodeBlockStates.of("fold click", code)
        // The mark is the last thing on the first line.
        val mark = scene.render().use { it.edgeOfFirstLine(start = false) }
        state.foldedAt(build) shouldBe setOf(1..4)
        scene.click(mark)
        scene.render().close()
        state.foldedAt(build) shouldBe emptySet()
        build = listOf(4..5)
        scene.render().close()
        state.foldedAt(build) shouldBe setOf(4..5)
        scene.close()
    }

    test("a click on a region's mark in the gutter folds it, and another unfolds it") {
        val code = "fun a() {\n    one()\n    two()\n}\na()"
        val scene = ImageComposeScene(900, 400, Density(1f)) {
            Box(Modifier.background(Color.White).fillMaxSize()) { CodeBlock(code, id = "gutter", folds = listOf(1..4), editable = false) }
        }
        val state = CodeBlockStates.of("gutter", code)
        // The mark is the first thing on the first line, left of the code.
        val mark = scene.render().use { it.edgeOfFirstLine(start = true) }
        scene.click(mark)
        scene.render().close()
        state.foldedAt(emptyList()) shouldBe setOf(1..4)
        scene.click(mark)
        scene.render().close()
        state.foldedAt(emptyList()) shouldBe emptySet()
        scene.close()
    }

    test("the gutter takes no room from the code: code with regions to fold is where code without is") {
        val code = "fun a() {\n    one()\n}"
        fun firstLineEnd(folds: List<IntRange>): Offset {
            val scene = ImageComposeScene(900, 400, Density(1f)) {
                Box(Modifier.background(Color.White).fillMaxSize()) { CodeBlock(code, id = "gutter room $folds", folds = folds, editable = false) }
            }
            return scene.render().use { it.edgeOfFirstLine(start = false) }.also { scene.close() }
        }
        firstLineEnd(listOf(1..3)) shouldBe firstLineEnd(emptyList())
    }

    test("a build that folds and adds code folds first, closing up, then adds it, and the code stays where it ends up") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var build by mutableStateOf(0)
            var height = 0
            setContent {
                Box(Modifier.background(Color.White).onSizeChanged { height = it.height }) {
                    CodeBlock(
                        if (build == 0) "fun a() {\n    one()\n}" else "fun a() {\n    one()\n}\nb()\nc()",
                        id = "fold then add",
                        folded = if (build == 0) emptyList() else listOf(1..3),
                        editable = false,
                    )
                }
            }
            mainClock.advanceTimeBy(100)
            val start = height
            val lines = onRoot().captureToImage().textLines()
            build = 1
            mainClock.advanceTimeByFrame()
            // Folded, and nothing added yet: only the first line shows, and the code has closed up under it.
            mainClock.advanceTimeBy(440)
            onRoot().captureToImage().textLines() shouldBe 1
            height shouldBeLessThan start
            mainClock.advanceTimeBy(2_000)
            onRoot().captureToImage().textLines() shouldBe lines
            abs(height - start) shouldBeLessThanOrEqual 2
        }
    }

    test("a build that folds and adds code first closes up under the fold, then makes room for what comes, in time of its own") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var build by mutableStateOf(0)
            val added = (1..7).joinToString("") { "b$it()\n" }
            setContent {
                Box(Modifier.background(Color.White)) {
                    CodeBlock(
                        if (build == 0) "fun a() {\n    one()\n    two()\n}\nz()" else "fun a() {\n    one()\n    two()\n}\n${added}z()",
                        id = "room after the fold",
                        folded = if (build == 0) emptyList() else listOf(1..4),
                        editable = false,
                    )
                }
            }
            mainClock.advanceTimeBy(100)
            val start = onRoot().captureToImage().lowestInk()
            build = 1
            mainClock.advanceTimeByFrame()
            // The fold is done, and the last line, after the code that comes, has gone up with it.
            mainClock.advanceTimeBy(440)
            val closed = onRoot().captureToImage().lowestInk()
            closed shouldBeLessThan start
            // Then it goes down to make room, which takes a while.
            mainClock.advanceTimeBy(360)
            val making = onRoot().captureToImage().lowestInk()
            mainClock.advanceTimeBy(2_000)
            val end = onRoot().captureToImage().lowestInk()
            end shouldBeGreaterThan start
            (making in (closed + 1)..<end) shouldBe true
        }
    }

    test("a region folded into a blank line closes up too, as one folded into a line of code does") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var build by mutableStateOf(0)
            setContent {
                Box(Modifier.background(Color.White)) {
                    CodeBlock(
                        // As the first DEMO of the Kotlin Fest talk does: the extension functions fold into the blank line above them.
                        if (build == 0) "a()\n\nfun x()\nfun y()\n\nz()" else "a()\n\nfun x()\nfun y()\n\nb()\nc()\nd()\ne()\nz()",
                        id = "fold into a blank line",
                        folded = if (build == 0) emptyList() else listOf(2..5),
                        editable = false,
                    )
                }
            }
            mainClock.advanceTimeBy(100)
            val start = onRoot().captureToImage().lowestInk()
            build = 1
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(440)
            onRoot().captureToImage().lowestInk() shouldBeLessThan start
            mainClock.advanceTimeBy(2_000)
            onRoot().captureToImage().lowestInk() shouldBeGreaterThan start
        }
    }

    test("the frame a build folds in still shows the code where it was, as a window draws it") {
        val code = "fun a() {\n    one()\n    two()\n}\na()"
        var folded by mutableStateOf(emptyList<IntRange>())
        var height = 0
        val scene = ImageComposeScene(900, 600, Density(1f)) {
            Box(Modifier.onSizeChanged { height = it.height }) { CodeBlock(code, id = "first frame", folds = listOf(1..4), folded = folded, editable = false) }
        }
        scene.render(0).close()
        scene.render(16_000_000).close()
        val open = height
        folded = listOf(1..4)
        scene.render(32_000_000).close()
        height shouldBe open
        scene.close()
    }

    test("folded by a build, the code moves into place, and the editor changes height with it") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            val code = "fun a() {\n    one()\n    two()\n}\na()"
            var folded by mutableStateOf(emptyList<IntRange>())
            var height = 0
            setContent {
                Box(Modifier.onSizeChanged { height = it.height }) { CodeBlock(code, id = "refold", folds = listOf(1..4), folded = folded, editable = false) }
            }
            mainClock.advanceTimeBy(100)
            val open = height
            folded = listOf(1..4)
            // The frame that folds still shows the code where it was: it does not flash where it goes.
            mainClock.advanceTimeByFrame()
            val first = height
            mainClock.advanceTimeBy(400)
            val halfway = height
            mainClock.advanceTimeBy(2_000)
            val shut = height
            first shouldBe open
            (shut < halfway && halfway < open) shouldBe true
        }
    }

    test("a build that only changes the focus fades it: what comes into focus lights up, then what leaves it dims") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            val code = "context(a) {\n    one()\n}\nfun one() {\n    two()\n}"
            var focus by mutableStateOf<List<IntRange>?>(null)
            setContent { CodeBlock(code, id = "focus fade", editable = false, focusRanges = focus) }
            mainClock.advanceTimeBy(100)
            val lit = onRoot().captureToImage().toPixelMap().let { map -> (0 until map.width * map.height).count { map[it % map.width, it / map.width].luminance() < 0.45f } }
            focus = listOf(0 until 10)
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(500)
            val fading = onRoot().captureToImage().toPixelMap().let { map -> (0 until map.width * map.height).count { map[it % map.width, it / map.width].luminance() < 0.45f } }
            mainClock.advanceTimeBy(2_000)
            val dimmed = onRoot().captureToImage().toPixelMap().let { map -> (0 until map.width * map.height).count { map[it % map.width, it / map.width].luminance() < 0.45f } }
            // Dark ink: all the code at first, only the part in focus at the end, and in between on its way.
            dimmed shouldBeLessThan lit
            (fading < lit && fading > dimmed) shouldBe true
        }
    }

    test("code taller than its room stays where it is while a build changes its focus") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            val code = (1..12).joinToString("\n") { "val line$it = $it" }
            var focus by mutableStateOf<List<IntRange>?>(null)
            // Less room than the code takes, as code at the foot of a slide can have.
            setContent { Box(Modifier.height(200.dp)) { CodeBlock(code, id = "focus in little room", editable = false, focusRanges = focus) } }
            mainClock.advanceTimeBy(100)
            val still = onRoot().captureToImage().highestInk()
            // The first line stays in focus, dark all along.
            focus = listOf(0 until 10)
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(500)
            onRoot().captureToImage().highestInk() shouldBe still
        }
    }

    test("other code from a build moves into place, while what is typed shows at once") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var code by mutableStateOf("val a = 1")
            var height = 0
            setContent { Box(Modifier.onSizeChanged { height = it.height }) { CodeBlock(code, id = "versions", editable = true) } }
            mainClock.advanceTimeBy(100)
            val short = height
            code = "val a = 1\nval b = 2\nval c = 3"
            mainClock.advanceTimeByFrame()
            height shouldBe short
            mainClock.advanceTimeBy(400)
            val halfway = height
            mainClock.advanceTimeBy(2_000)
            val tall = height
            (short < halfway && halfway < tall) shouldBe true
            // Typed, the same lines show at once.
            CodeBlockStates.of("versions", "val a = 1\nval b = 2\nval c = 3").text = TextFieldValue("val a = 1")
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            height shouldBe short
        }
    }

    test("as wide as its code, a block widens as other code from a build moves in, rather than at once") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var code by mutableStateOf("val a = 1")
            var width = 0
            setContent {
                Box(Modifier.onSizeChanged { width = it.width }) {
                    CodeBlock(code, Modifier.width(IntrinsicSize.Max), id = "widens", editable = false)
                }
            }
            mainClock.advanceTimeBy(100)
            val narrow = width
            code = "val a = 1\nval somethingLonger = 2"
            mainClock.advanceTimeByFrame()
            width shouldBe narrow
            mainClock.advanceTimeBy(400)
            val halfway = width
            mainClock.advanceTimeBy(2_000)
            val wide = width
            (narrow < halfway && halfway < wide) shouldBe true
        }
    }

    test("the parts a version adds are in focus, but in the first version and in one that adds nothing") {
        val versions = listOf("val a = 1", "val a = f(1)", "val a = f(1)\nval b = 2", "val a = f(1)")
        addedParts(versions, 0) shouldBe null
        addedParts(versions, 1)!!.map { versions[1].substring(it) } shouldBe listOf("f", "(", ")")
        addedParts(versions, 2)!!.map { versions[2].substring(it) } shouldBe listOf("val", "b", "=", "2")
        addedParts(versions, 3) shouldBe null
    }

    test("the parts added are found in the code as a block trims it") {
        val after = """
            val a = 1
            val b = 2
        """
        addedParts("val a = 1", after)!!.map { after.trimIndent().trimEnd().substring(it) } shouldBe listOf("val", "b", "=", "2")
    }

    test("a block of versions is as wide as its widest version from the first version on") {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var version by mutableStateOf(0)
            var width = 0
            setContent {
                Box(Modifier.onSizeChanged { width = it.width }) {
                    CodeBlock(listOf("val a = 1", "val a = 1\nval somethingLonger = 2"), version)
                }
            }
            mainClock.advanceTimeBy(100)
            val first = width
            version = 1
            mainClock.advanceTimeBy(400)
            width shouldBe first
            mainClock.advanceTimeBy(3_000)
            width shouldBe first
        }
    }

    test("with widthFor, a block as wide as its code is as wide as the widest of that code from the start") {
        runComposeUiTest {
            val long = "val a = 1\nval somethingLonger = 2"
            var reserved = 0
            var own = 0
            setContent {
                Column {
                    Box(Modifier.onSizeChanged { reserved = it.width }) {
                        CodeBlock("val a = 1", Modifier.width(IntrinsicSize.Max), id = "reserved", editable = false, widthFor = listOf("val a = 1", long))
                    }
                    Box(Modifier.onSizeChanged { own = it.width }) {
                        CodeBlock(long, Modifier.width(IntrinsicSize.Max), id = "own", editable = false)
                    }
                }
            }
            waitForIdle()
            reserved shouldBe own
        }
    }

    test("the code without the lines hidden at a build is what shows, with what was typed") {
        val state = CodeBlockState("hidden", "a()\nb()\nc()")
        state.text = TextFieldValue("z()\na()\nb()\nc()")
        state.codeWithout(listOf(2..2)) shouldBe "z()\na()\nc()"
        state.codeWithout(listOf(1..1)) shouldBe "z()\nb()\nc()"
        state.codeWithout(emptyList()) shouldBe "z()\na()\nb()\nc()"
    }

    test("it colors the code as the language it is given") {
        val kotlin = renderedCode("val first = 1", focusLines = null, language = "kotlin")
        val plain = renderedCode("val first = 1", focusLines = null, language = "text")
        (kotlin.first contentEquals plain.first) shouldBe false
    }

    test("it dims the lines out of focus, and only them") {
        val code = "val first = 1\nval second = 2"
        val focused = renderedCode(code, focusLines = 2..2)
        val unfocused = renderedCode(code, focusLines = null)
        (focused.first contentEquals unfocused.first) shouldBe false
        (focused.second contentEquals unfocused.second) shouldBe true
    }

    test("with focus ranges, it dims all but those parts, even within a line") {
        val code = "val first = 1\nval second = 2"
        val second = code.indexOf("second").let { it until it + "second".length }
        val ranged = renderedCode(code, focusLines = null, focusRanges = listOf(second))
        val unfocused = renderedCode(code, focusLines = null)
        val secondDimmed = renderedCode(code, focusLines = 1..1)
        (ranged.first contentEquals unfocused.first) shouldBe false
        (ranged.second contentEquals unfocused.second) shouldBe false
        (ranged.second contentEquals secondDimmed.second) shouldBe false
    }

    test("↓ and ↑ move the caret line by line through folded code, blank lines too") {
        // As the first DEMO of the Kotlin Fest talk folds it: a region from a blank line, and one right after it.
        val code = listOf("class A", "class B", "", "fun x()", "fun y()", "", "interface S", "object P", "object Q", "fun r()", "", "fun t() {", "    u()", "}", "a()", "b()").joinToString("\n")
        val scene = ImageComposeScene(800, 900, Density(1f)) {
            Box(Modifier.background(Color.White).fillMaxSize()) { CodeBlock(code, id = "lines through folds", folded = listOf(3..6, 7..10), editable = true) }
        }
        scene.render()
        scene.click(Offset(100f, 60f))
        scene.render()
        val state = CodeBlockStates.of("lines through folds", code)
        state.text = TextFieldValue(code, TextRange(code.indexOf("\n\n") + 1))
        scene.render()
        fun lines(key: Int) = (1..8).map {
            scene.press(keyPress(key))
            code.substring(0, state.text.selection.start).count { it == '\n' } + 1
        }
        // From the blank line that holds x and y: past the other fold, and on to the end.
        lines(AwtKeyEvent.VK_DOWN) shouldBe listOf(7, 11, 12, 13, 14, 15, 16, 16)
        lines(AwtKeyEvent.VK_UP) shouldBe listOf(15, 14, 13, 12, 11, 7, 3, 2)
        scene.close()
    }

    test("the caret shows where it is in the code, after the regions folded before it") {
        val code = "fun a() {\n    one()\n}\nxyz"
        val state = CodeBlockStates.of("caret after folds", code)
        val scene = ImageComposeScene(800, 400, Density(1f)) {
            CompositionLocalProvider(LocalSharesCaret provides true) {
                Box(Modifier.background(Color.White).fillMaxSize()) { CodeBlock(code, id = "caret after folds", folded = listOf(1..3), editable = true) }
            }
        }
        scene.render().close()
        // At the start of xyz, typed in another window's copy.
        state.text = TextFieldValue(code, TextRange(code.indexOf("xyz")))
        val elsewhere = Any()
        state.focusChanged(elsewhere, focused = true)
        val withCaret = scene.render().use { it.toComposeImageBitmap().toPixelMap() }
        state.focusChanged(elsewhere, focused = false)
        val without = scene.render().use { it.toComposeImageBitmap().toPixelMap() }
        scene.close()
        val caret = (0 until withCaret.height).flatMap { y -> (0 until withCaret.width).filter { x -> withCaret[x, y] != without[x, y] }.map { x -> x to y } }
        val caretRows = caret.minOf { it.second }..caret.maxOf { it.second }
        // The second line shown, xyz's: the caret is just left of its first letter.
        val letters = caretRows.flatMap { y -> (60 until without.width).filter { x -> without[x, y].red < 0.5f } }
        caret.maxOf { it.first } shouldBeLessThanOrEqual letters.min() + 2
        caretRows.first shouldBeGreaterThan 60
    }

    test("a copy shows where another is typed in while the windows share the pointer, and only then") {
        val shared = editedCopies("shared caret", sharesCaret = true, typed = true)
        val unshared = editedCopies("unshared caret", sharesCaret = false, typed = true)
        val untouched = editedCopies("untouched caret", sharesCaret = true, typed = false)
        (shared contentEquals unshared) shouldBe false
        (unshared contentEquals untouched) shouldBe true
    }
})

/**
 * The pixels of the second of two copies of a block, after typing into the first one if [typed],
 * while the windows share the caret if [sharesCaret].
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun editedCopies(id: String, sharesCaret: Boolean, typed: Boolean): ByteArray {
    val scene = ImageComposeScene(800, 500, Density(1f)) {
        CompositionLocalProvider(LocalSharesCaret provides sharesCaret) {
            Column {
                CodeBlock("val caret = 1", Modifier.height(240.dp), id = id, editable = true)
                CodeBlock("val caret = 1", id = id, editable = true)
            }
        }
    }
    scene.render()
    if (typed) {
        scene.click(Offset(100f, 50f))
        scene.press(typing("ab") + keyPress(AwtKeyEvent.VK_LEFT, modifiers = InputEvent.SHIFT_DOWN_MASK))
    } else {
        // The same text, typed nowhere.
        CodeBlockStates.of(id, "val caret = 1").text = CodeBlockStates.of("shared caret", "val caret = 1").text.copy(selection = TextRange(0))
    }
    val pixels = scene.render().use { it.pixelsIn(top = 240, bottom = 500) }
    scene.close()
    return pixels
}

/** The pixels of the first and the second line of [code] in [language], shown with [focusLines]. */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderedCode(code: String, focusLines: IntRange?, language: String = "kotlin", focusRanges: List<IntRange>? = null): Pair<ByteArray, ByteArray> {
    val scene = ImageComposeScene(800, 300, Density(1f)) {
        CodeBlock(code, id = "focus $focusLines $focusRanges $language", language = language, focusLines = focusLines, editable = false, focusRanges = focusRanges)
    }
    // At 30 px with 36 px above them, the lines are 43.5 px apart.
    val lines = scene.render().use { it.pixelsIn(top = 38, bottom = 78) to it.pixelsIn(top = 82, bottom = 121) }
    scene.close()
    return lines
}

/** Notes the key [event] pressed, as a window's own handler would see it, and leaves it. */
private fun keptBy(keys: MutableList<Key>, event: KeyEvent): Boolean {
    if (event.type == KeyEventType.KeyDown) keys += event.key
    return false
}

/** Where the first line of text in the image starts, or ends: its leftmost or rightmost dark pixel, a little inside, at its middle. */
/** How many lines of dark text [this] shows: runs of rows with dark pixels. */
private fun androidx.compose.ui.graphics.ImageBitmap.textLines(): Int {
    val pixels = toPixelMap()
    val inked = (0 until height).map { y -> (0 until width).any { x -> pixels[x, y].let { it.red + it.green + it.blue < 1.5f } } }
    return inked.indices.count { inked[it] && (it == 0 || !inked[it - 1]) }
}

/** The highest row of [this] with dark pixels, not counting those it leaves clear: the top of its first line of text. */
private fun androidx.compose.ui.graphics.ImageBitmap.highestInk(): Int {
    val pixels = toPixelMap()
    return (0 until height).first { y -> (0 until width).any { x -> pixels[x, y].let { it.alpha > 0.5f && it.red + it.green + it.blue < 1.5f } } }
}

/** The lowest row of [this] with dark pixels: the bottom of its last line of text. */
private fun androidx.compose.ui.graphics.ImageBitmap.lowestInk(): Int {
    val pixels = toPixelMap()
    return (height - 1 downTo 0).first { y -> (0 until width).any { x -> pixels[x, y].let { it.red + it.green + it.blue < 1.5f } } }
}

private fun org.jetbrains.skia.Image.edgeOfFirstLine(start: Boolean): Offset {
    val pixels = Bitmap().use { bitmap ->
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(width, height)))
        check(readPixels(bitmap))
        checkNotNull(bitmap.readPixels())
    }
    fun ink(x: Int, y: Int) = (0..2).all { (pixels[(y * width + x) * 4 + it].toInt() and 0xFF) < 200 }
    val rows = (0 until height).filter { y -> (0 until width).any { x -> ink(x, y) } }
    val line = rows.takeWhile { it - rows.first() == rows.indexOf(it) }
    if (start) return Offset(line.minOf { y -> (0 until width).first { x -> ink(x, y) } } + 3f, line.average().toFloat())
    return Offset(line.maxOf { y -> (0 until width).last { x -> ink(x, y) } } - 3f, line.average().toFloat())
}
