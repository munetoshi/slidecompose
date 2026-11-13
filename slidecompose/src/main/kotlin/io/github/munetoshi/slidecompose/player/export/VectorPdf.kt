@file:OptIn(InternalComposeUiApi::class)

package io.github.munetoshi.slidecompose.player.export

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.transition.ElementIdRecorder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.Normalizer
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import org.apache.fontbox.cmap.CMap
import org.apache.fontbox.cmap.CMapParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * The skpdf tool from the jar, ready to run: see slidecompose/build.gradle.kts. `null` where there
 * is none, or where it was built for another Skia than Skiko's, whose pictures it might not read.
 * Taken out of the jar once a process, when first needed.
 */
internal val skPdfTool: File? by lazy {
    runCatching { findSkPdfTool() }
        .onFailure { System.err.println("SlideCompose: vector PDF export is not available: $it") }
        .getOrNull()
}

/** Where skpdf is in the jar. */
internal const val SK_PDF_RESOURCE = "/io/github/munetoshi/slidecompose/skpdf/macos/skpdf"

private fun findSkPdfTool(): File? {
    if (!System.getProperty("os.name").startsWith("Mac")) return null
    val bytes = Presentation::class.java.getResourceAsStream(SK_PDF_RESOURCE)?.use { it.readBytes() } ?: return null
    val tool = extractTool(bytes, "skpdf")
    val process = ProcessBuilder(tool.path, "--version").redirectErrorStream(true).start()
    val version = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (version != org.jetbrains.skiko.Version.skia) {
        System.err.println("SlideCompose: skpdf was built for Skia $version, but Skiko uses ${org.jetbrains.skiko.Version.skia}; PDFs will be images")
        return null
    }
    return tool
}

/** Read, write and run for the owner only. */
private val ownerOnly = PosixFilePermissions.fromString("rwx------")

/**
 * Writes [bytes] as the program [name] into a new temporary directory that only this user can
 * open, and returns it; both go when the process exits. A new directory each time, rather than one
 * of a name known in advance, so that no one else on the machine can have put a program of their
 * own there to be run: the temporary directory may be shared, as /tmp is.
 */
internal fun extractTool(bytes: ByteArray, name: String): File {
    val directory = Files.createTempDirectory("slidecompose-$name-", PosixFilePermissions.asFileAttribute(ownerOnly))
    // Removed in the reverse order: the program, then its directory.
    directory.toFile().deleteOnExit()
    val tool = Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(ownerOnly))
    tool.toFile().deleteOnExit()
    Files.write(tool, bytes)
    return tool.toFile()
}

/** Thrown by [exportVectorPdf] when skpdf fails. */
internal class SkPdfException(message: String) : Exception(message)

/** One page as written to skpdf. */
private class WrittenPage(val text: String, val isImage: Boolean)

/**
 * Writes [frames] as a PDF with skpdf. Each frame is recorded as a Skia picture in slide units, and
 * skpdf draws it with Skia's PDF backend: text and shapes stay vectors, and what PDF cannot express,
 * such as shaders and blurs, becomes images at [PdfExportOptions.scale]. A frame whose picture loses
 * something on the way, such as an image made only of pixels, becomes one image instead.
 */
internal fun Presentation.exportVectorPdf(
    tool: File,
    frames: List<Frame>,
    file: File,
    options: PdfExportOptions,
    assetDirectory: File?,
    onProgress: (done: Int, total: Int) -> Unit,
): PdfExportResult {
    val process = ProcessBuilder(tool.path, "--title", title, "--raster-dpi", "${72 * options.scale}", file.path)
        .redirectErrorStream(true)
        .start()
    // Read what it says as it comes, so that it never waits on a full pipe.
    val messages = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    val pages = runCatching { sendFrames(process.outputStream, frames, options, assetDirectory, onProgress) }
    // Writing fails with an IOException when skpdf has stopped; anything else is the slides' own failure.
    pages.exceptionOrNull()?.takeIf { it !is IOException }?.let { failure ->
        process.destroy()
        throw failure
    }
    if (process.waitFor() != 0) throw SkPdfException("skpdf failed: ${messages.get().trim()}")
    val written = pages.getOrThrow()
    keepIdeographsInText(file, written.joinToString("\n") { it.text })
    onProgress(frames.size, frames.size)
    return PdfExportResult(vector = true, imagePages = written.indices.asSequence().filter { written[it].isImage }.map { it + 1 }.toList())
}

