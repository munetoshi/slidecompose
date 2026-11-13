package io.github.munetoshi.slidecompose.deck

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * What decks use (io.github.munetoshi.slidecompose.deck) stays apart from what plays them (io.github.munetoshi.slidecompose.player): the
 * player knows the slides, and the slides do not know the player.
 */
class DeckDependenciesTest : FunSpec({
    test("nothing under io.github.munetoshi.slidecompose.deck refers to io.github.munetoshi.slidecompose.player") {
        val sources = File("src/main/kotlin/io/github/munetoshi/slidecompose/deck").walk().filter { it.isFile && it.extension == "kt" }.toList()
        sources.shouldNotBeEmpty()
        sources.asSequence().flatMap(File::playerReferences).toList().shouldBeEmpty()
    }
})

/** The lines of this source file that name io.github.munetoshi.slidecompose.player, with where they are. */
private fun File.playerReferences(): Sequence<String> =
    readLines().asSequence()
        .withIndex()
        .filter { "io.github.munetoshi.slidecompose.player" in it.value }
        .map { "$name:${it.index + 1}: ${it.value.trim()}" }
