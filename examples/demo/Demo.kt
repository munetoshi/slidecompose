import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.code.*
import io.github.munetoshi.slidecompose.deck.components.*
import io.github.munetoshi.slidecompose.deck.theme.*
import io.github.munetoshi.slidecompose.deck.transition.*
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The SlideCompose demo: what decks can do, a point a slide, in four sections with an agenda
// between them. Values that several slides use are plain Kotlin, defined once out here.

// The demo plays in English or Japanese, picked on the second slide; it starts in the system's
// language. Slides and notes that read it follow it when it changes.
private enum class Language(val label: String) { English("English"), Japanese("日本語") }

private var language by mutableStateOf(if (Locale.getDefault().language == "ja") Language.Japanese else Language.English)

/** [en] or [ja], in the language the demo plays in. */
private fun tr(en: String, ja: String): String = if (language == Language.Japanese) ja else en

/** A note for each build, in the language the demo plays in, made again as the language changes. */
private fun notes(vararg notes: Pair<String, String>): () -> List<String> = { notes.map { (en, ja) -> tr(en, ja) } }

private const val Title = "SlideCompose"
private val Subtitle: String get() = tr("Slides written in Jetpack Compose", "Jetpack Compose でスライド作成")

/** The sections of the demo: the agenda lists them, and each grows into the title of its slides. */
private val Sections: List<String>
    get() = listOf(
        tr("Writing slides", "スライドの書き方"),
        tr("Moving things", "アニメーション"),
        tr("Showing code", "コードの表示"),
        tr("Presenting", "プレゼンテーション"),
    )

/** The element transition id of section [index]'s item on the agenda, and of its slides' titles. */
private fun sectionId(index: Int): String = "section-$index"

/** Margins of slides that lay out their own content. */
private val PagePadding = PaddingValues(horizontal = 140.dp, vertical = 110.dp)

/**
 * How many times the button on the Compose slide was clicked. Out here, rather than remembered in
 * the slide, so that every window showing the slide shows the same count.
 */
private var clicks by mutableIntStateOf(0)

/** A version of the deck's code, and the point of the build that shows it. */
private class DeckVersion(private val en: String, private val ja: String, val code: String) {
    val point: String get() = tr(en, ja)
}

private val DeckCode = listOf(
    DeckVersion(
        "`presentation { }` makes a deck",
        "`presentation { }` でスライドを作成",
        """
        val deck = presentation(title = "My Talk") {
        }
        """,
    ),
    DeckVersion(
        "`slide { }` adds a slide",
        "`slide { }` でページを追加",
        """
        val deck = presentation(title = "My Talk") {
            slide {
                TitleSlide("My Talk", "Jetpack Compose")
            }
        }
        """,
    ),
    DeckVersion(
        "`slide { }` adds a slide",
        "`slide { }` でページを追加",
        """
        val deck = presentation(title = "My Talk") {
            slide {
                TitleSlide("My Talk", "Jetpack Compose")
            }
            slide {
                TitleAndContent("My Talk") {
                    Bullet("one")
                }
            }
        }
        """,
    ),
)

/** The code of the focus slide's own code block, its code itself left out: it shows itself. */
private val FocusCode = """
    CodeBlock(
        code = "…",
        focusLines = listOf(null, 2..2, 3..3)[step],
    )
"""

/** The builds of the focus slide: what each says, in its language. */
private val FocusPoints: List<String>
    get() = listOf(
        tr("A code block shows code", "コードブロックでコードを表示"),
        tr("`code` is the code to show", "`code` で表示するコードを指定"),
        tr("`focusLines` lights some lines and dims the rest", "`focusLines` で一部の行を強調"),
    )

/** The code of the editable slide's own code block, its code itself left out: it shows itself, and can be changed. */
private val EditableCode = """
    CodeBlock(
        code = "…",
        folds = listOf(1..4),
        editable = true,
    )
"""

/** Ways to say which build steps of a slide go into the PDF. */
private val ExportCode = """
    slide(steps = 3, export = SlideExport.step(0)) { … }
    slide(steps = 3, export = SlideExport.steps(0, 2)) { … }
    slide(export = SlideExport.None) { … }
"""

