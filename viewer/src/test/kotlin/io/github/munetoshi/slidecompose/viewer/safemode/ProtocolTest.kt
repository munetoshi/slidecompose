package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** What [write] puts on the wire, read back as events. */
private fun bytesOf(write: DataOutputStream.() -> Unit): DataInputStream {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).write()
    return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
}

private val deck = DeckInfo(
    title = "Deck",
    width = 1920,
    height = 1080,
    durationMillis = 600_000,
    slides = listOf(
        SlideInfo(steps = 2, durationMillis = null, weight = 1f, pointer = PointerColor.White, notes = listOf("one", "two"), representativeStep = 0),
        SlideInfo(steps = 1, durationMillis = 60_000, weight = 0.5f, pointer = PointerColor.Black, notes = emptyList()),
    ),
)

private fun roundTripCommand(command: Command) {
    bytesOf { writeCommand(command) }.readCommand() shouldBe command
}

private fun roundTripEvent(event: Event) {
    bytesOf { writeEvent(event) }.readEvent() shouldBe event
}

class ProtocolTest : FunSpec({
    test("every command survives a round trip") {
        listOf(
            Command.Configure(1920, 1080, 2f),
            Command.Render(123_456_789L),
            Command.Pointer(PointerType.PRESS, 12.5f, 34.5f),
            Command.Pointer(PointerType.SCROLL, 0f, 0f, scrollY = -3f),
            Command.GoTo(slide = 4, step = 2),
            Command.Still(slide = 1, step = 0, width = 640, height = 360, density = 2f),
            Command.Thumbnail(slide = 2, step = 1, width = 256, height = 144),
            Command.Shutdown,
        ).forEach(::roundTripCommand)
    }

    test("every event survives a round trip") {
        roundTripEvent(Event.Loaded(deck))
        roundTripEvent(Event.Loaded(deck.copy(durationMillis = null, slides = emptyList())))
        roundTripEvent(Event.LoadFailed(listOf(Diagnostic(true, "boom", "Slides.kt", 4, 24, "    slide { TitleSlide(missing) }"), Diagnostic(false, "note"))))
        roundTripEvent(Event.Frame(3840, 2160, animating = true))
        roundTripEvent(Event.Unchanged(animating = false))
        roundTripEvent(Event.Still(slide = 1, step = 0, width = 640, height = 360))
        roundTripEvent(Event.Notes(slide = 3, notes = listOf("a", "b")))
        roundTripEvent(Event.Clicked)
        roundTripEvent(Event.Thumbnail(slide = 2, step = 1, width = 256, height = 144))
    }

    test("an answer to a render is several events in a row") {
        val input = bytesOf { writeEvents(listOf(Event.Clicked, Event.Notes(0, listOf("n")), Event.Unchanged(animating = true))) }
        generateSequence { input.readEvent() }.toList() shouldBe listOf(Event.Clicked, Event.Notes(0, listOf("n")), Event.Unchanged(animating = true))
    }

    test("a diagnostic's file goes as its name only") {
        val failed = bytesOf { writeEvent(Event.LoadFailed(listOf(Diagnostic(true, "x", "/Users/someone/deck/Slides.kt", 1, 2)))) }.readEvent()
        failed shouldBe Event.LoadFailed(listOf(Diagnostic(true, "x", "Slides.kt", 1, 2)))
    }

    test("text too long for the wire is cut, not refused") {
        val long = "あ".repeat(30_000)
        val notes = bytesOf { writeEvent(Event.Notes(0, listOf(long))) }.readEvent() as Event.Notes
        notes.notes.single() shouldBe long.take(20_000)
    }

    test("end of stream reads as null") {
        DataInputStream(ByteArrayInputStream(ByteArray(0))).readCommand() shouldBe null
        DataInputStream(ByteArrayInputStream(ByteArray(0))).readEvent() shouldBe null
    }

    test("the parent refuses events out of range, as the child is not trusted") {
        // Frames larger than any display, or of no size.
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Frame(100_000, 100_000, animating = false)) }.readEvent() }
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Still(0, 0, 0, 360)) }.readEvent() }
        // A deck with slides of no steps, or a negative weight.
        shouldThrow<IllegalArgumentException> {
            bytesOf { writeEvent(Event.Loaded(deck.copy(slides = listOf(deck.slides[0].copy(steps = 0))))) }.readEvent()
        }
        shouldThrow<IllegalArgumentException> {
            bytesOf { writeEvent(Event.Loaded(deck.copy(slides = listOf(deck.slides[0].copy(weight = -1f))))) }.readEvent()
        }
        // A step standing for a slide that it does not have.
        shouldThrow<IllegalArgumentException> {
            bytesOf { writeEvent(Event.Loaded(deck.copy(slides = listOf(deck.slides[0].copy(representativeStep = 2))))) }.readEvent()
        }
        // Notes for a slide index no deck has.
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Notes(-1, emptyList())) }.readEvent() }
        // An unknown event.
        shouldThrow<IllegalArgumentException> { bytesOf { writeByte(99) }.readEvent() }
    }

    test("a large real deck's text fits in one event") {
        // A thousand slides with ten notes of 300 chars each: 3,000,000 chars in 10,000 texts.
        val slides = List(1_000) { SlideInfo(1, null, 1f, PointerColor.White, List(10) { "あ".repeat(300) }) }
        roundTripEvent(Event.Loaded(deck.copy(slides = slides)))
        roundTripEvent(Event.LoadFailed(List(1_000) { Diagnostic(true, "e".repeat(300), "Slides.kt", 1, 1, "l".repeat(200)) }))
    }

    test("an event carrying more text than the parent keeps for one is refused") {
        val tooLong = List(MAX_EVENT_CHARS / 20_000 + 1) { "x".repeat(20_000) }
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Notes(0, tooLong)) }.readEvent() }
        shouldThrow<IllegalArgumentException> {
            bytesOf { writeEvent(Event.Loaded(deck.copy(slides = listOf(deck.slides[0].copy(notes = tooLong))))) }.readEvent()
        }
        // Many slides, each within the limits for one.
        val manySlides = List(MAX_EVENT_CHARS / 20_000 + 1) { deck.slides[1].copy(notes = listOf("x".repeat(20_000))) }
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Loaded(deck.copy(slides = manySlides))) }.readEvent() }
        val diagnostics = List(MAX_EVENT_CHARS / 20_000 + 1) { Diagnostic(true, "x".repeat(20_000)) }
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.LoadFailed(diagnostics)) }.readEvent() }
    }

    test("an event carrying more texts than the parent keeps for one is refused, though they are empty") {
        val emptyNotes = List(MAX_EVENT_TEXTS / 1_000 + 1) { deck.slides[1].copy(notes = List(1_000) { "" }) }
        shouldThrow<IllegalArgumentException> { bytesOf { writeEvent(Event.Loaded(deck.copy(slides = emptyNotes))) }.readEvent() }
    }
})
