package com.podclatch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.podclatch.data.network.artworkHttpClient
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.StartedActivityCounter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import com.podclatch.download.DownloadReconciler
import com.podclatch.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class PodClatchApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var downloadReconciler: DownloadReconciler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /**
     * The one Coil ImageLoader: a memory cache, a disk cache under `cacheDir`, and an OkHttp client that
     * has no interceptor (so no `Authorization` header). Coil's debug logger is never installed.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve(Config.IMAGE_CACHE_DIR).toOkioPath())
                .maxSizeBytes(Config.IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { artworkHttpClient() })) }
        .build()

    override fun onCreate() {
        super.onCreate()

        // Launch and foreground: an Activity-lifecycle counter, so a WorkManager-started process (which
        // has no Activity) and a rotation do not fire a Sync.
        val counter = StartedActivityCounter()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (counter.onStarted()) appScope.launch { syncEngine.sync(SyncTrigger.LAUNCH_OR_FOREGROUND) }
            }

            override fun onActivityStopped(activity: Activity) = counter.onStopped(activity.isChangingConfigurations)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Downloads: once per process start, bring the database and the disk back in line.
        appScope.launch { downloadReconciler.run() }

        // Periodic: enqueued now and again whenever the interval changes (UPDATE never duplicates it).
        appScope.launch {
            settings.settings.map { it.syncIntervalHours }.distinctUntilChanged().collect { syncScheduler.schedule(it) }
        }
    }
}
