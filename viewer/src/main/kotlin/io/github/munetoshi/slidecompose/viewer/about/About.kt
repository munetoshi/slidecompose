package io.github.munetoshi.slidecompose.viewer.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.HomeColors
import io.github.munetoshi.slidecompose.viewer.home.homeColorScheme
import java.util.Properties
import org.jetbrains.compose.resources.stringResource

/**
 * An entry in the About window's list of the software the app bundles: a group of libraries in one
 * version, or software from elsewhere, such as Skia. [texts] name its license and notice texts.
 */
data class ThirdPartyEntry(val title: String, val detail: String, val version: String, val license: String, val texts: List<String>)

/** The software the app bundles, as the build collected it, or nothing when it runs without that. */
fun thirdPartyEntries(): List<ThirdPartyEntry> = parseThirdPartyEntries(resourceText("notices/index.tsv").orEmpty())

/** Reads the build's `index.tsv`: per line title, detail, version, license and the texts' names, tab-separated. */
fun parseThirdPartyEntries(index: String): List<ThirdPartyEntry> =
    index.lineSequence()
        .filter { it.isNotBlank() }
        .map(String::toThirdPartyEntry)
        .toList()

private fun String.toThirdPartyEntry(): ThirdPartyEntry {
    val (title, detail, version, license, texts) = split('\t')
    return ThirdPartyEntry(title, detail, version, license, texts.splitToSequence(' ').filter { it.isNotEmpty() }.toList())
}

/** A license or notice text that an entry names. */
fun thirdPartyText(name: String): String = resourceText("notices/texts/$name.txt").orEmpty().trimEnd()

/** The app's version and copyright, as the build gives them, or empty when it runs without that. */
private val aboutProperties: Properties by lazy { propertiesOf(resourceText("about.properties")) }

private fun propertiesOf(text: String?): Properties {
    val properties = Properties()
    if (text != null) properties.load(text.reader())
    return properties
}

val appVersion: String? get() = aboutProperties.getProperty("version")

val appCopyright: String? get() = aboutProperties.getProperty("copyright")

private fun resourceText(path: String): String? = ThirdPartyEntry::class.java.getResource("/io/github/munetoshi/slidecompose/viewer/$path")?.readText()

/** The app's icon, as the build gives it, or `null` when it runs without that. */
internal fun appIcon(density: Density): Painter? =
    ThirdPartyEntry::class.java.getResourceAsStream("/io/github/munetoshi/slidecompose/viewer/icon.svg")?.use { loadSvgPainter(it, density) }

/**
 * The About window, as macOS's About panel with the bundled software listed below. Each [requests]
 * after the first brings it to the front.
 */
@Composable
fun AboutWindow(dark: Boolean, requests: Int, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = stringResource(Res.string.about_title),
        state = rememberWindowState(size = DpSize(640.dp, 720.dp), position = WindowPosition(Alignment.Center)),
        // As other windows on macOS, ⌘W closes it.
        onPreviewKeyEvent = { event ->
            val close = event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.W
            if (close) onClose()
            close
        },
    ) {
        LaunchedEffect(requests) { window.toFront() }
        AboutScreen(dark)
    }
}

@Composable
fun AboutScreen(dark: Boolean) {
    val colors = if (dark) HomeColors.Dark else HomeColors.Light
    val entries = remember { thirdPartyEntries() }
    var expanded by remember { mutableStateOf(emptySet<Int>()) }
    MaterialTheme(colorScheme = homeColorScheme(dark)) {
        LazyColumn(
            Modifier.fillMaxSize().background(colors.background),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 32.dp),
        ) {
            item { AboutHeader(colors) }
            item {
                Spacer(Modifier.height(28.dp))
                Text(stringResource(Res.string.about_third_party), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(Res.string.about_third_party_note), color = colors.secondary, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(entries) { index, entry ->
                ThirdPartyRow(entry, colors, isExpanded = index in expanded) {
                    expanded = if (index in expanded) expanded - index else expanded + index
                }
            }
        }
    }
}

@Composable
private fun AboutHeader(colors: HomeColors) {
    val density = LocalDensity.current
    val icon = remember(density) { appIcon(density) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // As in macOS's About panels; the icon's own margin sets it off from the name.
        icon?.let { Image(it, contentDescription = null, Modifier.size(112.dp)) }
        Text("SlideCompose", color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        appVersion?.let { Text(stringResource(Res.string.about_version, it), color = colors.secondary, fontSize = 13.sp) }
        Text(
            stringResource(Res.string.about_description),
            color = colors.secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        appCopyright?.let { Text(it, color = colors.secondary, fontSize = 12.sp) }
        Text(stringResource(Res.string.about_license), color = colors.secondary, fontSize = 12.sp)
    }
}

/** An entry: its name, what it contains, version and license, and when [isExpanded] its texts. */
@Composable
private fun ThirdPartyRow(entry: ThirdPartyEntry, colors: HomeColors, isExpanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(if (isExpanded) "▾" else "▸", Modifier.width(16.dp), color = colors.secondary, fontSize = 12.sp, lineHeight = 20.sp)
            Column(Modifier.weight(1f)) {
                // The version beside the name, the license beside what it contains.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(entry.title, Modifier.weight(1f), color = colors.text, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(entry.version, Modifier.padding(start = 16.dp), color = colors.secondary, fontSize = 12.sp, lineHeight = 20.sp)
                }
                Row {
                    Text(entry.detail, Modifier.weight(1f), color = colors.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(entry.license, Modifier.padding(start = 16.dp), color = colors.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        if (isExpanded) {
            Column(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in entry.texts) {
                    SelectionContainer {
                        Text(
                            thirdPartyText(name),
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.panel).padding(12.dp),
                            color = colors.text,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
