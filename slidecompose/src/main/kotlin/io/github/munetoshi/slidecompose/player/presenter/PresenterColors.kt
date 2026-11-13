package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Light or dark colors for the app's own windows, such as the presenter window. */
enum class Appearance {
    /** Follows the system's light or dark appearance. */
    System,
    Light,
    Dark,
    ;

    /** Whether this appearance is dark right now. */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = when (this) {
        System -> isSystemInDarkTheme()
        Light -> false
        Dark -> true
    }
}

/** The colors of the presenter window. */
@Immutable
data class PresenterColors(
    val background: Color,
    val panel: Color,
    val text: Color,
    val dim: Color,
    /** Build dots and indicators. */
    val accent: Color,
    /** The timer while running on schedule, within the planned time of the current slide. */
    val running: Color,
    /** Being ahead of schedule, before the planned start of the current slide. */
    val ahead: Color,
    /** Being behind schedule or over time. */
    val late: Color,
    /** Progress bar: slides already presented, the current one, and the outline of the rest. */
    val done: Color,
    val current: Color,
    val upcoming: Color,
) {
    companion object {
        /** The green of the top tier of the app's logo, its primary color. */
        val LogoGreen = Color(0xFF35D07F)

        /** The darker green of the side of the logo's top tier, for green on light backgrounds and under white text. */
        val LogoGreenDark = Color(0xFF2BAB68)

        /** The blue of the middle tier of the app's logo. */
        val LogoBlue = Color(0xFF658ED2)

        val Dark = PresenterColors(
            background = Color(0xFF111114),
            panel = Color(0xFF1C1C21),
            text = Color(0xFFECECF1),
            dim = Color(0xFF8B8B96),
            accent = Color(0xFF9C9CA6),
            running = LogoGreen,
            ahead = LogoBlue,
            // A red as bright and as saturated as the logo's colors.
            late = Color(0xFFE0605A),
            done = Color(0xFF6E6E78),
            current = Color(0xFFA6A6B0),
            upcoming = Color(0xFF45454F),
        )

        val Light = PresenterColors(
            background = Color(0xFFF2F2F5),
            panel = Color(0xFFFFFFFF),
            text = Color(0xFF1D1D1F),
            dim = Color(0xFF6E6E78),
            accent = Color(0xFF8E8E98),
            running = LogoGreenDark,
            ahead = LogoBlue,
            late = Color(0xFFD24E45),
            done = Color(0xFF7C7C86),
            current = Color(0xFFB8B8C0),
            upcoming = Color(0xFFC4C4CC),
        )
    }
}

internal val LocalPresenterColors = staticCompositionLocalOf { PresenterColors.Dark }

/** The presenter window's colors, in this file's composables. */
internal val colors: PresenterColors
    @Composable @ReadOnlyComposable get() = LocalPresenterColors.current
