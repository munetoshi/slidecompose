@file:OptIn(ExperimentalSharedTransitionApi::class)

package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.munetoshi.slidecompose.deck.code.CodeLook
import io.github.munetoshi.slidecompose.deck.code.CodeMorph
import io.github.munetoshi.slidecompose.deck.components.SlideText

/** How a [elementTransition] element changes size during an element transition. */
enum class ElementTransitionMode {
    /**
     * The element is laid out at its own size and scaled to the animated width, centred in the
     * animated bounds. Best for text and images: they grow and shrink like in Keynote.
     */
    Scale,

    /**
     * The element is re-laid out at the animated bounds on every frame.
     * Best for boxes, cards and shapes whose corners and borders should not be stretched.
     */
    Resize,
}

@Stable
internal class ElementTransitionContext(
    val sharedScope: SharedTransitionScope,
    val visibilityScope: AnimatedVisibilityScope,
    /** Shared by the frames on screen, to tell an element what it looks like in the other frame. */
    val looks: ElementLooks,
    /** Reads the transition in effect; backed by snapshot state. */
    val currentTransition: () -> ResolvedTransition,
) {
    val elementTransition: ResolvedTransition.ElementTransition? get() = currentTransition() as? ResolvedTransition.ElementTransition

    /**
     * Shared bounds must stay attached even when the current transition is not an element transition:
     * a match can only animate from bounds that were measured before the transition started.
     * Instead, matching is switched off through this config.
     */
    val config: SharedTransitionScope.SharedContentConfig = MatchingWhileElementTransition(this)
}

/** Shared bounds that match only while [context]'s transition is an element transition: see [ElementTransitionContext.config]. */
private class MatchingWhileElementTransition(private val context: ElementTransitionContext) : SharedTransitionScope.SharedContentConfig {
    override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
        get() = context.elementTransition != null
}

/** Non-null only while rendering a slide inside an animated stage. */
internal val LocalElementTransition = compositionLocalOf<ElementTransitionContext?> { null }

/** Collects the [elementTransition] ids in a frame while it is composed off-screen. */
internal class ElementIdRecorder {
    val ids = mutableSetOf<Any>()
}

internal val LocalElementIdRecorder = staticCompositionLocalOf<ElementIdRecorder?> { null }

/** How the elements with element transition ids look in the frames on screen. */
internal class ElementLooks {
    class Entry(val id: Any, val scope: AnimatedVisibilityScope) {
        /** Whether the element asks to be cross-faded as a whole. */
        var crossfade = false

        /** How it looks, when it is text that says so ([SlideText]). */
        var text: TextLook? = null

        /** What it shows, when it is a code block: see [CodeMorph]. */
        var code: CodeLook? = null
    }

    private val entries = mutableListOf<Entry>()

    fun register(id: Any, scope: AnimatedVisibilityScope): Entry = Entry(id, scope).also { entries += it }

    fun unregister(entry: Entry) {
        entries -= entry
    }

    /** Whether [scope]'s frame has an element with [id]. */
    fun has(id: Any, scope: AnimatedVisibilityScope): Boolean = entries.any { it.id == id && it.scope === scope }

    /** The element with [id] in another frame than [scope]'s, if there is one. */
    fun elsewhere(id: Any, scope: AnimatedVisibilityScope): Entry? = entries.lastOrNull { it.id == id && it.scope !== scope }
}

/**
 * Whether two matching elements cross-fade as a whole while they move: when either asks to, or
 * when they are text that says different things. Both frames decide alike.
 */
internal fun crossfadesAsWhole(one: ElementLooks.Entry, other: ElementLooks.Entry): Boolean {
    val oneText = one.text
    val otherText = other.text
    return one.crossfade || other.crossfade || (oneText != null && otherText != null && oneText.text != otherText.text)
}

/** What of a text's look an element transition changes gradually, and what it says. */
@Immutable
internal data class TextLook(
    val text: AnnotatedString,
    val fontFamily: FontFamily?,
    val weight: FontWeight,
    val fontStyle: FontStyle?,
    val color: Color,
) {
    /** Whether [other] is set in another font, not only another weight: then the letters change shape. */
    fun changesFontFrom(other: TextLook): Boolean = fontFamily != other.fontFamily || fontStyle != other.fontStyle
}

/**
 * The same text changing from looking like [from] to [to] while it comes in. [fraction] goes from 0
 * to 1 as the element moves, eased; [fade] goes from 0 to 1 evenly over the same time.
 */
