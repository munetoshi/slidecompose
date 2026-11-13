package io.github.munetoshi.slidecompose.player.presenter


/** Where to scroll notes placed from [tops] to [bottoms] to show note [focused]; see [focusScroll]. */
internal fun noteScroll(tops: IntArray, bottoms: IntArray, focused: Int, viewport: Int): Int =
    focusScroll(
        previousStart = tops[(focused - 1).coerceAtLeast(0)],
        currentStart = tops[focused],
        nextEnd = bottoms[(focused + 1).coerceAtMost(bottoms.lastIndex)],
        viewport = viewport,
    )

/**
 * Where to scroll a list, [viewport] long, to show its current item, then as much of the next as
 * there is room for, then of the one before: [currentStart] is where the current item starts,
 * [nextEnd] where the next ends (the current, if it is the last), and [previousStart] where the
 * one before starts (the current, if it is the first). So with little room the one before goes
 * first, then the next.
 */
internal fun focusScroll(previousStart: Int, currentStart: Int, nextEnd: Int, viewport: Int): Int =
    (nextEnd - viewport).coerceIn(previousStart, currentStart)

/** Where to scroll the slide list, [stride] apart and [width] wide each, to show the current slide; see [focusScroll]. */
internal fun thumbnailScroll(current: Int, count: Int, stride: Int, width: Int, viewport: Int): Int =
    focusScroll(
        previousStart = (current - 1).coerceAtLeast(0) * stride,
        currentStart = current * stride,
        nextEnd = (current + 1).coerceAtMost(count - 1) * stride + width,
        viewport = viewport,
    )
