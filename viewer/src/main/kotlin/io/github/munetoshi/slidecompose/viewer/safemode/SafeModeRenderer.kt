package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.PresentationBuilder
import io.github.munetoshi.slidecompose.deck.SlideExport
import io.github.munetoshi.slidecompose.deck.SlideSize
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SharedPointer
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.SlideViewport
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Shows a deck that a sandboxed child renders in the ordinary presentation windows. The windows run
 * on [controller], whose presentation stands in for the deck: it has the deck's slides, steps,
 * notes and timing but no content, so none of the deck's code runs here.
 *
 * The renderer shows the frames the child sends, and sends it the size of the slides, the mouse
 * over them and where the talk is. Clicks that nothing on the slides took come back from the child
 * and are handled like clicks in the windows, by [SlideRenderer.Stage]'s caller.
 */
@Stable
internal class SafeModeRenderer(private val session: SafeModeSession, deck: DeckInfo) : SlideRenderer {
    private val notes = deck.slides.map { it.notes }.toMutableStateList()

    val controller = PresentationController(standIn(deck) { notes.getOrNull(it).orEmpty() })

    private var frame: ImageBitmap? by mutableStateOf(null)
    private var still: Pair<Frame, ImageBitmap>? by mutableStateOf(null)
    /** What [frame] and [still] were made from, to know when the session has newer ones. */
    private var shownFrame: RenderedImage? = null
    private var shownStill: RenderedStill? = null

    @Composable
    override fun Stage(controller: PresentationController, onClick: () -> Unit, modifier: Modifier) {
        val density = LocalDensity.current.density
        val click by rememberUpdatedState(onClick)
        LaunchedEffect(controller) { snapshotFlow { controller.frame }.collect { session.goTo(it) } }
        LaunchedEffect(Unit) {
            while (isActive) {
                session.awaitWork()
                withFrameNanos { session.renderTick(it) }
                update()
                repeat(session.takeClicks()) { click() }
            }
        }
        SlideViewport(controller.presentation.size, modifier) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { session.configure(it.width, it.height, density) }
                    .pointerInput(Unit) { forwardPointerEvents(session) },
            ) {
                frame?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                SharedPointer(controller)
            }
        }
    }

    @Composable
    override fun Current(controller: PresentationController, modifier: Modifier) {
        SlideViewport(controller.presentation.size, modifier) {
            frame?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
    }

    /** Until the still of [frame] arrives, the one before stays: a flash of the letterbox color would show more. */
    @Composable
    override fun Still(controller: PresentationController, frame: Frame, letterboxColor: Color, modifier: Modifier) {
        val density = LocalDensity.current.density
        var size by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(frame, size, density) {
            if (size.width > 0 && size.height > 0) session.requestStill(frame, size.width, size.height, density)
        }
        SlideViewport(controller.presentation.size, modifier, letterboxColor) {
            Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
                still?.let { Image(it.second, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            }
        }
    }

    /** Asks the child, which has the deck's code, for the picture. */
    override suspend fun thumbnail(controller: PresentationController, frame: Frame, width: Int, assetDirectory: File?): ImageBitmap? {
        val size = controller.presentation.size
        // The height an export of that width has, as the child draws it.
        val height = (size.height * (width.toFloat() / size.width)).roundToInt()
        return session.thumbnail(frame, width, height)?.bitmap
    }

    /** Takes what the session received since the last display frame into Compose state. */
    private fun update() {
        session.frame?.takeIf { it !== shownFrame }?.let {
            shownFrame = it
            frame = it.bitmap
        }
        session.still?.takeIf { it !== shownStill }?.let {
            shownStill = it
            still = it.frame to it.image.bitmap
        }
        session.takeNotes().asSequence().filter { it.slide in notes.indices }.forEach { notes[it.slide] = it.notes }
    }
}

/**
 * A presentation with [deck]'s slides, steps, timing and pointer colors, but no content, and the
 * notes [notesOf] gives. Its transition is none: the child plays the deck's own, and none needs no
 * look at the slides' elements here.
 */
internal fun standIn(deck: DeckInfo, notesOf: (slide: Int) -> List<String>): Presentation =
    presentation(
        title = deck.title,
        size = SlideSize(deck.width, deck.height),
        transition = SlideTransition.None,
        duration = deck.durationMillis?.milliseconds,
    ) {
        standInSlides(deck, notesOf)
    }

private fun PresentationBuilder.standInSlides(deck: DeckInfo, notesOf: (slide: Int) -> List<String>) {
    for ((index, slide) in deck.slides.withIndex()) standInSlide(index, slide, notesOf)
}

/** The slide at [index], as [slide] tells it, with nothing on it. */
private fun PresentationBuilder.standInSlide(index: Int, slide: SlideInfo, notesOf: (slide: Int) -> List<String>) {
    slide(
        steps = slide.steps,
        notes = { notesOf(index) },
        duration = slide.durationMillis?.milliseconds,
        weight = slide.weight,
        style = { pointer(slide.pointer) },
        export = SlideExport.step(slide.representativeStep),
    ) {}
}

/** Sends the mouse over the slides to the child, in the slides' pixels, so their own elements can take clicks. */
private suspend fun PointerInputScope.forwardPointerEvents(session: SafeModeSession) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val position = change.position
            val type = when (event.type) {
                PointerEventType.Move -> PointerType.MOVE
                PointerEventType.Press -> PointerType.PRESS
                PointerEventType.Release -> PointerType.RELEASE
                PointerEventType.Exit -> PointerType.EXIT
                PointerEventType.Scroll -> PointerType.SCROLL
                else -> continue
            }
            session.pointer(type, position.x, position.y, if (type == PointerType.SCROLL) change.scrollDelta.y else 0f)
        }
    }
}

/** What stands in for a deck that never loaded: no slides, at the usual size. */
internal fun emptyDeckInfo(title: String): DeckInfo =
    DeckInfo(title, SlideSize.Widescreen.width, SlideSize.Widescreen.height, durationMillis = null, slides = emptyList())

/** The child's load state, polled into composition so the windows can show loading and errors. */
@Composable
internal fun rememberSafeModeStatus(session: SafeModeSession): SafeModeSession.Status {
    var status by remember(session) { mutableStateOf(session.status) }
    LaunchedEffect(session) {
        while (isActive) {
            status = session.status
            delay(100)
        }
    }
    return status
}
