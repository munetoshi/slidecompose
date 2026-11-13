package io.github.munetoshi.slidecompose.deck

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.github.munetoshi.slidecompose.deck.components.Reveal
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideTheme
import io.github.munetoshi.slidecompose.deck.theme.SlideStyle
import io.github.munetoshi.slidecompose.deck.theme.SlideStyleBuilder
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.deck.transition.SlideTransition
import io.github.munetoshi.slidecompose.deck.transition.discoverElementIds
import io.github.munetoshi.slidecompose.deck.transition.discoverSteps
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The logical size of a slide. All slide content is laid out in this coordinate space
 * (1 dp == 1 unit), and is scaled to fit whatever window or page it is rendered into.
 */
@Immutable
data class SlideSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Slide size must be positive: ${width}x$height" }
    }

    val aspectRatio: Float get() = width.toFloat() / height

    companion object {
        /** 16:9, 1920x1080. */
        val Widescreen = SlideSize(1920, 1080)

        /** 4:3, 1440x1080. */
        val Standard = SlideSize(1440, 1080)
    }
}

/** What a slide's content can see about where it is in the presentation. */
@Stable
interface SlideScope {
    /** The current build step of this slide, in `0 until steps`. */
    val step: Int

    /** The number of build steps of this slide. */
    val steps: Int

    /** The index of this slide, starting from 0. */
    val slideIndex: Int

    /** The total number of slides in the presentation. */
    val slideCount: Int
}

/** A single slide. Create slides with [PresentationBuilder.slide]. */
@Immutable
class Slide internal constructor(
    private val declaredSteps: Int?,
    private val notesOf: () -> List<String>,
    val transition: SlideTransition?,
    val stepTransition: SlideTransition?,
    /** The time planned for this slide; see [PresentationBuilder.slide]. */
    val duration: Duration?,
    val weight: Float,
    private val styleOverrides: (SlideStyleBuilder.() -> Unit)?,
    /** Which build steps a PDF export shows, and which one stands for the slide. */
    val export: SlideExport,
    /** The theme of this slide alone, in place of the presentation's, or `null` for that one. */
    val theme: SlideTheme?,
    val content: @Composable SlideScope.() -> Unit,
) {
    private lateinit var owner: Presentation
    private var index = 0

    /**
     * Speaker notes: one for the whole slide, or one per build step. Notes given as a function are
     * made each time they are read, so in the presenter window they follow the state they read.
     */
    val notes: List<String> get() = notesOf()

    /** The presentation's style with this slide's overrides. */
    lateinit var style: SlideStyle
        private set

    @Volatile
    private var discoveredSteps = 0

    /**
     * The number of build steps: as given to [PresentationBuilder.slide], or else found from the
     * [Reveal]s in the content.
     */
    val steps: Int
        get() = declaredSteps ?: discoveredSteps.takeIf { it > 0 } ?: discoverSteps()

    /**
     * Copies made by [Presentation.withTheme] share their slides and attach them again. That is
     * fine: the copies have the same slides and style, so any of them can count the steps.
     */
    internal fun attach(owner: Presentation, index: Int) {
        this.owner = owner
        this.index = index
        style = owner.style.with(styleOverrides)
    }

    private fun discoverSteps(): Int {
        if (discoveredSteps == 0) owner.discoverStepsOfAllSlides()
        return discoveredSteps
    }

    /**
     * The build step that stands for the slide: its page in a PDF of one page per slide, and its
     * picture in the presenter's slide list. The last one unless [export] says otherwise.
     */
    val representativeStep: Int get() = (export.representative ?: (steps - 1)).coerceIn(0, steps - 1)

    /** The build steps a PDF export of one page per slide shows, in order: none when it is left out. */
    val exportedSteps: List<Int>
        get() = export.pages?.asSequence()?.map { it.coerceIn(0, steps - 1) }?.distinct()?.sorted()?.toList() ?: listOf(representativeStep)

    /** Whether the number of steps still has to be found from the content. */
    internal val needsStepDiscovery: Boolean get() = declaredSteps == null && discoveredSteps == 0

    internal fun setDiscoveredSteps(steps: Int) {
        discoveredSteps = steps
    }
}

