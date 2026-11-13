import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.window.PresentationWindows
import io.github.munetoshi.slidecompose.player.window.PresentationWindowsState
import io.github.munetoshi.slidecompose.player.window.configureDesktopApp
import java.io.File
import kotlinx.coroutines.delay

/** Opens the real windows for a few seconds, exercises them and prints what AWT sees. */
fun main() {
    configureDesktopApp("SlideCompose Smoke Test")
    application {
        val controller = remember { PresentationController(deck) }
        val state = remember { PresentationWindowsState() }
        PresentationWindows(controller, state, assetDirectory = File("examples/demo"))
        LaunchedEffect(Unit) {
            exercise(controller, state)
            exitApplication()
        }
    }
}

/** Shows the presenter, moves on, goes full screen and swaps the windows, reporting after each. */
private suspend fun exercise(controller: PresentationController, state: PresentationWindowsState) {
    delay(1500)
    report(controller, state, "start")
    state.isPresenterVisible = true
    delay(1500)
    report(controller, state, "presenter")
    for (step in 1..4) {
        controller.next()
        delay(1000)
    }
    report(controller, state, "navigated")
    state.first.placement = WindowPlacement.Fullscreen
    delay(2500)
    report(controller, state, "fullscreen")
    // Swapping exchanges what the windows show; the windows stay put, full screen or not.
    for (swap in 1..4) {
        state.isSwapped = !state.isSwapped
        delay(800)
        report(controller, state, "swap $swap")
    }
    state.first.placement = WindowPlacement.Floating
    delay(2000)
    report(controller, state, "windowed")
}

/** Prints where the talk is and the windows AWT sees, after [label]. */
private fun report(controller: PresentationController, state: PresentationWindowsState, label: String) {
    println("[$label] frame=${controller.frame} first=${state.first.placement} second=${state.second.placement} swapped=${state.isSwapped}")
    java.awt.Frame.getFrames().asSequence().filter { it.isVisible }.forEach {
        println("    window '${it.title}' bounds=${it.bounds}")
    }
}
