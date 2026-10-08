package com.example.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.BuildConfig
import com.example.data.local.AbredDatabase
import com.example.data.local.BookRetentionEntity
import com.example.data.local.CachedPayloadEntity
import com.example.data.local.LocalBookEntity
import com.example.data.local.normalizedLibraryBackfillRows
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkDto
import com.example.data.model.ProgressResponse
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.AppThemeMode
import com.example.data.settings.BookSourcePreferenceStore
import com.example.data.settings.PlayerSettings
import com.example.data.settings.PlayerSettingsStore
import com.example.data.settings.SourceAvailabilityStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restores user-owned state produced by [UserDataExportManager]. */
@Singleton
class UserDataImportManager @Inject constructor(
    @ApplicationContext context: Context,
    private val resumeStore: PlaybackResumeStore,
    private val sourcePreferenceStore: BookSourcePreferenceStore,
    private val settingsStore: PlayerSettingsStore,
    private val sourceAvailabilityStore: SourceAvailabilityStore,
) {
    data class ImportResult(
        val favoriteBooks: Int,
        val historyBooks: Int,
        val series: Int,
        val bookmarks: Int,
        val progressEntries: Int,
        val checkpoints: Int,
    )

    private data class BookmarksPayload(val items: List<BookmarkDto> = emptyList())

    private val appContext = context.applicationContext
    private val database = AbredDatabase.get(appContext)
    private val payloadDao = database.cachedPayloads()
    private val catalogDao = database.localCatalog()
    private val normalizedLibraryDao = database.normalizedLibrary()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val backupAdapter = moshi.adapter(UserDataBackup::class.java)
    private val bookmarksAdapter = moshi.adapter(BookmarksPayload::class.java)
    private val progressAdapter = moshi.adapter(ProgressResponse::class.java)
    private val bookCardAdapter = moshi.adapter(BookCardDto::class.java)
    private val restoreJournal = UserDataRestoreJournal(appContext)

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val backup = readAndValidate(uri)

        // Close the race with a still-running Media3 session before taking the
        // rollback image. A user-requested import must own its restore epoch;
        // overlapping imports cannot safely share one rollback snapshot/journal.
        if (!UserDataRestoreGate.tryBeginImportRestore()) {
            throw IllegalStateException("Восстановление пользовательских данных уже выполняется")
        }
        val previousResume: Map<String, Any?>
        val previousSources: Map<String, String>
        val previousSettings: PlayerSettings
        val previousEnabledSources: Set<String>
        try {
            previousResume = resumeStore.rawSnapshot()
            previousSources = sourcePreferenceStore.snapshot()
            previousSettings = settingsStore.state.value
            previousEnabledSources = sourceAvailabilityStore.snapshot()
        } catch (error: Exception) {
            UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
            throw error
        }

        val targetSettings = settingsFromBackup(backup.settings, previousSettings.themeMode)
        val targetEnabledSources = enabledSourcesFromBackup(
            value = backup.settings,
            fallback = previousEnabledSources,
        )
        val targetCheckpoints = checkpointsFromBackup(backup)

        if (!restoreJournal.writePrepared(backup)) {
            UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
            throw IOException("Не удалось создать журнал восстановления")
        }

        var roomCommitted = false
        try {
            applyRestorePreferences(
                backup = backup,
                targetCheckpoints = targetCheckpoints,
                targetSettings = targetSettings,
                targetEnabledSources = targetEnabledSources,
            )

            // Every Room mutation that belongs to the backup is one SQLite
            // transaction. Live point mutations use Room transactions too, so
            // SQLite serialization replaces the retired whole-library mutex.
            database.withTransaction {
                restoreRoomStateWithinTransaction(backup)
            }
            roomCommitted = true

            // Keep RESTORE_ACTIVE until the journal is either terminal or gone.
            // Otherwise a second import could claim the gate after the Room commit
            // and then have its PREPARED journal cleared by this import's cleanup.
            val terminalRecorded = restoreJournal.markCommitted(backup)
            val journalGone = restoreJournal.clear()
            if (!terminalRecorded && !journalGone) {
                throw IOException("Не удалось завершить журнал восстановления")
            }

            // The restore target and its crash journal are safe now. Advance the
            // epoch to invalidate preparations that overlapped restore IO, but keep
            // writes blocked until a new playback target is prepared afterwards.
            UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()

            ImportResult(
                favoriteBooks = backup.library.favorites.size,
                historyBooks = backup.library.history.size,
                series = backup.library.series.size,
                bookmarks = backup.bookmarks.size,
                progressEntries = backup.progress.size,
                checkpoints = backup.playbackCheckpoints.size,
            )
        } catch (cancelled: CancellationException) {
            handleImportFailure(
                backup = backup,
                previousResume = previousResume,
                previousSources = previousSources,
                previousSettings = previousSettings,
                previousEnabledSources = previousEnabledSources,
                roomCommitted = roomCommitted,
                error = cancelled,
                rollbackMessage = "Не удалось полностью откатить настройки после отмены импорта",
            )
            throw cancelled
        } catch (error: Exception) {
            handleImportFailure(
                backup = backup,
                previousResume = previousResume,
                previousSources = previousSources,
                previousSettings = previousSettings,
                previousEnabledSources = previousEnabledSources,
                roomCommitted = roomCommitted,
                error = error,
                rollbackMessage = "Не удалось полностью откатить настройки после ошибки импорта",
            )
            throw error
        }
    }

    /**
     * Rolls a PREPARED restore journal forward after process death.
     *
     * Room transactions are crash-atomic by SQLite. Re-applying the same backup
     * therefore converges both SharedPreferences and Room to one target whether
     * the previous process died before, during, or after the Room commit.
     */
    suspend fun recoverPendingRestore(): Boolean = withContext(Dispatchers.IO) {
        val entry = try {
            restoreJournal.read()
        } catch (_: Exception) {
            UserDataRestoreGate.blockPlaybackWrites()
            return@withContext false
        } ?: run {
            // Application may have fail-closed the gate after observing a journal
            // that disappeared before importer construction. With no recovery
            // target left, do not strand playback writes in the blocked state.
            UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
            return@withContext true
        }

        when (entry.state) {
            UserDataRestoreJournal.STATE_COMMITTED,
            UserDataRestoreJournal.STATE_ABORTED -> {
                // Terminal states are intentionally not replayed. The process that
                // produced them is gone, so there is no stale playback session to
                // guard anymore.
                restoreJournal.clear()
                UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
                return@withContext true
            }
        }

        UserDataRestoreGate.blockPlaybackWrites()
        val backup = entry.backup
        try {
            validateBackup(backup)
            val targetSettings = settingsFromBackup(backup.settings, settingsStore.state.value.themeMode)
            val targetEnabledSources = enabledSourcesFromBackup(
                value = backup.settings,
                fallback = sourceAvailabilityStore.snapshot(),
            )
            val targetCheckpoints = checkpointsFromBackup(backup)
            applyRestorePreferences(
                backup = backup,
                targetCheckpoints = targetCheckpoints,
                targetSettings = targetSettings,
                targetEnabledSources = targetEnabledSources,
            )
            database.withTransaction {
                restoreRoomStateWithinTransaction(backup)
            }

            // Recovery owns the same journal/gate invariant as an explicit import:
            // do not reopen the gate until this PREPARED record is terminal or gone.
            val terminalRecorded = restoreJournal.markCommitted(backup)
            val journalGone = restoreJournal.clear()
            if (!terminalRecorded && !journalGone) {
                return@withContext false
            }

            UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
            true
        } catch (_: Exception) {
            // PREPARED remains on disk. Playback writes stay blocked and the next
            // process start can retry the same deterministic roll-forward.
            false
        }
    }

    private fun applyRestorePreferences(
        backup: UserDataBackup,
        targetCheckpoints: List<PlaybackResumeStore.Snapshot>,
        targetSettings: PlayerSettings,
        targetEnabledSources: Set<String>,
    ) {
        if (!resumeStore.replaceForRestore(targetCheckpoints)) {
            throw IOException("Не удалось сохранить восстановленные позиции воспроизведения")
        }
        if (!sourcePreferenceStore.replaceAll(backup.sourcePreferences)) {
            throw IOException("Не удалось сохранить восстановленные источники")
        }
        if (!settingsStore.replace(targetSettings)) {
            throw IOException("Не удалось сохранить восстановленные настройки")
        }
        if (!sourceAvailabilityStore.replaceEnabled(targetEnabledSources)) {
            throw IOException("Не удалось сохранить восстановленные настройки источников")
        }
    }

    private fun handleImportFailure(
        backup: UserDataBackup,
        previousResume: Map<String, Any?>,
        previousSources: Map<String, String>,
        previousSettings: PlayerSettings,
        previousEnabledSources: Set<String>,
        roomCommitted: Boolean,
        error: Throwable,
        rollbackMessage: String,
    ) {
        if (roomCommitted) {
            // Room and target preferences are already coherent. Never roll only
            // SharedPreferences back after the SQLite commit. Keep ownership of
            // the restore gate until the crash journal is terminal or gone.
            val terminalRecorded = restoreJournal.markCommitted(backup)
            val journalGone = restoreJournal.clear()
            if (!terminalRecorded && !journalGone) {
                error.addSuppressed(IOException("Не удалось зафиксировать завершённое восстановление"))
                return
            }
            UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
            return
        }

        val rolledBack = rollbackPreferences(
            resume = previousResume,
            sources = previousSources,
            settings = previousSettings,
            enabledSources = previousEnabledSources,
        )
        if (!rolledBack) {
            // Keep PREPARED: startup recovery will converge partial preference
            // writes and rolled-back Room state to the requested backup.
            error.addSuppressed(IOException(rollbackMessage))
            return
        }

        // Persist ABORTED before deletion. If that terminal rewrite itself fails,
        // direct deletion is safe because both preferences and Room are already
        // back at the pre-import state.
        val terminalRecorded = restoreJournal.markAborted(backup)
        val journalGone = restoreJournal.clear()
        if (!terminalRecorded && !journalGone) {
            error.addSuppressed(IOException("Не удалось завершить журнал после отката импорта"))
            return
        }
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
    }

    private fun readAndValidate(uri: Uri): UserDataBackup {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть выбранный файл")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BACKUP_BYTES) {
                    throw IOException("Файл резервной копии слишком большой")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = bytes.toString(StandardCharsets.UTF_8)
        val backup = runCatching { backupAdapter.fromJson(json) }
            .getOrElse { error ->
                throw IOException("Не удалось прочитать резервную копию", error)
            }
            ?: throw IOException("Файл резервной копии пуст")

        validateBackup(backup)
        return backup
    }

    /**
     * Applies the Room-owned portion of one validated backup.
     * Caller must already own this database's Room transaction.
     */
    internal suspend fun restoreRoomStateWithinTransaction(backup: UserDataBackup) {
        val now = System.currentTimeMillis()
        val normalizedLibrary = normalizedLibraryBackfillRows(backup.library)
        val favoriteIds = normalizedLibrary.favorites.mapTo(linkedSetOf()) { it.bookId }
        val historyIds = normalizedLibrary.history.mapTo(linkedSetOf()) { it.bookId }
        val bookmarkIds = backup.bookmarks.mapTo(linkedSetOf(), BookmarkDto::bookId)
        val progressIds = linkedSetOf<String>().apply {
            addAll(backup.progress.map(BackupProgress::bookId))
            addAll(backup.playbackCheckpoints.map(BackupPlaybackCheckpoint::bookId))
        }
        val referencedIds = favoriteIds + historyIds + bookmarkIds + progressIds

        // User-facing screen caches may contain stale favorite/progress flags after a restore.
        // They are disposable, so invalidate them while preserving download tables and files.
        payloadDao.deleteDisposablePayloads()
        payloadDao.deleteProfilePayloads()
        catalogDao.clearProfileRefs(now)

        val seriesBooks = backup.library.series.flatMap { series ->
            listOfNotNull(series.currentBook?.book, series.nextBook?.book)
        }
        // Library-embedded cards are authoritative for Library members.
        // Generic book_metadata is fallback for bookmark/progress/checkpoint-only books.
        val cards = (backup.library.favorites + backup.library.history + seriesBooks + backup.bookMetadata)
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy(BookCardDto::id)
            .map { card ->
                val json = runCatching { bookCardAdapter.toJson(card) }
                    .getOrElse { error ->
                        throw IOException(
                            "Не удалось восстановить metadata книги " + card.id,
                            error,
                        )
                    }
                LocalBookEntity(bookId = card.id, cardJson = json, updatedAtMs = now)
            }
            .toList()
        if (cards.isNotEmpty()) catalogDao.upsertBooks(cards)

        normalizedLibraryDao.replaceFavorites(normalizedLibrary.favorites)
        normalizedLibraryDao.replaceHistory(normalizedLibrary.history)
        normalizedLibraryDao.replaceSeries(normalizedLibrary.series)
        // Restore writes normalized Library directly. Remove any legacy snapshot in
        // the same transaction so a later migration/backfill pass can never replay
        // stale library:v1 data over the restored normalized rows.
        payloadDao.delete(LEGACY_LIBRARY_KEY)
        val bookmarkRows = backup.bookmarks
            .filter { it.bookId.isNotBlank() }
            .groupBy(BookmarkDto::bookId)
            .map { (bookId, items) ->
                CachedPayloadEntity(
                    cacheKey = bookmarksKey(bookId),
                    payloadJson = bookmarksAdapter.toJson(BookmarksPayload(items.distinctBy(BookmarkDto::id))),
                    savedAtMs = now,
                )
            }
        if (bookmarkRows.isNotEmpty()) payloadDao.putAll(bookmarkRows)

        val progressRows = backup.progress
            .asSequence()
            .filter { it.bookId.isNotBlank() }
            .distinctBy { "${it.bookId}\u0000${it.sourceCode.orEmpty()}" }
            .map { entry ->
                CachedPayloadEntity(
                    cacheKey = progressKey(entry.bookId, entry.sourceCode),
                    payloadJson = progressAdapter.toJson(entry.value),
                    savedAtMs = now,
                )
            }
            .toList()
        if (progressRows.isNotEmpty()) payloadDao.putAll(progressRows)

        referencedIds.filter(String::isNotBlank).forEach { bookId ->
            val current = catalogDao.retention(bookId) ?: BookRetentionEntity(bookId = bookId)
            catalogDao.putRetention(
                current.copy(
                    favoriteRef = bookId in favoriteIds,
                    historyRef = bookId in historyIds,
                    progressRef = bookId in progressIds,
                    bookmarkRef = bookId in bookmarkIds,
                    updatedAtMs = now,
                )
            )
        }
        catalogDao.pruneUnreferencedBooks()
        catalogDao.pruneEmptyRetention()
    }

    private fun rollbackPreferences(
        resume: Map<String, Any?>,
        sources: Map<String, String>,
        settings: PlayerSettings,
        enabledSources: Set<String>,
    ): Boolean {
        val resumeOk = resumeStore.restoreRawSnapshot(resume)
        val sourceOk = sourcePreferenceStore.replaceAll(sources)
        val settingsOk = settingsStore.replace(settings)
        val availabilityOk = sourceAvailabilityStore.replaceEnabled(enabledSources)
        return resumeOk && sourceOk && settingsOk && availabilityOk
    }

    companion object {
        const val LEGACY_LIBRARY_KEY = "library:v1"
        private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

        fun hasPendingRestore(context: Context): Boolean = UserDataRestoreJournal.exists(context)
    }
}
