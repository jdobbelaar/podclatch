package com.podclatch.di

import com.podclatch.data.network.OkHttpPodClatchApi
import com.podclatch.data.network.PodClatchApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Release builds bind the HTTP client directly; the debug build has a test seam here instead (`src/debug`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiBindingModule {
    @Binds abstract fun api(impl: OkHttpPodClatchApi): PodClatchApi
}
