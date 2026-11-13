@file:OptIn(ExperimentalSharedTransitionApi::class)

package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.SlideSize
import io.github.munetoshi.slidecompose.deck.code.LocalSharesCaret
import io.github.munetoshi.slidecompose.deck.scopeFor
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideStyle
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.deck.theme.ProvideSlideTheme
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.deck.transition.ElementLooks
import io.github.munetoshi.slidecompose.deck.transition.ElementTransitionContext
import io.github.munetoshi.slidecompose.deck.transition.LocalElementTransition
import io.github.munetoshi.slidecompose.deck.transition.LocalSlideChange
import io.github.munetoshi.slidecompose.deck.transition.Motion
import io.github.munetoshi.slidecompose.deck.transition.SlideChange
import io.github.munetoshi.slidecompose.deck.transition.contentTransform
import io.github.munetoshi.slidecompose.generated.resources.Res
import io.github.munetoshi.slidecompose.generated.resources.no_slides
import java.awt.Cursor
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/**
 * Lays out [content] in the presentation's logical coordinate space and scales it to fit,
 * letterboxed. Scaling is done by changing the density, so text and vectors stay sharp.
 */
@Composable
fun SlideViewport(
    size: SlideSize,
    modifier: Modifier = Modifier,
    letterboxColor: Color = Color.Black,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.background(letterboxColor), contentAlignment = Alignment.Center) {
        val outer = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        if (maxWidthPx <= 0f || maxHeightPx <= 0f || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            return@BoxWithConstraints
        }
        val scale = min(maxWidthPx / size.width, maxHeightPx / size.height)
        val widthDp = with(outer) { (size.width * scale).toDp() }
        val heightDp = with(outer) { (size.height * scale).toDp() }
        Box(Modifier.requiredSize(widthDp, heightDp).clipToBounds()) {
            CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
                content()
            }
        }
    }
}

/**
 * The animated slide show: renders the controller's current frame with transitions.
 *
 * @param showsPointer whether to draw a pointer where [PresentationController.pointer] is, as
 *   the audience window does, while [PresentationController.isPointerShared].
 */
@Composable
fun SlideStage(
    controller: PresentationController,
    modifier: Modifier = Modifier,
    showsPointer: Boolean = false,
) {
    val presentation = controller.presentation
    SlideViewport(presentation.size, modifier) {
        if (presentation.slides.isEmpty()) {
            EmptyPresentation(presentation)
            return@SlideViewport
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedSlides(controller)
            if (showsPointer) SharedPointer(controller)
        }
    }
}

