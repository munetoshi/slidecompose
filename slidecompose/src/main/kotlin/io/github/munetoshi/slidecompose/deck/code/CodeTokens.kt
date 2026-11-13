package io.github.munetoshi.slidecompose.deck.code

internal class SourceToken(val row: Int, val column: Int, val offset: Int, val text: String)

/**
 * Code split into lines and tokens. Whitespace separates tokens and is not one. [anchors] has the
 * id of the anchored part of the code each token is in, or null, token by token; it is empty when
 * the code has none (see [matchTokens]).
 */
internal class SourceCode(val text: String, val anchors: List<Any?> = emptyList()) {
    val lines: List<String> = text.split('\n')
    val tokens: List<SourceToken> = tokensOf(lines)

    /** The id of the anchored part token [i] is in, or null. */
    fun anchorOf(i: Int): Any? = anchors.getOrNull(i)

    /** The id of the anchored part each token is in, or null, for all the tokens. */
    fun tokenAnchors(): List<Any?> = tokens.indices.map(::anchorOf)

    /** This code with the tokens in the parts of [anchors] (offsets in [text], by id) anchored to their ids. */
    fun anchoredBy(anchors: Map<Any, IntRange>): SourceCode =
        if (anchors.isEmpty()) this else SourceCode(text, tokens.map { token -> anchors.entries.firstOrNull { token.offset in it.value }?.key })

    /** Tokens of row `r` are `tokens[rowStarts[r] until rowStarts[r + 1]]`. */
    private val rowStarts: IntArray = (0..lines.size).map { firstTokenAt(tokens, it) }.toIntArray()

    fun tokensIn(rows: IntRange): IntRange = rowStarts[rows.first] until rowStarts[rows.last + 1]
}

/** The tokens of all [lines], in order. */
private fun tokensOf(lines: List<String>): List<SourceToken> {
    val offsets = lines.runningFold(0) { offset, line -> offset + line.length + 1 }
    return lines.indices.asSequence().flatMap { tokensOfLine(lines[it], it, offsets[it]) }.toList()
}

/** The tokens of [line], row [row] of the code, which starts at [offset] in it. */
private fun tokensOfLine(line: String, row: Int, offset: Int): Sequence<SourceToken> =
    tokenPattern.findAll(line).map { SourceToken(row, it.range.first, offset + it.range.first, it.value) }

/** The index of the first of [tokens] in row [row] or after it. */
private fun firstTokenAt(tokens: List<SourceToken>, row: Int): Int =
    tokens.indexOfFirst { it.row >= row }.takeIf { it >= 0 } ?: tokens.size

private val tokenPattern = Regex(
    listOf(
        """//.*""",
        """/\*.*?\*/""",
        """"(?:\\.|[^"\\])*"?""",
        """'(?:\\.|[^'\\])*'?""",
        """[\p{L}_@][\p{L}\p{N}_]*""",
        """\p{N}[\p{L}\p{N}_.]*""",
        """\S""",
    ).joinToString("|"),
)

/**
 * For each token of [new], the index of the token of [old] it continues, or -1 if it is new.
 * Lines that are the same apart from indentation are matched first; a line with no word in it,
 * such as a blank line or a closing brace, only next to lines that match. Tokens are then matched
 * within each run of changed lines between them, among lines alike (see [runMatches]), so tokens
 * do not jump between unrelated lines. Last, a word left over on both sides moves, if it is the
 * only one of its text left on each: see [movedWords]. Above all, the tokens of a part anchored to
 * an id on both sides continue one another, whatever else is like them: see [anchorMatches].
 */
internal fun matchTokens(old: SourceCode, new: SourceCode): IntArray {
    val lineMatches = longestCommonSubsequence(old.lines, new.lines, needsNeighbors = String::hasNoWord) { a, b ->
        when {
            a.trim() != b.trim() -> 0
            a.hasNoWord() -> 1
            a == b -> 4
            else -> 3 // the same code, re-indented
        }
    }
    val matchedLines = matchedRows(lineMatches)
    // Lines that match have the same tokens.
    val inMatchedLines = matchedLines.flatMap { (oldRow, newRow) -> old.tokensIn(oldRow..oldRow).asSequence().zip(new.tokensIn(newRow..newRow).asSequence()) }
    val inChangedLines = changedRuns(matchedLines, old.lines.size, new.lines.size).flatMap { (oldRows, newRows) -> runMatches(old, new, oldRows, newRows) }
    val result = IntArray(new.tokens.size) { -1 }
    (inMatchedLines + inChangedLines).forEach { (oldToken, newToken) -> result[newToken] = oldToken }
    movedWords(old, new, result).forEach { (oldToken, newToken) -> result[newToken] = oldToken }
    return anchorMatches(old, new, result)
}

