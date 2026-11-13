package io.github.munetoshi.slidecompose.deck

import kotlin.time.Duration

/**
 * The planned timing of a presentation, for the presenter's progress bars.
 *
 * With a planned total (the presentation's `duration`, or else the sum of the slides' durations
 * when every slide has one), slides with a `duration` get that time, and the others share the
 * rest in proportion to their `weight`. Without one, slides only take up space on the progress
 * bar in proportion to their `weight`.
 */
internal class Schedule(presentation: Presentation) {
    /** The planned length of the talk in milliseconds, or `null` when there is no time plan. */
    val totalMillis: Double?

    /** Slide `i` takes up `bounds[i] until bounds[i + 1]` of the bar, in 0..1. */
    private val bounds: DoubleArray

    init {
        val slides = presentation.slides
        val fixed = slides.mapNotNull { it.duration }
        val total = presentation.duration
            ?: if (slides.isNotEmpty() && fixed.size == slides.size) fixed.fold(Duration.ZERO, Duration::plus) else null
        val lengths = if (total == null) {
            slides.map { it.weight.toDouble() }
        } else {
            val rest = (total - fixed.fold(Duration.ZERO, Duration::plus)).inWholeMilliseconds.coerceAtLeast(0)
            val weights = slides.asSequence().filter { it.duration == null }.sumOf { it.weight.toDouble() }
            slides.map { slide ->
                slide.duration?.inWholeMilliseconds?.toDouble()
                    ?: if (weights > 0) rest * slide.weight / weights else 0.0
            }
        }
        // Fixed durations can add up to more than the total; the bar then covers the longer of the two.
        val scale = maxOf(lengths.sum(), total?.inWholeMilliseconds?.toDouble() ?: 0.0).takeIf { it > 0 } ?: 1.0
        totalMillis = if (total != null) scale else null
        bounds = DoubleArray(slides.size + 1)
        lengths.forEachIndexed { i, length -> bounds[i + 1] = bounds[i] + length / scale }
    }

    /** Where slide [index] starts on the bar, in 0..1. */
    fun start(index: Int): Double = bounds[index.coerceIn(0, bounds.size - 1)]

    /** Where slide [index] ends on the bar, in 0..1. */
    fun end(index: Int): Double = bounds[(index + 1).coerceIn(0, bounds.size - 1)]

    /** The planned time of slide [index] in milliseconds, or `null` without a time plan. */
    fun millisOf(index: Int): Double? = totalMillis?.let { (end(index) - start(index)) * it }
}
