package io.github.munetoshi.slidecompose.viewer.compile

import java.io.File
import java.net.URLClassLoader

/**
 * The class loaders of the libraries decks and themes use, the jars in their `libs` folder, under
 * [parent], the app's. A deck compiled again with the same jars, as on a reload, loads them with
 * the same loader: the libraries' classes stay the same, and what they keep stays with them, such
 * as code typed into a slide. A jar that changes, or goes, gets a loader of its own again.
 */
internal class LibraryLoaders(private val parent: ClassLoader) {
    /** The loader of each set of jars, by their paths, with the jars as they were when it was made. */
    private val loaders = HashMap<List<String>, Pair<List<LibraryJar>, ClassLoader>>()

    /** The loader of [libraries], or the [parent] when there are none. */
    @Synchronized
    fun of(libraries: List<File>): ClassLoader {
        if (libraries.isEmpty()) return parent
        val jars = libraries.map(File::toLibraryJar)
        val paths = jars.map { it.path }
        loaders[paths]?.takeIf { it.first == jars }?.let { return it.second }
        // The loader of the jars as they were stays with what it loaded, which may still show.
        val loader = URLClassLoader(libraries.map { it.toURI().toURL() }.toTypedArray(), parent)
        loaders[paths] = jars to loader
        return loader
    }
}

/** A jar as it is now: where it is, when it last changed and how long it is. */
private data class LibraryJar(val path: String, val lastModified: Long, val length: Long)

private fun File.toLibraryJar(): LibraryJar = LibraryJar(absolutePath, lastModified(), length())
