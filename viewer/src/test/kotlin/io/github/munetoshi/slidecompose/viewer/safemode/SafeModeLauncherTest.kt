package io.github.munetoshi.slidecompose.viewer.safemode

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.Locale

class SafeModeLauncherTest : FunSpec({
    test("the command runs the child JVM through sandbox-exec with an inline profile") {
        val java = File("/runtime/bin/java")
        val deck = File("/decks/Talk.kt")
        val work = File("/work")
        val command = SafeModeLauncher.command(java, "/a.jar:/b.jar", deck, work, profile = "(deny default)")

        command.first() shouldBe "/usr/bin/sandbox-exec"
        command shouldContainInOrder listOf("-p", "(deny default)")
        command shouldContainInOrder listOf("/runtime/bin/java", "-cp", "/a.jar:/b.jar")
        command shouldContainInOrder listOf("io.github.munetoshi.slidecompose.viewer.MainKt", SafeModeLauncher.CHILD_FLAG, "/decks/Talk.kt")
        // The child is headless and writes only inside the work directory.
        command shouldContain "-Djava.awt.headless=true"
        command shouldContain "-Djava.io.tmpdir=/work"
    }

    test("the profile reads only what it lists, writes only to work, and execs only the JVM") {
        val profile = SafeModeLauncher.profile(
            readRoots = listOf(File("/app/lib"), File("/jdk")),
            work = File("/work"),
            java = File("/jdk/bin/java"),
            home = File("/Users/me"),
        )

        profile shouldContain "(deny default)"
        profile shouldContain "(import \"system.sb\")"
        // No read is allowed everywhere: only the app, the JDK, the fonts and the work folder.
        profile.lines().none { it.trim() == "(allow file-read*)" } shouldBe true
        profile shouldContain "(subpath \"/app/lib\")"
        profile shouldContain "(subpath \"/jdk\")"
        profile shouldContain "(subpath \"/Library/Fonts\")"
        profile shouldContain "(subpath \"/Users/me/Library/Fonts\")"
        profile shouldContain "(allow file-read* file-write* (subpath \"/work\"))"
        // Nothing else of home is read; the folders on the way to an allowed path are only looked up.
        profile shouldNotContain "(subpath \"/Users/me\")"
        profile shouldContain "(deny network*)"
        // Only the JVM launcher may be exec'd; the deck can launch nothing else.
        profile shouldContain "(allow process-exec*"
        profile shouldContain "(literal \"/jdk/bin/java\")"
        // The hole closed in testing: launching other apps via LaunchServices.
        profile shouldContain "com.apple.runningboard"
    }

    test("the folders above what the child reads are allowed their metadata only") {
        val profile = SafeModeLauncher.profile(
            readRoots = listOf(File("/Users/me/.gradle/caches/a.jar")),
            work = File("/private/var/folders/xy/work"),
            java = File("/jdk/bin/java"),
            home = File("/Users/me"),
        )
        val metadata = profile.substringAfter("(allow file-read-metadata\n").substringBefore("\n)")
        metadata.lines().map(String::trim) shouldContainAll listOf(
            "(literal \"/\")",
            "(literal \"/Users\")",
            "(literal \"/Users/me\")",
            "(literal \"/Users/me/.gradle\")",
            "(literal \"/Users/me/.gradle/caches\")",
            "(literal \"/Users/me/Library\")",
            "(literal \"/private/var/folders/xy\")",
        )
        // The allowed paths themselves are read through their own rules, and nothing here is a whole folder.
        metadata shouldNotContain "a.jar"
        metadata shouldNotContain "subpath"
    }

    test("a fonts folder that is a link is allowed where it leads too") {
        val home = tempdir()
        val elsewhere = tempdir()
        File(home, "Library").mkdirs()
        Files.createSymbolicLink(File(home, "Library/Fonts").toPath(), elsewhere.toPath())
        val profile = SafeModeLauncher.profile(readRoots = emptyList(), work = File("/work"), java = File("/jdk/bin/java"), home = home)
        profile shouldContain "(subpath \"${File(home, "Library/Fonts").path}\")"
        profile shouldContain "(subpath \"${elsewhere.canonicalPath}\")"
    }

    test("a path with a quote in it is escaped in the profile, not injected") {
        // The quote that would close the SBPL string early is backslash-escaped, so the path stays data.
        val profile = SafeModeLauncher.profile(
            readRoots = listOf(File("""/odd"path""")),
            work = File("/work"),
            java = File("/jdk/bin/java"),
        )
        profile shouldContain """(subpath "/odd\"path")"""
    }

    test("the child's environment is only its home and temp, both the work folder") {
        SafeModeLauncher.environment(File("/work")) shouldBe mapOf("HOME" to "/work", "TMPDIR" to "/work/")
    }

    test("a process started with the child's environment sees nothing of this app's") {
        // As start() builds it, but running env instead of the sandboxed JVM, to list what it sees.
        val builder = ProcessBuilder("/usr/bin/env")
        builder.environment()["JAVA_TOOL_OPTIONS"] = "-Dleaked=true"
        builder.environment().clear()
        builder.environment().putAll(SafeModeLauncher.environment(tempdir()))
        val process = builder.start()
        val names = process.inputStream.bufferedReader().readLines().map { it.substringBefore("=") }
        process.waitFor()
        names shouldContainExactlyInAnyOrder listOf("HOME", "TMPDIR")
    }

    test("the locale options give every part of the default locale, even empty ones") {
        val options = SafeModeLauncher.localeOptions(default = Locale.JAPAN, display = Locale.JAPAN, format = Locale.JAPAN)
        options shouldBe listOf("-Duser.language=ja", "-Duser.script=", "-Duser.country=JP", "-Duser.variant=")
    }

    test("the locale options give the display and format locales where they differ from the default") {
        val chinese = Locale.Builder().setLanguage("zh").setScript("Hant").setRegion("TW").build()
        val options = SafeModeLauncher.localeOptions(default = Locale.JAPAN, display = chinese, format = Locale.US)
        options shouldContainAll listOf(
            "-Duser.language=ja", "-Duser.country=JP",
            "-Duser.language.display=zh", "-Duser.script.display=Hant", "-Duser.country.display=TW",
            "-Duser.language.format=en", "-Duser.country.format=US",
        )
    }

    test("the command passes the locale options to the child JVM") {
        val command = SafeModeLauncher.command(
            File("/jdk/bin/java"), "/a.jar", File("/decks/Talk.kt"), File("/work"), profile = "(deny default)",
            localeOptions = listOf("-Duser.language=ja", "-Duser.country=JP"),
        )
        // JVM options go before the main class, or they would be the deck's arguments.
        command shouldContainInOrder listOf("-Duser.language=ja", "-Duser.country=JP", "io.github.munetoshi.slidecompose.viewer.MainKt")
    }

    test("classpath roots drop blanks and files that no longer exist") {
        val roots = SafeModeLauncher.classpathRoots("/does/not/exist.jar::${System.getProperty("java.home")}")
        roots.any { it.path == "/does/not/exist.jar" } shouldBe false
        roots.isEmpty() shouldBe false
    }
})
