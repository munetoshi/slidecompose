package io.github.munetoshi.slidecompose.viewer.safemode

import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Session") {
    slide(notes = "hello") { TitleSlide("One") }
    slide { TitleSlide("Two") }
}
"""

private const val BROKEN = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Session") {
    slide { TitleSlide(missing) }
}
"""

/** A session reading a child's answers from [bytes], as if a child had written them and ended. */
private fun sessionReading(bytes: ByteArray): SafeModeSession =
    SafeModeSession(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()), Files.createTempDirectory("slidecompose-test-").toFile())
        .also { it.start() }

/** A session with a child in this process for a deck of [source]. */
private fun TestConfiguration.session(source: String): SafeModeSession = inProcessSession(write(tempdir(), "Slides.kt", source))

/** A [session] that has loaded its deck and drawn a first 400 x 225 frame. */
private fun TestConfiguration.loadedSession(source: String = DECK): SafeModeSession =
    session(source).apply {
        await { status is SafeModeSession.Status.Loaded }
        configure(400, 225, 1f)
        await { frame != null }
    }

private val oneSlide = DeckInfo("Fake", 1920, 1080, null, listOf(SlideInfo(1, null, 1f, PointerColor.White, emptyList())))

/**
 * Drives [SafeModeSession] against an in-process [RenderServer] (see [inProcessSession]), so the
 * session's threading, state and render handshake are tested without spawning a process.
 */
