package com.podclatch.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.podclatch.Config
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The repeat interval for [hours]: a value below 1 means the default (1 hour), and the result is never
 * below WorkManager's 15-minute minimum.
 */
fun periodicIntervalMinutes(hours: Int): Long {
    val effective = if (hours < 1) Config.DEFAULT_SYNC_INTERVAL_HOURS else hours
    return maxOf(effective * 60L, Config.MIN_PERIODIC_INTERVAL_MINUTES)
}

/** Enqueues the unique periodic Sync job; `UPDATE` replaces the old schedule and never duplicates it. */
@Singleton
class SyncScheduler @Inject constructor(private val workManager: dagger.Lazy<WorkManager>) {
    fun schedule(hours: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(periodicIntervalMinutes(hours), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.get().enqueueUniquePeriodicWork(
            Config.PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun syncEngine(): SyncEngine
}

/**
 * Runs `sync(PERIODIC)` and reports success whatever the outcome: a failed Sync is recorded in the sync
 * state and retried at the next period. Dependencies come from a Hilt EntryPoint (no hilt-work).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
        entryPoint.syncEngine().sync(SyncTrigger.PERIODIC)
        return Result.success()
    }
}
