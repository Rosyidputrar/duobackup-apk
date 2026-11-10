package io.github.rosyidputrar.duobackup.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.engine.BackupSession
import io.github.rosyidputrar.duobackup.core.source.SourceFileSystem
import io.github.rosyidputrar.duobackup.core.util.Clock
import io.github.rosyidputrar.duobackup.core.util.SystemClock
import io.github.rosyidputrar.duobackup.data.db.CatalogDatabase
import io.github.rosyidputrar.duobackup.data.db.RoomCatalog
import io.github.rosyidputrar.duobackup.data.secrets.KeystoreAead
import io.github.rosyidputrar.duobackup.data.secrets.SecretStore
import io.github.rosyidputrar.duobackup.data.settings.SettingsRepository
import io.github.rosyidputrar.duobackup.storage.AndroidSourceFileSystem
import io.github.rosyidputrar.duobackup.work.BackupNotifications
import io.github.rosyidputrar.duobackup.work.BackupScheduler
import io.github.rosyidputrar.duobackup.work.PlaceholderBackupSession
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

/** A scope that lives as long as the process, for work that must outlive any screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun catalogDatabase(@ApplicationContext context: Context): CatalogDatabase =
        Room.databaseBuilder(context, CatalogDatabase::class.java, CatalogDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    @Singleton
    fun catalog(db: CatalogDatabase): Catalog = RoomCatalog(db)

    @Provides
    @Singleton
    @SettingsStore
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @SecretsStore
    fun secretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }

    @Provides
    @Singleton
    fun settingsRepository(@SettingsStore store: DataStore<Preferences>): SettingsRepository = SettingsRepository(store)

    @Provides
    @Singleton
    fun secretStore(@SecretsStore store: DataStore<Preferences>, @ApplicationContext context: Context): SecretStore =
        SecretStore(store) { KeystoreAead.create(context) }

    @Provides
    fun clock(): Clock = SystemClock

    @Provides
    @Singleton
    fun sourceFileSystem(@ApplicationContext context: Context): SourceFileSystem = AndroidSourceFileSystem(context)

    @Provides
    fun backupSession(): BackupSession = PlaceholderBackupSession()

    @Provides
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun backupScheduler(workManager: WorkManager): BackupScheduler = BackupScheduler(workManager)

    @Provides
    fun backupNotifications(@ApplicationContext context: Context): BackupNotifications = BackupNotifications(context)
}
