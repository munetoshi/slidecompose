package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import kotlin.math.abs

/**
 * A region of code folded, or hidden: [region] is its lines (from 1) in the code the block was
 * given, and [hidden] the part of the text as it is now that it hides. Folded, a region hides its
 * lines after the first, from the end of its first line to the end of its last, and a mark shows
 * in their place ([marked]); hidden, it hides all of them, and nothing shows in their place.
 */
internal class Fold(val region: IntRange, val hidden: TextRange, val marked: Boolean = true) {
    /** Whether it hides the line that starts at [lineStart] in the text. */
    fun hides(lineStart: Int): Boolean =
        // Hidden from the first line, it hides up to the start of the line after it; otherwise,
        // from the end of the line before (or its own first line) to the end of its last.
        if (hidden.start == 0 && !marked) lineStart < hidden.end else lineStart > hidden.start && lineStart <= hidden.end
}

/**
 * Where the [regions] of [original] (lines from 1) are in [text], which may have been edited: each
 * region follows its lines when lines are added or removed around it, and folds no more once its
 * own lines change. A region of a single line hides nothing and does not fold; of regions that
 * overlap, the first folds.
 */
internal fun foldsIn(text: String, original: List<String>, regions: Collection<IntRange>): List<Fold> =
    regionsIn(text, original, regions.filter { it.last > it.first }) { lines, starts, first, last ->
        TextRange(starts[first] + lines[first].length, starts[last] + lines[last].length)
    }.withoutOverlaps()

/**
 * Where the [regions] of [original] (lines from 1) are in [text] to be hidden, which follow their
 * lines as [foldsIn]'s do: each hides its lines and one line break, the one before it, or after it
 * at the start of the text.
 */
internal fun hiddenIn(text: String, original: List<String>, regions: Collection<IntRange>): List<Fold> =
    regionsIn(text, original, regions) { lines, starts, first, last ->
        if (first == 0) TextRange(0, if (last + 1 < lines.size) starts[last + 1] else text.length) else TextRange(starts[first] - 1, starts[last] + lines[last].length)
    }.map { Fold(it.region, it.hidden, marked = false) }

/** Folds and hidden regions in the order of the text, without those that overlap one before them. */
internal fun List<Fold>.withoutOverlaps(): List<Fold> =
    sortedBy { it.hidden.start }.fold(emptyList()) { kept, fold -> if (kept.isEmpty() || fold.hidden.start >= kept.last().hidden.end) kept + fold else kept }

/**
 * The [regions] of [original] found in [text], each where its lines are now nearest where they
 * were, with the part of the text that [hidden] gives for its rows, from [first] to [last].
 */
private fun regionsIn(
    text: String,
    original: List<String>,
    regions: Collection<IntRange>,
    hidden: (lines: List<String>, starts: List<Int>, first: Int, last: Int) -> TextRange,
): List<Fold> {
    val lines = text.split('\n')
    val starts = lines.runningFold(0) { start, line -> start + line.length + 1 }
    return regions.asSequence()
        .filter { it.first >= 1 && it.last <= original.size && it.last >= it.first }
        .mapNotNull { region ->
            val block = original.subList(region.first - 1, region.last)
            val at = (0..lines.size - block.size).asSequence()
                .filter { row -> block.indices.all { lines[row + it] == block[it] } }
                .minByOrNull { abs(it - (region.first - 1)) }
                ?: return@mapNotNull null
            Fold(region, hidden(lines, starts, at, at + block.size - 1))
        }
        .toList()
}

/** Code with [folds], as shown: its [text], where its marks are, and how offsets map between it and the code. */
internal class FoldedText(val text: AnnotatedString, val folds: List<Fold>, val marks: List<FoldMark>, val mapping: OffsetMapping)

/** The mark of a folded [region], at [range] of the folded text. */
internal class FoldMark(val region: IntRange, val range: TextRange)

/** [code] (colored as it is drawn) with each of [folds] hidden behind [mark]. */
internal fun fold(code: AnnotatedString, folds: List<Fold>, mark: AnnotatedString): FoldedText {
    if (folds.isEmpty()) return FoldedText(code, emptyList(), emptyList(), OffsetMapping.Identity)
    val pieces = shownPieces(code, folds, mark).toList()
    val starts = pieces.runningFold(0) { at, piece -> at + piece.text.length }
    val marks = pieces.asSequence().zip(starts.asSequence()).mapNotNull { (piece, start) -> piece.toMark(start) }.toList()
    return FoldedText(pieces.toAnnotatedString(), folds, marks, FoldMapping(folds, mark.length))
}

/** A piece of folded code as shown: code, or the [mark] of the folded region [region]. */
private class ShownPiece(val text: AnnotatedString, val region: IntRange? = null) {
    /** The mark this piece is, starting at [start] of the folded text, or `null` when it is code. */
    fun toMark(start: Int): FoldMark? = region?.let { FoldMark(it, TextRange(start, start + text.length)) }
}

/** What [folds] leave shown of [code], in order: the code before, between and after them, and the [mark] of each marked one. */
private fun shownPieces(code: AnnotatedString, folds: List<Fold>, mark: AnnotatedString): Sequence<ShownPiece> {
    val froms = sequenceOf(0) + folds.asSequence().map { it.hidden.end }
    val tos = folds.asSequence().map { it.hidden.start } + code.length
    val gaps = froms.zip(tos) { from, to -> ShownPiece(code.subSequence(from, to)) }
    val marks = folds.asSequence().map { it.toMarkPiece(mark) } + null
    return gaps.zip(marks) { gap, marked -> sequenceOf(gap, marked) }.flatten().filterNotNull()
}

/** The [mark] shown for this fold, or `null` when it shows none. */
private fun Fold.toMarkPiece(mark: AnnotatedString): ShownPiece? = if (marked) ShownPiece(mark, region) else null

private fun List<ShownPiece>.toAnnotatedString(): AnnotatedString =
    AnnotatedString.Builder().apply { forEach { append(it.text) } }.toAnnotatedString()

/** Offsets in code with [folds], each hidden behind a mark [markLength] long, or nothing, and in the code as shown. */
private class FoldMapping(folds: List<Fold>, private val markLength: Int) : OffsetMapping {
    private fun Fold.markLength(): Int = if (marked) markLength else 0

    /** Each fold, with how far the folds before it move what follows them in the code as shown. */
    private val shifted: List<Pair<Fold, Int>> =
        folds.zip(folds.runningFold(0) { shift, fold -> shift + fold.markLength() - fold.hidden.length })

    override fun originalToTransformed(offset: Int): Int {
        val (fold, shift) = shifted.lastOrNull { (fold, _) -> fold.hidden.start < offset } ?: return offset
        // In what is hidden: after the mark.
        return if (offset < fold.hidden.end) {
            fold.hidden.start + shift + fold.markLength()
        } else {
            offset + shift + fold.markLength() - fold.hidden.length
        }
    }

    override fun transformedToOriginal(offset: Int): Int {
        val (fold, shift) = shifted.lastOrNull { (fold, shift) -> fold.hidden.start + shift < offset } ?: return offset
        // On the mark: after what it hides.
        return if (offset <= fold.hidden.start + shift + fold.markLength()) {
            fold.hidden.end
        } else {
            offset - shift - fold.markLength() + fold.hidden.length
        }
    }
}
