package io.github.rosyidputrar.duobackup.core

import io.github.rosyidputrar.duobackup.core.model.DestinationLimits

/** One part of a content: `length` plaintext bytes starting at `offset`. */
data class PartSpan(val index: Int, val offset: Long, val length: Long)

/** Part layout and stored-size math (plan §4.2, §4.6). */
object PartSizing {
    private const val HEADER = 21L
    private const val FRAME_OVERHEAD = 20L

    /** Bytes a part of `plain` plaintext bytes occupies remotely. */
    fun storedSize(plain: Long, encrypted: Boolean): Long {
        require(plain >= 0) { "negative size" }
        if (!encrypted) return plain
        val frames = maxOf(1L, (plain + Limits.MIB - 1) / Limits.MIB)
        return HEADER + plain + FRAME_OVERHEAD * frames
    }

    /**
     * Largest multiple of 1 MiB whose stored size fits `limitBytes` with a 1 MiB margin.
     * Throws when the limit leaves no room for even a 1 MiB part.
     */
    fun maxPlainPartSize(limitBytes: Long, encrypted: Boolean): Long {
        val budget = limitBytes - Limits.MIB
        val mebibytes = if (encrypted) {
            (budget - HEADER) / (Limits.MIB + FRAME_OVERHEAD)
        } else {
            budget / Limits.MIB
        }
        require(budget > 0 && mebibytes >= 1) { "destination limit of $limitBytes bytes is too small" }
        return mebibytes * Limits.MIB
    }

    /** Part size for a new content given the limits of every enabled destination. */
    fun choose(limits: List<DestinationLimits>, encrypted: Boolean): Long {
        require(limits.isNotEmpty()) { "no destination limits" }
        val smallest = limits.minOf { it.maxFileBytes }
        return minOf(Limits.TARGET_PART_SIZE, maxPlainPartSize(smallest, encrypted))
    }

    /** Splits `size` bytes into parts of `partSize`. An empty file has no parts. */
    fun layout(size: Long, partSize: Long): List<PartSpan> {
        require(size >= 0) { "negative size" }
        require(partSize > 0) { "part size must be positive" }
        val count = (size + partSize - 1) / partSize
        require(count <= Int.MAX_VALUE) { "too many parts" }
        return List(count.toInt()) { i ->
            val offset = i * partSize
            PartSpan(i, offset, minOf(partSize, size - offset))
        }
    }
}
