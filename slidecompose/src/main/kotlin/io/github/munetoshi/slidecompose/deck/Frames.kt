package io.github.munetoshi.slidecompose.deck

import androidx.compose.runtime.Immutable

/** A position in a presentation: a slide and one of its build steps. */
@Immutable
data class Frame(val slide: Int, val step: Int) : Comparable<Frame> {
    override fun compareTo(other: Frame): Int =
        compareValuesBy(this, other, Frame::slide, Frame::step)
}

/** Every frame of the presentation in order: each step of each slide. */
fun Presentation.allFrames(): List<Frame> =
    slides.flatMapIndexed { index, slide -> List(slide.steps) { Frame(index, it) } }

/** The last step of every slide, i.e. each slide fully built. */
fun Presentation.finalFrames(): List<Frame> =
    slides.mapIndexed { index, slide -> Frame(index, slide.steps - 1) }

/** The step of every slide that stands for it: see [Slide.representativeStep]. */
fun Presentation.representativeFrames(): List<Frame> =
    slides.mapIndexed { index, slide -> Frame(index, slide.representativeStep) }

/**
 * The frames an export shows, in order: with [includeBuildSteps] every step of every slide, else
 * the steps each slide's [SlideExport] gives. Slides left out ([SlideExport.None]) are left out
 * of both.
 */
fun Presentation.exportedFrames(includeBuildSteps: Boolean): List<Frame> =
    slides.asSequence()
        .withIndex()
        .filter { (_, slide) -> slide.export != SlideExport.None }
        .flatMap { (index, slide) -> (if (includeBuildSteps) 0 until slide.steps else slide.exportedSteps).map { Frame(index, it) } }
        .toList()
