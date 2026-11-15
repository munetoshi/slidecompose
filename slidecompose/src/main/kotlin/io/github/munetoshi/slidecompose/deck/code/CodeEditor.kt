package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.theme.CodeColors
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideStyle
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideTheme
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.deck.transition.timeline
import io.github.munetoshi.slidecompose.deck.transition.totalMillis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.withIndex

/**
 * Whether an [CodeBlock] shows where another window's copy of it is being typed in: while
 * the presenter's pointer is shared, the windows share the caret too. Whoever plays the slides
 * provides it.
 */
val LocalSharesCaret = staticCompositionLocalOf { false }

/**
 * The deck that the windows show live, as it was loaded: a reload loads another one, and a
 * [CodeBlock] in it starts over. Whoever plays the slides provides it; pictures of slides do not.
 */
internal val LocalShownDeck = staticCompositionLocalOf<Presentation?> { null }

/** The tag of an [CodeBlock]'s editor, for tests. */
internal const val CodeEditorTag = "CodeEditor"

/** The tag of the part of an [CodeBlock]'s editor in view, when it shows only some lines, for tests. */
internal const val CodeViewportTag = "CodeViewport"

/**
 * A code block, colored as code in [language] (see [highlightCode]), as wide as its code. With
 * [editable], the code can be changed on the slide.
 *
 * While the code is changed, the editor takes the keys it types, so they do not move the slides;
 * Esc, which the windows take first, leaves it. Tab puts in four spaces, and ⌘/ (Ctrl+/ off macOS)
 * comments the lines of the selection out, or in again. Every window showing the block shows the
 * same code. It starts over from [code] when the block is shown again after no window showed it,
 * as the slides moved on from it or the deck was reloaded, unless [resetsOnLeave] is false.
 *
 * Something built on the block, such as a button that runs the code, takes its state from
 * [rememberCodeBlockState] and gives it to the other [CodeBlock].
 *
 * @param id takes part in element transitions: the block moves and resizes to the block with the
 *   same id in the next frame, and its code moves into that block's token by token, as it does
 *   between versions. It also tells apart blocks that start with the
 *   same [code].
 * @param from the ids of other code blocks of the frame before whose code moves into it: the code
 *   of two blocks side by side can move into one.
 * @param focusLines dims the other lines (from 1). With [editable], a slide can go through the
 *   code a few lines at a time before it is changed:
 *   ```
 *   slide(steps = focus.size + 1) {
 *       CodeBlock(code, focusLines = focus.getOrNull(step), editable = step == focus.size)
 *   }
 *   ```
 * @param focusRanges the parts of [code] in focus, by their offsets in it as [CodeBlock] trims it
 *   (without the indent its lines share, or the blank lines at its end), with the rest dimmed, in
 *   place of [focusLines]: a build can show in focus what it changes, and only that.
 * @param editable whether the code can be changed. Until it is, the block takes no clicks or keys,
 *   which go on moving the slides.
 * @param resetsOnLeave whether what was typed goes, once no window shows the block, as the slides
 *   moved on from it or the deck was reloaded: shown again, it starts over from [code]. Folds and
 *   scrolling start over too.
 * @param visibleLines how many lines the block shows at most, or null for all: it scrolls, by the
 *   wheel or the trackpad, and to keep the caret in view.
 * @param scrollTo the line (from 1) a build scrolls to the top of the block, with [visibleLines].
 *   Without it, the block scrolls [focusLines] into view when they are not.
 * @param folds regions of [code] (lines from 1) that can be folded: folded, a region shows its first
 *   line and ` ⋯`. A gutter on the left of the code has a mark at the first line of each region,
 *   which folds or unfolds it when clicked, as `⋯` unfolds it. A region follows its lines when
 *   lines are typed around it, and unfolds once its own lines change.
 * @param folded the regions folded at this build, which can be folded too: a slide folds and unfolds
 *   them build by build, and the code moves into place as it does between versions. What a click
 *   folded or unfolded holds until a build folds others.
 * @param hiddenLines regions of [code] (lines from 1) not shown at this build: a slide reveals them
 *   build by build. Like folded regions, they follow their lines when lines are typed around them.
 * @param widthFor other code the block is at least as wide as, such as the code of the slide's
 *   other builds: it keeps the width of the widest from the start, rather than growing build by build.
 * @param anchors parts of [code] by id, by their offsets in it as [focusRanges] has them: as the code
 *   moves into other code, a part moves into the part of the same id there, token by token, and into
 *   nothing else, however alike another line is. A build that changes a line, and adds another line
 *   much like it, keeps the line changing in its place with an anchor on both.
 *
 * The windows showing the block share its folds and where it is scrolled, as they share its code.
 *
 * A slide can also give the block other [code] build by build, under the same [id]: the code moves
 * from one version into the next token by token (see the [CodeBlock] of versions), and so does it
 * when regions fold, unfold, hide or show. What is typed into a version stays with it.
 */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    language: String = "kotlin",
    fontSize: TextUnit = TextUnit.Unspecified,
    focusLines: IntRange? = null,
    id: Any? = null,
    from: List<Any> = emptyList(),
    editable: Boolean = false,
    resetsOnLeave: Boolean = true,
    visibleLines: Int? = null,
    scrollTo: Int? = null,
    folds: List<IntRange> = emptyList(),
    folded: List<IntRange> = emptyList(),
    hiddenLines: List<IntRange> = emptyList(),
    focusRanges: List<IntRange>? = null,
    widthFor: List<String> = emptyList(),
    anchors: Map<Any, IntRange> = emptyMap(),
) {
    CodeBlock(
        rememberCodeBlockState(code, id, resetsOnLeave),
        modifier, language, fontSize, focusLines, from, editable, visibleLines, scrollTo, folds, folded, hiddenLines, focusRanges, widthFor, anchors,
    )
}

