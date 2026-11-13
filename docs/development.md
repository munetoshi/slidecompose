# Development: structure, how it works, limits

## Structure

| Directory | Contents |
| --- | --- |
| `slidecompose/` | The library (DSL, element transitions, windows, presenter, PDF) |
| `viewer/` | The app `SlideCompose.app`, with the Kotlin compiler and the Compose compiler plugin |
| `examples/demo/` | The demo deck (`Demo.kt`) and its themes (`themes/`) |
| `demo-runner/` | Runs the demo from the library (`run`, `snapshots`, `windowSmokeTest`), with `examples/demo` as its source |

Packages are under `io.github.munetoshi.slidecompose`, one per feature.

| Packages | Contents |
| --- | --- |
| `deck`, `deck.theme`, `deck.components`, `deck.code`, `deck.transition` | The DSL, the model and slide elements |
| `player.stage`, `player.presenter`, `player.window`, `player.export` | Drawing and moving through slides, the presenter, windows, export |
| `viewer.app`, `viewer.home`, `viewer.compile`, `viewer.theme`, `viewer.cli`, `viewer.about`, `viewer.safemode` | The app |

`player` uses `deck`; `deck` does not refer to `player`.

## How it works, and limits

- Decks are compiled by the Kotlin compiler in the app and loaded with a `URLClassLoader`
- Decks can use the libraries in the app and, in a folder, those in `libs/*.jar`
- `libs/*.jar` is loaded again only when a jar changes, so libraries keep their state across reloads
- A loaded deck draws every frame off-screen before it replaces the one shown, so exceptions do not crash the window
- Every transition is played off-screen once too, so the first one is not slow
- Reloading clears what slides `remember`, and keeps the slide and build step shown
- Outside safe mode, decks run in the app's JVM without limits
- Element transitions are built on Compose's shared element transitions
- The `.app` is about 280MB, with the Kotlin compiler and a JDK in it

## Tests and checks

| Command | What it does |
| --- | --- |
| `./gradlew test` | Tests of the library and the app ([Kotest](https://kotest.io)) |
| `./gradlew :buildSrc:test` | Tests of collecting the licenses |
| `./gradlew :demo-runner:snapshots` | Every slide of the demo and frames of its transitions as images in `demo-runner/build/snapshots/` |
| `./gradlew :demo-runner:windowSmokeTest` | Opens the windows and switches the presenter and full screen |

CI (`.github/workflows/ci.yml`) runs on pushes to main and on pull requests from branches of this repository.
It builds and tests, makes the snapshots and exports the demo as a PDF; the images and the PDF stay in Artifacts for 7 days.

Dependabot opens pull requests once a week for versions released at least 7 days before.
When Skiko's Skia changes, update `skia` in `libs.versions.toml` and `skiaSha256` in `slidecompose/build.gradle.kts` to match.

## Licenses

The licenses of the bundled libraries and more are in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
In the app, see SlideCompose > About SlideCompose.

- Library licenses are collected from their POMs with [Licensee](https://github.com/cashapp/licensee) (`buildSrc/`)
- Software from outside Maven (Skia, the JDK and more) is listed in `viewer/build.gradle.kts`, with its license texts in `licenses/`
- After a dependency change, run `./gradlew updateNotices` (`check` fails while the file is out of date)
- A library under a license not allowed (anything but Apache-2.0 for now) fails the build

## Distributing the app

The app bundles a Java runtime (Eclipse Temurin, GPL version 2 with the Classpath Exception).
When you distribute the app, publish the runtime's source in the same place, such as GitHub Releases.

```sh
./gradlew :viewer:createDistributable :viewer:jdkSources
```

The source is downloaded to `viewer/build/jdk-sources/`.
The app icon is in `viewer/icons/` (`SlideCompose.icns`, made from `icon.svg`).
