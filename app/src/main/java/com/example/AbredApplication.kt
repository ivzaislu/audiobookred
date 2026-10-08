package com.example

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.data.backup.UserDataImportManager
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.NormalizedLibraryBackfill
import com.example.data.maintenance.AppMaintenanceScheduler
import com.example.data.parser.AndroidLiveParserLocator
import com.example.data.settings.ExternalServiceCredentialsStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class AbredApplication : Application(), Configuration.Provider {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var userDataImportManager: Provider<UserDataImportManager>

    @Inject
    lateinit var normalizedLibraryBackfill: Provider<NormalizedLibraryBackfill>

    @Inject
    lateinit var externalServiceCredentialsStore: Provider<ExternalServiceCredentialsStore>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val startupStartedAt = SystemClock.elapsedRealtime()

        // Normalize the historical whole-library payload exactly once. The
        // migration marker and normalized rows commit atomically, so later
        // launches never read library:v1 as an authority again.
        val normalizedLibraryStartedAt = SystemClock.elapsedRealtime()
        migrateNormalizedLibraryOnce()
        logStartupStep("normalized-library-migration", normalizedLibraryStartedAt)

        // A restore can span SharedPreferences and Room, which cannot share one
        // transaction. Keep crash recovery synchronous only when an AtomicFile
        // journal proves the previous process died mid-restore; otherwise this
        // is a cheap existence check and constructs no Room/import graph.
        val restoreStartedAt = SystemClock.elapsedRealtime()
        recoverInterruptedUserDataRestore()
        logStartupStep("restore", restoreStartedAt)

        // Locator initialization stores only applicationContext and a lazy Hilt
        // credentials provider. Provider hub, credentials store and every real
        // parser remain lazy until first use.
        val parserStartedAt = SystemClock.elapsedRealtime()
        AndroidLiveParserLocator.initialize(
            context = this,
            externalServiceCredentialsStore = externalServiceCredentialsStore::get,
        )
        logStartupStep("parser-locator", parserStartedAt)

        scheduleDeferredStartupWork()
        logStartupStep("main-thread-total", startupStartedAt)
    }

    private fun scheduleDeferredStartupWork() {
        startupScope.launch {
            val maintenanceStartedAt = SystemClock.elapsedRealtime()
            runCatching {
                AppMaintenanceScheduler.enqueue(this@AbredApplication)
            }.onFailure { error ->
                Log.e(STARTUP_LOG_TAG, "Failed to enqueue deferred app maintenance", error)
            }
            logStartupStep("maintenance-enqueue", maintenanceStartedAt)
        }
    }

    private fun recoverInterruptedUserDataRestore() {
        if (!UserDataImportManager.hasPendingRestore(this)) return

        // Fail closed even if constructing the recovery graph itself fails. A
        // newly started playback service must not checkpoint partial restore
        // state over the durable recovery target.
        UserDataRestoreGate.blockPlaybackWrites()
        val result = runCatching {
            runBlocking(Dispatchers.IO) {
                userDataImportManager.get().recoverPendingRestore()
            }
        }
        result.onFailure { error ->
            Log.e(STARTUP_LOG_TAG, "Interrupted user-data restore recovery crashed", error)
        }
        if (result.getOrNull() == false) {
            Log.e(
                STARTUP_LOG_TAG,
                "Interrupted user-data restore could not be recovered; playback writes remain blocked",
            )
        }
    }

    private fun migrateNormalizedLibraryOnce() {
        // Keep the v7 -> v8 normalized-library bridge. After the first successful
        // run, the lightweight marker avoids constructing Room/migration objects
        // on normal cold starts.
        if (NormalizedLibraryBackfill.isMigrationComplete(this)) return

        runCatching {
            runBlocking(Dispatchers.IO) {
                normalizedLibraryBackfill.get().migrateOnce()
            }
        }.onFailure { error ->
            Log.e(STARTUP_LOG_TAG, "Normalized library migration failed", error)
        }
    }

    private fun logStartupStep(name: String, startedAt: Long) {
        if (!BuildConfig.DEBUG) return
        Log.i(STARTUP_LOG_TAG, "$name=${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    private companion object {
        const val STARTUP_LOG_TAG = "AbredStartup"
    }
}
