package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The messages between the trusted parent (the viewer) and the untrusted child that compiles and
 * renders a deck in a sandbox. The parent sends [Command]s; the child answers with [Event]s.
 *
 * The parent runs the windows on a controller of its own, so it decides where the talk is and the
 * child only draws: frames of the audience's view and stills for the presenter's next slide. The
 * black screen and the shared pointer are drawn by the parent over the child's frames. The pixels
 * go through [SharedPixels]; the messages only say when they are ready and what size they are.
 *
 * The parent never trusts the child: the reader checks every count and size against a limit, text
 * is only shown, and nothing the child sends makes the parent open a file or a link. The codec is
 * deliberately small and explicit so both ends and the tests agree on every byte.
 */
sealed interface Command {
    /**
     * Sets the pixel size and density of the audience's frames: the slide's area, without
     * letterboxing. The parent has made the [SharedPixels] "stage" file of that size first.
     */
    data class Configure(val width: Int, val height: Int, val density: Float) : Command

    /**
     * Asks for the frame at [nanos] on the animation clock. The child answers with any [Event.Still],
     * [Event.Notes] and [Event.Clicked], then exactly one [Event.Frame] or [Event.Unchanged].
     */
    data class Render(val nanos: Long) : Command

    /** A mouse event in frame pixels, so that the slides' own elements can take clicks. */
    data class Pointer(val type: PointerType, val x: Float, val y: Float, val scrollY: Float = 0f) : Command

    /** Goes to [slide]'s [step], with the transition the deck gives, as the parent's controller did. */
    data class GoTo(val slide: Int, val step: Int) : Command

    /**
     * Asks for a still of [slide]'s [step] at this pixel size, for the presenter's next slide. The
     * parent has made the [SharedPixels] "still" file of that size first.
     */
    data class Still(val slide: Int, val step: Int, val width: Int, val height: Int, val density: Float) : Command

    /**
     * Asks for a picture of [slide]'s [step] at this pixel size, for the presenter's slide list.
     * The parent has made the [SharedPixels] "thumbnail" file of that size first, and asks for the
     * next only once this one has come.
     */
    data class Thumbnail(val slide: Int, val step: Int, val width: Int, val height: Int) : Command

    data object Shutdown : Command
}

enum class PointerType { MOVE, PRESS, RELEASE, EXIT, SCROLL }

/** What the parent needs of a loaded deck to run the windows: everything but what its slides look like. */
data class DeckInfo(
    val title: String,
    val width: Int,
    val height: Int,
    /** The planned length of the talk, or null. */
    val durationMillis: Long?,
    val slides: List<SlideInfo>,
)

data class SlideInfo(
    val steps: Int,
    val durationMillis: Long?,
    val weight: Float,
    val pointer: PointerColor,
    val notes: List<String>,
    /** The step that stands for the slide, in its slide list: see [io.github.munetoshi.slidecompose.deck.Slide.representativeStep]. */
    val representativeStep: Int = steps - 1,
)

sealed interface Event {
    data class Loaded(val deck: DeckInfo) : Event

    /** The deck did not compile. A diagnostic's file is only its name. */
    data class LoadFailed(val diagnostics: List<Diagnostic>) : Event

    /**
     * A frame of the audience's view is in the "stage" [SharedPixels] of this size. [animating] is
     * true while the scene still has pending animation, so the parent keeps asking.
     */
    data class Frame(val width: Int, val height: Int, val animating: Boolean) : Event

    /** Nothing changed since the last frame; the parent keeps showing it. */
    data class Unchanged(val animating: Boolean) : Event

    /** A still of [slide]'s [step] is in the "still" [SharedPixels] of this size, answering [Command.Still]. */
    data class Still(val slide: Int, val step: Int, val width: Int, val height: Int) : Event

    /** The notes of [slide] now, when they differ from the ones sent before: notes can follow the deck's state. */
    data class Notes(val slide: Int, val notes: List<String>) : Event

