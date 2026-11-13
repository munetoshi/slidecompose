package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.components.SlideText
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.player.export.renderTransition
import io.github.munetoshi.slidecompose.player.stage.StaticSlide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

private val Big = TextStyle(fontSize = 140.sp)

/** Two slides with text of the same id: [first] at the top left, [second] at the bottom right. */
private fun twoSlides(first: @androidx.compose.runtime.Composable (Modifier) -> Unit, second: @androidx.compose.runtime.Composable (Modifier) -> Unit) =
    presentation {
        slide { Box(Modifier.fillMaxSize().padding(80.dp)) { first(Modifier.align(Alignment.TopStart)) } }
        slide { Box(Modifier.fillMaxSize().padding(80.dp)) { second(Modifier.align(Alignment.BottomEnd)) } }
    }

/** The frames of moving from the first slide to the second at 0 ms, halfway and after the end. */
private fun Presentation.transitionFrames(scale: Float = 0.25f): List<Image> = renderTransition(Frame(0, 0), Frame(1, 0), listOf(0, 450, 1500), scale)

/** The pixels of [image] as BGRA, whatever order the image keeps them in. */
private fun pixelsOf(image: Image): ByteArray =
    Bitmap().use { bitmap ->
        check(bitmap.allocPixels(ImageInfo.makeN32Premul(image.width, image.height)))
        check(image.readPixels(bitmap))
        checkNotNull(bitmap.readPixels())
    }

/** How many pixels are clearly red and clearly blue: the old and the new text. */
private fun Image.countRedAndBlue(): Pair<Int, Int> {
    val pixels = pixelsOf(this).asSequence().chunked(4) { (b, g, r) -> Triple(r.toInt() and 0xFF, g.toInt() and 0xFF, b.toInt() and 0xFF) }.toList()
    return pixels.count { (r, g, b) -> r > 180 && g < 150 && b < 150 } to pixels.count { (r, g, b) -> b > 180 && r < 150 && g < 150 }
}

/** The share of the text's pixels (dark in either image) that differ clearly between [a] and [b]. */
private fun differingShare(a: Image, b: Image): Double {
    val one = pixelsOf(a)
    val other = pixelsOf(b)
    val ink = one.indices.step(4).filter { isDark(one, it) || isDark(other, it) }
    val differing = ink.count { i -> (0..2).any { abs((one[i + it].toInt() and 0xFF) - (other[i + it].toInt() and 0xFF)) > 64 } }
    return differing.toDouble() / ink.size
}

/** Whether the BGRA pixel at [i] is dark in every channel. */
private fun isDark(pixels: ByteArray, i: Int): Boolean = (0..2).all { (pixels[i + it].toInt() and 0xFF) < 128 }

/** [frame] of [presentation] drawn on its own, full size, as [transitionFrames] draws it at scale 1. */
private fun still(presentation: Presentation, frame: Frame): Image {
    val scene = ImageComposeScene(presentation.size.width, presentation.size.height, Density(1f)) { StaticSlide(presentation, frame, Modifier.fillMaxSize()) }
    return scene.render(0).also { scene.close() }
}

private fun entry(crossfade: Boolean = false, text: String? = null): ElementLooks.Entry =
    ElementLooks.Entry("id", Scope).apply {
        this.crossfade = crossfade
        this.text = text?.let { TextLook(AnnotatedString(it), null, FontWeight.Normal, null, Color.Black) }
    }

/** Entries are matched by identity of their scope only; these tests never compare scopes. */
private object Scope : AnimatedVisibilityScope {
    override val transition: Transition<EnterExitState> get() = error("not used")
}

