# Themes and styles

The theme sets the look (colors, fonts, elements); the style sets defaults such as animation times.

## Themes

There are `SlideTheme.Light` (the default) and `SlideTheme.Dark`; `copy()` changes parts of them.
A theme can be a `.kt` file in the deck's `themes/` folder that declares `val theme`.

```kotlin
// themes/Midnight.kt
import androidx.compose.ui.graphics.Color
import io.github.munetoshi.slidecompose.deck.components.*   // themeFont
import io.github.munetoshi.slidecompose.deck.theme.*
// + Compose imports

val theme = SlideTheme.Dark.copy(
    primary = Color(0xFFFF8A4C),
    fontFamily = FontFamily(themeFont("fonts/Inter-Regular.ttf"), themeFont("fonts/Inter-Bold.ttf", FontWeight.Bold)),
    bullet = BulletTheme(markers = listOf("▸", "–")),
    codeBlock = CodeBlockTheme(shape = RoundedCornerShape(8.dp)),
)
```

- `slide(theme = SlideTheme.Dark)` draws one slide in a theme of its own, its background too
- View > Theme switches to a theme in `themes/` or another theme file
- The app remembers the theme chosen for each deck in its settings
- Other `SlideTheme` `val`s in a theme file show in the menu as presets
- Paths in `themeFont("fonts/x.ttf")` start from the theme file's folder
- As a library: `runPresentation(deck.withTheme(theme))`
- Colors written directly on a slide do not change with the theme

| Property | What it sets |
| --- | --- |
| `background`, `onBackground`, `primary`, `secondary`, `muted`, `surface`, `onSurface` | Colors (also in `MaterialTheme.colorScheme`) |
| `code` | The background and syntax colors of code |
| `fontFamily`, `headingFontFamily`, `codeFontFamily` | Fonts of text, headings and code |
| `typography` | Text sizes and weights |
| `slideModifier` | The background of every slide (`Modifier.drawBehindAnimated` for a moving one) |
| `titleSlide`, `titleAndContent`, `bullet`, `codeBlock` | Each element's text, spacing, markers, shapes and more |
| `slideNumber` | The text of `SlideNumber` |
| `inlineCode` | The size, background and text color of inline code |

An element's text style is applied on top of `typography`.

## Styles

Animation times and the pointer color can be overridden for the deck, a slide, then an element.

```kotlin
presentation {
    style {                                            // the deck
        reveal(durationMillis = 300)
        elementTransition(timing = MoveTiming.Together(800))
    }
    slide(style = { fade(durationMillis = 1000) }) { ... }   // this slide
    slide {
        Reveal(atStep = 1, durationMillis = 600) { ... }      // this element
    }
}
```

| Setting | Default | For |
| --- | --- | --- |
| `reveal(durationMillis, offset, easing)` | 400ms, 0.dp | `Reveal` |
| `fade(durationMillis)` | 400ms | `SlideTransition.Fade` |
| `push(durationMillis, easing)` | 600ms | `SlideTransition.Push` |
| `elementTransition(timing, easing)` | `MoveTiming.Together(900)` | `SlideTransition.ElementTransition` |
| `code(timing)` | `MoveTiming.Together(1000)` | `CodeBlock` between versions (its length) |
| `pointer(color)` | `PointerColor.White` | The color of the shared pointer (`White` / `Black`) |

Values passed to a transition itself, such as `SlideTransition.ElementTransition(timing = ...)`, come first.

### Moves and fades (`MoveTiming`)

| Timing | Motion |
| --- | --- |
| `MoveTiming.Together(durationMillis)` | Moves and cross-fades at once |
| `MoveTiming.Staged(moveMillis, moveOnlyMillis)` | What goes fades out before things move; what comes fades in before they stop |

```kotlin
slide(transition = SlideTransition.ElementTransition(timing = MoveTiming.Staged())) { ... }
```

The demo uses `Staged` between the agenda and its sections, and `Together` elsewhere.
