package io.github.munetoshi.slidecompose.deck

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.munetoshi.slidecompose.deck.components.SlideNumber
import io.github.munetoshi.slidecompose.deck.transition.ChangeLook
import io.github.munetoshi.slidecompose.deck.transition.Motion
import io.github.munetoshi.slidecompose.deck.transition.lookAt
import io.github.munetoshi.slidecompose.deck.transition.totalMillis

/**
 * The change of slides a stage is showing, for what its background shows of each slide, such as
 * [SlideNumber]: the background itself stays still while the slides change.
 */
internal class SlideChange(val presentation: Presentation, val transition: Transition<Frame>, val motion: () -> Motion)

internal val LocalSlideChange = staticCompositionLocalOf<SlideChange?> { null }

/**
 * Shows [content] for the slide on screen, in a stage's background, which stays still while the
 * slides change. As they change, [content] for the old slide goes as that slide does, from where it
 * was, and [content] for the new one comes as that one does: fading, or pushed, with them. Outside
 * a stage, as in exports, it shows [content] for this slide.
 */
@Composable
internal fun SlideScope.ChangesWithSlides(modifier: Modifier, content: @Composable (SlideScope) -> Unit) {
    val change = LocalSlideChange.current
    if (change == null) {
        Box(modifier) { content(this@ChangesWithSlides) }
        return
    }
    val transition = change.transition
    val from = transition.currentState
    val to = transition.targetState
    // From the old slide's number to the new one's, over the change.
    val position = transition.animateFloat(
        transitionSpec = { tween(change.motion().transition.totalMillis, easing = LinearEasing) },
        label = "slide change",
    ) { it.slide.toFloat() }
    val oldScope = remember(from, change.presentation) { change.presentation.scopeFor(from) }
    val newScope = remember(to, change.presentation) { change.presentation.scopeFor(to) }
    // Where this was placed for each slide, so that the old slide's part stays where it was.
    val places = remember { HashMap<Int, Offset>() }
    val size = change.presentation.size
    Layout(
        content = {
            if (from.slide != to.slide) {
                Box(Modifier.graphicsLayer { showOld(change.motion().lookAt(fraction(position, from, to)), size) }) { content(oldScope) }
            }
            Box(Modifier.graphicsLayer { showNew(change.motion().lookAt(fraction(position, from, to)), size) }) { content(newScope) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val new = placeables.last()
        layout(new.width, new.height) {
            val here = coordinates?.positionInParent() ?: Offset.Zero
            places[to.slide] = here
            if (placeables.size > 1) placeables.first().place(((places[from.slide] ?: here) - here).round())
            new.place(0, 0)
        }
    }
}

/** How far the change from [from] to [to] has come, by the slide number [position] has reached. */
private fun fraction(position: State<Float>, from: Frame, to: Frame): Float =
    if (from.slide == to.slide) 1f else ((position.value - from.slide) / (to.slide - from.slide)).coerceIn(0f, 1f)

private fun GraphicsLayerScope.showOld(look: ChangeLook, size: SlideSize) {
    alpha = look.oldAlpha
    translationX = look.oldShift.x * size.width.dp.toPx()
    translationY = look.oldShift.y * size.height.dp.toPx()
}

private fun GraphicsLayerScope.showNew(look: ChangeLook, size: SlideSize) {
    alpha = look.newAlpha
    translationX = look.newShift.x * size.width.dp.toPx()
    translationY = look.newShift.y * size.height.dp.toPx()
}
