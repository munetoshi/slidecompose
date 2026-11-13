package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/** The paths of the files and folders under [folder], from it, sorted. */
private fun contentsOf(folder: File): List<String> =
    folder.walkTopDown().drop(1).map { it.relativeTo(folder).path }.sorted().toList()

class DeckCopyTest : FunSpec({
    test("a folder is copied with its files and folders, but hidden ones") {
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", "// slides")
        write(deck, "images/photo.png", "png")
        write(deck, "libs/extra.jar", "jar")
        write(deck, ".git/config", "secret")
        write(deck, ".DS_Store", "x")
        val into = File(tempdir(), "copy")
        val copy = copyDeck(deck, into).shouldBeInstanceOf<DeckCopy.Copied>()
        copy.deck shouldBe File(into, "Talk")
        contentsOf(copy.deck) shouldBe listOf("Slides.kt", "images", "images/photo.png", "libs", "libs/extra.jar")
        File(copy.deck, "images/photo.png").readText() shouldBe "png"
    }

    test("a deck of one file is copied alone, without the files beside it") {
        val folder = tempdir()
        val deck = write(folder, "Slides.kt", "// slides")
        write(folder, "photo.png", "png")
        val into = File(tempdir(), "copy")
        val copy = copyDeck(deck, into).shouldBeInstanceOf<DeckCopy.Copied>()
        copy.deck shouldBe File(into, "Slides.kt")
        contentsOf(into) shouldBe listOf("Slides.kt")
        copy.deck.readText() shouldBe "// slides"
    }

    test("a deck of one file is copied with the files and folders chosen from its folder") {
        val folder = tempdir()
        val deck = write(folder, "Slides.kt", "// slides")
        val photo = write(folder, "photo.png", "png")
        write(folder, "other.png", "png")
        val logo = write(folder, "images/logo.png", "logo")
        write(folder, "images/unused.png", "png")
        write(folder, "fonts/Serif.ttf", "ttf")
        val into = File(tempdir(), "copy")
        val copy = copyDeck(deck, into, chosen = listOf(photo, logo, File(folder, "fonts"))).shouldBeInstanceOf<DeckCopy.Copied>()
        copy.deck shouldBe File(into, "Slides.kt")
        // Where they were beside the deck, so that the deck finds them as before.
        contentsOf(into) shouldBe listOf("Slides.kt", "fonts", "fonts/Serif.ttf", "images", "images/logo.png", "photo.png")
        File(into, "images/logo.png").readText() shouldBe "logo"
    }

    test("a chosen file outside the deck's folder, or the folder itself, is refused") {
        val folder = tempdir()
        val deck = write(folder, "Slides.kt", "// slides")
        val elsewhere = write(tempdir(), "secret.txt", "secret")
        val into = File(tempdir(), "copy")
        copyDeck(deck, into, chosen = listOf(elsewhere)) shouldBe DeckCopy.Outside(elsewhere.canonicalPath)
        copyDeck(deck, into, chosen = listOf(folder)) shouldBe DeckCopy.Outside(folder.canonicalPath)
        into.exists() shouldBe false
    }

    test("a chosen file with another hard link is refused") {
        val folder = tempdir()
        val deck = write(folder, "Slides.kt", "// slides")
        val photo = File(folder, "photo.png").apply { Files.createLink(toPath(), write(tempdir(), "secret.txt", "secret").toPath()) }
        copyDeck(deck, File(tempdir(), "copy"), chosen = listOf(photo)) shouldBe DeckCopy.HardLink("photo.png")
    }

    test("links are left out, and what they lead to is not copied") {
        val elsewhere = tempdir()
        val secret = write(elsewhere, "secret.txt", "secret")
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", "// slides")
        Files.createSymbolicLink(File(deck, "file.txt").toPath(), secret.toPath())
        Files.createSymbolicLink(File(deck, "folder").toPath(), elsewhere.toPath())
        val copy = copyDeck(deck, File(tempdir(), "copy")).shouldBeInstanceOf<DeckCopy.Copied>()
        contentsOf(copy.deck) shouldBe listOf("Slides.kt")
    }

    test("a deck opened through a link is copied from where the link leads") {
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", "// slides")
        val link = File(tempdir(), "Link").apply { Files.createSymbolicLink(toPath(), deck.toPath()) }
        val copy = copyDeck(link, File(tempdir(), "copy")).shouldBeInstanceOf<DeckCopy.Copied>()
        contentsOf(copy.deck) shouldBe listOf("Slides.kt")
    }

    test("a file with another hard link stops the copy, which then makes nothing") {
        val secret = write(tempdir(), "secret.txt", "secret")
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", "// slides")
        Files.createLink(File(deck, "images/innocent.png").apply { parentFile.mkdirs() }.toPath(), secret.toPath())
        val into = File(tempdir(), "copy")
        copyDeck(deck, into) shouldBe DeckCopy.HardLink("Talk/images/innocent.png")
        into.exists() shouldBe false
    }

    test("a deck of one file with another hard link is not copied either") {
        val original = write(tempdir(), "original.kt", "// slides")
        val deck = File(tempdir(), "Slides.kt").apply { Files.createLink(toPath(), original.toPath()) }
        copyDeck(deck, File(tempdir(), "copy")) shouldBe DeckCopy.HardLink("Slides.kt")
    }

    test("a deck with too many files and folders, or too many bytes, is not copied") {
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", "// slides")
        write(deck, "images/photo.png", "0123456789")
        // The folder itself, the deck, the images folder and the photo.
        copyDeck(deck, File(tempdir(), "copy"), maxEntries = 4).shouldBeInstanceOf<DeckCopy.Copied>()
        copyDeck(deck, File(tempdir(), "copy"), maxEntries = 3) shouldBe DeckCopy.TooLarge
        // The deck's 9 bytes and the photo's 10.
        copyDeck(deck, File(tempdir(), "copy"), maxBytes = 19).shouldBeInstanceOf<DeckCopy.Copied>()
        copyDeck(deck, File(tempdir(), "copy"), maxBytes = 18) shouldBe DeckCopy.TooLarge
    }
})
