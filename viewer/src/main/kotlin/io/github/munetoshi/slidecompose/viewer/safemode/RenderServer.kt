package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Frame as DeckFrame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.Slide
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.player.export.renderFrame
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.viewer.compile.DeckCompiler
import io.github.munetoshi.slidecompose.viewer.compile.LoadResult
import io.github.munetoshi.slidecompose.viewer.compile.deckSources
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The untrusted side of safe mode: it compiles a deck and renders it off-screen, driven only by
 * [Command]s over a stream. It holds a [PresentationController] (which runs deck code) that follows
 * the parent's, and two [ImageComposeScene]s: the audience's view, and a still for the presenter's
 * next slide. They draw into the [SharedPixels] the parent made in [work]. It never opens a window,
 * touches the GPU, or reaches the network. Everything runs on one thread, as Compose's snapshot
 * system requires.
 */
class RenderServer(
    private val deck: File,
    private val assets: File,
    private val libraries: List<File>,
    /** Where the parent puts the [SharedPixels] files: the child's work directory. */
    private val work: File,
    private val compiler: DeckCompiler = DeckCompiler(),
) {
    private var controller: PresentationController? = null
    private var stage: SceneOf? = null
    private var still: SceneOf? = null
    /** The frame the still shows; the still's scene reads it, so a new one recomposes it. */
    private var stillFrame: DeckFrame by mutableStateOf(DeckFrame(0, 0))
    /** Whether something changed since the last frame, so [Command.Render] must redraw. */
    private var stageDirty = true
    private var stillDirty = false
    /** When the still was last drawn because its scene asked to, on the render clock; see [stillEvent]. */
    private var stillDrawnAt = Long.MIN_VALUE
    /** What the still last sent looked like, to tell whether a redraw changed it. */
    private var lastStillHash = 0L
    /** Whether the slides got a click that nothing on them took, since the last render. */
    private var clicked = false
    /** The notes last sent for each slide. */
    private val sentNotes = HashMap<Int, List<String>>()

    /** Compiles and loads the deck. Returns [Event.Loaded] or [Event.LoadFailed]. */
    fun load(): Event =
        when (val result = compiler.compileAndLoad(deckSources(deck), assets, libraries)) {
            is LoadResult.Success -> {
                controller = PresentationController(result.presentation)
                val deck = deckInfo(result.presentation)
                // The parent has these notes from the deck it loaded.
                for ((index, slide) in deck.slides.withIndex()) sentNotes[index] = slide.notes
                Event.Loaded(deck)
            }
            is LoadResult.Failure -> Event.LoadFailed(result.diagnostics)
        }

    private fun deckInfo(presentation: Presentation): DeckInfo =
        DeckInfo(
            title = presentation.title,
            width = presentation.size.width,
            height = presentation.size.height,
            durationMillis = presentation.duration?.inWholeMilliseconds,
            slides = presentation.slides.map { slide ->
                SlideInfo(slide.steps, slide.duration?.inWholeMilliseconds, slide.weight, slide.style.pointer, notesOf(slide), slide.representativeStep)
            },
        )

    /** Applies a command. [Command.Render] returns the events that answer it, the frame last; the others return none. */
    fun apply(command: Command): List<Event> {
        val controller = controller ?: return emptyList()
        when (command) {
            is Command.Configure -> configure(controller, command)
            is Command.Render -> return render(controller, command.nanos)
            is Command.Pointer -> pointer(command)
            is Command.GoTo -> {
                controller.goTo(DeckFrame(command.slide, command.step))
                stageDirty = true
            }
            is Command.Still -> requestStill(controller, command)
            is Command.Thumbnail -> return listOfNotNull(thumbnail(controller.presentation, command))
            Command.Shutdown -> close()
        }
        return emptyList()
    }

    /**
     * Draws the picture asked for into the parent's file of its size, as an export draws a frame
     * (and as the slide list draws it in a deck that is trusted). Without an answer, the parent
     * gives up waiting after a while.
     */
    private fun thumbnail(presentation: Presentation, command: Command.Thumbnail): Event.Thumbnail? {
        val slide = presentation.slides.getOrNull(command.slide) ?: return null
        if (!fits(command.width, command.height)) return null
        val pixels = MappedPixels.open(work, "thumbnail", command.width, command.height) ?: return null
        val frame = DeckFrame(command.slide, command.step.coerceIn(0, slide.steps - 1))
        val scale = command.width.toFloat() / presentation.size.width
        val drawn = runCatching { presentation.renderFrame(frame, scale, assets).use { pixels.write(it) } }.isSuccess
        return if (drawn) Event.Thumbnail(command.slide, command.step, command.width, command.height) else null
    }

    private fun configure(controller: PresentationController, command: Command.Configure) {
        if (!fits(command.width, command.height)) return
        if (stage?.matches(command.width, command.height, command.density) == true) return
        val pixels = MappedPixels.open(work, "stage", command.width, command.height) ?: return
        stage?.scene?.close()
        stage = SceneOf(pixels, command.density) {
            CompositionLocalProvider(LocalAssetDirectory provides assets) {
                SlideRenderer.InProcess.Stage(controller, onClick = { clicked = true }, Modifier.fillMaxSize())
            }
        }
        stageDirty = true
    }

    private fun requestStill(controller: PresentationController, command: Command.Still) {
        if (!fits(command.width, command.height)) return
        stillFrame = DeckFrame(command.slide, command.step)
        stillDirty = true
        if (still?.matches(command.width, command.height, command.density) == true) return
        val pixels = MappedPixels.open(work, "still", command.width, command.height) ?: return
        still?.scene?.close()
        still = SceneOf(pixels, command.density) {
            CompositionLocalProvider(LocalAssetDirectory provides assets) {
                SlideRenderer.InProcess.Still(controller, stillFrame, Color.Black, Modifier.fillMaxSize())
            }
        }
    }

    private fun pointer(command: Command.Pointer) {
        val scene = stage?.scene ?: return
        val type = when (command.type) {
            PointerType.MOVE, PointerType.SCROLL -> PointerEventType.Move
            PointerType.PRESS -> PointerEventType.Press
            PointerType.RELEASE -> PointerEventType.Release
            PointerType.EXIT -> PointerEventType.Exit
        }
        scene.sendPointerEvent(type, Offset(command.x, command.y))
        stageDirty = true
    }

    private fun render(controller: PresentationController, nanos: Long): List<Event> {
        Snapshot.sendApplyNotifications()
        // Drawn first, as it runs the click handlers of the pointer events sent before.
        val frame = stage?.let { stageEvent(it, nanos) } ?: Event.Unchanged(animating = false)
        val wasClicked = clicked
        clicked = false
        val stillEvent = still?.let { stillEvent(it, nanos) }
        return (sequenceOf(stillEvent) + changedNotes(controller) + sequenceOf(if (wasClicked) Event.Clicked else null, frame)).filterNotNull().toList()
    }

    private fun stageEvent(stage: SceneOf, nanos: Long): Event {
        if (!stageDirty && !stage.scene.hasInvalidations()) return Event.Unchanged(animating = false)
        stage.draw(nanos)
        stageDirty = false
        return Event.Frame(stage.width, stage.height, animating = stage.scene.hasInvalidations())
    }

    /**
     * The still, when it was asked for or its slide changed. A still is drawn at time 0, so animated
     * backgrounds stand still; a scene that keeps asking to be drawn, as they do, is drawn again at
     * most every [STILL_REDRAW_NANOS]. Not to make the parent copy it for nothing, it is then sent
     * only when it looks different, e.g. after the deck's state changed.
     */
    private fun stillEvent(still: SceneOf, nanos: Long): Event.Still? {
        val asked = stillDirty
        if (!asked && !(still.scene.hasInvalidations() && nanos - stillDrawnAt >= STILL_REDRAW_NANOS)) return null
        still.draw(0L)
        val hash = still.pixels.checksum()
        stillDirty = false
        stillDrawnAt = nanos
        if (!asked && hash == lastStillHash) return null
        lastStillHash = hash
        return Event.Still(stillFrame.slide, stillFrame.step, still.width, still.height)
    }

    /** The notes of the current and next slides that differ from the ones last sent, which they then are. */
    private fun changedNotes(controller: PresentationController): List<Event.Notes> {
        val slides = controller.presentation.slides
        val changed = sequenceOf(controller.frame.slide, controller.nextFrame?.slide)
            .filterNotNull()
            .distinct()
            .map { Event.Notes(it, notesOf(slides[it])) }
            .filter { sentNotes[it.slide] != it.notes }
            .toList()
        changed.forEach { sentNotes[it.slide] = it.notes }
        return changed
    }

    fun close() {
        stage?.scene?.close()
        still?.scene?.close()
        stage = null
        still = null
    }

    /**
     * Serves commands from [input] and writes events to [output] until [Command.Shutdown] or end of
     * stream. Sends the load result first.
     */
    fun serve(input: DataInputStream, output: DataOutputStream) {
        output.writeEvent(load())
        while (true) {
            val command = input.readCommand() ?: break
            val events = apply(command)
            if (events.isNotEmpty()) output.writeEvents(events)
            if (command == Command.Shutdown) break
        }
        close()
    }
}

/** How often a still whose scene keeps asking to be drawn is drawn again: see [RenderServer]. */
private const val STILL_REDRAW_NANOS = 500_000_000L

/** Whether a frame of this size is one the parent accepts. */
private fun fits(width: Int, height: Int): Boolean = width in 1..MAX_FRAME_SIDE && height in 1..MAX_FRAME_SIDE

/** An off-screen scene the size of [pixels], which it draws into. */
private class SceneOf(val pixels: MappedPixels, val density: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
    val width: Int get() = pixels.width
    val height: Int get() = pixels.height
    val scene = ImageComposeScene(width, height, Density(density), content = content)

    fun matches(width: Int, height: Int, density: Float): Boolean = pixels.matches(width, height) && density == this.density

    /** Draws the scene at [nanos] into the shared pixels. */
    fun draw(nanos: Long) {
        scene.render(nanos).use { pixels.write(it) }
    }
}

/** A slide's notes, or none when the deck's code for them fails. */
private fun notesOf(slide: Slide): List<String> = runCatching { slide.notes }.getOrDefault(emptyList())
