package com.example.data.narration

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System installer returns control outside ABRed. This bounded work checks
 * package state after installation and deletes the private APK, even if the
 * user never reopens the settings screen. A cancelled install keeps the file.
 */
@HiltWorker
internal class RuVoiceApkCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val installer = RuVoiceReleaseInstaller(applicationContext)
        if (installer.cleanupInstalledApkIfReady()) return@withContext Result.success()
        if (!installer.pendingInstallationCleanup() || runAttemptCount >= 10) {
            return@withContext Result.success()
        }
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "abred-ruvoice-apk-install-cleanup"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RuVoiceApkCleanupWorker>()
                .setInitialDelay(45L, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
