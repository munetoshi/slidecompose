package io.github.munetoshi.slidecompose.viewer.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.viewer.compile.deckSources
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * Asks whether the user trusts a deck or theme [file] before any of its code runs: see [DeckTrust].
 * Opening it in the editor first shows what it would run.
 */
@Composable
internal fun TrustPrompt(
    file: File,
    title: String,
    body: String,
    trustLabel: String,
    colors: HomeColors,
    onTrust: () -> Unit,
    onOpenInEditor: () -> Unit,
    onCancel: () -> Unit,
    onSafeMode: (() -> Unit)? = null,
    /** For a deck of one file: opens it in safe mode with files the user chooses, such as its images. */
    onSafeModeWithFiles: (() -> Unit)? = null,
    /** Whether the deck is a folder with jars in `libs`, which the editor cannot show. */
    hasLibraries: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            color = colors.secondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        if (hasLibraries) {
            Text(
                stringResource(Res.string.trust_libraries),
                color = colors.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        Text(
            file.path,
            color = colors.secondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        // Safe mode is the way to open a deck without trusting its author.
        if (onSafeMode != null) {
            Text(
                stringResource(Res.string.safe_mode_explain),
                color = colors.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        if (onSafeModeWithFiles != null) {
            Text(
                stringResource(Res.string.safe_mode_files_explain),
                color = colors.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTrust) { Text(trustLabel) }
            if (onSafeMode != null) {
                OutlinedButton(onClick = onSafeMode) { Text(stringResource(Res.string.safe_mode_open)) }
            }
            if (onSafeModeWithFiles != null) {
                OutlinedButton(onClick = onSafeModeWithFiles) { Text(stringResource(Res.string.safe_mode_open_with_files)) }
            }
            OutlinedButton(onClick = onOpenInEditor) { Text(openInEditorLabel(file)) }
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
        }
    }
}

/**
 * What opening [file] in the editor does: open it, or show it in Finder when it is no `.kt` file
 * (see [editorFileOf]).
 */
@Composable
internal fun openInEditorLabel(file: File): String =
    stringResource(if (rememberOpensInEditor(file)) Res.string.open_in_editor else Res.string.menu_reveal_in_finder)

/** Whether [io.github.munetoshi.slidecompose.viewer.app.Viewer.openInEditor] opens [file] in the editor rather than showing it in Finder. */
@Composable
internal fun rememberOpensInEditor(file: File): Boolean = remember(file) { editorFileOf(file) != null }

/**
 * The `.kt` file to open in an editor for [file], a deck or theme: the file itself, or a folder
 * deck's first source, with links followed. `null` to show [file] in Finder instead. Opening asks
 * macOS for the app that opens such a file, and a deck dropped on the Dock or opened with "Open
 * With" may be any file: a `.command` file would run in Terminal, and a `.app` or `.jar` would
 * start. A link named `x.kt` may lead to any of them, so what counts is the file it leads to.
 */
internal fun editorFileOf(file: File): File? {
    val source = if (file.isDirectory) deckSources(file).firstOrNull() ?: return null else file
    return source.canonicalFile.takeIf { it.isFile && it.extension == "kt" }
}
