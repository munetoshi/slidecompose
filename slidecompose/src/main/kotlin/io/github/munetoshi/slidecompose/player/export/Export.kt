package io.github.munetoshi.slidecompose.player.export

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.SlideExport
import io.github.munetoshi.slidecompose.deck.allFrames
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.deck.components.LocalStillImage
import io.github.munetoshi.slidecompose.deck.components.Reveal
import io.github.munetoshi.slidecompose.deck.exportedFrames
import io.github.munetoshi.slidecompose.deck.transition.ElementIdRecorder
import io.github.munetoshi.slidecompose.deck.transition.LocalElementIdRecorder
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideStage
import io.github.munetoshi.slidecompose.player.stage.StaticSlide
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** How pages made of one image each are stored: see [PdfExportOptions.vector]. */
enum class PdfImageFormat {
    /** Lossless. Crisp text, larger files. */
    Png,

    /** Smaller files; fine for photo-heavy decks. */
    Jpeg,
}

data class PdfExportOptions(
    /**
     * `true` exports every build step as its own page; `false` exports each slide fully built, or
     * the steps its [SlideExport] gives. Slides left out with [SlideExport.None] are left out of both.
     */
    val includeBuildSteps: Boolean = false,
    /**
     * `true` keeps text and shapes as vectors, sharp at any zoom, and the text can be searched and
     * copied. What PDF cannot express, such as shaders and blurs, becomes images. A page that cannot
     * be kept so becomes one image: see [PdfExportResult.imagePages]. With `false`, and where vector
     * export is not available (it needs macOS), every page is one image.
     */
    val vector: Boolean = true,
    /** Pixels per slide unit in images: 2 renders a 1920x1080 page at 3840x2160. */
    val scale: Float = 2f,
    val imageFormat: PdfImageFormat = PdfImageFormat.Png,
    /** JPEG quality in 0..100. */
    val jpegQuality: Int = 90,
)

/** What [exportPdf] made. */
data class PdfExportResult(
    /** Whether text and shapes stayed vectors: see [PdfExportOptions.vector]. */
    val vector: Boolean,
    /**
     * In a vector PDF, the pages, counted from 1, that are one image each, because something on them
     * could not be kept, such as an image that Compose keeps only as pixels. [assetPainter]'s images
     * are kept.
     */
    val imagePages: List<Int> = emptyList(),
)

/**
 * Renders one frame off-screen, without transitions. Animations started on first composition
 * (e.g. [Reveal]), or by a state change right after it, are run to completion first.
 */
fun Presentation.renderFrame(
    frame: Frame,
    scale: Float = 2f,
    assetDirectory: File? = null,
): Image {
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

/** [frame] as a still image shows it, for [renderFrame] and vector PDFs. */
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

private const val SETTLE_NANOS = 5_000_000_000L

/** Thrown by [checkFrames] when rendering [frame] throws. */
class FrameCheckException(val frame: Frame, cause: Throwable) : Exception("Rendering $frame threw $cause", cause)

/**
 * Composes, lays out and draws each of [frames] once, small and off-screen, one after another in
 * one scene, and throws a [FrameCheckException] for the first frame that throws. A quick check
 * that frames render, e.g. before showing a freshly loaded deck.
 */
fun Presentation.checkFrames(frames: List<Frame> = allFrames(), assetDirectory: File? = null) {
    if (frames.isEmpty()) return
    val width = 64
    val scene = ImageComposeScene(width, (width / size.aspectRatio).roundToInt().coerceAtLeast(1), Density(1f))
    try {
        frames.forEachIndexed { index, frame ->
            val ids = ElementIdRecorder()
            try {
                // New content for every frame: composed from scratch, as when it comes on screen.
                scene.setContent {
                    CompositionLocalProvider(
                        LocalAssetDirectory provides (assetDirectory ?: LocalAssetDirectory.current),
                        LocalElementIdRecorder provides ids,
                    ) {
                        StaticSlide(this@checkFrames, frame, Modifier.fillMaxSize())
                    }
                }
                scene.render(index * 1_000_000L).close()
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                throw FrameCheckException(frame, e)
            }
            rememberElementIds(frame, ids.ids)
        }
    } finally {
        scene.close()
    }
}

/**
 * Renders the animated transition from [from] to [to] off-screen, at each of [atMillis]
 * milliseconds after it starts. Useful for checking element transitions without opening a window.
 */
fun Presentation.renderTransition(
    from: Frame,
    to: Frame,
    atMillis: List<Int>,
    scale: Float = 1f,
    assetDirectory: File? = null,
): List<Image> {
    val controller = PresentationController(this)
    controller.goTo(from, animate = false)
    val width = (size.width * scale).roundToInt()
    val height = (size.height * scale).roundToInt()
    val scene = ImageComposeScene(width, height, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides (assetDirectory ?: LocalAssetDirectory.current)) {
            SlideStage(controller, Modifier.fillMaxSize())
        }
    }
    try {
        scene.render(0L)
        scene.render(SETTLE_NANOS).close()
        controller.goTo(to)
        Snapshot.sendApplyNotifications()
        val start = SETTLE_NANOS * 2
        // The first frame recomposes and launches the animations; they take their start time
        // from the frame after that, so render the start time twice.
        scene.render(start).close()
        scene.render(start).close()
        return atMillis.map { scene.render(start + it * 1_000_000L) }
    } finally {
        scene.close()
    }
}

