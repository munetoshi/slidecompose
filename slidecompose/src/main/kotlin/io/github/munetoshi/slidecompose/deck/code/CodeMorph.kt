package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.util.lerp
import io.github.munetoshi.slidecompose.deck.transition.ElementTransitionMode
import io.github.munetoshi.slidecompose.deck.transition.LocalElementIdRecorder
import io.github.munetoshi.slidecompose.deck.transition.LocalElementTransition
import io.github.munetoshi.slidecompose.deck.transition.elementTransition
import io.github.munetoshi.slidecompose.deck.transition.fadeSpec
import io.github.munetoshi.slidecompose.deck.transition.moveSpec

/**
 * Code as a block shows it: its [source], each of its tokens colored as drawn, where each starts,
 * in the coordinates of the block's text, or null when it does not show, how tall the text is,
 * and the [text] it shows, in rows [rowHeight] tall. A token a folded region hides has the region
 * in [foldedIn], and the tokens of the marks of folded regions are [foldMarks]. Where the block dims
 * lines out of focus, [inFocus] has each token colored as it is in focus; [focusesParts] when it
 * dims parts of lines it was given, rather than whole lines.
 */
internal class ShownCode(
    val source: SourceCode,
    val colored: List<AnnotatedString>,
    val places: List<Offset?>,
    val height: Float,
    val text: String = source.text,
    val rowHeight: Float = 0f,
    val foldedIn: List<FoldedLines?> = emptyList(),
    val inFocus: List<AnnotatedString>? = null,
    val foldMarks: Set<Int> = emptySet(),
    val focusesParts: Boolean = false,
) {
    /** Token [i] colored as it is in focus. */
    fun focused(i: Int): AnnotatedString = inFocus?.get(i) ?: colored[i]
}

/**
 * A folded region: [lineToken], the last token of the line it shows, and how many [lines] it hides
 * under that line. A blank line has no token: then [lineToken] is the last one above it, and the
 * line is [rowsUnder] rows under that token's.
 */
internal class FoldedLines(val lineToken: Int, val lines: Int, val rowsUnder: Int = 0)

/** [text] as [layout] lays it out, colored as [colored] (the same text) draws it. */
internal fun shownCode(text: String, colored: AnnotatedString, layout: TextLayoutResult): ShownCode {
    val source = SourceCode(text)
    return ShownCode(
        source,
        source.tokens.map { colored.subSequence(it.offset, it.offset + it.text.length) },
        source.tokens.map { layout.placeOf(it.offset) },
        layout.size.height.toFloat(),
        rowHeight = layout.rowHeight(),
    )
}

/**
 * [code] with some of it folded or hidden, as [folded] shows it, laid out in [layout]. Its source
 * is all of the code, with the mark of each folded region after the line it shows: the tokens
 * folded away are there with no place. Code moving from one way of folding it to another then
 * matches what a fold hides to itself, and it comes and goes in place, rather than moving into
 * other lines that look like it. [inFocus] is the folded text colored with every line in focus,
 * where [folded] dims some. [anchors] are parts of [code] by id, by their offsets in it, which keep
 * to the parts of the same ids as the code moves (see [matchTokens]).
 */
internal fun shownCode(
    code: String,
    folded: FoldedText,
    layout: TextLayoutResult,
    inFocus: AnnotatedString? = null,
    focusesParts: Boolean = false,
    anchors: Map<Any, IntRange> = emptyMap(),
): ShownCode {
    val parts = partsOf(code, folded)
    val starts = parts.runningFold(0) { start, part -> start + part.text.length }
    val unanchored = SourceCode(parts.joinToString("") { it.text })
    val partOf = unanchored.tokens.map { token -> starts.indexOfLast { it <= token.offset } }
    val source = SourceCode(unanchored.text, anchorsOf(unanchored, parts, starts, partOf, anchors))
    // Where each token starts in the folded text, or null when it does not show.
    val shown = source.tokens.mapIndexed { i, token -> parts[partOf[i]].shownAt.takeIf { it >= 0 }?.let { it + token.offset - starts[partOf[i]] } }
    val regions = parts.indices.map { foldedLinesOf(source, parts, starts, it) }
    return ShownCode(
        source,
        source.tokens.mapIndexed { i, token -> shown[i]?.let { folded.text.subSequence(it, it + token.text.length) } ?: AnnotatedString(token.text) },
        shown.map { it?.let(layout::placeOf) },
        layout.size.height.toFloat(),
        folded.text.text,
        layout.rowHeight(),
        partOf.map(regions::get),
        inFocus?.let { colored -> source.tokens.mapIndexed { i, token -> shown[i]?.let { colored.subSequence(it, it + token.text.length) } ?: AnnotatedString(token.text) } },
        source.tokens.indices.filterTo(HashSet()) { parts[partOf[it]].mark },
        focusesParts,
    )
}

