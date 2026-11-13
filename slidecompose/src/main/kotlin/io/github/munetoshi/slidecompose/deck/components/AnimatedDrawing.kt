package io.github.munetoshi.slidecompose.deck.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import kotlinx.coroutines.launch

/**
 * Draws behind the content like [drawBehind], again on every frame, e.g. for a moving background
 * in [SlideTheme.slideModifier]. [onDraw] gets the time in seconds since the modifier was attached.
 * Only drawing happens each frame, not composition or layout.
 *
 * In [SlideTheme.slideModifier], it keeps moving across slide transitions. Exports draw it at 0 seconds.
 */
fun Modifier.drawBehindAnimated(onDraw: DrawScope.(seconds: Double) -> Unit): Modifier =
    this then AnimatedDrawElement(onDraw)

private data class AnimatedDrawElement(val onDraw: DrawScope.(seconds: Double) -> Unit) :
    ModifierNodeElement<AnimatedDrawNode>() {
    override fun create() = AnimatedDrawNode(onDraw)

    override fun update(node: AnimatedDrawNode) {
        node.onDraw = onDraw
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBehindAnimated"
    }
}

/** `true` while rendering a still image, e.g. for export: [drawBehindAnimated] then stays at 0 seconds. */
internal val LocalStillImage = staticCompositionLocalOf { false }

private class AnimatedDrawNode(var onDraw: DrawScope.(seconds: Double) -> Unit) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var start = -1L
    private var elapsed = 0L

    override fun onAttach() {
        start = -1L
        elapsed = 0L
        if (currentValueOf(LocalStillImage)) return
        coroutineScope.launch {
            while (true) tick()
        }
    }

    /** Waits for the next frame, and draws again at its time. */
    private suspend fun tick() {
        val now = withFrameNanos { it }
        if (start < 0) start = now
        elapsed = now - start
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        onDraw(elapsed / 1e9)
        drawContent()
    }
}
