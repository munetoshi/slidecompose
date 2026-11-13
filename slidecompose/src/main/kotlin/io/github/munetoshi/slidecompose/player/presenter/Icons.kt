package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/** The size of Material Symbols, whose style the icons follow; they fill the height of the row above the slides. */
internal val IconSize: Dp = 24.dp

/**
 * Two overlapping windows trading places along curved arrows. Drawn in the style of Material
 * Symbols, like the other icons here: on a 24-unit grid with 2-unit strokes.
 */
internal fun DrawScope.drawSwapIcon(tint: Color) {
    val unit = size.width / 24f
    val arrows = listOf(
        arcArrow(Offset(16.5f, 9f) * unit, radius = 6 * unit, startAngle = -90f, endAngle = -28f, unit),
        arcArrow(Offset(7.5f, 15f) * unit, radius = 6 * unit, startAngle = 90f, endAngle = 152f, unit),
    )
    // The windows go in a layer of their own, to clear a gap around the arrows that cross them.
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint()) }
    drawSwapWindows(tint, unit)
    arrows.forEach {
        drawPath(it.shaft, Color.Black, style = Stroke(4 * unit), blendMode = BlendMode.Clear)
        drawPath(it.head, Color.Black, style = Stroke(2 * unit, join = StrokeJoin.Miter), blendMode = BlendMode.Clear)
    }
    drawIntoCanvas { it.restore() }
    arrows.forEach { drawArcArrow(it, tint, unit) }
}

/** The windows of [drawSwapIcon]: the front one hides the back one where they overlap. */
private fun DrawScope.drawSwapWindows(tint: Color, unit: Float) {
    val stroke = Stroke(2 * unit)
    // The strokes are centred on these rectangles.
    val back = Rect(Offset(2.5f, 4f) * unit, Offset(15.5f, 13.5f) * unit)
    val front = Rect(Offset(8.5f, 10.5f) * unit, Offset(21.5f, 20f) * unit)
    val frontArea = Path().apply { addRoundRect(RoundRect(front.inflate(unit), CornerRadius(2 * unit))) }
    clipPath(frontArea, ClipOp.Difference) {
        drawRoundRect(tint, back.topLeft, back.size, CornerRadius(unit), style = stroke)
    }
    drawRoundRect(tint, front.topLeft, front.size, CornerRadius(unit), style = stroke)
}

/**
 * A stopwatch with one hand, ringed by an arrow running back around it to the crown: the arrow
 * starts dotted. In the style of Material Symbols, like [drawSwapIcon].
 */
internal fun DrawScope.drawResetIcon(tint: Color) {
    val unit = size.width / 24f
    val center = Offset(12f, 13f) * unit
    val radius = 8 * unit
    // Counterclockwise from just left of the crown: dots, then the arrow itself.
    val rootAngle = -126f
    val dottedSweep = -66f
    drawArc(
        tint,
        startAngle = rootAngle,
        sweepAngle = dottedSweep,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(2 * unit, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 3.4f * unit), 1.7f * unit)),
    )
    drawArcArrow(arcArrow(center, radius, startAngle = rootAngle + dottedSweep - 12f, endAngle = -26f - 360f, unit), tint, unit)
    // The crown: a wide bar resting on the ring, on a stem that just shows in the ring's gap.
    val barY = center.y - radius - 2 * unit
    drawLine(tint, Offset(center.x, center.y - radius), Offset(center.x, barY), strokeWidth = 2 * unit)
    drawLine(tint, Offset(center.x - 4 * unit, barY), Offset(center.x + 4 * unit, barY), strokeWidth = 2 * unit)
    // The hand, at ten o'clock.
    drawLine(tint, center, pointOnCircle(center, 4.5f * unit, -150f), strokeWidth = 2 * unit, cap = StrokeCap.Square)
}

/** A circular arrow: its shaft, an arc, and its head, a filled triangle. */
private class ArcArrow(val shaft: Path, val head: Path)

/**
 * The arrow along the circle around [center] from [startAngle] to [endAngle] degrees (clockwise
 * when [endAngle] is the greater), and then a head 4 units long and 5.5 wide. The head points
 * along the chord it spans, so that it sits evenly on the curve.
 */
private fun arcArrow(center: Offset, radius: Float, startAngle: Float, endAngle: Float, unit: Float): ArcArrow {
    val shaft = Path().apply { arcTo(Rect(center, radius), startAngle, endAngle - startAngle, forceMoveTo = true) }
    val length = 4 * unit
    val headSweep = Math.toDegrees((length / radius).toDouble()).toFloat() * sign(endAngle - startAngle)
    val base = pointOnCircle(center, radius, endAngle)
    val chord = pointOnCircle(center, radius, endAngle + headSweep) - base
    val heading = chord / chord.getDistance()
    val tip = base + heading * length
    val halfBase = Offset(-heading.y, heading.x) * (2.75f * unit)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + halfBase.x, base.y + halfBase.y)
        lineTo(base.x - halfBase.x, base.y - halfBase.y)
        close()
    }
    return ArcArrow(shaft, head)
}

private fun DrawScope.drawArcArrow(arrow: ArcArrow, tint: Color, unit: Float) {
    drawPath(arrow.shaft, tint, style = Stroke(2 * unit))
    drawPath(arrow.head, tint)
}

private fun pointOnCircle(center: Offset, radius: Float, angle: Float): Offset {
    val radians = Math.toRadians(angle.toDouble())
    return center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius
}
