package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.rememberDialogState
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.HomeColors
import io.github.munetoshi.slidecompose.viewer.home.homeColorScheme
import io.github.munetoshi.slidecompose.viewer.home.TrustPrompt
import io.github.munetoshi.slidecompose.viewer.theme.BuiltInTheme
import io.github.munetoshi.slidecompose.viewer.theme.ThemeChoice
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the deck in its own theme, a built-in one, one from its `themes` folder, or a theme file
 * from anywhere.
 */
@Composable
internal fun MenuScope.ThemeMenu(viewer: Viewer) {
    val dialogTitle = stringResource(Res.string.dialog_choose_theme)
    Menu(stringResource(Res.string.menu_theme)) {
        val choice = viewer.themeChoice
        RadioButtonItem(stringResource(Res.string.theme_deck_default), selected = choice == ThemeChoice.Deck) {
            viewer.chooseTheme(ThemeChoice.Deck)
        }
        Separator()
        for (theme in BuiltInTheme.entries) {
            val option = ThemeChoice.BuiltIn(theme)
            RadioButtonItem(theme.name, selected = choice == option) { viewer.chooseTheme(option) }
        }
        val files = viewer.menuThemeFiles()
        if (files.isNotEmpty()) Separator()
        for (file in files) {
            // The file's main theme, then its presets once it has compiled.
            val presets = viewer.themeNames[file].orEmpty().drop(1)
            for (preset in listOf(null) + presets) {
                val option = ThemeChoice.File(file, preset)
                val label = if (preset == null) file.nameWithoutExtension else "${file.nameWithoutExtension} ($preset)"
                RadioButtonItem(label, selected = choice == option) { viewer.chooseTheme(option) }
            }
        }
        Separator()
        Item(stringResource(Res.string.menu_choose_theme_file)) { viewer.chooseThemeFile(viewer.windows.firstFrame, dialogTitle) }
    }
}

/** Asks whether the user trusts a theme file from outside the deck before it runs, as [TrustPrompt] does for decks. */
@Composable
internal fun ThemeTrustDialog(viewer: Viewer) {
    val file = viewer.untrustedTheme ?: return
    DialogWindow(
        onCloseRequest = viewer::cancelTheme,
        state = rememberDialogState(size = DpSize(640.dp, 340.dp)),
        title = "SlideCompose",
        resizable = false,
    ) {
        val dark = viewer.appearance.isDark()
        val colors = if (dark) HomeColors.Dark else HomeColors.Light
        MaterialTheme(colorScheme = homeColorScheme(dark)) {
            Box(Modifier.fillMaxSize().background(colors.background).padding(20.dp)) {
                TrustPrompt(
                    file,
                    title = stringResource(Res.string.trust_title, file.name),
                    body = stringResource(Res.string.trust_body_theme),
                    trustLabel = stringResource(Res.string.trust_and_load),
                    colors,
                    onTrust = viewer::trustTheme,
                    onOpenInEditor = { viewer.openInEditor(file) },
                    onCancel = viewer::cancelTheme,
                )
            }
        }
    }
}
