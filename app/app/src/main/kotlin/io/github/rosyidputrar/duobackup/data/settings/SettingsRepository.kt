package io.github.rosyidputrar.duobackup.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** User settings in a Preferences DataStore. Missing or unreadable values fall back to defaults. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun setDriveEnabled(enabled: Boolean) = set { it[Keys.DRIVE_ENABLED] = enabled }

    suspend fun setTelegramEnabled(enabled: Boolean) = set { it[Keys.TELEGRAM_ENABLED] = enabled }

    suspend fun setWifiOnly(enabled: Boolean) = set { it[Keys.WIFI_ONLY] = enabled }

    suspend fun setChargingOnly(enabled: Boolean) = set { it[Keys.CHARGING_ONLY] = enabled }

    suspend fun setMinBatteryPct(percent: Int) = set { it[Keys.MIN_BATTERY_PCT] = percent.coerceIn(0, 100) }

    suspend fun setSchedule(schedule: Schedule) = set { it[Keys.SCHEDULE] = schedule.name }

    /** An empty selection means the whole volume, which is stored as `[""]`. */
    suspend fun setIncludeRoots(roots: List<String>) = set {
        val cleaned = roots.distinct()
        it[Keys.INCLUDE_ROOTS] = encodeList(if (cleaned.isEmpty() || "" in cleaned) listOf("") else cleaned)
    }

    suspend fun setExcludes(globs: List<String>) = set {
        it[Keys.EXCLUDES] = encodeList(globs.map(String::trim).filter(String::isNotEmpty).distinct())
    }

    suspend fun setEncryptionEnabled(enabled: Boolean) = set { it[Keys.ENCRYPTION_ENABLED] = enabled }

    suspend fun setRetentionDays(days: Int) = set { it[Keys.RETENTION_DAYS] = days.coerceAtLeast(1) }

    suspend fun setSnapshotKeep(count: Int) = set { it[Keys.SNAPSHOT_KEEP] = count.coerceAtLeast(1) }

    suspend fun setDriveFolderIds(json: String?) = set {
        if (json == null) it.remove(Keys.DRIVE_FOLDER_IDS) else it[Keys.DRIVE_FOLDER_IDS] = json
    }

    suspend fun setTgStorageChatId(chatId: Long?) = set {
        if (chatId == null) it.remove(Keys.TG_STORAGE_CHAT_ID) else it[Keys.TG_STORAGE_CHAT_ID] = chatId
    }

    /** The id of this install, generated on first use and stable afterwards. */
    suspend fun installUuid(): String {
        val updated = store.edit { prefs ->
            if (prefs[Keys.INSTALL_UUID] == null) prefs[Keys.INSTALL_UUID] = UUID.randomUUID().toString()
        }
        return checkNotNull(updated[Keys.INSTALL_UUID])
    }

    suspend fun current(): Settings = settings.first()

    private suspend fun set(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(change)
    }

    private fun read(prefs: Preferences): Settings {
        val defaults = Settings()
        return Settings(
            driveEnabled = prefs[Keys.DRIVE_ENABLED] ?: defaults.driveEnabled,
            telegramEnabled = prefs[Keys.TELEGRAM_ENABLED] ?: defaults.telegramEnabled,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: defaults.wifiOnly,
            chargingOnly = prefs[Keys.CHARGING_ONLY] ?: defaults.chargingOnly,
            minBatteryPct = prefs[Keys.MIN_BATTERY_PCT] ?: defaults.minBatteryPct,
            schedule = prefs[Keys.SCHEDULE]?.let { name -> Schedule.entries.firstOrNull { it.name == name } }
                ?: defaults.schedule,
            includeRoots = decodeList(prefs[Keys.INCLUDE_ROOTS]) ?: defaults.includeRoots,
            excludes = decodeList(prefs[Keys.EXCLUDES]) ?: defaults.excludes,
            encryptionEnabled = prefs[Keys.ENCRYPTION_ENABLED] ?: defaults.encryptionEnabled,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: defaults.retentionDays,
            snapshotKeep = prefs[Keys.SNAPSHOT_KEEP] ?: defaults.snapshotKeep,
            installUuid = prefs[Keys.INSTALL_UUID],
            driveFolderIds = prefs[Keys.DRIVE_FOLDER_IDS],
            tgStorageChatId = prefs[Keys.TG_STORAGE_CHAT_ID]
        )
    }

    private fun encodeList(values: List<String>): String = Json.encodeToString(listSerializer, values)

    private fun decodeList(text: String?): List<String>? = text?.let {
        try {
            Json.decodeFromString(listSerializer, it)
        } catch (e: SerializationException) {
            null
        }
    }

    private object Keys {
        val DRIVE_ENABLED = booleanPreferencesKey("drive_enabled")
        val TELEGRAM_ENABLED = booleanPreferencesKey("telegram_enabled")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val MIN_BATTERY_PCT = intPreferencesKey("min_battery_pct")
        val SCHEDULE = stringPreferencesKey("schedule")
        val INCLUDE_ROOTS = stringPreferencesKey("include_roots")
        val EXCLUDES = stringPreferencesKey("excludes")
        val ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val SNAPSHOT_KEEP = intPreferencesKey("snapshot_keep")
        val INSTALL_UUID = stringPreferencesKey("install_uuid")
        val DRIVE_FOLDER_IDS = stringPreferencesKey("drive_folder_ids")
        val TG_STORAGE_CHAT_ID = longPreferencesKey("tg_storage_chat_id")
    }

    private companion object {
        val listSerializer = ListSerializer(String.serializer())
    }
}
