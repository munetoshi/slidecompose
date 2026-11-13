package io.github.munetoshi.slidecompose.player.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.export.PdfExportOptions
import io.github.munetoshi.slidecompose.player.export.PdfExportResult
import io.github.munetoshi.slidecompose.player.export.exportPdf
import io.github.munetoshi.slidecompose.player.export.rehearse
import io.github.munetoshi.slidecompose.player.presenter.Appearance
import io.github.munetoshi.slidecompose.player.presenter.DefaultNotesFontSize
import io.github.munetoshi.slidecompose.player.presenter.LocalPresenterWindow
import io.github.munetoshi.slidecompose.player.presenter.PresenterColors
import io.github.munetoshi.slidecompose.player.presenter.PresenterView
import io.github.munetoshi.slidecompose.player.stage.AppForeground
import io.github.munetoshi.slidecompose.player.stage.LocalWindowFocus
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.TrackFocus
import io.github.munetoshi.slidecompose.player.stage.WindowFocus
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import io.github.munetoshi.slidecompose.player.stage.slidesClicked
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame as AwtFrame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Window-level state: placements, the presenter window, toasts.
 *
 * There are two windows: the [first] one, always open, and the [second] one, open while
 * [isPresenterVisible]. The first shows the slides and the second the presenter view, unless
 * [isSwapped]: swapping exchanges what they show and leaves the windows where they are.
 */
@Stable
class PresentationWindowsState {
    val first = WindowState(size = DpSize(1280.dp, 760.dp), position = WindowPosition(Alignment.Center))
    val second = WindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition(Alignment.Center))

    var isPresenterVisible: Boolean by mutableStateOf(false)

    /** Whether the first window shows the presenter view and the second one the slides. */
    var isSwapped: Boolean by mutableStateOf(false)

    /** The window showing the slides. */
    val slidesWindow: WindowState get() = if (isSwapped && isPresenterVisible) second else first

    /** The window showing the presenter view, while [isPresenterVisible]. */
    val presenterWindow: WindowState get() = if (isSwapped) first else second

    var notesFontSize: Int by mutableStateOf(DefaultNotesFontSize.value.roundToInt())
    /** Whether the presenter view shows the list of slides at its bottom. */
    var showsSlideList: Boolean by mutableStateOf(false)
    /** Light or dark colors for the presenter window. */
    var appearance: Appearance by mutableStateOf(Appearance.System)
    var jumpBuffer: String by mutableStateOf("")
    var message: String? by mutableStateOf(null)
    var isBusy: Boolean by mutableStateOf(false)

    /** Pages exported so far and in all, while a PDF is being exported. */
    internal var exportProgress: Pair<Int, Int>? by mutableStateOf(null)

    /** The AWT frames of the windows, e.g. to parent dialogs. */
    var firstFrame: AwtFrame? = null
        internal set
    var secondFrame: AwtFrame? = null
        internal set

    val isSlidesWindowFullscreen: Boolean get() = slidesWindow.placement == WindowPlacement.Fullscreen

    /** Closes the presenter view, wherever it is; the first window shows the slides again. */
    fun hidePresenter() {
        isPresenterVisible = false
        isSwapped = false
    }
}

/**
 * The audience window and the (toggleable) presenter window.
 *
 * @param fileMenu extra items for the File menu, e.g. the viewer app's Open and Reload.
 * @param viewMenu extra items for the View menu, e.g. the viewer app's Theme.
 * @param overlay drawn over the audience window, e.g. compile errors.
 * @param renderer draws the slides: here by default, or elsewhere, as in the viewer's safe mode.
 *   PDF export is only offered when the slides are drawn here.
 */
