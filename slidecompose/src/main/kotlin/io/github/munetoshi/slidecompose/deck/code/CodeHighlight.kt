package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import io.github.munetoshi.slidecompose.deck.theme.CodeColors

private val kotlinKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
    "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set",
    "setparam", "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
    "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
    "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified",
    "sealed", "suspend", "tailrec", "vararg", "value",
)

private val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "var", "record", "sealed", "permits", "yield", "true", "false", "null",
)

private val swiftKeywords = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
    "inout", "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows",
    "static", "struct", "subscript", "typealias", "var", "break", "case", "continue", "default",
    "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch",
    "where", "while", "as", "catch", "false", "is", "nil", "self", "Self", "super", "throw", "throws",
    "true", "try", "async", "await", "some", "any", "mutating", "override", "final", "lazy", "weak",
)

private val typeScriptKeywords = setOf(
    "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const",
    "constructor", "continue", "debugger", "declare", "default", "delete", "do", "else", "enum",
    "export", "extends", "false", "finally", "for", "from", "function", "get", "if", "implements",
    "import", "in", "infer", "instanceof", "interface", "is", "keyof", "let", "namespace", "never",
    "new", "null", "number", "of", "private", "protected", "public", "readonly", "return",
    "satisfies", "set", "static", "string", "super", "switch", "symbol", "this", "throw", "true",
    "try", "type", "typeof", "undefined", "unknown", "var", "void", "while", "yield",
)

private val pythonKeywords = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "case", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
    "import", "in", "is", "lambda", "match", "nonlocal", "not", "or", "pass", "raise", "return",
    "self", "try", "while", "with", "yield",
)

/** Opacity of code lines that are not in focus. */
internal const val DIMMED_CODE_ALPHA = 0.5f

private const val C_STYLE_COMMENT = """//[^\n]*|/\*[\s\S]*?\*/"""

/** Comments, strings, annotations, numbers and words, in groups 1 to 5, as [tokenStyle] takes them. */
private fun tokenPatternOf(comment: String, string: String, word: String = """\b[A-Za-z_][A-Za-z0-9_]*\b"""): Regex = Regex(
    listOf(
        comment, // 1
        string, // 2
        """@[A-Za-z_][A-Za-z0-9_]*""", // 3: annotation, or decorator
        """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?[fFLjn]?)\b""", // 4: number
        word, // 5
    ).joinToString("|") { "($it)" },
)

