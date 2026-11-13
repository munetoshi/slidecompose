package io.github.munetoshi.slidecompose.viewer.safemode

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * What [copyDeck] made: the copy of a deck that safe mode's child reads, or why there is none.
 *
 * The viewer copies the deck into the child's work folder before the child starts, so the child
 * reads no folder of the user's, only files the viewer wrote:
 * - A folder: its files and folders, but hidden ones such as `.git`.
 * - One file: that file, and the files and folders the user chose with it from its folder. The
 *   folder may be any, such as Downloads, so nothing else there is copied.
 *
 * The viewer reads with all of the user's rights, so it follows no link. A symbolic link is left
 * out. A file with another hard link stops the copy: the other name may be any file of the user's,
 * and nothing tells which.
 */
internal sealed interface DeckCopy {
    /** The copy of the deck, to give the child. */
    data class Copied(val deck: File) : DeckCopy

    /** Why safe mode does not open a deck. */
    sealed interface Refused : DeckCopy

    /** [file], a path from the folder the deck is in, has another hard link. */
    data class HardLink(val file: String) : Refused

    /** [file], chosen to go with a deck of one file, is not in the deck's folder. */
    data class Outside(val file: String) : Refused

    /** The deck has more than [MAX_ENTRIES] files and folders (itself included), or more than [MAX_BYTES] of files. */
    data object TooLarge : Refused

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_BYTES = 2L shl 30
    }
}

/** A file or folder of a deck, as it is: links not followed. */
private class Entry(val path: Path, val attributes: BasicFileAttributes, val links: Int)

/**
 * Copies [deck], a folder or one file with the files and folders [chosen] with it, into the folder
 * [into], which it makes, unless it may not be copied: a chosen file is not in the deck's folder,
 * a file has another hard link, or there are more than [maxEntries] files and folders or [maxBytes].
 */
internal fun copyDeck(
    deck: File,
    into: File,
    chosen: List<File> = emptyList(),
    maxEntries: Int = DeckCopy.MAX_ENTRIES,
    maxBytes: Long = DeckCopy.MAX_BYTES,
): DeckCopy {
    // The deck itself is what the user chose, wherever a link to it leads.
    val source = deck.canonicalFile.toPath()
    val folder = source.parent
    val picks = chosen.map(File::toPathInRealFolder)
    picks.firstOrNull { !it.startsWith(folder) || it == folder }?.let { return DeckCopy.Outside(it.toString()) }
    val inside = if (source.isDirectory(LinkOption.NOFOLLOW_LINKS)) source.contents() else picks.asSequence().flatMap(Path::withContents)
    val entries = (sequenceOf(source) + inside).distinct().map(Path::toEntry).take(maxEntries + 1).toList()
    val bytes = entries.asSequence().filter { it.attributes.isRegularFile }.sumOf { it.attributes.size() }
    if (entries.size > maxEntries || bytes > maxBytes) return DeckCopy.TooLarge
    entries.firstOrNull { it.attributes.isRegularFile && it.links > 1 }?.let { return DeckCopy.HardLink(folder.relativize(it.path).toString()) }
    // In the copy as in the deck's folder, so that paths from the deck lead to the same files.
    val target = into.toPath().createDirectories()
    entries.forEach { copyEntry(it, target.resolve(folder.relativize(it.path))) }
    return DeckCopy.Copied(target.resolve(source.name).toFile())
}

/** Where a chosen file is, in its folder's real place, but itself as it is: a link is not followed. */
private fun File.toPathInRealFolder(): Path = File(absoluteFile.parentFile.canonicalFile, name).toPath()

/** This file, or this folder and what is in it, without following links. */
private fun Path.withContents(): Sequence<Path> =
    if (isDirectory(LinkOption.NOFOLLOW_LINKS)) sequenceOf(this) + contents() else sequenceOf(this)

/** The files and folders in this folder and below, folders first, but hidden ones, without following links. */
private fun Path.contents(): Sequence<Path> =
    listDirectoryEntries().sorted().asSequence()
        .filterNot { it.name.startsWith(".") }
        .flatMap(Path::withContents)

private fun Path.toEntry(): Entry =
    Entry(
        this,
        Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS),
        Files.getAttribute(this, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Int,
    )

private fun copyEntry(entry: Entry, to: Path) {
    when {
        entry.attributes.isDirectory -> to.createDirectories()
        // Opened without following a link, in case one took its place since. A chosen file may
        // be in a folder that was not chosen.
        entry.attributes.isRegularFile -> Files.newInputStream(entry.path, LinkOption.NOFOLLOW_LINKS).use { Files.copy(it, to.parent.createDirectories().resolve(to.name)) }
        // Links, and anything else, such as a pipe, are left out.
    }
}
