package io.github.munetoshi.slidecompose.viewer.home

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.munetoshi.slidecompose.player.presenter.PresenterColors

/** The colors of the home and About windows. */
internal class HomeColors(val background: Color, val panel: Color, val text: Color, val secondary: Color, val link: Color) {
    companion object {
        val Dark = HomeColors(Color(0xFF16161A), Color(0xFF232329), Color.White, Color(0xFFB0B0B8), PresenterColors.LogoGreen)
        val Light = HomeColors(Color(0xFFF5F5F7), Color.White, Color(0xFF1D1D1F), Color(0xFF6E6E73), PresenterColors.LogoGreenDark)
    }
}

/** Material's colors for the home window's buttons and progress, in the green of the logo rather than Material's purple. */
internal fun homeColorScheme(dark: Boolean): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(primary = PresenterColors.LogoGreenDark, onPrimary = Color.White)
}