/**
 * The [CodeBlock] with the code in [state], which [rememberCodeBlockState] gives: for something
 * built on the block, which reads the code too. The other parameters are those of the same names
 * there.
 */
@Composable
fun CodeBlock(
    state: CodeBlockState,
    modifier: Modifier = Modifier,
    language: String = "kotlin",
    fontSize: TextUnit = TextUnit.Unspecified,
    focusLines: IntRange? = null,
    from: List<Any> = emptyList(),
    editable: Boolean = false,
    visibleLines: Int? = null,
    scrollTo: Int? = null,
    folds: List<IntRange> = emptyList(),
    folded: List<IntRange> = emptyList(),
    hiddenLines: List<IntRange> = emptyList(),
    focusRanges: List<IntRange>? = null,
    widthFor: List<String> = emptyList(),
    anchors: Map<Any, IntRange> = emptyMap(),
) {
    val theme = LocalSlideTheme.current
    val style = codeTextStyle(theme, fontSize)
    val transition = rememberCodeTransition(state.id, from, style)
    val view = CodeView(language, focusLines, visibleLines, scrollTo, (folds + folded).distinct(), folded, hiddenLines, focusRanges, anchors)
    val measurer = rememberTextMeasurer()
    val reservedWidth = remember(widthFor, style, measurer) { widestOf(widthFor, style, measurer) }
    // As wide as its code, unless the caller sizes it: a modifier that fixes the width wins.
    CodeEditor(state, style, theme, view, editable, transition, reservedWidth, modifier.width(IntrinsicSize.Max))
}

/**
 * The [CodeBlock] of `versions[version]`, moving from version to version token by token as the
 * builds go: code that stays moves to its new place, removed code fades out, and added code fades
 * in. Changes are found token by token, so an argument put into a line moves the rest of the line
 * aside instead of replacing the whole line. Unless [highlightChanges] is `false`, the code a
 * version adds is in focus and the rest dimmed, but in the first version. The block is as wide as
 * the widest of [versions] from the first one on. On a slide with build steps, pass the step:
 * ```
 * slide(steps = versions.size) { CodeBlock(versions, version = step) }
 * ```
 * The other parameters are those of the same names there; the code takes as long to move as the
 * slide's [CodeStyle][io.github.munetoshi.slidecompose.deck.theme.CodeStyle] says.
 */
@Composable
fun CodeBlock(
    versions: List<String>,
    version: Int,
    modifier: Modifier = Modifier,
    language: String = "kotlin",
    fontSize: TextUnit = TextUnit.Unspecified,
    highlightChanges: Boolean = true,
    id: Any? = null,
    from: List<Any> = emptyList(),
) {
    require(versions.isNotEmpty()) { "versions must not be empty" }
    val shown = version.coerceIn(versions.indices)
    val added = remember(versions, shown, highlightChanges) { if (highlightChanges) addedParts(versions, shown) else null }
    CodeBlock(versions[shown], modifier, language, fontSize, id = id, from = from, focusRanges = added, widthFor = versions)
}

/**
 * The parts of `versions[index]` the version before does not have, as [CodeBlock]'s `focusRanges`
 * takes them, or null in the first version and in one that adds nothing: see the other
 * [addedParts]. For a block given its code version by version under an [id][CodeBlock], to show in
 * focus what each adds, as the [CodeBlock] of versions does:
 * ```
 * CodeBlock(versions[step], id = "demo", folded = folds[step], focusRanges = addedParts(versions, step))
 * ```
 */
fun addedParts(versions: List<String>, index: Int): List<IntRange>? =
    if (index <= 0) null else addedParts(versions[index - 1], versions[index])

/**
 * The parts of [after] that [before] does not have, found token by token as a [CodeBlock] moves
 * from one into the other, by their offsets in [after] as [CodeBlock] trims it (as its
 * `focusRanges`), or null when it adds nothing.
 */
fun addedParts(before: String, after: String): List<IntRange>? {
    val new = SourceCode(after.trimIndent().trimEnd())
    val matches = matchTokens(SourceCode(before.trimIndent().trimEnd()), new)
    return new.tokens.asSequence()
        .filterIndexed { i, _ -> matches[i] < 0 }
        .map { it.offset until it.offset + it.text.length }
        .toList()
        .takeIf { it.isNotEmpty() }
}

/** How wide the widest of [codes] is laid out in [style], unwrapped, or 0 for none. */
internal fun widestOf(codes: List<String>, style: TextStyle, measurer: TextMeasurer): Int =
    codes.maxOfOrNull { measurer.measure(it.trimIndent().trimEnd(), style, softWrap = false).size.width } ?: 0

/**
 * The state of the [CodeBlock] that starts with [code] under [id], which every window
 * showing it shares: see [CodeBlock] for the parameters. While it is composed, the block
 * counts as shown, and once no window composes it, it starts over from [code] when it is shown
 * again, if [resetsOnLeave].
 */
@Composable
fun rememberCodeBlockState(code: String, id: Any? = null, resetsOnLeave: Boolean = true): CodeBlockState {
    val source = remember(code) { code.trimIndent().trimEnd() }
    // Before anything shows its code: coming back, it may start over.
    val deck = LocalShownDeck.current
    val state = remember(id, source, resetsOnLeave, deck) { CodeBlockStates.of(id, source).also { it.comeBack(resetsOnLeave, deck) } }
    DisposableEffect(state) {
        state.hold()
        onDispose { state.release() }
    }
    return state
}

/**
 * The code of an [CodeBlock], as it is typed in, which the windows showing the block
 * share.
 */
