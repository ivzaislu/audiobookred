package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Target normalized Room contract for the user-owned library.
 *
 * These entities are registered in [AbredDatabase] schema version 8. Migration
 * 7 -> 8 creates their tables; a one-time startup bridge seeds upgrades from
 * cached_payloads['library:v1']. After its marker commits, runtime reads and
 * mutations use normalized rows exclusively.
 *
 * Book metadata is not duplicated here: [LocalBookEntity] remains the canonical
 * retained card store. Normalized Library rows directly retain their referenced
 * cards, while [BookRetentionEntity] carries the independent progress/bookmark/
 * download lifecycle references used by pruning and storage accounting.
 */
@Entity(
    tableName = "library_favorites",
    indices = [Index(value = ["rank"])],
)
data class LibraryFavoriteEntity(
    @PrimaryKey val bookId: String,
    /**
     * Zero-based display order. Lower values are shown first.
     *
     * The legacy library snapshot prepends newly favorited books, so rank is
     * required to preserve exact ordering during library:v1 migration.
     */
    val rank: Int,
    /** Best-effort timestamp. Legacy migration may use 0 when no source timestamp exists. */
    val addedAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "library_history",
    indices = [
        Index(value = ["rank"]),
        Index(value = ["lastPlayedAtMs"]),
    ],
)
data class LibraryHistoryEntity(
    @PrimaryKey val bookId: String,
    /**
     * Zero-based display order. Lower values are shown first.
     *
     * History currently moves the most recently started book to the front, so
     * rank preserves the exact legacy order while lastPlayedAtMs becomes the
     * natural mutation timestamp for normalized writes.
     */
    val rank: Int,
    val lastPlayedAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "library_series",
    indices = [Index(value = ["rank"])],
)
data class LibrarySeriesEntity(
    /**
     * Stable identity used by the current runtime:
     * provider.lowercase() + ':' + (externalId if present, otherwise name.lowercase()).
     */
    @PrimaryKey val seriesKey: String,
    val id: String,
    val name: String,
    val provider: String,
    val externalId: String,
    val sourceName: String,
    /** Zero-based display order. Lower values are shown first. */
    val rank: Int,
    val availableCount: Int,
    val totalCount: Int,
    val completedCount: Int,
    val inProgressCount: Int,
    val notStartedCount: Int,
    val status: String,
    /**
     * Preserved verbatim for backup/UI compatibility with MySeriesDto.
     * New runtime writes may continue using the existing millisecond string.
     */
    val lastActivityAt: String,
    val currentBookId: String?,
    val currentPosition: Double?,
    val currentState: String?,
    val currentProgressPercent: Double?,
    val nextBookId: String?,
    val nextPosition: Double?,
    val nextState: String?,
    val nextProgressPercent: Double?,
    val updatedAtMs: Long,
)

/**
 * Keep one identity function for migration and normalized runtime writes so
 * series rows cannot diverge between import, playback updates and UI removal.
 */
internal fun normalizedLibrarySeriesKey(
    provider: String,
    externalId: String,
    name: String,
): String = buildString {
    append(provider.lowercase())
    append(':')
    append(externalId.ifBlank { name.lowercase() })
}

/** Map one series DTO into its normalized Room row. */
internal fun com.example.data.model.MySeriesDto.toNormalizedLibrarySeriesEntity(
    rank: Int,
    fallbackUpdatedAtMs: Long,
): LibrarySeriesEntity {
    val activityTimestamp = lastActivityAt.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?: fallbackUpdatedAtMs.coerceAtLeast(0L)
    return LibrarySeriesEntity(
        seriesKey = normalizedLibrarySeriesKey(provider, externalId, name),
        id = id,
        name = name,
        provider = provider,
        externalId = externalId,
        sourceName = sourceName,
        rank = rank,
        availableCount = availableCount,
        totalCount = totalCount,
        completedCount = completedCount,
        inProgressCount = inProgressCount,
        notStartedCount = notStartedCount,
        status = status,
        lastActivityAt = lastActivityAt,
        currentBookId = currentBook?.book?.id,
        currentPosition = currentBook?.position,
        currentState = currentBook?.state,
        currentProgressPercent = currentBook?.progressPercent,
        nextBookId = nextBook?.book?.id,
        nextPosition = nextBook?.position,
        nextState = nextBook?.state,
        nextProgressPercent = nextBook?.progressPercent,
        updatedAtMs = activityTimestamp,
    )
}
