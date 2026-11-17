package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.player.window.AppearanceMenu
import io.github.munetoshi.slidecompose.viewer.compile.deckLibraries
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.HomeColors
import io.github.munetoshi.slidecompose.viewer.home.RecentDecks
import io.github.munetoshi.slidecompose.viewer.home.TrustPrompt
import io.github.munetoshi.slidecompose.viewer.home.homeColorScheme
import io.github.munetoshi.slidecompose.viewer.home.openInEditorLabel
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeLauncher
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ApplicationScope.WelcomeWindow(viewer: Viewer) {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SlideCompose",
        state = rememberWindowState(size = HomeWindowSize),
    ) {
        val titles = dialogTitles()
        MenuBar {
            Menu(stringResource(Res.string.menu_file)) {
                Item(stringResource(Res.string.menu_new_deck), shortcut = KeyShortcut(Key.N, meta = true)) { viewer.createDeck(window, titles) }
                Item(stringResource(Res.string.menu_open_deck), shortcut = KeyShortcut(Key.O, meta = true)) { viewer.chooseDeck(window, titles) }
                Item(stringResource(Res.string.menu_open_deck_folder), shortcut = KeyShortcut(Key.O, meta = true, shift = true)) {
                    viewer.chooseDeckDirectory(window, titles)
                }
                RecentMenu(viewer)
                if (viewer.deck != null) {
                    Separator()
                    Item(stringResource(Res.string.menu_close_deck), shortcut = KeyShortcut(Key.W, meta = true)) { viewer.close() }
                }
            }
            Menu(stringResource(Res.string.menu_view)) {
                AppearanceMenu(viewer.appearance) { viewer.appearance = it }
            }
        }
        HomeScreen(viewer, window, titles)
    }
}

/** The size the home window opens at, which its rows fit. */
internal val HomeWindowSize = DpSize(760.dp, 440.dp)

/** The home window's content: what SlideCompose is, the deck being loaded, and ways to open one. */
@Composable
internal fun HomeScreen(viewer: Viewer, window: java.awt.Frame?, titles: DialogTitles) {
    val dark = viewer.appearance.isDark()
    val colors = if (dark) HomeColors.Dark else HomeColors.Light
    MaterialTheme(colorScheme = homeColorScheme(dark)) {
        Column(
            Modifier.fillMaxSize().background(colors.background).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val deck = viewer.deck
            val safeMode = viewer.safeMode != null
            val asking = deck != null && !viewer.isTrusted && !safeMode
            Text("SlideCompose", color = colors.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            // The trust prompt says what a deck is, and needs the room.
            if (!asking) {
                Text(
                    stringResource(Res.string.home_intro),
                    color = colors.secondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
            when {
                deck != null && asking -> TrustPrompt(
                    deck,
                    title = stringResource(Res.string.trust_title, deck.name),
                    body = stringResource(if (deck.isDirectory) Res.string.trust_body_folder else Res.string.trust_body_file),
                    trustLabel = stringResource(Res.string.trust_and_open),
                    colors,
                    onTrust = viewer::trustDeck,
                    onOpenInEditor = { viewer.openInEditor() },
                    onCancel = viewer::close,
                    onSafeMode = if (SafeModeLauncher.isAvailable()) ({ viewer.openSafeMode() }) else null,
                    onSafeModeWithFiles = if (SafeModeLauncher.isAvailable() && !deck.isDirectory) ({ viewer.chooseFilesForSafeMode(window, titles) }) else null,
                    hasLibraries = remember(deck) { deckLibraries(deck).isNotEmpty() },
                )
                deck != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(if (safeMode) Res.string.safe_mode_loading else Res.string.compiling_deck, deck.name),
                        color = colors.text,
                        fontSize = 15.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // The recent decks above the buttons, so the buttons stay at the bottom with or without them.
            val recent = viewer.recentDecks
            if (deck == null && recent.isNotEmpty()) RecentDecks(recent, colors, onOpen = viewer::open)
            // While asking, the prompt has the buttons that matter. While a deck loads, another
            // cannot be opened, so only the deck's own buttons show: closing it stops the loading.
            when {
                deck == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewer.chooseDeck(window, titles) }) { Text(stringResource(Res.string.button_open_deck)) }
                    OutlinedButton(onClick = { viewer.chooseDeckDirectory(window, titles) }) { Text(stringResource(Res.string.button_open_folder)) }
                    OutlinedButton(onClick = { viewer.createDeck(window, titles) }) { Text(stringResource(Res.string.button_new_deck)) }
                }
                !asking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { viewer.openInEditor() }) { Text(openInEditorLabel(deck)) }
                    TextButton(onClick = { viewer.close() }) { Text(stringResource(Res.string.close)) }
                }
            }
        }
    }
}

/** The titles of the file dialogs. The dialogs open from click handlers, outside composition, where resources are not at hand. */
internal class DialogTitles(val openDeck: String, val openFolder: String, val newDeck: String, val safeModeFiles: String)

@Composable
internal fun dialogTitles() = DialogTitles(
    openDeck = stringResource(Res.string.dialog_open_deck),
    openFolder = stringResource(Res.string.dialog_open_deck_folder),
    newDeck = stringResource(Res.string.dialog_new_deck),
    safeModeFiles = stringResource(Res.string.dialog_safe_mode_files),
)
