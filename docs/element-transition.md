# Element transitions

```kotlin
slide { Text("Compose", Modifier.elementTransition("logo"), style = MaterialTheme.typography.displayLarge) }
slide { Text("Compose", Modifier.align(Alignment.TopStart).elementTransition("logo"), style = MaterialTheme.typography.titleLarge) }
```

Elements with the same id in two frames move between their places and sizes. Elements without an id cross-fade.

| Argument | What it does |
| --- | --- |
| `mode = ElementTransitionMode.Scale` | The default: lays out at its own size and scales (for text and images) |
| `mode = ElementTransitionMode.Resize` | Lays out at the size of each frame on the way (for boxes with rounded corners) |
| `crossfade` | Cross-fades from the old look to the new one on the way |
| `zIndex` | The order where elements overlap |

- `crossfade` defaults to `false` with `Scale` and `true` with `Resize`
- Put `elementTransition` early in the modifiers (before size and background) to move the background with it
- `stepTransition = SlideTransition.ElementTransition()` moves elements between build steps too
- With the same ids on agenda items and section titles, an item moves into its title (the demo's `agenda()`)

## Text

Text of `SlideText` and `Bullet` with an `id`, and of `TitleSlide` / `TitleAndContent` with a `titleId`, changes on its way to match the other end.

- Cross-fades: the text, the font, the weight
- Changes gradually: the color

With `Text(…, Modifier.elementTransition(…))` of your own, the text scales in its new look.
Add `crossfade = true` if its words change.
