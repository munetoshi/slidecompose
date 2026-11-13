package io.github.munetoshi.slidecompose.viewer.compile

import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.components.SourceDirectoryProvider
import io.github.munetoshi.slidecompose.deck.prepare
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.player.export.FrameCheckException
import io.github.munetoshi.slidecompose.player.export.checkFrames
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

data class Diagnostic(
    val isError: Boolean,
    val message: String,
    val file: String? = null,
    val line: Int = 0,
    val column: Int = 0,
    val lineContent: String? = null,
) {
    override fun toString(): String = buildString {
        if (file != null) append(File(file).name + (if (line > 0) ":$line:$column" else "") + ": ")
        append(if (isError) "error: " else "warning: ")
        append(message)
    }
}

sealed interface LoadResult {
    data class Success(
        /** The deck, in the theme it was loaded with. */
        val presentation: Presentation,
        /** The deck as its code declares it, in its own theme. */
        val deck: Presentation,
        val warnings: List<Diagnostic>,
        val millis: Long,
    ) : LoadResult

    data class Failure(val diagnostics: List<Diagnostic>) : LoadResult
}

/** A theme declared in a theme file, under the name of its property or function. */
data class NamedTheme(val name: String, val theme: SlideTheme)

sealed interface ThemeResult {
    /** The file's themes, its main one first: see [DeckCompiler.compileTheme]. */
    data class Success(val themes: List<NamedTheme>, val warnings: List<Diagnostic>) : ThemeResult

    data class Failure(val diagnostics: List<Diagnostic>) : ThemeResult
}

/**
 * Compiles Kotlin source files that use Compose and SlideCompose, in process, with the same
 * libraries this app runs with, and loads the [Presentation] or [SlideTheme] they declare.
 */
class DeckCompiler {
    private val outputRoot: File = Files.createTempDirectory("slidecompose-build-").toFile()
    private var deckGeneration = 0
    private var themeGeneration = 0

    init {
        Runtime.getRuntime().addShutdownHook(Thread { outputRoot.deleteRecursively() })
    }

    /** Compiler, Compose compiler plugin and their dependencies need not be visible to decks. */
    private val compileClasspath: List<String> by lazy(::deckClasspath)

    private val composePluginJar: String by lazy {
        val registrar = Class.forName("androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar")
        File(registrar.protectionDomain.codeSource.location.toURI()).absolutePath
    }

    /**
     * @param assetDirectory the deck's folder, which relative paths in it are resolved against. If
     *   non-null, every frame is rendered once off-screen before the presentation is returned, so
     *   that slides throwing at runtime are reported as errors instead of taking down the windows
     *   showing them.
     * @param libraries extra jars the deck compiles against and loads.
     * @param theme a theme to show the deck in instead of its own.
     */
    fun compileAndLoad(
        sources: List<File>,
        assetDirectory: File? = null,
        libraries: List<File> = emptyList(),
        theme: SlideTheme? = null,
    ): LoadResult {
        if (sources.isEmpty()) {
            return LoadResult.Failure(listOf(Diagnostic(true, "No .kt files found")))
        }
        val started = System.currentTimeMillis()
        val output = File(outputRoot, "deck-${deckGeneration++}").apply { mkdirs() }
        // Keep the previous generation: classes of the presentation on screen may load lazily.
        File(outputRoot, "deck-${deckGeneration - 3}").deleteRecursively()
        val compiled = compile(sources, libraries, output, "deck$deckGeneration")
        if (compiled is Compiled.Failure) return LoadResult.Failure(compiled.diagnostics)
        return try {
            val loader = classLoader(output, libraries, sourceDirectory = assetDirectory ?: sources.first().absoluteFile.parentFile)
            val entries = entries(output, loader, Presentation::class.java)
            val entry = entries.firstOrNull { it.name == "getDeck" || it.name == "deck" }
                ?: entries.firstOrNull()
                ?: throw DeckException(
                    "No presentation found. Declare a top-level `val deck = presentation { ... }` " +
                        "(or any public top-level property or function returning Presentation).",
                )
            val deck = valueOf(entry, Presentation::class.java)
            val presentation = if (theme == null) deck else deck.withTheme(theme)
            check(presentation, assetDirectory)
            LoadResult.Success(presentation, deck, (compiled as Compiled.Success).warnings, System.currentTimeMillis() - started)
        } catch (e: DeckException) {
            LoadResult.Failure(listOf(Diagnostic(true, e.message.orEmpty())))
        }
    }

    /** Shows a loaded [deck] in another [theme], or in its own for `null`, checking it as [compileAndLoad] does. */
    fun applyTheme(deck: Presentation, theme: SlideTheme?, assetDirectory: File? = null): LoadResult {
        val started = System.currentTimeMillis()
        return try {
            val presentation = if (theme == null) deck else deck.withTheme(theme)
            check(presentation, assetDirectory)
            LoadResult.Success(presentation, deck, emptyList(), System.currentTimeMillis() - started)
        } catch (e: DeckException) {
            LoadResult.Failure(listOf(Diagnostic(true, e.message.orEmpty())))
        }
    }