/**
 * Plays every transition of the presentation once off-screen, at a small size, so that each one
 * is smooth the first time it is seen. The code behind a kind of transition is loaded and compiled
 * the first time it runs, which can make its first frame take tens of milliseconds; fonts and
 * images are not the issue.
 *
 * Call it on the windows' UI thread: the transitions observe snapshot state, which Compose does
 * not support from two threads. It yields after every frame, so the windows stay responsive.
 */
internal suspend fun Presentation.rehearse(assetDirectory: File? = null) {
    val frames = allFrames()
    if (frames.size < 2) return
    val controller = PresentationController(this)
    val width = REHEARSAL_WIDTH
    val scene = ImageComposeScene(width, (width / size.aspectRatio).roundToInt().coerceAtLeast(1), Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides (assetDirectory ?: LocalAssetDirectory.current)) {
            SlideStage(controller, Modifier.fillMaxSize())
        }
    }
    try {
        scene.render(0L).close()
        for ((index, frame) in frames.drop(1).withIndex()) {
            controller.goTo(frame)
            Snapshot.sendApplyNotifications()
            playTransition(scene, startNanos = (index * REHEARSAL_FRAMES + 1) * REHEARSAL_FRAME_NANOS)
        }
    } catch (e: Throwable) {
        if (e is VirtualMachineError || e is CancellationException) throw e
        // Only a rehearsal: rendering the slide for real reports the error.
    } finally {
        scene.close()
    }
}

private const val REHEARSAL_WIDTH = 192
private const val REHEARSAL_FRAMES = 12
private const val REHEARSAL_FRAME_NANOS = 100_000_000L

/**
 * Exports the presentation as a PDF, by default with text and shapes as vectors: see
 * [PdfExportOptions.vector].
 */
fun Presentation.exportPdf(
    file: File,
    options: PdfExportOptions = PdfExportOptions(),
    assetDirectory: File? = null,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): PdfExportResult {
    val frames = exportedFrames(options.includeBuildSteps)
    require(frames.isNotEmpty()) { "No slide to export: every one is left out with SlideExport.None" }
    file.absoluteFile.parentFile?.mkdirs()
    val tool = skPdfTool.takeIf { options.vector }
    if (tool != null) {
        try {
            return exportVectorPdf(tool, frames, file, options, assetDirectory, onProgress)
        } catch (e: SkPdfException) {
            System.err.println("SlideCompose: ${e.message}; making the pages images instead")
        }
    }
    exportImagePdf(frames, file, options, assetDirectory, onProgress)
    return PdfExportResult(vector = false)
}

/**
 * Renders the frames of a transition at [REHEARSAL_FRAMES] times from [startNanos]: through the
 * fade-out, the move and the fade-in of the usual transition lengths. The next move completes
 * anything still running, which needs warming up too.
 */
