package io.github.munetoshi.slidecompose.viewer.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuScope
import io.github.munetoshi.slidecompose.viewer.app.Viewer
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import java.io.File
import org.jetbrains.compose.resources.stringResource

/** How many recent decks the home window lists. */
internal const val HomeRecentCount = 3

/** The File menu's Open Recent, with the recent decks by name and an item that clears them. */
@Composable
internal fun MenuScope.RecentMenu(viewer: Viewer) {
    val decks = viewer.recentDecks
    Menu(stringResource(Res.string.menu_open_recent), enabled = decks.isNotEmpty()) {
        decks.forEach { deck -> Item(deck.name) { viewer.open(deck) } }
        Separator()
        Item(stringResource(Res.string.menu_clear_recent), onClick = viewer::clearRecentDecks)
    }
}

/** The latest of the recent decks, each its name and then its folder, opened by a click. */
@Composable
internal fun RecentDecks(decks: List<File>, colors: HomeColors, onOpen: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(Res.string.recent_decks), Modifier.padding(start = 4.dp), color = colors.secondary, fontSize = 13.sp)
        decks.asSequence().take(HomeRecentCount).forEach { deck -> RecentDeck(deck, colors) { onOpen(deck) } }
    }
}

@Composable
private fun RecentDeck(deck: File, colors: HomeColors, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(deck.name, color = colors.link, fontSize = 14.sp, maxLines = 1)
        Text(
            "  ${deck.parentFile?.path?.let(::tildeHome).orEmpty()}",
            color = colors.secondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [path] with the home folder written as `~`. */
internal fun tildeHome(path: String): String {
    val home = System.getProperty("user.home")
    return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home) else path
}