    /**
     * Compiles a theme file on its own and loads the [SlideTheme]s it declares: each public
     * top-level property or function that returns one. The one named `theme` is the file's main
     * theme and comes first; the others are its presets, in the order of the file. Paths given to
     * [themeFont] in it are resolved against its folder.
     */
    fun compileTheme(source: File, libraries: List<File> = emptyList()): ThemeResult {
        // Theme outputs are kept: the classes of a theme on screen may load lazily, and there are few.
        val output = File(outputRoot, "theme-${themeGeneration++}").apply { mkdirs() }
        val compiled = compile(listOf(source), libraries, output, "theme$themeGeneration")
        if (compiled is Compiled.Failure) return ThemeResult.Failure(compiled.diagnostics)
        return try {
            val loader = classLoader(output, libraries, sourceDirectory = source.absoluteFile.parentFile)
            val text = source.readText()
            val themes = entries(output, loader, SlideTheme::class.java)
                .asSequence()
                .map { entry -> declaredName(entry, text) to entry }
                .sortedWith(compareBy({ (name, _) -> name != "theme" }, { (name, _) -> positionOf(name, text) }))
                .map { (name, entry) -> NamedTheme(name, valueOf(entry, SlideTheme::class.java)) }
                .toList()
            if (themes.isEmpty()) {
                throw DeckException(
                    "No theme found in ${source.name}. Declare a top-level `val theme = SlideTheme(...)`, " +
                        "e.g. `val theme = SlideTheme.Dark.copy(primary = Color(0xFFFF7043))`.",
                )
            }
            ThemeResult.Success(themes, (compiled as Compiled.Success).warnings)
        } catch (e: DeckException) {
            ThemeResult.Failure(listOf(Diagnostic(true, e.message.orEmpty(), file = source.path)))
        }
    }

    private sealed interface Compiled {
        class Success(val warnings: List<Diagnostic>) : Compiled

        class Failure(val diagnostics: List<Diagnostic>) : Compiled
    }