/**
 * The id of the part of [anchors] (offsets in the code) each token of [source] is in, or null: the
 * tokens of [parts] (starting at [starts] in [source], token by token in [partOf]) but the marks of
 * folded regions, which are not in the code, are where they are in the code. Empty for no anchors.
 */
private fun anchorsOf(source: SourceCode, parts: List<CodePart>, starts: List<Int>, partOf: List<Int>, anchors: Map<Any, IntRange>): List<Any?> {
    if (anchors.isEmpty()) return emptyList()
    // Where each part starts in the code: the marks take no room there.
    val codeStarts = parts.runningFold(0) { start, part -> if (part.mark) start else start + part.text.length }
    return source.tokens.mapIndexed { i, token ->
        val part = parts[partOf[i]]
        if (part.mark) return@mapIndexed null
        val offset = codeStarts[partOf[i]] + token.offset - starts[partOf[i]]
        anchors.entries.firstOrNull { offset in it.value }?.key
    }
}

/**
 * The folded region that [part] of [parts] (starting at [starts]) is the hidden lines of, or null:
 * its mark is the part before it, after the last token of the line the region shows.
 */
private fun foldedLinesOf(source: SourceCode, parts: List<CodePart>, starts: List<Int>, part: Int): FoldedLines? {
    val lines = parts[part].foldedLines
    if (lines == 0 || part == 0) return null
    val lineToken = source.tokens.indexOfLast { it.offset < starts[part - 1] }
    val mark = source.tokens.getOrNull(lineToken + 1) ?: return null
    if (lineToken < 0) return null
    return FoldedLines(lineToken, lines, rowsUnder = mark.row - source.tokens[lineToken].row)
}

/** How tall a row of text is in this layout: its style's line height, or its first line's. */
private fun TextLayoutResult.rowHeight(): Float {
    val style = layoutInput.style
    return with(layoutInput.density) {
        when {
            style.lineHeight.isSp -> style.lineHeight.toPx()
            style.lineHeight.isEm && style.fontSize.isSp -> style.lineHeight.value * style.fontSize.toPx()
            lineCount == 0 -> 0f
            else -> getLineBottom(0) - getLineTop(0)
        }
    }
}

/**
 * A part of code with folds: its [text], and where it starts in the folded text, or -1 when it does
 * not show; for the lines a folded region hides, how many they are ([foldedLines]); and whether it is
 * the [mark] of a folded region.
 */
private class CodePart(val text: String, val shownAt: Int, val foldedLines: Int = 0, val mark: Boolean = false)

/** The parts of [code] that [folded] shows, the marks of its folded regions, and the parts it folds or hides, in order. */
private fun partsOf(code: String, folded: FoldedText): List<CodePart> {
    val marks = folded.folds.filter { it.marked }.zip(folded.marks).toMap()
    val froms = sequenceOf(0) + folded.folds.asSequence().map { it.hidden.end }
    val tos = folded.folds.asSequence().map { it.hidden.start } + code.length
    val shown = froms.zip(tos) { from, to -> CodePart(code.substring(from, to), folded.mapping.originalToTransformed(from)) }
    val hidden = folded.folds.asSequence().map { it.toParts(code, folded.text.text, marks[it]) } + sequenceOf(emptySequence())
    return shown.zip(hidden) { part, folds -> sequenceOf(part) + folds }.flatten().toList()
}

/** The parts of [code] this fold makes: its [mark] in the [folded] text, when it has one, and what it hides. */
private fun Fold.toParts(code: String, folded: String, mark: FoldMark?): Sequence<CodePart> {
    val text = code.substring(hidden.start, hidden.end)
    val markPart = mark?.let { CodePart(folded.substring(it.range.start, it.range.end), it.range.start, mark = true) }
    return sequenceOf(markPart, CodePart(text, -1, if (marked) text.count { it == '\n' } else 0)).filterNotNull()
}

/** Where the text at [offset] starts, in the coordinates of the text. */
private fun TextLayoutResult.placeOf(offset: Int): Offset =
    Offset(getHorizontalPosition(offset, usePrimaryDirection = true), getLineTop(getLineForOffset(offset)))

