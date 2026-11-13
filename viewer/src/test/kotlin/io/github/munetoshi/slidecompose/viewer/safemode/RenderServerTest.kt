package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import io.github.munetoshi.slidecompose.deck.theme.*
import kotlin.time.Duration.Companion.minutes

val deck = presentation(title = "Bench", duration = 10.minutes) {
    slide(notes = "Say hello") { TitleSlide("One") }
    slide(notes = listOf("a", "b"), style = { pointer(PointerColor.Black) }) {
        TitleAndContent("Two") {
            Bullet("a")
            Reveal(atStep = 1) { Bullet("b") }
        }
    }
    slide(duration = 1.minutes, weight = 2f) { TitleSlide("Three") }
}
"""

/** A deck whose whole slide is a button: it takes every click, and its notes follow its state. */
private const val BUTTON = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

object Counter { var clicks by mutableIntStateOf(0) }

val deck = presentation(title = "Button") {
    slide(notes = { listOf("clicked " + Counter.clicks) }) {
        Box(Modifier.fillMaxSize().clickable { Counter.clicks++ }) { TitleSlide("Click me " + Counter.clicks) }
    }
    slide { TitleSlide("Two") }
}
"""

private const val BROKEN = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Bench") {
    slide { TitleSlide(missing) }
}
"""

/**
 * Renders several ticks (so gesture and animation coroutines run) from [nanosFrom] and returns
 * every event they answered with.
 */
private fun RenderServer.render(nanosFrom: Long = 0L, ticks: Int = 6): List<Event> =
    (0 until ticks).flatMap { apply(Command.Render(nanosFrom + it * 16_000_000L)) }

/** The last frame that drew in [events]. */
private fun List<Event>.lastFrame(): Event.Frame = filterIsInstance<Event.Frame>().last()

/** A render server with the parent's side of its shared pixels, as the parent would make them. */
private class Child(val server: RenderServer, val work: java.io.File) {
    var stage: SharedPixels? = null
    var still: SharedPixels? = null

    fun configure(width: Int, height: Int) {
        stage = SharedPixels.create(work, "stage", width, height)!!
        server.apply(Command.Configure(width, height, 1f))
    }

    fun requestStill(slide: Int, step: Int, width: Int, height: Int) {
        still = SharedPixels.create(work, "still", width, height)!!
        server.apply(Command.Still(slide, step, width, height, 1f))
    }

    fun requestThumbnail(slide: Int, step: Int, width: Int, height: Int): List<Event> {
        thumbnail = SharedPixels.create(work, "thumbnail", width, height)!!
        return server.apply(Command.Thumbnail(slide, step, width, height))
    }

    var thumbnail: SharedPixels? = null

    /** The audience's view as last drawn. */
    fun pixels(): ByteArray = stage!!.read()!!

    fun stillPixels(): ByteArray = still!!.read()!!
}

/** Renders until an answer says nothing changed, so transitions have ended, and returns the events before it. */
private fun RenderServer.renderUntilIdle(nanosFrom: Long): List<Event> =
    (0 until 200).asSequence()
        .map { apply(Command.Render(nanosFrom + it * 16_000_000L)) }
        .takeWhile { answer -> answer.last() !is Event.Unchanged }
        .flatten()
        .toList()

/** Clicks at the middle of a 400 x 225 frame from [nanos], rendering between the events as a window would. */
private fun RenderServer.click(nanos: Long): List<Event> =
    listOf(PointerType.MOVE, PointerType.PRESS, PointerType.RELEASE).flatMapIndexed { index, type ->
        apply(Command.Pointer(type, 200f, 112f))
        render(nanos + index * 100_000_000L, ticks = 2)
    }

/** A render server for a deck of [source], not yet loaded. */
private fun TestConfiguration.server(source: String): RenderServer {
    val directory = tempdir()
    val deck = write(directory, "Slides.kt", source)
    return RenderServer(deck, directory, libraries = emptyList(), work = tempdir())
}

/** A loaded render server for a deck of [source], with the parent's side of its shared pixels. */
private fun TestConfiguration.child(source: String): Child {
    val work = tempdir()
    val directory = tempdir()
    val deck = write(directory, "Slides.kt", source)
    return Child(RenderServer(deck, directory, libraries = emptyList(), work = work).apply { load() }, work)
}

class RenderServerTest : FunSpec({
    test("loading tells the parent what it needs to run the windows") {
        val loaded = server(DECK).load().shouldBeInstanceOf<Event.Loaded>().deck
        loaded.title shouldBe "Bench"
        loaded.width shouldBe 1920
        loaded.height shouldBe 1080
        loaded.durationMillis shouldBe 600_000L
        loaded.slides.map { it.steps } shouldContainExactly listOf(1, 2, 1)
        loaded.slides.map { it.notes } shouldContainExactly listOf(listOf("Say hello"), listOf("a", "b"), emptyList())
        loaded.slides.map { it.durationMillis } shouldContainExactly listOf(null, null, 60_000L)
        loaded.slides.map { it.weight } shouldContainExactly listOf(1f, 1f, 2f)
        loaded.slides.map { it.pointer } shouldContainExactly listOf(PointerColor.White, PointerColor.Black, PointerColor.White)
    }

    test("a deck that does not compile reports the errors") {
        val failed = server(BROKEN).load().shouldBeInstanceOf<Event.LoadFailed>()
        failed.diagnostics.any { "missing" in it.message } shouldBe true
    }

    test("a configured server draws a non-blank frame into the shared pixels, the frame last in its answer") {
        val child = child(DECK)
        child.configure(400, 225)
        val frame = child.server.apply(Command.Render(0L)).last().shouldBeInstanceOf<Event.Frame>()
        frame.width shouldBe 400
        frame.height shouldBe 225
        val pixels = child.pixels()
        // Not a single flat color: the title slide drew something.
        pixels.any { it != pixels[0] } shouldBe true
        child.server.close()
    }

    test("without the parent's shared pixels, nothing is drawn") {
        val server = server(DECK)
        server.load()
        server.apply(Command.Configure(400, 225, 1f))
        server.apply(Command.Render(0L)) shouldBe listOf(Event.Unchanged(animating = false))
        server.close()
    }

    test("going to a slide draws it, and going back draws the first again") {
        val child = child(DECK)
        child.configure(400, 225)
        child.server.renderUntilIdle(0L).lastFrame()
        val first = child.pixels()
        child.server.apply(Command.GoTo(2, 0))
        child.server.renderUntilIdle(10_000_000_000L).lastFrame()
        child.pixels().contentEquals(first) shouldBe false
        child.server.apply(Command.GoTo(0, 0))
        child.server.renderUntilIdle(20_000_000_000L).lastFrame()
        child.pixels().contentEquals(first) shouldBe true
        child.server.close()
    }

    test("a click that nothing on the slide takes goes to the parent, which decides where to go") {
        val child = child(DECK)
        child.configure(400, 225)
        child.server.renderUntilIdle(0L)
        val before = child.pixels()
        child.server.click(10_000_000_000L) shouldContain Event.Clicked
        // The child does not move on by itself: what it draws after the click is the same slide.
        child.server.renderUntilIdle(20_000_000_000L)
        child.pixels().contentEquals(before) shouldBe true
        child.server.close()
    }

    test("a click the slide takes stays in the child, and notes that follow it are sent again") {
        val child = child(BUTTON)
        child.configure(400, 225)
        child.server.renderUntilIdle(0L)
        val answer = child.server.click(10_000_000_000L)
        answer shouldNotContain Event.Clicked
        answer shouldContain Event.Notes(0, listOf("clicked 1"))
        // Sent once: unchanged notes are not sent again.
        child.server.render(20_000_000_000L).filterIsInstance<Event.Notes>() shouldBe emptyList()
        child.server.close()
    }

    test("a still of the next slide comes at the size asked for, and again only when it changes") {
        val child = child(DECK)
        child.configure(400, 225)
        child.requestStill(1, 0, 200, 112)
        child.server.render().filterIsInstance<Event.Still>().single() shouldBe Event.Still(1, 0, 200, 112)
        val second = child.stillPixels()
        second.any { it != second[0] } shouldBe true
        child.server.render(1_000_000_000L).filterIsInstance<Event.Still>() shouldBe emptyList()
        child.server.apply(Command.Still(2, 0, 200, 112, 1f))
        child.server.render(2_000_000_000L).filterIsInstance<Event.Still>().single() shouldBe Event.Still(2, 0, 200, 112)
        child.stillPixels().contentEquals(second) shouldBe false
        child.server.close()
    }

    test("a thumbnail is drawn when asked for, as soon as asked, and none of a slide the deck lacks") {
        val child = child(DECK)
        child.requestThumbnail(1, 1, 192, 108) shouldBe listOf(Event.Thumbnail(1, 1, 192, 108))
        val pixels = child.thumbnail!!.read()!!
        pixels.any { it != pixels[0] } shouldBe true
        child.requestThumbnail(9, 0, 192, 108) shouldBe emptyList()
        child.server.close()
    }

    test("the deck tells which step stands for each slide") {
        server(DECK).load().shouldBeInstanceOf<Event.Loaded>().deck.slides.map { it.representativeStep } shouldContainExactly listOf(0, 1, 0)
    }

    test("sizes the parent would refuse are not drawn") {
        val child = child(DECK)
        child.server.apply(Command.Configure(MAX_FRAME_SIDE + 1, 225, 1f))
        child.server.apply(Command.Render(0L)) shouldBe listOf(Event.Unchanged(animating = false))
        child.server.close()
    }

    test("Unchanged is sent when nothing changed and the scene is idle") {
        val child = child(DECK)
        child.configure(400, 225)
        child.server.renderUntilIdle(0L)
        child.server.apply(Command.Render(10_000_000_000L)).single().shouldBeInstanceOf<Event.Unchanged>()
        child.server.close()
    }
})
