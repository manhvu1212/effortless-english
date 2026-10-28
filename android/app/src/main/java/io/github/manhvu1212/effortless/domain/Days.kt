package io.github.manhvu1212.effortless.domain

import java.time.LocalDate

fun today(): Long = LocalDate.now().toEpochDay()

/** Consecutive study days ending today, or yesterday when today has no activity yet. */
fun currentStreak(activeDays: Collection<Long>, today: Long): Int {
    val days = activeDays.toSet()
    var day = if (today in days) today else today - 1
    var streak = 0
    while (day in days) {
        streak++
        day--
    }
    return streak
}

fun longestStreak(activeDays: Collection<Long>): Int {
    var best = 0
    var run = 0
    var previous: Long? = null
    for (day in activeDays.toSortedSet()) {
        run = if (previous != null && day == previous + 1) run + 1 else 1
        best = maxOf(best, run)
        previous = day
    }
    return best
}
