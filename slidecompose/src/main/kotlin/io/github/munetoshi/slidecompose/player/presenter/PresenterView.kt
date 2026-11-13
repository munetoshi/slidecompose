package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.stage.LocalWindowFocus
import io.github.munetoshi.slidecompose.player.stage.PointerArrowSize
import io.github.munetoshi.slidecompose.player.stage.PointerShape
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.advancesOnClick
import io.github.munetoshi.slidecompose.player.stage.pointerShapeOf
import io.github.munetoshi.slidecompose.player.window.PresentationWindows
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import org.jetbrains.compose.resources.stringResource

/**
 * The presenter display: the current slide (animated) with its notes, the next frame with its
 * notes, progress bars for the time, with a planned duration, and the slides, and a list of the
 * slides that opens and closes.
 */
@Composable
fun PresenterView(
    controller: PresentationController,
    modifier: Modifier = Modifier,
    notesFontSize: TextUnit = DefaultNotesFontSize,
    /** Called by the notes size buttons; without it, the view keeps the size itself. */
    onNotesFontSizeChange: ((TextUnit) -> Unit)? = null,
    /** Called by the swap button, which is shown only with it. */
    onSwapDisplays: (() -> Unit)? = null,
    colors: PresenterColors = if (isSystemInDarkTheme()) PresenterColors.Dark else PresenterColors.Light,
    /** Draws the current and next slides; see [PresentationWindows]. */
    renderer: SlideRenderer = SlideRenderer.InProcess,
    /** Whether the list of slides shows at the bottom. */
    showsSlideList: Boolean = false,
    /** Called by the list's toggle; without it, the view keeps whether the list shows itself. */
    onShowsSlideListChange: ((Boolean) -> Unit)? = null,
) {
    var ownNotesFontSize by remember { mutableStateOf(notesFontSize) }
    var ownShowsSlideList by remember { mutableStateOf(showsSlideList) }
    CompositionLocalProvider(LocalPresenterColors provides colors) {
        PresenterContent(
            controller,
            modifier,
            notesFontSize = if (onNotesFontSizeChange != null) notesFontSize else ownNotesFontSize,
            onNotesFontSizeChange = onNotesFontSizeChange ?: { ownNotesFontSize = it },
            onSwapDisplays = onSwapDisplays,
            renderer = renderer,
            slideList = if (onShowsSlideListChange != null) showsSlideList else ownShowsSlideList,
            onSlideListChange = onShowsSlideListChange ?: { ownShowsSlideList = it },
        )
    }
}

/** The size of speaker notes at 100%, for the current and the next slide alike. */
val DefaultNotesFontSize: TextUnit = 21.sp

