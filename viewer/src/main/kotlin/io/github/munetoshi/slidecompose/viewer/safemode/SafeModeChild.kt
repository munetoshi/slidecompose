package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.viewer.compile.deckLibraries
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Runs this process as the untrusted render child: it compiles and renders [deckPath] off-screen and
 * answers [Command]s from the parent over stdio. It never opens a window. Started by
 * [SafeModeLauncher] inside the sandbox; see [io.github.munetoshi.slidecompose.viewer.main]'s dispatch.
 *
 * Stdout (file descriptor 1) carries the binary protocol and nothing else, so we point
 * [System.out] at stderr first: a stray `println`, from the compiler or the deck, would otherwise
 * corrupt the stream. The parent treats everything the child writes as untrusted data anyway.
 */
internal fun runSandboxedChild(deckPath: String): Nothing {
    val toParent = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
    val fromParent = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`)))
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

    System.setProperty("java.awt.headless", "true")
    val deck = File(deckPath)
    val assets = if (deck.isDirectory) deck else deck.absoluteFile.parentFile
    // The launcher points the temporary directory at the work directory, the one place the child may write.
    val server = RenderServer(deck, assets, deckLibraries(deck), work = File(System.getProperty("java.io.tmpdir")))
    try {
        server.serve(fromParent, toParent)
    } finally {
        server.close()
    }
    exitProcess(0)
}
