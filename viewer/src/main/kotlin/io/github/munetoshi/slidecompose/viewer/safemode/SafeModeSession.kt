package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.prepare
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/** An image the child rendered: [width] x [height] BGRA premultiplied [pixels]. */
internal class RenderedImage(val width: Int, val height: Int, val pixels: ByteArray) {
    /** The image to draw, made where the image is received: off the UI thread, in one copy. */
    val bitmap: ImageBitmap = bitmapOf(width, height, pixels)
}

private fun bitmapOf(width: Int, height: Int, pixels: ByteArray): ImageBitmap {
    val bitmap = Bitmap()
    check(bitmap.installPixels(ImageInfo.makeN32Premul(width, height), pixels, width * 4)) { "could not take a $width x $height frame" }
    return bitmap.setImmutable().asComposeImageBitmap()
}

/** A still of [frame] for the presenter's next slide. */
internal class RenderedStill(val frame: Frame, val image: RenderedImage)

/** How a session's child came to be: started, or not, and why. */
internal sealed interface ChildStart {
    /** The child runs: the session talks to it over [input] and [output], and [end] stops it once the session closes. */
    class Started(val input: DataInputStream, val output: DataOutputStream, val end: () -> Unit = {}) : ChildStart

    /** No child was started, as the deck may not be copied for one. */
    class Refused(val reason: DeckCopy.Refused) : ChildStart
}

/**
 * The parent's side of a safe-mode render child: it sends [Command]s and reads [Event]s on a
 * background thread, keeping the latest frame, still, notes and clicks for the UI thread to take.
 * Everything the child sends is untrusted data, checked by [readEvent]. The pixels come through
 * [SharedPixels] files that this side makes in [work], the child's work directory.
 *
 * [connect] starts the child, on that thread too, as it first copies the deck; until the child has
 * loaded the deck, the status is [Status.Loading]. The transport is any pair of streams, so tests
 * can drive an in-process [RenderServer]; [spawn] wires it to a sandboxed child process.
 */
