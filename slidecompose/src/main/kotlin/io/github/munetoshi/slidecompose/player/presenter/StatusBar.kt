package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Schedule
import io.github.munetoshi.slidecompose.deck.transition.totalMillis
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.stage.AppForeground
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The progress bars, with where the talk is now on their left and "left / total" on their
 * right. The time bar, on top, is there only with a planned duration.
 *
 * ```
 *   NOW                            LEFT   TOTAL
 *  3:10  [■■■■□□□□□□□□]    6:50  / 10:00
 *     4  [■■■■■■□□□□□□]       3  /  7
 * ```
 */
@Composable
internal fun StatusBar(controller: PresentationController, renderer: SlideRenderer) {
    val now by rememberNow()
    val schedule = controller.presentation.schedule
    val frame = controller.frame
    val slide = controller.currentSlide
    val count = controller.slideCount
    // Where the mouse is on the slide bar, as a fraction of its width, and where the bar is in the window.
    var hover by remember { mutableStateOf<Double?>(null) }
    var bar by remember { mutableStateOf(Rect.Zero) }
    // Pictures for the previews, kept while the deck is the same.
    val pictures = remember(controller.presentation) { mutableStateMapOf<Frame, ImageBitmap>() }
    // In a box, so that the preview is placed by it, and takes no room in the column.
    Box {
        StatusRows(controller, now, hover, onHoverChange = { hover = it }, onBarPlaced = { bar = it })
        val target = hover?.let { targetAt(schedule, count, frame.slide, slide?.steps ?: 1, it) }
        if (target != null) {
            // Above the progress bars, over the middle of the part of the slide bar it shows.
            val middle = (bar.left + (target.start + target.end) / 2 * bar.width).roundToInt()
            Popup(popupPositionProvider = remember(middle) { AboveAt(middle) }) {
                SlidePreview(controller, renderer, previewFrame(target.frame, frame.slide, controller.presentation), pictures)
            }
        }
    }
}

/** The caption row, the time bar with a planned duration, and the slide bar. */
@Composable
private fun StatusRows(controller: PresentationController, now: Long, hover: Double?, onHoverChange: (Double?) -> Unit, onBarPlaced: (Rect) -> Unit) {
    val schedule = controller.presentation.schedule
    val frame = controller.frame
    val slide = controller.currentSlide
    val elapsed = controller.timer.elapsedMillis(now)
    val count = controller.slideCount
    val total = schedule.totalMillis
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProgressRow(
            now = stringResource(Res.string.caption_now),
            left = stringResource(Res.string.caption_left),
            slash = "",
            total = stringResource(Res.string.caption_total),
            caption = true,
        )
        if (total != null && slide != null) {
            val over = elapsed > total
            ProgressRow(
                now = formatShort(elapsed),
                nowColor = timeColor(controller, elapsed),
                left = if (over) "+${formatShort(elapsed - total)}" else formatShort(total - elapsed),
                leftColor = if (over) colors.late else colors.text,
                total = formatShort(total),
            ) {
                TimeProgressBar(fraction = (elapsed / total).toFloat(), color = timeColor(controller, elapsed))
            }
        }
        ProgressRow(
            now = if (count == 0) "–" else "${frame.slide + 1}",
            left = if (count == 0) "–" else "${count - frame.slide - 1}",
            total = "$count",
        ) {
            SlideProgressBar(
                schedule,
                count,
                frame.slide,
                frame.step,
                slide?.steps ?: 1,
                hover = hover,
                onHoverChange = onHoverChange,
                onPlaced = onBarPlaced,
                onSelect = { target ->
                    // Like clicks on the slides, one that only brings the app to the front goes nowhere.
                    if (!AppForeground.justRaised()) controller.goTo(target)
                },
            )
        }
    }
}

/**
 * The color of the time used so far: dim while the timer is paused; while it runs, red behind the
 * plan (past the planned end of the current slide), blue ahead of it (before the planned start of
 * the current slide), and green on it.
 */
@Composable
internal fun timeColor(controller: PresentationController, elapsed: Long): Color {
    val schedule = controller.presentation.schedule
    val total = schedule.totalMillis
    return when {
        !controller.timer.isRunning -> colors.dim
        total != null && elapsed > schedule.end(controller.frame.slide) * total -> colors.late
        total != null && elapsed < schedule.start(controller.frame.slide) * total -> colors.ahead
        else -> colors.running
    }
}

