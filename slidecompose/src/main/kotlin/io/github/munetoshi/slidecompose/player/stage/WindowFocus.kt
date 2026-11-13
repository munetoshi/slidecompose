package io.github.munetoshi.slidecompose.player.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager

/** Whether something in a window has the focus, such as a text field on a slide, and how to take it back. */
internal class WindowFocus {
    var manager: FocusManager? = null
    var hasFocus = false

    /** Takes the focus back from what has it: returns whether anything had it. */
    fun clear(): Boolean {
        if (!hasFocus) return false
        manager?.clearFocus()
        return true
    }
}

/** The focus of the window around, which [TrackFocus] gives. */
internal val LocalWindowFocus = staticCompositionLocalOf<WindowFocus?> { null }

/** Keeps [focus] up to date with whether something in [content] has the focus, and gives it to [content]. */
@Composable
internal fun TrackFocus(focus: WindowFocus, content: @Composable () -> Unit) {
    val manager = LocalFocusManager.current
    SideEffect { focus.manager = manager }
    Box(Modifier.fillMaxSize().onFocusChanged { focus.hasFocus = it.hasFocus }) {
        CompositionLocalProvider(LocalWindowFocus provides focus, content = content)
    }
}
