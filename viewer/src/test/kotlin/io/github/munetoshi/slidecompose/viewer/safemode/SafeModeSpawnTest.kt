package io.github.munetoshi.slidecompose.viewer.safemode

import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import org.jetbrains.skia.FontMgr
import kotlin.time.Duration.Companion.seconds

private const val DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Spawn") {
    slide { TitleSlide("One") }
    slide { TitleSlide("Two") }
}
"""

/** A Kotlin string literal for [path], for embedding an absolute path in deck source. */
private fun literal(path: String): String = "\"" + path.replace("\\", "\\\\") + "\""

/**
 * A deck that tries to write [victim] (outside its folder) at load, then reports in its title whether
 * it was allowed. The deck still loads, so "safe" proves the sandbox blocked the write gracefully.
 */
private fun fileWriteDeck(victim: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.io.File
import java.nio.file.Files

private val wrote = try { File(${literal(victim.absolutePath)}).writeText("pwned"); true } catch (e: Throwable) { false }

val deck = presentation(title = if (wrote) "PWNED" else "safe") {
    slide { TitleSlide("x") }
}
"""

/**
 * A deck that tries to read [target] at load and reports in its title whether it could. "safe" means
 * the sandbox blocked the read.
 */
private fun fileReadDeck(target: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.io.File
import java.nio.file.Files

private val read = try { File(${literal(target.absolutePath)}).readText(); true } catch (e: Throwable) { false }

val deck = presentation(title = if (read) "LEAKED" else "safe") {
    slide { TitleSlide("x") }
}
"""

/** A deck that tries to launch a process that would create [victim]. The sandbox must stop it from launching anything. */
private fun processDeck(victim: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = run {
    try { ProcessBuilder("/bin/sh", "-c", "touch '" + ${literal(victim.absolutePath)} + "'").start().waitFor() } catch (e: Throwable) {}
    presentation(title = "x") { slide { TitleSlide("x") } }
}
"""

/**
 * A deck that leaves a link to [target], outside its work folder, in the work folder, as a deck
 * could to make the viewer delete what it points to when it cleans up. Its title says whether it could.
 */
private fun linkDeck(target: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.nio.file.*

private val linked = runCatching { Files.createSymbolicLink(Path.of(System.getProperty("java.io.tmpdir"), "x"), Path.of(${literal(target.absolutePath)})) }.isSuccess

val deck = presentation(title = if (linked) "linked" else "no link") { slide { TitleSlide("x") } }
"""

/**
 * A deck that reads, at load, its files [names] from the folder it was loaded from, as it would
 * its images, and says in its title what it read of each, or "-" when it could not.
 */
private fun assetReadDeck(vararg names: String) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.io.File

private val folder = (object {}.javaClass.classLoader as SourceDirectoryProvider).sourceDirectory
private val read = listOf(${names.joinToString { literal(it) }}).joinToString("|") { runCatching { File(folder, it).readText() }.getOrDefault("-") }

val deck = presentation(title = read) { slide { TitleSlide("x") } }
"""

/** A deck that tries to list [folder] at load and reports in its title whether it could. "safe" means the sandbox blocked it. */
private fun folderListDeck(folder: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.io.File

private val listed = try { File(${literal(folder.absolutePath)}).list() != null } catch (e: Throwable) { false }

val deck = presentation(title = if (listed) "LISTED" else "safe") {
    slide { TitleSlide("x") }
}
"""

/**
 * A deck that says in its title which environment variables it sees, other than the two the
 * launcher gives it and the one CoreFoundation sets in every process as it starts.
 */
private const val ENVIRONMENT_DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

private val given = setOf("HOME", "TMPDIR", "__CF_USER_TEXT_ENCODING")
private val others = System.getenv().keys.filter { it !in given }.sorted().joinToString(",")

val deck = presentation(title = "others:" + others + "|home:" + System.getenv("HOME")) {
    slide { TitleSlide("x") }
}
"""

/** A deck that says in its title its default locale and its locale for formatting. */
private const val LOCALE_DECK = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import java.util.Locale

val deck = presentation(title = Locale.getDefault().toLanguageTag() + "|" + Locale.getDefault(Locale.Category.FORMAT).toLanguageTag()) {
    slide { TitleSlide("x") }
}
"""

/**
 * A deck that loads the font file [font] with Skia, as text set in a font from a file (`themeFont`)
 * is drawn, and says in its title the font's family, or "-" when it could not load it.
 */
private fun fontDeck(font: File) = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*
import org.jetbrains.skia.FontMgr

private val family = runCatching { FontMgr.default.makeFromFile(${literal(font.absolutePath)})?.familyName }.getOrNull() ?: "-"

val deck = presentation(title = family) {
    slide { TitleSlide("x") }
}
"""

/** A font installed for this user only, with the family Skia reads from it here, or null when there is none. */
private fun userFont(): Pair<File, String>? =
    File(System.getProperty("user.home"), "Library/Fonts").listFiles().orEmpty().asSequence()
        .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
        .sortedBy { it.name }
        .mapNotNull(File::toFontFamily)
        .firstOrNull()

/** This font file and its family as Skia reads it, or null when Skia cannot read it. */
private fun File.toFontFamily(): Pair<File, String>? {
    val family = runCatching { FontMgr.default.makeFromFile(absolutePath)?.familyName }.getOrNull() ?: return null
    return this to family
}

/** The child compiles in a cold JVM, under load when the whole suite runs, so be patient. */
private const val SPAWN_MILLIS = 180_000L

/**
 * Spawns the real sandboxed child. macOS only, and only when a `java` to launch is at hand (the
 * toolchain JDK in dev; CI runs on Linux and skips these).
 */
class SafeModeSpawnTest : FunSpec({
    val available = SafeModeLauncher.isAvailable()

    test("a sandboxed child compiles and renders a deck").config(enabled = available) {
        val deck = write(tempdir(), "Slides.kt", DECK)
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            val loaded = session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>()
            loaded.deck.slides.size shouldBe 2
            session.configure(640, 360, 1f)
            session.await(SPAWN_MILLIS) { session.frame != null }
            val frame = session.frame!!
            frame.width shouldBe 640
            frame.pixels.any { it != frame.pixels[0] } shouldBe true
        } finally {
            session.close()
        }
    }

    test("the sandbox denies a deck writing outside its folder").config(enabled = available) {
        // The victim is outside the deck folder and the work directory, so only the sandbox stands between.
        val victim = File(tempdir(), "pwned.txt")
        val deck = write(tempdir(), "Slides.kt", fileWriteDeck(victim))
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            val loaded = session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>()
            // The deck ran (so the sandbox did not break it) but the write was blocked.
            loaded.deck.title shouldBe "safe"
            victim.exists() shouldBe false
        } finally {
            session.close()
        }
    }

    test("the sandbox denies a deck reading the user's documents").config(enabled = available) {
        // A sentinel in the real ~/Documents, readable outside the sandbox, so "safe" means the sandbox blocked it.
        val sentinel = File(File(System.getProperty("user.home"), "Documents"), ".slidecompose_sandbox_test_${System.nanoTime()}")
        val placed = runCatching { sentinel.writeText("secret"); sentinel.canRead() }.getOrDefault(false)
        if (!placed) return@config // Cannot place a sentinel (no Documents, or read-only); skip.
        try {
            val deck = write(tempdir(), "Slides.kt", fileReadDeck(sentinel))
            val session = SafeModeSession.spawn(deck, tempdir())
            try {
                session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
                val loaded = session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>()
                loaded.deck.title shouldBe "safe"
            } finally {
                session.close()
            }
        } finally {
            sentinel.delete()
        }
    }

    test("the sandbox denies a deck reading a file in the user's home outside the usual folders").config(enabled = available) {
        // As ~/.netrc or a shell history would be: in a new folder directly in home, readable outside the sandbox.
        val folder = File(System.getProperty("user.home"), ".slidecompose-sandbox-test-${UUID.randomUUID()}")
        val secret = runCatching { write(folder, "secret", "secret") }.getOrNull()
        if (secret == null || !secret.canRead()) return@config // Cannot write in home; skip.
        try {
            val deck = write(tempdir(), "Slides.kt", fileReadDeck(secret))
            val session = SafeModeSession.spawn(deck, tempdir())
            try {
                session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
                session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "safe"
            } finally {
                session.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    test("the sandbox denies a deck listing the user's home").config(enabled = available) {
        val home = File(System.getProperty("user.home"))
        if (home.list() == null) return@config // Not listable even outside the sandbox; nothing to show.
        val deck = write(tempdir(), "Slides.kt", folderListDeck(home))
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "safe"
        } finally {
            session.close()
        }
    }

    test("a deck sees none of the viewer's environment, and its home is the work folder").config(enabled = available) {
        // This test's own process has PATH and the like, which the child would inherit if it were let.
        System.getenv("PATH") shouldNotBe null
        val work = tempdir()
        val deck = write(tempdir(), "Slides.kt", ENVIRONMENT_DECK)
        val session = SafeModeSession.spawn(deck, work)
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "others:|home:${work.canonicalPath}"
        } finally {
            session.close()
        }
    }

    test("a deck has the viewer's locale, which it cannot read from the preferences itself").config(enabled = available) {
        val deck = write(tempdir(), "Slides.kt", LOCALE_DECK)
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            val expected = Locale.getDefault().toLanguageTag() + "|" + Locale.getDefault(Locale.Category.FORMAT).toLanguageTag()
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe expected
        } finally {
            session.close()
        }
    }

    test("a deck loads a font file installed for this user only").config(enabled = available) {
        // By its file, as themes name fonts. Skia cannot find such a font by its family name in the
        // sandbox (that takes asking the font server, which system.sb does not allow), nor could it before.
        val (font, family) = userFont() ?: return@config // No font installed for this user; skip.
        val deck = write(tempdir(), "Slides.kt", fontDeck(font))
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe family
        } finally {
            session.close()
        }
    }

    test("a deck in a folder reads its own files, but not its hidden files or links").config(enabled = available) {
        // The link leads out of the deck's folder: the copy leaves it out, and the sandbox would not let the child read where it leads either.
        val elsewhere = write(tempdir(), "elsewhere.txt", "elsewhere")
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", assetReadDeck("data.txt", ".hidden.txt", "link.txt"))
        write(deck, "data.txt", "data")
        write(deck, ".hidden.txt", "hidden")
        Files.createSymbolicLink(File(deck, "link.txt").toPath(), elsewhere.toPath())
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "data|-|-"
        } finally {
            session.close()
        }
    }

    test("a deck of one file in the user's documents cannot read the files beside it").config(enabled = available) {
        // As a deck downloaded on its own sits among the other downloads, in the real ~/Documents: only
        // the deck is copied to the work folder, and the sandbox lets the child read nothing beside the copy.
        val folder = File(File(System.getProperty("user.home"), "Documents"), ".slidecompose_sandbox_test_${System.nanoTime()}")
        val beside = runCatching { write(folder, "beside.txt", "beside") }.getOrNull()
        if (beside == null) return@config // Cannot write there (no Documents, or read-only); skip.
        try {
            val deck = write(folder, "Slides.kt", fileReadDeck(beside))
            val session = SafeModeSession.spawn(deck, tempdir())
            try {
                session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
                session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "safe"
            } finally {
                session.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    test("a deck of one file reads the files chosen with it, and no others").config(enabled = available) {
        val folder = tempdir()
        val deck = write(folder, "Slides.kt", assetReadDeck("data.txt", "other.txt"))
        val data = write(folder, "data.txt", "data")
        write(folder, "other.txt", "other")
        val session = SafeModeSession.spawn(deck, tempdir(), chosen = listOf(data))
        try {
            session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
            session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "data|-"
        } finally {
            session.close()
        }
    }

    test("a deck with a hard link in its folder is not opened, and its work folder goes").config(enabled = available) {
        val secret = write(tempdir(), "secret.txt", "secret")
        val deck = tempdir().resolve("Talk")
        write(deck, "Slides.kt", DECK)
        Files.createLink(File(deck, "photo.png").toPath(), secret.toPath())
        val work = tempdir()
        val session = SafeModeSession.spawn(deck, work)
        session.await(SPAWN_MILLIS) { session.status != SafeModeSession.Status.Loading }
        session.status shouldBe SafeModeSession.Status.Refused(DeckCopy.HardLink("Talk/photo.png"))
        work.exists() shouldBe false
        session.close()
    }

    test("closing the session removes the work folder, but nothing a link in it points to").config(enabled = available) {
        val canary = tempdir().apply { File(this, "precious.txt").writeText("keep") }
        val work = tempdir()
        val deck = write(tempdir(), "Slides.kt", linkDeck(canary))
        val session = SafeModeSession.spawn(deck, work)
        session.await(SPAWN_MILLIS) { session.status is SafeModeSession.Status.Loaded }
        // The child could make the link: only the viewer's cleaning up stands between it and the canary.
        session.status.shouldBeInstanceOf<SafeModeSession.Status.Loaded>().deck.title shouldBe "linked"
        session.close()
        eventually(30.seconds) { work.exists() shouldBe false }
        File(canary, "precious.txt").readText() shouldBe "keep"
    }

    test("the sandbox denies a deck launching a process").config(enabled = available) {
        val victim = File(tempdir(), "launched.txt")
        val deck = write(tempdir(), "Slides.kt", processDeck(victim))
        val session = SafeModeSession.spawn(deck, tempdir())
        try {
            // A denied launch may crash the child, so we only require it to stop running, then check the result.
            session.await(SPAWN_MILLIS) { session.status !is SafeModeSession.Status.Loading }
            Thread.sleep(500)
            // Whatever happened to the child, no process ran: the file was never created.
            victim.exists() shouldBe false
        } finally {
            session.close()
        }
    }
})
