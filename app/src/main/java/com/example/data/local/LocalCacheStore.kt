package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.data.backup.UserDataRestoreGate
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.BookListResponse
import com.example.data.model.BookmarkDto
import com.example.data.model.MySeriesDto
import com.example.data.model.ProgressResponse
import com.example.data.model.SeriesDetailDto
import com.example.data.model.toDetailShell
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun mergeLibraryRetentionRows(
    existing: List<BookRetentionEntity>,
    favoriteIds: Set<String>,
    historyIds: Set<String>,
    now: Long,
): List<BookRetentionEntity> {
    val existingById = existing.associateBy(BookRetentionEntity::bookId)
    val orderedIds = buildList {
        addAll(favoriteIds)
        historyIds.forEach { id -> if (id !in favoriteIds) add(id) }
    }
    return orderedIds.map { bookId ->
        val current = existingById[bookId] ?: BookRetentionEntity(bookId = bookId)
        current.copy(
            favoriteRef = bookId in favoriteIds,
            historyRef = bookId in historyIds,
            updatedAtMs = now,
        )
    }
}

/**
 * Room-backed local read model for standalone APK state.
 *
 * Catalog/search/browse pages are disposable JSON payloads. The old normalized
 * bounded catalog window remains in the Room schema only so existing installs
 * can upgrade without a destructive database migration; runtime reads and writes
 * never use it as a catalog source anymore.
 */
class LocalCacheStore(context: Context) {
    /** Logical storage split used by settings. */
    data class StorageStats(
        val catalogBytes: Long,
        val profileBytes: Long,
        val favoriteBooks: Int,
        val historyBooks: Int,
        val progressBooks: Int,
        val bookmarkBooks: Int,
        val downloadedBooks: Int,
        val downloadedBytes: Long = 0L
    )

    private data class BookmarksPayload(val items: List<BookmarkDto> = emptyList())
    private data class BrowsePayload(val response: BookListResponse = BookListResponse())

    private val db = AbredDatabase.get(context)
    private val dao = db.cachedPayloads()
    private val catalogDao = db.localCatalog()
    private val normalizedLibraryDao = db.normalizedLibrary()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val bookAdapter = moshi.adapter(BookDetailDto::class.java)
    private val bookCardAdapter = moshi.adapter(BookCardDto::class.java)
    private val seriesAdapter = moshi.adapter(SeriesDetailDto::class.java)
    private val bookmarksAdapter = moshi.adapter(BookmarksPayload::class.java)
    private val browseAdapter = moshi.adapter(BrowsePayload::class.java)
    private val progressAdapter = moshi.adapter(ProgressResponse::class.java)
    suspend fun readBook(bookId: String, sourceCode: String? = null): BookDetailDto? {
        val exact = sourceCode?.takeIf { it.isNotBlank() }?.let { source ->
            read(bookKey(bookId, source), bookAdapter)
        }
        if (exact != null) return exact
        read(bookKey(bookId, DEFAULT_VARIANT), bookAdapter)?.let { return it }
        val row = dao.latestWithPrefix("book:${part(bookId)}:") ?: return null
        return runCatching { bookAdapter.fromJson(row.payloadJson) }.getOrNull()
    }

    suspend fun readBookCard(bookId: String): BookCardDto? {
        val row = catalogDao.book(bookId) ?: return null
        return runCatching { bookCardAdapter.fromJson(row.cardJson) }.getOrNull()
    }

    /** Resolve many retained book cards without an N+1 Room query pattern. */
    suspend fun readBookCards(bookIds: Collection<String>): Map<String, BookCardDto> {
        val ids = bookIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (ids.isEmpty()) return emptyMap()

        return ids.chunked(BOOK_CARD_QUERY_CHUNK_SIZE)
            .flatMap { chunk -> catalogDao.books(chunk) }
            .mapNotNull { row ->
                runCatching { bookCardAdapter.fromJson(row.cardJson) }
                    .getOrNull()
                    ?.let { card -> row.bookId to card }
            }
            .toMap()
    }

