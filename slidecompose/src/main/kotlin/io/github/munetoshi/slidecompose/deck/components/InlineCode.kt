package io.github.munetoshi.slidecompose.deck.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideTheme
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.deck.transition.ElementTransitionMode
import io.github.munetoshi.slidecompose.deck.transition.TextLook
import io.github.munetoshi.slidecompose.deck.transition.TextMorphDrawing
import io.github.munetoshi.slidecompose.deck.transition.elementTextMorph
import io.github.munetoshi.slidecompose.deck.transition.elementTransition
import kotlin.math.roundToInt

/**
 * [text] with the parts between backticks styled as inline code, like in Markdown:
 * "Read `step` in the slide". The backticks are removed. [SlideText] (which [Bullet],
 * [TitleSlide] and [TitleAndContent] use) also draws a rounded background behind them, unless the
 * theme's [inlineCode][SlideTheme.inlineCode] has none.
 */
@Composable
fun slideText(text: String): AnnotatedString {
    val theme = LocalSlideTheme.current
    return remember(text, theme) { parseInlineCode(text, theme) }
}

/**
 * A [Text] that shows inline code written between backticks, see [slideText].
 *
 * @param id an [elementTransition] id. With one, moving from the same text in another font,
 *   weight or color, e.g. from an agenda item to a bold title in the heading font, the characters
 *   change on their way; moving from other text, the two cross-fade.
 */
@Composable
fun SlideText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    id: Any? = null,
) = SlideText(slideText(text), modifier, color, style, textAlign, id)

/**
 * A [Text] that draws the background of inline code made with [slideText]. See the other overload
 * for [id]. A line with inline code is as tall as the same line without, so that what is under it
 * stays where it is from slide to slide.
 */
@Composable
fun SlideText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    id: Any? = null,
) {
    val theme = LocalSlideTheme.current
    val background = inlineCodeBackground(theme)
    val scale = theme.inlineCode.scale
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val look = TextLook(
        text = text,
        fontFamily = style.fontFamily,
        weight = style.fontWeight ?: FontWeight.Normal,
        fontStyle = style.fontStyle,
        color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
    )
    // Coming in from the same text in another font, weight or color, the characters move one by
    // one and change on the way; see TextMorphDrawing. Other text cross-fades as a whole.
    val morph = elementTextMorph(id, look)
    val measurer = rememberTextMeasurer()
    val morphDrawing = remember(morph, measurer) { morph?.let { TextMorphDrawing(it, measurer) } }
    Text(
        text,
        modifier
            .then(if (id == null) Modifier else Modifier.elementTransition(id, ElementTransitionMode.Scale, crossfade = false, zIndex = 0f, text = look))
            .then(if (text.hasInlineCode()) Modifier.asTallAsPlain(text.text, style, measurer) else Modifier)
            .drawBehind { if (background != null) layout?.let { drawInlineCodeBackgrounds(it, background, scale) } }
            .drawWithContent {
                val textLayout = layout
                if (morphDrawing == null || textLayout == null) {
                    drawContent()
                } else {
                    with(morphDrawing) { draw(textLayout) }
                }
            },
        color = color,
        style = style,
        textAlign = textAlign,
        onTextLayout = { layout = it },
    )
}

private const val InlineCodeTag = "slidecompose.inline-code"

private fun AnnotatedString.hasInlineCode(): Boolean = hasStringAnnotations(InlineCodeTag, 0, length)

/**
 * Lays the text out as tall as [plain] would be in [style], one line of it, with its baseline where
 * that text's is: code set smaller than the text around it makes the line taller, by how much
 * depending on the fonts. Text of more than one line keeps its own height.
 */
private fun Modifier.asTallAsPlain(plain: String, style: TextStyle, measurer: TextMeasurer): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline]
    val alone = measurer.measure(plain, style, constraints = Constraints(maxWidth = constraints.maxWidth))
    if (alone.lineCount != 1 || placeable[LastBaseline] != baseline || baseline == AlignmentLine.Unspecified) {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val height = alone.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, alone.firstBaseline.roundToInt() - baseline) }
    }
}

