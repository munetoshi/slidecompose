package io.github.munetoshi.slidecompose.deck.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import io.github.munetoshi.slidecompose.deck.theme.SlideLineHeightStyle
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme

/** Line height of code, relative to its font size. */
internal const val CODE_LINE_HEIGHT = 1.45f

/**
 * The style code blocks set their code in, in [fontSize], or else in the theme's code block font
 * size: for text that goes with code, such as what it printed.
 */
fun codeTextStyle(theme: SlideTheme, fontSize: TextUnit): TextStyle {
    val size = fontSize.takeOrElse { theme.codeBlock.fontSize }
    return TextStyle(
        fontFamily = theme.codeFontFamily,
        fontSize = size,
        lineHeight = size * CODE_LINE_HEIGHT,
        lineHeightStyle = SlideLineHeightStyle,
        color = theme.code.text,
    )
}

/**
 * The box code blocks sit in, after their [CodeTransition.boxModifier]: without its padding on the
 * left when [padsStart] is false, for code that puts it there itself.
 */
internal fun Modifier.codeContainer(theme: SlideTheme, padsStart: Boolean = true): Modifier {
    val padding = theme.codeBlock.padding
    return then(theme.codeBlock.modifier)
        .clip(theme.codeBlock.shape)
        .background(theme.code.background)
        .padding(if (padsStart) padding else PaddingValues(0.dp, padding.calculateTopPadding(), padding.calculateRightPadding(LayoutDirection.Ltr), padding.calculateBottomPadding()))
}
