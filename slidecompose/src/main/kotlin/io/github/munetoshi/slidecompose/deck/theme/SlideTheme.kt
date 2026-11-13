package io.github.munetoshi.slidecompose.deck.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.Presentation
import io.github.munetoshi.slidecompose.deck.code.CodeBlock
import io.github.munetoshi.slidecompose.deck.components.Bullet
import io.github.munetoshi.slidecompose.deck.components.SlideNumber
import io.github.munetoshi.slidecompose.deck.components.TitleAndContent
import io.github.munetoshi.slidecompose.deck.components.TitleSlide
import io.github.munetoshi.slidecompose.deck.components.themeFont

/**
 * Colors, fonts and the look of the built-in parts of a presentation. Inside a slide, the theme is
 * also exposed as [MaterialTheme], so Material 3 components and `MaterialTheme.typography` work as
 * usual. Sizes are in slide units (a 1920x1080 slide by default).
 *
 * The same slides can be shown in another theme with [Presentation.withTheme]. The viewer app
 * loads themes from files of their own: see its View > Theme menu. Slides follow the theme where
 * they read it, through [LocalSlideTheme], [MaterialTheme] or the built-in parts; colors written
 * into a slide stay as they are.
 *
 * @property fontFamily the font of text, and of headings unless [headingFontFamily] is given. Load
 *   font files with [themeFont].
 * @property headingFontFamily the font of the display, headline and title styles of [typography].
 * @property typography text sizes and weights. Its styles without a font family get [fontFamily]
 *   or [headingFontFamily], and those without a line height style get [SlideLineHeightStyle].
 * @property slideModifier applied to the background of every slide, behind its content, e.g.
 *   `Modifier.background(brush)` for a gradient. Like the deck's `background`, it does not take part
 *   in slide transitions.
 * @property slideNumber the text style of [SlideNumber], on top of `labelLarge`. It is [muted]
 *   unless it gives a color.
 * @property inlineCode how code between backticks in [SlideText] looks.
 */
@Immutable
data class SlideTheme(
    val background: Color,
    val onBackground: Color,
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val surface: Color,
    val onSurface: Color,
    val code: CodeColors,
    val fontFamily: FontFamily = FontFamily.Default,
    val headingFontFamily: FontFamily? = null,
    val codeFontFamily: FontFamily = FontFamily.Monospace,
    val typography: Typography = SlideTypography,
    val isDark: Boolean = false,
    val slideModifier: Modifier = Modifier,
    val titleSlide: TitleSlideTheme = TitleSlideTheme(),
    val titleAndContent: TitleAndContentTheme = TitleAndContentTheme(),
    val bullet: BulletTheme = BulletTheme(),
    val codeBlock: CodeBlockTheme = CodeBlockTheme(),
    val slideNumber: TextStyle = TextStyle(),
    val inlineCode: InlineCodeTheme = InlineCodeTheme(),
) {
    companion object {
        val Light = SlideTheme(
            background = Color(0xFFFAFAF7),
            onBackground = Color(0xFF1D1D1F),
            primary = Color(0xFF3D6AF2),
            secondary = Color(0xFF7F52FF),
            muted = Color(0xFF86868B),
            surface = Color(0xFFEDEDEA),
            onSurface = Color(0xFF1D1D1F),
            code = CodeColors.Light,
        )

        val Dark = SlideTheme(
            background = Color(0xFF16161A),
            onBackground = Color(0xFFF2F2F5),
            primary = Color(0xFF7FA2FF),
            secondary = Color(0xFFB69CFF),
            muted = Color(0xFF8E8E96),
            surface = Color(0xFF26262C),
            onSurface = Color(0xFFF2F2F5),
            code = CodeColors.Dark,
            isDark = true,
        )
    }
}

