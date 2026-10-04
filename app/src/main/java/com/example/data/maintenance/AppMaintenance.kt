package com.example.data.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.download.AudiobookDownloadManager
import com.example.data.local.AbredDatabase
import com.example.data.local.CachedPayloadEntity
import com.example.data.local.ListeningStateStore
import com.example.data.local.LocalCacheStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
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

class BackendPreferenceResidueCleaner @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val payloads = AbredDatabase.get(appContext).cachedPayloads()

    suspend fun cleanupOnce(): Boolean {
        if (payloads.get(CLEANUP_MARKER_KEY) != null) return false

        val failed = LEGACY_BACKEND_PREFS.filterNot(appContext::deleteSharedPreferences)
        if (failed.isNotEmpty()) {
            throw IOException(
                "Failed to delete retired backend preferences: " + failed.joinToString()
            )
        }

        payloads.put(
            CachedPayloadEntity(
                cacheKey = CLEANUP_MARKER_KEY,
                payloadJson = "true",
                savedAtMs = System.currentTimeMillis(),
            )
        )
        return true
    }

    private companion object {
        const val CLEANUP_MARKER_KEY = "maintenance:backend-preferences-cleaned:v1"
        val LEGACY_BACKEND_PREFS = listOf(
            "abred_profile_identity",
            "abred_client",
            "audiobookred_playback_profile_switch",
        )
    }
}

@HiltWorker
class AppMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: AudiobookDownloadManager,
    private val cacheStore: LocalCacheStore,
    private val listeningStateStore: ListeningStateStore,
    private val backendPreferenceResidueCleaner: BackendPreferenceResidueCleaner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Current recovery always runs before historical one-time cleanup.
            // A failure in legacy repair must never prevent download lifecycle
            // reconciliation or cleanup of pending legacy trash rows.
            downloadManager.reconcileLifecycle()
            // selfapk has no delayed library trash lifecycle. Delete any legacy
            // 24-hour rows left by an older APK immediately on the next startup.
            cacheStore.purgeExpiredLibraryTrash(Long.MAX_VALUE)

            // Historical repairs are idempotent and durable-marker guarded.
            // Keeping them after current recovery means their failure can trigger
            // a retry without starving the maintenance work the current app needs.
            listeningStateStore.reconcileProgressToHistoryOnce()
            cacheStore.repairLibraryRetentionFromNormalizedOnce()

            // Retired backend/profile preference files have no current readers.
            backendPreferenceResidueCleaner.cleanupOnce()
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