@Composable
fun ApplicationScope.PresentationWindows(
    controller: PresentationController,
    state: PresentationWindowsState = remember { PresentationWindowsState() },
    assetDirectory: File? = null,
    title: String = controller.presentation.title,
    onCloseRequest: () -> Unit = ::exitApplication,
    fileMenu: @Composable MenuScope.() -> Unit = {},
    viewMenu: @Composable MenuScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    renderer: SlideRenderer = SlideRenderer.InProcess,
) {
    val scope = rememberCoroutineScope()
    val actions = remember(controller, state, scope) { WindowActions(controller, state, scope) }
    // Start listening for the app coming to the front before the first click needs it.
    remember { AppForeground.listen() }
    actions.assetDirectory = assetDirectory
    // The first time each kind of transition plays, its code is loaded and compiled: get that done
    // before the talk. Reloads replace the presentation in the same controller and run the same code.
    // Each off-screen frame runs as its own event on the UI thread, between the windows' frames.
    LaunchedEffect(controller, renderer) {
        if (renderer.isInProcess) withContext(AwtEvents) { controller.presentation.rehearse(assetDirectory) }
    }

    // Swapping exchanges what the two windows show: moving windows across displays, in and out of
    // full screen, is slow and unreliable on macOS.
    val presenterTitle = stringResource(Res.string.presenter_window_title, title)
    // Keys go first to what has the focus on a slide, as Compose has them go, such as a text field
    // that types them; the windows act on the keys it leaves. The few that a talk must always have
    // come to the windows first.
    val firstFocus = remember { WindowFocus() }
    Window(
        // Closing the window with the presenter view closes just that.
        onCloseRequest = { if (state.isSwapped) state.hidePresenter() else onCloseRequest() },
        state = state.first,
        title = if (state.isSwapped) presenterTitle else title,
        onPreviewKeyEvent = { actions.handleKeyFirst(it, state.first, firstFocus) },
        onKeyEvent = { actions.handleKey(it, state.first) },
    ) {
        state.firstFrame = window
        AppMenuBar(actions, renderer.isInProcess, fileMenu, viewMenu)
        TrackFocus(firstFocus) {
            if (state.isSwapped) Presenter(actions, renderer, assetDirectory, window) else Slides(actions, renderer, state.first, assetDirectory, overlay)
        }
    }

    if (state.isPresenterVisible) {
        val secondFocus = remember { WindowFocus() }
        Window(
            onCloseRequest = { state.hidePresenter() },
            state = state.second,
            title = if (state.isSwapped) title else presenterTitle,
            onPreviewKeyEvent = { actions.handleKeyFirst(it, state.second, secondFocus) },
            onKeyEvent = { actions.handleKey(it, state.second) },
        ) {
            state.secondFrame = window
            AppMenuBar(actions, renderer.isInProcess, fileMenu, viewMenu)
            TrackFocus(secondFocus) {
                if (state.isSwapped) Slides(actions, renderer, state.second, assetDirectory, overlay) else Presenter(actions, renderer, assetDirectory, window)
            }
        }
    }
}


/** Gives [content] the deck's [assetDirectory], if there is one. */
@Composable
private fun ProvideAssets(assetDirectory: File?, content: @Composable () -> Unit) {
    if (assetDirectory != null) {
        CompositionLocalProvider(LocalAssetDirectory provides assetDirectory, content = content)
    } else {
        content()
    }
}

/** The audience's view, in [window], with [overlay] over it. */
@Composable
private fun Slides(actions: WindowActions, renderer: SlideRenderer, window: WindowState, assetDirectory: File?, overlay: @Composable BoxScope.() -> Unit) {
    val controller = actions.controller
    val state = actions.state
    ProvideAssets(assetDirectory) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .then(if (window.placement == WindowPlacement.Fullscreen) Modifier.pointerHoverIcon(BlankPointer) else Modifier),
        ) {
            val focus = LocalWindowFocus.current
            renderer.Stage(controller, onClick = { controller.slidesClicked(focus) }, Modifier.fillMaxSize())
            if (controller.isBlackedOut) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
            if (!state.isPresenterVisible) JumpIndicator(state)
            overlay()
            Toast(state)
        }
    }
}

/** The presenter view, in [frame], which it reads the cursor of to share its shape. */
@Composable
private fun Presenter(actions: WindowActions, renderer: SlideRenderer, assetDirectory: File?, frame: AwtWindow) {
    val state = actions.state
    ProvideAssets(assetDirectory) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalPresenterWindow provides frame) {
                PresenterView(
                    actions.controller,
                    Modifier.fillMaxSize(),
                    notesFontSize = state.notesFontSize.sp,
                    onNotesFontSizeChange = { state.notesFontSize = it.value.roundToInt().coerceIn(12, 72) },
                    onSwapDisplays = actions::swapDisplays,
                    colors = if (state.appearance.isDark()) PresenterColors.Dark else PresenterColors.Light,
                    renderer = renderer,
                    showsSlideList = state.showsSlideList,
                    onShowsSlideListChange = { state.showsSlideList = it },
                )
            }
            JumpIndicator(state)
            Toast(state)
        }
    }
}

