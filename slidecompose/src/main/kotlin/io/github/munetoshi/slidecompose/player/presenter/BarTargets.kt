package io.github.munetoshi.slidecompose.player.presenter

import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.Schedule
import io.github.munetoshi.slidecompose.deck.Slide

/**
 * What the preview over [SlideProgressBar] shows for a [target] there: a build of the [current]
 * slide, or another slide as it stands for itself (see [Slide.representativeStep]).
 */
internal fun previewFrame(target: Frame, current: Int, presentation: Presentation): Frame =
    if (target.slide == current) target else Frame(target.slide, presentation.slides[target.slide].representativeStep)

/** Where a click on [SlideProgressBar] goes, and the part of the bar, as fractions of its width, that goes there. */
internal class BarTarget(val frame: Frame, val start: Double, val end: Double)

/**
 * The target at [fraction] of the width of [SlideProgressBar]: the first step of a slide, or, in
 * the current slide's segment, the build there.
 */
internal fun targetAt(schedule: Schedule, slideCount: Int, current: Int, steps: Int, fraction: Double): BarTarget? {
    if (slideCount == 0) return null
    val index = (0 until slideCount).firstOrNull { fraction < schedule.end(it) } ?: (slideCount - 1)
    val start = schedule.start(index)
    val end = schedule.end(index)
    if (index != current) return BarTarget(Frame(index, 0), start, end)
    val build = ((fraction - start) / (end - start) * steps).toInt().coerceIn(0, steps - 1)
    val buildWidth = (end - start) / steps
    return BarTarget(Frame(index, build), start + build * buildWidth, start + (build + 1) * buildWidth)
}
