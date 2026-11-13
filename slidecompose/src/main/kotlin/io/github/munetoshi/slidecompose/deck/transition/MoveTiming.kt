package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.runtime.Immutable

/**
 * How the three parts of a move are timed: what goes away fades out, what stays moves to its new
 * place, and what is new fades in. Used by element transitions.
 */
@Immutable
sealed interface MoveTiming {
    /** Moving and cross-fading all at once, for [durationMillis]. */
    data class Together(val durationMillis: Int = 900) : MoveTiming

    /**
     * One after another, within [moveMillis]: what stays moves all along, while what goes away
     * fades out at the start, and what is new fades in at the end. In between, for [moveOnlyMillis],
     * things only move. The fade-out and the fade-in take the rest of the time, half each.
     */
    data class Staged(
        val moveMillis: Int = 800,
        val moveOnlyMillis: Int = 200,
    ) : MoveTiming
}

/** A part of a move, in milliseconds from its start. */
internal data class TimeWindow(val startMillis: Int, val endMillis: Int) {
    val durationMillis: Int get() = endMillis - startMillis
}

/** When each part of a move runs. */
@Immutable
internal data class MoveTimeline(val fadeOut: TimeWindow, val move: TimeWindow, val fadeIn: TimeWindow) {
    val totalMillis: Int get() = maxOf(fadeOut.endMillis, move.endMillis, fadeIn.endMillis)
}

internal fun MoveTiming.timeline(): MoveTimeline = when (this) {
    is MoveTiming.Together -> {
        val all = TimeWindow(0, durationMillis.coerceAtLeast(0))
        MoveTimeline(all, all, all)
    }
    is MoveTiming.Staged -> {
        val end = moveMillis.coerceAtLeast(0)
        val fade = (end - moveOnlyMillis.coerceIn(0, end)) / 2
        MoveTimeline(TimeWindow(0, fade), TimeWindow(0, end), TimeWindow(end - fade, end))
    }
}