/** Runs each task as its own event on the AWT event dispatch thread, the windows' UI thread. */
private val AwtEvents = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()

@Composable
private fun FrameWindowScope.AppMenuBar(
    actions: WindowActions,
    exportsPdf: Boolean,
    fileMenu: @Composable MenuScope.() -> Unit,
    viewMenu: @Composable MenuScope.() -> Unit,
) {
    val state = actions.state
    val exportTitle = stringResource(Res.string.export_pdf_dialog)
    MenuBar {
        Menu(stringResource(Res.string.menu_file)) {
            fileMenu()
            if (exportsPdf) {
                Item(stringResource(Res.string.menu_export_pdf), shortcut = KeyShortcut(Key.E, meta = true), enabled = !state.isBusy) {
                    actions.exportPdf(includeBuildSteps = false, exportTitle)
                }
                Item(
                    stringResource(Res.string.menu_export_pdf_builds),
                    shortcut = KeyShortcut(Key.E, meta = true, shift = true),
                    enabled = !state.isBusy,
                ) {
                    actions.exportPdf(includeBuildSteps = true, exportTitle)
                }
            }
        }
        Menu(stringResource(Res.string.menu_play)) {
            Item(stringResource(Res.string.menu_play_slideshow), shortcut = KeyShortcut(Key.P, meta = true, alt = true)) { actions.play() }
            Item(stringResource(Res.string.menu_stop)) { actions.stop() }
            Separator()
            CheckboxItem(
                stringResource(Res.string.menu_presenter_window),
                checked = state.isPresenterVisible,
                onCheckedChange = { if (it) state.isPresenterVisible = true else state.hidePresenter() },
            )
            Item(stringResource(Res.string.menu_toggle_fullscreen), shortcut = KeyShortcut(Key.F, meta = true, ctrl = true)) {
                actions.toggleFullscreen(state.slidesWindow)
            }
            Item(stringResource(Res.string.menu_swap_displays)) { actions.swapDisplays() }
            Separator()
            CheckboxItem(
                stringResource(Res.string.menu_black_screen),
                checked = actions.controller.isBlackedOut,
                onCheckedChange = { actions.controller.isBlackedOut = it },
            )
            CheckboxItem(
                stringResource(Res.string.menu_share_pointer),
                checked = actions.controller.isPointerShared,
                onCheckedChange = { actions.controller.isPointerShared = it },
            )
            CheckboxItem(
                stringResource(Res.string.menu_hide_still_pointer),
                checked = actions.controller.hidesStillPointer,
                onCheckedChange = { actions.controller.hidesStillPointer = it },
            )
            Item(stringResource(if (actions.controller.timer.isRunning) Res.string.menu_pause_timer else Res.string.menu_resume_timer)) {
                actions.controller.timer.toggle()
            }
            Item(stringResource(Res.string.menu_reset_timer)) { actions.controller.resetTimer() }
        }
        Menu(stringResource(Res.string.menu_view)) {
            viewMenu()
            AppearanceMenu(state.appearance) { state.appearance = it }
        }
        Menu(stringResource(Res.string.menu_go)) {
            Item(stringResource(Res.string.menu_next)) { actions.controller.next() }
            Item(stringResource(Res.string.menu_previous)) { actions.controller.previous() }
            Item(stringResource(Res.string.menu_first_slide)) { actions.controller.first() }
            Item(stringResource(Res.string.menu_last_slide)) { actions.controller.last() }
        }
    }
}

@Composable
private fun BoxScope.JumpIndicator(state: PresentationWindowsState) {
    if (state.jumpBuffer.isEmpty()) return
    Text(
        stringResource(Res.string.go_to_slide, state.jumpBuffer),
        Modifier.align(Alignment.BottomEnd).padding(24.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xE0202024))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = Color.White,
        fontSize = 20.sp,
    )
}

@Composable
private fun BoxScope.Toast(state: PresentationWindowsState) {
    val progress = state.exportProgress
    val message = if (progress != null) {
        stringResource(Res.string.exporting_pdf, progress.first, progress.second)
    } else {
        state.message ?: return
    }
    LaunchedEffect(message, state.isBusy) {
        if (state.isBusy) return@LaunchedEffect
        delay(3500)
        if (state.message == message) state.message = null
    }
    Column(
        Modifier.align(Alignment.BottomCenter).padding(24.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xE0202024))
            .keepsNoFocus().clickable { state.message = null }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(message, color = Color.White, fontSize = 15.sp)
    }
}

