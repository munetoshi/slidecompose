package io.github.munetoshi.slidecompose.viewer.safemode

import java.io.File
import java.util.Locale

/**
 * Launches the untrusted child JVM that compiles and renders a deck, wrapped in a macOS sandbox so
 * the deck's code cannot read the user's files, write files, reach the network, or launch other
 * apps. The child runs the same app (`io.github.munetoshi.slidecompose.viewer.MainKt`) with
 * [CHILD_FLAG] and talks to the parent over its stdio.
 *
 * The sandbox is the boundary, not any in-process check: we assume the deck can call any native API,
 * as Chrome assumes of a renderer. See the profile in [profile].
 */
internal object SafeModeLauncher {
    /** The argument that tells [io.github.munetoshi.slidecompose.viewer.main] to run as the render child, followed by the deck path. */
    const val CHILD_FLAG = "--sandboxed-child"

    private const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"
    private const val MAIN_CLASS = "io.github.munetoshi.slidecompose.viewer.MainKt"

    /** The `java` of the JVM running this app: the bundled runtime when packaged, the toolchain JDK in dev. */
    fun javaExecutable(): File = File(File(System.getProperty("java.home"), "bin"), "java")

    /** Whether safe mode can run here: macOS, with `sandbox-exec` and a `java` to launch. */
    fun isAvailable(): Boolean =
        System.getProperty("os.name").startsWith("Mac") &&
            File(SANDBOX_EXEC).canExecute() &&
            javaExecutable().canExecute()

    /** The classpath to give the child: the same one this app runs with, so it has the compiler and Compose. */
    fun classpath(): String = System.getProperty("java.class.path").orEmpty()

    /** The existing directories and jars on [classpath], which the child must be allowed to read. */
    fun classpathRoots(classpath: String = classpath()): List<File> =
        classpath.splitToSequence(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }.map { it.canonicalFile }.toList()

    /**
     * System properties the child inherits from this app: where the packaged app keeps Skiko's native
     * library and Compose's resources. In the packaged app jpackage sets these; in dev they are unset
     * and the child loads Skiko from the classpath jar instead.
     */
    private val INHERITED_PROPERTIES = listOf("skiko.library.path", "compose.application.resources.dir")

    /**
     * Starts the child for [deck], a copy of the deck in [work] (see [DeckCopy]): the child reads no
     * folder of the user's. [work] is a private directory, the only place the child may write (its
     * temp, the compiler's output, Skiko's native library); the caller creates it and deletes it
     * when the child ends.
     *
     * Paths are canonical: macOS resolves symlinks before the sandbox matches a rule, so an allow for
     * `/var/folders/...` would miss the real `/private/var/folders/...`.
     */
    fun start(deck: File, work: File): Process {
        val java = javaExecutable()
        val classpath = classpath()
        val canonicalWork = work.canonicalFile
        val inherited = INHERITED_PROPERTIES.mapNotNull(String::toSystemProperty).toMap()
        // The child must also be able to read the directories those properties point at (the loose dylib, the resources).
        val inheritedRoots = inherited.values.asSequence().map { File(it).canonicalFile }.filter { it.exists() }
        val readRoots = (classpathRoots(classpath).asSequence() + File(System.getProperty("java.home")).canonicalFile + inheritedRoots)
            .distinct()
            .toList()
        val profile = profile(readRoots, canonicalWork, java)
        val command = command(java, classpath, deck.canonicalFile, canonicalWork, profile, inherited, localeOptions())
        val builder = ProcessBuilder(command)
            .redirectErrorStream(false)
            .directory(canonicalWork)
        // The child inherits nothing of this app's environment, only what [environment] gives it.
        builder.environment().clear()
        builder.environment().putAll(environment(canonicalWork))
        return builder.start()
    }

