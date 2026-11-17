package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.Slide
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.deck.theme.SlideStyle
import io.github.munetoshi.slidecompose.deck.theme.resolve
import io.github.munetoshi.slidecompose.deck.transition.Motion
import io.github.munetoshi.slidecompose.deck.transition.ResolvedTransition
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition

/** The navigation state of a presentation, shared by the audience and presenter windows. */
@Stable
class PresentationController(presentation: Presentation) {
    var presentation: Presentation by mutableStateOf(presentation)
        private set

    /** Incremented whenever [replacePresentation] swaps the content; used to reset slide state. */
    var revision: Int by mutableIntStateOf(0)
        private set

    var frame: Frame by mutableStateOf(Frame(0, 0))
        private set

    internal var motion: Motion by mutableStateOf(Motion.Initial)
        private set

    /** Whether the audience window is blacked out. */
    var isBlackedOut: Boolean by mutableStateOf(false)

    val timer = PresentationTimer()

    /**
     * Whether moving on starts the [timer] while it has not run yet. Once the presenter resets it,
     * as before the talk after a rehearsal, it starts only when started.
     */
    private var movesStartTimer = true

    /** The [timer]'s reading when the current slide was entered. */
    var slideStartMillis: Long by mutableLongStateOf(0L)
        private set

    /**
     * Where the mouse is over the presenter's copy of the current slide, as fractions of the
     * slide's width and height, or `null` when it is not over it. The audience window draws a
     * pointer there.
     */
    var pointer: Offset? by mutableStateOf(null)

    /** The shape of the cursor at the [pointer] in the presenter window, which the shared one takes. */
    var pointerShape: PointerShape by mutableStateOf(PointerShape.Arrow)

    /** Whether the audience window shows the [pointer]. */
    var isPointerShared: Boolean by mutableStateOf(true)

    /** Whether the shared [pointer] fades out once it has stayed still for [PointerIdleTime]. */
    var hidesStillPointer: Boolean by mutableStateOf(true)

    /** The pointer's color, or `null` for the one in the current slide's [SlideStyle]. */
    var pointerColor: PointerColor? by mutableStateOf(null)

    /** The color the pointer is drawn in on the current slide. */
    val effectivePointerColor: PointerColor
        get() = pointerColor ?: (currentSlide?.style ?: presentation.style).pointer

    val slideCount: Int get() = presentation.slides.size

    val currentSlide: Slide? get() = presentation.slides.getOrNull(frame.slide)

    /** The frame [next] would go to, or `null` at the end. */
    val nextFrame: Frame?
        get() {
            val slide = currentSlide ?: return null
            return when {
                frame.step < slide.steps - 1 -> frame.copy(step = frame.step + 1)
                frame.slide < slideCount - 1 -> Frame(frame.slide + 1, 0)
                else -> null
            }
        }

    val previousFrame: Frame?
        get() = when {
            frame.step > 0 -> frame.copy(step = frame.step - 1)
            frame.slide > 0 -> Frame(frame.slide - 1, presentation.slides[frame.slide - 1].steps - 1)
            else -> null
        }

    fun next() {
        nextFrame?.let { goTo(it) }
    }

    fun previous() {
        previousFrame?.let { goTo(it) }
    }

    fun first() {
        if (slideCount > 0) goTo(Frame(0, 0))
    }

    fun last() {
        if (slideCount > 0) goTo(Frame(slideCount - 1, presentation.slides.last().steps - 1))
    }

    /** Jumps to the first step of the slide at [index]. */
    fun goToSlide(index: Int) {
        if (slideCount > 0) goTo(Frame(index.coerceIn(0, slideCount - 1), 0))
    }

    fun goTo(target: Frame, animate: Boolean = true) {
        val clamped = clamp(target, presentation) ?: return
        if (clamped == frame) return
        val from = frame
        motion = if (animate) motionBetween(from, clamped) else Motion.Initial
        frame = clamped
        if (movesStartTimer && !timer.isRunning && timer.elapsedMillis() == 0L) timer.start()
        if (clamped.slide != from.slide) slideStartMillis = timer.elapsedMillis()
    }

    /** Stops the [timer] at zero: from now on it starts when started, not when the slides move. */
    fun resetTimer() {
        timer.reset()
        movesStartTimer = false
        slideStartMillis = 0L
    }

    /** Swaps in a new version of the presentation, keeping the position as far as possible. */
    fun replacePresentation(newPresentation: Presentation) {
        presentation = newPresentation
        motion = Motion.Initial
        frame = clamp(frame, newPresentation) ?: Frame(0, 0)
        revision++
    }

    private fun motionBetween(from: Frame, to: Frame): Motion {
        val slides = presentation.slides
        val motion = if (from.slide == to.slide) {
            val slide = slides[to.slide]
            Motion((slide.stepTransition ?: SlideTransition.None).resolve(slide.style), forward = to > from)
        } else {
            val forward = to.slide > from.slide
            // A transition belongs to the slide it leads into, and is reversed when going back.
            val owner = if (forward) slides[to.slide] else slides[from.slide]
            Motion((owner.transition ?: presentation.defaultTransition).resolve(owner.style), forward)
        }
        // With nothing to move, an element transition only fades.
        val transition = motion.transition
        return if (transition is ResolvedTransition.ElementTransition && !presentation.sharesElements(from, to)) {
            motion.copy(transition = transition.unmatched)
        } else {
            motion
        }
    }

    private fun clamp(target: Frame, presentation: Presentation): Frame? {
        if (presentation.slides.isEmpty()) return null
        val slide = target.slide.coerceIn(0, presentation.slides.lastIndex)
        val step = target.step.coerceIn(0, presentation.slides[slide].steps - 1)
        return Frame(slide, step)
    }
}

/** A stopwatch for the presenter window. */
@Stable
class PresentationTimer {
    private var startedAt: Long by mutableLongStateOf(0L)
    private var accumulated: Long by mutableLongStateOf(0L)

    var isRunning: Boolean by mutableStateOf(false)
        private set

    fun start() {
        if (isRunning) return
        startedAt = System.currentTimeMillis()
        isRunning = true
    }

    fun pause() {
        if (!isRunning) return
        accumulated += System.currentTimeMillis() - startedAt
        isRunning = false
    }

    fun toggle() = if (isRunning) pause() else start()

    /** Back to zero, stopped. */
    fun reset() {
        accumulated = 0L
        isRunning = false
    }

    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        accumulated + if (isRunning) now - startedAt else 0L
}
