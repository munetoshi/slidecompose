package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.deck.components.LocalStillImage
import io.github.munetoshi.slidecompose.deck.transition.ElementIdRecorder
import io.github.munetoshi.slidecompose.deck.transition.LocalElementIdRecorder
import java.io.File
import kotlin.math.roundToInt
import org.jetbrains.skia.Image

/**
 * [frame] drawn off-screen as [StillFrame] shows it, [scale] pixels per slide unit, once [settle]
 * has let its animations end.
 */
internal fun Presentation.renderStillFrame(frame: Frame, scale: Float, assetDirectory: File?): Image {
    val width = (size.width * scale).roundToInt()
    val height = (size.height * scale).roundToInt()
    // The element transition ids come for free, for knowing later whether frames share elements.
    val ids = ElementIdRecorder()
    val scene = ImageComposeScene(width, height, Density(1f)) { StillFrame(this, frame, assetDirectory, ids) }
    try {
        return scene.settle().also { rememberElementIds(frame, ids.ids) }
    } finally {
        scene.close()
    }
}

/** [frame] as a still image shows it, for [renderStillFrame] and vector PDFs. */
@Composable
internal fun StillFrame(presentation: Presentation, frame: Frame, assetDirectory: File?, ids: ElementIdRecorder) {
    CompositionLocalProvider(
        LocalAssetDirectory provides (assetDirectory ?: LocalAssetDirectory.current),
        LocalElementIdRecorder provides ids,
        LocalStillImage provides true,
    ) {
        StaticSlide(presentation, frame, Modifier.fillMaxSize())
    }
}

/**
 * Renders the scene's first frames, giving animations, effects and async work time to settle, and
 * returns the last. A state change made by an effect of the first frame recomposes at the second,
 * which launches its animations; they take their start time from the third, and end by the fourth.
 */
internal fun ImageComposeScene.settle(): Image {
    render(0L).close()
    render(SETTLE_NANOS).close()
    render(SETTLE_NANOS * 2).close()
    return render(SETTLE_NANOS * 3)
}

/** The time between the frames [settle] renders: long enough for an animation started at one to end by the next. */
internal const val SETTLE_NANOS = 5_000_000_000L