/**
 * How a code block with an element transition id shows its code in [style]: what it shows, where
 * its text is, and the part of it in view ([visible], from the top of the text; null for all), as
 * it is laid out. A block in the next frame that continues it moves its tokens from there, and
 * meanwhile has it hide its own ([continued]).
 */
@Stable
internal class CodeLook(val style: TextStyle) {
    var shown: ShownCode? = null
    var coordinates: LayoutCoordinates? = null
    var visible: ClosedFloatingPointRange<Float>? = null
    var continued by mutableStateOf(false)
}

/**
 * A token on its way: where it starts and where it ends, each in coordinates of its own (see
 * [drawMorph]), and which token of the code it moves into it is, unless it goes.
 */
internal class MorphToken(
    val layout: TextLayoutResult,
    /** Null when the token comes in. */
    val start: Offset?,
    /** Null when the token goes. */
    val end: Offset?,
    /** How much larger it is drawn at the start than at the end. */
    val startScale: Float,
    val index: Int? = null,
    /** Where it shows on its way, when it folds away or unfolds. */
    val clip: FoldClip? = null,
    /** The token as it is colored at the start, when that differs from [midLayout]'s. */
    val startLayout: TextLayoutResult? = null,
    /** The token as it is colored while the code changes, when that differs from [layout]'s, its colors at the end. */
    val midLayout: TextLayoutResult? = null,
    /**
     * Where it is once the code folding away above it is away, in the coordinates of the start,
     * when the code first closes up under the fold and only then moves on (see [placeAt]).
     */
    val mid: Offset? = null,
) {
    /** This token closing up to [mid] first, and folding away into [end] within [clip], if it folds away. */
    fun closingUp(mid: Offset?, end: Offset? = this.end, clip: FoldClip? = this.clip): MorphToken =
        MorphToken(layout, start, end, startScale, index, clip, startLayout, midLayout, mid)

    /** The token as it is colored at the start (0), while the code changes (1), or at the end (2). */
    fun layoutAt(stage: Int): TextLayoutResult = when (stage) {
        0 -> startLayout ?: midLayout ?: layout
        1 -> midLayout ?: layout
        else -> layout
    }
}

/**
 * The rows code folding away into a region's line shows in, or code unfolding from it ([opens]):
 * between a top and a bottom at the start, in the coordinates of the start, and at the end, in
 * those of the end. The code moves with the lines under the region, so that it slides under the
 * region's line, fading out over [feather] under it.
 */
internal data class FoldClip(val startTop: Float, val startBottom: Float, val endTop: Float, val endBottom: Float, val opens: Boolean, val feather: Float)

/**
 * How far a move is: how far what moves has gone ([moved]), and what folds away ([folded]) or
 * unfolds ([unfolded]), each eased; how opaque what goes ([going]) and what comes ([coming]) are;
 * and how far tokens have changed colors, first from those at the start into those while the code
 * changes ([highlighted]), and then into those at the end ([dimmed]).
 */
internal class MorphProgress(
    val moved: Float,
    val folded: Float,
    val unfolded: Float,
    val going: Float,
    val coming: Float,
    val highlighted: Float,
    val dimmed: Float,
)

/** Code as shown in [style], with its text starting at [origin], and the part of it in [visible] in view (null for all). */
internal class PlacedCode(val shown: ShownCode, val style: TextStyle, val origin: Offset, val visible: ClosedFloatingPointRange<Float>?) {
    /** Where [token] starts, or null when it is out of view. */
    fun at(token: Int): Offset? = shown.places[token]?.takeIf { visible == null || it.y in visible }?.plus(origin)
}

/**
 * The tokens of [sources] moving into [target], matched as a [CodeBlock] matches its
 * versions: the sources one after the other, as one piece of code. A token out of view on one side
 * comes or goes, as one the other side does not have. With [recolors], the code changing shows in
 * focus, and the rest as it is at the start or at the end, whichever is in focus (see [Recoloring]).
 */