internal class TextMorph(val from: TextLook, val to: TextLook, val fraction: () -> Float, val fade: () -> Float) {
    /** The color now, or `null` if it does not change. */
    fun color(): Color? = if (from.color == to.color || !from.color.isSpecified) null else lerp(from.color, to.color, fraction())
}

/**
 * For text with element transition [id] that looks like [look]: while it comes in with an
 * element transition from the same text looking otherwise, how it changes; otherwise `null`.
 * Text that says something else, or an element that asks to cross-fade, cross-fades as a whole
 * instead (see [crossfadesAsWhole]).
 */
@Composable
internal fun elementTextMorph(id: Any?, look: TextLook): TextMorph? {
    val context = LocalElementTransition.current
    if (id == null || context == null) return null
    // What the element looked like in the frame it comes from, when this frame came in.
    val from = remember(context, id) { context.looks.elsewhere(id, context.visibilityScope) }
    val move = context.elementTransition
    val visibility = context.visibilityScope.transition
    val fraction = visibility.animateFloat(
        transitionSpec = { move?.moveSpec() ?: tween(0) },
        label = "text morph",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val fade = visibility.animateFloat(
        transitionSpec = { move?.fadeSpec() ?: tween(0) },
        label = "text fade",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val entering = visibility.currentState != EnterExitState.Visible || visibility.targetState != EnterExitState.Visible
    val fromText = from?.text
    if (fromText == null || from.crossfade || fromText == look || fromText.text != look.text || move == null || !entering) return null
    return remember(fromText, look, fraction, fade) { TextMorph(fromText, look, { fraction.value }, { fade.value }) }
}

/**
 * Marks this element for an element transition: between two frames, it moves to where the
 * element with the same id is.
 *
 * When the presentation moves between two frames with a [SlideTransition.ElementTransition] transition,
 * an element with the same [id] in both frames animates from its old bounds to its new bounds.
 * Elements found in only one frame fade with the rest of the slide.
 *
 * Ids only need to be unique within a slide. Any value with a stable `equals` can be used.
 *
 * @param mode how the element is resized while it moves.
 * @param crossfade `false` draws only the new look of the element while it morphs, like Keynote
 *   does for matching objects. `true` cross-fades from the old look to the new one while it
 *   moves, which is needed when they differ (e.g. a box changing color, or a label changing
 *   text). It takes one of the two elements to ask, so an element can cross-fade with one it
 *   matches and not with another. The default is `true` for [ElementTransitionMode.Resize] and
 *   `false` for [ElementTransitionMode.Scale]: identical text rendered at two sizes never lines up
 *   exactly, so cross-fading it looks doubled. [SlideText] with an id cross-fades by itself when
 *   its text changes.
 * @param zIndex the drawing order among moving elements; higher is on top.
 */
fun Modifier.elementTransition(
    id: Any,
    mode: ElementTransitionMode = ElementTransitionMode.Scale,
    crossfade: Boolean = mode == ElementTransitionMode.Resize,
    zIndex: Float = 0f,
): Modifier = elementTransition(id, mode, crossfade, zIndex, text = null)

/**
 * [elementTransition] of text that tells how it looks, so that it can change gradually (see
 * [SlideText]), or of a code block that tells what it shows, so that its code can move into the
 * block that continues it (see [CodeMorph]).
 *
 * An element that [continues] others, when the frame it comes from has no element with its own
 * [id], moves from where the first of those it has is, as if it had that id too: a code block
 * continued only through its `from` moves as one continued under its own id does.
 */
internal fun Modifier.elementTransition(
    id: Any,
    mode: ElementTransitionMode,
    crossfade: Boolean,
    zIndex: Float,
    text: TextLook?,
    code: CodeLook? = null,
    continues: List<Any> = emptyList(),
): Modifier = composed {
    LocalElementIdRecorder.current?.ids?.add(id)
    val context = LocalElementTransition.current ?: return@composed Modifier
    // Chosen once, as the frame comes in: the frame before goes once it is in.
    val matched = remember(context, id, continues) { matchedId(context, id, continues) }
    val entry = remember(context, id) { context.looks.register(id, context.visibilityScope) }
    // Under the id it moves from too, for the element it moves from to see it continued.
    val alias = remember(context, id, matched) { if (matched == id) null else context.looks.register(matched, context.visibilityScope) }
    for (it in listOfNotNull(entry, alias)) {
        it.crossfade = crossfade
        it.text = text
        it.code = code
    }
    DisposableEffect(entry) { onDispose { context.looks.unregister(entry) } }
    DisposableEffect(alias) { onDispose { alias?.let(context.looks::unregister) } }
    with(context.sharedScope) {
        val state = rememberSharedContentState(ElementTransitionKey(matched), context.config)
        val transition = context.elementTransition
        // Cross-fades of matched elements happen while they move.
        val window = transition?.timeline?.move ?: TimeWindow(0, 0)
        val start = window.startMillis
        val duration = window.durationMillis
        val visibility = context.visibilityScope.transition
        val isIncoming = visibility.targetState == EnterExitState.Visible
        // Shapes fade through Compose; scaled elements here, as they draw, since whether they do
        // depends on the element they match (see crossfadesAsWhole).
        val fadesThroughCompose = crossfade && mode == ElementTransitionMode.Resize
        // 0 to 1 coming in, 1 to 0 going out, evenly over the move.
        val presence = visibility.animateFloat(
            transitionSpec = { transition?.fadeSpec() ?: tween(0) },
            label = "presence",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Modifier
            .sharedBounds(
                sharedContentState = state,
                animatedVisibilityScope = context.visibilityScope,
                // Without a match, these apply on top of the slide's own fade. The incoming copy is
                // drawn on top and turns opaque during the first half; only then does the outgoing
                // one fade, so nothing dips in opacity.
                enter = if (fadesThroughCompose) fadeIn(tween(duration / 2, delayMillis = start, easing = LinearEasing)) else EnterTransition.None,
                exit = if (fadesThroughCompose) {
                    fadeOut(tween(duration / 2, delayMillis = start + duration / 2, easing = LinearEasing))
                } else {
                    ExitTransition.None
                },
                boundsTransform = { _, _ -> transition?.moveSpec() ?: tween(0) },
                resizeMode = when (mode) {
                    // By width, not to fit: text sizes follow their widths, while line heights differ
                    // between styles. Fitting would also flip between the width and height ratios
                    // as they converge, making the element tremble.
                    ElementTransitionMode.Scale -> SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.Center)
                    ElementTransitionMode.Resize -> SharedTransitionScope.ResizeMode.RemeasureToBounds
                },
                zIndexInOverlay = zIndex * 2 + if (isIncoming) 1f else 0f,
            )
            .then(
                if (fadesThroughCompose) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        // Read at draw time: whether a match exists is only known after layout, and
                        // the element it matches only once both frames are there.
                        val other = if (state.isMatchFound) context.looks.elsewhere(matched, context.visibilityScope) else null
                        when {
                            other == null -> drawContent()
                            // A plain cross-fade: one comes in as the other goes, while they move.
                            crossfadesAsWhole(entry, other) -> drawContentWithAlpha(presence.value)
                            // Like Keynote, only the new look is drawn while it moves.
                            visibility.targetState != EnterExitState.Visible -> Unit
                            else -> drawContent()
                        }
                    }
                },
            )
    }
}

