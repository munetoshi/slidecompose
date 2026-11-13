package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowPlacement
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.player.presenter.Appearance
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.window.PresentationWindowsState
import io.github.munetoshi.slidecompose.viewer.compile.DeckCompiler
import io.github.munetoshi.slidecompose.viewer.compile.Diagnostic
import io.github.munetoshi.slidecompose.viewer.compile.LoadResult
import io.github.munetoshi.slidecompose.viewer.compile.assetDirectoryOf
import io.github.munetoshi.slidecompose.viewer.compile.deckLibraries
import io.github.munetoshi.slidecompose.viewer.compile.deckSources
import io.github.munetoshi.slidecompose.viewer.compile.unusedLibrariesNote
import io.github.munetoshi.slidecompose.viewer.generated.resources.*
import io.github.munetoshi.slidecompose.viewer.home.DeckTrust
import io.github.munetoshi.slidecompose.viewer.home.DialogTitles
import io.github.munetoshi.slidecompose.viewer.safemode.SafeModeSession
import io.github.munetoshi.slidecompose.viewer.theme.ThemeChoice
import io.github.munetoshi.slidecompose.viewer.theme.ThemeChoices
import io.github.munetoshi.slidecompose.viewer.theme.ThemeLoader
import io.github.munetoshi.slidecompose.viewer.theme.themeFiles
import io.github.munetoshi.slidecompose.viewer.theme.themeFilesStampOf
import io.github.munetoshi.slidecompose.viewer.theme.themeStampOf
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * What the windows show for a deck that has not compiled yet: one blank slide, in the [choice]'s
 * theme when it is a built-in one.
 */
internal fun blankDeck(choice: ThemeChoice): Presentation =
    presentation(theme = (choice as? ThemeChoice.BuiltIn)?.theme?.theme ?: SlideTheme.Light) { slide(steps = 1) {} }

/**
 * The loaded deck, its compile state and the reload loop. It remembers decks, themes and the
 * appearance in [preferences].
 */
