package io.github.munetoshi.slidecompose.player.window

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import io.github.munetoshi.slidecompose.click
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.code.CodeBlock
import io.github.munetoshi.slidecompose.deck.code.CodeBlockStates
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.keyPress
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.TrackFocus
import io.github.munetoshi.slidecompose.player.stage.WindowFocus
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import io.github.munetoshi.slidecompose.player.stage.slidesClicked
import io.github.munetoshi.slidecompose.typing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope

/**
 * The slides of [deck] in a window's content, with [extra] over them, and keys and clicks handled
 * as a window handles them: its first keys, then what has the focus, then the keys it leaves.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class SlidesWindow(deck: Presentation, extra: @Composable () -> Unit = {}) {
    val controller = PresentationController(deck)
    val windows = PresentationWindowsState().apply { first.placement = WindowPlacement.Fullscreen }
    private val actions = WindowActions(controller, windows, CoroutineScope(EmptyCoroutineContext))
    private val focus = WindowFocus()
    private val scene = ImageComposeScene(1920, 1080, Density(1f)) {
        TrackFocus(focus) {
            // As the window shows the slides: a click that nothing on them takes goes on, or takes the focus back.
            SlideRenderer.InProcess.Stage(controller, onClick = { controller.slidesClicked(focus) }, Modifier.fillMaxSize())
            extra()
        }
    }

    init {
        scene.render()
    }

    /** Gives the window [events], each in a frame of its own. */
    fun press(events: List<KeyEvent>) {
        for (event in events) {
            actions.handleKeyFirst(event, windows.first, focus) || scene.sendKeyEvent(event) || actions.handleKey(event, windows.first)
            scene.render()
        }
    }

    fun click(at: Offset) {
        scene.click(at)
        scene.render()
    }

    fun close() = scene.close()
}

/** A slide with an [CodeBlock] whose editor is about at (300, 160), then two more. */
private fun deckWithCode(id: String) = presentation {
    slide { Box(Modifier.fillMaxSize().padding(100.dp)) { CodeBlock("val window = 1", id = id, editable = true) } }
    slide {}
    slide {}
}

private val Editor = Offset(300f, 160f)

/** Where nothing is on the slides. */
private val Away = Offset(1500f, 900f)

class WindowKeysTest : FunSpec({
    test("keys a text field on a slide types stay with it, and Esc takes the focus back before it leaves full screen") {
        val window = SlidesWindow(deckWithCode("window keys"))
        window.click(Editor)
        window.press(typing("f 1") + keyPress(AwtKeyEvent.VK_RIGHT) + keyPress(AwtKeyEvent.VK_ENTER, '\n'))
        window.controller.frame shouldBe Frame(0, 0)
        window.windows.first.placement shouldBe WindowPlacement.Fullscreen
        CodeBlockStates.of("window keys", "val window = 1").code.length shouldBe "val window = 1".length + "f 1\n".length

        window.press(keyPress(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.VK_ESCAPE.toChar()))
        window.windows.first.placement shouldBe WindowPlacement.Fullscreen
        // The text field has let go: keys move the slides again.
        window.press(typing(" "))
        window.controller.frame shouldBe Frame(1, 0)
        window.press(keyPress(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.VK_ESCAPE.toChar()))
        window.windows.first.placement shouldBe WindowPlacement.Floating
        window.close()
    }

    test("the keys of presenter remotes move the slides while a text field has the focus") {
        val window = SlidesWindow(deckWithCode("remote keys"))
        window.click(Editor)
        window.press(keyPress(AwtKeyEvent.VK_PAGE_DOWN))
        window.controller.frame shouldBe Frame(1, 0)
        window.close()
    }

    test("a click away from a text field on a slide takes the focus from it, without moving the slides") {
        val window = SlidesWindow(deckWithCode("click away"))
        window.click(Editor)
        window.click(Away)
        window.controller.frame shouldBe Frame(0, 0)
        window.press(typing(" "))
        window.controller.frame shouldBe Frame(1, 0)
        window.click(Away)
        window.controller.frame shouldBe Frame(2, 0)
        window.close()
    }

    test("a control clicked with the mouse leaves Space to the windows") {
        var clicks = 0
        val window = SlidesWindow(presentation { slide {}; slide {} }) {
            Text("control", Modifier.keepsNoFocus().clickable { clicks++ })
        }
        window.click(Offset(20f, 10f))
        window.press(typing(" "))
        clicks shouldBe 1
        window.controller.frame shouldBe Frame(1, 0)
        window.close()
    }
})