    /**
     * The child's whole environment. It inherits none of this app's: started from a terminal, the
     * app has the shell's variables, tokens among them, which the deck could read and draw on a
     * slide; and `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS` would change how the
     * child JVM starts. The child itself needs none: the JVM finds its runtime from its own path,
     * and the command line sets its home, temp and locale.
     *
     * HOME and TMPDIR point at [work], as `user.home` and `java.io.tmpdir` do, for code that reads
     * them rather than Java's properties, such as a library a deck brings: it finds the same
     * private folder rather than nothing.
     */
    fun environment(work: File): Map<String, String> = mapOf(
        "HOME" to work.absolutePath,
        "TMPDIR" to work.absolutePath + File.separator,
    )

    /**
     * The JVM options that give the child this app's default locale, whose language a deck may pick
     * its words by. The JVM reads the locale from the user's macOS preferences, which the sandbox
     * keeps from the child, and would otherwise start in `en_001`.
     *
     * Every part of [default] is passed, even empty, since the JVM fills in a part not given from
     * the preferences it cannot read. The [display] and [format] locales are passed only where they
     * differ from [default], as the JVM itself sets them only then.
     */
    fun localeOptions(
        default: Locale = Locale.getDefault(),
        display: Locale = Locale.getDefault(Locale.Category.DISPLAY),
        format: Locale = Locale.getDefault(Locale.Category.FORMAT),
    ): List<String> =
        (
            default.toLocaleOptions(suffix = "") +
                display.takeIf { it != default }.toLocaleOptions(suffix = ".display") +
                format.takeIf { it != default }.toLocaleOptions(suffix = ".format")
            ).toList()

    /** The full command line. [profile] is passed inline with `-p`, so no profile file is written to disk. */
    fun command(
        java: File,
        classpath: String,
        deck: File,
        work: File,
        profile: String,
        inheritedProperties: Map<String, String> = emptyMap(),
        localeOptions: List<String> = emptyList(),
    ): List<String> = buildList {
        add(SANDBOX_EXEC)
        add("-p")
        add(profile)
        add(java.absolutePath)
        add("-Djava.awt.headless=true")
        // The child writes only inside [work]: its temp, the compiler's output and Skiko's native library.
        add("-Djava.io.tmpdir=${work.absolutePath}")
        // Skiko caches its native library under user.home (~/.skiko); point it at the writable work dir,
        // which also gives the deck an isolated, empty home.
        add("-Duser.home=${work.absolutePath}")
        // The parent's locale, which the child cannot read from the user's preferences itself.
        addAll(localeOptions)
        // In the packaged app, where the loose Skiko dylib and Compose resources live.
        inheritedProperties.forEach { (key, value) -> add("-D$key=$value") }
        // hsperfdata would be written under /tmp, which the sandbox forbids.
        add("-XX:-UsePerfData")
        add("-Xmx2g")
        // Skiko and the embedded compiler reach native code; allow it without a warning. We do not pass
        // --sun-misc-unsafe-memory-access here: it only quiets a warning and does not exist before JDK 24,
        // so it would stop an older child JVM from starting. The warning goes to stderr, which we drain.
        add("--enable-native-access=ALL-UNNAMED")
        add("-cp")
        add(classpath)
        add(MAIN_CLASS)
        add(CHILD_FLAG)
        add(deck.absolutePath)
    }

    /**
     * The folders of installed fonts, which the child reads as Skia draws text: the ones installed
     * for all users and the ones installed for this user only, which decks and themes use too. The
     * system's own fonts are under /System, which system.sb allows. Each is also where it leads when
     * it is a link: a rule matches a path after links.
     */
    fun fontDirs(home: File): List<File> =
        sequenceOf(File("/Library/Fonts"), File(home, "Library/Fonts"))
            .flatMap { sequenceOf(it, it.canonicalFile) }
            .distinct()
            .toList()