/** Kotlin, Java and Swift: comments after `//` or between `/*` and `*/`. */
private val cStylePattern = tokenPatternOf(
    comment = C_STYLE_COMMENT,
    string = "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'",
)

/** TypeScript and JavaScript: C-style comments, strings between backticks too, over lines, and `$` in words. */
private val typeScriptPattern = tokenPatternOf(
    comment = C_STYLE_COMMENT,
    string = "`(?:\\\\.|[^`\\\\])*`|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'",
    word = """(?<![\w$])[A-Za-z_$][\w$]*(?![\w$])""",
)

/** Python: comments after `#` (`//` divides), and strings between three quotes over lines. */
private val pythonPattern = tokenPatternOf(
    comment = """#[^\n]*""",
    string = "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'",
)

/** How code in a language is colored: its [keywords], and the [tokens] its comments, strings and words are. */
private class CodeSyntax(val keywords: Set<String>, val tokens: Regex)

/** The syntax of code in this language, by its name or file extension: C-style without keywords for others. */
private fun String.toSyntax(): CodeSyntax = when (lowercase()) {
    "kotlin", "kt", "kts" -> CodeSyntax(kotlinKeywords, cStylePattern)
    "java" -> CodeSyntax(javaKeywords, cStylePattern)
    "swift" -> CodeSyntax(swiftKeywords, cStylePattern)
    "typescript", "ts", "javascript", "js" -> CodeSyntax(typeScriptKeywords, typeScriptPattern)
    "python", "py" -> CodeSyntax(pythonKeywords, pythonPattern)
    else -> CodeSyntax(emptySet(), cStylePattern)
}

/**
 * A small, dependency-free highlighter for slides. It knows Kotlin, Java, Swift, TypeScript (and
 * JavaScript) and Python; any other [language] only gets strings, comments and numbers
 * highlighted, with comments as in Kotlin.
 *
 * What is out of focus is dimmed: the lines out of [focusLines] (from 1), or, with [focusRanges],
 * all but those parts of [code] (offsets), which then take the place of [focusLines].
 */
fun highlightCode(
    code: String,
    language: String,
    colors: CodeColors,
    focusLines: IntRange? = null,
    focusRanges: List<IntRange>? = null,
): AnnotatedString {
    val syntax = language.toSyntax()
    val keywords = syntax.keywords
    val lineStarts = lineStartsOf(code)
    // What is out of focus is dimmed as a whole; its tokens get no color of their own, because
    // span styles that start later win over the dimmed style.
    val dimmed = if (focusRanges != null) {
        dimmedOutside(focusRanges, code.length, colors)
    } else {
        lineStarts.indices.asSequence()
            .filterNot { isFocused(focusLines, it + 1) }
            .map { dimmedLine(lineStarts, it, code.length, colors) }
    }
    val tokens = syntax.tokens.findAll(code)
        .filter { isFocused(focusLines, focusRanges, lineStarts, it.range.first) }
        .mapNotNull { tokenStyle(it, code, keywords, colors) }
    return AnnotatedString(code, spanStyles = (dimmed + tokens).toList())
}

/** The styles that dim [length] characters of code but [focusRanges]. */
private fun dimmedOutside(focusRanges: List<IntRange>, length: Int, colors: CodeColors): Sequence<AnnotatedString.Range<SpanStyle>> {
    val style = SpanStyle(color = colors.text.copy(alpha = DIMMED_CODE_ALPHA))
    val sorted = focusRanges.sortedBy { it.first }
    val starts = sequenceOf(0) + sorted.asSequence().map { it.last + 1 }
    val ends = sorted.asSequence().map { it.first } + sequenceOf(length)
    return starts.zip(ends).filter { (start, end) -> end > start }.map { (start, end) -> AnnotatedString.Range(style, start, end) }
}

/** Whether the token at [offset] is in focus: in one of [focusRanges] if there are, or else on a line of [focusLines]. */
private fun isFocused(focusLines: IntRange?, focusRanges: List<IntRange>?, lineStarts: List<Int>, offset: Int): Boolean =
    if (focusRanges != null) focusRanges.any { offset in it } else isFocused(focusLines, lineOf(lineStarts, offset))

/** Where each line of [code] starts. */
private fun lineStartsOf(code: String): List<Int> =
    (sequenceOf(0) + code.indices.asSequence().filter { code[it] == '\n' }.map { it + 1 }).toList()

/** The style of the line at [index], dimmed as a whole. */
private fun dimmedLine(lineStarts: List<Int>, index: Int, length: Int, colors: CodeColors): AnnotatedString.Range<SpanStyle> {
    val lineEnd = lineStarts.getOrNull(index + 1)?.minus(1) ?: length
    return AnnotatedString.Range(SpanStyle(color = colors.text.copy(alpha = DIMMED_CODE_ALPHA)), lineStarts[index], lineEnd)
}

/** The color of the token [match] in [code], or null for a token shown plain. Words are colored only for a language with [keywords]. */
private fun tokenStyle(match: MatchResult, code: String, keywords: Set<String>, colors: CodeColors): AnnotatedString.Range<SpanStyle>? {
    val highlightsWords = keywords.isNotEmpty()
    val style = when {
        match.groups[1] != null -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        match.groups[2] != null -> SpanStyle(color = colors.string)
        match.groups[3] != null && highlightsWords -> SpanStyle(color = colors.annotation)
        match.groups[4] != null -> SpanStyle(color = colors.number)
        match.groups[5] != null && highlightsWords -> wordStyle(match, code, keywords, colors)
        else -> null
    }
    return style?.let { AnnotatedString.Range(it, match.range.first, match.range.last + 1) }
}

/** A keyword, a function called (a lowercase word before a parenthesis or a brace), or a type (capitalized). */
private fun wordStyle(match: MatchResult, code: String, keywords: Set<String>, colors: CodeColors): SpanStyle? {
    val word = match.value
    val nextChar = (match.range.last + 1 until code.length).firstOrNull { code[it] != ' ' }?.let(code::get)
    return when {
        word in keywords -> SpanStyle(color = colors.keyword)
        (nextChar == '(' || nextChar == '{') && word.first().isLowerCase() -> SpanStyle(color = colors.function)
        word.first().isUpperCase() -> SpanStyle(color = colors.type)
        else -> null
    }
}

/** The 1-based line number of [offset], in text whose lines start at [lineStarts]. */
private fun lineOf(lineStarts: List<Int>, offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it + 1 else -it - 1 }

/** Whether the 1-based [line] is in focus: every line is, without [focusLines]. */
private fun isFocused(focusLines: IntRange?, line: Int): Boolean = focusLines == null || line in focusLines