internal fun morphTokens(sources: List<PlacedCode>, target: PlacedCode, measurer: TextMeasurer, recolors: Boolean = false): List<MorphToken> {
    val old = SourceCode(sources.joinToString("\n") { it.shown.source.text }, sources.flatMap { it.shown.source.tokenAnchors() })
    val oldTokens = sources.flatMap { source -> source.shown.source.tokens.indices.map { source to it } }
    val matches = matchTokens(old, target.shown.source)
    val folds = Refolds(sources, oldTokens, target, matches)
    val coming = target.shown.source.tokens.indices.mapNotNull { i ->
        val from = matches[i].takeIf { it >= 0 }?.let(oldTokens::get)
        val start = from?.let { (source, token) -> source.at(token) }
        val end = target.at(i)
        when {
            end != null && start != null -> PlannedToken(from, i, start, end)
            // Folded where it was, it unfolds; or it is new.
            end != null -> from?.let { (source, token) -> folds.unfolding(source, token, end) }
                ?.let { (start, clip) -> PlannedToken(from, i, start, end, clip) }
                ?: PlannedToken(null, i, null, end)
            // Folded where it goes, it folds away; out of view, it goes from where it was.
            start != null -> folds.folding(i, start)?.let { (end, clip) -> PlannedToken(from, i, start, end, clip) } ?: PlannedToken(from, null, start, null)
            else -> null
        }
    }
    val matched = matches.filter { it >= 0 }.toSet()
    val gone = oldTokens.indices.filter { it !in matched }.mapNotNull { i ->
        val (source, token) = oldTokens[i]
        source.at(token)?.let { PlannedToken(source to token, null, it, null) }
    }
    val planned = coming + gone
    val recoloring = if (recolors) Recoloring(planned, target) else null
    return planned.map { it.toMorphToken(target, measurer, recoloring) }
}

/**
 * A token on its way, before it is laid out: the token of a source it comes from ([from]) and the
 * target's it goes into ([into]), where it starts and where it ends, if anywhere, and where it shows
 * on its way, if it folds away or unfolds.
 */
private class PlannedToken(val from: Pair<PlacedCode, Int>?, val into: Int?, val start: Offset?, val end: Offset?, val clip: FoldClip? = null) {
    fun toMorphToken(target: PlacedCode, measurer: TextMeasurer, recoloring: Recoloring?): MorphToken {
        val startColors = from?.let { (source, token) -> source.shown.colored[token] }
        val endColors = into?.let { target.shown.colored[it] }
        val source = from?.first
        return when {
            // What folds away keeps its colors, and what unfolds has those at the end.
            clip != null && !clip.opens -> MorphToken(layoutOf(checkNotNull(startColors), checkNotNull(source).style, measurer), start, end, 1f, clip = clip)
            clip != null -> MorphToken(layoutOf(checkNotNull(endColors), target.style, measurer), start, end, 1f, into, clip)
            // What goes shows in focus before it goes, if it is not.
            end == null -> {
                val style = checkNotNull(source).style
                val mid = recoloring?.midColors(this) ?: checkNotNull(startColors)
                MorphToken(layoutOf(mid, style, measurer), start, null, 1f, startLayout = startColors?.takeIf { it != mid }?.let { layoutOf(it, style, measurer) })
            }
            // What comes shows in focus, and dims after if it is out of focus.
            start == null -> {
                val mid = recoloring?.midColors(this) ?: checkNotNull(endColors)
                MorphToken(layoutOf(checkNotNull(endColors), target.style, measurer), null, end, 1f, into, midLayout = mid.takeIf { it != endColors }?.let { layoutOf(it, target.style, measurer) })
            }
            else -> {
                val mid = recoloring?.midColors(this) ?: checkNotNull(endColors)
                MorphToken(
                    layoutOf(checkNotNull(endColors), target.style, measurer),
                    start,
                    end,
                    sizeRatio(checkNotNull(source).style, target.style),
                    into,
                    startLayout = startColors?.takeIf { it != mid }?.let { layoutOf(it, source.style, measurer) },
                    midLayout = mid.takeIf { it != endColors }?.let { layoutOf(it, target.style, measurer) },
                )
            }
        }
    }
}

/** [colored] laid out alone in [style], as a token is drawn on its way. */
private fun layoutOf(colored: AnnotatedString, style: TextStyle, measurer: TextMeasurer): TextLayoutResult =
    measurer.measure(colored, style, softWrap = false, maxLines = 1)

/**
 * The colors tokens show while the code changes: the code that changes, the tokens that go or come
 * and those on their lines, in focus, so that it shows in focus before it changes; the rest as at
 * the start or at the end, whichever is in focus, so that what leaves the focus dims only after.
 * Into code that [ShownCode.focusesParts], what goes and the rest of those lines stay as they are:
 * the parts in focus are all there is to see. The mark of a region folding or unfolding changes no code.
 */
