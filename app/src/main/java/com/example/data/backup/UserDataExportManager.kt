package com.example.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.example.BuildConfig
import com.example.data.cache.AppCacheStore
import com.example.data.local.AbredDatabase
import com.example.data.local.LocalCacheStore
import com.example.data.local.NormalizedLibraryStore
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkDto
import com.example.data.model.ProgressResponse
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.BookSourcePreferenceStore
import com.example.data.settings.PROGRESS_SAVE_INTERVAL_SECONDS
import com.example.data.settings.PlayerSettings
import com.example.data.settings.PlayerSettingsStore
import com.example.data.settings.SourceAvailabilityStore
import com.example.data.storage.PublicAppDocumentStorage
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class UserDataExportManager @Inject constructor(
    @ApplicationContext context: Context,
    private val cacheStore: LocalCacheStore,
    private val normalizedLibraryStore: NormalizedLibraryStore,
    private val resumeStore: PlaybackResumeStore,
    private val sourcePreferenceStore: BookSourcePreferenceStore,
    private val settingsStore: PlayerSettingsStore,
    private val sourceAvailabilityStore: SourceAvailabilityStore,
) {
    data class ExportResult(
        val bytes: Int,
        val favoriteBooks: Int,
        val historyBooks: Int,
        val series: Int,
        val bookmarks: Int,
        val progressEntries: Int,
        val checkpoints: Int,
        val location: String? = null,
    )

    private data class RoomBackupSnapshot(
        val library: AppCacheStore.LibraryCache,
        val bookmarks: List<BookmarkDto>,
        val progress: List<BackupProgress>,
        val bookMetadata: List<BookCardDto>,
    )

    private val appContext = context.applicationContext
    private val database = AbredDatabase.get(appContext)
    private val publicDocumentStorage = PublicAppDocumentStorage(appContext)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val progressAdapter = moshi.adapter(ProgressResponse::class.java)
    private val backupAdapter = moshi.adapter(UserDataBackup::class.java).indent("  ")

    suspend fun exportToPublicBackup(): ExportResult = withContext(Dispatchers.IO) {
        val backup = buildBackup()
        val bytes = backupAdapter.toJson(backup).toByteArray(StandardCharsets.UTF_8)
        val stored = publicDocumentStorage.writeBackup(
            displayName = backupFileName(),
            mimeType = "application/json",
            bytes = bytes,
        )
        exportResult(backup, bytes, stored.relativePath)
    }

    private fun exportResult(
        backup: UserDataBackup,
        bytes: ByteArray,
        location: String?,
    ): ExportResult = ExportResult(
        bytes = bytes.size,
        favoriteBooks = backup.library.favorites.size,
        historyBooks = backup.library.history.size,
        series = backup.library.series.size,
        bookmarks = backup.bookmarks.size,
        progressEntries = backup.progress.size,
        checkpoints = backup.playbackCheckpoints.size,
        location = location,
    )

    private fun backupFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return "AudioBookRed-" + BuildConfig.VERSION_NAME + "-backup-" + timestamp + ".json"
    }

    internal suspend fun buildBackup(): UserDataBackup {
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val playbackEpoch = UserDataRestoreGate.capturePlaybackWriteEpoch()
                ?: throw IOException("Нельзя создать резервную копию во время восстановления данных")
            val checkpointSnapshot = resumeStore.backupSnapshots()
                .map(::toBackupCheckpoint)
                .sortedByDescending(BackupPlaybackCheckpoint::savedAtMs)
            val sourcePreferenceSnapshot = sourcePreferenceStore.snapshot()
            val settingsSnapshot = settingsStore.state.value
            val sourceAvailabilitySnapshot = sourceAvailabilityStore.snapshot()

            val roomSnapshot = database.withTransaction {
                readRoomSnapshot(checkpointSnapshot)
            }

            if (!UserDataRestoreGate.playbackWriteEpochAllowed(playbackEpoch)) {
                throw IOException("Состояние восстановления изменилось во время создания резервной копии")
            }

            // The Room transaction happens entirely between these two preference
            // snapshots. If all three external stores are unchanged, the exported
            // combination corresponds to a state that actually existed while the
            // transaction was open instead of a fuzzy per-key read assembled over time.
            if (checkpointSnapshot != resumeStore.backupSnapshots().map(::toBackupCheckpoint)
                    .sortedByDescending(BackupPlaybackCheckpoint::savedAtMs)
            ) {
                return@repeat
            }
            if (sourcePreferenceSnapshot != sourcePreferenceStore.snapshot()) {
                return@repeat
            }
            if (settingsSnapshot != settingsStore.state.value) {
                return@repeat
            }
            if (sourceAvailabilitySnapshot != sourceAvailabilityStore.snapshot()) {
                return@repeat
            }

            val bookIds = linkedSetOf<String>().apply {
                addAll(roomSnapshot.library.favorites.map(BookCardDto::id))
                addAll(roomSnapshot.library.history.map(BookCardDto::id))
                addAll(roomSnapshot.library.series.flatMap { series ->
                    listOfNotNull(series.currentBook?.book?.id, series.nextBook?.book?.id)
                })
                addAll(roomSnapshot.bookmarks.map(BookmarkDto::bookId))
                addAll(roomSnapshot.progress.map(BackupProgress::bookId))
                addAll(checkpointSnapshot.map(BackupPlaybackCheckpoint::bookId))
            }

            val sourcePreferences = bookIds
                .sorted()
                .mapNotNull { bookId ->
                    sourcePreferenceSnapshot[bookId]?.let { sourceCode -> bookId to sourceCode }
                }
                .toMap(linkedMapOf())

            return UserDataBackup(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                exportedAtMs = System.currentTimeMillis(),
                library = roomSnapshot.library,
                bookmarks = roomSnapshot.bookmarks,
                progress = roomSnapshot.progress,
                playbackCheckpoints = checkpointSnapshot,
                bookMetadata = roomSnapshot.bookMetadata,
                sourcePreferences = sourcePreferences,
                settings = BackupSettings.from(
                    value = settingsSnapshot,
                    enabledSources = sourceAvailabilitySnapshot,
                ),
            )
        }

        throw IOException("Данные изменяются слишком часто; повторите создание резервной копии")
    }

    private suspend fun readRoomSnapshot(
        checkpointSnapshot: List<BackupPlaybackCheckpoint>,
    ): RoomBackupSnapshot {
        val normalized = normalizedLibraryStore.readWithinTransaction()
        if (normalized.missingBookIds.isNotEmpty()) {
            throw IOException(
                "Нельзя создать резервную копию: отсутствует metadata для " +
                    normalized.missingBookIds.size + " элементов библиотеки"
            )
        }
        val library = normalized.library
        val bookmarks = cacheStore.readAllBookmarks()
        val progress = readProgress()
        val seriesBooks = library.series.flatMap { series ->
            listOfNotNull(series.currentBook?.book, series.nextBook?.book)
        }

        val bookIds = linkedSetOf<String>().apply {
            addAll(library.favorites.map(BookCardDto::id))
            addAll(library.history.map(BookCardDto::id))
            addAll(seriesBooks.map(BookCardDto::id))
            addAll(bookmarks.map(BookmarkDto::bookId))
            addAll(progress.map(BackupProgress::bookId))
            addAll(checkpointSnapshot.map(BackupPlaybackCheckpoint::bookId))
        }

        val cardsById = linkedMapOf<String, BookCardDto>()
        (library.favorites + library.history + seriesBooks).forEach { card ->
            cardsById[card.id] = card
        }

        val unresolvedBookIds = bookIds.filterNot(cardsById::containsKey)
        cardsById.putAll(cacheStore.readBookCards(unresolvedBookIds))

        // Older installs can retain a source-aware playback checkpoint after its
        // local_books row has already been pruned. Preserve metadata from the
        // still-local detail cache when available; backup export never goes to network.
        val checkpointSourceByBook = checkpointSnapshot
            .asSequence()
            .filter { checkpoint -> checkpoint.bookId.isNotBlank() }
            .distinctBy(BackupPlaybackCheckpoint::bookId)
            .associate { checkpoint -> checkpoint.bookId to checkpoint.sourceCode }
        unresolvedBookIds
            .asSequence()
            .filterNot(cardsById::containsKey)
            .forEach { bookId ->
                cacheStore.readBook(bookId, checkpointSourceByBook[bookId])
                    ?.asCard()
                    ?.let { card -> cardsById[bookId] = card }
            }

        return RoomBackupSnapshot(
            library = library,
            bookmarks = bookmarks.sortedWith(
                compareBy(BookmarkDto::bookId, BookmarkDto::chapterIndex, BookmarkDto::positionMs)
            ),
            progress = progress,
            bookMetadata = cardsById.values.sortedBy(BookCardDto::id),
        )
    }

    private suspend fun readProgress(): List<BackupProgress> = database.cachedPayloads()
        .allWithPrefix(PROGRESS_PREFIX)
        .mapNotNull { row ->
            val key = decodeProgressKey(row.cacheKey) ?: return@mapNotNull null
            val value = runCatching { progressAdapter.fromJson(row.payloadJson) }.getOrNull() ?: return@mapNotNull null
            BackupProgress(
                bookId = value.bookId.takeIf { it.isNotBlank() } ?: key.first,
                sourceCode = key.second,
                value = value,
            )
        }
        .distinctBy { "${it.bookId}\u0000${it.sourceCode.orEmpty()}" }
        .sortedWith(compareBy(BackupProgress::bookId, { it.sourceCode.orEmpty() }))

    private fun decodeProgressKey(cacheKey: String): Pair<String, String?>? {
        if (!cacheKey.startsWith(PROGRESS_PREFIX)) return null
        val encoded = cacheKey.removePrefix(PROGRESS_PREFIX)
        val separator = encoded.indexOf(':')
        if (separator < 0) return null
        return runCatching {
            val bookId = URLDecoder.decode(encoded.substring(0, separator), StandardCharsets.UTF_8.toString())
            val source = URLDecoder.decode(encoded.substring(separator + 1), StandardCharsets.UTF_8.toString())
                .takeIf { it.isNotBlank() }
            bookId to source
        }.getOrNull()?.takeIf { it.first.isNotBlank() }
    }

    private fun toBackupCheckpoint(value: PlaybackResumeStore.Snapshot) = BackupPlaybackCheckpoint(
        bookId = value.bookId,
        sourceCode = value.sourceCode,
        chapterId = value.chapterId,
        chapterIndex = value.chapterIndex,
        positionMs = value.positionMs,
        speed = value.speed,
        progressPercent = value.progressPercent,
        savedAtMs = value.savedAtMs,
    )

    private companion object {
        const val PROGRESS_PREFIX = "progress:"
        const val MAX_SNAPSHOT_ATTEMPTS = 3
    }
}
