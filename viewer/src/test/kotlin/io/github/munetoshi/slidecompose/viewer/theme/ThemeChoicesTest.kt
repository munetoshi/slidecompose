package io.github.munetoshi.slidecompose.viewer.theme

import io.github.munetoshi.slidecompose.viewer.MemoryPreferences
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ThemeChoicesTest : FunSpec({
    context("themeChoiceOf") {
        test("finds built-in themes by name, ignoring case") {
            themeChoiceOf("dark") shouldBe ThemeChoice.BuiltIn(BuiltInTheme.Dark)
            themeChoiceOf("Light") shouldBe ThemeChoice.BuiltIn(BuiltInTheme.Light)
        }

        test("takes anything else as a theme file, with a preset after #") {
            val file = write(tempdir(), "Warm.kt", "")
            themeChoiceOf(file.path) shouldBe ThemeChoice.File(file.canonicalFile)
            themeChoiceOf("${file.path}#Plain") shouldBe ThemeChoice.File(file.canonicalFile, "Plain")
        }
    }

    test("ThemeChoices remembers each deck's theme, and forgets theme files that are gone") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", "")
        val theme = write(directory, "themes/Warm.kt", "")
        val choices = ThemeChoices(MemoryPreferences())
        choices.get(deck) shouldBe ThemeChoice.Deck
        listOf(ThemeChoice.BuiltIn(BuiltInTheme.Dark), ThemeChoice.File(theme), ThemeChoice.File(theme, "Plain"), ThemeChoice.Deck).forEach {
            choices.set(deck, it)
            choices.get(deck) shouldBe it
        }
        choices.set(deck, ThemeChoice.File(theme))
        theme.delete()
        choices.get(deck) shouldBe ThemeChoice.Deck
    }

    test("themeFiles lists the deck's themes folder, by name") {
        val directory = tempdir()
        listOf("themes/Warm.kt", "themes/Cool.kt", "themes/notes.txt", "Slides.kt").forEach { write(directory, it, "") }
        themeFiles(directory).map { it.name } shouldContainExactly listOf("Cool.kt", "Warm.kt")
    }
})
