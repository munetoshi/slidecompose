package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.theme.PointerColor
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.stage.PointerArrowSize
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.drawPointerArrow
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import io.github.munetoshi.slidecompose.player.stage.pointerArrowPath
import java.time.LocalTime
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Clock() {
    val now by rememberNow()
    Text(
        LocalTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault()).format(clockFormat),
        color = colors.dim,
        fontSize = 24.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}

/** The current time in milliseconds, updated a few times a second. */
@Composable
internal fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(250)
        }
    }
    return now
}

/**
 * The time since the talk started (click to pause or resume), resetting it, and how the pointer
 * is shared.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Controls(controller: PresentationController) {
    val timer = controller.timer
    val now by rememberNow()
    TooltipArea(tooltip = { Tooltip(stringResource(if (timer.isRunning) Res.string.tooltip_pause_timer else Res.string.tooltip_resume_timer)) }) {
        val elapsed = timer.elapsedMillis(now)
        Text(
            formatShort(elapsed),
            Modifier.clip(RoundedCornerShape(5.dp)).keepsNoFocus().clickable { timer.toggle() }.padding(horizontal = 6.dp),
            color = timeColor(controller, elapsed),
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
    IconButton(tooltip = stringResource(Res.string.tooltip_reset_timer), onClick = { controller.resetTimer() }) { tint -> drawResetIcon(tint) }
    PointerButton(controller)
}

/** A small clickable icon drawn in [draw], with a tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconButton(tooltip: String, onClick: () -> Unit, draw: DrawScope.(tint: Color) -> Unit) {
    val tint = colors.dim
    TooltipArea(tooltip = { Tooltip(tooltip) }) {
        Canvas(
            Modifier.clip(RoundedCornerShape(5.dp))
                .keepsNoFocus().clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = (PanelLabelHeight - IconSize) / 2)
                .size(IconSize),
        ) {
            draw(tint)
        }
    }
}

@Composable
internal fun Tooltip(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(4.dp)).background(colors.panel).border(1.dp, colors.upcoming, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = colors.text,
        fontSize = 13.sp,
    )
}

/**
 * Shows how the pointer is shared: a white or black arrow, or a crossed-out one when it is not.
 * Clicking it cycles through white, black and off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointerButton(controller: PresentationController) {
    val shared = controller.isPointerShared
    val color = controller.effectivePointerColor
    val dim = colors.dim
    TooltipArea(tooltip = { Tooltip(stringResource(Res.string.tooltip_pointer)) }) {
        Canvas(
            Modifier.clip(RoundedCornerShape(5.dp))
                .keepsNoFocus().clickable {
                    when {
                        !shared -> {
                            controller.isPointerShared = true
                            controller.pointerColor = PointerColor.White
                        }
                        color == PointerColor.White -> controller.pointerColor = PointerColor.Black
                        else -> controller.isPointerShared = false
                    }
                }
                .padding(horizontal = 8.dp, vertical = (PanelLabelHeight - PointerIconHeight) / 2)
                .size(width = PointerIconHeight * 0.65f, height = PointerIconHeight),
        ) {
            val scale = size.height / PointerArrowSize.height
            if (shared) {
                drawPointerArrow(color, scale)
            } else {
                withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                    drawPath(pointerArrowPath(), dim, style = Stroke(width = 3f))
                }
                drawLine(dim, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

private val PointerIconHeight: Dp = 20.dp
