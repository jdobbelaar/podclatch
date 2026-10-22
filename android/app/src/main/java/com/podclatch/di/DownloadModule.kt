package com.podclatch.di

import com.podclatch.Config
import com.podclatch.domain.Clock
import com.podclatch.download.DownloadCoordinator
import com.podclatch.download.DownloadEngine
import com.podclatch.download.DownloadFileSystem
import com.podclatch.download.DownloadScheduler
import com.podclatch.download.DownloadStore
import com.podclatch.download.Downloads
import com.podclatch.download.ExternalDownloadFileSystem
import com.podclatch.download.PartialDownloadCleaner
import com.podclatch.download.RoomDownloadStore
import com.podclatch.download.WorkManagerDownloadScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The client that downloads audio from publishers. It never carries the bearer token. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttp

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadBindingsModule {
    @Binds abstract fun store(impl: RoomDownloadStore): DownloadStore
    @Binds abstract fun scheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler
    @Binds abstract fun fileSystem(impl: ExternalDownloadFileSystem): DownloadFileSystem
    @Binds abstract fun downloads(impl: DownloadCoordinator): Downloads
    @Binds abstract fun partialCleaner(impl: DownloadCoordinator): PartialDownloadCleaner
}

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    /**
     * Its own client, built from scratch: no interceptor at all (so no `Authorization`, and nothing shared
     * with the PodClatch Server client), redirects followed, and no whole-call timeout because a long
     * episode takes minutes.
     */
    @Provides @Singleton @DownloadHttp
    fun downloadHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.DOWNLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides @Singleton
    fun downloadEngine(
        store: DownloadStore,
        files: DownloadFileSystem,
        @DownloadHttp http: OkHttpClient,
        clock: Clock,
        @IoDispatcher io: CoroutineDispatcher,
    ): DownloadEngine = DownloadEngine(store, files, http, clock, io)
}