    /**
     * The sandbox profile. The deck's code reads only the system, the app's code and the JDK,
     * installed fonts and [work]; it writes only to [work]; it cannot reach the network or launch
     * other apps.
     *
     * Reads are a list of what is allowed, not of what is denied, so nothing of the user's is
     * readable unless it is named here:
     * - system.sb, which every profile builds on, allows the operating system: /System (its
     *   libraries, frameworks, the dyld cache and its fonts), /usr/lib, /usr/share (time zones,
     *   ICU), /Library/Apple, /private/var/db/timezone (where /etc/localtime leads), a few files in
     *   /private/etc (passwd, services, protocols), a few of the system's own settings, and the
     *   devices /dev/null, /dev/zero, /dev/random and /dev/urandom.
     * - [readRoots]: the app's code (the jars and folders on its classpath), the JDK, and in the
     *   packaged app Skiko's native library and Compose's resources.
     * - The installed fonts, see [fontDirs].
     * - [work], which holds the copy of the deck: the deck reads its own files there.
     * - The metadata, not the contents or the listing, of each folder above those: the JVM resolves
     *   its own path (`realpath`) as it starts, which takes looking up each folder on the way, and
     *   crashes in `os::init_system_properties_values` when it cannot. This shows that the folders
     *   exist and when they changed, nothing of what is in them.
     *
     * [java] is the one program the child may exec: `sandbox-exec` execs it to start the JVM, and the
     * sandbox checks that exec against this profile. Everything else, including the `jspawnhelper` the
     * JVM would use to run a deck's `ProcessBuilder`, stays denied, so the deck launches nothing.
     */
    fun profile(
        readRoots: List<File>,
        work: File,
        java: File,
        home: File = File(System.getProperty("user.home")).canonicalFile,
    ): String = buildString {
        val fontDirs = fontDirs(home)
        appendLine("(version 1)")
        appendLine("(deny default)")
        // The operating system's files, and the non-file allowances (mach services, sysctl) the JVM needs to start.
        appendLine("(import \"system.sb\")")
        // The app's code and the JDK, the installed fonts.
        appendLine("(allow file-read*")
        (readRoots.asSequence() + fontDirs).forEach { appendLine("    (subpath ${sbpl(it.absolutePath)})") }
        appendLine(")")
        // The folders on the way to those, for the JVM resolving paths: their metadata only, so not their listing.
        appendLine("(allow file-read-metadata")
        (readRoots.asSequence() + fontDirs + work).flatMap(File::toAncestors).distinct().forEach { appendLine("    (literal ${sbpl(it.absolutePath)})") }
        appendLine(")")
        // The only place the child may write: its temp, the compiler's output and Skiko's native
        // library. It also holds the copy of the deck.
        appendLine("(allow file-read* file-write* (subpath ${sbpl(work.absolutePath)}))")
        // The only program the child may exec: the JVM launcher itself (and its resolved path).
        appendLine("(allow process-exec*")
        sequenceOf(java.absolutePath, java.canonicalPath).distinct().forEach { appendLine("    (literal ${sbpl(it)})") }
        appendLine(")")
        // Belt and braces: close the holes that matter even if system.sb opened any of them.
        appendLine("(deny network*)")
        // The hole found in testing: a deck calling LaunchServices through Java's FFM launched another
        // app, which would run outside the sandbox. Denying this service stops it.
        appendLine("(deny mach-lookup (global-name \"com.apple.runningboard\"))")
    }

    /** An SBPL string literal: double quotes, with backslashes and quotes escaped. */
    private fun sbpl(path: String): String = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** This system property's name and its value in this process, or null when it is not set. */
private fun String.toSystemProperty(): Pair<String, String>? {
    val value = System.getProperty(this) ?: return null
    return this to value
}

/** The folders above this file, nearest first, up to and including the root. */
private fun File.toAncestors(): Sequence<File> = generateSequence(absoluteFile.parentFile) { it.parentFile }

/**
 * The JVM options that set this locale, as `-Duser.language` and the like, each name followed by
 * [suffix] (`.display`, `.format`, or nothing for the default locale). None for no locale.
 */
private fun Locale?.toLocaleOptions(suffix: String): Sequence<String> {
    if (this == null) return emptySequence()
    return sequenceOf("language" to language, "script" to script, "country" to country, "variant" to variant)
        .map { (name, value) -> "-Duser.$name$suffix=$value" }
}