@Stable
class CodeBlockState internal constructor(
    internal val id: Any?,
    private val source: String,
) {
    internal var text by mutableStateOf(TextFieldValue(source))

    /** The code as it is now, with what was typed into it. */
    val code: String get() = text.text

    /**
     * How many times the block started over from its code, as the slides came back to it: what
     * was made of the code before then, such as what it printed, goes with it.
     */
    var restarts by mutableIntStateOf(0)
        private set

    /** The lines of the code the block was given, which its folds are regions of. */
    internal val sourceLines: List<String> = source.split('\n')

    /** Where the editor is scrolled to, in lines, as the windows' copies share it. */
    internal var scrollLines by mutableFloatStateOf(0f)

    /** The regions folded after a click, and the regions folded by the build it was clicked in. */
    private var clicked by mutableStateOf<Pair<List<IntRange>, Set<IntRange>>?>(null)

    /** The regions folded now, at a build that folds [byBuild]: what was clicked holds until a build folds others. */
    internal fun foldedAt(byBuild: List<IntRange>): Set<IntRange> = clicked?.takeIf { it.first == byBuild }?.second ?: byBuild.toSet()

    /** Folds [region] if it is not folded at a build that folds [byBuild], and unfolds it if it is. */
    internal fun toggle(region: IntRange, byBuild: List<IntRange>) {
        val now = foldedAt(byBuild)
        clicked = byBuild to (if (region in now) now - setOf(region) else now + setOf(region))
    }

    /** The editor that has the focus, of the windows' copies of the block, or null. */
    internal var focusedIn: Any? by mutableStateOf(null)
        private set

    /** Notes whether [editor], one of the copies, has the focus. */
    internal fun focusChanged(editor: Any, focused: Boolean) {
        if (focused) {
            focusedIn = editor
        } else if (focusedIn === editor) {
            focusedIn = null
        }
    }

    /**
     * The code as it is now, without the regions [hidden] at a build (lines from 1 of the code the
     * block was given, as [CodeBlock]'s `hiddenLines`): what shows, folded regions included.
     */
    fun codeWithout(hidden: List<IntRange>): String {
        val code = text.text
        val folds = hiddenIn(code, sourceLines, hidden).withoutOverlaps()
        return if (folds.isEmpty()) code else fold(AnnotatedString(code), folds, AnnotatedString("")).text.text
    }

    /** How many copies of the block windows show now. */
    private var holders = 0

    /** Whether the last copy went, and none shows since. */
    private var isLeft = false

    /** The deck the windows showed the block in last, as it was loaded, or null before they did. */
    private var shownIn: Presentation? = null

    /** Notes that a window shows a copy of the block. */
    internal fun hold() {
        holders++
        isLeft = false
    }

    /** Notes that a window no longer shows its copy. */
    internal fun release() {
        holders--
        isLeft = holders == 0
    }

    /**
     * Starts over from the code, if [resets] and no window shows the block since the last copy
     * went, as the slides moved on from it, or the windows show it in [deck], a deck loaded again
     * (null for a picture of a slide). A new copy that comes while the old one still shows, as a
     * build with a step transition makes one, keeps what was typed.
     */
    internal fun comeBack(resets: Boolean, deck: Presentation?) {
        val isReloaded = deck != null && shownIn != null && deck !== shownIn
        if (deck != null) shownIn = deck
        if (resets && (isLeft || isReloaded)) {
            isLeft = false
            text = TextFieldValue(source)
            clicked = null
            scrollLines = 0f
            restarts++
        }
    }

    /** Puts [typed] in place of the selection, as typing it would. */
    internal fun type(typed: String) {
        val selection = text.selection
        text = TextFieldValue(text.text.replaceRange(selection.min, selection.max, typed), TextRange(selection.min + typed.length))
    }
}

/**
 * The state of every [CodeBlock] in this app, by its id and code: the windows showing a
 * slide compose it each on its own, and show the same code.
 */
internal object CodeBlockStates {
    private val states = HashMap<Pair<Any?, String>, CodeBlockState>()

    fun of(id: Any?, source: String): CodeBlockState = states.getOrPut(id to source) { CodeBlockState(id, source) }
}


private const val TAB = "    "

/**
 * How the editor shows the code at a build: see [CodeBlock]'s parameters of the same names.
 * [foldable] are the regions that can be folded, those [folded] at this build among them, and
 * [hidden] the regions not shown. With [focusRanges], only those parts of the code are in focus.
 */
@Immutable
private data class CodeView(
    val language: String,
    val focusLines: IntRange?,
    val visibleLines: Int?,
    val scrollTo: Int?,
    val foldable: List<IntRange>,
    val folded: List<IntRange>,
    val hidden: List<IntRange>,
    val focusRanges: List<IntRange>? = null,
    val anchors: Map<Any, IntRange> = emptyMap(),
) {
    /** Whether some of the code is out of focus, dimmed. */
    val dims: Boolean get() = focusLines != null || focusRanges != null
}