private class Recoloring(planned: List<PlannedToken>, private val target: PlacedCode) {
    /** The rows of the sources with tokens that go, and of the target with tokens that come. */
    private val changedFrom = planned.filter { it.end == null && it.clip == null }
        .mapNotNullTo(HashSet()) { token -> token.from?.takeIf { (source, i) -> i !in source.shown.foldMarks }?.let { (source, i) -> source to source.shown.source.tokens[i].row } }
    private val changedInto = planned.filter { it.start == null && it.clip == null }
        .mapNotNullTo(HashSet()) { token -> token.into?.takeIf { it !in target.shown.foldMarks }?.let { target.shown.source.tokens[it].row } }

    fun midColors(token: PlannedToken): AnnotatedString? {
        val from = token.from
        val into = token.into
        return when {
            token.clip != null -> null
            // Into code focused by parts, what goes fades out as it is, out of focus or not.
            into == null -> from?.let { (source, i) -> if (target.shown.focusesParts) source.shown.colored[i] else source.shown.focused(i) }
            from == null -> target.shown.focused(into)
            else -> {
                val (source, i) = from
                val changed = !target.shown.focusesParts &&
                    ((source to source.shown.source.tokens[i].row) in changedFrom || target.shown.source.tokens[into].row in changedInto)
                val inFocus = changed || source.shown.colored[i] == source.shown.focused(i) || target.shown.colored[into] == target.shown.focused(into)
                if (inFocus) target.shown.focused(into) else target.shown.colored[into]
            }
        }
    }
}

/**
 * Where code folds away into a folded region of [target], or unfolds from one of [sources], whose
 * tokens [oldTokens] are, one after the other, with [matches] from them for the target's tokens.
 */
private class Refolds(sources: List<PlacedCode>, private val oldTokens: List<Pair<PlacedCode, Int>>, private val target: PlacedCode, private val matches: IntArray) {
    /** Where each source's tokens start among [oldTokens]. */
    private val firsts = sources.zip(sources.runningFold(0) { first, source -> first + source.shown.source.tokens.size }).toMap()

    /** The target's token that each of [oldTokens] goes on as, or -1. */
    private val onward = IntArray(oldTokens.size) { -1 }.also { onward -> matches.forEachIndexed { i, old -> if (old >= 0) onward[old] = i } }

    /** Where token [i] of the target, at [start] in a source, ends folded away, and where it shows on the way; null unless a folded region hides it. */
    fun folding(i: Int, start: Offset): Pair<Offset, FoldClip>? {
        val region = target.shown.foldedIn.getOrNull(i) ?: return null
        val lineEnd = target.at(region.lineToken) ?: return null
        val (source, lineToken) = matches[region.lineToken].takeIf { it >= 0 }?.let(oldTokens::get) ?: return null
        val lineStart = source.at(lineToken) ?: return null
        val top = lineStart.y + (region.rowsUnder + 1) * source.shown.rowHeight
        val bottom = top + region.lines * source.shown.rowHeight
        val endTop = lineEnd.y + (region.rowsUnder + 1) * target.shown.rowHeight
        return Offset(lineEnd.x + start.x - lineStart.x, endTop - (bottom - start.y)) to FoldClip(top, bottom, endTop, endTop, opens = false, feather = source.shown.rowHeight)
    }

    /** Where token [token] of [source], which ends at [end] in the target, starts as it unfolds, and where it shows on the way; null unless a folded region hides it. */
    fun unfolding(source: PlacedCode, token: Int, end: Offset): Pair<Offset, FoldClip>? {
        val region = source.shown.foldedIn.getOrNull(token) ?: return null
        val lineStart = source.at(region.lineToken) ?: return null
        val lineEnd = onward[checkNotNull(firsts[source]) + region.lineToken].takeIf { it >= 0 }?.let(target::at) ?: return null
        val top = lineStart.y + (region.rowsUnder + 1) * source.shown.rowHeight
        val endTop = lineEnd.y + (region.rowsUnder + 1) * target.shown.rowHeight
        val endBottom = endTop + region.lines * target.shown.rowHeight
        return Offset(lineStart.x + end.x - lineEnd.x, top - (endBottom - end.y)) to FoldClip(top, top, endTop, endBottom, opens = true, feather = target.shown.rowHeight)
    }
}