@Immutable
data class CodeColors(
    val background: Color,
    val text: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val type: Color,
    val function: Color,
) {
    companion object {
        val Light = CodeColors(
            background = Color(0xFFF1F1EE),
            text = Color(0xFF24292F),
            keyword = Color(0xFFCF222E),
            string = Color(0xFF0A3069),
            number = Color(0xFF0550AE),
            comment = Color(0xFF6E7781),
            annotation = Color(0xFF8250DF),
            type = Color(0xFF953800),
            function = Color(0xFF8250DF),
        )

        val Dark = CodeColors(
            background = Color(0xFF1E1F22),
            text = Color(0xFFBCBEC4),
            keyword = Color(0xFFCF8E6D),
            string = Color(0xFF6AAB73),
            number = Color(0xFF2AACB8),
            comment = Color(0xFF7A7E85),
            annotation = Color(0xFFB3AE60),
            type = Color(0xFFBCBEC4),
            function = Color(0xFF56A8F5),
        )
    }
}

/**
 * How [TitleSlide] looks. [title] and [subtitle] are set on top of the typography's
 * `displayMedium` and `titleLarge`; the subtitle is [muted][SlideTheme.muted] unless [subtitle]
 * gives a color. The text lines up with [horizontalAlignment], and the subtitle starts
 * [subtitleIndent] further in, e.g. to line it up with the title's text after an opening bracket.
 * [modifier] goes on the whole slide.
 */
@Immutable
data class TitleSlideTheme(
    val title: TextStyle = TextStyle(),
    val subtitle: TextStyle = TextStyle(),
    val subtitleIndent: Dp = 0.dp,
    val spacing: Dp = 40.dp,
    val padding: PaddingValues = PaddingValues(horizontal = 160.dp),
    val horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    val verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    val modifier: Modifier = Modifier,
)

/**
 * How [TitleAndContent] looks. [title] is set on top of the typography's `headlineLarge`.
 * [modifier] goes on the whole slide.
 */
@Immutable
data class TitleAndContentTheme(
    val title: TextStyle = TextStyle(),
    val titleSpacing: Dp = 64.dp,
    val contentSpacing: Dp = 28.dp,
    val padding: PaddingValues = PaddingValues(start = 140.dp, top = 110.dp, end = 140.dp, bottom = 90.dp),
    val modifier: Modifier = Modifier,
)

/**
 * How [Bullet] looks. [markers] go by level, the last one for all deeper levels; they are
 * [primary][SlideTheme.primary] unless [markerColor] is given. [text] is set on top of the
 * typography's `bodyLarge` at level 0, and [nestedText] on top of `bodyMedium` below it.
 * [modifier] goes on each bullet, inside its indent.
 */
@Immutable
data class BulletTheme(
    val markers: List<String> = listOf("•", "–"),
    val markerColor: Color = Color.Unspecified,
    val markerSpacing: Dp = 28.dp,
    val indent: Dp = 64.dp,
    val text: TextStyle = TextStyle(),
    val nestedText: TextStyle = TextStyle(),
    val modifier: Modifier = Modifier,
) {
    init {
        require(markers.isNotEmpty()) { "markers must not be empty" }
    }
}

/**
 * How [CodeBlock] looks. Their colors are the theme's
 * [code][SlideTheme.code], and their font its [codeFontFamily][SlideTheme.codeFontFamily].
 * [modifier] goes on the block, outside its [shape], e.g. for a border or a shadow.
 */
@Immutable
data class CodeBlockTheme(
    val fontSize: TextUnit = 30.sp,
    val shape: Shape = RoundedCornerShape(24.dp),
    val padding: PaddingValues = PaddingValues(horizontal = 44.dp, vertical = 36.dp),
    val modifier: Modifier = Modifier,
)

/**
 * How code between backticks in [SlideText] looks, in the theme's
 * [codeFontFamily][SlideTheme.codeFontFamily].
 *
 * @property scale its size against the text around it: monospaced letters look large next to
 *   proportional ones.
 * @property background the color of the rounded box behind it, or null for the theme's
 *   [surface][SlideTheme.surface]. [Color.Transparent] draws none, and leaves no room for one.
 * @property color the color of its letters, whatever color the text around them is, as in a
 *   colored lead line; [Color.Unspecified] for the text's own.
 */
@Immutable
data class InlineCodeTheme(
    val scale: Float = 0.88f,
    val background: Color? = null,
    val color: Color = Color.Unspecified,
)

val LocalSlideTheme = staticCompositionLocalOf { SlideTheme.Light }