private suspend fun playTransition(scene: ImageComposeScene, startNanos: Long) {
    for (index in 0 until REHEARSAL_FRAMES) {
        scene.render(startNanos + index * REHEARSAL_FRAME_NANOS).close()
        yield()
    }
}

/** Writes [frames] as a PDF with one image per page. */
private fun Presentation.exportImagePdf(
    frames: List<Frame>,
    file: File,
    options: PdfExportOptions,
    assetDirectory: File?,
    onProgress: (done: Int, total: Int) -> Unit,
) {
    PDDocument().use { writeImagePdf(it, frames, file, options, assetDirectory, onProgress) }
}

private fun Presentation.writeImagePdf(
    document: PDDocument,
    frames: List<Frame>,
    file: File,
    options: PdfExportOptions,
    assetDirectory: File?,
    onProgress: (done: Int, total: Int) -> Unit,
) {
    document.documentInformation.title = title
    document.documentInformation.creator = "SlideCompose"
    for ((index, frame) in frames.withIndex()) {
        onProgress(index, frames.size)
        addImagePage(document, frame, options, assetDirectory)
    }
    onProgress(frames.size, frames.size)
    document.save(file)
}

/** Adds a page to [document] that is an image of [frame]. */
private fun Presentation.addImagePage(document: PDDocument, frame: Frame, options: PdfExportOptions, assetDirectory: File?) {
    val pageSize = PDRectangle(size.width.toFloat(), size.height.toFloat())
    val image = renderFrame(frame, options.scale, assetDirectory)
    val xObject = when (options.imageFormat) {
        PdfImageFormat.Png -> LosslessFactory.createFromImage(document, image.toComposeImageBitmap().toAwtImage())
        PdfImageFormat.Jpeg -> JPEGFactory.createFromByteArray(document, jpegOf(image, frame, options))
    }
    image.close()
    val page = PDPage(pageSize)
    document.addPage(page)
    PDPageContentStream(document, page).use { it.drawImage(xObject, 0f, 0f, pageSize.width, pageSize.height) }
}

/** [frame] rendered and encoded as [PdfExportOptions.imageFormat] says, for a page made of one image. */
internal fun Presentation.encodedFrame(frame: Frame, options: PdfExportOptions, assetDirectory: File?): ByteArray =
    renderFrame(frame, options.scale, assetDirectory).use { encoded(it, frame, options) }

private fun encoded(image: Image, frame: Frame, options: PdfExportOptions): ByteArray =
    when (options.imageFormat) {
        PdfImageFormat.Png -> pngOf(image, frame)
        PdfImageFormat.Jpeg -> jpegOf(image, frame, options)
    }

private fun pngOf(image: Image, frame: Frame): ByteArray =
    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Failed to encode slide ${frame.slide + 1} as PNG" }.bytes

private fun jpegOf(image: Image, frame: Frame, options: PdfExportOptions): ByteArray =
    checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, options.jpegQuality)) {
        "Failed to encode slide ${frame.slide + 1} as JPEG"
    }.bytes

/**
 * Exports every slide (or every build step) as a PNG file into [directory], as [exportPdf] would
 * make pages: see [SlideExport].
 */
fun Presentation.exportPngs(
    directory: File,
    includeBuildSteps: Boolean = false,
    scale: Float = 1f,
    assetDirectory: File? = null,
): List<File> {
    directory.mkdirs()
    val frames = exportedFrames(includeBuildSteps)
    // A slide with more than one page names its files after their steps.
    val pagesOf = frames.groupingBy { it.slide }.eachCount()
    return frames.map { writePng(it, File(directory, pngName(it, includeBuildSteps || pagesOf.getValue(it.slide) > 1)), scale, assetDirectory) }
}

/** "slide-003.png", or with [withStep], "slide-003-02.png". */
private fun pngName(frame: Frame, withStep: Boolean): String =
    if (withStep) "slide-%03d-%02d.png".format(frame.slide + 1, frame.step + 1) else "slide-%03d.png".format(frame.slide + 1)

private fun Presentation.writePng(frame: Frame, file: File, scale: Float, assetDirectory: File?): File {
    val bytes = renderFrame(frame, scale, assetDirectory).use { pngOf(it, frame) }
    file.writeBytes(bytes)
    return file
}
