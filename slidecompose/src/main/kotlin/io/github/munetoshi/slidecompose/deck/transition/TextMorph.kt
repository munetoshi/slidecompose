package io.github.munetoshi.slidecompose.deck.transition

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.util.lerp
import java.text.BreakIterator

/**
 * Draws the same text changing its font or weight during an element transition: every character
 * moves on its own from where it is in the old text to where it is in the new one, and changes on
 * the way.
 *
 * - Another weight of the same font, the way Keynote does it: at each spot the two weights
 *   cross-fade, each drawn in full while the other fades, so the text never looks faint. The new
 *   weight comes in over the first half and the old one goes over the second: a lighter weight's
 *   strokes stick out of a heavier one's here and there (a serif's W), so the text starts and ends
 *   in one weight, as the slides draw it.
 * - Another font: the letters change shape, so drawing one in full would show both all along.
 *   They cross-fade, letter by letter, as text saying something else does as a whole.
 *
 * Either way, the letters start the size the old ones were and end the size the new ones are, the
 * old and the new the same size on a shared baseline on the way.
 *
 * The text is laid out in the new style (its own) except for the font and weight; the element
 * transition scales it from the old size to the new one.
 */
internal class TextMorphDrawing(private val morph: TextMorph, private val measurer: TextMeasurer) {
    /** A character: where its baseline starts in the old text and the new, and it laid out alone in either look. */
    private class Glyph(
        val from: Offset,
        val to: Offset,
        val old: TextLayoutResult,
        val new: TextLayoutResult,
    )

    /** The characters, and how much larger the old text was drawn than it is laid out here, once the element transition has scaled it. */
    private class Glyphs(val glyphs: List<Glyph>, val oldScale: Float)

    private var laidOut: TextLayoutResult? = null
    private var glyphs = Glyphs(emptyList(), 1f)

    fun DrawScope.draw(layout: TextLayoutResult) {
        val color = morph.color() ?: Color.Unspecified
        // Only the color changes: no need to take the text apart.
        if (morph.from.weight == morph.to.weight && !morph.to.changesFontFrom(morph.from)) {
            drawText(layout, color = color)
            return
        }
        if (laidOut !== layout) {
            glyphs = glyphsOf(layout)
            laidOut = layout
        }
        if (morph.to.changesFontFrom(morph.from)) drawFontChange(color) else drawWeightChange(color)
    }

    private fun DrawScope.drawWeightChange(color: Color) {
        val fraction = morph.fraction()
        val newIsHeavier = morph.to.weight.weight > morph.from.weight.weight
        val oldAlpha = (2f * (1f - fraction)).coerceAtMost(1f)
        val newAlpha = (2f * fraction).coerceAtMost(1f)
        val (lighterAlpha, heavierAlpha) = if (newIsHeavier) oldAlpha to newAlpha else newAlpha to oldAlpha
        val scale = lerp(glyphs.oldScale, 1f, fraction)
        for (glyph in glyphs.glyphs) {
            val baseline = lerp(glyph.from, glyph.to, fraction)
            val (lighter, heavier) = if (newIsHeavier) glyph.old to glyph.new else glyph.new to glyph.old
            // While the heavier (and wider) version shows, centre the lighter one under it, so the
            // difference shows evenly on both sides. Without it, each is exactly where it belongs.
            val centring = (heavier.size.width - lighter.size.width) * scale / 2f * heavierAlpha
            drawGlyph(lighter, baseline + Offset(centring, 0f), scale, color, lighterAlpha)
            drawGlyph(heavier, baseline, scale, color, heavierAlpha)
        }
    }

    private fun DrawScope.drawFontChange(color: Color) {
        val fraction = morph.fraction()
        val fade = morph.fade()
        val scale = lerp(glyphs.oldScale, 1f, fraction)
        for (glyph in glyphs.glyphs) {
            val baseline = lerp(glyph.from, glyph.to, fraction)
            drawGlyph(glyph.old, baseline, scale, color, alpha = 1f - fade)
            drawGlyph(glyph.new, baseline, scale, color, alpha = fade)
        }
    }

    /** Draws a character laid out alone with its baseline's start at [baseline], [scale] times its size. */
    private fun DrawScope.drawGlyph(layout: TextLayoutResult, baseline: Offset, scale: Float, color: Color, alpha: Float) {
        if (alpha <= 0f) return
        translate(baseline.x, baseline.y) {
            scale(scale, pivot = Offset.Zero) {
                drawText(layout, color = color, topLeft = Offset(0f, -layout.firstBaseline), alpha = alpha)
            }
        }
    }

    /** Where each character's baseline starts in the text of either look, and each character laid out alone in either look. */
    private fun glyphsOf(layout: TextLayoutResult): Glyphs {
        val input = layout.layoutInput
        val fromStyle = input.style.copy(fontFamily = morph.from.fontFamily, fontWeight = morph.from.weight, fontStyle = morph.from.fontStyle)
        val fromLayout = measurer.measure(
            text = input.text,
            style = fromStyle,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            constraints = input.constraints,
        )
        // The old text spans the width the element transition scales from: stretch its
        // positions to the new text's width, so that at the start every character is in place.
        val stretch = if (fromLayout.size.width > 0) layout.size.width.toFloat() / fromLayout.size.width else 1f
        // The old text is also as tall as it was, which is centred, as the element transition
        // centres the element in its bounds; and its letters are as large. Without it, a weight
        // much wider than the other (a serif's bold) would start with smaller letters, further apart.
        val top = (layout.size.height - fromLayout.size.height * stretch) / 2f
        val text = input.text
        val clusters = BreakIterator.getCharacterInstance().apply { setText(text.text) }
        val glyphs = generateSequence(clusters.first() to clusters.next()) { (_, end) -> end to clusters.next() }
            .takeWhile { (_, end) -> end != BreakIterator.DONE }
            .filter { (start, end) -> text.text.substring(start, end).isNotBlank() }
            .map { (start, end) ->
                val character = text.subSequence(start, end)
                Glyph(
                    from = Offset(
                        fromLayout.getHorizontalPosition(start, usePrimaryDirection = true) * stretch,
                        fromLayout.getLineBaseline(fromLayout.getLineForOffset(start)) * stretch + top,
                    ),
                    to = Offset(
                        layout.getHorizontalPosition(start, usePrimaryDirection = true),
                        layout.getLineBaseline(layout.getLineForOffset(start)),
                    ),
                    old = measureAlone(character, fromStyle),
                    new = measureAlone(character, input.style),
                )
            }
            .toList()
        return Glyphs(glyphs, stretch)
    }

    private fun measureAlone(character: androidx.compose.ui.text.AnnotatedString, style: TextStyle): TextLayoutResult =
        measurer.measure(character, style, softWrap = false, maxLines = 1)
}
