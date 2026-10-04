package com.example

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.data.backup.UserDataImportManager
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.AbredDatabase
import com.example.data.local.DownloadStore
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
    lateinit var downloadStore: Provider<DownloadStore>

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
        val normalizedLibraryReady = migrateNormalizedLibraryOnce()
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

        scheduleDeferredStartupWork(normalizedLibraryReady)
        logStartupStep("main-thread-total", startupStartedAt)
    }

    private fun scheduleDeferredStartupWork(normalizedLibraryReady: Boolean) {
        startupScope.launch {
            if (normalizedLibraryReady) {
                val purgeStartedAt = SystemClock.elapsedRealtime()
                purgeLegacyBoundedCatalogOnce()
                logStartupStep("legacy-catalog-purge", purgeStartedAt)

            }

            val maintenanceStartedAt = SystemClock.elapsedRealtime()
            runCatching {
                AppMaintenanceScheduler.enqueue(this@AbredApplication)
            }.onFailure { error ->
                Log.e(STARTUP_LOG_TAG, "Failed to enqueue deferred app maintenance", error)
            }
            logStartupStep("maintenance-enqueue", maintenanceStartedAt)

            // Existing private downloads can be large. Start durable lifecycle
            // maintenance first so a multi-gigabyte migration never delays recovery.
            val publicDownloadsStartedAt = SystemClock.elapsedRealtime()
            runCatching {
                downloadStore.get().migrateCompletedPrivateFilesToPublic()
            }.onFailure { error ->
                Log.e(STARTUP_LOG_TAG, "Completed download migration failed", error)
            }
            logStartupStep("public-download-migration", publicDownloadsStartedAt)
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

    private fun migrateNormalizedLibraryOnce(): Boolean {
        // After the first successful v8 startup, avoid constructing the migration
        // graph and opening Room on every normal cold start. The durable Room
        // marker is still checked whenever this lightweight marker is absent.
        if (NormalizedLibraryBackfill.isMigrationComplete(this)) return true

        return runCatching {
            runBlocking(Dispatchers.IO) {
                normalizedLibraryBackfill.get().migrateOnce()
            }
            true
        }.onFailure { error ->
            Log.e(STARTUP_LOG_TAG, "Normalized library migration failed", error)
        }.isSuccess
    }

    /**
     * Pre-standalone builds kept a manually filled 100..2000-book catalog
     * window. Standalone runtime no longer reads that window, so cleanup may run
     * after the first frame.
     *
     * Only the retired window/state rows are touched here. local_books and
     * book_retention remain under their normal runtime ownership: pruning them
     * from deferred startup work could race progress/bookmark/download retention
     * updates that intentionally do not use the whole-library mutex.
     */
    private suspend fun purgeLegacyBoundedCatalogOnce() {
        val prefs = getSharedPreferences(STANDALONE_MIGRATION_PREFS, MODE_PRIVATE)

        // The retired backend migrator used the same preference file. Its
        // completion bit has no reader anymore; remove only that obsolete key
        // while keeping the catalog-purge marker until the v9 schema cleanup.
        if (prefs.contains(LEGACY_SYNC_MIGRATION_MARKER)) {
            prefs.edit().remove(LEGACY_SYNC_MIGRATION_MARKER).apply()
        }

        if (prefs.getBoolean(LEGACY_CATALOG_PURGED, false)) return

        val result = runCatching {
            val sqlite = AbredDatabase.get(this).openHelper.writableDatabase
            sqlite.beginTransaction()
            try {
                sqlite.execSQL("DELETE FROM catalog_window")
                sqlite.execSQL(
                    "UPDATE catalog_cache_state SET serverTotal = 0, lastSyncedAtMs = 0 WHERE id = 1"
                )
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
        }
        result.onFailure { error ->
            Log.e(STARTUP_LOG_TAG, "Legacy bounded catalog purge failed", error)
        }
        if (result.isSuccess) {
            prefs.edit().putBoolean(LEGACY_CATALOG_PURGED, true).apply()
        }
    }

    private fun logStartupStep(name: String, startedAt: Long) {
        if (!BuildConfig.DEBUG) return
        Log.i(STARTUP_LOG_TAG, "$name=${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    private companion object {
        const val STANDALONE_MIGRATION_PREFS = "abred_standalone_migrations"
        const val LEGACY_CATALOG_PURGED = "legacy_bounded_catalog_purged_v1"
        const val LEGACY_SYNC_MIGRATION_MARKER = "legacy_sync_state_migrated_v1"
        const val STARTUP_LOG_TAG = "AbredStartup"
    }
}