/**
 * [matches] with the tokens of each part anchored to the same id in [old] and [new] continuing one
 * another, token by token as lines alike do, and none of them continuing a token outside it: a part
 * keeps to its counterpart, even with a line more alike elsewhere. An id on one side only changes
 * nothing.
 */
private fun anchorMatches(old: SourceCode, new: SourceCode, matches: IntArray): IntArray {
    val oldParts = old.tokens.indices.groupBy { old.anchorOf(it) }
    val newParts = new.tokens.indices.groupBy { new.anchorOf(it) }
    val shared = (oldParts.keys intersect newParts.keys) - setOf(null)
    if (shared.isEmpty()) return matches
    val inParts = shared.asSequence()
        .flatMap { partMatches(old, new, oldParts.getValue(it), newParts.getValue(it)) }
        .associate { (oldToken, newToken) -> newToken to oldToken }
    return IntArray(matches.size) {
        when {
            new.anchorOf(it) in shared -> inParts[it] ?: -1
            // Out of a part, it continues nothing in one.
            matches[it] >= 0 && old.anchorOf(matches[it]) in shared -> -1
            else -> matches[it]
        }
    }
}

/** The tokens of an anchored part, [oldTokens] in old code and [newTokens] in new, that continue one another, as (old token, new token). */
private fun partMatches(old: SourceCode, new: SourceCode, oldTokens: List<Int>, newTokens: List<Int>): Sequence<Pair<Int, Int>> {
    val pairs = longestCommonSubsequence(oldTokens.map { old.tokens[it].text }, newTokens.map { new.tokens[it].text }) { a, b -> if (a != b) 0 else tokenWeight(a) }
    return pairs.asSequence().withIndex().filter { it.value >= 0 }.map { (j, i) -> oldTokens[i] to newTokens[j] }
}

/**
 * The words that [matches] leaves out on both sides and that are the only ones of their text left
 * out on each, as (old token, new token): a word moved to where the order of the tokens cannot
 * keep it, such as an argument moved before the call into a block around it.
 */
private fun movedWords(old: SourceCode, new: SourceCode, matches: IntArray): Sequence<Pair<Int, Int>> {
    val matched = matches.filter { it >= 0 }.toSet()
    val oldLeft = old.tokens.indices.filter { it !in matched && old.tokens[it].text.isWord() }.groupBy { old.tokens[it].text }
    val newLeft = new.tokens.indices.filter { matches[it] < 0 && new.tokens[it].text.isWord() }.groupBy { new.tokens[it].text }
    return newLeft.asSequence().mapNotNull { (text, newTokens) ->
        val oldToken = oldLeft[text]?.singleOrNull() ?: return@mapNotNull null
        newTokens.singleOrNull()?.let { oldToken to it }
    }
}

/** Whether this token is a word, such as a name or a keyword, rather than a symbol, a string or a comment. */
private fun String.isWord(): Boolean = first().isLetter() || first() == '_' || first() == '@'

/** Whether this line has no word or number in it, as a blank line or a closing bracket. */
private fun String.hasNoWord(): Boolean = none { it.isLetterOrDigit() }

/** The rows that [lineMatches] pairs, as (old row, new row). */
private fun matchedRows(lineMatches: IntArray): Sequence<Pair<Int, Int>> =
    lineMatches.asSequence().withIndex().filter { it.value >= 0 }.map { (newRow, oldRow) -> oldRow to newRow }

/** The runs of rows between the [matched] ones, as (old rows, new rows), up to the ends of the texts. */
private fun changedRuns(matched: Sequence<Pair<Int, Int>>, oldRows: Int, newRows: Int): Sequence<Pair<IntRange, IntRange>> =
    (sequenceOf(-1 to -1) + matched + sequenceOf(oldRows to newRows))
        .zipWithNext { (oldBefore, newBefore), (oldAfter, newAfter) -> (oldBefore + 1 until oldAfter) to (newBefore + 1 until newAfter) }

/**
 * Rows of old code and of new code whose tokens are matched with one another; whether rows joined
 * it on the old side ([grownOld]), or on the new side ([grownNew]): rows join one side only.
 */
private class LineGroup(val oldRows: IntRange, val newRows: IntRange, val grownOld: Boolean = false, val grownNew: Boolean = false)

/**
 * The tokens of [oldRows] and [newRows], a run of changed lines, that continue one another, as
 * (old token, new token). Lines with words in them are paired with lines alike, sharing at least
 * half the tokens of the shorter (by [tokenWeight]). A line paired with none joins the pair of
 * lines next to it when the tokens it shares with it are more than those it shares already, on one
 * side only: lines merged into one, or one split into lines, such as a body folded into `{ … }`,
 * whose closing brace goes with it. Lines with no words in them left over pair with the same ones.
 * Tokens are then matched within each group of lines.
 */
