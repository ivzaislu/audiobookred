package com.example.ui.paging

import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.BookListResponse
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.parser.AndroidLiveParserHub
import com.example.data.parser.AndroidLiveParserLocator
import com.example.data.source.StandaloneSourceRegistry
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the "Similar" shelf entirely from the on-device provider that owns
 * the currently selected source of the book.
 *
 * Recommendations intentionally stay inside one provider. Cross-provider
 * lookup is reserved for flows where it is actually useful, such as finding a
 * full version of a preview-only book. This keeps detail-screen network and
 * parsing work small and predictable.
 */
internal object LocalSimilarBooks {
    private data class CacheEntry(
        val storedAtMs: Long,
        val items: List<BookCardDto>,
    )

    private data class RankedCandidate(
        val candidate: LiveCatalogItemDto,
        val score: Int,
        val normalizedTitle: String,
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_MAX
    }

    suspend fun page(bookId: String, page: Int, limit: Int): BookListResponse {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val local = AndroidLiveParserLocator.instanceOrNull()
            ?: error("Android live parser is not initialized")
        val book = local.book(bookId)
        val source = recommendationSource(book, local.sources)
            ?: return BookListResponse(page = safePage, limit = safeLimit)
        val cacheKey = "$bookId|$source"
        val allItems = cached(cacheKey) ?: load(local, book, source).also { store(cacheKey, it) }
        val offset = (safePage.toLong() - 1L) * safeLimit.toLong()
        val pageItems = if (offset >= allItems.size.toLong() || offset > Int.MAX_VALUE) {
            emptyList()
        } else {
            allItems.drop(offset.toInt()).take(safeLimit)
        }
        return BookListResponse(
            items = pageItems,
            page = safePage,
            limit = safeLimit,
            total = allItems.size,
        )
    }

    private suspend fun load(
        local: AndroidLiveParserHub,
        book: BookDetailDto,
        source: String,
    ): List<BookCardDto> {
        val query = similarityQuery(book)
        if (query.isBlank()) return emptyList()

        val candidates = providerOrNull {
            local.search(source, query)
        }.orEmpty()

        // Search completion can resume the caller on the UI dispatcher. All
        // normalization/ranking/sorting is CPU work, so keep it off the main
        // thread even though there is now only one provider response.
        return withContext(Dispatchers.Default) {
            rank(book, candidates)
        }
    }

