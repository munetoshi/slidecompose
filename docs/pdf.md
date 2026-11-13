# Exporting PDF / PNG

Export with File > Export PDF… (`⌘E`), `--pdf` on the command line, or from code.

```kotlin
deck.exportPdf(File("out.pdf"), PdfExportOptions(includeBuildSteps = false, scale = 2f))
deck.exportPngs(File("out/"))
```

Each slide can choose the build steps it exports (counted from 0).

```kotlin
slide(steps = 4, export = SlideExport.step(2)) { ... }      // build step 2 as one page (also the thumbnail in the slide list)
slide(steps = 4, export = SlideExport.steps(1, 3)) { ... }  // build steps 1 and 3, a page each
slide(export = SlideExport.None) { ... }                    // not exported (but presented)
```

By default (`SlideExport.Final`), a slide exports its last build step as one page.
With a page per build step (`⇧⌘E`, `--builds`, `includeBuildSteps = true`), every build step of every slide but `None` is exported.

Text and shapes stay vectors, with the fonts embedded (on macOS only; elsewhere each page is an image).

- Only what a PDF cannot hold, such as shaders and blurs, becomes an image (at `scale`)
- A page with a `painterResource` bitmap becomes one image (`AssetImage` does not)
- `vector = false` or `--images` makes each page one image (`imageFormat = PdfImageFormat.Jpeg` for smaller files)
