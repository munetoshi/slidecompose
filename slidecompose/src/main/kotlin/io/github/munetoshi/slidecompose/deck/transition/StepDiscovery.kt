package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.SlideScope
import io.github.munetoshi.slidecompose.deck.SlideScopeImpl
import io.github.munetoshi.slidecompose.deck.components.Reveal
import io.github.munetoshi.slidecompose.deck.scopeFor
import io.github.munetoshi.slidecompose.deck.theme.ProvideSlideTheme

/**
 * Collects the build steps slide content uses, while a slide is composed off-screen to count
 * its steps. [Reveal] reports to it.
 */
internal class StepRecorder {
    var lastStep = 0
        private set

    fun record(step: Int) {
        if (step > lastStep) lastStep = step
    }
}

/** Non-null only while a slide is being composed to count its steps. */
internal val LocalStepRecorder = staticCompositionLocalOf<StepRecorder?> { null }

/** Collects the [elementTransition] ids in a frame while it is composed off-screen. */
internal class ElementIdRecorder {
    val ids = mutableSetOf<Any>()
}

internal val LocalElementIdRecorder = staticCompositionLocalOf<ElementIdRecorder?> { null }

/**
 * Counts the build steps of the slides at [slideIndices] by composing them off-screen, all in one
 * scene, and collecting the steps their [Reveal]s use. Content that only appears at a later step
 * is found by composing again at the last step found, until no new steps turn up.
 */
internal fun Presentation.discoverSteps(slideIndices: List<Int>): Map<Int, Int> {
    val scene = offscreenScene()
    try {
        return slideIndices.associateWith { stepsOf(scene, it) }
    } finally {
        scene.close()
    }
}

/**
 * The build steps of the slide at [slideIndex]: composed with one step, then again at the last
 * step found, as long as new ones turn up.
 */
private fun Presentation.stepsOf(scene: ImageComposeScene, slideIndex: Int): Int =
    generateSequence(1) { moreSteps(scene, slideIndex, it) }.take(MAX_DISCOVERY_PASSES + 1).last()

/**
 * The steps the slide at [slideIndex] uses when it is composed at the last of [steps], or null
 * when that turns up no more.
 */
private fun Presentation.moreSteps(scene: ImageComposeScene, slideIndex: Int, steps: Int): Int? {
    val recorder = StepRecorder()
    composeOffscreen(scene, SlideScopeImpl(step = steps - 1, steps = steps, slideIndex = slideIndex, slideCount = slides.size), recorder, null)
    val found = recorder.lastStep + 1
    return if (found > steps) found else null
}

/** The [elementTransition] ids in [frame], found by composing it off-screen. */
internal fun Presentation.discoverElementIds(frame: Frame): Set<Any> {
    val recorder = ElementIdRecorder()
    val scene = offscreenScene()
    try {
        composeOffscreen(scene, scopeFor(frame), StepRecorder(), recorder)
    } finally {
        scene.close()
    }
    return recorder.ids
}

private fun Presentation.offscreenScene() = ImageComposeScene(
    width = DISCOVERY_WIDTH,
    height = (DISCOVERY_WIDTH / size.aspectRatio).toInt().coerceAtLeast(1),
    density = Density(DISCOVERY_WIDTH.toFloat() / size.width),
)

/** Composes a slide in [scene] from scratch, collecting what it uses. */
private fun Presentation.composeOffscreen(scene: ImageComposeScene, scope: SlideScope, steps: StepRecorder, ids: ElementIdRecorder?) {
    try {
        scene.setContent {
            CompositionLocalProvider(LocalStepRecorder provides steps, LocalElementIdRecorder provides ids) {
                ProvideSlideTheme(theme) {
                    Box(Modifier.fillMaxSize()) { slides[scope.slideIndex].content(scope) }
                }
            }
        }
        scene.render(0L).close()
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        // Rendering the slide for real reports the error; go with what was found before it.
    }
}

private const val MAX_DISCOVERY_PASSES = 8
private const val DISCOVERY_WIDTH = 64
