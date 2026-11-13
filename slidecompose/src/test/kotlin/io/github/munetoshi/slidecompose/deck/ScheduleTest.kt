package io.github.munetoshi.slidecompose.deck

import io.github.munetoshi.slidecompose.deck.transition.totalMillis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ScheduleTest : FunSpec({
    test("without a time plan, slides take up the bar by weight") {
        val schedule = Schedule(
            presentation {
                slide(steps = 1) {}
                slide(steps = 1, weight = 3f) {}
            },
        )
        schedule.totalMillis shouldBe null
        schedule.end(0) shouldBe (0.25 plusOrMinus 1e-9)
        schedule.end(1) shouldBe (1.0 plusOrMinus 1e-9)
        schedule.millisOf(0) shouldBe null
    }

    test("slides with a duration get it, and the others share the rest by weight") {
        val schedule = Schedule(
            presentation(duration = 10.minutes) {
                slide(steps = 1, duration = 4.minutes) {}
                slide(steps = 1) {}
                slide(steps = 1, weight = 2f) {}
            },
        )
        schedule.totalMillis shouldBe 10.minutes.inWholeMilliseconds.toDouble()
        schedule.millisOf(0)!! shouldBe (4.minutes.inWholeMilliseconds.toDouble() plusOrMinus 1e-6)
        schedule.millisOf(1)!! shouldBe (2.minutes.inWholeMilliseconds.toDouble() plusOrMinus 1e-6)
        schedule.millisOf(2)!! shouldBe (4.minutes.inWholeMilliseconds.toDouble() plusOrMinus 1e-6)
    }

    test("durations on every slide make the plan without a total") {
        val schedule = Schedule(
            presentation {
                slide(steps = 1, duration = 1.minutes) {}
                slide(steps = 1, duration = 2.minutes) {}
            },
        )
        schedule.totalMillis shouldBe 3.minutes.inWholeMilliseconds.toDouble()
        schedule.start(1) shouldBe (1.0 / 3 plusOrMinus 1e-9)
    }

    test("durations longer than the total stretch the bar to cover them") {
        val schedule = Schedule(
            presentation(duration = 1.minutes) {
                slide(steps = 1, duration = 2.minutes) {}
                slide(steps = 1) {}
            },
        )
        schedule.totalMillis shouldBe 2.minutes.inWholeMilliseconds.toDouble()
        schedule.millisOf(1)!! shouldBe (Duration.ZERO.inWholeMilliseconds.toDouble() plusOrMinus 1e-6)
    }
})
