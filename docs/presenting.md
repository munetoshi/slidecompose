# Presenting

## Keys

| Key | Action |
| --- | --- |
| `→` `↓` `Space` `Enter` `PageDown` `N` / click | Next (build step, then slide) |
| `←` `↑` `Shift+Space` `PageUp` `Backspace` | Previous |
| `Home` / `End` | First / last |
| Number + `Enter` | Go to that slide |
| `F` / `⌃⌘F` | Toggle full screen for the focused window |
| `Esc` | Leave what has focus on the slide (such as a code editor), or else full screen |
| `P` | Open or close the presenter window |
| `B` / `.` | Black screen; a click or any key brings the slides back |
| `L` | Share the pointer, or stop |
| `R` | Reset the timer (`T` starts it) |
| `T` | Pause or resume the timer |
| `S` | Swap the slides and the presenter view between the two windows |
| `+` / `-` | The size of the presenter's notes |
| `⌥⌘P` | Play |
| `⌘E` / `⇧⌘E` | Export a PDF / with a page per build step |
| `⌘R` | Reload (the app only) |

Going on during an animation finishes it before the next one starts.
What has focus on a slide, such as a code editor, gets the keys first.
For a button on a slide not to take `Space` and `Enter` after a click,
put `Modifier.focusProperties { canFocus = false }` before its `clickable`.

## Playing

Play (⌥⌘P) shows the slides full screen on an external display and the presenter window full screen on the main one.
If they come out the other way round, `S` swaps them. With one display, only the slides go full screen.
Both windows can be full screen at once only with "Displays have separate Spaces" on (the default).

## The presenter window

It shows the current slide and its notes on the left, the next slide (or build step) and its notes on the right.

- The current slide reacts to clicks; a click nothing on it takes goes to the next build step
- With `notes = listOf(...)`, a note per build step, the current build step's note comes to the middle
- The dots above the slides are its build steps; clicking one goes to that build step
- While the screen is black, the current slide is dimmed and says Black Screen
- View > Appearance sets it to light or dark

### Progress bars

At the bottom are the time bar and the slide bar.

- The time bar shows with `presentation(duration = 20.minutes)`
  - The time and the bar are green on schedule, blue ahead of it and red behind
  - Each slide's time is set with `slide(duration = 30.seconds)`, or as a share with `slide(weight = 2f)`
- Clicking the slide bar goes to that slide (or build step)
  - Each slide's length on it follows its planned time, split by build steps

### Slide list

SLIDES ▸ at the bottom shows thumbnails of the slides.

- Clicking a thumbnail goes to that slide
- Each thumbnail shows the slide's representative build step (`SlideExport.step(n)` in [Exporting PDF / PNG](pdf.md))

### Sharing the pointer

With the cursor over the current slide, a pointer shows at the same place on the slides.

- Where the cursor is an I-beam, so is the pointer
- The arrow button at the top right switches it between white, black and off (the default color is the style's `pointer(color)`)
- It fades out after 3 seconds still and comes back when it moves (Play > Hide Pointer When Idle turns that off)