/** Draws the content [alpha] opaque, as one layer so that overlapping parts do not show through each other. */
private fun ContentDrawScope.drawContentWithAlpha(alpha: Float) {
    when {
        alpha <= 0f -> Unit
        alpha >= 1f -> drawContent()
        else -> {
            // Room around the element: text can reach a little past its bounds.
            val bounds = Rect(Offset.Zero, size).inflate(maxOf(size.width, size.height))
            drawContext.canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
            drawContent()
            drawContext.canvas.restore()
        }
    }
}

private data class ElementTransitionKey(val id: Any)

/**
 * The id an element with [id] that [continues] others moves with: its own, unless the frame before
 * has none with it, nor this frame another with the one it would take; then the first of
 * [continues] that the frame before has.
 */
private fun matchedId(context: ElementTransitionContext, id: Any, continues: List<Any>): Any {
    val scope = context.visibilityScope
    if (continues.isEmpty() || context.looks.elsewhere(id, scope) != null) return id
    return continues.firstOrNull { context.looks.elsewhere(it, scope) != null && !context.looks.has(it, scope) } ?: id
}

/** How matched elements move: in the timeline's move window, with the transition's easing. */
internal fun <T> ResolvedTransition.ElementTransition.moveSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    tween(timeline.move.durationMillis, delayMillis = timeline.move.startMillis, easing = easing)

/** How matched elements cross-fade: evenly over the timeline's move window. */
internal fun <T> ResolvedTransition.ElementTransition.fadeSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    tween(timeline.move.durationMillis, delayMillis = timeline.move.startMillis, easing = LinearEasing)