// The viewer app shows the top-level property that returns a Presentation. `duration` is the
// planned length of the talk, which the presenter window keeps track of.
val deck = presentation(title = Title, duration = 10.minutes) {

    // Defaults for every slide, like a stylesheet: slides and elements can override them.
    style {
        reveal(durationMillis = 400)
        elementTransition(timing = MoveTiming.Together(900))
    }

    // Behind every slide, still during transitions; only the number changes with the slide.
    background {
        if (slideIndex > 0) SlideNumber(Modifier.fillMaxSize().padding(48.dp).wrapContentSize(Alignment.BottomEnd))
    }

    slide(
        duration = 20.seconds,
        notes = notes("This is SlideCompose: slides written in Jetpack Compose." to "SlideCompose は、Jetpack Compose でスライドを書くツールです。"),
    ) {
        TitleCard()
    }

    // The same title, lifted to make room for a choice of language. Buttons do nothing on paper, so
    // the PDF leaves this slide out.
    slide(
        duration = 20.seconds,
        export = SlideExport.None,
        notes = notes(
            "Pick a language: the slides and these notes follow it." to "言語を選ぶと、スライドもこのノートも切り替わります。",
        ),
    ) {
        TitleCard(lift = 80.dp) {
            LanguagePicker(Modifier.align(Alignment.Center).offset(y = 350.dp))
        }
    }

    // The title and the logo move to their places, then the list fades in.
    slide(
        transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
        notes = notes(
            "A deck is Kotlin and its slides are Compose, so it can do what Compose can. Here is a short tour." to
                "スライドは Kotlin で、中身は Compose です。なので、Compose でできることは何でもできます。順に見ていきます。",
        ),
    ) {
        Overview()
    }

    agenda(notes("It has four parts. First, how slides are written." to "4 つのパートがあります。まずはスライドの書き方です。"))

    // 1. Writing slides.

    slide(
        transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
        steps = DeckCode.size,
        notes = notes(
            "A deck is a top-level property made with presentation." to "スライドは、presentation で作るトップレベルのプロパティです。",
            "slide adds a slide." to "slide でページを追加します。",
            "Another slide, with a title and a bullet. Inside, it is plain Compose." to "もう 1 枚、タイトルと箇条書きのスライドです。中身はふつうの Compose です。",
        ),
    ) {
        SectionSlide(0) {
            Point(DeckCode[step].point)
            CodeBlock(DeckCode.map { it.code }, version = step)
        }
    }

    slide(
        transition = SlideTransition.Fade(),
        style = { reveal(offset = 24.dp) },
        notes = notes(
            "A slide can show its parts one build step at a time." to "スライドは、部分ごとに順に見せられます。",
            "Reveal with atStep shows its content from that build step on: this one at build step 1," to "Reveal の atStep で、そのビルドステップから表示します。これはビルドステップ 1 で、",
            "and this one at build step 2. The number of build steps comes from the Reveals." to "これはビルドステップ 2 です。ビルドステップの数は Reveal から決まります。",
        ),
    ) {
        SectionSlide(0) {
            Point(tr("Build steps show a slide a part at a time", "ビルドステップで少しずつ表示"))
            // The code beside the bullets it makes, the current build's line lit.
            SideBySide(left = { CodeBlock(buildsCode(), focusLines = step + 1..step + 1) }, right = { BuildBullets() })
        }
    }

    // Buttons do nothing on paper, so the PDF leaves this slide out.
    slide(
        transition = SlideTransition.Fade(),
        export = SlideExport.None,
        notes = notes(
            "Slides are Compose, so they can hold state and buttons. Click the button: every window counts the same clicks." to
                "スライドは Compose なので、状態やボタンを持てます。ボタンを押すと、どのウィンドウでも同じ数を数えます。",
        ),
    ) {
        SectionSlide(0) {
            Point(tr("Slides are Compose: state and buttons work", "スライドは Compose なので状態やボタンが利用可能"))
            SideBySide(left = { CodeBlock(counterCode()) }, right = { ClickCounter() }, centersRight = true)
        }
    }

    agenda(notes("Next, things that move." to "次は、アニメーションです。"))

    // 2. Moving things.

    slide(
        transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
        steps = 2,
        stepTransition = SlideTransition.ElementTransition(),
        notes = notes(
            "Elements with the same id move between slides, and between build steps too. Here, three tiles in a row," to
                "同じ id の要素は、スライドの間でも、ビルドステップの間でも動きます。ここでは、横に並んだ 3 枚のタイルが、",
            "move into a column the other way round, each to its new place and size." to "逆の順に縦に並び替わります。それぞれ新しい位置と大きさへ動きます。",
        ),
    ) {
        SectionSlide(1) {
            Point(tr("The same id moves from place to place", "同じ id の UI 要素でアニメーション"))
            SideBySide(left = { CodeBlock(TilesCode, focusLines = step + 3..step + 3) }, right = { Tiles(step) }, centersRight = true)
        }
    }

    slide(
        transition = SlideTransition.Push(),
        notes = notes(
            "Each slide picks how it comes in: element transitions by default, or a fade, a push, or none. This one pushed in." to
                "スライドごとに、入り方を選べます。既定は UI 要素トランジションで、ほかにフェード、押し出し、なしがあります。このスライドは押し出しで入りました。",
        ),
    ) {
        SectionSlide(1) {
            Point(tr("Each slide picks its transition", "スライドごとにトランジションを選択"))
            Bullet(tr("`ElementTransition`: the default", "`ElementTransition`: 既定"))
            Bullet(tr("`Fade`, `Push` or `None`", "`Fade`、`Push`、`None`"))
        }
    }

    agenda(notes("Then, code." to "次は、コードです。"))

    // 3. Showing code.

    slide(
        transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
        steps = 3,
        notes = notes(
            "A code block shows code, here its own." to "コードブロックはコードを見せます。ここでは、自分自身のコードです。",
            "code is the code it shows," to "code が見せるコードで、",
            "and focusLines lights some lines and dims the rest, build step by build step." to "focusLines で、ビルドステップごとに一部の行を光らせ、ほかを暗くします。",
        ),
    ) {
        SectionSlide(2) {
            Point(FocusPoints[step])
            CodeBlock(code = FocusCode, focusLines = listOf(null, 2..2, 3..3)[step])
        }
    }

    slide(
        transition = SlideTransition.Fade(),
        notes = notes(
            "With editable, the code can be changed during the talk: this block shows its own code. The mark on the left folds it." to
                "editable にすると、発表中にコードを書き換えられます。このブロックも自分自身のコードです。左の印で畳めます。",
        ),
    ) {
        SectionSlide(2) {
            Point(tr("`editable = true` lets you edit it on the slide", "`editable = true` でスライド上での編集が可能"))
            CodeBlock(code = EditableCode, folds = listOf(1..4), editable = true)
        }
    }

    agenda(notes("Last, presenting." to "最後に、プレゼンテーションです。"))

    // 4. Presenting.

    slide(
        transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
        style = { reveal(offset = 24.dp) },
        notes = notes(
            "P opens the presenter window: the current slide and notes on the left, what comes next on the right." to
                "P でプレゼンターウィンドウが開きます。左に今のスライドとノート、右に次のものが出ます。",
            "The bars at the bottom show the time and the slides, against the plan." to
                "下のバーで、時間とスライドの進み具合を予定と比べて管理できます。",
            "The slides in the window react to clicks, as the projected ones do." to
                "ウィンドウ内のスライドでも、ボタンなどの UI を操作できます。",
            "And the pointer over the window shows on the projected slides." to
                "そして、ウィンドウ上のマウスポインターを、投影しているスライドに共有できます。",
        ),
    ) {
        SectionSlide(3) {
            Point(tr("The presenter window keeps the talk on track", "プレゼンターウィンドウで進行を管理"))
            Bullet(tr("`P` opens the presenter window", "`P` でプレゼンターウィンドウを開閉"))
            Reveal(atStep = 1) { Bullet(tr("Time and slide progress bars keep time", "時間とスライド進捗バーで時間管理")) }
            Reveal(atStep = 2) { Bullet(tr("The slides in the window take clicks too", "ウィンドウ内のスライドで UI インタラクション可能")) }
            Reveal(atStep = 3) { Bullet(tr("The pointer over the window shows on the projected slides", "ウィンドウ上のマウスポインタを投影スライドに共有")) }
        }
    }

    // In a theme of its own, the dark one: the slide shows what it says.
    slide(
        transition = SlideTransition.Fade(),
        theme = SlideTheme.Dark,
        notes = notes(
            "A theme sets the look of the slides. Give it to presentation for the whole deck, or to slide for one page, like this dark one." to
                "テーマでスライドの見た目を変えられます。presentation に渡せばスライド全体に、slide に渡せばこのページのように 1 ページにだけ適用されます。",
            "And without changing the code, View, Theme shows the deck in another theme." to
                "コードを変更しなくても、表示 > テーマ で別のテーマを適用できます。",
        ),
    ) {
        SectionSlide(3) {
            Point(tr("Themes set the look", "見た目をテーマで変更"))
            Bullet(tr("The whole deck: `presentation(theme = SlideTheme.Dark)`", "スライド全体に適用: `presentation(theme = SlideTheme.Dark)`"))
            Bullet(tr("One page alone: `slide(theme = SlideTheme.Dark)`", "1 ページにだけ適用: `slide(theme = SlideTheme.Dark)`"))
            PlainLine(tr("Without changing the code: View > Theme", "コードを変更しなくても 表示 > テーマ で適用可能"))
        }
    }

    slide(
        transition = SlideTransition.Fade(),
        notes = notes(
            "Handouts come from File, Export PDF." to "配布資料は、ファイル > PDF を書き出す で書き出せます。",
            "Each slide can say which of its build steps go in, or that it stays out." to
                "スライドごとに、どのビルドステップを書き出すか、書き出さないかを指定できます。",
        ),
    ) {
        SectionSlide(3) {
            Point(tr("Export handouts", "配布資料をエクスポート"))
            Bullet(tr("File > Export PDF… in the menu", "メニューの ファイル > PDF を書き出す…"))
            PlainLine(tr("Choosing the build steps to export", "書き出し対象のビルドステップの指定方法"))
            CodeBlock(ExportCode)
        }
    }

    slide(duration = 20.seconds, notes = notes("That is SlideCompose. Thank you." to "以上、SlideCompose でした。ありがとうございました。")) {
        Box(Modifier.fillMaxSize()) {
            Hero(Modifier.align(Alignment.Center).offset(y = (-150).dp).size(180.dp))
            Text(
                tr("Thank you", "ありがとうございました"),
                // Other words than the title it comes from: the two cross-fade on the way.
                Modifier.align(Alignment.Center).offset(y = 110.dp).elementTransition("title", crossfade = true),
                style = MaterialTheme.typography.displayMedium,
            )
        }
    }
}

