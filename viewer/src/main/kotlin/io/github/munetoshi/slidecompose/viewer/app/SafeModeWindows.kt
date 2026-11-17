package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.ApplicationScope
import io.github.munetoshi.slidecompose.player.window.PresentationWindows
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.WelcomeWindow
import io.github.munetoshi.slidecompose.viewer.home.rememberOpensInEditor
import io.github.munetoshi.slidecompose.viewer.safemode.DeckCopy
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeRenderer
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeSession
import io.github.munetoshi.slidecompose.viewer.safemode.emptyDeckInfo
import io.github.munetoshi.slidecompose.viewer.safemode.rememberSafeModeStatus
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * The windows of a deck open in safe mode: the usual audience and presenter windows, showing what a
 * sandboxed child renders. The deck's code runs in that child, never here. While the child loads
 * the deck, the home window says so.
 */
@Composable
internal fun ApplicationScope.SafeModeWindows(viewer: Viewer, deck: File, session: SafeModeSession) {
    val status = rememberSafeModeStatus(session)
    if (status == SafeModeSession.Status.Loading) {
        WelcomeWindow(viewer)
        return
    }
    // A deck that did not load gets a stand-in without slides, to show its errors over.
    val loaded = session.deck
    val renderer = remember(session, loaded) { SafeModeRenderer(session, loaded ?: emptyDeckInfo(deck.name)) }
    PresentationWindows(
        controller = renderer.controller,
        state = viewer.windows,
        title = stringResource(Res.string.safe_mode_window_title, if (loaded != null) "${loaded.title} — ${deck.name}" else deck.name),
        onCloseRequest = viewer::close,
        fileMenu = {
            // A deck that is no .kt file would only show in Finder, which the next item does.
            if (rememberOpensInEditor(deck)) Item(stringResource(Res.string.open_in_editor)) { viewer.openInEditor() }
            Item(stringResource(Res.string.menu_reveal_in_finder)) { viewer.revealInFinder() }
            Separator()
            Item(stringResource(Res.string.menu_close_deck), shortcut = KeyShortcut(Key.W, meta = true)) { viewer.close() }
            Separator()
        },
        overlay = { SafeModeStatus(status) },
        renderer = renderer,
    )
}

/** Why safe mode shows no slides: the deck did not compile, may not be copied for the child, or the child stopped. */
@Composable
private fun BoxScope.SafeModeStatus(status: SafeModeSession.Status) {
    when (status) {
        is SafeModeSession.Status.Failed ->
            ErrorPanel(status.diagnostics, title = stringResource(Res.string.safe_mode_failed_title), Modifier.align(Alignment.BottomCenter))
        is SafeModeSession.Status.Refused ->
            ErrorPanel(emptyList(), title = refusal(status.reason), Modifier.align(Alignment.BottomCenter))
        SafeModeSession.Status.Stopped ->
            ErrorPanel(emptyList(), title = stringResource(Res.string.safe_mode_stopped), Modifier.align(Alignment.BottomCenter))
        SafeModeSession.Status.Loading, is SafeModeSession.Status.Loaded -> {}
    }
}

@Composable
private fun refusal(reason: DeckCopy.Refused): String =
    when (reason) {
        is DeckCopy.HardLink -> stringResource(Res.string.safe_mode_hard_link, reason.file)
        is DeckCopy.Outside -> stringResource(Res.string.safe_mode_outside, reason.file)
        DeckCopy.TooLarge -> stringResource(Res.string.safe_mode_too_large, "%,d".format(DeckCopy.MAX_ENTRIES), DeckCopy.MAX_BYTES shr 30)
    }