/** A whole presentation. Create one with [presentation]. */
@Immutable
class Presentation internal constructor(
    val title: String,
    val size: SlideSize,
    val theme: SlideTheme,
    val defaultTransition: SlideTransition,
    val style: SlideStyle,
    /** The planned length of the talk, shown as a progress bar in the presenter window. */
    val duration: Duration?,
    val background: (@Composable SlideScope.() -> Unit)?,
    val slides: List<Slide>,
    /** The [elementTransition] ids found in each frame, shared with copies made by [withTheme]. */
    private val elementIds: ConcurrentHashMap<Frame, Set<Any>> = ConcurrentHashMap(),
) {
    init {
        slides.forEachIndexed { index, slide -> slide.attach(this, index) }
    }

    internal val schedule: Schedule by lazy { Schedule(this) }

    /**
     * The same slides in another [theme], e.g. one loaded from a theme file. Slides follow it where
     * they read the theme: through [LocalSlideTheme], [MaterialTheme] or the built-in parts.
     */
    fun withTheme(theme: SlideTheme): Presentation =
        if (theme == this.theme) {
            this
        } else {
            Presentation(title, size, theme, defaultTransition, style, duration, background, slides, elementIds)
        }

    /** Counts the steps of all slides that need it at once: off-screen composition is cheaper in bulk. */
    @Synchronized
    internal fun discoverStepsOfAllSlides() {
        val pending = slides.indices.filter { slides[it].needsStepDiscovery }
        if (pending.isEmpty()) return
        discoverSteps(pending).forEach { (index, steps) -> slides[index].setDiscoveredSteps(steps) }
    }

    /** Whether [a] and [b] have elements with the same [elementTransition] id. */
    internal fun sharesElements(a: Frame, b: Frame): Boolean {
        val ids = elementIdsOf(a)
        return elementIdsOf(b).any { it in ids }
    }

    internal fun elementIdsOf(frame: Frame): Set<Any> = elementIds.getOrPut(frame) { discoverElementIds(frame) }

    /** Records the ids found while rendering [frame] for some other reason, e.g. for export. */
    internal fun rememberElementIds(frame: Frame, ids: Set<Any>) {
        elementIds.putIfAbsent(frame, ids)
    }
}

@DslMarker
annotation class SlideDsl

@SlideDsl
class PresentationBuilder internal constructor() {
    internal val slides = mutableListOf<Slide>()
    internal var background: (@Composable SlideScope.() -> Unit)? = null
    internal var style = SlideStyle()

    /**
     * Adds a slide.
     *
     * @param steps the number of build steps. The content can read the current one from
     *   [SlideScope.step]. By default it is found from the content: one more than the highest
     *   `atStep` of its [Reveal]s. Give it when the content reads [SlideScope.step] itself.
     * @param notes speaker notes shown in the presenter window. Pass a list instead to give each
     *   build step its own note.
     * @param transition how this slide is entered (and, reversed, how it is left when going back).
     *   `null` uses the presentation's default transition.
     * @param stepTransition `null` (default) keeps the slide's composition alive across steps, so
     *   steps can be animated with ordinary Compose animations such as [Reveal]. Non-null makes
     *   every step a separate frame that is transitioned with this transition. Use
     *   [SlideTransition.ElementTransition] to move [elementTransition] elements between steps.
     * @param duration the time planned for this slide. Slides without one share the rest of the
     *   presentation's `duration` in proportion to their [weight].
     * @param weight this slide's share of the time not planned with [duration], or, without a
     *   planned time, of the presenter's progress bar.
     * @param style overrides of the presentation's [style] for this slide.
     * @param export which build steps a PDF export shows as pages, which one stands for the slide,
     *   or that it is left out: see [SlideExport]. By default the slide fully built.
     * @param theme a theme for this slide alone, such as [SlideTheme.Dark] for one dark slide in a
     *   light presentation: the slide, its background and the presentation's [background] on it
     *   are drawn in it. `null` keeps the presentation's.
     */
    fun slide(
        steps: Int? = null,
        notes: String = "",
        transition: SlideTransition? = null,
        stepTransition: SlideTransition? = null,
        duration: Duration? = null,
        weight: Float = 1f,
        style: (SlideStyleBuilder.() -> Unit)? = null,
        export: SlideExport = SlideExport.Final,
        theme: SlideTheme? = null,
        content: @Composable SlideScope.() -> Unit,
    ) {
        val trimmed = notes.trimIndent()
        val list = if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        addSlide(steps, { list }, transition, stepTransition, duration, weight, style, export, theme, content)
    }