/**
 * The agenda: it comes back before each section, with its own [notes]. Each item has the id of its
 * section's title, so the item grows into the title, and the title shrinks back into the item.
 */
private fun PresentationBuilder.agenda(notes: () -> List<String>) = slide(
    // Coming back from a section, its title shrinks into its item, then the other items fade in.
    transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged()),
    duration = 10.seconds,
    notes = notes,
) {
    TitleAndContent(tr("Agenda", "アジェンダ")) {
        Sections.forEachIndexed { index, section -> Bullet(section, id = sectionId(index)) }
    }
}

/** A slide of section [index], titled with the section's name, which the agenda's item grows into. */
@Composable
private fun SectionSlide(index: Int, content: @Composable () -> Unit) {
    TitleAndContent(Sections[index], titleId = sectionId(index)) { content() }
}

/** A line of body text without a bullet mark. */
@Composable
private fun PlainLine(text: String) {
    SlideText(text, style = MaterialTheme.typography.bodyLarge)
}

/** The point of a slide, in green, above what shows it, cross-fading when it changes. */
@Composable
private fun Point(text: String) {
    Crossfade(text, animationSpec = tween(400)) {
        SlideText(it, style = MaterialTheme.typography.titleMedium, color = LocalSlideTheme.current.primary)
    }
}