@Composable
private fun PresenterContent(
    controller: PresentationController,
    modifier: Modifier,
    notesFontSize: TextUnit,
    onNotesFontSizeChange: (TextUnit) -> Unit,
    onSwapDisplays: (() -> Unit)?,
    renderer: SlideRenderer,
    slideList: Boolean,
    onSlideListChange: (Boolean) -> Unit,
) {
    val presentation = controller.presentation
    val frame = controller.frame
    val slide = controller.currentSlide
    val next = controller.nextFrame
    // The audience window should not keep showing a pointer once this view is gone.
    DisposableEffect(controller) { onDispose { controller.pointer = null } }
    SharePointerShape(controller)
    val pointerArea = remember { PointerArea() }
    Column(
        modifier.fillMaxSize()
            .background(colors.background)
            .onPlaced { pointerArea.view = it }
            .sharePointer(controller, pointerArea)
            .padding(28.dp),
    ) {
        // The share of the width for the current slide; the notch between the two adjusts it.
        var split by remember { mutableFloatStateOf(DefaultSplit) }
        var rowWidth by remember { mutableIntStateOf(1) }
        Row(Modifier.weight(1f).fillMaxWidth().onSizeChanged { rowWidth = it.width }) {
            SlideWithNotes(
                label = {
                    PanelLabel(stringResource(Res.string.label_current))
                    if (slide != null) BuildDots(slide.steps, frame.step, onSelect = { controller.goTo(frame.copy(step = it)) })
                },
                actions = { Controls(controller) },
                aspectRatio = presentation.size.aspectRatio,
                notes = slide?.notes.orEmpty(),
                step = frame.step,
                notesFontSize = notesFontSize,
                modifier = Modifier.weight(split),
                slideModifier = Modifier.onPlaced { pointerArea.slide = it }.advancesOnClick(controller, LocalWindowFocus.current),
                notesOverlay = { NotesSizeButtons(notesFontSize, onNotesFontSizeChange) },
            ) {
                renderer.Current(controller, Modifier.fillMaxSize())
                if (controller.isBlackedOut) BlackScreenNotice()
            }
            Notch(
                onDrag = { pixels -> split = (split + pixels / rowWidth).coerceIn(MinSplit, MaxSplit) },
                onReset = { split = DefaultSplit },
            )
            SlideWithNotes(
                label = {
                    PanelLabel(stringResource(Res.string.label_next))
                    if (next != null) BuildDots(presentation.slides[next.slide].steps, next.step)
                },
                actions = {
                    Clock()
                    if (onSwapDisplays != null) {
                        IconButton(tooltip = stringResource(Res.string.tooltip_swap), onClick = onSwapDisplays) { tint ->
                            drawSwapIcon(tint)
                        }
                    }
                },
                aspectRatio = presentation.size.aspectRatio,
                // Its top right corner, with the clock and the swap button, at the window's right edge when it is not as wide as its share.
                alignment = Alignment.End,
                notes = next?.let { presentation.slides[it.slide].notes }.orEmpty(),
                step = next?.step ?: 0,
                notesFontSize = notesFontSize,
                modifier = Modifier.weight(1f - split),
            ) {
                if (next != null) {
                    renderer.Still(controller, next, colors.panel, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(colors.panel), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.end_of_presentation), color = colors.dim, fontSize = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        StatusBar(controller, renderer)
        Spacer(Modifier.height(10.dp))
        SlideList(controller, renderer, slideList, onSlideListChange)
    }
}

private const val DefaultSplit = 0.6f
private const val MinSplit = 0.4f
private const val MaxSplit = 0.68f

/** The handle between the current and next slides: drag it to share the width differently, double-click to reset. */
@Composable
private fun Notch(onDrag: (Float) -> Unit, onReset: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxHeight().width(28.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 4.dp, height = 44.dp).clip(RoundedCornerShape(2.dp))
                .background(if (hovered || dragging) colors.dim else colors.upcoming),
        )
    }
}

/**
 * A slide preview as large as fits, with notes below it in the height left over: many slides have
 * no notes, so they give way first, and are left out when too little is left. Above the slide,
 * [label] is on the left and [actions] at its top right corner; when the slide is narrow, the
 * label gives way.
 */
@Composable
private fun RowScope.SlideWithNotes(
    label: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    aspectRatio: Float,
    notes: List<String>,
    step: Int,
    notesFontSize: TextUnit,
    modifier: Modifier = Modifier,
    slideModifier: Modifier = Modifier,
    /** Where the slide is when it is narrower than the space given. */
    alignment: Alignment.Horizontal = Alignment.Start,
    /** Floats at the bottom right of the notes. */
    notesOverlay: @Composable () -> Unit = {},
    slide: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val slideHeightRoom = (maxHeight - PanelLabelHeight - LabelGap).coerceAtLeast(0.dp)
        val slideWidth = min(maxWidth, slideHeightRoom * aspectRatio)
        val notesHeight = slideHeightRoom - slideWidth / aspectRatio - NotesGap
        Column(Modifier.fillMaxSize(), horizontalAlignment = alignment) {
            Row(Modifier.width(slideWidth).height(PanelLabelHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).padding(end = 12.dp).clipToBounds(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = label,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            Spacer(Modifier.height(LabelGap))
            Box(
                slideModifier.size(slideWidth, slideWidth / aspectRatio)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.upcoming, RoundedCornerShape(6.dp)),
            ) {
                slide()
            }
            if (notesHeight >= MinNotesHeight) {
                Spacer(Modifier.height(NotesGap))
                Notes(notes, step, notesFontSize, Modifier.fillMaxWidth().weight(1f), notesOverlay)
            }
        }
    }
}

/** Dims the presenter's copy of the current slide while the audience sees a black screen, and says how to end it. */
@Composable
private fun BlackScreenNotice() {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The shadow keeps the words clear of the slide's own text behind them.
        val style = TextStyle(color = Color.White, textAlign = TextAlign.Center, shadow = Shadow(Color.Black, blurRadius = 8f))
        Text(stringResource(Res.string.black_screen), style = style, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.black_screen_hint), style = style, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
    }
}