/** Room for the background on each side of inline code. */
private const val InlineCodePadding = " " // thin space

/** The color of the background behind inline code in [theme], or null for none. */
internal fun inlineCodeBackground(theme: SlideTheme): Color? = (theme.inlineCode.background ?: theme.surface).takeIf { it.alpha > 0f }

internal fun parseInlineCode(text: String, theme: SlideTheme): AnnotatedString {
    val look = theme.inlineCode
    val style = SpanStyle(fontFamily = theme.codeFontFamily, fontSize = look.scale.em, color = look.color)
    // Without a background, the code needs no room for one.
    val padding = if (inlineCodeBackground(theme) == null) "" else InlineCodePadding
    // Between backticks, every other part is code. A backtick without a pair stays as it is.
    val parts = text.split('`')
    val unpaired = parts.size % 2 == 0
    val builder = AnnotatedString.Builder()
    (if (unpaired) parts.dropLast(1) else parts).asSequence()
        .withIndex()
        .map { (index, part) -> if (index % 2 == 0) AnnotatedString(part) else inlineCode(part, style, padding) }
        .forEach(builder::append)
    if (unpaired) builder.append("`" + parts.last())
    return builder.toAnnotatedString()
}

/** [code] in [style], marked as inline code, with [padding] on either side as room for its background. */
private fun inlineCode(code: String, style: SpanStyle, padding: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    builder.append(padding)
    builder.pushStringAnnotation(InlineCodeTag, "")
    builder.pushStyle(style)
    builder.append(code)
    builder.pop()
    builder.pop()
    builder.append(padding)
    return builder.toAnnotatedString()
}

/** Rounded backgrounds behind inline code, one per line it spans, the code [scale] times the text's size. */
private fun DrawScope.drawInlineCodeBackgrounds(layout: TextLayoutResult, color: Color, scale: Float) {
    val codeSize = layout.layoutInput.style.fontSize.toPx() * scale
    layout.inlineCodeBackgrounds(codeSize).forEach { drawRoundRect(color, it.topLeft, it.size, CornerRadius(codeSize * 0.22f)) }
}

/** Where the backgrounds behind the inline code in this text go, one per line it spans, for code [codeSize] large. */
private fun TextLayoutResult.inlineCodeBackgrounds(codeSize: Float): Sequence<Rect> {
    val text = layoutInput.text
    return text.getStringAnnotations(InlineCodeTag, 0, text.length).asSequence()
        .filter { it.end > it.start }
        .flatMap { lineSpans(it.start, it.end) }
        .map { it.toBackground(this, codeSize) }
}

/** The part of the text from [start] to [end] on [line]. */
private class LineSpan(val line: Int, val start: Int, val end: Int) {
    /** The rounded background behind it in [layout], for code [codeSize] large. */
    fun toBackground(layout: TextLayoutResult, codeSize: Float): Rect {
        val padding = codeSize * 0.18f
        val baseline = layout.getLineBaseline(line)
        return Rect(
            left = layout.getHorizontalPosition(start, usePrimaryDirection = true) - padding,
            top = baseline - codeSize * 0.95f,
            right = layout.getHorizontalPosition(end, usePrimaryDirection = true) + padding,
            bottom = baseline + codeSize * 0.32f,
        )
    }
}

/** The non-empty parts of the text from [start] to [end], line by line. */
private fun TextLayoutResult.lineSpans(start: Int, end: Int): Sequence<LineSpan> =
    (getLineForOffset(start)..getLineForOffset(end - 1)).asSequence()
        .map { LineSpan(it, maxOf(start, getLineStart(it)), minOf(end, getLineEnd(it, visibleEnd = true))) }
        .filter { it.end > it.start }