/** Sends [frames] to skpdf through [stream], one page each, then the end. */
private fun Presentation.sendFrames(
    stream: OutputStream,
    frames: List<Frame>,
    options: PdfExportOptions,
    assetDirectory: File?,
    onProgress: (done: Int, total: Int) -> Unit,
): List<WrittenPage> =
    DataOutputStream(BufferedOutputStream(stream)).use { writeFrames(it, frames, options, assetDirectory, onProgress) }

private fun Presentation.writeFrames(
    out: DataOutputStream,
    frames: List<Frame>,
    options: PdfExportOptions,
    assetDirectory: File?,
    onProgress: (done: Int, total: Int) -> Unit,
): List<WrittenPage> {
    val pages = frames.mapIndexed { index, frame ->
        onProgress(index, frames.size)
        writeFrame(out, frame, options, assetDirectory)
    }
    out.writeByte('E'.code)
    return pages
}

private fun Presentation.writeFrame(
    out: DataOutputStream,
    frame: Frame,
    options: PdfExportOptions,
    assetDirectory: File?,
): WrittenPage {
    val recorded = recordFrame(frame, assetDirectory)
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    if (recorded.isComplete) {
        out.writePage('P', width, height, recorded.picture)
    } else {
        out.writePage('I', width, height, encodedFrame(frame, options, assetDirectory))
    }
    return WrittenPage(recorded.text, isImage = !recorded.isComplete)
}

/** A page for skpdf: see src/main/cpp/skpdf.cpp. */
private fun DataOutputStream.writePage(type: Char, width: Float, height: Float, data: ByteArray) {
    writeByte(type.code)
    writeFloat(width)
    writeFloat(height)
    writeInt(data.size)
    write(data)
}

/**
 * A frame recorded as a serialized Skia picture, and the text on it. [isComplete] tells whether the
 * picture still draws the same after serializing, which drops images made only of pixels.
 */
private class RecordedFrame(val picture: ByteArray, val isComplete: Boolean, val text: String)

/** Records [frame] as [renderFrame] draws it, in slide units. */
private fun Presentation.recordFrame(frame: Frame, assetDirectory: File?): RecordedFrame {
    val width = size.width
    val height = size.height
    val ids = ElementIdRecorder()
    val scene = ImageComposeScene(width, height, Density(1f)) { StillFrame(this, frame, assetDirectory, ids) }
    try {
        scene.settle().close()
        rememberElementIds(frame, ids.ids)
        val picture = PictureRecorder().run {
            composeSceneOf(scene).draw(beginRecording(Rect.makeWH(width.toFloat(), height.toFloat())).asComposeCanvas())
            finishRecordingAsPicture()
        }
        val bytes = picture.serializeToData().bytes
        val copy = Picture.makeFromData(Data.makeFromBytes(bytes))
        return RecordedFrame(bytes, copy != null && drawsAlike(picture, copy, width, height), textOf(scene))
    } finally {
        scene.close()
    }
}

/** The scene inside [scene], which can draw on any canvas. Compose has no public way to do this. */
private fun composeSceneOf(scene: ImageComposeScene): ComposeScene =
    ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(scene) as ComposeScene

/** Whether [a] and [b] draw the same at [width] x [height], but for a few pixels. */
private fun drawsAlike(a: Picture, b: Picture, width: Int, height: Int): Boolean {
    val pixelsA = pixelsOf(a, width, height)
    val pixelsB = pixelsOf(b, width, height)
    val different = (pixelsA.indices step 4).count { pixelDiffers(pixelsA, pixelsB, it) }
    return different <= width * height / 10_000
}

/** Whether a channel of the pixel at [pixel] differs clearly between [a] and [b]. */
private fun pixelDiffers(a: ByteArray, b: ByteArray, pixel: Int): Boolean =
    (pixel until pixel + 4).any { abs((a[it].toInt() and 0xFF) - (b[it].toInt() and 0xFF)) > 16 }

private fun pixelsOf(picture: Picture, width: Int, height: Int): ByteArray =
    Surface.makeRasterN32Premul(width, height).use { pixelsDrawn(it, picture) }

private fun pixelsDrawn(surface: Surface, picture: Picture): ByteArray {
    surface.canvas.drawPicture(picture)
    return surface.makeImageSnapshot().use(Image::toPixels)
}

