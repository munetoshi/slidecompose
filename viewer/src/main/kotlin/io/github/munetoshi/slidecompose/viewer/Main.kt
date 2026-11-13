package io.github.munetoshi.slidecompose.viewer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.application
import io.github.munetoshi.slidecompose.player.window.configureDesktopApp
import io.github.munetoshi.slidecompose.viewer.about.AboutWindow
import io.github.munetoshi.slidecompose.viewer.app.Viewer
import io.github.munetoshi.slidecompose.viewer.app.ViewerWindows
import io.github.munetoshi.slidecompose.viewer.cli.exportHeadless
import io.github.munetoshi.slidecompose.viewer.cli.fail
import io.github.munetoshi.slidecompose.viewer.cli.parseOptions
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeLauncher
import io.github.munetoshi.slidecompose.viewer.safemode.runSandboxedChild
import java.awt.Desktop
import java.awt.desktop.AboutEvent
import java.awt.desktop.AboutHandler
import java.awt.desktop.OpenFilesEvent
import java.awt.desktop.OpenFilesHandler
import java.io.File

fun main(args: Array<String>) {
    // Launched by SafeModeLauncher to render an untrusted deck inside the sandbox: this process has
    // no window and only answers the parent over stdio.
    if (args.firstOrNull() == SafeModeLauncher.CHILD_FLAG) {
        runSandboxedChild(args.getOrNull(1) ?: fail("${SafeModeLauncher.CHILD_FLAG} needs a deck path"))
    }
    val options = parseOptions(args)
    if (options.pdf != null || options.png != null) {
        exportHeadless(options)
        return
    }
    if (options.theme != null) fail("--theme needs --pdf or --png; in the window, pick a theme in View > Theme")
    configureDesktopApp("SlideCompose")
    val requests = AppRequests(options.deck)
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
        Desktop.getDesktop().setOpenFileHandler(requests)
    }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
        Desktop.getDesktop().setAboutHandler(requests)
    }
    application {
        val viewer = remember { Viewer() }
        LaunchedEffect(requests.deck) {
            requests.deck?.let { viewer.open(it) }
        }
        ViewerWindows(viewer)
        if (requests.isAboutOpen) AboutWindow(viewer.appearance.isDark(), requests.aboutRequests, onClose = { requests.isAboutOpen = false })
    }
}

/**
 * What macOS asks of the app: a deck opened from Finder ("Open With", or dropped on the Dock icon),
 * and the About window from the app menu's item, which macOS adds.
 */
private class AppRequests(deck: File?) : OpenFilesHandler, AboutHandler {
    var deck: File? by mutableStateOf(deck)
        private set
    var isAboutOpen: Boolean by mutableStateOf(false)

    /** How many times About was chosen, so that choosing it again brings the window to the front. */
    var aboutRequests: Int by mutableIntStateOf(0)
        private set

    override fun openFiles(event: OpenFilesEvent) {
        deck = event.files.firstOrNull() ?: return
    }

    override fun handleAbout(event: AboutEvent) {
        isAboutOpen = true
        aboutRequests++
    }
}
