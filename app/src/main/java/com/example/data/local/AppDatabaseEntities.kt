package com.example.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "cached_payloads", primaryKeys = ["cacheKey"])
data class CachedPayloadEntity(
    val cacheKey: String,
    val payloadJson: String,
    val savedAtMs: Long = System.currentTimeMillis()
)

/** Minimal per-book metadata retained independently from screen payload caches. */
@Entity(tableName = "local_books")
data class LocalBookEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val cardJson: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Profile/device references are deliberately separate from catalog membership.
 * A book may leave the latest-N catalog window and still remain locally known
 * because progress/history/favorite/bookmark/download state points at it.
 */
@Entity(tableName = "book_retention")
data class BookRetentionEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val favoriteRef: Boolean = false,
    val historyRef: Boolean = false,
    val progressRef: Boolean = false,
    val bookmarkRef: Boolean = false,
    val downloadedRef: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val retained: Boolean
        get() = favoriteRef || historyRef || progressRef || bookmarkRef || downloadedRef
}

@Entity(
    tableName = "catalog_window",
    indices = [Index(value = ["rank"], unique = true)]
)
data class CatalogWindowEntity(
    @androidx.room.PrimaryKey val bookId: String,
    /** Zero-based position in the newest-first server catalog snapshot. */
    val rank: Int
)

@Entity(tableName = "catalog_cache_state")
data class CatalogCacheStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val maxItems: Int = 1_000,
    val serverTotal: Int = 0,
    val lastSyncedAtMs: Long = 0L
)


@Entity(
    tableName = "sync_entities",
    primaryKeys = ["entityType", "entityId"],
    indices = [Index(value = ["revision"])]
)
data class SyncEntityEntity(
    val entityType: String,
    val entityId: String,
    val revision: Long = 0L,
    val operation: String = "upsert",
    val payloadJson: String = "{}",
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** One coalesced pending mutation per durable sync entity. */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["mutationId"], unique = true),
        Index(value = ["createdAtMs"]),
        Index(value = ["blocked"])
    ]
)
data class SyncOutboxEntity(
    @androidx.room.PrimaryKey val entityKey: String,
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val operation: String = "upsert",
    val baseRevision: Long = 0L,
    val payloadJson: String = "{}",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val blocked: Boolean = false,
    val lastError: String = ""
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val profileId: String = "",
    val cursor: Long = 0L,
    val snapshotComplete: Boolean = false,
    val lastAttemptAtMs: Long = 0L,
    val lastSyncedAtMs: Long = 0L,
    val lastPullAtMs: Long = 0L,
    val lastPushAtMs: Long = 0L,
    val lastWorkerAtMs: Long = 0L,
    val lastWorkerResult: String = "",
    val lastError: String = ""
)

@Entity(
    tableName = "download_books",
    indices = [Index(value = ["bookId"]), Index(value = ["state"])]
)
data class DownloadBookEntity(
    @androidx.room.PrimaryKey val bookSourceId: String,
    val bookId: String,
    val sourceCode: String,
    val sourceName: String,
    val title: String,
    val coverUrl: String = "",
    val manifestId: String,
    val state: String = "queued",
    val totalSizeBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val filesCount: Int = 0,
    val completedFiles: Int = 0,
    val wifiOnly: Boolean = true,
    val error: String = "",
    val deletedAtMs: Long? = null,
    val purgeAfterMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "library_trash",
    primaryKeys = ["kind", "targetId"],
    indices = [Index(value = ["purgeAfterMs"])]
)
data class LibraryTrashEntity(
    val kind: String,
    val targetId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val payloadJson: String = "",
    val deletedAtMs: Long,
    val purgeAfterMs: Long,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_files",
    primaryKeys = ["bookSourceId", "fileId"],
    indices = [Index(value = ["bookSourceId"]), Index(value = ["state"])]
)
data class DownloadFileEntity(
    val bookSourceId: String,
    val fileId: String,
    val chapterId: String,
    val chapterPosition: Int,
    val title: String,
    val durationSeconds: Long = 0L,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Long? = null,
    val downloadUrl: String,
    val localPath: String,
    val downloadedBytes: Long = 0L,
    val state: String = "queued",
    val error: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)