/**
 * Draws [tokens] on their way, their starts moved by [startShift] and their ends by [endShift], as
 * far as [progress] has them: what moves, what folds away into a region's line or unfolds from it,
 * fading out under it, what goes and what comes, each in the colors it has got to.
 */
internal fun DrawScope.drawMorph(tokens: List<MorphToken>, startShift: Offset, endShift: Offset, progress: MorphProgress) {
    val shifts = Shifts(startShift, endShift)
    drawRecolored(tokens.filter { it.start != null && it.end != null && it.clip == null }, shifts, progress)
    for ((clip, group) in tokens.filter { it.clip != null }.groupBy { checkNotNull(it.clip) }) drawRefolding(clip, group, shifts, if (clip.opens) progress.unfolded else progress.folded)
    withAlpha(progress.going) { drawRecolored(tokens.filter { it.end == null }, shifts, progress) }
    withAlpha(progress.coming) { drawRecolored(tokens.filter { it.start == null }, shifts, progress) }
}

/** How far the starts of tokens are moved ([start]) and their ends ([end]), as [drawMorph] draws them. */
internal class Shifts(val start: Offset, val end: Offset)

/**
 * The shifts for tokens on their way from places on the screen into the text at [text] on the
 * screen, drawn in a box that moves with them, now at [box] and at [boxAtStart] as they started:
 * their starts as the box had them then, so that they keep up with the box, rather than lag behind
 * it while it moves, as starts kept still on the screen would.
 */
internal fun morphShifts(boxAtStart: Offset, box: Offset, text: Offset): Shifts = Shifts(-boxAtStart, text - box)

/**
 * Draws [tokens], each where it is: moving, at its start if it goes, or at its end if it comes. While
 * their colors change, the old colors fade out as the new ones fade in.
 */
private fun DrawScope.drawRecolored(tokens: List<MorphToken>, shifts: Shifts, progress: MorphProgress) {
    if (tokens.isEmpty()) return
    // First from the colors at the start into those while the code changes, then into those at the end.
    val dimming = progress.dimmed > 0f
    val from = if (dimming) 1 else 0
    val to = from + 1
    val share = if (dimming) progress.dimmed else progress.highlighted
    for (token in tokens) if (token.layoutAt(from) === token.layoutAt(to)) drawPlaced(token, token.layoutAt(to), shifts, progress)
    withAlpha(1f - share) {
        for (token in tokens) if (token.layoutAt(from) !== token.layoutAt(to)) drawPlaced(token, token.layoutAt(from), shifts, progress)
    }
    withAlpha(share) {
        for (token in tokens) if (token.layoutAt(from) !== token.layoutAt(to)) drawPlaced(token, token.layoutAt(to), shifts, progress)
    }
}

/**
 * Where [this] token is on its way, its start moved by [startShift] and its end by [endShift], as
 * far as [progress] has it: [MorphProgress.moved] of the way; or, if it closes up first, as far as
 * the fold above it has got ([MorphProgress.folded]) towards [MorphToken.mid], and then on. What
 * comes is where it ends, and what goes where it starts, or where it closes up to.
 */
internal fun MorphToken.placeAt(startShift: Offset, endShift: Offset, progress: MorphProgress): Offset {
    val end = end?.plus(endShift)
    val start = start?.plus(startShift) ?: return checkNotNull(end)
    val mid = mid?.plus(startShift)
    return when {
        end == null -> if (mid == null) start else lerp(start, mid, progress.folded)
        mid == null -> lerp(start, end, progress.moved)
        progress.moved > 0f -> lerp(mid, end, progress.moved)
        else -> lerp(start, mid, progress.folded)
    }
}

/** Draws [token] as [layout] lays it out, where it is on its way as far as [progress] has it (see [placeAt]). */
private fun DrawScope.drawPlaced(token: MorphToken, layout: TextLayoutResult, shifts: Shifts, progress: MorphProgress) {
    val place = token.placeAt(shifts.start, shifts.end, progress)
    when {
        token.start == null || token.end == null -> drawToken(layout, place, scale = 1f)
        // Laid out at the start's size, it is drawn that much smaller at the end.
        layout === token.startLayout -> drawToken(layout, place, lerp(1f, 1f / token.startScale, progress.moved))
        else -> drawToken(layout, place, lerp(token.startScale, 1f, progress.moved))
    }
}