    /** A click on the slides that nothing on them took. The parent decides what it does, as for its own windows. */
    data object Clicked : Event

    /** A picture of [slide]'s [step] is in the "thumbnail" [SharedPixels] of this size, answering [Command.Thumbnail]. */
    data class Thumbnail(val slide: Int, val step: Int, val width: Int, val height: Int) : Event
}

/** The largest frame side in pixels the parent accepts: beyond 8K, and a frame is 256 MB. */
const val MAX_FRAME_SIDE = 8192

private const val MAX_SLIDE_SIDE = 100_000
private const val MAX_SLIDES = 10_000
private const val MAX_STEPS = 10_000
private const val MAX_TEXTS = 1_000

/**
 * The longest text sent, in chars. `writeUTF` takes at most 65535 bytes, and a char takes up to
 * three; longer notes and messages are cut.
 */
private const val MAX_TEXT_CHARS = 20_000

/**
 * The most text one event may carry in all, in chars: the title and every slide's notes of an
 * [Event.Loaded], the diagnostics of an [Event.LoadFailed], the notes of an [Event.Notes]. The
 * counts and lengths above alone would let a child send hundreds of gigabytes in one event; this
 * keeps one event to a few megabytes. A large real deck, a thousand slides with a page of notes
 * each, is a few hundred thousand chars, and a thousand compiler errors with their lines a few
 * hundred thousand more; this is ten times that. A child that sends more has its session ended.
 */
const val MAX_EVENT_CHARS = 4_000_000

/**
 * The most texts one event may carry in all. Empty notes cost no chars, but each is still an
 * object to keep: without this, 10,000 slides of 1,000 empty notes each would be ten million.
 * A thousand slides with twenty notes each is twenty thousand.
 */
const val MAX_EVENT_TEXTS = 200_000

/** A one-byte tag per message, so a corrupt or truncated stream fails loudly instead of silently. */
private enum class CommandTag { CONFIGURE, RENDER, POINTER, GO_TO, STILL, THUMBNAIL, SHUTDOWN }

private enum class EventTag { LOADED, LOAD_FAILED, FRAME, UNCHANGED, STILL, NOTES, CLICKED, THUMBNAIL }

fun DataOutputStream.writeCommand(command: Command) {
    when (command) {
        is Command.Configure -> {
            writeByte(CommandTag.CONFIGURE.ordinal)
            writeInt(command.width); writeInt(command.height); writeFloat(command.density)
        }
        is Command.Render -> {
            writeByte(CommandTag.RENDER.ordinal); writeLong(command.nanos)
        }
        is Command.Pointer -> {
            writeByte(CommandTag.POINTER.ordinal)
            writeByte(command.type.ordinal); writeFloat(command.x); writeFloat(command.y); writeFloat(command.scrollY)
        }
        is Command.GoTo -> {
            writeByte(CommandTag.GO_TO.ordinal); writeInt(command.slide); writeInt(command.step)
        }
        is Command.Still -> {
            writeByte(CommandTag.STILL.ordinal)
            writeInt(command.slide); writeInt(command.step)
            writeInt(command.width); writeInt(command.height); writeFloat(command.density)
        }
        is Command.Thumbnail -> {
            writeByte(CommandTag.THUMBNAIL.ordinal)
            writeInt(command.slide); writeInt(command.step); writeInt(command.width); writeInt(command.height)
        }
        Command.Shutdown -> writeByte(CommandTag.SHUTDOWN.ordinal)
    }
    flush()
}