    /**
     * Adds a slide with one speaker note per build step. The presenter window highlights the
     * note of the current step. See the other overload for the parameters.
     */
    fun slide(
        steps: Int? = null,
        notes: List<String>,
        transition: SlideTransition? = null,
        stepTransition: SlideTransition? = null,
        duration: Duration? = null,
        weight: Float = 1f,
        style: (SlideStyleBuilder.() -> Unit)? = null,
        export: SlideExport = SlideExport.Final,
        theme: SlideTheme? = null,
        content: @Composable SlideScope.() -> Unit,
    ) {
        val trimmed = notes.map { it.trimIndent() }
        addSlide(steps, { trimmed }, transition, stepTransition, duration, weight, style, export, theme, content)
    }

    /**
     * Adds a slide whose speaker notes, one per build step or a single one, are made each time
     * they are shown. Notes that read Compose state, such as the language a deck plays in, follow
     * it in the presenter window. See the first overload for the parameters.
     */
    fun slide(
        steps: Int? = null,
        notes: () -> List<String>,
        transition: SlideTransition? = null,
        stepTransition: SlideTransition? = null,
        duration: Duration? = null,
        weight: Float = 1f,
        style: (SlideStyleBuilder.() -> Unit)? = null,
        export: SlideExport = SlideExport.Final,
        theme: SlideTheme? = null,
        content: @Composable SlideScope.() -> Unit,
    ) {
        addSlide(steps, { notes().map { it.trimIndent() } }, transition, stepTransition, duration, weight, style, export, theme, content)
    }

    private fun addSlide(
        steps: Int?,
        notes: () -> List<String>,
        transition: SlideTransition?,
        stepTransition: SlideTransition?,
        duration: Duration?,
        weight: Float,
        style: (SlideStyleBuilder.() -> Unit)?,
        export: SlideExport,
        theme: SlideTheme?,
        content: @Composable SlideScope.() -> Unit,
    ) {
        require(steps == null || steps >= 1) { "steps must be >= 1, but was $steps" }
        require(duration == null || !duration.isNegative()) { "duration must not be negative, but was $duration" }
        require(weight >= 0f) { "weight must not be negative, but was $weight" }
        slides += Slide(steps, notes, transition, stepTransition, duration, weight, style, export, theme, content)
    }

    /** Sets the default animation settings of every slide. Slides can override them with `slide(style = ...)`. */
    fun style(block: SlideStyleBuilder.() -> Unit) {
        style = style.with(block)
    }

    /** Sets a background drawn behind every slide. It does not take part in slide transitions. */
    fun background(content: @Composable SlideScope.() -> Unit) {
        background = content
    }
}

/**
 * Builds a [Presentation].
 *
 * ```
 * val deck = presentation(title = "My Talk") {
 *     slide { TitleSlide("Hello", "world") }
 *     slide {
 *         TitleAndContent("Agenda") {
 *             Bullet("first")
 *             Reveal(atStep = 1) { Bullet("second") }
 *         }
 *     }
 * }
 * ```
 */
fun presentation(
    title: String = "Untitled",
    size: SlideSize = SlideSize.Widescreen,
    theme: SlideTheme = SlideTheme.Light,
    transition: SlideTransition = SlideTransition.ElementTransition(),
    duration: Duration? = null,
    build: PresentationBuilder.() -> Unit,
): Presentation {
    val builder = PresentationBuilder().apply(build)
    return Presentation(
        title = title,
        size = size,
        theme = theme,
        defaultTransition = transition,
        style = builder.style,
        duration = duration,
        background = builder.background,
        slides = builder.slides.toList(),
    )
}

/**
 * Looks at every frame ahead of time (its build steps and [elementTransition] ids), so that moving
 * between frames during the talk does not have to. Optional: otherwise it happens when needed.
 */
fun Presentation.prepare() {
    allFrames().forEach { elementIdsOf(it) }
}

/** What the slide of [frame] sees of itself: its step, clamped to its steps, and its place in the deck. */
internal fun Presentation.scopeFor(frame: Frame): SlideScope {
    val slide = slides[frame.slide]
    return SlideScopeImpl(
        step = frame.step.coerceIn(0, slide.steps - 1),
        steps = slide.steps,
        slideIndex = frame.slide,
        slideCount = slides.size,
    )
}

internal class SlideScopeImpl(
    override val step: Int,
    override val steps: Int,
    override val slideIndex: Int,
    override val slideCount: Int,
) : SlideScope