/** The logo above the title and the subtitle, [lift] higher than on the first slide, and [extra] on top. */
@Composable
private fun TitleCard(lift: Dp = 0.dp, extra: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        Hero(Modifier.align(Alignment.Center).offset(y = (-170).dp - lift).size(260.dp))
        Column(Modifier.align(Alignment.Center).offset(y = 170.dp - lift), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Title, Modifier.elementTransition("title"), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(28.dp))
            Text(
                Subtitle,
                Modifier.elementTransition("subtitle"),
                style = MaterialTheme.typography.titleLarge,
                color = LocalSlideTheme.current.muted,
            )
        }
        extra()
    }
}

/** "Play in [English] [日本語]": one choice of [Language], which the whole demo follows. */
@Composable
private fun LanguagePicker(modifier: Modifier = Modifier) {
    val theme = LocalSlideTheme.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text("Play in", style = MaterialTheme.typography.titleLarge, color = theme.muted)
        Row(Modifier.selectableGroup().clip(CircleShape).border(2.dp, theme.primary, CircleShape)) {
            Language.entries.forEach { option ->
                val selected = option == language
                Text(
                    option.label,
                    Modifier
                        // Clicked, a choice leaves Space and Enter to move the slides.
                        .focusProperties { canFocus = false }
                        .selectable(selected, role = Role.RadioButton) { language = option }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .background(if (selected) theme.primary else Color.Transparent)
                        .padding(horizontal = 44.dp, vertical = 16.dp),
                    color = if (selected) Color.White else theme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

/** The title and the subtitle at the top left, what SlideCompose does under them, and the logo on the right. */
@Composable
private fun Overview() {
    Row(Modifier.fillMaxSize().padding(PagePadding)) {
        Column(Modifier.weight(1f)) {
            Text(Title, Modifier.elementTransition("title"), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                Subtitle,
                Modifier.elementTransition("subtitle"),
                style = MaterialTheme.typography.titleSmall,
                color = LocalSlideTheme.current.muted,
            )
            Spacer(Modifier.height(72.dp))
            Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Bullet(tr("Slides are `@Composable` functions", "スライドは `@Composable` 関数"))
                Bullet(tr("Elements move from slide to slide", "スライドをまたぐ UI 要素アニメーション"))
                Bullet(tr("A presenter window that helps keep time", "時間を管理しやすいプレゼンターウィンドウ"))
            }
        }
        Hero(Modifier.align(Alignment.CenterVertically).size(520.dp))
    }
}

/** The logo, as large as the box it is given: it grows and shrinks with the box as it moves. */
@Composable
private fun Hero(modifier: Modifier = Modifier) {
    Box(modifier.elementTransition("hero", ElementTransitionMode.Resize)) {
        AssetImage("assets/logo.svg", Modifier.fillMaxSize())
    }
}

/**
 * [left], code, and [right], what it makes, side by side at the top; with [centersRight], [right]
 * is in the middle of the room right of [left].
 */
@Composable
private fun SideBySide(left: @Composable () -> Unit, right: @Composable () -> Unit, centersRight: Boolean = false) {
    // The code stays at the top; what it makes sits in the middle of the row, which the taller of the two sets.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(80.dp)) {
        left()
        Box(
            Modifier.weight(1f).align(if (centersRight) Alignment.CenterVertically else Alignment.Top),
            contentAlignment = if (centersRight) Alignment.Center else Alignment.TopStart,
        ) { right() }
    }
}

/** The bullets of the builds slide, made by [buildsCode]: one at the start, one a build after it. */
private val BuildTexts: List<String>
    get() = listOf(tr("A slide shows its parts", "スライドの一部を"), tr("one build step", "ビルドステップごとに"), tr("at a time", "少しずつ表示"))

@Composable
private fun SlideScope.BuildBullets() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Bullet(BuildTexts[0])
        Reveal(atStep = 1) { Bullet(BuildTexts[1]) }
        Reveal(atStep = 2) { Bullet(BuildTexts[2]) }
    }
}

