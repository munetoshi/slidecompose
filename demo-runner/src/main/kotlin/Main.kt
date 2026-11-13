import io.github.munetoshi.slidecompose.player.window.runPresentation
import java.io.File

/** Using SlideCompose as a library: compile the deck into your app and call [runPresentation]. */
fun main() = runPresentation(deck, assetDirectory = File("examples/demo"))
