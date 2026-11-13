package io.github.munetoshi.slidecompose.viewer.app

import io.github.munetoshi.slidecompose.viewer.MemoryPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class RecentDecksTest : FunSpec({
    fun decks(count: Int): List<File> {
        val directory = tempdir()
        return (1..count).map { File(directory, "Deck$it.kt").apply { writeText("") } }
    }

    test("lists the decks newest first, each once") {
        val (a, b, c) = decks(3)
        val recent = RecentDecks(MemoryPreferences())
        listOf(a, b, c, a).forEach(recent::add)
        recent.existing() shouldBe listOf(a, c, b)
    }

    test("keeps ten, and leaves out those that are gone") {
        val all = decks(12)
        val recent = RecentDecks(MemoryPreferences())
        all.forEach(recent::add)
        recent.existing() shouldBe all.reversed().take(10)
        all.last().delete()
        recent.existing() shouldBe all.reversed().drop(1).take(9)
    }

    test("starts from the one deck that earlier versions remembered, and forgets them all on clear") {
        val (a, b) = decks(2)
        val preferences = MemoryPreferences().apply { put("lastDeck", a.path) }
        val recent = RecentDecks(preferences)
        recent.existing() shouldBe listOf(a)
        recent.add(b)
        recent.existing() shouldBe listOf(b, a)
        recent.clear()
        recent.existing().shouldBeEmpty()
        RecentDecks(preferences).existing().shouldBeEmpty()
    }
})
