package io.github.munetoshi.slidecompose.viewer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.viewer.MemoryPreferences
import io.github.munetoshi.slidecompose.viewer.TWO_SLIDES
import io.github.munetoshi.slidecompose.viewer.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** The text of a text node, and how many lines it takes. */
private fun SemanticsNode.textAndLines(): Pair<String, Int> {
    val layouts = mutableListOf<TextLayoutResult>()
    config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return config[SemanticsProperties.Text].joinToString() to layouts.single().lineCount
}

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest : FunSpec({
    test("while a deck loads, only its own buttons show, each label on one line in the window") {
        val viewer = Viewer(MemoryPreferences())
        viewer.open(write(tempdir(), "Slides.kt", TWO_SLIDES))
        viewer.trustDeck()
        runComposeUiTest {
            setContent {
                Box(Modifier.size(HomeWindowSize)) { HomeScreen(viewer, window = null, DialogTitles("", "", "", "")) }
            }
            val isText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
            val labels = onAllNodes(isText and hasAnyAncestor(hasClickAction()), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.textAndLines() }
            // Open in the editor, and close: none to open another.
            labels shouldHaveSize 2
            labels shouldBe labels.map { (text, _) -> text to 1 }
        }
    }

    test("the three latest decks show above the buttons, which stay in the window") {
        val viewer = Viewer(MemoryPreferences())
        val directory = tempdir()
        (1..4).forEach {
            viewer.open(write(directory, "Deck$it.kt", TWO_SLIDES))
            viewer.trustDeck()
        }
        viewer.close()
        runComposeUiTest {
            setContent {
                Box(Modifier.size(HomeWindowSize)) { HomeScreen(viewer, window = null, DialogTitles("", "", "", "")) }
            }
            onNodeWithText("Deck1.kt").assertDoesNotExist()
            val shown = listOf("Deck4.kt", "Deck3.kt", "Deck2.kt").map { onNodeWithText(it).getBoundsInRoot() }
            shown.zipWithNext { above, below -> above.bottom <= below.top } shouldBe listOf(true, true)
            val button = onNodeWithText("Open Deck File…").getBoundsInRoot()
            (shown.last().bottom <= button.top) shouldBe true
            // Less the title bar, about 28dp.
            (button.bottom <= HomeWindowSize.height - 28.dp) shouldBe true
        }
    }
})
