// A light theme in serif type. Fonts can also come from files next to the theme:
// `fontFamily = FontFamily(themeFont("fonts/MyFont-Regular.ttf"), themeFont("fonts/MyFont-Bold.ttf", FontWeight.Bold))`.
package themes.paper

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.munetoshi.slidecompose.deck.theme.*

private val CodeShape = RoundedCornerShape(6.dp)

val theme = SlideTheme.Light.copy(
    background = Color(0xFFF6F1E7),
    onBackground = Color(0xFF2A2420),
    primary = Color(0xFFB0472A),
    secondary = Color(0xFF5F7A3A),
    muted = Color(0xFF8C8074),
    surface = Color(0xFFE9E1D1),
    onSurface = Color(0xFF2A2420),
    code = CodeColors.Light.copy(background = Color(0xFFEFE8DA)),
    fontFamily = FontFamily.Serif,
    bullet = BulletTheme(markers = listOf("—", "–")),
    codeBlock = CodeBlockTheme(shape = CodeShape, modifier = Modifier.border(2.dp, Color(0xFFD9CDB6), CodeShape)),
)

// A preset, listed as "Paper (sansHeadings)": titles in sans serif over serif text. An agenda item
// becoming a section's title then changes font on the way, letter by letter.
val sansHeadings = theme.copy(headingFontFamily = FontFamily.SansSerif)
