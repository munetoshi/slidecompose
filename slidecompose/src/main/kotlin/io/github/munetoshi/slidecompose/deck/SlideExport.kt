package io.github.munetoshi.slidecompose.deck

import androidx.compose.runtime.Immutable

/**
 * Which build steps of a slide a PDF (or PNG) export shows, and which one stands for the slide,
 * also in the presenter's slide list. Steps count from 0, as [SlideScope.step] does; steps past
 * the last count as the last.
 *
 * ```
 * slide(steps = 4, export = SlideExport.step(2)) { ... }        // one page, of step 2
 * slide(steps = 4, export = SlideExport.steps(1, 3)) { ... }    // two pages
 * slide(export = SlideExport.None) { ... }                      // not in the PDF
 * ```
 *
 * An export of every build step (`includeBuildSteps`, `--builds`) shows every step of every slide
 * but the ones left out.
 */
@Immutable
class SlideExport private constructor(
    /** The steps exported as pages, or `null` for the representative one. */
    internal val pages: List<Int>?,
    /** The step that stands for the slide, or `null` for the last one. */
    internal val representative: Int?,
) {
    companion object {
        /** The default: the slide fully built, as one page. */
        val Final: SlideExport = SlideExport(null, null)

        /** Left out of exports; still presented, and in the slide list. */
        val None: SlideExport = SlideExport(emptyList(), null)

        /** Step [step] stands for the slide: its one page, and its picture in the slide list. */
        fun step(step: Int): SlideExport {
            require(step >= 0) { "step must not be negative, but was $step" }
            return SlideExport(null, step)
        }

        /** Each of [steps] as a page of its own; the latest of them stands for the slide. */
        fun steps(vararg steps: Int): SlideExport {
            require(steps.isNotEmpty()) { "give at least one step, or use SlideExport.None" }
            require(steps.all { it >= 0 }) { "steps must not be negative, but were ${steps.toList()}" }
            return SlideExport(steps.toList(), steps.max())
        }
    }
}
