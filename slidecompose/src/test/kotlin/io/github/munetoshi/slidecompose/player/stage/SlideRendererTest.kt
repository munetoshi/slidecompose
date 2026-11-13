package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.presentation
import io.github.munetoshi.slidecompose.player.presenter.PresenterView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Three slides with 2, 1 and 1 build steps, with notes. */
private fun deck() = presentation {
    slide(steps = 2, notes = "first") {}
    slide(notes = "second") {}
    slide {}
}

/** A renderer that draws nothing and records what the presenter view asked of it. */
private class RecordingRenderer : SlideRenderer {
    var currentShown = 0
    val stills = mutableListOf<Frame>()

    @Composable
    override fun Stage(controller: PresentationController, onClick: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun Current(controller: PresentationController, modifier: Modifier) {
        currentShown++
    }

    @Composable
    override fun Still(controller: PresentationController, frame: Frame, letterboxColor: Color, modifier: Modifier) {
        stills += frame
    }
}

/** Draws [scene] once at [nanos], after the state changes made since the last time. */
private fun drawOnce(nanos: Long, scene: ImageComposeScene) {
    Snapshot.sendApplyNotifications()
    scene.render(nanos).close()
}

class SlideRendererTest : FunSpec({
    test("the presenter view draws the current and next slides through its renderer") {
        val controller = PresentationController(deck())
        val renderer = RecordingRenderer()
        val scene = ImageComposeScene(1360, 860, Density(1f)) {
            PresenterView(controller, Modifier.fillMaxSize(), renderer = renderer)
        }
        try {
            drawOnce(0L, scene)
            (renderer.currentShown > 0) shouldBe true
            renderer.stills.last() shouldBe Frame(0, 1)
            controller.next()
            drawOnce(16_000_000L, scene)
            renderer.stills.last() shouldBe Frame(1, 0)
        } finally {
            scene.close()
        }
    }

    test("only the in-process renderer runs the slides' code here") {
        SlideRenderer.InProcess.isInProcess shouldBe true
        RecordingRenderer().isInProcess shouldBe false
    }

    test("a click on the slides goes on, or only ends the black screen") {
        val controller = PresentationController(deck())
        controller.slidesClicked(focus = null)
        controller.frame shouldBe Frame(0, 1)
        controller.isBlackedOut = true
        controller.slidesClicked(focus = null)
        controller.isBlackedOut shouldBe false
        controller.frame shouldBe Frame(0, 1)
    }
})