internal class SafeModeSession(
    private val work: File,
    private val connect: () -> ChildStart,
) {
    /** A session with a child already started, talking over [input] and [output]; [onClosed] ends it. */
    constructor(input: DataInputStream, output: DataOutputStream, work: File, onClosed: () -> Unit = {}) :
        this(work, { ChildStart.Started(input, output, onClosed) })

    sealed interface Status {
        /** The deck is being copied, and the child is compiling and loading it. */
        data object Loading : Status

        data class Loaded(val deck: DeckInfo) : Status

        /** The deck did not compile, or the child could not start. */
        data class Failed(val diagnostics: List<Diagnostic>) : Status

        /** No child was started for the deck, for [reason]. */
        data class Refused(val reason: DeckCopy.Refused) : Status

        /** The child ended, or sent something malformed, while it should have been running. */
        data object Stopped : Status
    }

    @Volatile
    var status: Status = Status.Loading
        private set

    /** The deck as loaded, kept when the child stops later. */
    @Volatile
    var deck: DeckInfo? = null
        private set

    private val latestFrame = AtomicReference<RenderedImage?>()
    private val latestStill = AtomicReference<RenderedStill?>()
    private val notes = PendingNotes()
    private val clicks = AtomicInteger()

    /** The most recent frame of the audience's view, or null before the first one. */
    val frame: RenderedImage? get() = latestFrame.get()

    /** The most recent still, or null before the first one. */
    val still: RenderedStill? get() = latestStill.get()

    @Volatile
    private var running = true

    /** Whether the session still talks to its child: until [close], or until the child ends. */
    val isOpen: Boolean get() = running

    /** The child, once started: until then, there is nothing to send to. */
    @Volatile
    private var child: ChildStart.Started? = null

    /** Guards [running] and [child] together, as the child starts on one thread while the session may close on another. */
    private val lifecycleLock = Any()

    /** At most one [Command.Render] is outstanding; the reader clears it when a frame or Unchanged arrives. */
    @Volatile
    private var renderInFlight = false

    @Volatile
    private var animating = false

    /** Set by every command whose effect the next render must show (configure, go to, pointer, ...). */
    @Volatile
    private var needsRender = false

    /**
     * The shared pixels of the audience's frames and of stills: the size asked for last and the one
     * before, as a frame of that size may still be on its way.
     */
    private val stagePixels = PixelFiles(work, "stage")
    private val stillPixels = PixelFiles(work, "still")
    private val thumbnailPixels = PixelFiles(work, "thumbnail")

    /** One thumbnail at a time: the child draws them all into one file. */
    private val thumbnailLock = Mutex()

    /** The thumbnail asked for, and where its picture goes when it comes. */
    @Volatile
    private var pendingThumbnail: Pair<Frame, CompletableDeferred<RenderedImage>>? = null

    private val writeLock = Any()
    private val reader = Thread({ readLoop() }, "safe-mode-reader").apply { isDaemon = true }

    /** Signaled whenever there may be something to do; see [awaitWork]. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        reader.start()
    }

    private fun readLoop() {
        val child = try {
            when (val start = connect()) {
                is ChildStart.Started -> start
                is ChildStart.Refused -> {
                    status = Status.Refused(start.reason)
                    return
                }
            }
        } catch (e: Exception) {
            status = Status.Failed(listOf(Diagnostic(isError = true, message = e.message ?: e.toString())))
            return
        }
        if (!attach(child)) return
        val input = child.input
        try {
            status = when (val first = input.readEvent()) {
                is Event.Loaded -> {
                    deck = first.deck
                    Status.Loaded(first.deck)
                }
                is Event.LoadFailed -> Status.Failed(first.diagnostics)
                else -> Status.Stopped
            }
            if (status !is Status.Loaded) return
            while (running) {
                take(input.readEvent() ?: break)
                wakeUp.trySend(Unit)
            }
        } catch (_: Exception) {
            // The child died, sent something malformed, or shrank a shared pixels file: fall through to report it.
        } finally {
            // Closing the session on purpose ends the stream too; only an end nobody asked for is a stop.
            if ((running && status is Status.Loaded) || status == Status.Loading) status = Status.Stopped
            close()
        }
    }

    /** Keeps what the child sent, for the UI to take. */
    private fun take(event: Event) {
        when (event) {
            is Event.Frame -> takeFrame(event)
            is Event.Unchanged -> renderDone(event.animating)
            is Event.Still -> takeStill(event)
            is Event.Notes -> notes.add(event)
            Event.Clicked -> clicks.incrementAndGet()
            is Event.Thumbnail -> takeThumbnail(event)
            // A second load result is not expected; ignore it.
            is Event.Loaded, is Event.LoadFailed -> {}
        }
    }

    private fun takeFrame(event: Event.Frame) {
        // Copied out before the child may draw the next frame into the same pixels.
        val pixels = stagePixels.of(event.width, event.height)?.readOrThrow()
        renderDone(event.animating)
        if (pixels != null) latestFrame.set(RenderedImage(event.width, event.height, pixels))
    }

    private fun takeStill(event: Event.Still) {
        val pixels = stillPixels.of(event.width, event.height)?.readOrThrow() ?: return
        latestStill.set(RenderedStill(Frame(event.slide, event.step), RenderedImage(event.width, event.height, pixels)))
    }

    private fun takeThumbnail(event: Event.Thumbnail) {
        val (frame, answer) = pendingThumbnail ?: return
        if (frame != Frame(event.slide, event.step)) return
        val pixels = thumbnailPixels.of(event.width, event.height)?.readOrThrow() ?: return
        answer.complete(RenderedImage(event.width, event.height, pixels))
    }

    /** The pixels, or a failure that stops the session when the child shrank the file. */
    private fun SharedPixels.readOrThrow(): ByteArray = read() ?: throw IOException("shrunk")

    /**
     * Keeps [child] to talk to, unless the session closed while it started: then it stops the child
     * at once. Returns whether it kept it.
     */
    private fun attach(child: ChildStart.Started): Boolean {
        val kept = synchronized(lifecycleLock) {
            if (running) this.child = child
            running
        }
        if (!kept) shutDown(child)
        return kept
    }

    private fun renderDone(animating: Boolean) {
        this.animating = animating
        renderInFlight = false
    }

    /**
     * Suspends until there may be something to do: a command whose effect is to be drawn, an
     * animation going on, or something the child sent to take. In between, the UI sleeps.
     */
    suspend fun awaitWork() {
        wakeUp.receive()
    }

    /**
     * A picture of [frame] this size from the child, for the slide list, or null when the child
     * does not draw it in time. One at a time; the child draws it between frames.
     */
    suspend fun thumbnail(frame: Frame, width: Int, height: Int): RenderedImage? =
        thumbnailLock.withLock {
            if (!running || !thumbnailPixels.prepare(width, height)) return@withLock null
            val answer = CompletableDeferred<RenderedImage>()
            pendingThumbnail = frame to answer
            send(Command.Thumbnail(frame.slide, frame.step, width, height))
            try {
                withTimeoutOrNull(THUMBNAIL_TIMEOUT_MILLIS) { answer.await() }
            } finally {
                pendingThumbnail = null
            }
        }

    /** The notes that changed since the last call, oldest first. */
    fun takeNotes(): List<Event.Notes> = notes.takeAll()

    /** How many clicks on the slides nothing on them took since the last call. */
    fun takeClicks(): Int = clicks.getAndSet(0)

    fun configure(width: Int, height: Int, density: Float) {
        if (!running || !stagePixels.prepare(width, height)) return
        command(Command.Configure(width, height, density))
    }

    fun goTo(frame: Frame) = command(Command.GoTo(frame.slide, frame.step))

    fun pointer(type: PointerType, x: Float, y: Float, scrollY: Float = 0f) = command(Command.Pointer(type, x, y, scrollY))

    fun requestStill(frame: Frame, width: Int, height: Int, density: Float) {
        if (!running || !stillPixels.prepare(width, height)) return
        command(Command.Still(frame.slide, frame.step, width, height, density))
    }

    private fun command(command: Command) {
        needsRender = true
        send(command)
        wakeUp.trySend(Unit)
    }

    /**
     * Asks the child for the frame at [nanos] if a redraw is due and none is in flight. Called by the
     * UI at a display frame after [awaitWork]; while the child is still animating, each frame it
     * sends wakes the UI to ask for the next, so transitions play.
     */
    fun renderTick(nanos: Long) {
        if (!running || renderInFlight || !(needsRender || animating)) return
        renderInFlight = true
        needsRender = false
        send(Command.Render(nanos))
    }

    private fun send(command: Command) {
        val output = child?.output ?: return
        if (!running) return
        try {
            synchronized(writeLock) { output.writeCommand(command) }
        } catch (_: Exception) {
            close()
        }
    }

    private fun sendShutdown(output: DataOutputStream) {
        synchronized(writeLock) { output.writeCommand(Command.Shutdown) }
    }

    fun close() {
        val child = synchronized(lifecycleLock) {
            if (!running) return
            running = false
            child
        }
        stagePixels.close()
        stillPixels.close()
        thumbnailPixels.close()
        pendingThumbnail?.second?.cancel()
        // A child still starting is stopped as it is attached.
        child?.let(::shutDown)
    }

    private fun shutDown(child: ChildStart.Started) {
        runCatching { sendShutdown(child.output) }
        runCatching { child.input.close() }
        runCatching { child.output.close() }
        child.end()
    }

    companion object {
        /**
         * Starts a sandboxed child for [deck], with the files [chosen] to go with a deck of one
         * file, writing only to a new temporary directory.
         */
        fun spawn(deck: File, chosen: List<File>): SafeModeSession = spawn(deck, Files.createTempDirectory("slidecompose-safe-").toFile(), chosen)

        /**
         * Returns a session for [deck] and, on its thread, copies the deck, with the files [chosen]
         * for a deck of one file, and starts a sandboxed child that reads the copy (see
         * [DeckCopy]). [work] is a fresh private directory, the only
         * place the child may write, which holds the copy; it is removed once the session has
         * closed and the child has stopped. Child stderr (compiler and JVM messages) is drained to
         * this process's stderr.
         */
        fun spawn(deck: File, work: File, chosen: List<File> = emptyList()): SafeModeSession =
            SafeModeSession(work) { startChild(deck, chosen, work) }.also { it.start() }

        private fun startChild(deck: File, chosen: List<File>, work: File): ChildStart =
            try {
                when (val copy = copyDeck(deck, File(work, COPY_FOLDER), chosen)) {
                    is DeckCopy.Copied -> started(SafeModeLauncher.start(copy.deck, work), work)
                    is DeckCopy.Refused -> ChildStart.Refused(copy).also { deleteWorkFolder(work) }
                }
            } catch (e: Exception) {
                deleteWorkFolder(work)
                throw e
            }

        private fun started(process: Process, work: File): ChildStart.Started {
            drainToStderr(process)
            return ChildStart.Started(
                input = DataInputStream(BufferedInputStream(process.inputStream)),
                output = DataOutputStream(BufferedOutputStream(process.outputStream)),
                end = { stop(process, work) },
            )
        }

        /**
         * Stops the child for good first, so that it cannot change [work] while it goes; then,
         * off the caller's thread, which may be the UI's, deletes it.
         */
        private fun stop(process: Process, work: File) {
            process.destroyForcibly()
            process.onExit().thenRun { deleteWorkFolder(work) }
        }

        private fun drainToStderr(process: Process) {
            val thread = Thread({ copyToStderr(process) }, "safe-mode-stderr")
            thread.isDaemon = true
            thread.start()
        }

        private fun copyToStderr(process: Process) {
            runCatching { process.errorStream.copyLines(ChildOutput(System.err::println)) }
        }
    }
}