@Stable
internal class Viewer(
    private val preferences: Preferences = Preferences.userRoot().node("slidecompose-viewer"),
    /** Starts the sandboxed child that renders a deck in safe mode, with the files chosen to go with a deck of one file. */
    private val startSafeMode: (File, List<File>) -> SafeModeSession = SafeModeSession::spawn,
) {
    private val compiler = DeckCompiler()
    private val compilerDispatcher = Executors.newSingleThreadExecutor(::compilerThread).asCoroutineDispatcher()
    private val trust = DeckTrust(preferences.node("trustedDecks"))
    private val themeChoices = ThemeChoices(preferences.node("themes"))
    private val recent = RecentDecks(preferences)
    private val themes = ThemeLoader(compiler)

    /** The open deck as its code declares it, to show in another theme without compiling it again. */
    private var loadedDeck: Presentation? by mutableStateOf(null)

    /**
     * Whether the deck has compiled since it was opened. Until then, after a compile error, the
     * [controller] shows a blank slide.
     */
    val hasLoadedDeck: Boolean get() = loadedDeck != null

    var deck: File? by mutableStateOf(null)
        private set
    /** Whether the user trusts [deck]: until then, none of its code runs, not even the compiler on it. */
    var isTrusted: Boolean by mutableStateOf(false)
        private set
    /** The child rendering [deck] in safe mode, if it is open so: its code runs sandboxed there, not here. */
    var safeMode: SafeModeSession? by mutableStateOf(null)
        private set
    var controller: PresentationController? by mutableStateOf(null)
        private set
    var isCompiling: Boolean by mutableStateOf(false)
        private set
    var errors: List<Diagnostic> by mutableStateOf(emptyList())
        private set
    var showErrors: Boolean by mutableStateOf(true)
    var reloadRequests: Int by mutableStateOf(0)
    val windows = PresentationWindowsState()

    /** The theme the deck is shown in, remembered for each deck. */
    var themeChoice: ThemeChoice by mutableStateOf(ThemeChoice.Deck)
        private set
    /** The theme files in the deck's `themes` folder. */
    var themeFiles: List<File> by mutableStateOf(emptyList())
        private set
    /** The names of the themes in each theme file in the menu, its main one first, once it has compiled. */
    var themeNames: Map<File, List<String>> by mutableStateOf(emptyMap())
        private set
    /** A theme file from outside the deck that waits for the user to trust it. */
    var untrustedTheme: File? by mutableStateOf(null)
        private set

    /** The decks the user trusted and opened lately, newest first, that still exist. */
    var recentDecks: List<File> by mutableStateOf(recent.existing())
        private set

    private fun addRecent(deck: File) {
        recent.add(deck)
        recentDecks = recent.existing()
    }

    /** Forgets the recent decks. */
    fun clearRecentDecks() {
        recent.clear()
        recentDecks = emptyList()
    }

    fun open(file: File) {
        val target = file.absoluteFile
        if (target != deck) {
            deck = target
            controller = null
            loadedDeck = null
            errors = emptyList()
            closeSafeMode()
            isTrusted = trust.contains(target)
            themeChoice = trustedThemeChoiceOf(target)
            themeFiles = themeFiles(assetDirectoryOf(target))
            themeNames = emptyMap()
            untrustedTheme = null
        }
        if (isTrusted) addRecent(target)
    }

    /**
     * The theme remembered for [deck], or its own instead of a theme file the user does not trust,
     * such as one in the `themes` folder next to a deck of one file: none compiles untrusted.
     */
    private fun trustedThemeChoiceOf(deck: File): ThemeChoice {
        val choice = themeChoices.get(deck)
        return if (choice is ThemeChoice.File && !trust.contains(choice.file)) ThemeChoice.Deck else choice
    }

    /** Remembers that the user trusts the open deck, which then loads. */
    fun trustDeck() {
        val deck = deck ?: return
        trust.add(deck)
        isTrusted = true
        addRecent(deck)
    }

    /**
     * Opens the deck in safe mode, without trusting it: its code runs sandboxed in a child process,
     * not here, which reads a copy of the deck, with the files [chosen] for a deck of one file.
     */
    fun openSafeMode(chosen: List<File> = emptyList()) {
        val deck = deck ?: return
        closeSafeMode()
        safeMode = startSafeMode(deck, chosen)
    }

    /** Asks for the files a deck of one file uses, such as its images, and opens it in safe mode with them. */
    fun chooseFilesForSafeMode(parent: java.awt.Frame?, titles: DialogTitles) {
        val deck = deck ?: return
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val dialog = FileDialog(parent, titles.safeModeFiles, FileDialog.LOAD).apply {
            directory = deck.absoluteFile.parent
            isMultipleMode = true
            isVisible = true
        }
        val chosen = dialog.files.toList()
        if (chosen.isNotEmpty()) openSafeMode(chosen)
    }

    private fun closeSafeMode() {
        safeMode?.close()
        safeMode = null
    }

    /** Closes the deck and goes back to the home window. */
    fun close() {
        deck = null
        controller = null
        loadedDeck = null
        closeSafeMode()
        untrustedTheme = null
        errors = emptyList()
        windows.hidePresenter()
        windows.first.placement = WindowPlacement.Floating
        windows.second.placement = WindowPlacement.Floating
    }

    /** Light or dark colors for the home and presenter windows, remembered across launches. */
    var appearance: Appearance
        get() = windows.appearance
        set(value) {
            windows.appearance = value
            preferences.put("appearance", value.name)
        }

    init {
        preferences.get("appearance", null)?.toAppearance()?.let { windows.appearance = it }
    }

    /**
     * Compiles [deck] now and again whenever one of its source files changes, and shows it in
     * another theme when the [themeChoice] or its theme file changes. Then compiles the theme
     * files in the menu that have changed, or that the user has come to trust, to list their presets.
     */
    suspend fun watch(deck: File) {
        var compiledStamp: String? = null
        var compiledRequest = reloadRequests
        var themeStamp: String? = null
        var themeFilesStamp: String? = null
        while (true) {
            themeFiles = themeFiles(assetDirectoryOf(deck))
            val stamp = stampOf(deck)
            if (stamp != compiledStamp || reloadRequests != compiledRequest) {
                // Editors may save in several writes; wait until the files settle.
                if (compiledStamp != null) delay(150)
                compiledStamp = stampOf(deck)
                compiledRequest = reloadRequests
                themeStamp = themeStampOf(themeChoice)
                compile(deck)
            } else if (themeStampOf(themeChoice) != themeStamp) {
                if (themeChoice is ThemeChoice.File) delay(150)
                themeStamp = themeStampOf(themeChoice)
                applyTheme(deck)
            }
            val files = trustedMenuThemeFiles()
            if (themeFilesStamp != themeFilesStampOf(files)) {
                themeFilesStamp = themeFilesStampOf(files)
                loadThemeNames(deck, files)
            }
            delay(300)
        }
    }

    /** The theme files the menu lists: the deck's own, and the one chosen from elsewhere. */
    fun menuThemeFiles(): List<File> =
        (themeFiles.asSequence() + listOfNotNull((themeChoice as? ThemeChoice.File)?.file)).distinct().toList()

    /**
     * The theme files in the menu that the user trusts, the ones to compile for their presets. A
     * folder deck's themes are in the folder the user trusts; those next to a deck of one file wait
     * until the user chooses one and trusts it.
     */
    private fun trustedMenuThemeFiles(): List<File> = menuThemeFiles().filter(trust::contains)

    private suspend fun loadThemeNames(deck: File, files: List<File>) {
        themeNames = withContext(compilerDispatcher) { presetNamesOf(deck, files) }
    }

    /** The names of the presets in each of [files], theme files of [deck] the user trusts. */
    private fun presetNamesOf(deck: File, files: List<File>): Map<File, List<String>> {
        val libraries = deckLibraries(deck)
        return files.associateWith { presetNamesIn(it, libraries) }
    }

    private fun presetNamesIn(file: File, libraries: List<File>): List<String> = themes.load(file, libraries).themes.map { it.name }

    private suspend fun compile(deck: File) {
        isCompiling = true
        val choice = themeChoice
        val (result, theme) = withContext(compilerDispatcher) {
            val assets = assetDirectoryOf(deck)
            val libraries = deckLibraries(deck)
            val theme = themes.resolve(choice, libraries)
            compiler.compileAndLoad(deckSources(deck), assets, libraries, theme.theme) to theme
        }
        isCompiling = false
        show(deck, result, theme.errors)
    }

    /** Shows the loaded deck in the chosen theme, without compiling the deck again. */
    private suspend fun applyTheme(deck: File) {
        val loaded = loadedDeck ?: return
        isCompiling = true
        val choice = themeChoice
        val (result, theme) = withContext(compilerDispatcher) {
            val assets = assetDirectoryOf(deck)
            val theme = themes.resolve(choice, deckLibraries(deck))
            compiler.applyTheme(loaded, theme.theme, assets) to theme
        }
        isCompiling = false
        show(deck, result, theme.errors)
    }

    private suspend fun show(deck: File, result: LoadResult, themeErrors: List<Diagnostic>) {
        themeErrors.forEach { System.err.println(it) }
        when (result) {
            is LoadResult.Success -> {
                val isFirst = loadedDeck == null
                loadedDeck = result.deck
                errors = themeErrors
                if (themeErrors.isNotEmpty()) showErrors = true
                val current = controller
                val presentation = result.presentation
                // A theme file that does not compile leaves the theme as it was: nothing to redraw.
                if (current != null && current.presentation.slides === presentation.slides && current.presentation.theme == presentation.theme) {
                    return
                }
                // A new controller in place of the blank slide, so that the windows warm up the
                // deck's transitions, as they do for each controller.
                if (current == null || isFirst) {
                    controller = PresentationController(presentation)
                } else {
                    current.replacePresentation(presentation)
                    windows.message = getString(Res.string.reloaded, result.millis)
                }
                println("${if (isFirst) "Loaded" else "Reloaded"} ${deck.name}: ${presentation.slides.size} slides in ${result.millis} ms")
                result.warnings.forEach { System.err.println(it) }
            }
            is LoadResult.Failure -> {
                val diagnostics = result.diagnostics + listOfNotNull(unusedLibrariesNote(deck))
                errors = diagnostics + themeErrors
                showErrors = true
                diagnostics.forEach { System.err.println(it) }
                // The errors show over the slides, as after a reload, and the deck replaces the
                // blank slide once it compiles.
                if (controller == null) controller = PresentationController(blankDeck(themeChoice))
            }
        }
    }

    /** Shows the deck in [choice], first asking about a theme file the user has not trusted yet. */
    fun chooseTheme(choice: ThemeChoice) {
        val deck = deck ?: return
        if (choice is ThemeChoice.File && !trust.contains(choice.file)) {
            untrustedTheme = choice.file
            return
        }
        themeChoice = choice
        themeChoices.set(deck, choice)
    }

    /** Remembers that the user trusts the [untrustedTheme], which then loads. */
    fun trustTheme() {
        val file = untrustedTheme ?: return
        trust.add(file)
        untrustedTheme = null
        chooseTheme(ThemeChoice.File(file))
    }

    fun cancelTheme() {
        untrustedTheme = null
    }

    fun chooseThemeFile(parent: java.awt.Frame?, title: String) {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val dialog = showKotlinFileDialog(parent, title)
        val name = dialog.file ?: return
        chooseTheme(ThemeChoice.File(File(dialog.directory, name).canonicalFile))
    }

    private fun stampOf(deck: File): String =
        (deckSources(deck) + deckLibraries(deck))
            .joinToString("|") { "${it.path}:${it.lastModified()}:${it.length()}" }

    fun chooseDeck(parent: java.awt.Frame?, titles: DialogTitles) {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val dialog = showKotlinFileDialog(parent, titles.openDeck)
        val name = dialog.file ?: return
        open(File(dialog.directory, name))
    }

    fun chooseDeckDirectory(parent: java.awt.Frame?, titles: DialogTitles) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(parent, titles.openFolder, FileDialog.LOAD).apply { isVisible = true }
            val name = dialog.file ?: return
            open(File(dialog.directory, name))
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun createDeck(parent: java.awt.Frame?, titles: DialogTitles) {
        val dialog = FileDialog(parent, titles.newDeck, FileDialog.SAVE).apply {
            file = "Slides.kt"
            isVisible = true
        }
        val name = dialog.file ?: return
        val file = File(dialog.directory, if (name.endsWith(".kt")) name else "$name.kt")
        val template = checkNotNull(Viewer::class.java.getResource("/io/github/munetoshi/slidecompose/viewer/template.kt.txt")).readText()
        file.writeText(template)
        trust.add(file)
        open(file)
        openInEditor(file)
    }

    /**
     * Opens [file], a deck or theme, in the app that edits `.kt` files, or shows it in Finder when
     * it is not one: see [editorFileOf].
     */
    fun openInEditor(file: File? = deck) {
        val target = file ?: return
        val source = editorFileOf(target) ?: return revealInFinder(target)
        runCatching { Desktop.getDesktop().open(source) }
    }

    fun revealInFinder(file: File? = deck) {
        val target = file ?: return
        runCatching { Desktop.getDesktop().browseFileDirectory(target) }
    }
}

