package io.github.munetoshi.slidecompose.viewer.safemode

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.impl.BufferUtil

/**
 * Pixels that the child draws and the parent shows, shared through a file in the child's work
 * directory: frames go without being copied through a pipe, which took 35 ms for a 4K frame. The
 * pipe still says when a frame is ready and how large it is. This is the parent's side; the child
 * draws through [MappedPixels].
 *
 * The parent makes the file at its full size, and reads it with plain file reads, never through a
 * memory mapping: if the child shrank the file, a mapped read would raise SIGBUS, which compiled
 * Java code does not survive. A read only comes up short. The pixels are never trusted as more
 * than pixels.
 *
 * macOS writes the child's mapping back to disk now and then, about once every 30 seconds while
 * frames keep changing: one frame's worth each time, not every frame.
 */
internal class SharedPixels private constructor(
    val file: File,
    val width: Int,
    val height: Int,
    private val channel: FileChannel,
) : Closeable {
    private val byteCount: Int get() = width * height * 4

    fun matches(width: Int, height: Int): Boolean = width == this.width && height == this.height

    /** Copies the pixels out, BGRA premultiplied, or returns null if the child shrank the file. */
    fun read(): ByteArray? {
        val bytes = ByteArray(byteCount)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, buffer.position().toLong()) <= 0) return null
        }
        return bytes
    }

    override fun close() = channel.close()

    companion object {
        /** The file for [kind] ("stage" or "still") frames of this size in [work]. */
        fun fileOf(work: File, kind: String, width: Int, height: Int): File = File(work, "$kind-${width}x$height.pixels")

        /**
         * Makes the file, replacing any, at the size of a frame, for the child to draw into; or
         * returns null when it cannot, as when the child keeps putting something in its place.
         */
        fun create(work: File, kind: String, width: Int, height: Int): SharedPixels? {
            val file = fileOf(work, kind, width, height)
            // A link the child put there goes, not what it points to.
            file.delete()
            val channel = createExclusively(file.toPath(), width.toLong() * height * 4) ?: return null
            return SharedPixels(file, width, height, channel)
        }
    }
}

/**
 * A new file of [size] bytes at [path], open to read and write, or null when anything is at [path]
 * already, a link included. Between the viewer deleting the old file and making the new one, the
 * child can put a link there, to make the viewer cut or grow a file of the user's: this only ever
 * makes a file of its own. Once it is open, what the child puts at the path does not change it.
 */
internal fun createExclusively(path: Path, size: Long): FileChannel? {
    val channel = try {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        return null
    }
    return try {
        // Grown by writing its last byte: the rest reads as zeros.
        if (size > 0) channel.write(ByteBuffer.wrap(ByteArray(1)), size - 1)
        channel
    } catch (_: IOException) {
        channel.close()
        null
    }
}

/** The child's side of [SharedPixels]: the file mapped into memory, so that Skia draws straight into it. */
internal class MappedPixels private constructor(val width: Int, val height: Int, private val buffer: MappedByteBuffer) {
    fun matches(width: Int, height: Int): Boolean = width == this.width && height == this.height

    /** Draws [image], which must be [width] x [height], into the shared pixels. */
    fun write(image: Image) {
        val pixmap = Pixmap.make(ImageInfo.makeN32Premul(width, height), BufferUtil.getPointerFromByteBuffer(buffer), width * 4)
        pixmap.use { readInto(image, it) }
    }

    private fun readInto(image: Image, pixmap: Pixmap) = check(image.readPixels(pixmap, 0, 0, false)) { "could not read the frame's pixels" }

    /** A checksum of the pixels, to tell whether a redraw changed them. */
    fun checksum(): Long = CRC32().apply { update(buffer.duplicate().clear()) }.value

    companion object {
        /** Maps the file the parent made, or returns null when it is missing or not the size of such a frame. */
        fun open(work: File, kind: String, width: Int, height: Int): MappedPixels? {
            val file = SharedPixels.fileOf(work, kind, width, height)
            val size = width.toLong() * height * 4
            if (!file.isFile || file.length() != size) return null
            val buffer = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use {
                it.map(FileChannel.MapMode.READ_WRITE, 0, size)
            }
            return MappedPixels(width, height, buffer)
        }
    }
}

