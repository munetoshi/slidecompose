package io.github.munetoshi.slidecompose.deck.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.deck.transition.LocalStepRecorder
import java.io.File
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit
import org.jetbrains.skia.svg.SVGPreserveAspectRatio
import org.jetbrains.skia.svg.SVGPreserveAspectRatioAlign

/**
 * The directory that relative asset paths are resolved against. The viewer app sets it to the
 * directory of the loaded slides; `runPresentation` takes it as a parameter.
 */
val LocalAssetDirectory = staticCompositionLocalOf { File(System.getProperty("user.dir")) }

/** Resolves [path] against [LocalAssetDirectory] unless it is absolute. */
@Composable
fun assetFile(path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(LocalAssetDirectory.current, path)
}

/**
 * Loads an image (PNG, JPEG, WebP, GIF, BMP, ICO or SVG) relative to [LocalAssetDirectory]. In vector
 * PDFs, SVGs stay vectors and the other images are stored as their files have them.
 */
@Composable
fun assetPainter(path: String): Painter {
    // Counting a slide's steps needs no images, and may run without the asset directory.
    if (LocalStepRecorder.current != null) return remember { ColorPainter(Color.Transparent) }
    val file = assetFile(path)
    val still = LocalStillImage.current
    return remember(file, file.lastModified(), still) { if (still) loadStillPainter(file) else loadPainter(file) }
}

/** An [Image] loaded with [assetPainter]. */
@Composable
fun AssetImage(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
) {
    Image(
        painter = assetPainter(path),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
    )
}

/**
 * A font from a file, e.g. for a [SlideTheme]'s `fontFamily`:
 * ```
 * fontFamily = FontFamily(themeFont("fonts/Inter-Regular.ttf"), themeFont("fonts/Inter-Bold.ttf", FontWeight.Bold))
 * ```
 * A relative [path] is resolved against the folder of the theme file that calls this, or in a deck
 * against the deck's folder, like [AssetImage]. Elsewhere it is the working directory.
 */
fun themeFont(path: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
    val file = sourceRelativeFile(path)
    require(file.isFile) { "Font not found: $file" }
    return androidx.compose.ui.text.platform.Font(file, weight, style)
}

/**
 * Implemented by the class loaders of compiled decks and themes, such as the viewer app's, so that
 * [themeFont] finds files next to the code that names them.
 */
interface SourceDirectoryProvider {
    val sourceDirectory: File
}

/** [path] resolved against the folder of the nearest caller loaded by a [SourceDirectoryProvider]. */
private fun sourceRelativeFile(path: String): File {
    val file = File(path)
    if (file.isAbsolute) return file
    val directory = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
        frames.map { (it.declaringClass.classLoader as? SourceDirectoryProvider)?.sourceDirectory }
            .filter { it != null }
            .findFirst()
            .orElse(null)
    }
    return File(directory ?: File(System.getProperty("user.dir")), path)
}

@Suppress("DEPRECATION")
private fun loadPainter(file: File): Painter {
    require(file.isFile) { "Asset not found: $file" }
    return if (file.extension.equals("svg", ignoreCase = true)) {
        // With density 1, the SVG's intrinsic size is in slide units.
        file.inputStream().use { androidx.compose.ui.res.loadSvgPainter(it, Density(1f)) }
    } else {
        val image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
        BitmapPainter(image.toComposeImageBitmap())
    }
}

/**
 * Like [loadPainter], but drawing straight from the SVG or the image file's data rather than from
 * pixels made of it, which a recorded picture would drop: see [exportVectorPdf].
 */
private fun loadStillPainter(file: File): Painter {
    require(file.isFile) { "Asset not found: $file" }
    val data = org.jetbrains.skia.Data.makeFromBytes(file.readBytes())
    return if (file.extension.equals("svg", ignoreCase = true)) {
        SvgPainter(SVGDOM(data))
    } else {
        EncodedImagePainter(org.jetbrains.skia.Image.makeFromEncoded(data.bytes))
    }
}

/** Draws an SVG as vectors, at its own size in slide units, as Compose's SVG painter at density 1 does. */
private class SvgPainter(private val dom: SVGDOM) : Painter() {
    private val root = dom.root
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size = run {
        val width = root?.width?.withUnit(SVGLengthUnit.PX)?.value ?: 0f
        val height = root?.height?.withUnit(SVGLengthUnit.PX)?.value ?: 0f
        if (width == 0f && height == 0f) Size.Unspecified else Size(width, height)
    }

    init {
        if (root?.viewBox == null && intrinsicSize.isSpecified) {
            root?.viewBox = Rect.makeWH(intrinsicSize.width, intrinsicSize.height)
        }
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            val layered = alpha < 1f || colorFilter != null
            if (layered) {
                canvas.saveLayer(size.toRect(), Paint().also { it.alpha = alpha; it.colorFilter = colorFilter })
            }
            root?.width = SVGLength(size.width, SVGLengthUnit.PX)
            root?.height = SVGLength(size.height, SVGLengthUnit.PX)
            root?.preserveAspectRatio = SVGPreserveAspectRatio(SVGPreserveAspectRatioAlign.NONE)
            dom.render(canvas.nativeCanvas)
            if (layered) canvas.restore()
        }
    }
}

/** Draws an image decoded from its file's data, which goes into recorded pictures and PDFs as it is. */
private class EncodedImagePainter(private val image: org.jetbrains.skia.Image) : Painter() {
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize = Size(image.width.toFloat(), image.height.toFloat())

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val paint = Paint().also { it.alpha = alpha; it.colorFilter = colorFilter }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(size.width, size.height),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                paint.skiaPaint,
                true,
            )
        }
    }
}
