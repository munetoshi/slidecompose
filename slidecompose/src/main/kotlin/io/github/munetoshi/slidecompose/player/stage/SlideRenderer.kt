package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.player.presenter.PresenterView
import io.github.munetoshi.slidecompose.player.window.PresentationWindows
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * How [PresentationWindows] and [PresenterView] draw the slides. [InProcess], the default, composes
 * them here from the controller's presentation. Another renderer can show slides drawn somewhere
 * else, as the viewer's safe mode shows frames that a sandboxed process renders: the windows then
 * still handle navigation, notes and timing through the [PresentationController], whose
 * presentation only has to say how many slides and steps there are, their notes and their timing.
 */
@Stable
interface SlideRenderer {
    /**
     * The audience's view: the current frame with its transitions, and the pointer shared from the
     * presenter window (see [SharedPointer]). A click that nothing on the slide takes calls [onClick].
     */
    @Composable
    fun Stage(controller: PresentationController, onClick: () -> Unit, modifier: Modifier)

    /** The presenter's copy of the current slide. */
    @Composable
    fun Current(controller: PresentationController, modifier: Modifier)

    /** [frame] without transitions, for the presenter's next slide, letterboxed in [letterboxColor]. */
    @Composable
    fun Still(controller: PresentationController, frame: Frame, letterboxColor: Color, modifier: Modifier)

    /**
     * A picture of [frame] [width] pixels wide, for the presenter's slide list, or null when there
     * is none. It may take a while: the caller keeps the pictures. [assetDirectory] is where the
     * slides' images are, when they are drawn here.
     */
    suspend fun thumbnail(controller: PresentationController, frame: Frame, width: Int, assetDirectory: File?): ImageBitmap? = null

    /**
     * Whether the slides' code runs in this process. Only then can the windows export PDFs and play
     * every transition once off-screen before the talk.
     */
    val isInProcess: Boolean get() = false

    companion object {
        /** Composes the slides in this process. */
        val InProcess: SlideRenderer = InProcessRenderer
    }
}

private object InProcessRenderer : SlideRenderer {
    @Composable
    override fun Stage(controller: PresentationController, onClick: () -> Unit, modifier: Modifier) {
        val click by rememberUpdatedState(onClick)
        // Taps that the slide's own elements consume never complete here.
        SlideStage(controller, modifier.pointerInput(controller) { detectTapGestures(onTap = { click() }) }, showsPointer = true)
    }

    @Composable
    override fun Current(controller: PresentationController, modifier: Modifier) {
        SlideStage(controller, modifier)
    }

    @Composable
    override fun Still(controller: PresentationController, frame: Frame, letterboxColor: Color, modifier: Modifier) {
        StaticSlide(controller.presentation, frame, modifier, letterboxColor)
    }

    /** Drawn off the UI thread, as an export does: a still frame needs no window. */
    override suspend fun thumbnail(controller: PresentationController, frame: Frame, width: Int, assetDirectory: File?): ImageBitmap? {
        val presentation = controller.presentation
        return withContext(Dispatchers.Default) { presentation.thumbnailOf(frame, width, assetDirectory) }
    }

    override val isInProcess: Boolean get() = true
}

/** [frame] drawn [width] pixels wide, or null when it does not draw. */
private fun Presentation.thumbnailOf(frame: Frame, width: Int, assetDirectory: File?): ImageBitmap? =
    runCatching { renderStillFrame(frame, scale = width.toFloat() / size.width, assetDirectory) }.getOrNull()?.use(Image::toComposeImageBitmap)