private val LabelGap: Dp = 10.dp
private val NotesGap: Dp = 20.dp

/** Where the presenter view and its copy of the current slide are, for sharing the pointer. */
private class PointerArea {
    var view: LayoutCoordinates? = null
    var slide: LayoutCoordinates? = null

    /** The current slide's bounds in the view, or `null` before both are placed. */
    fun slideBounds(): Rect? {
        val view = view?.takeIf { it.isAttached } ?: return null
        val slide = slide?.takeIf { it.isAttached } ?: return null
        return view.localBoundingBoxOf(slide).takeIf { it.width > 0f && it.height > 0f }
    }
}

/**
 * Shares the mouse position over the current slide through [PresentationController.pointer].
 * The cursor's tip is at its top left, so past the slide's left or top edge part of the cursor
 * is still over the slide: the position keeps being shared until the pointer drawn on the
 * audience's slide would be out of sight entirely.
 */
private fun Modifier.sharePointer(controller: PresentationController, area: PointerArea): Modifier =
    pointerInput(controller, area) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val position = event.changes.firstOrNull()?.position
                val bounds = area.slideBounds()
                controller.pointer = if (event.type == PointerEventType.Exit || position == null || bounds == null) {
                    null
                } else {
                    val size = controller.presentation.size
                    val x = (position.x - bounds.left) / bounds.width
                    val y = (position.y - bounds.top) / bounds.height
                    Offset(x, y).takeIf {
                        x in -PointerArrowSize.width / size.width..1f && y in -PointerArrowSize.height / size.height..1f
                    }
                }
            }
        }
    }

/**
 * The window the presenter view is in, which it reads the cursor of to share its shape through
 * [PresentationController.pointerShape], or `null` to share the arrow only.
 */
internal val LocalPresenterWindow = staticCompositionLocalOf<AwtWindow?> { null }

/**
 * Keeps [PresentationController.pointerShape] after the cursor in the [LocalPresenterWindow]. The
 * cursor is read once the pointer has moved and the move has been handled, which is when what is
 * under it has set the cursor.
 */
@Composable
private fun SharePointerShape(controller: PresentationController) {
    val window = LocalPresenterWindow.current ?: return
    LaunchedEffect(controller, window) {
        snapshotFlow { controller.pointer }.collect {
            controller.pointerShape = if (it == null) PointerShape.Arrow else pointerShapeOf(window.cursorUnderMouse())
        }
    }
}

/** The cursor shown where the mouse is over this window, or `null` when it is not over it. */
private fun AwtWindow.cursorUnderMouse(): Cursor? {
    val location = MouseInfo.getPointerInfo()?.location ?: return null
    SwingUtilities.convertPointFromScreen(location, this)
    return findComponentAt(location)?.cursor
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        color = colors.dim,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

internal val PanelLabelHeight: Dp = 28.dp