@Composable
private fun AnimatedSlides(controller: PresentationController) {
    val presentation = controller.presentation
    key(controller.revision) {
        // Moving on during a transition first completes it instantly, then starts the next
        // one from there, so transitions never pile up and always run between whole frames.
        val transitionState = remember { SeekableTransitionState(controller.frame) }
        LaunchedEffect(transitionState) {
            snapshotFlow { controller.frame to controller.motion }.collectLatest { (frame, motion) ->
                if (transitionState.currentState != transitionState.targetState) {
                    transitionState.snapTo(transitionState.targetState)
                    // Lay out the completed frame once, so that element transitions start from where
                    // its elements ended up rather than from mid-flight.
                    withFrameNanos {}
                    withFrameNanos {}
                }
                if (motion == Motion.Initial) transitionState.snapTo(frame) else transitionState.animateTo(frame)
            }
        }
        val transition = rememberTransition(transitionState, label = "slide")
        ProvideSlideTheme(presentation.theme) {
            Box(Modifier.fillMaxSize().background(presentation.theme.background).then(presentation.theme.slideModifier)) {
                presentation.background?.let { background ->
                    val frame = controller.frame
                    val scope = remember(frame, presentation) { presentation.scopeFor(frame) }
                    // The background stays still, but what it shows of each slide changes with them.
                    val change = remember(presentation, transition) { SlideChange(presentation, transition) { controller.motion } }
                    CompositionLocalProvider(LocalSlideChange provides change) { background(scope) }
                }
                val looks = remember { ElementLooks() }
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    transition.AnimatedContent(
                        modifier = Modifier.fillMaxSize(),
                        contentKey = { frame ->
                            val slide = presentation.slides.getOrNull(frame.slide)
                            if (slide?.stepTransition != null) frame else frame.slide
                        },
                        transitionSpec = { contentTransform(controller.motion) },
                    ) { frame ->
                        val sharedScope = this@SharedTransitionLayout
                        val visibilityScope = this@AnimatedContent
                        val elementTransition = remember(sharedScope, visibilityScope) {
                            ElementTransitionContext(sharedScope, visibilityScope, looks) { controller.motion.transition }
                        }
                        // Text fields on the slides show where another window types in them, as the slides show where it points.
                        CompositionLocalProvider(LocalElementTransition provides elementTransition, LocalSharesCaret provides controller.isPointerShared) {
                            SlideContent(presentation, frame)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a single frame without any transition, e.g. for previews and export.
 * [elementTransition] modifiers have no effect here.
 */
@Composable
fun StaticSlide(
    presentation: Presentation,
    frame: Frame,
    modifier: Modifier = Modifier,
    letterboxColor: Color = Color.Black,
) {
    SlideViewport(presentation.size, modifier, letterboxColor) {
        if (presentation.slides.isEmpty()) {
            EmptyPresentation(presentation)
            return@SlideViewport
        }
        ProvideSlideTheme(presentation.theme) {
            Box(Modifier.fillMaxSize().background(presentation.theme.background).then(presentation.theme.slideModifier)) {
                val scope = remember(frame, presentation) { presentation.scopeFor(frame) }
                presentation.background?.invoke(scope)
                SlideContent(presentation, frame)
            }
        }
    }
}

@Composable
private fun SlideContent(presentation: Presentation, frame: Frame) {
    val slide = presentation.slides.getOrNull(frame.slide) ?: return
    val scope = remember(frame, presentation) { presentation.scopeFor(frame) }
    CompositionLocalProvider(LocalSlideStyle provides slide.style) {
        val theme = slide.theme
        if (theme == null) {
            Box(Modifier.fillMaxSize()) { slide.content(scope) }
        } else {
            // A theme of its own: the slide covers the presentation's background with its own,
            // and draws the presentation's background content again over it, in its theme.
            ProvideSlideTheme(theme) {
                Box(Modifier.fillMaxSize().background(theme.background).then(theme.slideModifier)) {
                    presentation.background?.invoke(scope)
                    slide.content(scope)
                }
            }
        }
    }
}

/**
 * The pointer shared from the presenter window, while [PresentationController.isPointerShared]. Put
 * it in a [SlideViewport] over slides drawn some other way than [SlideStage], which draws it
 * itself with `showsPointer`.
 */
@Composable
fun SharedPointer(controller: PresentationController) {
    SharedPointer(
        controller.pointer.takeIf { controller.isPointerShared },
        controller.pointerShape,
        controller.effectivePointerColor,
        hidesAfter = PointerIdleTime.takeIf { controller.hidesStillPointer },
    )
}

/** The shapes the shared pointer takes after the presenter's cursor. */
enum class PointerShape {
    /** The arrow, for any cursor but the text one. */
    Arrow,

    /** The I-beam, over text that can be edited or selected. */
    Text,
}

/** The shape the shared pointer takes for [cursor] in the presenter window. */
fun pointerShapeOf(cursor: Cursor?): PointerShape =
    if (cursor?.type == Cursor.TEXT_CURSOR) PointerShape.Text else PointerShape.Arrow

/** How long the shared pointer stays still before it fades out, when [PresentationController.hidesStillPointer]. */
val PointerIdleTime: Duration = 3.seconds

/** How long the shared pointer takes to fade out once it has stayed still. */
internal const val PointerFadeMillis: Int = 400

/**
 * A pointer in [shape] with its hot spot at [position], given as fractions of the slide's width
 * and height: the arrow's tip, or the I-beam's middle. Like the system cursor, it can stick out of
 * the slide: past the left or top edge, the rest of the arrow is still seen. Unless [hidesAfter]
 * is `null`, it fades out once it has stayed still that long, and comes back as soon as it moves.
 */
@Composable
private fun SharedPointer(position: Offset?, shape: PointerShape, color: PointerColor, hidesAfter: Duration?) {
    val still by produceState(false, position, hidesAfter) {
        value = false
        if (hidesAfter != null) {
            delay(hidesAfter)
            value = true
        }
    }
    // Back at once when it moves, fading only on the way out.
    val alpha by animateFloatAsState(if (still) 0f else 1f, if (still) tween(PointerFadeMillis) else snap(), label = "pointer")
    if (position == null || alpha == 0f) return
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        // In slide units: this draws in the slide's density.
        translate(position.x * size.width, position.y * size.height) {
            when (shape) {
                PointerShape.Arrow -> drawPointerArrow(color, density)
                PointerShape.Text -> drawPointerIBeam(color, density)
            }
        }
    }
}

/** An I-beam like the system text cursor's, with its middle at the origin, times [scale]. */
internal fun DrawScope.drawPointerIBeam(color: PointerColor, scale: Float) {
    scale(scale, pivot = Offset.Zero) {
        val beam = pointerIBeamPath()
        translate(1.5f, 2f) { drawPath(beam, Color.Black.copy(alpha = 0.25f), style = IBeamOutline) }
        drawPath(beam, color.outline, style = IBeamOutline)
        drawPath(beam, color.fill, style = Stroke(width = IBeamWidth, cap = StrokeCap.Round))
    }
}

/** The I-beam's stem and its serifs at both ends, as strokes, its middle at the origin. */
internal fun pointerIBeamPath(): Path = Path().apply {
    moveTo(-8f, -22f)
    lineTo(-3f, -22f)
    quadraticTo(0f, -22f, 0f, -18f)
    quadraticTo(0f, -22f, 3f, -22f)
    lineTo(8f, -22f)
    moveTo(0f, -18f)
    lineTo(0f, 18f)
    moveTo(-8f, 22f)
    lineTo(-3f, 22f)
    quadraticTo(0f, 22f, 0f, 18f)
    quadraticTo(0f, 22f, 3f, 22f)
    lineTo(8f, 22f)
}

private const val IBeamWidth = 3.5f
private val IBeamOutline = Stroke(width = IBeamWidth + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** An arrow like the system cursor's, with its tip at the origin, [PointerArrowSize] times [scale]. */
internal fun DrawScope.drawPointerArrow(color: PointerColor, scale: Float) {
    scale(scale, pivot = Offset.Zero) {
        val arrow = pointerArrowPath()
        translate(2f, 3f) { drawPath(arrow, Color.Black.copy(alpha = 0.25f)) }
        drawPath(arrow, color.fill)
        drawPath(arrow, color.outline, style = Stroke(width = 3f, join = StrokeJoin.Round))
    }
}

internal fun pointerArrowPath(): Path = Path().apply {
    moveTo(0f, 0f)
    lineTo(0f, 46f)
    lineTo(11f, 35f)
    lineTo(19f, 54f)
    lineTo(27f, 50f)
    lineTo(19f, 32f)
    lineTo(34f, 32f)
    close()
}

@Composable
private fun EmptyPresentation(presentation: Presentation) {
    Box(Modifier.fillMaxSize().background(presentation.theme.background), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.no_slides), color = presentation.theme.muted)
    }
}

/** The size of the shared pointer's arrow, shadow and outline included, in slide units. */
internal val PointerArrowSize = Size(37f, 58f)
