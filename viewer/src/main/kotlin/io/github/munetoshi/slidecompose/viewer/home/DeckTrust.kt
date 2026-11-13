package io.github.munetoshi.slidecompose.viewer.home

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.prefs.Preferences

/**
 * The decks the user trusts. A deck is a program: opening one runs its code with the user's
 * rights, before any slide shows. So the viewer asks before compiling a deck it has not seen, as
 * VS Code does for folders. Trusting a folder trusts everything in it, since a folder deck
 * compiles every file below it, its themes included, and uses the jars in its `libs`. Trusting a
 * file trusts that file only: the themes next to a deck of one file ask on their own, and it uses
 * no jars.
 */
class DeckTrust(private val node: Preferences) {
    fun contains(deck: File): Boolean =
        generateSequence(deck.canonicalFile) { it.parentFile }.any { node.get(keyOf(it), null) == it.path }

    fun add(deck: File) {
        val file = deck.canonicalFile
        node.put(keyOf(file), file.path)
    }
}

/** Preferences keys are at most 80 characters long, so a path is stored under its hash. */
internal fun keyOf(file: File): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.path.toByteArray()))
