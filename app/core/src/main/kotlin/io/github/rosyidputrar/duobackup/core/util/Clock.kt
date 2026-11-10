package io.github.rosyidputrar.duobackup.core.util

/** Source of wall-clock time in epoch milliseconds, injectable for tests. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
