package io.github.manhvu1212.effortless.domain

import kotlin.math.max
import kotlin.math.roundToInt

enum class Grade(val quality: Int) { AGAIN(0), HARD(3), GOOD(4), EASY(5) }

data class Sm2State(
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val lapses: Int = 0,
)

/** SM-2 behind Anki-style buttons. AGAIN sets the interval to 0: the card comes back in the same session. */
object Sm2 {
    const val MIN_EASE = 1.3

    fun review(state: Sm2State, grade: Grade): Sm2State {
        if (grade == Grade.AGAIN) {
            return state.copy(
                ease = max(MIN_EASE, state.ease - 0.2),
                intervalDays = 0,
                repetitions = 0,
                lapses = state.lapses + 1,
            )
        }
        val q = grade.quality
        val ease = max(MIN_EASE, state.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        val repetitions = state.repetitions + 1
        val interval = when (repetitions) {
            1 -> if (grade == Grade.EASY) 3 else 1
            2 -> when (grade) {
                Grade.HARD -> 3
                Grade.EASY -> 8
                else -> 6
            }
            else -> {
                val next = when (grade) {
                    Grade.HARD -> state.intervalDays * 1.2
                    Grade.EASY -> state.intervalDays * ease * 1.3
                    else -> state.intervalDays * ease
                }
                max(state.intervalDays + 1, next.roundToInt())
            }
        }
        return Sm2State(ease, interval, repetitions, state.lapses)
    }
}
