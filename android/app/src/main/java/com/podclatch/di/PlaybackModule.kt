package com.podclatch.di

import com.podclatch.data.db.CacheStore
import com.podclatch.playback.EpisodeMediaResolver
import com.podclatch.playback.MediaControllerPlaybackController
import com.podclatch.playback.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindingsModule {
    @Binds abstract fun playbackController(impl: MediaControllerPlaybackController): PlaybackController
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    @Provides @Singleton
    fun episodeMediaResolver(store: CacheStore, @IoDispatcher io: CoroutineDispatcher): EpisodeMediaResolver =
        EpisodeMediaResolver(
            lookup = store::episodeForPlayback,
            fileUsable = { path -> File(path).let { it.isFile && it.length() > 0 } },
            fileDispatcher = io,
        )
}
