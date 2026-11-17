package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.FadeStyle
import io.github.munetoshi.slidecompose.deck.theme.SlideStyle

/**
 * How one frame is replaced by the next. Parameters left `null` come from the [SlideStyle] of
 * the slide the transition belongs to.
 */
@Immutable
sealed interface SlideTransition {
    /** Cuts to the next frame immediately. */
    data object None : SlideTransition

    /** Cross-fades the whole slide. */
    data class Fade(val durationMillis: Int? = null) : SlideTransition

    /** Pushes the old slide out and the new one in. */
    data class Push(
        val direction: PushDirection = PushDirection.Left,
        val durationMillis: Int? = null,
        val easing: Easing? = null,
    ) : SlideTransition

    /**
     * Element transition: elements marked with the same [elementTransition] id morph from their
     * old position, size and look into the new one. Everything else fades out and in. [timing]
     * says how these overlap: all at once, or one after another. Between frames without matching
     * elements, it is a plain cross-fade of the slide's [FadeStyle] length.
     */
    data class ElementTransition(
        val timing: MoveTiming? = null,
        val easing: Easing? = null,
    ) : SlideTransition
}

enum class PushDirection { Left, Right, Up, Down }

/** A [SlideTransition] with every parameter filled in from the style. */
internal sealed interface ResolvedTransition {
    data object None : ResolvedTransition

    data class Fade(val durationMillis: Int) : ResolvedTransition

    data class Push(val direction: PushDirection, val durationMillis: Int, val easing: Easing) : ResolvedTransition

    data class ElementTransition(
        val timeline: MoveTimeline,
        val easing: Easing,
        /** What happens instead between frames without matching elements. */
        val unmatched: ResolvedTransition,
    ) : ResolvedTransition
}

internal fun SlideTransition.resolve(style: SlideStyle): ResolvedTransition = when (this) {
    SlideTransition.None -> ResolvedTransition.None
    is SlideTransition.Fade -> ResolvedTransition.Fade(durationMillis ?: style.fade.durationMillis)
    is SlideTransition.Push -> ResolvedTransition.Push(
        direction,
        durationMillis ?: style.push.durationMillis,
        easing ?: style.push.easing,
    )
    is SlideTransition.ElementTransition -> {
        val timing = timing ?: style.elementTransition.timing
        val easing = easing ?: style.elementTransition.easing
        ResolvedTransition.ElementTransition(
            timing.timeline(),
            easing,
            unmatched = when (timing) {
                // With nothing to move: a quick cross-fade,
                is MoveTiming.Together -> ResolvedTransition.Fade(style.fade.durationMillis)
                // or, one after another, a fade-out followed by a fade-in.
                is MoveTiming.Staged -> {
                    val fade = timing.timeline().fadeOut.durationMillis
                    ResolvedTransition.ElementTransition(
                        MoveTimeline(TimeWindow(0, fade), TimeWindow(0, 0), TimeWindow(fade, fade * 2)),
                        easing,
                        ResolvedTransition.None,
                    )
                }
            },
        )
    }
}

/**
 * The transition in effect for the frame change currently on screen. An element transition
 * between frames without matching elements is a plain cross-fade instead.
 */
@Immutable
internal data class Motion(val transition: ResolvedTransition, val forward: Boolean) {
    companion object {
        val Initial = Motion(ResolvedTransition.None, forward = true)
    }
}

