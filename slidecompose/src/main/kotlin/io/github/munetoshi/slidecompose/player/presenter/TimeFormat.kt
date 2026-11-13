package io.github.munetoshi.slidecompose.player.presenter

import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

internal val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** "4:05", or "1:04:05" from an hour. */
internal fun formatShort(millis: Number): String {
    val seconds = abs(millis.toDouble() / 1000).roundToLong()
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