private fun runMatches(old: SourceCode, new: SourceCode, oldRows: IntRange, newRows: IntRange): Sequence<Pair<Int, Int>> {
    if (oldRows.isEmpty() || newRows.isEmpty()) return emptySequence()
    val paired = longestCommonSubsequence(oldRows.toList(), newRows.toList()) { a, b -> likeness(old, new, a, b) }
    val groups = paired.withIndex().filter { it.value >= 0 }.map { (j, i) -> LineGroup(oldRows.first + i..oldRows.first + i, newRows.first + j..newRows.first + j) }
    val grown = grownGroups(old, new, groups, oldRows, newRows)
    return (grown + leftOverLines(old, new, grown, oldRows, newRows)).sortedBy { it.newRows.first }.asSequence()
        .flatMap { tokenMatches(old, new, it.oldRows, it.newRows) }
}

/** What old row [a] and new row [b] share, weighed by [tokenWeight], or 0 when they are not alike. */
private fun likeness(old: SourceCode, new: SourceCode, a: Int, b: Int): Int {
    if (old.lines[a].hasNoWord() || new.lines[b].hasNoWord()) return 0
    val shared = sharedWeight(old, new, a..a, b..b)
    val shorter = minOf(weightOf(old, a..a), weightOf(new, b..b))
    return if (shared > 0 && 2 * shared >= shorter) shared else 0
}

/** The total weight of the tokens of [rows]. */
private fun weightOf(code: SourceCode, rows: IntRange): Int = code.tokensIn(rows).sumOf { tokenWeight(code.tokens[it].text) }

/** The weight of the tokens [oldRows] and [newRows] share, as [tokenMatches] matches them. */
private fun sharedWeight(old: SourceCode, new: SourceCode, oldRows: IntRange, newRows: IntRange): Int =
    tokenMatches(old, new, oldRows, newRows).sumOf { (oldToken, _) -> tokenWeight(old.tokens[oldToken].text) }

/**
 * [groups] with the rows of the run between them that pair with none joining a group next to
 * them, one run at a time, the one that adds the most shared tokens first.
 */
private fun grownGroups(old: SourceCode, new: SourceCode, groups: List<LineGroup>, oldRows: IntRange, newRows: IntRange): List<LineGroup> =
    generateSequence(groups) { grownOnce(old, new, it, oldRows, newRows) }.last()

/** [groups] with the run of rows that adds the most shared tokens joining its group, or null when no run adds any. */
private fun grownOnce(old: SourceCode, new: SourceCode, groups: List<LineGroup>, oldRows: IntRange, newRows: IntRange): List<LineGroup>? {
    val best = groups.indices.asSequence()
        .flatMap { growthsOf(old, new, groups, it, oldRows, newRows) }
        .maxByOrNull { it.gain }
        ?.takeIf { it.gain > 0 }
        ?: return null
    return groups.mapIndexed { index, group -> if (index == best.index) best.group else group }
}

/** The group at [index], grown into [group] by a run of rows joining it, adding [gain] to the tokens they share. */
private class Growth(val index: Int, val group: LineGroup, val gain: Int)

/** The ways the rows that pair with none can join the group at [index] of [groups], from either side of it. */
private fun growthsOf(old: SourceCode, new: SourceCode, groups: List<LineGroup>, index: Int, oldRows: IntRange, newRows: IntRange): Sequence<Growth> {
    val group = groups[index]
    val before = groups.getOrNull(index - 1)
    val after = groups.getOrNull(index + 1)
    val shared = sharedWeight(old, new, group.oldRows, group.newRows)
    // The rows between this group and the one before it, or the one after it.
    val oldBefore = (before?.oldRows?.last?.plus(1) ?: oldRows.first) until group.oldRows.first
    val oldAfter = group.oldRows.last + 1..(after?.oldRows?.first?.minus(1) ?: oldRows.last)
    val newBefore = (before?.newRows?.last?.plus(1) ?: newRows.first) until group.newRows.first
    val newAfter = group.newRows.last + 1..(after?.newRows?.first?.minus(1) ?: newRows.last)
    val onOld = sequenceOf(oldBefore, oldAfter)
        .filter { !group.grownNew && !it.isEmpty() }
        .map { LineGroup(group.oldRows.spanning(it), group.newRows, grownOld = true) }
    val onNew = sequenceOf(newBefore, newAfter)
        .filter { !group.grownOld && !it.isEmpty() }
        .map { LineGroup(group.oldRows, group.newRows.spanning(it), grownNew = true) }
    return (onOld + onNew).map { Growth(index, it, sharedWeight(old, new, it.oldRows, it.newRows) - shared) }
}

