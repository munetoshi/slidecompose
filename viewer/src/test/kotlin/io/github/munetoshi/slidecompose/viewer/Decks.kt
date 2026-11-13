package io.github.munetoshi.slidecompose.viewer

import java.io.File

/** A deck with two title slides. */
const val TWO_SLIDES = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Test") {
    slide { TitleSlide("One") }
    slide { TitleSlide("Two") }
}
"""

/** A deck that does not compile: `missing`, on line 4 once written, is not declared. */
const val BROKEN = """
import io.github.munetoshi.slidecompose.deck.*
import io.github.munetoshi.slidecompose.deck.components.*

val deck = presentation(title = "Test") {
    slide { TitleSlide(missing) }
}
"""

/** Writes [text] to [name] in [directory], making the folders on the way. */
fun write(directory: File, name: String, text: String): File =
    File(directory, name).apply {
        parentFile.mkdirs()
        writeText(text.trimStart())
    }
