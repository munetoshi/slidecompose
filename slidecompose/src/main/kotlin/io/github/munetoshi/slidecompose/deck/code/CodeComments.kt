package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Comments out the lines of the selection in [state] for ⌘/ (Ctrl+/ off macOS), or comments them
 * in again when they all are, as code editors do, with the line comments of [language]. Returns
 * whether [event] was that key.
 */
internal fun commentKey(event: KeyEvent, state: CodeBlockState, language: String, isMac: Boolean = IsMac): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Slash) return false
    val command = if (isMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed
    if (!command || event.isAltPressed || event.isShiftPressed) return false
    state.text = toggledComments(state.text, lineCommentOf(language))
    return true
}

private val IsMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** What starts a line comment in [language]. */
internal fun lineCommentOf(language: String): String = when (language.lowercase()) {
    "python", "py", "ruby", "rb", "shell", "sh", "bash", "zsh", "yaml", "yml", "toml" -> "#"
    else -> "//"
}

/**
 * [value] with the lines of its selection (the caret's line without one) commented out with
 * [comment] and a space, at the indent the least indented of them has, or, when all of them are
 * comments, with the comment marks taken out, with a space after them. Blank lines stay as they
 * are. The selection stays on the same code.
 */
internal fun toggledComments(value: TextFieldValue, comment: String): TextFieldValue {
    val text = value.text
    val lines = selectedLines(text, value.selection)
    val written = lines.filter { text.substring(it).isNotBlank() }.ifEmpty { lines }
    val uncomments = written.all { text.substring(it).trimStart().startsWith(comment) }
    val indent = written.minOf { text.substring(it).indentWidth() }
    val edits = written.map { line -> if (uncomments) uncommenting(text, line, comment) else TextEdit(line.first + indent, 0, "$comment ") }
    return TextFieldValue(applied(text, edits), TextRange(movedBy(value.selection.start, edits), movedBy(value.selection.end, edits)))
}

/** A change to text: [length] characters at [at] replaced with [inserted]. */
internal class TextEdit(val at: Int, val length: Int, val inserted: String)

/** The comment mark of [line] in [text], and a space after it, taken out. */
private fun uncommenting(text: String, line: IntRange, comment: String): TextEdit {
    val at = line.first + text.substring(line).indentWidth()
    val after = at + comment.length
    val space = if (after <= line.last && text[after] == ' ') 1 else 0
    return TextEdit(at, comment.length + space, "")
}

/** The lines of [text] (each without its line break) that [selection] covers: a selection that ends at the start of a line leaves that line out. */
private fun selectedLines(text: String, selection: TextRange): List<IntRange> {
    val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    val lines = starts.mapIndexed { i, start -> start until (starts.getOrNull(i + 1)?.minus(1) ?: text.length) }
    val last = if (!selection.collapsed && selection.max > 0 && text[selection.max - 1] == '\n') selection.max - 1 else selection.max
    return lines.filter { it.first <= last && it.last + 1 >= selection.min }
}

private fun String.indentWidth(): Int = takeWhile { it == ' ' || it == '\t' }.length

/** [text] with [edits] made, which are in order and do not overlap. */
private fun applied(text: String, edits: List<TextEdit>): String =
    edits.asReversed().fold(text) { result, edit -> result.replaceRange(edit.at, edit.at + edit.length, edit.inserted) }

/** Where [offset] is once [edits] are made: after what they put before it, at the start of what they took out around it. */
private fun movedBy(offset: Int, edits: List<TextEdit>): Int =
    edits.fold(offset) { moved, edit ->
        when {
            offset < edit.at || (offset == edit.at && edit.length > 0) -> moved
            offset < edit.at + edit.length -> moved - (offset - edit.at)
            else -> moved - edit.length + edit.inserted.length
        }
    }