/** These rows with [rows] next to them joining them. */
private fun IntRange.spanning(rows: IntRange): IntRange = minOf(first, rows.first)..maxOf(last, rows.last)

/** The rows with no words in them that no group has, paired with the same rows between the same groups. */
private fun leftOverLines(old: SourceCode, new: SourceCode, groups: List<LineGroup>, oldRows: IntRange, newRows: IntRange): List<LineGroup> {
    val bounds = listOf(LineGroup(oldRows.first - 1..oldRows.first - 1, newRows.first - 1..newRows.first - 1)) + groups +
        LineGroup(oldRows.last + 1..oldRows.last + 1, newRows.last + 1..newRows.last + 1)
    return bounds.zipWithNext { before, after ->
        val oldGap = (before.oldRows.last + 1 until after.oldRows.first).filter { old.lines[it].hasNoWord() }
        val newGap = (before.newRows.last + 1 until after.newRows.first).filter { new.lines[it].hasNoWord() }
        val pairs = longestCommonSubsequence(oldGap, newGap) { a, b -> if (old.lines[a].trim() == new.lines[b].trim()) 1 else 0 }
        pairs.withIndex().filter { it.value >= 0 }.map { (j, i) -> LineGroup(oldGap[i]..oldGap[i], newGap[j]..newGap[j]) }
    }.flatten()
}

/** The tokens of [oldRows] and [newRows] that continue one another, as (old token, new token). */
private fun tokenMatches(old: SourceCode, new: SourceCode, oldRows: IntRange, newRows: IntRange): Sequence<Pair<Int, Int>> {
    if (oldRows.isEmpty() || newRows.isEmpty()) return emptySequence()
    val oldTokens = old.tokensIn(oldRows)
    val newTokens = new.tokensIn(newRows)
    val matches = longestCommonSubsequence(
        oldTokens.map { old.tokens[it].text },
        newTokens.map { new.tokens[it].text },
    ) { a, b -> if (a != b) 0 else tokenWeight(a) }
    return matches.asSequence().withIndex().filter { it.value >= 0 }.map { (j, i) -> oldTokens.first + i to newTokens.first + j }
}

/** How much a token counts in matching: a symbol alone less than a word. */
private fun tokenWeight(token: String): Int = if (token.length == 1 && !token[0].isLetterOrDigit()) 1 else 3

/**
 * For each element of [new], the index of the element of [old] it is paired with in the
 * subsequence common to both with the highest total [weight], or -1. A weight of 0 means the
 * elements cannot be paired. Between pairings of the same weight, it takes the one that keeps
 * the most neighbors together, then the one pairing later elements of [new]: a token that could
 * go to a line before its neighbors, or with them, goes with them. Elements paired with a weight
 * of 1, such as blank lines and lone symbols, hold no neighbors together: a closing brace goes with
 * the lines before it, not with a blank line after another. An element of old that [needsNeighbors]
 * pairs only right after a pairing that holds neighbors.
 */
private fun <T> longestCommonSubsequence(old: List<T>, new: List<T>, needsNeighbors: (T) -> Boolean = { false }, weight: (T, T) -> Int): IntArray {
    // Weights count before neighbors: all the neighbors kept together weigh less than one weight.
    val scale = minOf(old.size, new.size) + 1
    // best[p][i][j]: the best total pairing old[i..] with new[j..], when old[i - 1] and new[j - 1]
    // are paired with a weight that holds neighbors (p = 1) or not (p = 0).
    val best = Array(2) { Array(old.size + 1) { IntArray(new.size + 1) } }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            val w = weight(old[i], new[j])
            val skipping = maxOf(best[0][i + 1][j], best[0][i][j + 1])
            val alone = needsNeighbors(old[i])
            for (p in 0..1) {
                best[p][i][j] = if (w > 0 && (p == 1 || !alone)) maxOf(skipping, w * scale + p * holds(w) + best[holds(w)][i + 1][j + 1]) else skipping
            }
        }
    }
    val result = IntArray(new.size) { -1 }
    var i = 0
    var j = 0
    var p = 0
    while (i < old.size && j < new.size) {
        val w = weight(old[i], new[j]).takeIf { p == 1 || !needsNeighbors(old[i]) } ?: 0
        val total = best[p][i][j]
        when {
            best[0][i][j + 1] == total -> j++.also { p = 0 }
            w > 0 && total == w * scale + p * holds(w) + best[holds(w)][i + 1][j + 1] -> result[j++] = i++.also { p = holds(w) }
            else -> i++.also { p = 0 }
        }
    }
    return result
}

/** 1 when a pairing of weight [w] holds its neighbors together, or 0. */
private fun holds(w: Int): Int = if (w > 1) 1 else 0

