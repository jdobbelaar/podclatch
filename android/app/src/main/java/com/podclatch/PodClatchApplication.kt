package com.podclatch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.di.ApplicationScope
import com.podclatch.domain.StartedActivityCounter
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import com.podclatch.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PodClatchApplication : Application() {
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

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

        // Periodic: enqueued now and again whenever the interval changes (UPDATE never duplicates it).
        appScope.launch {
            settings.settings.map { it.syncIntervalHours }.distinctUntilChanged().collect { syncScheduler.schedule(it) }
        }
    }
}