/** One row of [StatusBar]; the columns line up across rows, the slashes included. */
@Composable
private fun ProgressRow(
    now: String,
    left: String,
    total: String,
    slash: String = "/",
    caption: Boolean = false,
    nowColor: Color = colors.text,
    leftColor: Color = colors.text,
    bar: @Composable () -> Unit = {},
) {
    val style = if (caption) {
        TextStyle(color = colors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    } else {
        TextStyle(color = colors.text, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
    }
    // Captions keep the color of their style.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(now, Modifier.width(ValueWidth), style = style, color = if (caption) Color.Unspecified else nowColor, textAlign = TextAlign.End, maxLines = 1)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f).height(if (caption) 0.dp else BarHeight)) { bar() }
        Spacer(Modifier.width(18.dp))
        Text(left, Modifier.width(ValueWidth), style = style, color = if (caption) Color.Unspecified else leftColor, textAlign = TextAlign.End, maxLines = 1)
        Text(slash, Modifier.width(SlashWidth), style = style.copy(color = colors.dim), textAlign = TextAlign.Center, maxLines = 1)
        Text(total, Modifier.width(ValueWidth), style = style, textAlign = TextAlign.Start, maxLines = 1)
    }
}

internal val ValueWidth: Dp = 96.dp
internal val SlashWidth: Dp = 30.dp
internal val BarHeight: Dp = 12.dp

/**
 * One dot per step, the first included, filled once shown and outlined until then; nothing
 * without builds. With [onSelect], clicking a dot goes to its step.
 */
@Composable
internal fun BuildDots(steps: Int, step: Int, onSelect: ((step: Int) -> Unit)? = null) {
    if (steps < 2) return
    Row {
        for (build in 0 until steps) {
            // The padding makes the gap between the dots, and a bigger target to click.
            Box(
                Modifier.clip(CircleShape)
                    .then(if (onSelect != null) Modifier.keepsNoFocus().clickable { onSelect(build) } else Modifier)
                    .padding(3.dp),
            ) {
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .then(
                            if (build <= step) {
                                Modifier.background(colors.accent)
                            } else {
                                Modifier.border(1.5.dp, colors.accent, CircleShape)
                            },
                        ),
                )
            }
        }
    }
}

/** The test tag of [SlideProgressBar]. */
internal const val SlideBarTag = "slide-bar"

/** Places a popup above what it is in, its middle at [middle] in the window, but inside the window. */
private class AboveAt(private val middle: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = (middle - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(left, anchorBounds.top - popupContentSize.height)
    }
}

/**
 * One segment per slide, as long as the time planned for it: dark for slides already
 * presented, light for the current one, outlined for the rest. The current slide's segment is
 * split into its builds, filled up to the current one. Clicking a segment calls [onSelect] with
 * the frame there, and at [hover], where the mouse is as a fraction of its width, the part of the
 * bar that goes there is shaded: see [targetAt]. [onPlaced] tells where the bar is in the window.
 */
@Composable
internal fun SlideProgressBar(
    schedule: Schedule,
    slideCount: Int,
    current: Int,
    step: Int,
    steps: Int,
    hover: Double?,
    onHoverChange: (Double?) -> Unit,
    onPlaced: (Rect) -> Unit,
    onSelect: (Frame) -> Unit,
) {
    val colors = colors
    // The bar recomposes with the clock; reading the latest values keeps the tap detector running.
    val select by rememberUpdatedState { fraction: Double -> targetAt(schedule, slideCount, current, steps, fraction)?.let { onSelect(it.frame) } }
    val target = hover?.let { targetAt(schedule, slideCount, current, steps, it) }
    val hovered by rememberUpdatedState(onHoverChange)
    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
            .testTag(SlideBarTag)
            // The bar is thin: clicks a little above and below it count too.
            .overhang(BarClickMargin)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) { detectTapGestures { select(it.x.toDouble() / size.width) } }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val x = event.changes.firstOrNull()?.position?.x
                        hovered(if (event.type == PointerEventType.Exit || x == null) null else x.toDouble() / size.width)
                    }
                }
            }
            .drawBehind {
                if (target == null) return@drawBehind
                val left = (target.start * size.width).toFloat()
                drawRoundRect(
                    colors.text.copy(alpha = 0.12f),
                    topLeft = Offset(left, 0f),
                    size = Size((target.end * size.width).toFloat() - left, size.height),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
            }
            .padding(vertical = BarClickMargin),
    ) {
        val gap = 3.dp.toPx()
        for (index in 0 until slideCount) {
            val start = (schedule.start(index) * size.width).toFloat()
            val width = (schedule.end(index) * size.width).toFloat() - start - gap
            if (width <= 0f) continue
            val topLeft = Offset(start + gap / 2, 0f)
            when {
                index < current -> bar(colors.done, topLeft, width)
                index == current -> {
                    val buildGap = if (steps > 1) 2.dp.toPx() else 0f
                    val buildWidth = (width - buildGap * (steps - 1)) / steps
                    for (build in 0 until steps) {
                        val left = topLeft + Offset(build * (buildWidth + buildGap), 0f)
                        bar(colors.current, left, buildWidth, outline = build > step)
                    }
                }
                else -> bar(colors.upcoming, topLeft, width, outline = true)
            }
        }
    }
}

private val BarClickMargin: Dp = 10.dp

/** Makes the rest of the modifiers [margin] taller above and below, over the neighbours, without taking more room. */
private fun Modifier.overhang(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = margin.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * extra))
    layout(placeable.width, placeable.height - 2 * extra) { placeable.place(0, -extra) }
}

/** The time used so far, on the same scale as [SlideProgressBar] with a planned duration. */
@Composable
private fun TimeProgressBar(fraction: Float, color: Color) {
    val track = colors.upcoming
    Canvas(Modifier.fillMaxSize()) {
        bar(track, Offset.Zero, size.width, outline = true)
        val width = fraction.coerceIn(0f, 1f) * size.width
        if (width > 0f) bar(color, Offset.Zero, width)
    }
}

private fun DrawScope.bar(color: Color, topLeft: Offset, width: Float, outline: Boolean = false) {
    val stroke = if (outline) 1.5.dp.toPx() else 0f
    drawRoundRect(
        color = color,
        topLeft = topLeft + Offset(stroke / 2, stroke / 2),
        size = Size(width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = if (outline) Stroke(stroke) else androidx.compose.ui.graphics.drawscope.Fill,
    )
}
