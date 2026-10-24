package com.podclatch.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import android.util.Log
import androidx.work.WorkManager
import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.MIGRATION_1_2
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.network.TokenGuardInterceptor
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.DataStoreSettingsRepository
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.Clock
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FileDeleter
import com.podclatch.domain.AutoDeleteSweep
import com.podclatch.domain.AutoDownloadTopUp
import com.podclatch.domain.LoadedEpisodeSource
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.NewEpisodeNotificationStep
import com.podclatch.domain.NewEpisodeNotifier
import com.podclatch.domain.OrderedLocalUpkeep
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
import com.podclatch.download.Downloads
import com.podclatch.download.PartialDownloadCleaner
import com.podclatch.work.AndroidNewEpisodeNotifier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun cacheStore(impl: RoomCacheStore): CacheStore
    @Binds abstract fun fileDeleter(impl: DiskFileDeleter): FileDeleter
    @Binds abstract fun newEpisodeNotifier(impl: AndroidNewEpisodeNotifier): NewEpisodeNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides @Singleton
    fun okHttpClient(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(Config.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // POST /v1/refresh answers only after the refresh ran (up to 60 s), so the default 10 s read
        // timeout would cut it short; the whole-call timeout above still bounds every request.
        .readTimeout(Config.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor(TokenGuardInterceptor(context.isDebuggable()))
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PodClatchDatabase =
        Room.databaseBuilder(context, PodClatchDatabase::class.java, Config.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { context.settingsFile() }

    @Provides @Singleton
    fun urlValidator(@ApplicationContext context: Context): ServerUrlValidator =
        ServerUrlValidator(isDebuggable = context.isDebuggable())

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    /**
     * The local upkeep after every successful pull, as an ordered list of steps: the auto-delete sweep (US-15),
     * then the top-up. A failing step is logged by name and exception class only.
     */
    @Provides @Singleton
    fun localUpkeep(
        store: CacheStore,
        settings: SettingsRepository,
        downloads: Downloads,
        notifier: NewEpisodeNotifier,
        clock: Clock,
        playback: com.podclatch.playback.PlaybackController,
    ): LocalUpkeep = OrderedLocalUpkeep(
        steps = listOf(
            "auto-delete sweep" to AutoDeleteSweep(
                store, settings, downloads, LoadedEpisodeSource { playback.state.value.episodeId }, clock,
            ),
            "top-up" to AutoDownloadTopUp(store, settings, downloads),
            "new-episode notification" to NewEpisodeNotificationStep(store, settings, notifier),
        ),
        onStepFailed = { step, errorClass -> Log.w("PodClatchSync", "Upkeep step $step failed: $errorClass") },
    )

    @Provides @Singleton
    fun syncEngine(
        api: PodClatchApi,
        settings: SettingsRepository,
        store: CacheStore,
        upkeep: LocalUpkeep,
        clock: Clock,
        tokenRejection: TokenRejection,
        fileDeleter: FileDeleter,
        @IoDispatcher io: CoroutineDispatcher,
        partials: PartialDownloadCleaner,
    ): SyncEngine =
        SyncEngine(api, settings, store, upkeep, clock, tokenRejection, fileDeleter, io, partials = partials)

    @Provides @Singleton
    fun connectionRepository(
        api: PodClatchApi,
        settings: SettingsRepository,
        store: CacheStore,
        engine: SyncEngine,
        tokenRejection: TokenRejection,
        fileDeleter: FileDeleter,
        validator: ServerUrlValidator,
        @IoDispatcher io: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): ConnectionRepository =
        ConnectionRepository(api, settings, store, engine, tokenRejection, fileDeleter, validator, io, scope)
}

private fun Context.isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

private fun Context.settingsFile() = java.io.File(filesDir, "datastore/podclatch_settings.preferences_pb")
