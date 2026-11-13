package io.github.munetoshi.slidecompose.viewer.app

import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.viewer.BROKEN
import io.github.munetoshi.slidecompose.viewer.MemoryPreferences
import io.github.munetoshi.slidecompose.viewer.TWO_SLIDES
import io.github.munetoshi.slidecompose.viewer.home.DeckTrust
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeSession
import io.github.munetoshi.slidecompose.viewer.safemode.inProcessSession
import io.github.munetoshi.slidecompose.viewer.theme.BuiltInTheme
import io.github.munetoshi.slidecompose.viewer.theme.ThemeChoice
import io.github.munetoshi.slidecompose.viewer.theme.ThemeChoices
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A theme file with a main theme and one preset. */
private const val WARM = """
import io.github.munetoshi.slidecompose.deck.theme.*

val theme = SlideTheme.Dark
val Plain = SlideTheme.Light
"""

class ViewerTest : FunSpec({
    test("opens a deck only once the user trusts it") {
        val deck = write(tempdir(), "Slides.kt", TWO_SLIDES)
        val preferences = MemoryPreferences()
        val viewer = Viewer(preferences)
        viewer.open(deck)
        viewer.isTrusted shouldBe false
        viewer.recentDecks.shouldBeEmpty()
        viewer.trustDeck()
        viewer.isTrusted shouldBe true
        viewer.recentDecks shouldBe listOf(deck.absoluteFile)
        // Another viewer with the same preferences remembers it.
        Viewer(preferences).apply { open(deck) }.isTrusted shouldBe true
    }

    test("a deck that does not compile opens on a blank slide with its errors, and shows once fixed") {
        val deck = write(tempdir(), "Slides.kt", BROKEN)
        val viewer = Viewer(MemoryPreferences())
        viewer.open(deck)
        viewer.trustDeck()
        coroutineScope {
            val watching = launch { viewer.watch(deck) }
            val blank = eventually(60.seconds) { viewer.controller.shouldNotBeNull() }
            viewer.hasLoadedDeck shouldBe false
            viewer.errors.shouldNotBeEmpty()
            viewer.showErrors shouldBe true
            blank.presentation.slides.size shouldBe 1

            write(deck.parentFile, deck.name, TWO_SLIDES)
            eventually(60.seconds) { viewer.hasLoadedDeck shouldBe true }
            viewer.errors.shouldBeEmpty()
            // A new controller, so that the windows warm up the deck's transitions.
            val loaded = viewer.controller.shouldNotBeNull()
            loaded shouldNotBeSameInstanceAs blank
            loaded.presentation.slides.size shouldBe 2

            write(deck.parentFile, deck.name, BROKEN)
            eventually(60.seconds) { viewer.errors.shouldNotBeEmpty() }
            // The last version that worked stays.
            viewer.hasLoadedDeck shouldBe true
            viewer.controller shouldBe loaded
            loaded.presentation.slides.size shouldBe 2
            watching.cancel()
        }
    }

    test("a deck of one file that fails with jars in libs next to it says that only folders use them") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", BROKEN)
        write(directory, "libs/a.jar", "")
        val viewer = Viewer(MemoryPreferences())
        viewer.open(deck)
        viewer.trustDeck()
        coroutineScope {
            val watching = launch { viewer.watch(deck) }
            eventually(60.seconds) { viewer.errors.shouldNotBeEmpty() }
            viewer.errors.map { it.message } shouldContain
                "libs/ is used only by a deck opened as a folder: open the folder ${directory.name} to use its jars"
            watching.cancel()
        }
    }

    test("the themes next to a deck of one file are listed, but compile only once trusted") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", TWO_SLIDES)
        val theme = write(directory, "themes/Warm.kt", WARM).canonicalFile
        val viewer = Viewer(MemoryPreferences())
        viewer.open(deck)
        viewer.trustDeck()
        coroutineScope {
            val watching = launch { viewer.watch(deck) }
            eventually(60.seconds) { viewer.hasLoadedDeck shouldBe true }
            viewer.menuThemeFiles() shouldBe listOf(theme)
            // Time for the watch to compile the theme, as it would were it trusted.
            delay(3_000)
            viewer.themeNames[theme] shouldBe null

            viewer.chooseTheme(ThemeChoice.File(theme))
            viewer.untrustedTheme shouldBe theme
            viewer.trustTheme()
            eventually(60.seconds) { viewer.themeNames[theme] shouldBe listOf("theme", "Plain") }
            watching.cancel()
        }
    }

    test("the themes of a folder deck compile with it, trusted with the folder") {
        val directory = tempdir()
        write(directory, "Slides.kt", TWO_SLIDES)
        val theme = write(directory, "themes/Warm.kt", WARM).canonicalFile
        val viewer = Viewer(MemoryPreferences())
        viewer.open(directory)
        viewer.trustDeck()
        coroutineScope {
            val watching = launch { viewer.watch(directory) }
            eventually(60.seconds) { viewer.themeNames[theme] shouldBe listOf("theme", "Plain") }
            watching.cancel()
        }
    }

    test("a remembered theme file the user does not trust gives way to the deck's own theme") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", TWO_SLIDES)
        val theme = write(directory, "themes/Warm.kt", WARM).canonicalFile
        val preferences = MemoryPreferences()
        ThemeChoices(preferences.node("themes")).set(deck, ThemeChoice.File(theme))
        Viewer(preferences).apply { open(deck) }.themeChoice shouldBe ThemeChoice.Deck
        DeckTrust(preferences.node("trustedDecks")).add(theme)
        Viewer(preferences).apply { open(deck) }.themeChoice shouldBe ThemeChoice.File(theme)
    }

    test("only a .kt file opens in the editor, links followed; anything else shows in Finder") {
        val directory = tempdir()
        val kotlin = write(directory, "Slides.kt", TWO_SLIDES).canonicalFile
        val command = write(directory, "x.command", "#!/bin/sh\n")
        editorFileOf(kotlin) shouldBe kotlin
        editorFileOf(command) shouldBe null
        val toCommand = Files.createSymbolicLink(File(directory, "linked/x.kt").apply { parentFile.mkdirs() }.toPath(), command.toPath()).toFile()
        editorFileOf(toCommand) shouldBe null
        val toKotlin = Files.createSymbolicLink(File(directory, "other/x.kt").apply { parentFile.mkdirs() }.toPath(), kotlin.toPath()).toFile()
        editorFileOf(toKotlin) shouldBe kotlin

        val folder = tempdir()
        editorFileOf(folder) shouldBe null
        val intro = write(folder, "Intro.kt", "").canonicalFile
        write(folder, "Slides.kt", "")
        editorFileOf(folder) shouldBe intro
    }

    test("blankDeck is one slide, in the chosen built-in theme") {
        blankDeck(ThemeChoice.BuiltIn(BuiltInTheme.Dark)).apply {
            slides.size shouldBe 1
            theme shouldBe SlideTheme.Dark
        }
        blankDeck(ThemeChoice.Deck).theme shouldBe SlideTheme.Light
    }

    test("safe mode opens a deck without trusting it, and closing the deck ends its child") {
        val deck = write(tempdir(), "Slides.kt", TWO_SLIDES)
        val preferences = MemoryPreferences()
        val started = mutableListOf<SafeModeSession>()
        val viewer = Viewer(preferences, startSafeMode = { deck, _ -> inProcessSession(deck).also(started::add) })
        viewer.open(deck)
        viewer.openSafeMode()
        val session = viewer.safeMode.shouldNotBeNull()
        started shouldBe listOf(session)
        viewer.isTrusted shouldBe false
        viewer.recentDecks.shouldBeEmpty()
        // Nothing compiled here: the child has the deck.
        viewer.controller shouldBe null
        Viewer(preferences).apply { open(deck) }.isTrusted shouldBe false
        viewer.close()
        viewer.safeMode shouldBe null
        session.isOpen shouldBe false
    }

    test("the files chosen for a deck of one file go to safe mode with it") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", TWO_SLIDES)
        val photo = write(directory, "photo.png", "png")
        val chosen = mutableListOf<List<File>>()
        val viewer = Viewer(MemoryPreferences(), startSafeMode = { file, files -> inProcessSession(file).also { chosen += files } })
        viewer.open(deck)
        viewer.openSafeMode(listOf(photo))
        chosen shouldBe listOf(listOf(photo))
        viewer.close()
    }

    test("opening another deck ends safe mode") {
        val directory = tempdir()
        val viewer = Viewer(MemoryPreferences(), startSafeMode = { deck, _ -> inProcessSession(deck) })
        viewer.open(write(directory, "One.kt", TWO_SLIDES))
        viewer.openSafeMode()
        val session = viewer.safeMode.shouldNotBeNull()
        viewer.open(write(directory, "other/Two.kt", TWO_SLIDES))
        viewer.safeMode shouldBe null
        session.isOpen shouldBe false
    }
})