class SafeModeSessionTest : FunSpec({

    test("the session reports the loaded deck") {
        val session = session(DECK)
        session.await { session.status is SafeModeSession.Status.Loaded }
        val deck = session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck
        deck.title shouldBe "Session"
        deck.slides.map { it.notes } shouldBe listOf(listOf("hello"), emptyList())
        session.deck shouldBe deck
        session.close()
    }

    test("a broken deck is reported as failed with the compiler errors") {
        val session = session(BROKEN)
        session.await { session.status is SafeModeSession.Status.Failed }
        val failed = session.status.shouldBeInstanceOf<SafeModeSession.Status.Failed>()
        failed.diagnostics.any { "missing" in it.message } shouldBe true
        session.deck shouldBe null
        session.close()
    }

    test("configuring then rendering yields a non-blank frame") {
        val session = loadedSession()
        val frame = session.frame!!
        frame.width shouldBe 400
        frame.height shouldBe 225
        frame.pixels.size shouldBe 400 * 225 * 4
        frame.pixels.any { it != frame.pixels[0] } shouldBe true
        session.close()
    }

    test("going to another slide draws it") {
        val session = loadedSession()
        val first = session.frame!!.pixels
        session.goTo(Frame(1, 0))
        session.await { !session.frame!!.pixels.contentEquals(first) }
        session.close()
    }

    test("a click the slides do not take comes back to be handled here") {
        val session = loadedSession()
        session.pointer(PointerType.MOVE, 200f, 112f)
        session.pointer(PointerType.PRESS, 200f, 112f)
        session.pointer(PointerType.RELEASE, 200f, 112f)
        var clicks = 0
        session.await {
            clicks += session.takeClicks()
            clicks > 0
        }
        clicks shouldBe 1
        session.close()
    }

    test("a still asked for arrives with its frame") {
        val session = loadedSession()
        session.requestStill(Frame(1, 0), 200, 112, 1f)
        session.await { session.still != null }
        val still = session.still!!
        still.frame shouldBe Frame(1, 0)
        still.image.width shouldBe 200
        session.close()
    }

    test("a thumbnail asked for comes from the child at that size") {
        val session = loadedSession()
        val picture = session.thumbnail(Frame(1, 0), 192, 108)!!
        picture.width shouldBe 192
        picture.height shouldBe 108
        picture.pixels.any { it != picture.pixels[0] } shouldBe true
        session.close()
    }

    test("notes are taken in the order they came") {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeEvents(listOf(Event.Loaded(oneSlide), Event.Notes(0, listOf("a")), Event.Notes(0, listOf("b"))))
        val session = sessionReading(bytes.toByteArray())
        session.await { session.status != SafeModeSession.Status.Loading && session.status !is SafeModeSession.Status.Loaded }
        session.takeNotes() shouldBe listOf(Event.Notes(0, listOf("a")), Event.Notes(0, listOf("b")))
        session.takeNotes() shouldBe emptyList()
    }

    test("a child that floods the parent with notes the UI has not taken has stopped") {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeEvents(listOf(Event.Loaded(oneSlide)) + List(MAX_PENDING_NOTES + 1) { Event.Notes(0, emptyList()) })
        val session = sessionReading(bytes.toByteArray())
        session.await { session.status == SafeModeSession.Status.Stopped }
        session.takeNotes().size shouldBe MAX_PENDING_NOTES
    }

    test("a child's stderr is printed in lines of a bounded length, up to a bounded total, and read to its end") {
        val printed = ArrayList<String>()
        val stream = EndlessLine(length = 50_000_000)
        stream.copyLines(ChildOutput(printed::add, maxLineChars = 1_000, maxTotalChars = 10_000))
        stream.left shouldBe 0L
        printed.dropLast(1).forEach { it shouldBe "[safe mode] " + "x".repeat(1_000) }
        printed.size shouldBe 11
        printed.last() shouldBe "[safe mode] (the rest of the output is left out)"
    }

    test("a child's stderr is printed line by line, with a last line that has no end") {
        val printed = ArrayList<String>()
        "one\r\ntwo\n\nthree".byteInputStream().copyLines(ChildOutput(printed::add))
        printed shouldBe listOf("[safe mode] one", "[safe mode] two", "[safe mode] ", "[safe mode] three")
    }

    test("a child that ends or sends something malformed has stopped") {
        val ended = ByteArrayOutputStream().also { DataOutputStream(it).writeEvent(Event.Loaded(oneSlide)) }.toByteArray()
        sessionReading(ended).apply { await { status == SafeModeSession.Status.Stopped } }.deck shouldBe oneSlide
        sessionReading(ended + byteArrayOf(99)).apply { await { status == SafeModeSession.Status.Stopped } }
        sessionReading(byteArrayOf()).apply { await { status == SafeModeSession.Status.Stopped } }.deck shouldBe null
    }

    test("a child that shrinks the shared pixels under the copy has stopped, and the parent lives on") {
        val work = Files.createTempDirectory("slidecompose-test-").toFile()
        val toParent = PipedOutputStream()
        val session = SafeModeSession(DataInputStream(PipedInputStream(toParent, 1 shl 16)), DataOutputStream(ByteArrayOutputStream()), work)
        session.start()
        val child = DataOutputStream(toParent)
        child.writeEvent(Event.Loaded(oneSlide))
        session.await { session.status is SafeModeSession.Status.Loaded }
        session.configure(640, 360, 1f)
        RandomAccessFile(SharedPixels.fileOf(work, "stage", 640, 360), "rw").use { it.setLength(0) }
        child.writeEvent(Event.Frame(640, 360, animating = false))
        session.await { session.status == SafeModeSession.Status.Stopped }
        session.frame shouldBe null
    }

    test("frames of a size not asked for are not taken") {
        val toParent = PipedOutputStream()
        val session = SafeModeSession(
            DataInputStream(PipedInputStream(toParent, 1 shl 16)),
            DataOutputStream(ByteArrayOutputStream()),
            Files.createTempDirectory("slidecompose-test-").toFile(),
        )
        session.start()
        val child = DataOutputStream(toParent)
        child.writeEvents(listOf(Event.Loaded(oneSlide), Event.Frame(640, 360, animating = false), Event.Clicked))
        var clicks = 0
        session.await {
            clicks += session.takeClicks()
            clicks > 0
        }
        session.frame shouldBe null
        session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>()
        session.close()
    }

    test("a received image becomes a bitmap of its size, with its pixels") {
        val image = RenderedImage(4, 2, ByteArray(32) { it.toByte() })
        image.bitmap.width shouldBe 4
        image.bitmap.height shouldBe 2
        image.bitmap.asSkiaBitmap().readPixels()!!.toList() shouldBe List(32) { it.toByte() }
    }

    test("a deck that may not be copied for a child is refused, with no child to talk to") {
        val session = SafeModeSession(tempdir()) { ChildStart.Refused(DeckCopy.HardLink("Talk/photo.png")) }
        session.start()
        session.await { session.status != SafeModeSession.Status.Loading }
        session.status shouldBe SafeModeSession.Status.Refused(DeckCopy.HardLink("Talk/photo.png"))
        // Nothing to send to: commands go nowhere, and closing is fine.
        session.goTo(Frame(1, 0))
        session.close()
    }

    test("a child that cannot start fails the session, saying why") {
        val session = SafeModeSession(tempdir()) { throw IOException("no sandbox here") }
        session.start()
        session.await { session.status != SafeModeSession.Status.Loading }
        session.status shouldBe SafeModeSession.Status.Failed(listOf(Diagnostic(isError = true, message = "no sandbox here")))
    }

    test("a session closed while its child starts stops the child once it has started") {
        val starting = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val session = SafeModeSession(tempdir()) {
            starting.await()
            ChildStart.Started(DataInputStream(PipedInputStream(PipedOutputStream())), DataOutputStream(ByteArrayOutputStream()), end = { ended.countDown() })
        }
        session.start()
        session.close()
        ended.count shouldBe 1L
        starting.countDown()
        ended.await(10, TimeUnit.SECONDS) shouldBe true
    }

    test("closing the session on purpose is not a stop") {
        val session = loadedSession()
        session.close()
        Thread.sleep(200)
        session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>()
    }
})

/** A stream of one line of [length] x's that does not end before the stream does, as a child might write. */
private class EndlessLine(length: Long) : InputStream() {
    /** How many bytes are still to read. */
    var left = length
        private set

    override fun read(): Int {
        if (left == 0L) return -1
        left--
        return 'x'.code
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (left == 0L) return -1
        val read = minOf(count.toLong(), left).toInt()
        buffer.fill('x'.code.toByte(), offset, offset + read)
        left -= read
        return read
    }
}