    private fun compile(sources: List<File>, libraries: List<File>, output: File, moduleName: String): Compiled {
        val collector = Collector()
        val arguments = K2JVMCompilerArguments()
        parseCommandLineArguments(
            listOf(
                "-classpath", (compileClasspath + libraries.map { it.absolutePath }).joinToString(File.pathSeparator),
                "-d", output.absolutePath,
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target", "17",
                "-module-name", moduleName,
                "-Xplugin=$composePluginJar",
                "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            ) + sources.map { it.absolutePath },
            arguments,
            false,
        )
        val exitCode = try {
            K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        } catch (e: Throwable) {
            return Compiled.Failure(listOf(Diagnostic(true, "Compiler crashed: $e")))
        }
        val errors = collector.diagnostics.filter { it.isError }
        if (exitCode != ExitCode.OK || errors.isNotEmpty()) {
            return Compiled.Failure(errors.ifEmpty { listOf(Diagnostic(true, "Compilation failed ($exitCode)")) })
        }
        return Compiled.Success(collector.diagnostics)
    }

    /** Renders every frame once if [assetDirectory] is given (see [compileAndLoad]), then prepares the presentation. */
    private fun check(presentation: Presentation, assetDirectory: File?) {
        if (assetDirectory != null) preflight(presentation, assetDirectory)
        presentation.prepare()
    }

    private fun preflight(presentation: Presentation, assetDirectory: File) {
        try {
            presentation.checkFrames(assetDirectory = assetDirectory)
        } catch (e: FrameCheckException) {
            val frame = e.frame
            val cause = e.cause ?: e
            val where = "slide ${frame.slide + 1}" + if (presentation.slides[frame.slide].steps > 1) ", step ${frame.step}" else ""
            throw DeckException("Rendering $where threw $cause\n" + cause.stackTraceSummary())
        }
    }

    private fun classLoader(output: File, libraries: List<File>, sourceDirectory: File): ClassLoader =
        SourceClassLoader(arrayOf(output.toURI().toURL()), libraryLoaders.of(libraries), sourceDirectory)

    private val libraryLoaders = LibraryLoaders(Presentation::class.java.classLoader)

    /** The getters of the public top-level properties in [output], and its functions without parameters, that return a [type]. */
    private fun entries(output: File, loader: ClassLoader, type: Class<*>): List<Method> =
        output.walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name }
            .map { it.relativeTo(output).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .sorted()
            .mapNotNull { classNamed(it, loader) }
            .flatMap { entriesIn(it, type) }
            .toList()

    private fun classNamed(name: String, loader: ClassLoader): Class<*>? = runCatching { Class.forName(name, false, loader) }.getOrNull()

    /** The public static methods of [declaring] without parameters that return a [type]: top-level getters and functions. */
    private fun entriesIn(declaring: Class<*>, type: Class<*>): Sequence<Method> =
        declaring.declaredMethods.asSequence().filter {
            Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && type.isAssignableFrom(it.returnType)
        }

    private fun <T> valueOf(entry: Method, type: Class<T>): T =
        try {
            type.cast(entry.invoke(null))
        } catch (e: Throwable) {
            // The code threw, maybe in a top-level property's initializer as its file was loaded.
            val cause = generateSequence(e) { it.cause }
                .firstOrNull { it !is InvocationTargetException && it !is ExceptionInInitializerError }
                ?: e
            throw DeckException("${entry.declaringClass.simpleName}.${entry.name}() threw $cause\n" + cause.stackTraceSummary())
        }

    /**
     * Loads a deck's or theme's classes, and tells [themeFont] which folder they came from. It loads
     * the classes of its own output before asking its [parent], the libraries' loader: a jar in
     * `libs` with a class of the same name as one of the deck's, such as `TalkKt` for `Talk.kt`,
     * would otherwise run in its place. Every other class comes from the parent first, as usual.
     */
    private class SourceClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
        override val sourceDirectory: File,
    ) : URLClassLoader(urls, parent), SourceDirectoryProvider {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                val own = findLoadedClass(name) ?: ownClass(name) ?: return super.loadClass(name, resolve)
                if (resolve) resolveClass(own)
                return own
            }
        }

        /** The class [name] from this loader's own output, or `null` when the output has none of that name. */
        private fun ownClass(name: String): Class<*>? =
            if (findResource(name.replace('.', '/') + ".class") != null) findClass(name) else null
    }

    private class Collector : MessageCollector {
        val diagnostics = mutableListOf<Diagnostic>()

        override fun clear() = diagnostics.clear()

        override fun hasErrors(): Boolean = diagnostics.any { it.isError }

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            val isError = severity.isError
            if (!isError && severity != CompilerMessageSeverity.WARNING && severity != CompilerMessageSeverity.STRONG_WARNING) {
                return
            }
            diagnostics += Diagnostic(
                isError = isError,
                message = message,
                file = location?.path,
                line = location?.line ?: 0,
                column = location?.column ?: 0,
                lineContent = location?.lineContent,
            )
        }
    }

    private class DeckException(message: String) : Exception(message)

    /**
     * The name [entry] has in the [source]: a function's own, or a property's, which its getter
     * gives with the first letter in upper case.
     */
    private fun declaredName(entry: Method, source: String): String {
        val property = entry.name.removePrefix("get")
        if (property == entry.name || property.isEmpty()) return entry.name
        val lower = property.replaceFirstChar { it.lowercaseChar() }
        return if (positionOf(lower, source) == Int.MAX_VALUE && positionOf(property, source) != Int.MAX_VALUE) property else lower
    }

    /** Where [name] is declared in the [source], to keep the themes of a file in its order. */
    private fun positionOf(name: String, source: String): Int =
        Regex("\\b(val|fun)\\s+${Regex.escape(name)}\\b").find(source)?.range?.first ?: Int.MAX_VALUE

    companion object {
        private val excludedJars = listOf(
            "kotlin-compiler-embeddable",
            "kotlin-compose-compiler-plugin-embeddable",
            "kotlin-daemon-embeddable",
            "kotlin-script-runtime",
            "kotlin-reflect",
            "trove4j",
            "kotlin-build-tools",
        )

        /** The app's classpath without the compiler's own jars. */
        private fun deckClasspath(): List<String> =
            System.getProperty("java.class.path")
                .splitToSequence(File.pathSeparator)
                .filter { it.isNotBlank() && File(it).exists() }
                .filterNot(::isExcludedJar)
                .toList()

        /** Whether the jar at [path] is one of the compiler's own, which decks need not see. */
        private fun isExcludedJar(path: String): Boolean {
            val name = File(path).name
            return excludedJars.any { name.startsWith(it) }
        }
    }
}

/** The frames of the deck's own code, which are the interesting ones. */
private fun Throwable.stackTraceSummary(): String {
    val frames = stackTrace.filterNot(::isLibraryFrame).ifEmpty { stackTrace.toList() }
    return frames.take(6).joinToString("\n") { "    at $it" }
}

/** Packages of the app and the libraries, not of decks. */
private val LibraryPackages = listOf("androidx.", "kotlin.", "java.", "jdk.", "sun.", "io.github.munetoshi.slidecompose.", "org.jetbrains.")

private fun isLibraryFrame(frame: StackTraceElement): Boolean = LibraryPackages.any { frame.className.startsWith(it) }