    suspend fun writeBook(value: BookDetailDto) {
        upsertCards(listOf(value.asCard()))
        val json = runCatching { bookAdapter.toJson(value) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val keys = buildList {
            add(bookKey(value.id, DEFAULT_VARIANT))
            value.selectedSource.takeIf { it.isNotBlank() }?.let { add(bookKey(value.id, it)) }
        }.distinct()
        dao.putAll(keys.map { CachedPayloadEntity(it, json, now) })
    }

    /**
     * Persist metadata-only detail fallbacks for one Paging page without an N+1
     * read/write loop. Existing full details always win.
     */
    suspend fun writeBookShellsIfAbsent(books: List<BookCardDto>) {
        val unique = books
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy(BookCardDto::id)
            .toList()
        if (unique.isEmpty()) return

        val candidateKeysByBook = unique.associate { book ->
            val sources = buildList {
                add(DEFAULT_VARIANT)
                book.primarySource.takeIf { it.isNotBlank() }?.let(::add)
                book.sourceCodes
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .forEach(::add)
            }.distinct()
            book.id to sources.map { source -> bookKey(book.id, source) }
        }
        val allCandidateKeys = candidateKeysByBook.values.flatten().distinct()
        val existingKeys = allCandidateKeys
            .chunked(CACHE_KEY_QUERY_CHUNK_SIZE)
            .flatMap { keys -> dao.existingKeys(keys) }
            .toHashSet()
        val missingBooks = unique.filter { book ->
            candidateKeysByBook.getValue(book.id).none(existingKeys::contains)
        }
        if (missingBooks.isEmpty()) return

        // A shell is only fallback metadata. Never let it overwrite a fresher
        // retained/favorite/full-detail card that arrived after the key query.
        insertCardsIfAbsent(missingBooks)

        val now = System.currentTimeMillis()
        val shells = missingBooks.mapNotNull { book ->
            val json = runCatching { bookAdapter.toJson(book.toDetailShell()) }.getOrNull()
                ?: return@mapNotNull null
            CachedPayloadEntity(
                cacheKey = bookKey(book.id, DEFAULT_VARIANT),
                payloadJson = json,
                savedAtMs = now,
            )
        }
        if (shells.isNotEmpty()) {
            // IGNORE closes the race where a real detail arrives between the
            // existing-key query and this insert.
            dao.putAllIfAbsent(shells)
        }
    }

    suspend fun readBookmarks(bookId: String): List<BookmarkDto>? =
        read(bookmarksKey(bookId), bookmarksAdapter)?.items

    private suspend fun writeBookmarks(bookId: String, value: List<BookmarkDto>) {
        markRetention(bookId) { current -> current.copy(bookmarkRef = value.isNotEmpty()) }
        write(bookmarksKey(bookId), BookmarksPayload(value), bookmarksAdapter)
    }

    suspend fun readAllBookmarks(): List<BookmarkDto> =
        dao.allWithPrefix("bookmarks:")
            .flatMap { row ->
                runCatching { bookmarksAdapter.fromJson(row.payloadJson)?.items.orEmpty() }.getOrDefault(emptyList())
            }
            .distinctBy(BookmarkDto::id)

    suspend fun readSimilarPage(bookId: String, page: Int): BookListResponse? =
        read(similarPageKey(bookId, page), browseAdapter)?.response

    suspend fun writeSimilarPage(bookId: String, page: Int, response: BookListResponse) {
        upsertCards(response.items)
        write(similarPageKey(bookId, page), BrowsePayload(response), browseAdapter)
    }

    suspend fun readSeries(cacheKey: String): SeriesDetailDto? = read("series:${part(cacheKey)}", seriesAdapter)

    suspend fun writeSeries(cacheKey: String, value: SeriesDetailDto) {
        upsertCards(value.books + value.entries.mapNotNull { it.book })
        write("series:${part(cacheKey)}", value, seriesAdapter)
    }

    /** Standalone catalog cache is page-based only; legacy catalog_window is never read. */
    suspend fun readCatalog(
        query: String,
        genreId: String?,
        source: String?,
        sourceAvailabilityKey: String,
        page: Int,
    ): BookListResponse? =
        read(
            catalogKey(query, genreId, source, sourceAvailabilityKey, page),
            browseAdapter,
        )?.response

    suspend fun writeCatalog(
        query: String,
        genreId: String?,
        source: String?,
        sourceAvailabilityKey: String,
        page: Int,
        response: BookListResponse,
    ) {
        write(
            catalogKey(query, genreId, source, sourceAvailabilityKey, page),
            BrowsePayload(response),
            browseAdapter,
        )
    }

    suspend fun readBrowse(kind: String, id: String, page: Int): BookListResponse? =
        read(browseKey(kind, id, page), browseAdapter)?.response

    suspend fun writeBrowse(kind: String, id: String, page: Int, response: BookListResponse) {
        write(browseKey(kind, id, page), BrowsePayload(response), browseAdapter)
    }

    suspend fun readProgress(bookId: String, sourceCode: String?): ProgressResponse? {
        return if (sourceCode.isNullOrBlank()) {
            read(progressKey(bookId, null), progressAdapter)
        } else {
            read(progressKey(bookId, sourceCode), progressAdapter)
        }
    }

    suspend fun writeProgress(
        bookId: String,
        sourceCode: String?,
        value: ProgressResponse,
        playbackWriteEpoch: Long? = UserDataRestoreGate.capturePlaybackWriteEpoch(),
    ) {
        val json = runCatching { progressAdapter.toJson(value) }.getOrNull() ?: return
        val key = progressKey(bookId, sourceCode)
        val meaningful = value.completed || value.positionMs > 0L || value.chapterIndex > 0
        val now = System.currentTimeMillis()

        // Keep the epoch validation in the same Room transaction as every
        // progress mutation. A checkpoint created before the latest restore can
        // therefore never become valid again after the gate is reopened.
        db.withTransaction {
            if (!UserDataRestoreGate.playbackWriteEpochAllowed(playbackWriteEpoch)) {
                return@withTransaction
            }
            if (meaningful) {
                val current = catalogDao.retention(bookId) ?: BookRetentionEntity(bookId = bookId)
                catalogDao.putRetention(
                    current.copy(
                        progressRef = true,
                        updatedAtMs = now,
                    )
                )
            }
            dao.put(CachedPayloadEntity(cacheKey = key, payloadJson = json, savedAtMs = now))
        }
    }

    suspend fun sourceCodeForBookSource(bookId: String, bookSourceId: String?): String? {
        if (bookSourceId.isNullOrBlank()) return null
        val detail = readBook(bookId) ?: return null
        if (detail.selectedBookSourceId == bookSourceId && detail.selectedSource.isNotBlank()) {
            return detail.selectedSource
        }
        return detail.sourceVariants.firstOrNull { it.bookSourceId == bookSourceId }?.sourceCode
    }

    suspend fun isFavoriteState(bookId: String): Boolean =
        normalizedLibraryDao.favorite(bookId) != null

    /** Set favorite membership through one normalized Room transaction. */
    suspend fun setFavoriteState(bookId: String, favorite: Boolean, card: BookCardDto? = null) {
        mutateFavoriteState(bookId, card) { favorite }
    }

    /** Atomically invert favorite membership without the whole-library mutex. */
    suspend fun toggleFavoriteState(bookId: String, card: BookCardDto? = null): Boolean =
        mutateFavoriteState(bookId, card) { current -> !current }

    private suspend fun mutateFavoriteState(
        bookId: String,
        card: BookCardDto?,
        transform: (Boolean) -> Boolean,
    ): Boolean {
        val now = System.currentTimeMillis()
        var updatedState = false
        db.withTransaction {
            val retention = catalogDao.retention(bookId) ?: BookRetentionEntity(bookId = bookId)
            val existingFavorite = normalizedLibraryDao.favorite(bookId)
            val currentState = existingFavorite != null
            val favorite = transform(currentState)
            updatedState = favorite

            val retainedCard = card
                ?: catalogDao.book(bookId)
                    ?.let { row -> runCatching { bookCardAdapter.fromJson(row.cardJson) }.getOrNull() }
            val candidate = retainedCard?.copy(isFavorite = favorite)

            if (candidate != null) {
                bookCardEntities(listOf(candidate)).firstOrNull()?.let {
                    catalogDao.upsertBooks(listOf(it))
                }
            }

            if (favorite) {
                if (existingFavorite == null) {
                    normalizedLibraryDao.shiftFavoritesForNewFront()
                } else {
                    normalizedLibraryDao.shiftFavoritesBefore(existingFavorite.rank)
                }
                normalizedLibraryDao.putFavorite(
                    LibraryFavoriteEntity(
                        bookId = bookId,
                        rank = 0,
                        addedAtMs = existingFavorite?.addedAtMs ?: now,
                        updatedAtMs = now,
                    )
                )
            } else if (existingFavorite != null) {
                normalizedLibraryDao.deleteFavorite(bookId)
                normalizedLibraryDao.compactFavoritesAfter(existingFavorite.rank)
            }

            catalogDao.putRetention(
                retention.copy(
                    favoriteRef = favorite,
                    updatedAtMs = now,
                )
            )

            dao.allWithPrefix("book:${part(bookId)}:").forEach { row ->
                val detail = runCatching { bookAdapter.fromJson(row.payloadJson) }.getOrNull()
                    ?: return@forEach
                val json = runCatching {
                    bookAdapter.toJson(detail.copy(isFavorite = favorite))
                }.getOrNull() ?: return@forEach
                dao.put(CachedPayloadEntity(row.cacheKey, json, now))
            }

            if (!favorite) {
                catalogDao.pruneUnreferencedBooks()
                catalogDao.pruneEmptyRetention()
            }
        }
        return updatedState
    }

    /** Move one book to the front of local playback history. */
    suspend fun recordHistoryState(card: BookCardDto) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val retention = catalogDao.retention(card.id) ?: BookRetentionEntity(bookId = card.id)
            val favorite = normalizedLibraryDao.favorite(card.id) != null
            val resolvedCard = card.copy(isFavorite = favorite)

            bookCardEntities(listOf(resolvedCard)).firstOrNull()?.let {
                catalogDao.upsertBooks(listOf(it))
            }

            val existingHistory = normalizedLibraryDao.historyEntry(card.id)
            if (existingHistory == null) {
                normalizedLibraryDao.shiftHistoryForNewFront()
            } else {
                normalizedLibraryDao.shiftHistoryBefore(existingHistory.rank)
            }
            normalizedLibraryDao.putHistory(
                LibraryHistoryEntity(
                    bookId = card.id,
                    rank = 0,
                    lastPlayedAtMs = now,
                    updatedAtMs = now,
                )
            )
            catalogDao.putRetention(
                retention.copy(
                    historyRef = true,
                    updatedAtMs = now,
                )
            )

        }
    }

    suspend fun removeHistoryState(bookId: String): Boolean {
        val now = System.currentTimeMillis()
        var removed = false
        db.withTransaction {
            val existing = normalizedLibraryDao.historyEntry(bookId)
            removed = existing != null

            if (existing != null) {
                normalizedLibraryDao.deleteHistory(bookId)
                normalizedLibraryDao.compactHistoryAfter(existing.rank)
            }

            val retention = catalogDao.retention(bookId) ?: BookRetentionEntity(bookId = bookId)
            catalogDao.putRetention(
                retention.copy(
                    historyRef = false,
                    updatedAtMs = now,
                )
            )
            catalogDao.pruneUnreferencedBooks()
            catalogDao.pruneEmptyRetention()
        }
        return removed
    }

    /**
     * Promote refreshed listened cycles to the front while preserving every
     * unrelated normalized series row and its relative order.
     */
    suspend fun promoteSeriesState(values: List<MySeriesDto>) {
        val unique = values.distinctBy {
            normalizedLibrarySeriesKey(it.provider, it.externalId, it.name)
        }
        if (unique.isEmpty()) return

        val now = System.currentTimeMillis()
        db.withTransaction {
            val updatedKeys = unique.mapTo(linkedSetOf()) {
                normalizedLibrarySeriesKey(it.provider, it.externalId, it.name)
            }
            val currentRows = normalizedLibraryDao.series()
            val survivors = currentRows.filterNot { it.seriesKey in updatedKeys }

            val currentCards = unique.mapNotNull { it.currentBook?.book }
                .distinctBy(BookCardDto::id)
            val currentCardIds = currentCards.mapTo(hashSetOf(), BookCardDto::id)
            val nextCards = unique.mapNotNull { it.nextBook?.book }
                .filterNot { it.id in currentCardIds }
                .distinctBy(BookCardDto::id)
            val seriesCardEntities = bookCardEntities(currentCards + nextCards)
            if (seriesCardEntities.isNotEmpty()) {
                // Series progress must not overwrite fresher retained metadata or
                // a favorite flag written by the history/favorite transaction.
                catalogDao.insertBooksIfAbsent(seriesCardEntities)
            }

            val rows = buildList {
                unique.forEachIndexed { index, series ->
                    add(series.toNormalizedLibrarySeriesEntity(index, now))
                }
                survivors.forEachIndexed { index, row ->
                    add(row.copy(rank = unique.size + index))
                }
            }
            normalizedLibraryDao.replaceSeries(rows)

        }
    }

    suspend fun removeMySeriesState(series: MySeriesDto): Boolean {
        val targetKey = normalizedLibrarySeriesKey(
            provider = series.provider,
            externalId = series.externalId,
            name = series.name,
        )
        var removed = false
        db.withTransaction {
            val currentRows = normalizedLibraryDao.series()
            val survivors = currentRows.filterNot { it.seriesKey == targetKey }
            removed = survivors.size != currentRows.size

            if (removed) {
                normalizedLibraryDao.replaceSeries(
                    survivors.mapIndexed { index, row -> row.copy(rank = index) }
                )
            }
            catalogDao.pruneUnreferencedBooks()
            catalogDao.pruneEmptyRetention()
        }
        return removed
    }

    suspend fun upsertBookmarkState(value: BookmarkDto) {
        val current = readBookmarks(value.bookId).orEmpty()
        writeBookmarks(
            value.bookId,
            (listOf(value) + current.filterNot { it.id == value.id }).distinctBy(BookmarkDto::id)
        )
    }

    suspend fun removeBookmarkState(bookmarkId: String, bookIdHint: String? = null) {
        val targets = buildList {
            bookIdHint?.takeIf { it.isNotBlank() }?.let(::add)
        }
        for (bookId in targets) {
            val current = readBookmarks(bookId) ?: continue
            if (current.any { it.id == bookmarkId }) {
                writeBookmarks(bookId, current.filterNot { it.id == bookmarkId })
                return
            }
        }
        dao.allWithPrefix("bookmarks:").forEach { row ->
            val payload = runCatching { bookmarksAdapter.fromJson(row.payloadJson) }.getOrNull() ?: return@forEach
            val match = payload.items.firstOrNull { it.id == bookmarkId } ?: return@forEach
            writeBookmarks(match.bookId, payload.items.filterNot { it.id == bookmarkId })
            return
        }
    }

    suspend fun setDownloadedReference(bookId: String, downloaded: Boolean) {
        markRetention(bookId) { current -> current.copy(downloadedRef = downloaded) }
    }

    suspend fun storageStats(): StorageStats {
        val catalogBytes = dao.disposablePayloadBytes()
        val profileBytes = dao.profilePayloadBytes() + catalogDao.retainedMetadataBytes()
        return StorageStats(
            catalogBytes = catalogBytes.coerceAtLeast(0L),
            profileBytes = profileBytes.coerceAtLeast(0L),
            favoriteBooks = catalogDao.favoriteRefCount(),
            historyBooks = catalogDao.historyRefCount(),
            progressBooks = catalogDao.progressRefCount(),
            bookmarkBooks = catalogDao.bookmarkRefCount(),
            downloadedBooks = catalogDao.downloadedRefCount()
        )
    }

    suspend fun clearCatalogCache() {
        db.withTransaction {
            dao.deleteDisposablePayloads()
            // Keep old schema rows empty so an upgraded install cannot resurrect
            // the retired bounded catalog through a stale compatibility path.
            catalogDao.clearWindow()
            val current = catalogDao.state() ?: CatalogCacheStateEntity()
            catalogDao.putState(current.copy(serverTotal = 0, lastSyncedAtMs = 0L))
            catalogDao.pruneUnreferencedBooks()
            catalogDao.pruneEmptyRetention()
        }
    }

    private suspend fun upsertCards(items: List<BookCardDto>) {
        if (items.isEmpty()) return
        val entities = bookCardEntities(items)
        if (entities.isNotEmpty()) catalogDao.upsertBooks(entities)
    }

    private suspend fun insertCardsIfAbsent(items: List<BookCardDto>) {
        if (items.isEmpty()) return
        val entities = bookCardEntities(items)
        if (entities.isNotEmpty()) catalogDao.insertBooksIfAbsent(entities)
    }

    private fun bookCardEntities(items: List<BookCardDto>): List<LocalBookEntity> {
        val now = System.currentTimeMillis()
        return items.distinctBy(BookCardDto::id).mapNotNull { card ->
            runCatching { bookCardAdapter.toJson(card) }.getOrNull()?.let { json ->
                LocalBookEntity(bookId = card.id, cardJson = json, updatedAtMs = now)
            }
        }
    }

    private suspend fun markRetention(
        bookId: String,
        transform: (BookRetentionEntity) -> BookRetentionEntity
    ) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val current = catalogDao.retention(bookId) ?: BookRetentionEntity(bookId = bookId)
            val updated = transform(current).copy(updatedAtMs = now)
            catalogDao.putRetention(updated)
            if (!updated.retained) {
                catalogDao.pruneUnreferencedBooks()
                catalogDao.pruneEmptyRetention()
            }
        }
    }

    private suspend fun <T> read(key: String, adapter: JsonAdapter<T>): T? {
        val row = dao.get(key) ?: return null
        return runCatching { adapter.fromJson(row.payloadJson) }.getOrNull()
    }

    private suspend fun <T> write(key: String, value: T, adapter: JsonAdapter<T>) {
        val json = runCatching { adapter.toJson(value) }.getOrNull() ?: return
        dao.put(CachedPayloadEntity(cacheKey = key, payloadJson = json))
    }

    companion object {
        private const val BOOK_CARD_QUERY_CHUNK_SIZE = 500
        private const val CACHE_KEY_QUERY_CHUNK_SIZE = 500
        private const val DEFAULT_VARIANT = "_default"

        fun sourceSeriesKey(bookId: String, provider: String?) = "source:${bookId}:${provider.orEmpty()}"
        fun canonicalSeriesKey(seriesId: String) = "canonical:$seriesId"
        fun audioSeriesKey(seriesId: String) = "audio:$seriesId"

        private fun part(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        private fun bookKey(bookId: String, source: String) = "book:${part(bookId)}:${part(source)}"
        private fun bookmarksKey(bookId: String) = "bookmarks:${part(bookId)}"
        private fun similarPageKey(bookId: String, page: Int) = "similar-page:${part(bookId)}:$page"
        private fun progressKey(bookId: String, source: String?) = "progress:${part(bookId)}:${part(source.orEmpty())}"
        private fun catalogKey(
            query: String,
            genreId: String?,
            source: String?,
            sourceAvailabilityKey: String,
            page: Int,
        ) =
            "catalog:${part(query.trim())}:${part(genreId.orEmpty())}:${part(source.orEmpty())}:" +
                "${part(sourceAvailabilityKey)}:$page"
        private fun browseKey(kind: String, id: String, page: Int) = "browse:${part(kind)}:${part(id)}:$page"
    }
}
