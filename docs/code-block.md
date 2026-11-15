# Code blocks

`CodeBlock` shows code. It can switch between versions of the code at build steps, and be edited while presenting.

## Showing code

```kotlin
slide {
    CodeBlock("""fun main() = println("Hello")""", focusLines = 1..1)
}
```

`language` (`"kotlin"` by default) sets the syntax colors. `focusLines` dims the other lines.
The block is as wide as its code, unless its modifier sets a width.

## Versions of the code

Given a list of versions and the one to show, the block moves from version to version.

```kotlin
val versions = listOf(
    """
    val deck = presentation(title = "My Talk") {
    }
    """,
    """
    val deck = presentation(title = "My Talk") {
        slide { ... }
    }
    """,
)

slide(steps = versions.size) {
    CodeBlock(versions, version = step)
}
```

- Code that stays moves to its new place, code removed fades out, and code added fades in
- What a version adds is in focus, and the rest dimmed (not with `highlightChanges = false`)
- The block is as wide as the widest version from the first one on
- The slide's `code(timing)` style sets how long the code takes to move ([Themes and styles](theme-and-style.md#styles))

Versions are compared token by token (names, strings, symbols and so on).
An argument added in the middle of a line comes in alone, and the `)` after it slides aside.
Tokens move only between lines alike, never to a line far away.

## Editing on the slide

With `editable = true`, click the code to edit it while presenting.

```kotlin
slide {
    CodeBlock(
        """
        val words = listOf("Slide", "Compose")
        words.forEach { println(it.uppercase()) }
        """,
        editable = true,
    )
}
```

- While you edit, keys go to the editor; `Esc` leaves it
- `PageUp` / `PageDown` (from presentation remotes) go on even in the editor
- Edits show in every window that shows the slide
- Once no window shows the block, the original code comes back: going to another slide and back, or reloading the deck (`resetsOnLeave = false` keeps the edits)
- While the pointer is shared, the caret and selection show in the other windows too

## Build steps

Arguments that follow `step` change how the code shows from build step to build step.

| Argument | What it does |
| --- | --- |
| `version` | The version of the code shown |
| `focusLines` / `focusRanges` | Dims the other lines / the other parts |
| `editable` | Whether the code can be edited (until then, keys move the slides) |
| `visibleLines` / `scrollTo` | How many lines show, and the line at the top |
| `folds` / `folded` | Ranges that can fold, and those folded at the build step |
| `hiddenLines` | Lines hidden at the build step |

This one explains the code build step by build step, then lets you edit it at the last build step.

```kotlin
slide(steps = focus.size + 1) {
    CodeBlock(code, focusLines = focus.getOrNull(step), editable = step == focus.size)
}
```

The marks left of the code (⌄ / ›) and ` ⋯` fold and unfold the ranges in `folds`.
To use these with versions, give the block another `code` at each build step under the same `id`: it moves from version to version as well.
`addedParts(versions, step)` gives the parts a version adds, to put in focus as `CodeBlock(versions, version)` does.

```kotlin
val versions = listOf(start, withInstance, withContext)
slide(steps = versions.size) {
    CodeBlock(
        versions[step],
        id = "demo",
        folded = if (step >= 1) listOf(1..5) else emptyList(),
        focusRanges = addedParts(versions, step),
    )
}
```

## Code across slides

Code with the same `id` moves token by token from slide to slide too.
Other ids in `from` gather several blocks of the slide before into one.

```kotlin
slide { Row { CodeBlock(foo, id = "foo"); CodeBlock(wanted, id = "wanted") } }
slide { CodeBlock(demo, id = "foo", from = listOf("wanted"), editable = true) }
```

## Building on a code block

For something built on a block, such as a button that runs its code,
get the state with `rememberCodeBlockState(code, id)` and pass it to `CodeBlock(state, …)`.

| Property | What it is |
| --- | --- |
| `state.code` | The code now |
| `state.codeWithout(hiddenLines)` | The code that shows |
| `state.restarts` | How many times the original code came back (to clear results) |

`codeTextStyle(theme, fontSize)` gives the text style of code.