    private fun rank(
        book: BookDetailDto,
        candidates: List<LiveCatalogItemDto>,
    ): List<BookCardDto> {
        val sourceTitle = normalize(book.title)
        val sourceAuthors = book.authors.map { normalize(it.name) }.filter(String::isNotBlank).toSet()
        val sourceGenres = book.genres.map { normalize(it.name) }.filter(String::isNotBlank).toSet()
        val sourceNarrators = book.narrators.map { normalize(it.name) }.filter(String::isNotBlank).toSet()
        val sourceSeries = normalize(book.sourceSeriesName.ifBlank { book.seriesName })
        val sourceTitleTokens = tokens(book.title)

        return candidates
            .asSequence()
            .distinctBy { it.key }
            .filterNot { candidate -> candidate.key == book.id }
            .filterNot { candidate ->
                val sameTitle = normalize(candidate.title) == sourceTitle
                val candidateAuthors = candidate.authors.map(::normalize).toSet()
                sameTitle && sourceAuthors.isNotEmpty() && candidateAuthors.any(sourceAuthors::contains)
            }
            .take(MAX_CANDIDATES)
            .map { candidate ->
                RankedCandidate(
                    candidate = candidate,
                    score = similarityScore(
                        candidate = candidate,
                        sourceAuthors = sourceAuthors,
                        sourceGenres = sourceGenres,
                        sourceNarrators = sourceNarrators,
                        sourceSeries = sourceSeries,
                        sourceTitleTokens = sourceTitleTokens,
                    ),
                    normalizedTitle = normalize(candidate.title),
                )
            }
            .filter { ranked -> ranked.score > 0 }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.score }
                    .thenBy { it.normalizedTitle }
            )
            .map { ranked -> ranked.candidate.toBookCard() }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun recommendationSource(book: BookDetailDto, supportedSources: List<String>): String? {
        val supported = supportedSources.toSet()
        val idSource = book.id.substringBefore(':', missingDelimiterValue = "")
        return sequence {
            yield(book.selectedSource)
            yield(book.primarySource)
            yieldAll(book.sourceCodes)
            yield(idSource)
        }
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotBlank() && it in supported }
    }

    private fun similarityQuery(book: BookDetailDto): String =
        book.authors.firstOrNull()?.name?.trim().orEmpty().takeIf(String::isNotBlank)
            ?: book.sourceSeriesName.trim().takeIf(String::isNotBlank)
            ?: book.seriesName.trim().takeIf(String::isNotBlank)
            ?: book.genres.firstOrNull()?.name?.trim().orEmpty().takeIf(String::isNotBlank)
            ?: book.title.trim()

    private fun similarityScore(
        candidate: LiveCatalogItemDto,
        sourceAuthors: Set<String>,
        sourceGenres: Set<String>,
        sourceNarrators: Set<String>,
        sourceSeries: String,
        sourceTitleTokens: Set<String>,
    ): Int {
        val candidateAuthors = candidate.authors.map(::normalize).filter(String::isNotBlank).toSet()
        val candidateGenres = candidate.genres.map(::normalize).filter(String::isNotBlank).toSet()
        val candidateNarrators = candidate.narrators.map(::normalize).filter(String::isNotBlank).toSet()
        val candidateSeries = normalize(candidate.seriesName)
        var score = 0

        val authorOverlap = candidateAuthors.count(sourceAuthors::contains)
        val genreOverlap = candidateGenres.count(sourceGenres::contains)
        val narratorOverlap = candidateNarrators.count(sourceNarrators::contains)
        score += authorOverlap * 50
        score += genreOverlap * 12
        score += narratorOverlap * 3

        if (sourceSeries.isNotBlank() && candidateSeries.isNotBlank()) {
            score += when {
                candidateSeries == sourceSeries -> 80
                candidateSeries.contains(sourceSeries) || sourceSeries.contains(candidateSeries) -> 30
                else -> 0
            }
        }

        score += tokens(candidate.title).count(sourceTitleTokens::contains).coerceAtMost(8)
        return score
    }

    private suspend fun <T> providerOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun LiveCatalogItemDto.toBookCard(): BookCardDto = BookCardDto(
        id = key,
        title = title,
        authors = authors.filter(String::isNotBlank).map { PersonDto(id = "", name = it) },
        narrators = narrators.filter(String::isNotBlank).map { PersonDto(id = "", name = it) },
        genres = genres.filter(String::isNotBlank).map { GenreDto(id = "", name = it) },
        coverUrl = coverUrl,
        durationSeconds = durationSeconds,
        sourceMeta = sourceMeta,
        sourceCodes = listOf(source),
        primarySource = source,
        sourceSeriesName = seriesName,
        sourceSeriesPosition = seriesPosition,
        audioSeries = if (seriesExternalId.isBlank() || seriesName.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = "source:$source:$seriesExternalId",
                name = seriesName,
                position = seriesPosition?.toDouble(),
                provider = source,
                externalId = seriesExternalId,
                sourceName = sourceName(source),
            )
        ),
    )

    private fun sourceName(source: String): String =
        StandaloneSourceRegistry.displayNameOrNull(source) ?: source

    private fun tokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .asSequence()
        .filter { it.length >= 4 }
        .toSet()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(NON_ALNUM_REGEX, " ")
        .trim()

    private fun cached(cacheKey: String): List<BookCardDto>? = synchronized(cache) {
        val row = cache[cacheKey] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > CACHE_TTL_MS) {
            cache.remove(cacheKey)
            null
        } else row.items
    }

    private fun store(cacheKey: String, items: List<BookCardDto>) {
        synchronized(cache) {
            cache[cacheKey] = CacheEntry(monotonicMs(), items)
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_MAX = 32
    private const val MAX_CANDIDATES = 600
    private const val MAX_RESULTS = 120
}
