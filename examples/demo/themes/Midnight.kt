// A theme file: a .kt file in the deck's `themes` folder that declares `val theme`. The viewer
// compiles it on its own, lists it in View > Theme, and shows the same slides in it. Slides follow
// the theme where they read it (MaterialTheme, LocalSlideTheme and the built-in parts such as
// Bullet); colors written into a slide, like the demo's gradient logo, stay as they are.
//
// The package is only needed because the `demo-runner` module compiles all of examples/demo together.
package themes.midnight

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.munetoshi.slidecompose.deck.theme.*

val theme = SlideTheme.Dark.copy(
    background = Color(0xFF0D1321),
    onBackground = Color(0xFFF0EEE9),
    primary = Color(0xFFFF8A4C),
    secondary = Color(0xFFFFC56E),
    muted = Color(0xFF8D96AD),
    surface = Color(0xFF1C2537),
    onSurface = Color(0xFFF0EEE9),
    code = CodeColors.Dark.copy(background = Color(0xFF151D2E)),
    // A warm glow from the top right corner, behind every slide.
    slideModifier = Modifier.drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x33FF8A4C), Color.Transparent),
                center = Offset(size.width, 0f),
                radius = size.width * 0.7f,
            ),
        )
    },
    bullet = BulletTheme(markers = listOf("▸", "–")),
)
