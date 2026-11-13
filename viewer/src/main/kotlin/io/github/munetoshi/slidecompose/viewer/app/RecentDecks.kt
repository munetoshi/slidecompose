package io.github.munetoshi.slidecompose.viewer.app

import java.io.File
import java.util.prefs.Preferences

/** How many recent decks are kept, and listed in the File menu. */
private const val KeptDeckCount = 10

private const val RecentKey = "recentDecks"

/** The one deck that earlier versions remembered. */
private const val LastDeckKey = "lastDeck"

/** The decks opened lately, newest first, kept in [preferences] one path a line. */
internal class RecentDecks(private val preferences: Preferences) {
    /** The kept decks that still exist. */
    fun existing(): List<File> = kept().filter(File::exists)

    /** Puts [deck] first. */
    fun add(deck: File) {
        val decks = (sequenceOf(deck) + kept().asSequence().filter { it != deck }).take(KeptDeckCount)
        preferences.put(RecentKey, decks.joinToString("\n") { it.path })
    }

    private fun kept(): List<File> =
        (preferences.get(RecentKey, null) ?: preferences.get(LastDeckKey, null))
            ?.lineSequence()
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            ?.toList()
            .orEmpty()

    /** Forgets them all. */
    fun clear() {
        preferences.put(RecentKey, "")
    }
}
