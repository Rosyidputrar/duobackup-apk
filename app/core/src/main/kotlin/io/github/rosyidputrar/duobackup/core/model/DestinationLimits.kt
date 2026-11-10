package io.github.rosyidputrar.duobackup.core.model

/**
 * Limits of one destination, detected at runtime at the start of a session.
 *
 * @property maxFileBytes largest single remote file the destination accepts
 * @property captionMax caption budget in UTF-16 code units (Telegram); unused by Drive
 * @property quotaFreeBytes remaining quota, or null when unknown or unlimited
 * @property isPremium Telegram Premium state, or null when not applicable
 */
data class DestinationLimits(
    val maxFileBytes: Long,
    val captionMax: Int,
    val quotaFreeBytes: Long? = null,
    val isPremium: Boolean? = null
)
