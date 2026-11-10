package io.github.rosyidputrar.duobackup.data.settings

import io.github.rosyidputrar.duobackup.core.util.ExcludeMatcher

enum class Schedule { OFF, DAILY, EVERY_6H }

/** Everything the user can configure, with the defaults of a fresh install. */
data class Settings(
    val driveEnabled: Boolean = false,
    val telegramEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val minBatteryPct: Int = 20,
    val schedule: Schedule = Schedule.DAILY,
    /** Folders to back up, relative to the primary volume. "" is the whole volume. */
    val includeRoots: List<String> = listOf(""),
    val excludes: List<String> = ExcludeMatcher.DEFAULT_GLOBS,
    val encryptionEnabled: Boolean = false,
    val retentionDays: Int = 30,
    val snapshotKeep: Int = 10,
    /** Null until [SettingsRepository.installUuid] has generated it. */
    val installUuid: String? = null,
    /** Cache of Drive folder ids as JSON, re-resolved when a folder is gone. */
    val driveFolderIds: String? = null,
    val tgStorageChatId: Long? = null
)
