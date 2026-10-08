package com.example.data.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.download.AudiobookDownloadManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Durable startup recovery that is independent from any screen or ViewModel lifecycle. */
object AppMaintenanceScheduler {
    private const val STARTUP_WORK = "abred-app-startup-maintenance-v1"
    private const val STARTUP_DELAY_SECONDS = 4L

    fun enqueue(context: Context) {
        // Persist the request immediately, but keep its Hilt/Room/download graph
        // off the first-frame path. Cold-start logs showed this worker competing
        // with Compose startup; none of its housekeeping requires synchronous UI
        // visibility.
        val request = OneTimeWorkRequestBuilder<AppMaintenanceWorker>()
            .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            STARTUP_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

@HiltWorker
class AppMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: AudiobookDownloadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Current process-death recovery only. Historical migration/repair
            // work has been retired from normal startup maintenance.
            downloadManager.reconcileLifecycle()
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