/** Reads one command, or returns null at end of stream. */
fun DataInputStream.readCommand(): Command? {
    val tag = read()
    if (tag < 0) return null
    return when (CommandTag.entries[tag]) {
        CommandTag.CONFIGURE -> Command.Configure(readInt(), readInt(), readFloat())
        CommandTag.RENDER -> Command.Render(readLong())
        CommandTag.POINTER -> Command.Pointer(PointerType.entries[readByte().toInt()], readFloat(), readFloat(), readFloat())
        CommandTag.GO_TO -> Command.GoTo(readInt(), readInt())
        CommandTag.STILL -> Command.Still(readInt(), readInt(), readInt(), readInt(), readFloat())
        CommandTag.THUMBNAIL -> Command.Thumbnail(readInt(), readInt(), readInt(), readInt())
        CommandTag.SHUTDOWN -> Command.Shutdown
    }
}

/** Writes the events of one answer and flushes once, after the last. */
fun DataOutputStream.writeEvents(events: List<Event>) {
    events.forEach { writeEventBody(it) }
    flush()
}

fun DataOutputStream.writeEvent(event: Event) = writeEvents(listOf(event))

private fun DataOutputStream.writeEventBody(event: Event) {
    when (event) {
        is Event.Loaded -> {
            writeByte(EventTag.LOADED.ordinal)
            writeDeck(event.deck)
        }
        is Event.LoadFailed -> {
            writeByte(EventTag.LOAD_FAILED.ordinal)
            val sent = event.diagnostics.take(MAX_TEXTS)
            writeInt(sent.size)
            sent.forEach { writeDiagnostic(it) }
        }
        is Event.Frame -> {
            writeByte(EventTag.FRAME.ordinal)
            writeInt(event.width); writeInt(event.height); writeBoolean(event.animating)
        }
        is Event.Unchanged -> {
            writeByte(EventTag.UNCHANGED.ordinal); writeBoolean(event.animating)
        }
        is Event.Still -> {
            writeByte(EventTag.STILL.ordinal)
            writeInt(event.slide); writeInt(event.step); writeInt(event.width); writeInt(event.height)
        }
        is Event.Notes -> {
            writeByte(EventTag.NOTES.ordinal)
            writeInt(event.slide); writeTexts(event.notes)
        }
        Event.Clicked -> writeByte(EventTag.CLICKED.ordinal)
        is Event.Thumbnail -> {
            writeByte(EventTag.THUMBNAIL.ordinal)
            writeInt(event.slide); writeInt(event.step); writeInt(event.width); writeInt(event.height)
        }
    }
}

/**
 * Reads one event, or returns null at end of stream. Throws [java.io.IOException] (or
 * [IllegalArgumentException]) on anything malformed or out of range, which ends the session.
 */
fun DataInputStream.readEvent(): Event? {
    val tag = read()
    if (tag < 0) return null
    return when (EventTag.entries.getOrNull(tag) ?: throw IllegalArgumentException("unknown event $tag")) {
        EventTag.LOADED -> Event.Loaded(readDeck(TextBudget()))
        EventTag.LOAD_FAILED -> Event.LoadFailed(readDiagnostics(TextBudget()))
        EventTag.FRAME -> Event.Frame(readFrameSide(), readFrameSide(), animating = readBoolean())
        EventTag.UNCHANGED -> Event.Unchanged(readBoolean())
        EventTag.STILL -> Event.Still(readIndex(MAX_SLIDES), readIndex(MAX_STEPS), readFrameSide(), readFrameSide())
        EventTag.NOTES -> Event.Notes(readIndex(MAX_SLIDES), readTexts(TextBudget()))
        EventTag.CLICKED -> Event.Clicked
        EventTag.THUMBNAIL -> Event.Thumbnail(readIndex(MAX_SLIDES), readIndex(MAX_STEPS), readFrameSide(), readFrameSide())
    }
}

private fun DataOutputStream.writeDeck(deck: DeckInfo) {
    writeText(deck.title)
    writeInt(deck.width); writeInt(deck.height)
    writeLong(deck.durationMillis ?: -1L)
    writeInt(deck.slides.size)
    deck.slides.forEach { slide ->
        writeInt(slide.steps)
        writeLong(slide.durationMillis ?: -1L)
        writeFloat(slide.weight)
        writeByte(slide.pointer.ordinal)
        writeTexts(slide.notes)
        writeInt(slide.representativeStep)
    }
}

