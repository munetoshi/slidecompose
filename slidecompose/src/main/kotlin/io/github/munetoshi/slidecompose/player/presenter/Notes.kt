package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/** "− 100% +": the size of the speaker notes. */
@Composable
internal fun NotesSizeButtons(size: TextUnit, onChange: (TextUnit) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(colors.background).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotesSizeButton("−", stringResource(Res.string.tooltip_smaller_notes)) { onChange((size.value - 2).coerceAtLeast(12f).sp) }
        NotesSizeButton("${(size.value / DefaultNotesFontSize.value * 100).roundToInt()}%", stringResource(Res.string.tooltip_reset_notes)) {
            onChange(DefaultNotesFontSize)
        }
        NotesSizeButton("+", stringResource(Res.string.tooltip_larger_notes)) { onChange((size.value + 2).coerceAtMost(72f).sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesSizeButton(text: String, tooltip: String, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(tooltip) }) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(4.dp)).keepsNoFocus().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
            color = colors.dim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Less than this, and the notes are left out: their padding alone takes 40 dp. */
internal val MinNotesHeight: Dp = 64.dp

/**
 * Speaker notes. With one note per build step, the others are dimmed, and the list scrolls to show
 * the note of [step], then the next, then the one before, as far as there is room (see [focusScroll]).
 */
@Composable
internal fun Notes(
    notes: List<String>,
    step: Int,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(colors.panel)) {
        NoteList(notes, step, fontSize, Modifier.fillMaxSize().padding(20.dp))
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { overlay() }
    }
}

@Composable
private fun NoteList(notes: List<String>, step: Int, fontSize: TextUnit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        if (notes.isEmpty()) {
            Text(stringResource(Res.string.no_notes), color = colors.dim, fontSize = fontSize)
            return@BoxWithConstraints
        }
        val focused = if (notes.size > 1) step.coerceIn(notes.indices) else -1
        val scroll = rememberScrollState()
        val tops = remember(notes) { IntArray(notes.size) }
        val bottoms = remember(notes) { IntArray(notes.size) }
        val viewport = constraints.maxHeight
        LaunchedEffect(notes, focused, fontSize, maxWidth) {
            if (focused < 0) return@LaunchedEffect
            withFrameNanos {} // Wait for the notes to be placed.
            scroll.animateScrollTo(noteScroll(tops, bottoms, focused, viewport))
        }
        // With more or less room, as while the list of slides opens, the notes follow at once.
        LaunchedEffect(viewport) {
            if (focused < 0) return@LaunchedEffect
            withFrameNanos {}
            scroll.scrollTo(noteScroll(tops, bottoms, focused, viewport))
        }
        val gap = with(LocalDensity.current) { (fontSize * 0.9f).toDp() }
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap)) {
            notes.forEachIndexed { index, note ->
                val alpha by animateFloatAsState(if (focused < 0 || index == focused) 1f else 0.3f, label = "note")
                Text(
                    note,
                    Modifier
                        .onPlaced {
                            tops[index] = it.positionInParent().y.toInt()
                            bottoms[index] = tops[index] + it.size.height
                        }
                        .graphicsLayer { this.alpha = alpha },
                    color = colors.text,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                )
            }
            // Room for the last note to scroll to the top.
            if (focused >= 0) Spacer(Modifier.height(viewportHeight))
        }
    }
}
