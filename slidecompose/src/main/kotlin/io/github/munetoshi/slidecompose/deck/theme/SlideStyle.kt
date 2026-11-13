package io.github.munetoshi.slidecompose.deck.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.PresentationBuilder
import io.github.munetoshi.slidecompose.deck.SlideDsl
import io.github.munetoshi.slidecompose.deck.code.CodeBlock
import io.github.munetoshi.slidecompose.deck.components.Reveal
import io.github.munetoshi.slidecompose.deck.transition.MoveTiming
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition
import io.github.munetoshi.slidecompose.deck.transition.elementTransition

/**
 * Default settings for animations and the shared pointer, like a stylesheet. They cascade: set them for the whole deck with
 * [PresentationBuilder.style], override some for one slide with `slide(style = { ... })`, and
 * override one element through its own parameters (e.g. `Reveal(atStep = 1, durationMillis = 800)`).
 *
 * ```
 * presentation {
 *     style {
 *         reveal(durationMillis = 300)
 *         elementTransition(durationMillis = 700)
 *     }
 *     slide(style = { fade(durationMillis = 1000) }) { ... }
 * }
 * ```
 */
@Immutable
data class SlideStyle(
    val reveal: RevealStyle = RevealStyle(),
    val fade: FadeStyle = FadeStyle(),
    val push: PushStyle = PushStyle(),
    val elementTransition: ElementTransitionStyle = ElementTransitionStyle(),
    val code: CodeStyle = CodeStyle(),
    val pointer: PointerColor = PointerColor.White,
)

/** How [Reveal] shows its content. A non-zero [offset] also slides it up into place. */
@Immutable
data class RevealStyle(
    val durationMillis: Int = 400,
    val offset: Dp = 0.dp,
    val easing: Easing = FastOutSlowInEasing,
)

/** [SlideTransition.Fade]. */
@Immutable
data class FadeStyle(val durationMillis: Int = 400)

/** [SlideTransition.Push]. */
@Immutable
data class PushStyle(
    val durationMillis: Int = 600,
    val easing: Easing = FastOutSlowInEasing,
)

/**
 * [SlideTransition.ElementTransition]: matching elements move with [easing] while everything else
 * fades out and in, as [timing] says. Between frames without matching elements, it is a plain
 * cross-fade of [FadeStyle] length.
 */
@Immutable
data class ElementTransitionStyle(
    val timing: MoveTiming = MoveTiming.Together(900),
    val easing: Easing = ElementTransitionEasing,
)

/** [CodeBlock] between versions of its code: [timing] says how long the code takes to move. */
@Immutable
data class CodeStyle(val timing: MoveTiming = MoveTiming.Together(1000))

/** The colors of the pointer shared from the presenter window. */
enum class PointerColor(internal val fill: Color, internal val outline: Color) {
    /** A white arrow with a black outline. */
    White(Color.White, Color.Black),

    /** A black arrow with a white outline. */
    Black(Color.Black, Color.White),
}

/** An ease-in-out curve close to Keynote's. */
val ElementTransitionEasing: Easing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

/** Sets style properties on top of the ones inherited from the deck. */
@SlideDsl
class SlideStyleBuilder internal constructor(base: SlideStyle) {
    internal var style: SlideStyle = base
        private set

    fun reveal(
        durationMillis: Int = style.reveal.durationMillis,
        offset: Dp = style.reveal.offset,
        easing: Easing = style.reveal.easing,
    ) {
        style = style.copy(reveal = RevealStyle(durationMillis, offset, easing))
    }

    fun fade(durationMillis: Int = style.fade.durationMillis) {
        style = style.copy(fade = FadeStyle(durationMillis))
    }

    fun push(
        durationMillis: Int = style.push.durationMillis,
        easing: Easing = style.push.easing,
    ) {
        style = style.copy(push = PushStyle(durationMillis, easing))
    }

    fun elementTransition(
        timing: MoveTiming = style.elementTransition.timing,
        easing: Easing = style.elementTransition.easing,
    ) {
        style = style.copy(elementTransition = ElementTransitionStyle(timing, easing))
    }

    fun code(timing: MoveTiming = style.code.timing) {
        style = style.copy(code = CodeStyle(timing))
    }

    fun pointer(color: PointerColor) {
        style = style.copy(pointer = color)
    }
}

internal fun SlideStyle.with(block: (SlideStyleBuilder.() -> Unit)?): SlideStyle =
    if (block == null) this else SlideStyleBuilder(this).apply(block).style

/** The style of the slide being rendered. */
val LocalSlideStyle = staticCompositionLocalOf { SlideStyle() }