/** The most notes events waiting for the UI: see [PendingNotes]. */
internal const val MAX_PENDING_NOTES = 1_000

/**
 * The notes the child sent that the UI has not taken yet. A child sends notes only in answer to a
 * render, which the UI asks for once a display frame and then takes them, so two events wait at
 * most: the current slide's and the next one's. One that sends more than [MAX_PENDING_NOTES]
 * events, or more than two events' worth of text, before the UI takes them is flooding the parent,
 * and [add] throws to end its session.
 */
private class PendingNotes {
    private val notes = ArrayList<Event.Notes>()
    private var chars = 0L

    @Synchronized
    fun add(event: Event.Notes) {
        chars += event.notes.sumOf(String::length)
        if (notes.size >= MAX_PENDING_NOTES) throw IOException("more than $MAX_PENDING_NOTES notes waiting")
        if (chars > 2L * MAX_EVENT_CHARS) throw IOException("more than ${2L * MAX_EVENT_CHARS} chars of notes waiting")
        notes.add(event)
    }

    /** The notes, oldest first, which are then no longer waiting. */
    @Synchronized
    fun takeAll(): List<Event.Notes> {
        val taken = notes.toList()
        notes.clear()
        chars = 0
        return taken
    }
}

/** The longest line of the child's stderr printed as one, in chars; longer lines are split. */
internal const val MAX_CHILD_LINE_CHARS = 4_000