class ElementTransitionTest : FunSpec({
    test("matching elements cross-fade as a whole when either asks to, or when their text differs") {
        crossfadesAsWhole(entry(), entry()) shouldBe false
        crossfadesAsWhole(entry(crossfade = true), entry()) shouldBe true
        crossfadesAsWhole(entry(), entry(crossfade = true)) shouldBe true
        crossfadesAsWhole(entry(text = "One"), entry(text = "One")) shouldBe false
        crossfadesAsWhole(entry(text = "One"), entry(text = "Two")) shouldBe true
        // Text that does not say what it is (a plain Text) cannot be compared.
        crossfadesAsWhole(entry(text = "One"), entry()) shouldBe false
    }

    test("another font family or style is a change of font, another weight is not") {
        val look = TextLook(AnnotatedString("A"), FontFamily.Serif, FontWeight.Normal, null, Color.Black)
        look.copy(weight = FontWeight.Bold).changesFontFrom(look) shouldBe false
        look.copy(fontFamily = FontFamily.SansSerif).changesFontFrom(look) shouldBe true
        look.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic).changesFontFrom(look) shouldBe true
    }

    test("text that says something else cross-fades with it while it moves") {
        val deck = twoSlides(
            { SlideText("One", it, color = Color.Red, style = Big, id = "text") },
            { SlideText("Two", it, color = Color.Blue, style = Big, id = "text") },
        )
        val (start, middle, end) = deck.transitionFrames().map(Image::countRedAndBlue)
        (start.first > 0 && start.second == 0) shouldBe true
        // Both at once, halfway: the old fading out, the new fading in.
        (middle.first > 0 && middle.second > 0) shouldBe true
        (end.first == 0 && end.second > 0) shouldBe true
    }

    test("an element asking to cross-fade does so with one that does not ask") {
        val deck = twoSlides(
            { Text("One", it.elementTransition("text"), color = Color.Red, style = Big) },
            { Text("Two", it.elementTransition("text", crossfade = true), color = Color.Blue, style = Big) },
        )
        val middle = deck.transitionFrames()[1].countRedAndBlue()
        (middle.first > 0 && middle.second > 0) shouldBe true
    }

    test("without a reason to cross-fade, only the new look is drawn while it moves") {
        val deck = twoSlides(
            { Text("One", it.elementTransition("text"), color = Color.Red, style = Big) },
            { Text("Two", it.elementTransition("text"), color = Color.Blue, style = Big) },
        )
        val middle = deck.transitionFrames()[1].countRedAndBlue()
        (middle.first == 0 && middle.second > 0) shouldBe true
    }

    test("the same text in another weight and size starts as it was and ends as it is") {
        // A serif's bold is much wider than its regular weight, as in the Paper theme's titles shrinking into items.
        val title = TextStyle(fontSize = 120.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        val item = TextStyle(fontSize = 100.sp, fontFamily = FontFamily.Serif)
        val deck = twoSlides({ SlideText("Writing slides", it, style = title, id = "text") }, { SlideText("Writing slides", it, style = item, id = "text") })
        val (start, _, end) = deck.transitionFrames(scale = 1f)
        val old = still(deck, Frame(0, 0))
        val new = still(deck, Frame(1, 0))
        // The new look where the old text was, as a measure of a clear difference.
        val switched = differingShare(still(twoSlides({ SlideText("Writing slides", it, style = item.copy(fontSize = 120.sp)) }, {}), Frame(0, 0)), old)
        differingShare(start, old) shouldBeLessThan switched / 4
        differingShare(end, new) shouldBeLessThan switched / 4
    }

    test("text changing weight starts in the old weight alone and ends in the new one alone") {
        // A serif's regular strokes stick out of its bold ones here and there. Drawn under the
        // bold until the end, they vanished at once when the transition ended. Both are one size:
        // on Linux, glyphs scaled from another size come out a little different from the slide's.
        val regular = TextStyle(fontSize = 100.sp, fontFamily = FontFamily.Serif)
        val bold = regular.copy(fontWeight = FontWeight.Bold)
        val heavier = twoSlides({ SlideText("Writing slides", it, style = regular, id = "text") }, { SlideText("Writing slides", it, style = bold, id = "text") })
        val lighter = twoSlides({ SlideText("Writing slides", it, style = bold, id = "text") }, { SlideText("Writing slides", it, style = regular, id = "text") })
        // Just before the end of the 900 ms transition, and at its start.
        val nearEnd = heavier.renderTransition(Frame(0, 0), Frame(1, 0), listOf(895), 1f).single()
        val start = lighter.renderTransition(Frame(0, 0), Frame(1, 0), listOf(0), 1f).single()
        differingShare(nearEnd, still(heavier, Frame(1, 0))) shouldBeLessThan 0.005
        differingShare(start, still(lighter, Frame(0, 0))) shouldBeLessThan 0.005
    }

    test("the same text in another font starts as it was and ends as it is") {
        val serif = Big.copy(fontFamily = FontFamily.Serif)
        val sans = Big.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
        val deck = twoSlides({ SlideText("Hello", it, style = serif, id = "text") }, { SlideText("Hello", it, style = sans, id = "text") })
        // Full size: smaller, the edges of the letters are most of them.
        val (start, middle, end) = deck.transitionFrames(scale = 1f)
        val old = still(deck, Frame(0, 0))
        val new = still(deck, Frame(1, 0))
        // The new font where the old text was: what the first frame used to show.
        val switched = differingShare(still(twoSlides({ SlideText("Hello", it, style = sans) }, {}), Frame(0, 0)), old)
        // The first frame is the old slide, the old font the size it was, but for the edges of
        // letters placed one by one; the last is the new one.
        differingShare(start, old) shouldBeLessThan switched / 4
        differingShare(end, new) shouldBeLessThan switched / 4
        // On the way, it is neither.
        differingShare(middle, old) shouldBeGreaterThan switched / 4
        differingShare(middle, new) shouldBeGreaterThan switched / 4
    }
})