/**
 * Draws the tokens of a region folding away ([folded] of the way) or unfolding, in the part of the
 * code [clip] has them show in, fading out towards its top, the region's line. The fade grows as
 * the code leaves its place, and shrinks as it reaches it, so that it does not show at rest.
 */
private fun DrawScope.drawRefolding(clip: FoldClip, tokens: List<MorphToken>, shifts: Shifts, folded: Float) {
    val top = lerp(clip.startTop + shifts.start.y, clip.endTop + shifts.end.y, folded)
    val bottom = lerp(clip.startBottom + shifts.start.y, clip.endBottom + shifts.end.y, folded)
    if (bottom <= top) return
    val area = Rect(-size.width * LAYER_REACH, top, size.width * (LAYER_REACH + 1), bottom)
    drawContext.canvas.saveLayer(area, Paint())
    for (token in tokens) {
        drawToken(token.layout, lerp(checkNotNull(token.start) + shifts.start, checkNotNull(token.end) + shifts.end, folded), lerp(token.startScale, 1f, folded))
    }
    val feather = clip.feather * (if (clip.opens) 1f - folded else folded)
    val fade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = top, endY = top + feather.coerceAtLeast(1f))
    drawRect(fade, area.topLeft, area.size, blendMode = BlendMode.DstIn)
    drawContext.canvas.restore()
}

/** How many times its width a layer reaches out left and right of what draws it: tokens on their way may be drawn outside it. */
private const val LAYER_REACH = 4f

/**
 * A code block coming in with an element transition, continuing the code blocks of [sources] in
 * the frame before: the tokens of theirs that it shows too move from where they were to where they
 * are in it, changing size on the way, as a [CodeBlock]'s do between versions (see
 * [drawMorph]). [fraction] goes from 0 to 1 as the elements move, eased; [fade] goes from 0 to 1
 * evenly over the same time.
 */
internal class CodeMorph(
    private val sources: List<CodeLook>,
    private val target: CodeLook,
    private val fraction: () -> Float,
    private val fade: () -> Float,
) {
    private var tokens: List<MorphToken>? = null

    /** Where the box was on the screen as the transition started. */
    private var boxAtStart: Offset? = null

    /** Draws the tokens where they are now, in the box laid out at [box], which they are on their way into. */
    fun DrawScope.draw(box: LayoutCoordinates, measurer: TextMeasurer) {
        val text = target.coordinates?.takeIf { it.isAttached } ?: return
        // Where the sources are is read once, as the transition starts: their frame stays still.
        val tokens = tokens ?: (morphTokens(measurer) ?: return).also { tokens = it }
        val boxOnScreen = box.positionInRoot()
        val shifts = morphShifts(boxAtStart ?: boxOnScreen.also { boxAtStart = it }, boxOnScreen, text.positionInRoot())
        drawMorph(tokens, shifts.start, shifts.end, crossFading(fraction(), fade()))
    }

    /** The tokens of the sources, on the screen, and the target's, in its text; null until the target is laid out. */
    private fun morphTokens(measurer: TextMeasurer): List<MorphToken>? {
        val shown = target.shown ?: return null
        val placed = sources.mapNotNull { source ->
            val code = source.shown ?: return@mapNotNull null
            val coordinates = source.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            PlacedCode(code, source.style, coordinates.positionInRoot(), source.visible)
        }
        return morphTokens(placed, PlacedCode(shown, target.style, Offset.Zero, target.visible), measurer)
    }
}

/**
 * How far a move is as an element transition has it: what moves, folds or unfolds at [fraction] of
 * the way, what goes fading out over the first half of [fade], and what comes, and new colors,
 * fading in over its second half, so that halfway only what moves is there.
 */
private fun crossFading(fraction: Float, fade: Float): MorphProgress {
    val coming = (2f * fade - 1f).coerceIn(0f, 1f)
    return MorphProgress(fraction, fraction, fraction, (1f - 2f * fade).coerceIn(0f, 1f), coming, highlighted = coming, dimmed = 0f)
}

/** How much larger code in [from] is than in [to]. */
private fun sizeRatio(from: TextStyle, to: TextStyle): Float =
    if (from.fontSize.isSp && to.fontSize.isSp && to.fontSize.value > 0f) from.fontSize.value / to.fontSize.value else 1f

/** Draws a token laid out alone with its top left at [topLeft], [scale] times its size. */
private fun DrawScope.drawToken(layout: TextLayoutResult, topLeft: Offset, scale: Float) {
    translate(topLeft.x, topLeft.y) {
        scale(scale, pivot = Offset.Zero) { drawText(layout) }
    }
}

