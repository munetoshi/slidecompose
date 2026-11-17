package io.github.munetoshi.slidecompose.viewer.compile

import java.io.File

internal fun assetDirectoryOf(deck: File): File = if (deck.isDirectory) deck else deck.absoluteFile.parentFile

/**
 * The jars [deck] uses: those in the `libs` folder of a deck that is a folder. A deck of one file
 * uses none: its folder may hold anything, such as Downloads, and trusting the file trusts no jar
 * next to it.
 */
fun deckLibraries(deck: File): List<File> = if (deck.isDirectory) jarsIn(File(deck, LIBRARIES_DIRECTORY)) else emptyList()

/**
 * A note for a deck of one file that did not compile when its folder has jars in `libs`: the deck
 * may count on them, and opening the folder is how to use them. `null` otherwise.
 */
fun unusedLibrariesNote(deck: File): Diagnostic? {
    if (deck.isDirectory) return null
    val folder = deck.absoluteFile.parentFile
    if (jarsIn(File(folder, LIBRARIES_DIRECTORY)).isEmpty()) return null
    return Diagnostic(
        isError = false,
        message = "$LIBRARIES_DIRECTORY/ is used only by a deck opened as a folder: open the folder ${folder.name} to use its jars",
    )
}

/** The folder of a folder deck that holds the jars it uses. */
private const val LIBRARIES_DIRECTORY = "libs"

/** The jars in [directory], by name. */
private fun jarsIn(directory: File): List<File> =
    directory.listFiles { file -> file.extension == "jar" }?.sortedBy { it.name }.orEmpty()

/** The folder next to a deck that holds its theme files. */
const val THEMES_DIRECTORY = "themes"

/**
 * The Kotlin files that make up the deck at [path]: the file itself, or all files in a directory
 * except its [themes][THEMES_DIRECTORY], which are compiled on their own.
 */
fun deckSources(path: File): List<File> = when {
    path.isFile -> listOf(path)
    path.isDirectory -> path.walkTopDown()
        .onEnter { it == path || !it.name.startsWith(".") && it.name != "build" && it != File(path, THEMES_DIRECTORY) }
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()
    else -> emptyList()
}