@Composable
private fun CodeEditor(
    state: CodeBlockState,
    style: TextStyle,
    theme: SlideTheme,
    view: CodeView,
    editable: Boolean,
    transition: CodeTransition,
    reservedWidth: Int,
    modifier: Modifier,
) {
    val text = state.text.text
    val folded = state.foldedAt(view.folded).filterTo(HashSet()) { it in view.foldable }
    val mark = remember(theme.muted) { AnnotatedString(FOLD_MARK, SpanStyle(color = theme.muted)) }
    val folding = remember(text, state, folded, view.language, view.hidden, theme.code, view.focusLines, view.focusRanges, mark) {
        foldedCode(text, state.sourceLines, folded, view, theme.code, mark)
    }
    val highlighting = remember(state, folded, view.language, view.hidden, theme.code, view.focusLines, view.focusRanges, mark) {
        CodeHighlighting(state.sourceLines, folded, view, theme.code, mark)
    }
    // The code as it shows at rest, for code moving in or on with an element transition: the
    // editor itself is laid out narrower while its box grows, and wraps.
    val measurer = rememberTextMeasurer()
    val shownLayout = remember(folding, style, measurer) { measurer.measure(folding.text, style, softWrap = false) }
    val shown = remember(text, folding, shownLayout, view.anchors) {
        // In focus, how the code shows while a build changes it.
        val inFocus = if (view.dims) fold(highlightCode(text, view.language, theme.code, null), folding.folds, mark).text else null
        shownCode(text, folding, shownLayout, inFocus, focusesParts = view.focusRanges != null, anchors = view.anchors)
    }
    SideEffect { transition.look.shown = shown }
    val refolding = rememberRefolding(Showing(state, text, shown, shownLayout.size.width.toFloat()), style, measurer)
    val editor = remember { Any() }
    DisposableEffect(state, editor) {
        onDispose { state.focusChanged(editor, focused = false) }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lineMoves = remember { LineMoves() }
    val focused = state.focusedIn === editor
    val typedElsewhere = state.focusedIn != null && !focused && LocalSharesCaret.current
    val caretOn = rememberCaretBlink(state.text, shown = focused || typedElsewhere)
    val density = LocalDensity.current
    val lineHeight = with(density) { style.lineHeight.toPx() }
    val scroll = rememberScrollState()
    SharedScroll(scroll, lineHeight, { state.scrollLines }) { state.scrollLines = it }
    ScrollsAtBuilds(state, view, folding, text)
    val viewport = view.visibleLines?.let { with(density) { (it * lineHeight).toDp() } }
    val toggles = remember { FoldToggles() }
    val foldedByBuild by rememberUpdatedState(view.folded)
    // The code of this build, which another build may change.
    val current by rememberUpdatedState(state)
    // The marks that fold and unfold regions go in the box's padding on the left, which the text
    // field puts there itself to take clicks on them: the code is where a CodeBlock's is.
    val gutter = theme.codeBlock.padding.calculateLeftPadding(LayoutDirection.Ltr)
    Box(
        modifier
            .then(transition.boxModifier)
            .fillMaxWidth()
            .codeContainer(theme, padsStart = false)
            .then(if (viewport == null) Modifier else Modifier.testTag(CodeViewportTag).heightIn(max = viewport).verticalScroll(scroll)),
    ) {
        // The text field draws the selection even where it is not typed in; a copy elsewhere shows
        // it only as the editor draws it, while the windows share the caret.
        CompositionLocalProvider(LocalTextSelectionColors provides if (focused) LocalTextSelectionColors.current else NoSelectionColors) {
            BasicTextField(
                value = state.text,
                onValueChange = { state.text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CodeEditorTag)
                    .onFocusChanged { state.focusChanged(editor, it.isFocused) }
                    .onPreviewKeyEvent { editorKey(it, state) || commentKey(it, state, view.language) || typesWithCtrl(it) || lineMoves.move(it, state, layout, folding) }
                    // A key the text field leaves, such as a letter it types from the character that
                    // follows, stops here: it is typing, not a key for the windows.
                    .onKeyEvent { it.type == KeyEventType.KeyDown && !it.isMetaPressed && !it.isCtrlPressed }
                    // Before the text field, and over the gutter: a click on a mark folds or unfolds,
                    // and goes no further.
                    .pointerInput(toggles) { clickFolds(toggles) { current.toggle(it, foldedByBuild) } }
                    .drawWithContent {
                        drawContent()
                        toggles.gutter.forEach { drawFoldMark(it.area, theme.muted, it.folded) }
                    }
                    .padding(start = gutter)
                    .then(transition.textModifier)
                    // Folded or unfolded, it changes height as the code moves, so that none of it is cut
                    // off, and width with it: a block as wide as its code grows and shrinks as it moves.
                    // It is never narrower than the code it keeps room for.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = max(refolding.width(placeable.width), reservedWidth).coerceIn(constraints.minWidth, constraints.maxWidth)
                        // Within the room it has, too: taller, it would be centered in it, and the code shift up.
                        val height = refolding.height(placeable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .drawWithContent {
                        transition.look.visible = viewport?.let { scroll.value.toFloat()..(scroll.value + it.toPx()) }
                        val moving = refolding.tokens
                        if (moving == null) {
                            drawEditor(layout, state.text.selection, folding.mapping, typedElsewhere, caretOn, theme)
                        } else {
                            drawMorph(moving, Offset.Zero, Offset.Zero, refolding.morph)
                        }
                        // Where the marks are, for the gutter to draw and for clicks, in its coordinates.
                        val width = gutter.toPx()
                        val marks = layout?.let { gutterMarks(it, folding, text, state.sourceLines, view.foldable, width) } ?: emptyList()
                        // While the code moves, each mark goes with its line.
                        toggles.place(marks.map { refolding.moving(it, shown) }, layout?.let { foldedMarks(it, folding) } ?: emptyList(), Offset(width, 0f))
                    },
                enabled = editable,
                textStyle = style,
                // The editor draws the caret itself: the text field's is 2 slide units wide, which is
                // hardly a pixel on a slide drawn small, as in the presenter window.
                cursorBrush = SolidColor(Color.Transparent),
                onTextLayout = { layout = it },
                visualTransformation = highlighting,
            )
        }
    }
}

/** What shows in place of the lines of a folded region. */
private const val FOLD_MARK = " ⋯"

/** No selection at all, as a copy of the editor not typed in draws none of its own. */
private val NoSelectionColors = TextSelectionColors(handleColor = Color.Transparent, backgroundColor = Color.Transparent)

/**
 * [text] colored as code in the [view]'s language, with the lines out of its focus dimmed, the
 * regions of [sourceLines] in [folded] folded, and those it hides hidden.
 */
private fun foldedCode(
    text: String,
    sourceLines: List<String>,
    folded: Set<IntRange>,
    view: CodeView,
    colors: CodeColors,
    mark: AnnotatedString,
): FoldedText {
    val folds = (foldsIn(text, sourceLines, folded) + hiddenIn(text, sourceLines, view.hidden)).withoutOverlaps()
    return fold(highlightCode(text, view.language, colors, view.focusLines, view.focusRanges), folds, mark)
}

/** The row (from 0) that line [line] (from 1) of [code] shows on, with some of it folded. */
private fun FoldedText.rowOf(line: Int, code: String): Int {
    val lines = code.split('\n')
    val offset = lines.take((line - 1).coerceIn(0, lines.lastIndex)).sumOf { it.length + 1 }
    return text.text.substring(0, mapping.originalToTransformed(offset)).count { it == '\n' }
}

/** A region's mark in the gutter: where it is, and whether the region is folded. */
private class GutterMark(val region: IntRange, val area: Rect, val folded: Boolean)

/** Where the editor's fold marks were last drawn, to draw the gutter's, and to fold and unfold regions with a click. */
private class FoldToggles {
    /** The gutter's marks, in the coordinates of the editor with its gutter. */
    var gutter: List<GutterMark> = emptyList()
        private set

    /** Where to click to fold or unfold each region, in the same coordinates. */
    private var areas: List<Pair<IntRange, Rect>> = emptyList()

    /** Notes the gutter's [marks] and the [folded] regions' marks, in the coordinates of the code, which start at [code] in the editor's. */
    fun place(marks: List<GutterMark>, folded: List<Pair<IntRange, Rect>>, code: Offset) {
        gutter = marks.map { GutterMark(it.region, it.area.translate(code), it.folded) }
        areas = gutter.map { it.region to it.area } + folded.map { (region, area) -> region to area.translate(code).inflate(area.height * FOLD_CLICK_SLACK) }
    }

    fun at(position: Offset): IntRange? = areas.firstOrNull { it.second.contains(position) }?.first
}

/**
 * The gutter's marks, [width] wide on the left of [layout] of the code as shown: one at the first
 * line of each region of [foldable] in [text], unless a folded region hides that line.
 */
private fun gutterMarks(layout: TextLayoutResult, folding: FoldedText, text: String, sourceLines: List<String>, foldable: List<IntRange>, width: Float): List<GutterMark> {
    if (width <= 0f) return emptyList()
    val length = layout.layoutInput.text.length
    val folded = folding.folds.filter { it.marked }.mapTo(HashSet()) { it.region }
    return foldable.mapNotNull { region -> foldsIn(text, sourceLines, listOf(region)).firstOrNull() }
        .mapNotNull { fold ->
            val start = text.lastIndexOf('\n', fold.hidden.start - 1) + 1
            if (folding.folds.any { it.hides(start) }) return@mapNotNull null
            val line = layout.getLineForOffset(folding.mapping.originalToTransformed(start).coerceIn(0, length))
            GutterMark(fold.region, Rect(-width, layout.getLineTop(line), 0f, layout.getLineBottom(line)), fold.region in folded)
        }
}

/** Where the marks of the regions [folding] folds are, in [layout] of the code as shown: clicked, they unfold. */
private fun foldedMarks(layout: TextLayoutResult, folding: FoldedText): List<Pair<IntRange, Rect>> {
    val length = layout.layoutInput.text.length
    return folding.marks.filter { it.range.end <= length }
        .map { mark -> mark.region to layout.getBoundingBox(mark.range.start).copy(right = layout.getBoundingBox(mark.range.end - 1).right) }
}

/** How large a gutter's mark is, against a line's height. */
private const val FOLD_MARK_SIZE = 0.38f

/** How much a click may miss the mark of a folded region by, against its height. */
private const val FOLD_CLICK_SLACK = 0.25f

/** Draws a gutter's mark in [area]: a chevron pointing down for a region that shows, and right for one [folded]. */
private fun DrawScope.drawFoldMark(area: Rect, color: Color, folded: Boolean) {
    val half = area.height * FOLD_MARK_SIZE / 2f
    val center = area.center
    val stroke = max(half / 3f, 1f)
    val path = Path().apply {
        if (folded) {
            moveTo(center.x - half / 2, center.y - half)
            lineTo(center.x + half / 2, center.y)
            lineTo(center.x - half / 2, center.y + half)
        } else {
            moveTo(center.x - half, center.y - half / 2)
            lineTo(center.x, center.y + half / 2)
            lineTo(center.x + half, center.y - half / 2)
        }
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Folds or unfolds the region whose mark is clicked, with [onToggle]: the click goes no further. */
private suspend fun PointerInputScope.clickFolds(toggles: FoldToggles, onToggle: (IntRange) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val region = toggles.at(down.position) ?: return@awaitEachGesture
        down.consume()
        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
        up.consume()
        onToggle(region)
    }
}

/**
 * The code moving into place when a build changes how it shows, as it does between versions
 * between versions, in stages (see [RefoldingTimeline]).
 */
@Stable
private class Refolding {
    private var move by mutableStateOf<RefoldingMove?>(null)

    /** A new one for each change, going from 0 to 1, which the editor draws from at once. */
    var progress by mutableStateOf(Animatable(1f))
        private set

    /** How long the move that [progress] is the progress of takes. */
    val totalMillis: Int get() = move?.timeline?.totalMillis ?: 0

    val tokens: List<MorphToken>? get() = move?.tokens
    val morph: MorphProgress get() = checkNotNull(move).timeline.at(progress.value)

    /** The code as shown that the tokens move into, which they start moving into once. */
    private var target: ShownCode? = null

    /**
     * Starts moving the code from [from], [fromWidth] wide, to [to], [toWidth] wide, unless it
     * already does, the code changing for [editMillis].
     */
    fun start(from: ShownCode, fromWidth: Float, to: ShownCode, toWidth: Float, style: TextStyle, measurer: TextMeasurer, editMillis: Int) {
        if (target === to) return
        target = to
        val tokens = morphTokens(listOf(PlacedCode(from, style, Offset.Zero, null)), PlacedCode(to, style, Offset.Zero, null), measurer, recolors = true)
        val timeline = RefoldingTimeline.of(tokens, to.foldMarks, editMillis)
        val bands = if (timeline.closesUp) foldedBands(tokens) else emptyList()
        val heights = Heights(from.height, from.height - bands.heightAbove(Float.POSITIVE_INFINITY), to.height)
        move = RefoldingMove(tokens.map { closingUp(it, bands) }, heights, fromWidth, toWidth, timeline)
        progress = Animatable(0f)
    }

    /** Ends the move that [progress] was the progress of, unless another started since. */
    fun end(progress: Animatable<Float, *>) {
        if (this.progress === progress) move = null
    }

    /** How tall the text is to be, laid out [current] pixels tall: on its way from how tall it was, while it moves. */
    fun height(current: Int): Int = move?.heightAt(morph)?.roundToInt() ?: current

    /** How wide the text is to be, laid out [current] pixels wide: on its way from how wide it was, while it moves. */
    fun width(current: Int): Int = move?.widthAt(morph)?.roundToInt() ?: current

    /** [mark] of [shown], at the first line of its region: where that line is on its way, while the code moves. */
    fun moving(mark: GutterMark, shown: ShownCode): GutterMark {
        val moving = tokens ?: return mark
        val first = shown.places.indexOfFirst { it != null && abs(it.y - mark.area.top) < 0.5f }.takeIf { it >= 0 } ?: return mark
        val token = moving.firstOrNull { it.index == first } ?: return mark
        val end = token.end ?: return mark
        val y = token.placeAt(Offset.Zero, Offset.Zero, morph).y
        return GutterMark(mark.region, mark.area.translate(0f, y - end.y), mark.folded)
    }
}

/**
 * How tall code is at the start of a [RefoldingMove], once it has closed up under what folds away
 * ([mid], as at the start unless it does), and at its end.
 */
private class Heights(val start: Float, val mid: Float, val end: Float)

/**
 * A move of a [Refolding]: the [tokens] on their way, how tall the code is on its way, how wide it
 * is at its start and at its end, and how it is timed.
 */
private class RefoldingMove(
    val tokens: List<MorphToken>,
    private val heights: Heights,
    private val startWidth: Float,
    private val endWidth: Float,
    val timeline: RefoldingTimeline,
) {
    /** How tall the code is as far as [progress] has it: closing up as what folds goes, then on to its end. */
    fun heightAt(progress: MorphProgress): Float =
        if (progress.moved > 0f) lerp(heights.mid, heights.end, progress.moved) else lerp(heights.start, heights.mid, progress.folded)

    /** How wide the code is as far as [progress] has it: it widens or narrows as the code moves. */
    fun widthAt(progress: MorphProgress): Float = lerp(startWidth, endWidth, progress.moved)
}

/** Rows that code folding away leaves, from [top] to [bottom] at the start: the code under them closes up by their [height]. */
private class FoldedBand(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/** The rows that what folds away among [tokens] leaves. */
private fun foldedBands(tokens: List<MorphToken>): List<FoldedBand> =
    tokens.mapNotNull { it.clip }.filterNot { it.opens }.distinct().map { FoldedBand(it.startTop, it.startBottom) }

/** How far code at [y] at the start closes up under the bands above it. */
private fun List<FoldedBand>.heightAbove(y: Float): Float = filter { it.bottom <= y + 0.5f }.sumOf { it.height.toDouble() }.toFloat()

/**
 * [token] closing up under [bands] before it moves on: to where it is once they are gone, or, if it
 * folds away, into where its region's line is by then.
 */
private fun closingUp(token: MorphToken, bands: List<FoldedBand>): MorphToken {
    val start = token.start ?: return token
    val clip = token.clip
    return when {
        bands.isEmpty() -> token
        clip == null -> token.closingUp(mid = Offset(start.x, start.y - bands.heightAbove(start.y)))
        clip.opens -> token
        else -> {
            val endTop = clip.startTop - bands.heightAbove(clip.startTop)
            token.closingUp(mid = null, end = Offset(start.x, endTop - (clip.startBottom - start.y)), clip = clip.copy(endTop = endTop, endBottom = endTop))
        }
    }
}

/**
 * How a build's change to the code is timed, in milliseconds: first the code to change shows in
 * focus ([highlightMillis]); then it changes ([editMillis]): what goes fades out and what folds
 * away slides under its region's line, then what stays moves, and what comes fades in, while what
 * unfolds slides out after what makes room for it; and after a pause, what leaves the focus dims
 * ([dimMillis]). Stages with nothing to do take no time. When code also [comes] where code folds
 * away, the change [closesUp]: the code under the fold first closes up with it, and only then makes
 * room for what comes, each in time of its own, so that the fold does not look like code replaced.
 */
private class RefoldingTimeline(
    private val highlightMillis: Int,
    private val editMillis: Int,
    private val pauseMillis: Int,
    private val dimMillis: Int,
    private val folds: Boolean,
    private val unfolds: Boolean,
    private val comes: Boolean,
) {
    val totalMillis: Int get() = highlightMillis + editMillis + pauseMillis + dimMillis

    /** Whether the code first closes up under what folds away, then moves on (see [MorphToken.mid]). */
    val closesUp: Boolean get() = folds && !unfolds && comes

    fun at(progress: Float): MorphProgress {
        val millis = progress * totalMillis
        val edit = share(millis, highlightMillis.toFloat(), editMillis.toFloat())
        // What stays moves after what folds away, or before what unfolds.
        val moved = when {
            closesUp -> share(edit, 0.3f, 0.45f)
            folds && !unfolds -> share(edit, 0.3f, 0.55f)
            unfolds && !folds -> share(edit, 0f, 0.55f)
            else -> share(edit, 0f, 0.75f)
        }
        return MorphProgress(
            moved = FastOutSlowInEasing.transform(moved),
            folded = FastOutSlowInEasing.transform(share(edit, 0f, if (closesUp) 0.3f else 0.45f)),
            unfolded = FastOutSlowInEasing.transform(share(edit, 0.45f, 0.45f)),
            going = 1f - share(edit, 0f, 0.25f),
            coming = share(edit, 0.75f, 0.25f),
            highlighted = FastOutSlowInEasing.transform(share(millis, 0f, highlightMillis.toFloat())),
            dimmed = FastOutSlowInEasing.transform(share(millis, (highlightMillis + editMillis + pauseMillis).toFloat(), dimMillis.toFloat())),
        )
    }

    companion object {
        /** The timeline of [tokens] moving into code whose fold marks are the tokens [foldMarks]. */
        fun of(tokens: List<MorphToken>, foldMarks: Set<Int>, editMillis: Int): RefoldingTimeline {
            val dims = tokens.any { it.midLayout != null }
            val folds = tokens.any { it.clip?.opens == false }
            val unfolds = tokens.any { it.clip?.opens == true }
            // A fold's mark comes with the fold, and is not code that comes.
            val comes = tokens.any { it.start == null && it.clip == null && it.index !in foldMarks }
            // Only the focus changing: it fades with no time for the code to move.
            val still = tokens.all { it.start != null && it.start == it.end && it.clip == null }
            return RefoldingTimeline(
                highlightMillis = if (tokens.any { it.startLayout != null }) HIGHLIGHT_MILLIS else 0,
                // Closing up, then making room, takes longer than moving at once.
                editMillis = when {
                    still -> 0
                    folds && !unfolds && comes -> editMillis * 3 / 2
                    else -> editMillis
                },
                pauseMillis = if (dims) DIM_PAUSE_MILLIS else 0,
                dimMillis = if (dims) DIM_MILLIS else 0,
                folds = folds,
                unfolds = unfolds,
                comes = comes,
            )
        }
    }
}

/** How far [value] is through the part of a range that starts at [start] and is [length] long, from 0 to 1. */
private fun share(value: Float, start: Float, length: Float): Float =
    if (length <= 0f) (if (value >= start) 1f else 0f) else ((value - start) / length).coerceIn(0f, 1f)

private const val HIGHLIGHT_MILLIS = 400
private const val DIM_PAUSE_MILLIS = 300
private const val DIM_MILLIS = 400

/** The code an [CodeBlock] showed: whose it was, its text, how it showed, and how wide it was, unwrapped. */
private class Showing(val state: CodeBlockState, val text: String, val shown: ShownCode, val width: Float)

/**
 * How the code moves when a build changes how it shows, [showing] now: its folds, what it hides,
 * or the code itself (another state's). Typing changes the code too, and does not move it.
 */
@Composable
private fun rememberRefolding(showing: Showing, style: TextStyle, measurer: TextMeasurer): Refolding {
    val refolding = remember { Refolding() }
    // The code as shown by the composition before this one.
    val last = remember { arrayOfNulls<Showing>(1) }
    val before = last[0]
    SideEffect { last[0] = showing }
    val editMillis = LocalSlideStyle.current.code.timing.timeline().totalMillis
    // In the composition that changes it, so that its frame already draws the code where it was
    // rather than where it goes.
    val built = before != null && (before.state !== showing.state || before.text == showing.text)
    // What it shows, or only what is in focus: what comes into focus lights up, then what leaves it dims.
    if (built && (before.shown.text != showing.shown.text || before.shown.colored != showing.shown.colored)) {
        refolding.start(before.shown, before.width, showing.shown, showing.width, style, measurer, editMillis)
    }
    val progress = refolding.progress
    LaunchedEffect(progress) {
        if (progress.value >= 1f) return@LaunchedEffect
        progress.animateTo(1f, tween(refolding.totalMillis, easing = LinearEasing))
        refolding.end(progress)
    }
    return refolding
}

/**
 * Keeps [scroll] where the windows' copies share it, [lines] down ([lineHeight] pixels each), and
 * shares where it is scrolled to with [share] when its own user scrolls it.
 */
@Composable
private fun SharedScroll(scroll: ScrollState, lineHeight: Float, lines: () -> Float, share: (Float) -> Unit) {
    val following = remember { booleanArrayOf(false) }
    // Of the code shown now, which a build may change.
    val target by rememberUpdatedState(lines)
    val shareNow by rememberUpdatedState(share)
    LaunchedEffect(scroll, lineHeight) {
        // Again once the content is laid out, until which the scroll cannot go anywhere.
        snapshotFlow { target() to scroll.maxValue }.withIndex().collectLatest { (index, value) ->
            val (lines, max) = value
            val y = (lines * lineHeight).roundToInt().coerceAtMost(max)
            if (abs(scroll.value - y) > 1) {
                following[0] = true
                try {
                    if (index == 0) scroll.scrollTo(y) else scroll.animateScrollTo(y, tween(SCROLL_MILLIS))
                } finally {
                    following[0] = false
                }
            }
        }
    }
    LaunchedEffect(scroll, lineHeight) {
        snapshotFlow { scroll.value }.collect { y ->
            if (!following[0] && scroll.isScrollInProgress && lineHeight > 0f) shareNow(y / lineHeight)
        }
    }
}

private const val SCROLL_MILLIS = 400

/** Scrolls where a build asks: line [CodeView.scrollTo] to the top, or [CodeView.focusLines] into view when they are not. */
@Composable
private fun ScrollsAtBuilds(state: CodeBlockState, view: CodeView, folding: FoldedText, text: String) {
    val latest by rememberUpdatedState(folding to text)
    LaunchedEffect(state, view.scrollTo, view.focusLines, view.visibleLines, view.folded, view.hidden) {
        val lines = view.visibleLines ?: return@LaunchedEffect
        val (code, current) = latest
        val target = view.scrollTo?.let { code.rowOf(it, current) } ?: view.focusLines?.let { focus ->
            val first = code.rowOf(focus.first, current)
            val last = code.rowOf(focus.last, current)
            val top = state.scrollLines
            first.takeUnless { first >= top && last + 1 <= top + lines }
        }
        if (target != null) state.scrollLines = target.toFloat()
    }
}

/** Whether the caret, if [shown], is on now: as soon as [value] changes, then off and on every half second. */
@Composable
private fun rememberCaretBlink(value: TextFieldValue, shown: Boolean): Boolean {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(value, shown) {
        on = true
        while (shown) {
            delay(CARET_BLINK_MILLIS)
            on = !on
        }
    }
    return shown && on
}

private const val CARET_BLINK_MILLIS = 500L

/** The caret's width in slide units, and at least in pixels. */
private val CaretWidth = 3.dp
private const val MIN_CARET_PIXELS = 3f

/**
 * Draws the editor's text, under it the [selection] when it is made in another window, whose
 * editor the text field does not draw it in, and over it the caret when it is [caretOn]. [layout]
 * is of the code as shown, with some of it folded: [mapping] takes offsets in the code there.
 */
private fun ContentDrawScope.drawEditor(layout: TextLayoutResult?, selection: TextRange, mapping: OffsetMapping, typedElsewhere: Boolean, caretOn: Boolean, theme: SlideTheme) {
    val length = layout?.layoutInput?.text?.length ?: 0
    fun shown(offset: Int) = mapping.originalToTransformed(offset).coerceIn(0, length)
    if (layout != null && typedElsewhere && !selection.collapsed) {
        drawPath(layout.getPathForRange(shown(selection.min), shown(selection.max)), theme.primary.copy(alpha = SELECTION_ALPHA))
    }
    drawContent()
    if (layout != null && caretOn) {
        val rect = layout.getCursorRect(shown(selection.end))
        val width = max(CaretWidth.toPx(), MIN_CARET_PIXELS)
        drawRect(theme.code.text, topLeft = Offset(rect.left - width / 2, rect.top), size = Size(width, rect.height))
    }
}

private const val SELECTION_ALPHA = 0.3f

/** Handles the key the editor gives a meaning of its own: Tab indents. */
private fun editorKey(event: KeyEvent, state: CodeBlockState): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Tab) return false
    state.type(TAB)
    return true
}

/**
 * Whether [event] is a character typed while Ctrl is held, which the editor leaves untyped, as
 * macOS's own text fields do. An input method that switches modes with such a key, as Google 日本語入力
 * does with Ctrl+Shift+:, still has the JVM send the character (";"), which the text field typed.
 * With Option (Alt) too it types, as AltGr comes as Ctrl+Alt on Windows.
 */
private fun typesWithCtrl(event: KeyEvent): Boolean =
    event.type == KeyEventType.Unknown && event.isCtrlPressed && !event.isAltPressed

/**
 * Moves the caret a line up or down for ↑ and ↓ (with ⇧, the end of the selection) while code is
 * folded. The text field does it itself, and does it right without folds. With them, it put the
 * caret at the end of a line too short for where the caret was across, such as a blank line, by
 * where that end is in the code as shown, not in the code: the caret landed in a fold above, and
 * ↓ went back and forth between two lines.
 */
private class LineMoves {
    /** Where across the lines the caret goes, kept from line to line as the text field keeps it. */
    private var x = 0f

    /** Where in the code the caret went last: once it is elsewhere, it goes from where it is across. */
    private var movedTo = -1

    /**
     * Moves the caret of [state] for [event], in [layout] of the code as [folding] shows it, if it
     * is ↑ or ↓ and code is folded. Returns whether it did.
     */
    fun move(event: KeyEvent, state: CodeBlockState, layout: TextLayoutResult?, folding: FoldedText): Boolean {
        val lines = when (event.key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> return false
        }
        if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return false
        if (layout == null || folding.folds.isEmpty()) return false
        val value = state.text
        val end = moved(value, layout, folding.mapping, lines)
        state.text = value.copy(selection = if (event.isShiftPressed) TextRange(value.selection.start, end) else TextRange(end))
        return true
    }

    /** Where in the code the end of [value]'s selection goes [lines] lines down (up if negative) in [layout], shown with [mapping]. */
    private fun moved(value: TextFieldValue, layout: TextLayoutResult, mapping: OffsetMapping, lines: Int): Int {
        val shownEnd = mapping.originalToTransformed(value.selection.end).coerceIn(0, layout.layoutInput.text.length)
        if (movedTo != value.selection.end) x = layout.getCursorRect(shownEnd).left
        val line = layout.getLineForOffset(shownEnd) + lines
        movedTo = when {
            line < 0 -> 0
            line >= layout.lineCount -> value.text.length
            x >= layout.getLineRight(line) -> mapping.transformedToOriginal(layout.getLineEnd(line, visibleEnd = true))
            else -> mapping.transformedToOriginal(layout.getOffsetForPosition(Offset(x, layout.getLineBottom(line) - 1)))
        }
        return movedTo
    }
}

/**
 * Colors the code as [CodeBlock] does, with the lines out of the [view]'s focus dimmed, the
 * regions of [sourceLines] in [folded] folded behind [mark], and those it hides hidden.
 */
private class CodeHighlighting(
    private val sourceLines: List<String>,
    private val folded: Set<IntRange>,
    private val view: CodeView,
    private val colors: CodeColors,
    private val mark: AnnotatedString,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        foldedCode(text.text, sourceLines, folded, view, colors, mark).let { TransformedText(it.text, it.mapping) }
}