/**
 * The most of the child's stderr printed in a session, in chars. Compiler errors for a broken deck
 * and a few stack traces are tens of thousands; this is room for far more. The rest is read and
 * dropped, so that the child never waits on a full pipe.
 */
internal const val MAX_CHILD_OUTPUT_CHARS = 1_000_000

/**
 * Reads this stream of the child's stderr to its end, in blocks of a fixed size, and gives what it
 * says to [output]: one line that never ends, or one that never stops coming, takes no more memory
 * than one block and one line.
 */
internal fun InputStream.copyLines(output: ChildOutput) {
    val reader = InputStreamReader(this, Charsets.UTF_8)
    val block = CharArray(8192)
    while (true) {
        val count = reader.read(block)
        if (count < 0) break
        output.take(block, count)
    }
    output.finish()
}

/**
 * Prints the child's stderr through [print], line by line with a prefix, splitting lines longer
 * than [maxLineChars] and printing at most [maxTotalChars] in all, then saying the rest is left out.
 */
internal class ChildOutput(
    private val print: (String) -> Unit,
    private val maxLineChars: Int = MAX_CHILD_LINE_CHARS,
    private val maxTotalChars: Int = MAX_CHILD_OUTPUT_CHARS,
) {
    private val line = StringBuilder()
    private var printed = 0
    private var full = false

    /** Takes the first [count] chars of [block]. */
    fun take(block: CharArray, count: Int) {
        for (index in 0 until count) takeChar(block[index])
    }

    /** Prints what is left of a line without its end, once the stream has ended. */
    fun finish() {
        if (line.isNotEmpty()) printLine()
    }

    private fun takeChar(char: Char) {
        if (full) return
        when {
            char == '\n' -> printLine()
            char == '\r' -> {}
            else -> line.append(char)
        }
        if (line.length >= maxLineChars) printLine()
    }

    private fun printLine() {
        printed += line.length
        if (printed > maxTotalChars) {
            full = true
            print("[safe mode] (the rest of the output is left out)")
        } else {
            print("[safe mode] $line")
        }
        line.setLength(0)
    }
}

/**
 * The [SharedPixels] files of one kind: the size asked for last and the one before. Made on the UI
 * thread, read on the reader thread.
 */
private class PixelFiles(private val work: File, private val kind: String) {
    @Volatile
    private var current: SharedPixels? = null

    @Volatile
    private var previous: SharedPixels? = null

    /**
     * Makes the file for frames of this size, unless it is the current one. Returns false when the
     * size is not one the reader would accept, or the file could not be made.
     */
    fun prepare(width: Int, height: Int): Boolean {
        if (width !in 1..MAX_FRAME_SIDE || height !in 1..MAX_FRAME_SIDE) return false
        if (current?.matches(width, height) == true) return true
        val made = SharedPixels.create(work, kind, width, height) ?: return false
        previous?.let {
            it.close()
            it.file.delete()
        }
        previous = current
        current = made
        return true
    }

    /** The pixels of frames of this size, or null for a size not asked for (lately). */
    fun of(width: Int, height: Int): SharedPixels? =
        current?.takeIf { it.matches(width, height) } ?: previous?.takeIf { it.matches(width, height) }

    fun close() {
        current?.close()
        previous?.close()
    }
}

/**
 * Deletes the child's [work] folder. The child may have left links in it to anywhere, such as the
 * user's documents: they go as links, and what they point to stays.
 */
@OptIn(ExperimentalPathApi::class)
internal fun deleteWorkFolder(work: File) {
    runCatching { work.toPath().deleteRecursively() }
}

/** The folder in the child's work folder that holds the copy of the deck. */
private const val COPY_FOLDER = "deck"

/** How long the slide list waits for a thumbnail from the child. */
private const val THUMBNAIL_TIMEOUT_MILLIS = 5_000L
