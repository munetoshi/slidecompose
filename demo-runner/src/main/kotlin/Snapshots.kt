import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.PresentationBuilder
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.player.export.exportPdf
import io.github.munetoshi.slidecompose.player.export.exportPngs
import io.github.munetoshi.slidecompose.player.export.renderFrame
import io.github.munetoshi.slidecompose.player.export.renderTransition
import io.github.munetoshi.slidecompose.player.presenter.PresenterColors
import io.github.munetoshi.slidecompose.player.presenter.PresenterView
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.PresentationTimer
import io.github.munetoshi.slidecompose.player.stage.SlideStage
import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/snapshots").apply { mkdirs() }
    val assets = File("examples/demo")
    val started = System.currentTimeMillis()
    val discoveryStarted = System.currentTimeMillis()
    println("steps: ${deck.slides.map { it.steps }} in ${System.currentTimeMillis() - discoveryStarted} ms")
    deck.exportPngs(File(out, "slides"), includeBuildSteps = true, scale = 0.5f, assetDirectory = assets)
    println("pngs: ${System.currentTimeMillis() - started} ms")

    // The title moves up while the language picker fades in.
    transition(out, assets, "t0-1", Frame(0, 0), Frame(1, 0))
    transition(out, assets, "t1-2", Frame(1, 0), Frame(2, 0))
    // A closer look at the middle of the first element transition that moves things.
    deck.renderTransition(Frame(1, 0), Frame(2, 0), listOf(300, 450), scale = 0.5f, assetDirectory = assets)
        .forEachIndexed { i, image ->
            File(out, "t1-2-zoom-$i.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    // Staged timing without matching elements: the old slide fades out before the agenda fades in.
    transition(out, assets, "t-into-agenda", Frame(2, 0), Frame(3, 0))
    // Staged timings: agenda items into section titles, and back.
    transition(out, assets, "t-agenda-in", Frame(3, 0), Frame(4, 0))
    transition(out, assets, "t-agenda-in-2", Frame(7, 0), Frame(8, 0))
    transition(out, assets, "t-agenda-in-3", Frame(10, 0), Frame(11, 0))
    transition(out, assets, "t-agenda-in-4", Frame(13, 0), Frame(14, 0))
    transition(out, assets, "t-agenda-out", Frame(6, 0), Frame(7, 0))
    transition(out, assets, "t-code-1", Frame(4, 0), Frame(4, 1))
    transition(out, assets, "t-code-2", Frame(4, 1), Frame(4, 2))
    transition(out, assets, "t-code-back", Frame(4, 2), Frame(4, 1))
    interrupted(out, assets, "i1-2-3", Frame(1, 0), Frame(2, 0), Frame(3, 0))
    interrupted(out, assets, "i-code", Frame(4, 0), Frame(4, 1), Frame(4, 2))
    interrupted(out, assets, "i-steps", Frame(8, 0), Frame(8, 1), Frame(8, 0))
    transition(out, assets, "t-steps", Frame(8, 0), Frame(8, 1))
    transition(out, assets, "t-steps-b", Frame(8, 1), Frame(8, 0))
    // A fade into the code block whose focus moves, which stays under the same title.
    transition(out, assets, "t-code-focus", Frame(11, 2), Frame(12, 0))
    transition(out, assets, "t-focus-1", Frame(11, 0), Frame(11, 1))
    transition(out, assets, "t-code-agenda", Frame(12, 0), Frame(13, 0))
    // Items that float up as they come in (the slide's reveal offset).
    transition(out, assets, "t-presenting-1", Frame(14, 0), Frame(14, 1))
    // A push: the page number moves along with the slide.
    transition(out, assets, "t-push", Frame(8, 1), Frame(9, 0))
    // The slide with an editable CodeBlock: its editor.
    deck.renderFrame(Frame(12, 0), scale = 0.5f, assetDirectory = assets).use {
        File(out, "editable-code.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    // The slide with a button, which the PDF leaves out: its code and the button beside it.
    deck.renderFrame(Frame(6, 0), scale = 0.5f, assetDirectory = assets).use {
        File(out, "button.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    // From the first slide to the last: the title says other words there, so the two cross-fade on the way.
    transition(out, assets, "t-first-last", Frame(0, 0), Frame(deck.slides.lastIndex, 0))
    // In a theme with sans serif titles over serif text, an agenda item changes font letter by letter.
    val sansHeadings = deck.withTheme(themes.paper.sansHeadings)
    sheet(out, "t-agenda-in-font", sansHeadings.renderTransition(Frame(3, 0), Frame(4, 0), listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 1000), scale = 0.25f, assetDirectory = assets))
    sansHeadings.renderTransition(Frame(3, 0), Frame(4, 0), listOf(0, 350, 500, 650), scale = 0.5f, assetDirectory = assets)
        .forEachIndexed { i, image ->
            File(out, "t-agenda-in-font-zoom-$i.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    // In the Paper theme, a section's title shrinks back into its item, from the serif's bold to its
    // regular weight, which is much narrower: the letters start the size they were.
    val paper = deck.withTheme(themes.paper.theme)
    sheet(out, "t-code-agenda-paper", paper.renderTransition(Frame(12, 0), Frame(13, 0), listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 1000), scale = 0.25f, assetDirectory = assets))
    paper.renderTransition(Frame(12, 0), Frame(13, 0), listOf(0, 250, 500, 700), scale = 0.5f, assetDirectory = assets)
        .forEachIndexed { i, image ->
            File(out, "t-code-agenda-paper-zoom-$i.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    // And the other way, an item growing into its section's title: the regular weight's strokes,
    // which stick out of the bold's here and there, fade out before the end instead of at it.
    sheet(out, "t-agenda-in-paper", paper.renderTransition(Frame(7, 0), Frame(8, 0), listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 1000), scale = 0.25f, assetDirectory = assets))
    paper.renderTransition(Frame(7, 0), Frame(8, 0), listOf(700, 760, 790, 1000), scale = 0.5f, assetDirectory = assets)
        .forEachIndexed { i, image ->
            File(out, "t-agenda-in-paper-zoom-$i.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }

    // The presenter window's layout, rendered off-screen, a few minutes into the talk.
    val controller = PresentationController(deck).apply { goTo(Frame(4, 2), animate = false) }
    controller.timer.pretendElapsed(190_000)
    val scene = ImageComposeScene(1360, 860, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            PresenterView(controller, onSwapDisplays = {}, colors = io.github.munetoshi.slidecompose.player.presenter.PresenterColors.Dark)
        }
    }
    // Let the notes scroll to the current step.
    for (frame in 0..60) scene.render(frame * 16_000_000L).close()
    File(out, "presenter.png").writeBytes(scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    val lightScene = ImageComposeScene(1360, 860, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            PresenterView(controller, colors = io.github.munetoshi.slidecompose.player.presenter.PresenterColors.Light)
        }
    }
    for (frame in 0..60) lightScene.render(frame * 16_000_000L).close()
    File(out, "presenter-light.png").writeBytes(lightScene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    lightScene.close()

    // With the list of slides open. Its pictures are drawn off the UI thread: give them time. As in a
    // window, the scene's own work runs on one thread, here this one, between frames; with the
    // default, it would run on whichever thread drew a picture.
    val uiThreadWork = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    val listScene = ImageComposeScene(1360, 860, Density(1f), coroutineContext = java.util.concurrent.Executor { uiThreadWork.add(it) }.asCoroutineDispatcher()) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            PresenterView(controller, colors = io.github.munetoshi.slidecompose.player.presenter.PresenterColors.Dark, showsSlideList = true)
        }
    }
    for (frame in 0..60) {
        runAll(uiThreadWork)
        listScene.render(frame * 50_000_000L).close()
        Thread.sleep(20)
    }
    File(out, "presenter-slides.png").writeBytes(listScene.render(4_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    listScene.close()

    // Behind the plan: 7 minutes in, still on the code slide of a 10 minute talk (planned to end at 6:47).
    controller.timer.pretendElapsed(420_000)
    val behindScene = ImageComposeScene(1360, 860, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            PresenterView(controller, colors = io.github.munetoshi.slidecompose.player.presenter.PresenterColors.Dark)
        }
    }
    for (frame in 0..10) behindScene.render(frame * 16_000_000L).close()
    File(out, "presenter-behind.png").writeBytes(behindScene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    behindScene.close()

    // Without a planned duration there is no time bar.
    val untimed = io.github.munetoshi.slidecompose.deck.presentation(title = deck.title) { slidesOf(deck) }
    val untimedController = PresentationController(untimed).apply { goTo(Frame(4, 2), animate = false) }
    val untimedScene = ImageComposeScene(1360, 860, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            PresenterView(untimedController, colors = io.github.munetoshi.slidecompose.player.presenter.PresenterColors.Dark)
        }
    }
    for (frame in 0..10) untimedScene.render(frame * 16_000_000L).close()
    File(out, "presenter-untimed.png").writeBytes(untimedScene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    untimedScene.close()

    // The audience window with the pointer shared from the presenter window.
    controller.pointer = Offset(0.55f, 0.45f)
    val audience = ImageComposeScene(960, 540, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            SlideStage(controller, Modifier.fillMaxSize(), showsPointer = true)
        }
    }
    audience.render(0)
    File(out, "pointer.png").writeBytes(audience.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    // Past the left edge, the rest of the arrow is still seen.
    controller.pointer = Offset(-0.01f, 0.45f)
    Snapshot.sendApplyNotifications()
    audience.render(1_500_000_000).close()
    File(out, "pointer-edge.png").writeBytes(audience.render(1_600_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    // A deck that uses the black pointer.
    val blackPointerDeck = io.github.munetoshi.slidecompose.deck.presentation {
        blackPointer()
        contentOf(deck)
    }
    val blackController = PresentationController(blackPointerDeck).apply {
        goTo(Frame(4, 2), animate = false)
        pointer = Offset(0.55f, 0.45f)
    }
    val blackScene = ImageComposeScene(960, 540, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) {
            SlideStage(blackController, Modifier.fillMaxSize(), showsPointer = true)
        }
    }
    blackScene.render(0)
    File(out, "pointer-black.png").writeBytes(blackScene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    blackScene.close()
    controller.isPointerShared = false
    Snapshot.sendApplyNotifications()
    File(out, "pointer-off.png").writeBytes(audience.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    audience.close()

    val pdfStarted = System.currentTimeMillis()
    val pdf = File(out, "demo.pdf")
    deck.exportPdf(pdf, assetDirectory = assets)
    println("pdf: ${pdf.length() / 1024} KiB in ${System.currentTimeMillis() - pdfStarted} ms")
}

/** Writes [images] to [out] as a contact sheet named [name]: 2 rows of 5 frames. */
private fun sheet(out: File, name: String, images: List<Image>) {
    val w = images[0].width
    val h = images[0].height
    val surface = Surface.makeRasterN32Premul(w * 5 + 4 * 8, h * 2 + 8)
    surface.canvas.clear(0xFF808080.toInt())
    images.forEachIndexed { i, image ->
        surface.canvas.drawImage(image, (i % 5 * (w + 8)).toFloat(), (i / 5 * (h + 8)).toFloat())
    }
    File(out, "$name.png").writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
}

/** A contact sheet of the demo's transition from [from] to [to]. */
private fun transition(out: File, assets: File, name: String, from: Frame, to: Frame) {
    val times = listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 1000)
    sheet(out, name, deck.renderTransition(from, to, times, scale = 0.25f, assetDirectory = assets))
}

private const val MS = 1_000_000L

/** Moving on again 300 ms into a transition, from [start] to [first] and on to [second]: the first 4 frames are before that. */
private fun interrupted(out: File, assets: File, name: String, start: Frame, first: Frame, second: Frame) {
    val controller = PresentationController(deck).apply { goTo(start, animate = false) }
    val scene = ImageComposeScene(480, 270, Density(1f)) {
        CompositionLocalProvider(LocalAssetDirectory provides assets) { SlideStage(controller, Modifier.fillMaxSize()) }
    }
    scene.render(0).close()
    scene.render(5_000 * MS).close()
    val firstStart = 5_000 * MS + 16 * MS
    val firstTimes = listOf(0, 100, 200, 300)
    val firstImages = goAndRender(scene, controller, first, firstStart, firstTimes)
    // A frame after the last one shown.
    val secondImages = goAndRender(scene, controller, second, firstStart + firstTimes.last() * MS + 16 * MS, listOf(0, 100, 200, 400, 600, 1200))
    scene.close()
    sheet(out, name, firstImages + secondImages)
}

/**
 * Goes to [target] and renders [scene] [times] milliseconds after [start]. The first frame
 * recomposes and launches the animations, which start from the frame after: [start] is rendered twice.
 */
private fun goAndRender(scene: ImageComposeScene, controller: PresentationController, target: Frame, start: Long, times: List<Int>): List<Image> {
    controller.goTo(target)
    Snapshot.sendApplyNotifications()
    scene.render(start).close()
    scene.render(start).close()
    return times.map { scene.render(start + it * MS) }
}

/** Sets the timer's reading, to show the presenter window mid-talk. */
private fun PresentationTimer.pretendElapsed(millis: Long) {
    val accumulated = PresentationTimer::class.java.getDeclaredField("accumulated\$delegate").apply { isAccessible = true }
    (accumulated.get(this) as MutableLongState).longValue = millis
}

/** Runs the work waiting in [queue], and what that work adds to it. */
private fun runAll(queue: java.util.Queue<Runnable>) {
    generateSequence(queue::poll).forEach(Runnable::run)
}

/** The slides of [deck], with their steps and notes, but their timing. */
private fun PresentationBuilder.slidesOf(deck: Presentation) {
    for (slide in deck.slides) slide(steps = slide.steps, notes = slide.notes, content = slide.content)
}

/** The content of the slides of [deck] alone. */
private fun PresentationBuilder.contentOf(deck: Presentation) {
    for (slide in deck.slides) slide(content = slide.content)
}

private fun PresentationBuilder.blackPointer() = style { pointer(io.github.munetoshi.slidecompose.deck.theme.PointerColor.Black) }