/** The keys the windows take before what has the focus: Esc, and what presenter remotes send to move the slides. */
private val KeysFirst = setOf(Key.Escape, Key.PageUp, Key.PageDown)

/** Keys that arrive on their own but are not presses of "any key"; ⌘, ⌃ and ⌥ never get that far. */
private val LoneModifierKeys = setOf(Key.ShiftLeft, Key.ShiftRight, Key.CapsLock)

// Made when first shown: without a display, as in tests, there is no cursor to make.
private val BlankPointer by lazy {
    PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank"),
    )
}

internal class WindowActions(
    val controller: PresentationController,
    val state: PresentationWindowsState,
    val scope: CoroutineScope,
) {
    var assetDirectory: File? = null

    /**
     * Handles a key pressed in [window] before what has the focus there sees it: Esc, which first
     * takes the focus back from a text field on a slide (see [WindowFocus]), and the keys
     * presenter remotes send.
     */
    fun handleKeyFirst(event: KeyEvent, window: WindowState, focus: WindowFocus): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key !in KeysFirst) return false
        if (event.key == Key.Escape && focus.clear()) return true
        return handleKey(event, window)
    }

    /** Handles a key pressed in [window] that what has the focus there left. */
    fun handleKey(event: KeyEvent, window: WindowState): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return false
        // While the screen is black, any key only brings the slides back, as in Keynote and PowerPoint.
        if (controller.isBlackedOut) {
            if (event.key in LoneModifierKeys) return false
            controller.isBlackedOut = false
            return true
        }
        digitOf(event.key)?.let {
            state.jumpBuffer = (state.jumpBuffer + it).take(4)
            return true
        }
        when (event.key) {
            Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.N -> controller.next()
            Key.Spacebar -> if (event.isShiftPressed) controller.previous() else controller.next()
            Key.Enter, Key.NumPadEnter -> {
                val target = state.jumpBuffer.toIntOrNull()
                state.jumpBuffer = ""
                if (target != null) controller.goToSlide(target - 1) else controller.next()
            }
            Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> controller.previous()
            Key.Backspace, Key.Delete ->
                if (state.jumpBuffer.isNotEmpty()) state.jumpBuffer = state.jumpBuffer.dropLast(1) else controller.previous()
            Key.MoveHome -> controller.first()
            Key.MoveEnd -> controller.last()
            Key.Escape -> if (state.jumpBuffer.isNotEmpty()) state.jumpBuffer = "" else stop()
            Key.F -> toggleFullscreen(window)
            Key.P -> if (state.isPresenterVisible) state.hidePresenter() else state.isPresenterVisible = true
            // Period too, like PowerPoint and Google Slides: what the black screen button of presenter remotes sends.
            Key.B, Key.Period -> controller.isBlackedOut = !controller.isBlackedOut
            Key.L -> controller.isPointerShared = !controller.isPointerShared
            Key.R -> controller.resetTimer()
            Key.T -> controller.timer.toggle()
            Key.S -> swapDisplays()
            Key.Equals, Key.Plus, Key.NumPadAdd -> state.notesFontSize = (state.notesFontSize + 2).coerceAtMost(72)
            Key.Minus, Key.NumPadSubtract -> state.notesFontSize = (state.notesFontSize - 2).coerceAtLeast(12)
            else -> return false
        }
        return true
    }

    fun toggleFullscreen(window: WindowState) {
        window.placement =
            if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    /**
     * Starts the slideshow: with a second display, the slides go full screen there and the
     * presenter view full screen on the main display. With one display, the slides go full screen.
     */
    fun play() {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = environment.defaultScreenDevice
        val external = environment.screenDevices.firstOrNull { it != main }
        scope.launch {
            state.isSwapped = false
            if (external != null) {
                state.isPresenterVisible = true
                placeOn(slidesDevice = external, presenterDevice = main)
            } else {
                state.first.placement = WindowPlacement.Fullscreen
            }
            controller.resetTimer()
            controller.timer.start()
        }
    }

    fun stop() {
        state.first.placement = WindowPlacement.Floating
        state.second.placement = WindowPlacement.Floating
    }

    /**
     * Swaps the slides and the presenter view between the two windows, which stay where they are:
     * on two displays, that swaps the displays; on one, which of them is full screen.
     */
    fun swapDisplays() {
        if (!state.isPresenterVisible) {
            state.isPresenterVisible = true
            state.isSwapped = true
        } else {
            state.isSwapped = !state.isSwapped
        }
    }

    private suspend fun placeOn(slidesDevice: GraphicsDevice, presenterDevice: GraphicsDevice) {
        val wasFullscreen = state.first.placement == WindowPlacement.Fullscreen ||
            state.second.placement == WindowPlacement.Fullscreen
        state.first.placement = WindowPlacement.Floating
        state.second.placement = WindowPlacement.Floating
        // Leaving native full screen is animated on macOS; moving the window before it ends is ignored.
        if (wasFullscreen) delay(900)
        state.first.position = centeredOn(slidesDevice, state.first.size)
        state.second.position = centeredOn(presenterDevice, state.second.size)
        delay(300)
        state.first.placement = WindowPlacement.Fullscreen
        state.second.placement = WindowPlacement.Fullscreen
    }

    private fun centeredOn(device: GraphicsDevice, size: DpSize): WindowPosition {
        val bounds = device.defaultConfiguration.bounds
        val width = size.width.value.toInt().coerceAtMost(bounds.width)
        val height = size.height.value.toInt().coerceAtMost(bounds.height)
        return WindowPosition(
            (bounds.x + (bounds.width - width) / 2).dp,
            (bounds.y + (bounds.height - height) / 2).dp,
        )
    }

    fun exportPdf(includeBuildSteps: Boolean, dialogTitle: String) {
        val presentation = controller.presentation
        val dialog = FileDialog(state.firstFrame, dialogTitle, FileDialog.SAVE).apply {
            file = presentation.title.replace(Regex("""[/\\:*?"<>|]"""), "_") + ".pdf"
            isVisible = true
        }
        val name = dialog.file ?: return
        val target = File(dialog.directory, if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf")
        state.isBusy = true
        scope.launch { export(presentation, target, includeBuildSteps) }
    }

    /** Exports [presentation] to [target] off the UI thread, showing the progress, then what came of it. */
    private suspend fun export(presentation: Presentation, target: File, includeBuildSteps: Boolean) {
        val result = runCatching { exportOffUiThread(presentation, target, includeBuildSteps) }
        state.exportProgress = null
        state.isBusy = false
        state.message = result.fold(onSuccess = { exportedMessage(it, target) }, onFailure = { failureMessage(it) })
        result.exceptionOrNull()?.printStackTrace()
    }

    private suspend fun exportOffUiThread(presentation: Presentation, target: File, includeBuildSteps: Boolean): PdfExportResult =
        withContext(Dispatchers.Default) {
            presentation.exportPdf(target, PdfExportOptions(includeBuildSteps = includeBuildSteps), assetDirectory, ::showProgress)
        }

    private fun showProgress(done: Int, total: Int) {
        state.exportProgress = done to total
    }

    private suspend fun exportedMessage(export: PdfExportResult, target: File): String =
        if (export.imagePages.isEmpty()) {
            getString(Res.string.exported, target.name)
        } else {
            getString(Res.string.exported_with_image_pages, target.name, export.imagePages.joinToString())
        }

    private suspend fun failureMessage(failure: Throwable): String =
        getString(Res.string.export_failed, failure.message ?: failure::class.simpleName.orEmpty())

    private fun digitOf(key: Key): Char? = when (key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
}

/** An "Appearance" submenu choosing between [Appearance.System], [Appearance.Light] and [Appearance.Dark]. */
@Composable
fun MenuScope.AppearanceMenu(appearance: Appearance, onChange: (Appearance) -> Unit) {
    Menu(stringResource(Res.string.menu_appearance)) {
        for (option in Appearance.entries) {
            RadioButtonItem(stringResource(option.label), selected = appearance == option) { onChange(option) }
        }
    }
}

private val Appearance.label: StringResource
    get() = when (this) {
        Appearance.System -> Res.string.appearance_system
        Appearance.Light -> Res.string.appearance_light
        Appearance.Dark -> Res.string.appearance_dark
    }