/**
 * The `.kt` file to open in an editor for [file], a deck or theme: the file itself, or a folder
 * deck's first source, with links followed. `null` to show [file] in Finder instead. Opening asks
 * macOS for the app that opens such a file, and a deck dropped on the Dock or opened with "Open
 * With" may be any file: a `.command` file would run in Terminal, and a `.app` or `.jar` would
 * start. A link named `x.kt` may lead to any of them, so what counts is the file it leads to.
 */
internal fun editorFileOf(file: File): File? {
    val source = if (file.isDirectory) deckSources(file).firstOrNull() ?: return null else file
    return source.canonicalFile.takeIf { it.isFile && it.extension == "kt" }
}

/** The thread that compiles decks and themes, one at a time. */
private fun compilerThread(task: Runnable): Thread {
    val thread = Thread(task, "deck-compiler")
    thread.isDaemon = true
    return thread
}

private fun String.toAppearance(): Appearance? = Appearance.entries.firstOrNull { it.name == this }

/** Shows a dialog to open a `.kt` file, and returns it once it has closed. */
private fun showKotlinFileDialog(parent: java.awt.Frame?, title: String): FileDialog {
    val dialog = FileDialog(parent, title, FileDialog.LOAD)
    dialog.setFilenameFilter(::isKotlinFile)
    dialog.isVisible = true
    return dialog
}

private fun isKotlinFile(directory: File, name: String): Boolean = name.endsWith(".kt")