/** The code of [BuildBullets], line by line as it is. */
private fun buildsCode(): String = """
    Bullet("${BuildTexts[0]}")
    Reveal(atStep = 1) { Bullet("${BuildTexts[1]}") }
    Reveal(atStep = 2) { Bullet("${BuildTexts[2]}") }
"""

/** The code of [ClickCounter], in short. */
private fun counterCode(): String = """
    var clicks by mutableIntStateOf(0)

    Button(onClick = { clicks++ }) {
        Text("${tr("Clicked ${'$'}clicks times", "クリック ${'$'}clicks 回")}")
    }
"""

/** A button that counts its clicks; a click on it does not move the slides on. */
@Composable
private fun ClickCounter() {
    val theme = LocalSlideTheme.current
    Text(
        tr("Clicked $clicks times", "クリック $clicks 回"),
        Modifier
            // Clicked, it leaves Space and Enter to move the slides.
            .focusProperties { canFocus = false }
            .clip(CircleShape)
            .clickable { clicks++ }
            .pointerHoverIcon(PointerIcon.Hand)
            .background(theme.primary)
            .padding(horizontal = 48.dp, vertical = 20.dp),
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
    )
}

/** The code of [Tiles], in short: the tiles' modifier gives each its id. */
private val TilesCode = """
    slide(stepTransition = ElementTransition()) {
        when (step) {
            0 -> Row { Tile(0); Tile(1); Tile(2) }
            1 -> Column { Tile(2); Tile(1); Tile(0) }
        }
    }
"""

/** Three tiles in a row at build 0, and in a column the other way round at build 1. */
@Composable
private fun Tiles(step: Int) {
    val colors = listOf(Color(0xFF35D07F), Color(0xFF658ED2), Color(0xFF8558F5))
    Box(Modifier.size(width = 500.dp, height = 420.dp)) {
        if (step == 0) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                colors.indices.forEach { Tile("$it", colors[it], 150.dp) }
            }
        } else {
            Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                colors.indices.reversed().forEach { Tile("$it", colors[it], 110.dp, wide = true) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, color: Color, size: Dp, modifier: Modifier = Modifier, wide: Boolean = false) {
    Box(
        modifier
            .elementTransition("tile-$label", ElementTransitionMode.Resize)
            .size(width = if (wide) size * 4 else size, height = size)
            .clip(RoundedCornerShape(28.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
    }
}