private fun Image.toPixels(): ByteArray = Bitmap.makeFromImage(this).use { checkNotNull(it.readPixels()) }

/** The text on the slide in [scene], as its semantics report it. */
private fun textOf(scene: ImageComposeScene): String =
    scene.semanticsOwners.asSequence()
        .flatMap { it.unmergedRootSemanticsNode.withDescendants() }
        .flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText))
        }
        .joinToString("\n") { it.text }

private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

/**
 * Fonts often draw a Kangxi radical such as ⽇ (U+2F47) with the glyph of the ideograph 日 (U+65E5).
 * Without being told which character each glyph stands for, as Chromium tells it, SkPDF then writes
 * the radical, the lower code point, as the glyph's text, and copying or searching finds ⽇ for 日.
 * Compose's text does not tell it, so this maps such glyphs back to the ideographs in the fonts'
 * ToUnicode maps, unless [text], the slides' own text, has the radical itself.
 */
private fun keepIdeographsInText(file: File, text: String) {
    val used = text.codePoints().toArray().toSet()
    Loader.loadPDF(file.readBytes()).use { keepIdeographsIn(it, file, used) }
}

/** Keeps the ideographs in the fonts of [document], loaded from [file], and saves it there if that changed them. */
private fun keepIdeographsIn(document: PDDocument, file: File, used: Set<Int>) {
    val changed = document.document.getObjectsByType(COSName.FONT).asSequence()
        .mapNotNull { it.`object` as? COSDictionary }
        .count { keepIdeographs(it, used) }
    // Loaded from bytes, so writing over the file is safe.
    if (changed > 0) file.outputStream().use(document::save)
}

/** Maps radicals in [font]'s ToUnicode map back to ideographs, but those in [used]. `true` if it changed. */
private fun keepIdeographs(font: COSDictionary, used: Set<Int>): Boolean {
    val stream = font.getCOSStream(COSName.TO_UNICODE) ?: return false
    // skpdf's Type0 fonts use two-byte codes (Identity-H); its Type3 fonts one-byte codes.
    val codeLength = if (font.getCOSName(COSName.SUBTYPE) == COSName.TYPE0) 2 else 1
    val cmap = stream.createInputStream().use { CMapParser().parse(RandomAccessReadBuffer(it)) }
    val mappings = (0 until (1 shl (8 * codeLength))).mapNotNull { mappingOf(cmap, it, codeLength) }
    val kept = mappings.map { (code, text) -> code to (ideographOf(text, used) ?: text) }
    if (kept == mappings) return false
    stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(toUnicodeCMap(kept, codeLength).toByteArray(Charsets.US_ASCII)) }
    return true
}

/** The text [cmap] maps [code] to, with the code, or null when it maps it to none. */
private fun mappingOf(cmap: CMap, code: Int, codeLength: Int): Pair<Int, String>? {
    val text = cmap.toUnicode(code, codeLength) ?: return null
    return code to text
}

/** The ideograph that the radical [text] stands for, unless the slides use the radical itself. */
internal fun ideographOf(text: String, used: Set<Int>): String? {
    val codePoint = text.codePointAt(0)
    val block = Character.UnicodeBlock.of(codePoint)
    if (text.length != Character.charCount(codePoint) || codePoint in used) return null
    if (block != Character.UnicodeBlock.KANGXI_RADICALS && block != Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT) return null
    return Normalizer.normalize(text, Normalizer.Form.NFKC).takeIf { it != text }
}

private fun toUnicodeCMap(mappings: List<Pair<Int, String>>, codeLength: Int): String {
    val digits = codeLength * 2
    // At most 100 entries to a block, as the CMap format asks.
    val blocks = mappings.chunked(100).joinToString("") { bfcharBlock(it, digits) }
    return "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n" +
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n" +
        "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n" +
        "1 begincodespacerange\n<${"0".repeat(digits)}> <${"F".repeat(digits)}>\nendcodespacerange\n" +
        blocks +
        "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"
}

private fun bfcharBlock(block: List<Pair<Int, String>>, digits: Int): String =
    block.joinToString("", prefix = "${block.size} beginbfchar\n", postfix = "endbfchar\n") { (code, text) -> "<%0${digits}X> <%s>\n".format(code, utf16Hex(text)) }

private fun utf16Hex(text: String): String = text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) }
