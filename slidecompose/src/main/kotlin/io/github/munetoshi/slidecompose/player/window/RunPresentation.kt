package io.github.munetoshi.slidecompose.player.window

import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.components.AssetImage
import io.github.munetoshi.slidecompose.deck.components.assetPainter
import io.github.munetoshi.slidecompose.deck.prepare
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import java.io.File
import kotlin.concurrent.thread

/**
 * Opens [presentation] in a window. Call this from `main`.
 *
 * @param assetDirectory where [assetPainter] and [AssetImage] resolve relative paths.
 */
fun runPresentation(
    presentation: Presentation,
    assetDirectory: File = File(System.getProperty("user.dir")),
) {
    configureDesktopApp(presentation.title)
    // Count the build steps of every slide up front rather than while the window first composes,
    // and look at the frames in the background before the talk moves between them.
    presentation.slides.forEach { it.steps }
    thread(isDaemon = true, name = "prepare-presentation") { presentation.prepare() }
    application {
        val controller = remember { PresentationController(presentation) }
        PresentationWindows(controller, assetDirectory = assetDirectory)
    }
}

/** Sets macOS app properties. Must run before any AWT class is initialized. */
fun configureDesktopApp(name: String) {
    System.setProperty("apple.awt.application.name", name)
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
}
