package io.github.munetoshi.slidecompose.viewer.cli

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.player.export.PdfExportOptions
import io.github.munetoshi.slidecompose.player.export.exportPdf
import io.github.munetoshi.slidecompose.player.export.exportPngs
import io.github.munetoshi.slidecompose.viewer.compile.DeckCompiler
import io.github.munetoshi.slidecompose.viewer.compile.LoadResult
import io.github.munetoshi.slidecompose.viewer.compile.assetDirectoryOf
import io.github.munetoshi.slidecompose.viewer.compile.deckLibraries
import io.github.munetoshi.slidecompose.viewer.compile.deckSources
import io.github.munetoshi.slidecompose.viewer.compile.unusedLibrariesNote
import io.github.munetoshi.slidecompose.viewer.theme.ThemeLoader
import io.github.munetoshi.slidecompose.viewer.theme.themeChoiceOf
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: slidecompose [options] [deck.kt | deck-directory]

Opens a slide deck written in Kotlin + Compose and reloads it whenever a source file changes.
A deck is a .kt file (or a directory of them) declaring `val deck = presentation { ... }`.
Jars in the `libs` directory of a deck directory are added to its classpath.

A deck is a program and runs with your permissions. The window asks before running a deck
for the first time; --pdf and --png do not ask, like running any other program.

Options:
  --pdf <file>     Export the deck as PDF and exit (no window). Text and shapes stay vectors, and
                   the text can be searched and copied.
  --png <dir>      Export every slide as PNG and exit (no window).
  --builds         With --pdf/--png: one page per build step instead of per slide.
  --images         With --pdf: make every page one image, without text to search or copy.
  --scale <n>      With --pdf/--png: pixels per slide unit in images (default: 2 for PDF, 1 for PNG).
  --theme <theme>  With --pdf/--png: show the deck in a built-in theme (Light, Dark), a theme
                   file (.kt) or a preset in one (file.kt#name) instead of its own.
  -h, --help       Show this help.
"""

internal data class Options(
    val deck: File? = null,
    val pdf: File? = null,
    val png: File? = null,
    val builds: Boolean = false,
    val images: Boolean = false,
    val scale: Float? = null,
    val theme: String? = null,
)

internal fun parseOptions(args: Array<String>): Options = parseOptions(args, 0, Options())

/** [options] with the arguments from [at] on added. */
internal tailrec fun parseOptions(args: Array<String>, at: Int, options: Options): Options {
    val arg = args.getOrNull(at) ?: return options
    // An option's value is the next argument.
    val value by lazy { args.getOrNull(at + 1) ?: fail("$arg needs a value") }
    val (next, taken) = when (arg) {
        "-h", "--help" -> {
            println(USAGE)
            exitProcess(0)
        }
        "--pdf" -> options.copy(pdf = File(value).absoluteFile) to 2
        "--png" -> options.copy(png = File(value).absoluteFile) to 2
        "--builds" -> options.copy(builds = true) to 1
        "--images" -> options.copy(images = true) to 1
        "--scale" -> options.copy(scale = value.toFloatOrNull() ?: fail("--scale needs a number")) to 2
        "--theme" -> options.copy(theme = value) to 2
        // macOS passes -psn_… to apps opened from Finder.
        else -> when {
            !arg.startsWith("-") -> options.copy(deck = File(arg).absoluteFile) to 1
            arg.startsWith("-psn_") -> options to 1
            else -> fail("Unknown option: $arg")
        }
    }
    return parseOptions(args, at + taken, next)
}

internal fun fail(message: String): Nothing {
    System.err.println("slidecompose: $message\n\n$USAGE")
    exitProcess(2)
}

internal fun exportHeadless(options: Options) {
    System.setProperty("java.awt.headless", "true")
    val deck = options.deck ?: fail("No deck given")
    println("Compiling ${deck.path}…")
    val assets = assetDirectoryOf(deck)
    val libraries = deckLibraries(deck)
    val compiler = DeckCompiler()
    val theme = options.theme?.let { themeOf(it, compiler, libraries) }
    when (val result = compiler.compileAndLoad(deckSources(deck), assets, libraries, theme)) {
        is LoadResult.Failure -> {
            (result.diagnostics + listOfNotNull(unusedLibrariesNote(deck))).forEach(System.err::println)
            exitProcess(1)
        }
        is LoadResult.Success -> {
            result.warnings.forEach(System.err::println)
            options.pdf?.let { exportPdfTo(it, result.presentation, options, assets) }
            options.png?.let { exportPngsTo(it, result.presentation, options, assets) }
        }
    }
    exitProcess(0)
}

/** The theme [argument] names, or the end of the program with its errors. */
private fun themeOf(argument: String, compiler: DeckCompiler, libraries: List<File>): SlideTheme? {
    val resolved = ThemeLoader(compiler).resolve(themeChoiceOf(argument), libraries)
    if (resolved.errors.isNotEmpty()) {
        resolved.errors.forEach(System.err::println)
        exitProcess(1)
    }
    return resolved.theme
}

private fun exportPdfTo(pdf: File, presentation: Presentation, options: Options, assets: File) {
    val export = presentation.exportPdf(
        pdf,
        PdfExportOptions(includeBuildSteps = options.builds, vector = !options.images, scale = options.scale ?: 2f),
        assets,
        ::printProgress,
    )
    println("\nWrote ${pdf.path}${if (export.vector || options.images) "" else ", with every page an image"}")
    if (export.imagePages.isNotEmpty()) {
        println("Pages made of one image, because something on them could not be kept: ${export.imagePages.joinToString()}")
    }
}

private fun printProgress(done: Int, total: Int) = print("\rRendering $done / $total")

private fun exportPngsTo(directory: File, presentation: Presentation, options: Options, assets: File) {
    val files = presentation.exportPngs(directory, options.builds, options.scale ?: 1f, assets)
    println("Wrote ${files.size} images to ${directory.path}")
}
