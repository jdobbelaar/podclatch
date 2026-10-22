package com.podclatch.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.podclatch.Config
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

internal const val KEY_EPISODE_ID = "episodeId"
internal const val KEY_MANUAL = "manual"

internal fun downloadWorkName(episodeId: Long) = "${Config.DOWNLOAD_WORK_NAME_PREFIX}$episodeId"

/** One unique WorkManager job per Episode. The input holds the id and the request kind, never an address. */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    private val workManager: dagger.Lazy<WorkManager>,
) : DownloadScheduler {
    override suspend fun state(episodeId: Long): JobState? =
        workManager.get().getWorkInfosForUniqueWork(downloadWorkName(episodeId)).await()
            .firstNotNullOfOrNull { info ->
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> JobState.WAITING
                    WorkInfo.State.RUNNING -> JobState.RUNNING
                    else -> null
                }
            }

    override suspend fun enqueue(episodeId: Long, network: NetworkType, manual: Boolean, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(workDataOf(KEY_EPISODE_ID to episodeId, KEY_MANUAL to manual))
            .build()
        workManager.get().enqueueUniqueWork(
            downloadWorkName(episodeId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        ).result.await()
    }

    override fun cancel(episodeId: Long) {
        workManager.get().cancelUniqueWork(downloadWorkName(episodeId))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerEntryPoint {
    fun engine(): DownloadEngine
}

/**
 * Thin wrapper: runs [DownloadEngine.run] and always reports success. A failure is recorded in the database
 * (FAILED) and retried by the next Sync's top-up, never by WorkManager's own back-off. When the system
 * stops the worker the coroutine is cancelled, the engine keeps the partial file and DOWNLOADING, and
 * WorkManager runs the job again, which continues from the partial file.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (id < 0) return Result.success()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val engine = EntryPointAccessors.fromApplication(applicationContext, DownloadWorkerEntryPoint::class.java).engine()
        engine.run(id, manual)
        return Result.success()
    }
}
