package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.window.PresentationWindows
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import io.github.munetoshi.slidecompose.viewer.compile.assetDirectoryOf
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.DialogTitles
import io.github.munetoshi.slidecompose.viewer.home.RecentMenu
import io.github.munetoshi.slidecompose.viewer.home.WelcomeWindow
import io.github.munetoshi.slidecompose.viewer.home.dialogTitles
import io.github.munetoshi.slidecompose.viewer.home.rememberOpensInEditor
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeWindows
import io.github.munetoshi.slidecompose.viewer.theme.ThemeMenu
import io.github.munetoshi.slidecompose.viewer.theme.ThemeTrustDialog
import java.io.File
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ApplicationScope.ViewerWindows(viewer: Viewer) {
    val deck = viewer.deck
    val safeMode = viewer.safeMode
    if (deck != null && safeMode != null) {
        SafeModeWindows(viewer, deck, safeMode)
        return
    }
    if (deck != null && viewer.isTrusted) {
        LaunchedEffect(deck) { viewer.watch(deck) }
    }
    val controller = viewer.controller
    if (deck == null || controller == null) {
        WelcomeWindow(viewer)
        return
    }
    val titles = dialogTitles()
    DeckWindows(viewer, deck, controller, titles)
    ThemeTrustDialog(viewer)
}

@Composable
private fun ApplicationScope.DeckWindows(viewer: Viewer, deck: File, controller: PresentationController, titles: DialogTitles) {
    PresentationWindows(
        controller = controller,
        state = viewer.windows,
        assetDirectory = assetDirectoryOf(deck),
        title = if (viewer.hasLoadedDeck) "${controller.presentation.title} — ${deck.name}" else deck.name,
        onCloseRequest = viewer::close,
        fileMenu = {
            val frame = viewer.windows.firstFrame
            Item(stringResource(Res.string.menu_new_deck), shortcut = KeyShortcut(Key.N, meta = true)) { viewer.createDeck(frame, titles) }
            Item(stringResource(Res.string.menu_open_deck), shortcut = KeyShortcut(Key.O, meta = true)) { viewer.chooseDeck(frame, titles) }
            Item(stringResource(Res.string.menu_open_deck_folder), shortcut = KeyShortcut(Key.O, meta = true, shift = true)) {
                viewer.chooseDeckDirectory(frame, titles)
            }
            RecentMenu(viewer)
            Separator()
            Item(stringResource(Res.string.menu_reload), shortcut = KeyShortcut(Key.R, meta = true)) { viewer.reloadRequests++ }
            // A deck that is no .kt file would only show in Finder, which the next item does.
            if (rememberOpensInEditor(deck)) Item(stringResource(Res.string.open_in_editor)) { viewer.openInEditor() }
            Item(stringResource(Res.string.menu_reveal_in_finder)) { viewer.revealInFinder() }
            Separator()
            Item(stringResource(Res.string.menu_close_deck), shortcut = KeyShortcut(Key.W, meta = true)) { viewer.close() }
            Separator()
        },
        viewMenu = { ThemeMenu(viewer) },
        overlay = { CompileStatus(viewer) },
    )
}

@Composable
private fun BoxScope.CompileStatus(viewer: Viewer) {
    if (viewer.isCompiling) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(16.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xE0202024))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.compiling), color = Color.White, fontSize = 14.sp)
        }
    }
    val errors = viewer.errors
    if (errors.isEmpty()) return
    if (!viewer.showErrors) {
        Text(
            pluralStringResource(Res.plurals.error_count, errors.size, errors.size),
            Modifier.align(Alignment.TopStart).padding(16.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFFB3261E))
                // Clicked, it leaves the keys to the slides, as the windows' own controls do.
                .focusProperties { canFocus = false }
                .clickable { viewer.showErrors = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White,
            fontSize = 14.sp,
        )
        return
    }
    ErrorPanel(
        errors,
        title = stringResource(if (viewer.hasLoadedDeck) Res.string.compile_error_title else Res.string.compile_error_title_first),
        Modifier.align(Alignment.BottomCenter),
        onClose = { viewer.showErrors = false },
    )
}

@Composable
internal fun ErrorPanel(errors: List<Diagnostic>, title: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF01E1012))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Color(0xFFFF8A80),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            if (onClose != null) {
                Text(
                    stringResource(Res.string.hide),
                    Modifier.clip(RoundedCornerShape(4.dp)).focusProperties { canFocus = false }.clickable(onClick = onClose).padding(6.dp),
                    color = Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                errors.forEach { error ->
                    Text(error.toString(), color = Color(0xFFF2F2F2), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    error.lineContent?.takeIf { it.isNotBlank() }?.let { line ->
                        Text(
                            "    " + line.trimEnd(),
                            Modifier.padding(bottom = 8.dp),
                            color = Color(0xFF9E9E9E),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
