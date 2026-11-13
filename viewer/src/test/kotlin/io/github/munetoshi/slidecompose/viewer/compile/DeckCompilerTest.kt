package io.github.munetoshi.slidecompose.viewer.compile

import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.viewer.BROKEN
import io.github.munetoshi.slidecompose.viewer.TWO_SLIDES
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class DeckCompilerTest : FunSpec({
    // One compiler for all, as in the app: the first compile is the slow one.
    val compiler = DeckCompiler()

    test("compiles and loads a deck, rendering each frame to check it") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", TWO_SLIDES)
        val result = compiler.compileAndLoad(deckSources(deck), assetDirectory = directory).shouldBeInstanceOf<LoadResult.Success>()
        result.presentation.title shouldBe "Test"
        result.presentation.slides.size shouldBe 2
    }

    test("shows a loaded deck in another theme without compiling it again") {
        val directory = tempdir()
        val loaded = compiler.compileAndLoad(listOf(write(directory, "Slides.kt", TWO_SLIDES))).shouldBeInstanceOf<LoadResult.Success>()
        val themed = compiler.applyTheme(loaded.deck, SlideTheme.Dark).shouldBeInstanceOf<LoadResult.Success>()
        themed.presentation.theme shouldBe SlideTheme.Dark
        themed.presentation.slides shouldBe loaded.deck.slides
    }

    test("reports compile errors with their place and line") {
        val deck = write(tempdir(), "Slides.kt", BROKEN)
        val error = compiler.compileAndLoad(listOf(deck)).shouldBeInstanceOf<LoadResult.Failure>().diagnostics.first { it.isError }
        error.message shouldContain "missing"
        File(error.file!!).name shouldBe "Slides.kt"
        error.line shouldBe 5
        error.lineContent!!.trim() shouldBe "slide { TitleSlide(missing) }"
        error.toString() shouldContain "Slides.kt:5:"
    }

    test("asks for a deck when the code declares none") {
        val file = write(tempdir(), "Slides.kt", "val answer = 42\n")
        val failure = compiler.compileAndLoad(listOf(file)).shouldBeInstanceOf<LoadResult.Failure>()
        failure.diagnostics.single().message shouldContain "No presentation found"
        compiler.compileAndLoad(emptyList()).shouldBeInstanceOf<LoadResult.Failure>().diagnostics.single().message shouldBe "No .kt files found"
    }

    test("loads a theme file's main theme first, then its presets in the order of the file") {
        val file = write(
            tempdir(),
            "Warm.kt",
            """
            import io.github.munetoshi.slidecompose.deck.theme.*

            val Plain = SlideTheme.Light
            val theme = SlideTheme.Dark
            val Contrast = SlideTheme.Dark.copy(isDark = true)
            """.trimIndent(),
        )
        val themes = compiler.compileTheme(file).shouldBeInstanceOf<ThemeResult.Success>().themes
        themes.map { it.name } shouldContainExactly listOf("theme", "Plain", "Contrast")
        themes.first().theme shouldBe SlideTheme.Dark
    }

    test("deckSources takes the deck's Kotlin files, but its themes and build output") {
        val directory = tempdir()
        listOf("Slides.kt", "parts/Intro.kt", "themes/Warm.kt", "build/Generated.kt", ".hidden/Old.kt", "notes.txt")
            .forEach { write(directory, it, "") }
        deckSources(directory).map { it.relativeTo(directory).path } shouldContainExactly listOf("Slides.kt", "parts/Intro.kt")
        deckSources(File(directory, "Slides.kt")).map { it.name } shouldContainExactly listOf("Slides.kt")
    }

    test("deckLibraries takes the jars in libs of a folder deck, by name") {
        val directory = tempdir()
        listOf("libs/b.jar", "libs/a.jar", "libs/readme.txt").forEach { write(directory, it, "") }
        deckLibraries(directory).map { it.name } shouldContainExactly listOf("a.jar", "b.jar")
    }

    test("a deck of one file uses no jars, even with libs next to it") {
        val directory = tempdir()
        write(directory, "libs/a.jar", "")
        deckLibraries(write(directory, "Slides.kt", TWO_SLIDES)).shouldBeEmpty()
    }

    test("a deck of one file with jars in libs next to it gets a note on how to use them") {
        val directory = tempdir()
        val deck = write(directory, "Slides.kt", BROKEN)
        unusedLibrariesNote(deck) shouldBe null
        write(directory, "libs/a.jar", "")
        val note = unusedLibrariesNote(deck).shouldNotBeNull()
        note.isError shouldBe false
        note.message shouldBe "libs/ is used only by a deck opened as a folder: open the folder ${directory.name} to use its jars"
        // A folder deck uses them already.
        unusedLibrariesNote(directory) shouldBe null
    }

    test("a jar in libs with a class named as one of the deck's does not run in its place") {
        val directory = tempdir()
        javaJar(File(directory, "libs/impostor.jar"), "TalkKt", IMPOSTOR)
        write(directory, "Talk.kt", TWO_SLIDES)
        val result = compiler.compileAndLoad(deckSources(directory), directory, deckLibraries(directory))
        result.shouldBeInstanceOf<LoadResult.Success>().presentation.title shouldBe "Test"
    }

    test("a deck compiled again with the same libraries shares their classes, and what they keep, until a jar changes") {
        val directory = tempdir()
        val jar = javaJar(File(directory, "libs/counter.jar"), "Counter", COUNTER)
        val deck = write(directory, "Slides.kt", COUNTING)
        fun title() = compiler.compileAndLoad(listOf(deck), libraries = listOf(jar)).shouldBeInstanceOf<LoadResult.Success>().presentation.title
        title() shouldBe "1"
        title() shouldBe "2"
        jar.setLastModified(jar.lastModified() + 2_000)
        title() shouldBe "1"
    }
})

/** A deck whose title is the next number a library's counter gives. */
private const val COUNTING = """
import io.github.munetoshi.slidecompose.deck.*

val deck = presentation(title = "${'$'}{Counter.next()}") {
    slide { }
}
"""

/** A library's class that counts: the numbers it gives go on while its class stays loaded. */
private const val COUNTER = """
public class Counter {
    private static int count;

    public static int next() {
        return ++count;
    }
}
"""

/** A class in a jar named as the class of a deck's `Talk.kt`, with a deck of its own that throws. */
private const val IMPOSTOR = """
import io.github.munetoshi.slidecompose.deck.Presentation;

public class TalkKt {
    public static Presentation getDeck() {
        throw new IllegalStateException("the jar's TalkKt ran");
    }
}
"""

/** Compiles the Java [source] of the class [name], in no package, into [jar], a library of that one class. */
private fun javaJar(jar: File, name: String, source: String): File {
    val classes = Files.createTempDirectory("library").toFile()
    val file = write(classes, "$name.java", source)
    val classPath = System.getProperty("java.class.path")
    ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-classpath", classPath, "-d", classes.path, file.path) shouldBe 0
    jar.parentFile.mkdirs()
    JarOutputStream(jar.outputStream()).use { out ->
        out.putNextEntry(JarEntry("$name.class"))
        File(classes, "$name.class").inputStream().use { it.copyTo(out) }
        out.closeEntry()
    }
    classes.deleteRecursively()
    return jar
}