internal fun AnimatedContentTransitionScope<Frame>.contentTransform(motion: Motion): ContentTransform {
    val noSize = SizeTransform(clip = false) { _, _ -> snap() }
    return when (val t = motion.transition) {
        ResolvedTransition.None -> ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = noSize)

        is ResolvedTransition.Fade -> crossFade(t.durationMillis, noSize)

        // Matching elements are animated by their shared bounds, which keep the transition
        // running for as long as they move.
        is ResolvedTransition.ElementTransition -> {
            val out = t.timeline.fadeOut
            val `in` = t.timeline.fadeIn
            ContentTransform(
                targetContentEnter = fadeIn(tween(`in`.durationMillis, delayMillis = `in`.startMillis, easing = LinearEasing)),
                initialContentExit = fadeOut(tween(out.durationMillis, delayMillis = out.startMillis, easing = LinearEasing)),
                sizeTransform = noSize,
            )
        }

        is ResolvedTransition.Push -> {
            val sign = if (motion.forward) 1 else -1
            val enterSpec = tween<androidx.compose.ui.unit.IntOffset>(t.durationMillis, easing = t.easing)
            val exitSpec = tween<androidx.compose.ui.unit.IntOffset>(t.durationMillis, easing = t.easing)
            when (t.direction) {
                PushDirection.Left -> ContentTransform(
                    slideInHorizontally(enterSpec) { sign * it },
                    slideOutHorizontally(exitSpec) { -sign * it },
                    sizeTransform = noSize,
                )
                PushDirection.Right -> ContentTransform(
                    slideInHorizontally(enterSpec) { -sign * it },
                    slideOutHorizontally(exitSpec) { sign * it },
                    sizeTransform = noSize,
                )
                PushDirection.Up -> ContentTransform(
                    slideInVertically(enterSpec) { sign * it },
                    slideOutVertically(exitSpec) { -sign * it },
                    sizeTransform = noSize,
                )
                PushDirection.Down -> ContentTransform(
                    slideInVertically(enterSpec) { -sign * it },
                    slideOutVertically(exitSpec) { sign * it },
                    sizeTransform = noSize,
                )
            }
        }
    }
}

private fun crossFade(durationMillis: Int, sizeTransform: SizeTransform) = ContentTransform(
    targetContentEnter = fadeIn(tween(durationMillis, easing = LinearEasing)),
    initialContentExit = fadeOut(tween(durationMillis, easing = LinearEasing)),
    sizeTransform = sizeTransform,
)

/**
 * How the parts of the old and the new slide look, a [fraction] of the way through a change of
 * slides by [Motion]: as the slides themselves do. Shifts are in slide widths and heights.
 */
internal data class ChangeLook(val oldAlpha: Float, val newAlpha: Float, val oldShift: Offset, val newShift: Offset)

internal fun Motion.lookAt(fraction: Float): ChangeLook =
    when (val t = transition) {
        ResolvedTransition.None -> ChangeLook(0f, 1f, Offset.Zero, Offset.Zero)
        is ResolvedTransition.Fade -> ChangeLook(1f - fraction, fraction, Offset.Zero, Offset.Zero)
        is ResolvedTransition.ElementTransition -> {
            val millis = fraction * t.timeline.totalMillis
            ChangeLook(1f - t.timeline.fadeOut.at(millis), t.timeline.fadeIn.at(millis), Offset.Zero, Offset.Zero)
        }
        is ResolvedTransition.Push -> {
            // Where the new slide comes from, as in contentTransform.
            val from = when (t.direction) {
                PushDirection.Left -> Offset(1f, 0f)
                PushDirection.Right -> Offset(-1f, 0f)
                PushDirection.Up -> Offset(0f, 1f)
                PushDirection.Down -> Offset(0f, -1f)
            } * (if (forward) 1f else -1f)
            val eased = t.easing.transform(fraction)
            ChangeLook(1f, 1f, oldShift = -from * eased, newShift = from * (1f - eased))
        }
    }

/** How long a change of slides by this takes. */
internal val ResolvedTransition.totalMillis: Int
    get() = when (this) {
        ResolvedTransition.None -> 0
        is ResolvedTransition.Fade -> durationMillis
        is ResolvedTransition.Push -> durationMillis
        is ResolvedTransition.ElementTransition -> timeline.totalMillis
    }

/** How far through this window [millis] is, from 0 to 1. */
private fun TimeWindow.at(millis: Float): Float =
    if (durationMillis <= 0) (if (millis >= startMillis) 1f else 0f) else ((millis - startMillis) / durationMillis).coerceIn(0f, 1f)
