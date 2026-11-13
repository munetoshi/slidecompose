package io.github.munetoshi.slidecompose.viewer.safemode

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files

/**
 * A session with a [RenderServer] for [deck] on a thread of this process, over pipes: the child's
 * protocol and threading without spawning a sandboxed process.
 */
internal fun inProcessSession(deck: File): SafeModeSession {
    // Commands flow parent -> child; events flow child -> parent. Pixel frames are large, so the pipes are roomy.
    val commandsOut = PipedOutputStream()
    val commandsIn = PipedInputStream(commandsOut, 1 shl 16)
    val eventsOut = PipedOutputStream()
    val eventsIn = PipedInputStream(eventsOut, 1 shl 20)
    // The work directory the two sides share, as the child's would be.
    val work = Files.createTempDirectory("slidecompose-test-work-").toFile().apply { deleteOnExit() }
    val server = RenderServer(deck, deck.absoluteFile.parentFile, libraries = emptyList(), work = work)
    Thread({
        runCatching { server.serve(DataInputStream(commandsIn), DataOutputStream(eventsOut)) }
    }, "test-render-server").apply { isDaemon = true }.start()
    return SafeModeSession(DataInputStream(eventsIn), DataOutputStream(commandsOut), work, onClosed = { deleteWorkFolder(work) }).also { it.start() }
}

/** Waits until [condition] holds, driving a render each poll as the windows would, or fails after [millis]. */
internal fun SafeModeSession.await(millis: Long = 20_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + millis
    var nanos = 0L
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        renderTick(nanos)
        nanos += 16_000_000L
        Thread.sleep(5)
    }
    error("condition not met within ${millis}ms; status=$status")
}
