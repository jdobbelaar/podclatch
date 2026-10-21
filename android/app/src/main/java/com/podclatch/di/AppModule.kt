package com.podclatch.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.work.WorkManager
import com.podclatch.Config
import com.podclatch.data.db.CacheStore
import com.podclatch.data.db.PodClatchDatabase
import com.podclatch.data.db.RoomCacheStore
import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.PodClatchApi
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.DataStoreSettingsRepository
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.Clock
import com.podclatch.domain.DiskFileDeleter
import com.podclatch.domain.FileDeleter
import com.podclatch.domain.LocalUpkeep
import com.podclatch.domain.NoOpLocalUpkeep
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.TokenRejection
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
    @Binds abstract fun api(impl: OkHttpPodClatchApi): PodClatchApi
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun cacheStore(impl: RoomCacheStore): CacheStore
    @Binds abstract fun fileDeleter(impl: DiskFileDeleter): FileDeleter

    /** The local-upkeep hook; US-14 and US-15 replace this binding with their implementation. */
    @Binds abstract fun localUpkeep(impl: NoOpLocalUpkeep): LocalUpkeep
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
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(Config.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // POST /v1/refresh answers only after the refresh ran (up to 60 s), so the default 10 s read
        // timeout would cut it short; the whole-call timeout above still bounds every request.
        .readTimeout(Config.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PodClatchDatabase =
        Room.databaseBuilder(context, PodClatchDatabase::class.java, Config.DATABASE_NAME).build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { context.settingsFile() }

    @Provides @Singleton
    fun urlValidator(@ApplicationContext context: Context): ServerUrlValidator =
        ServerUrlValidator(isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

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
    ): SyncEngine = SyncEngine(api, settings, store, upkeep, clock, tokenRejection, fileDeleter, io)

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

private fun Context.settingsFile() = java.io.File(filesDir, "datastore/podclatch_settings.preferences_pb")
