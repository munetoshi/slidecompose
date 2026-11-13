# The app (SlideCompose.app)

The app compiles decks written in `.kt` files while it runs, shows them, and reloads them every time they are saved.

## Building and starting

```sh
# Makes viewer/build/compose/binaries/main/app/SlideCompose.app
./gradlew :viewer:createDistributable

# Opens the demo
open -a viewer/build/compose/binaries/main/app/SlideCompose.app examples/demo

# Starts it from Gradle
./gradlew :viewer:run --args="examples/demo"
```

Building needs JDK 17 or later. Gradle downloads the JDK bundled in the app (Eclipse Temurin 25) on the first build.
Copied to `/Applications`, the app also opens decks from Finder's Open With and from drops on its Dock icon.

### Keeping macOS permissions across builds

macOS remembers permissions, such as reading the Documents folder, per app signature.
With the ad-hoc signature, every new build loses them.
Run this once on your Mac, and later builds are signed with a self-signed certificate that keeps them.

```sh
./gradlew :viewer:createSigningKeychain
```

The certificate goes into a keychain without a password, `~/Library/Keychains/slidecompose-signing.keychain-db`.
It does not replace a signature for distribution (Developer ID and notarization).

## Opening a deck

- The app opens a single `.kt` file, or a folder (every `.kt` file in it but those in `themes/`)
- It shows the top-level `val deck = presentation { ... }`
- Saving reloads the deck; on an error, the error shows over the last version that worked
- Relative paths, such as those of images, start from the deck's folder
- Libraries in `libs/*.jar` can be used when the deck is opened as a folder
- File > New Deck… makes a new deck from a template
- File > Close Deck (⌘W) goes back to the home window
- View > Theme shows the deck in another theme ([Themes and styles](theme-and-style.md))
- View > Appearance sets the home and presenter windows to light or dark
- The app is in English and Japanese, following the macOS language settings

## Trust and safe mode

A deck is a program: opening it runs its code with your permissions.
So before compiling a deck for the first time, the app asks whether you trust it.
It remembers the files and folders you trust and does not ask again.
Open in Editor lets you read the code before you decide.

Decks you do not trust can be opened with Open in Safe Mode (macOS only).
The deck's code then runs in another process, in a macOS sandbox.

- It can read only the system, the app, installed fonts and a copy of the deck
- It cannot write files, reach the network or start other apps
- For a deck of one file that uses images, choose them with Choose Files and Open in Safe Mode…
- Reloading on save, PDF export and View > Theme are not available

## Exporting from the command line

PDFs and PNGs can be exported without opening a window. The app does not ask for trust here.

```sh
SlideCompose.app/Contents/MacOS/SlideCompose --pdf out.pdf examples/demo           # a page per slide
SlideCompose.app/Contents/MacOS/SlideCompose --pdf out.pdf --builds examples/demo  # a page per build step
SlideCompose.app/Contents/MacOS/SlideCompose --pdf out.pdf --images examples/demo  # each page as an image
SlideCompose.app/Contents/MacOS/SlideCompose --png out/ --scale 1 examples/demo
SlideCompose.app/Contents/MacOS/SlideCompose --pdf out.pdf --theme examples/demo/themes/Midnight.kt examples/demo
SlideCompose.app/Contents/MacOS/SlideCompose --pdf out.pdf --theme my-theme.kt#plain examples/demo  # a preset
```

## Completion in an editor

Open this repository in IntelliJ IDEA and put your decks under `examples/` to get completion.
Add a new folder to `srcDir` in `demo-runner/build.gradle.kts`.
With several decks or themes in one module, give each file its own `package`.