/**
 * Draws [content] [alpha] opaque, as one layer: drawing text with an alpha fades only the parts in
 * the style's color, not the parts that syntax highlighting colors.
 */
private fun DrawScope.withAlpha(alpha: Float, content: DrawScope.() -> Unit) {
    when {
        alpha <= 0f -> Unit
        alpha >= 1f -> content()
        else -> {
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size).inflate(maxOf(size.width, size.height) * 4), Paint().apply { this.alpha = alpha })
            content()
            drawContext.canvas.restore()
        }
    }
}

/**
 * How a code block takes part in element transitions with its [id]: its box moves and resizes to
 * the block's, and its code [look] continues that of the block with the same id in the frame
 * before, and of the blocks with the [from] ids, whose tokens move into it (see [CodeMorph]).
 * Without an id, it takes no part.
 */
@Composable
internal fun rememberCodeTransition(id: Any?, from: List<Any>, style: TextStyle): CodeTransition {
    val look = remember(style) { CodeLook(style) }
    if (id == null) return remember(look) { CodeTransition(null, emptyList(), look, null, null, null) }
    LocalElementIdRecorder.current?.ids?.addAll(from)
    val context = LocalElementTransition.current
    val visibility = context?.visibilityScope?.transition
    val morph = codeMorph(listOf(id) + from, look)
    val measurer = rememberTextMeasurer()
    return remember(id, from, look, morph, measurer, visibility) { CodeTransition(id, from, look, morph, measurer, visibility) }
}

/**
 * While this frame comes in with an element transition from the blocks with [ids] in the frame
 * before, how [look]'s code moves in from theirs; otherwise null.
 */
@Composable
private fun codeMorph(ids: List<Any>, look: CodeLook): CodeMorph? {
    val context = LocalElementTransition.current ?: return null
    // What the blocks looked like in the frame this one comes from, when it came in.
    val sources = remember(context, ids) { ids.mapNotNull { context.looks.elsewhere(it, context.visibilityScope)?.code } }
    val move = context.elementTransition
    val visibility = context.visibilityScope.transition
    val fraction = visibility.animateFloat(
        transitionSpec = { move?.moveSpec() ?: tween(0) },
        label = "code morph",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val fade = visibility.animateFloat(
        transitionSpec = { move?.fadeSpec() ?: tween(0) },
        label = "code fade",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val entering = visibility.targetState == EnterExitState.Visible && visibility.currentState != EnterExitState.Visible
    if (sources.isEmpty() || move == null || !entering) return null
    // The sources hide their code while it moves in here.
    DisposableEffect(sources) {
        sources.forEach { it.continued = true }
        onDispose { sources.forEach { it.continued = false } }
    }
    return remember(sources, look, fraction, fade) { CodeMorph(sources, look, { fraction.value }, { fade.value }) }
}

/** The modifiers a code block takes part in element transitions with: see [rememberCodeTransition]. */
@Stable
internal class CodeTransition(
    private val id: Any?,
    private val from: List<Any>,
    val look: CodeLook,
    private val morph: CodeMorph?,
    private val measurer: TextMeasurer?,
    private val visibility: Transition<EnterExitState>?,
) {
    private var box: LayoutCoordinates? = null

    /**
     * For the block's box, before its look: the tokens on their way into it are drawn over it, and
     * reach out of it.
     */
    val boxModifier: Modifier = if (id == null) {
        Modifier
    } else {
        Modifier
            // Only the new block is drawn while they move: its code takes over the old one's. A
            // block the frame before has only under one of [from] moves from where that one is.
            .elementTransition(id, ElementTransitionMode.Resize, crossfade = false, zIndex = 0f, text = null, code = look, continues = from)
            .onPlaced { box = it }
            .drawWithContent {
                drawContent()
                val placed = box
                if (morph != null && measurer != null && placed != null) with(morph) { draw(placed, measurer) }
            }
    }

    /**
     * For what draws the code, where the tokens' places start: it tells where the code is, and
     * hides it while its tokens are drawn on their way into this block or into one that continues it.
     */
    val textModifier: Modifier = if (id == null) {
        Modifier
    } else {
        Modifier
            .onPlaced { look.coordinates = it }
            .drawWithContent {
                val leaving = visibility != null && visibility.targetState != EnterExitState.Visible
                if (morph == null && !(look.continued && leaving)) drawContent()
            }
    }
}
