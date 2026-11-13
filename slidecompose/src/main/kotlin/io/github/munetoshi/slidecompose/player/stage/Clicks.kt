package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Desktop
import java.awt.desktop.AppForegroundEvent
import java.awt.desktop.AppForegroundListener

/**
 * Goes to the next frame on a click that nothing inside the element (e.g. a button on a slide)
 * takes. Like in other Mac apps, the click that brings the app to the front only does that.
 * While something in the window has the [focus], such as a text field on a slide, a click only
 * takes it back. While the screen is black, a click only brings the slides back, as in Keynote
 * and PowerPoint.
 */
internal fun Modifier.advancesOnClick(controller: PresentationController, focus: WindowFocus?): Modifier = pointerInput(controller, focus) {
    // Taps that children consume never complete here.
    detectTapGestures(onTap = { controller.slidesClicked(focus) })
}

/** What a click on the slides that nothing on them takes does: see [advancesOnClick]. */
internal fun PresentationController.slidesClicked(focus: WindowFocus?) {
    when {
        AppForeground.justRaised() -> Unit
        focus?.clear() == true -> Unit
        isBlackedOut -> isBlackedOut = false
        else -> next()
    }
}

/** When the app last came to the front, to tell clicks that only activate it from clicks on the slides. */
internal object AppForeground : AppForegroundListener {
    @Volatile
    private var raisedAt = 0L

    init {
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
        if (desktop?.isSupported(Desktop.Action.APP_EVENT_FOREGROUND) == true) desktop.addAppEventListener(this)
    }

    override fun appRaisedToForeground(event: AppForegroundEvent) {
        raisedAt = System.currentTimeMillis()
    }

    override fun appMovedToBackground(event: AppForegroundEvent) = Unit

    /** Starts listening, if not yet. */
    fun listen() = Unit

    /** Whether the app came to the front just now, e.g. with the click being handled. */
    fun justRaised(): Boolean = System.currentTimeMillis() - raisedAt < 500
}

/**
 * Keeps a control from taking the focus when it is clicked: Space, Enter and the other keys then
 * go on to the windows, which move the slides, rather than click it again.
 */
internal fun Modifier.keepsNoFocus(): Modifier = focusProperties { canFocus = false }
