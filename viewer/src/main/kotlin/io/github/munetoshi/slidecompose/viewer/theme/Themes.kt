package io.github.munetoshi.slidecompose.viewer.theme

import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.viewer.compile.DeckCompiler
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import io.github.munetoshi.slidecompose.viewer.compile.NamedTheme
import io.github.munetoshi.slidecompose.viewer.compile.THEMES_DIRECTORY
import io.github.munetoshi.slidecompose.viewer.compile.ThemeResult
import io.github.munetoshi.slidecompose.viewer.home.keyOf
import java.io.File
import java.util.prefs.Preferences

/** The themes that come with SlideCompose. */
enum class BuiltInTheme(val theme: SlideTheme) {
    Light(SlideTheme.Light),
    Dark(SlideTheme.Dark),
}

/** The theme the viewer shows a deck in. */
sealed interface ThemeChoice {
    /** The theme the deck's code gives. */
    data object Deck : ThemeChoice

    data class BuiltIn(val theme: BuiltInTheme) : ThemeChoice

    /**
     * A theme file: a `.kt` file declaring `val theme = SlideTheme(...)`, compiled on its own. [preset]
     * names another theme it declares, or is `null` for its main one: see [DeckCompiler.compileTheme].
     */
    data class File(val file: java.io.File, val preset: String? = null) : ThemeChoice
}

/** The theme files in the deck at [assetDirectory]: the `.kt` files in its `themes` folder. */
fun themeFiles(assetDirectory: File): List<File> =
    File(assetDirectory, THEMES_DIRECTORY).listFiles { file -> file.isFile && file.extension == "kt" }
        ?.asSequence()
        ?.map { it.canonicalFile }
        ?.sortedBy { it.name }
        ?.toList()
        .orEmpty()

/**
 * Finds the theme `--theme` names: a built-in theme by name, ignoring case, a theme file, or a
 * preset in one as `file.kt#name`.
 */
fun themeChoiceOf(argument: String): ThemeChoice {
    BuiltInTheme.entries.firstOrNull { it.name.equals(argument, ignoreCase = true) }?.let { return ThemeChoice.BuiltIn(it) }
    val file = File(argument)
    if (file.isFile || '#' !in argument) return ThemeChoice.File(file.canonicalFile)
    return ThemeChoice.File(File(argument.substringBeforeLast('#')).canonicalFile, argument.substringAfterLast('#'))
}

/** The theme chosen for each deck, remembered across launches. Decks without one use their own. */
class ThemeChoices(private val node: Preferences) {
    fun get(deck: File): ThemeChoice {
        val value = node.get(keyOf(deck.canonicalFile), null) ?: return ThemeChoice.Deck
        val builtIn = BuiltInTheme.entries.firstOrNull { "$BUILT_IN_PREFIX${it.name}" == value }
        val choice = when {
            builtIn != null -> ThemeChoice.BuiltIn(builtIn)
            value.startsWith(FILE_PREFIX) -> ThemeChoice.File(File(value.removePrefix(FILE_PREFIX)))
            // Names in Kotlin for the JVM cannot contain ':'.
            value.startsWith(PRESET_PREFIX) -> value.removePrefix(PRESET_PREFIX).split(':', limit = 2)
                .takeIf { it.size == 2 }
                ?.let { (preset, path) -> ThemeChoice.File(File(path), preset) }
            else -> null
        }
        return choice?.takeIf { it !is ThemeChoice.File || it.file.isFile } ?: ThemeChoice.Deck
    }

    fun set(deck: File, choice: ThemeChoice) {
        val key = keyOf(deck.canonicalFile)
        when (choice) {
            ThemeChoice.Deck -> node.remove(key)
            is ThemeChoice.BuiltIn -> node.put(key, "$BUILT_IN_PREFIX${choice.theme.name}")
            is ThemeChoice.File -> node.put(
                key,
                if (choice.preset == null) "$FILE_PREFIX${choice.file.path}" else "$PRESET_PREFIX${choice.preset}:${choice.file.path}",
            )
        }
    }

    private companion object {
        const val BUILT_IN_PREFIX = "builtin:"
        const val FILE_PREFIX = "file:"
        const val PRESET_PREFIX = "preset:"
    }
}

/** A theme to show a deck in, `null` for its own, and why a chosen theme file could not be used. */
data class ResolvedTheme(val theme: SlideTheme?, val errors: List<Diagnostic> = emptyList())

/** The themes in a theme file, its main one first, and why it does not compile, if it does not. */
data class ThemeFile(val themes: List<NamedTheme>, val errors: List<Diagnostic> = emptyList())

/**
 * Compiles theme files as they change. While a theme file does not compile, the version of it
 * that last did stays in use, as with decks. Not thread-safe: use it from one thread.
 */
class ThemeLoader(private val compiler: DeckCompiler) {
    private class Loaded(val stamp: String, val file: ThemeFile)

    private val loaded = mutableMapOf<File, Loaded>()

    fun resolve(choice: ThemeChoice, libraries: List<File>): ResolvedTheme = when (choice) {
        ThemeChoice.Deck -> ResolvedTheme(null)
        is ThemeChoice.BuiltIn -> ResolvedTheme(choice.theme.theme)
        is ThemeChoice.File -> {
            val file = load(choice.file, libraries)
            val theme = if (choice.preset == null) file.themes.firstOrNull() else file.themes.firstOrNull { it.name == choice.preset }
            val missing = if (theme == null && file.errors.isEmpty()) {
                listOf(Diagnostic(true, "No theme named ${choice.preset} in ${choice.file.name}", file = choice.file.path))
            } else {
                emptyList()
            }
            ResolvedTheme(theme?.theme, file.errors + missing)
        }
    }

    /** The themes in [file], compiled again if it has changed. */
    fun load(file: File, libraries: List<File>): ThemeFile {
        if (!file.isFile) return ThemeFile(emptyList(), listOf(Diagnostic(true, "Theme file not found: ${file.path}")))
        val stamp = file.toStamp()
        val last = loaded[file]
        if (last != null && last.stamp == stamp) return last.file
        val compiled = when (val result = compiler.compileTheme(file, libraries)) {
            is ThemeResult.Success -> {
                result.warnings.forEach { System.err.println(it) }
                ThemeFile(result.themes)
            }
            is ThemeResult.Failure -> ThemeFile(last?.file?.themes.orEmpty(), result.diagnostics)
        }
        loaded[file] = Loaded(stamp, compiled)
        return compiled
    }
}

/** Changes whenever the theme [choice] stands for may have changed. */
fun themeStampOf(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.Deck -> "deck"
    is ThemeChoice.BuiltIn -> "builtin:${choice.theme.name}"
    is ThemeChoice.File -> "file:${choice.preset}:${choice.file.toStamp()}"
}

/** Changes whenever one of the theme [files] may have changed. */
fun themeFilesStampOf(files: List<File>): String = files.joinToString("|") { it.toStamp() }

private fun File.toStamp(): String = "$path:${lastModified()}:${length()}"