private fun DataInputStream.readDeck(budget: TextBudget): DeckInfo =
    DeckInfo(
        title = readText(budget),
        width = readInRange(1..MAX_SLIDE_SIDE),
        height = readInRange(1..MAX_SLIDE_SIDE),
        durationMillis = readDuration(),
        slides = List(readInRange(0..MAX_SLIDES)) { readSlide(budget) },
    )

private fun DataInputStream.readSlide(budget: TextBudget): SlideInfo {
    val steps = readInRange(1..MAX_STEPS)
    return SlideInfo(
        steps = steps,
        durationMillis = readDuration(),
        weight = readFloat().also { require(it.isFinite() && it >= 0f) { "bad weight $it" } },
        pointer = PointerColor.entries.getOrNull(readByte().toInt()) ?: throw IllegalArgumentException("bad pointer"),
        notes = readTexts(budget),
        representativeStep = readInRange(0 until steps),
    )
}

/** A duration in milliseconds, or -1 for none. */
private fun DataInputStream.readDuration(): Long? =
    readLong().let {
        require(it >= -1L) { "bad duration $it" }
        if (it == -1L) null else it
    }

private fun DataOutputStream.writeDiagnostic(diagnostic: Diagnostic) {
    writeBoolean(diagnostic.isError)
    writeText(diagnostic.message)
    writeText(diagnostic.file?.let { File(it).name }.orEmpty())
    writeInt(diagnostic.line); writeInt(diagnostic.column)
    writeText(diagnostic.lineContent.orEmpty())
}

private fun DataInputStream.readDiagnostics(budget: TextBudget): List<Diagnostic> =
    List(readInRange(0..MAX_TEXTS)) { readDiagnostic(budget) }

private fun DataInputStream.readDiagnostic(budget: TextBudget): Diagnostic =
    Diagnostic(
        isError = readBoolean(),
        message = readText(budget),
        file = readText(budget).ifEmpty { null },
        line = readInt(),
        column = readInt(),
        lineContent = readText(budget).ifEmpty { null },
    )

/** A frame's width or height, at most [MAX_FRAME_SIDE]. */
private fun DataInputStream.readFrameSide(): Int = readInRange(1..MAX_FRAME_SIDE)

private fun DataOutputStream.writeText(text: String) = writeUTF(text.take(MAX_TEXT_CHARS))

private fun DataOutputStream.writeTexts(texts: List<String>) {
    val sent = texts.take(MAX_TEXTS)
    writeInt(sent.size)
    sent.forEach { writeText(it) }
}

private fun DataInputStream.readTexts(budget: TextBudget): List<String> = List(readInRange(0..MAX_TEXTS)) { readText(budget) }

/** One text, which [budget] must still have room for. */
private fun DataInputStream.readText(budget: TextBudget): String = budget.spend(readUTF())

/**
 * The text that the event being read may still carry: see [MAX_EVENT_CHARS] and [MAX_EVENT_TEXTS].
 * Only the reader counts, as the parent is the one to protect; real decks stay far below both.
 */
private class TextBudget {
    private var chars = MAX_EVENT_CHARS
    private var texts = MAX_EVENT_TEXTS

    /** Returns [text], counted against the budget, or throws when the event has carried too much. */
    fun spend(text: String): String {
        chars -= text.length
        texts -= 1
        require(chars >= 0) { "an event of more than $MAX_EVENT_CHARS chars" }
        require(texts >= 0) { "an event of more than $MAX_EVENT_TEXTS texts" }
        return text
    }
}

private fun DataInputStream.readIndex(limit: Int): Int = readInRange(0 until limit)

private fun DataInputStream.readInRange(range: IntRange): Int =
    readInt().also { require(it in range) { "$it is not in $range" } }