// Headings share one line-height ratio (and so do body styles): an element transition between two
// styles then scales one text box into the other without the copies drifting apart.
/**
 * Text sizes and weights for a 1080-unit-high slide, the default [SlideTheme.typography]. The
 * styles have no font family: the theme gives them its own.
 */
val SlideTypography: Typography = Typography(
    displayLarge = typeStyle(144, FontWeight.Bold, 1.2f),
    displayMedium = typeStyle(112, FontWeight.Bold, 1.2f),
    displaySmall = typeStyle(88, FontWeight.Bold, 1.2f),
    headlineLarge = typeStyle(80, FontWeight.Bold, 1.2f),
    headlineMedium = typeStyle(64, FontWeight.Bold, 1.2f),
    headlineSmall = typeStyle(56, FontWeight.SemiBold, 1.2f),
    titleLarge = typeStyle(52, FontWeight.SemiBold, 1.2f),
    titleMedium = typeStyle(44, FontWeight.SemiBold, 1.2f),
    titleSmall = typeStyle(38, FontWeight.SemiBold, 1.2f),
    bodyLarge = typeStyle(44, FontWeight.Normal, 1.4f),
    bodyMedium = typeStyle(38, FontWeight.Normal, 1.4f),
    bodySmall = typeStyle(32, FontWeight.Normal, 1.4f),
    labelLarge = typeStyle(30, FontWeight.Medium, 1.2f),
    labelMedium = typeStyle(26, FontWeight.Medium, 1.2f),
    labelSmall = typeStyle(22, FontWeight.Medium, 1.2f),
)

private fun typeStyle(size: Int, weight: FontWeight, lineHeight: Float) = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    lineHeightStyle = SlideLineHeightStyle,
)

/**
 * Makes every line of a text [TextStyle.lineHeight] high, the first and the last too, with the
 * glyphs in the middle, as Material 3's own styles do. Without a line height style, Compose Desktop
 * makes a single line as high as the font says, whatever the line height.
 */
val SlideLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The theme's [typography][SlideTheme.typography], with its font families and line height styles filled in. */
internal fun SlideTheme.resolvedTypography(): Typography {
    val heading = headingFontFamily ?: fontFamily
    return typography.copy(
        displayLarge = typography.displayLarge.withDefaults(heading),
        displayMedium = typography.displayMedium.withDefaults(heading),
        displaySmall = typography.displaySmall.withDefaults(heading),
        headlineLarge = typography.headlineLarge.withDefaults(heading),
        headlineMedium = typography.headlineMedium.withDefaults(heading),
        headlineSmall = typography.headlineSmall.withDefaults(heading),
        titleLarge = typography.titleLarge.withDefaults(heading),
        titleMedium = typography.titleMedium.withDefaults(heading),
        titleSmall = typography.titleSmall.withDefaults(heading),
        bodyLarge = typography.bodyLarge.withDefaults(fontFamily),
        bodyMedium = typography.bodyMedium.withDefaults(fontFamily),
        bodySmall = typography.bodySmall.withDefaults(fontFamily),
        labelLarge = typography.labelLarge.withDefaults(fontFamily),
        labelMedium = typography.labelMedium.withDefaults(fontFamily),
        labelSmall = typography.labelSmall.withDefaults(fontFamily),
    )
}

private fun TextStyle.withDefaults(family: FontFamily): TextStyle =
    if (fontFamily == null || lineHeightStyle == null) {
        copy(fontFamily = fontFamily ?: family, lineHeightStyle = lineHeightStyle ?: SlideLineHeightStyle)
    } else {
        this
    }

private fun SlideTheme.colorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        secondary = secondary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
        outline = muted,
    )
}

@Composable
internal fun ProvideSlideTheme(theme: SlideTheme, content: @Composable () -> Unit) {
    val colorScheme = remember(theme) { theme.colorScheme() }
    val typography = remember(theme) { theme.resolvedTypography() }
    CompositionLocalProvider(LocalSlideTheme provides theme) {
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides theme.onBackground) {
                androidx.compose.material3.ProvideTextStyle(typography.bodyLarge, content)
            }
        }
    }
}
