package com.example.data.model

import com.squareup.moshi.Json

data class PersonDto(val id: String, val name: String)
data class GenreDto(val id: String, val name: String)

data class SeriesBriefDto(
    val id: String,
    val name: String,
    val position: Double? = null,
    @Json(name = "is_primary") val isPrimary: Boolean = false,
    val provider: String = "fantlab",
    @Json(name = "external_id") val externalId: String = ""
)

data class AudioSeriesBriefDto(
    val id: String,
    val name: String,
    val position: Double? = null,
    val provider: String,
    @Json(name = "external_id") val externalId: String = "",
    @Json(name = "source_name") val sourceName: String = ""
)

data class ChapterDto(
    val id: String,
    val position: Int,
    val title: String,
    @Json(name = "duration_seconds") val durationSeconds: Long = 0,
    @Json(name = "stream_url") val streamUrl: String = ""
)

data class SourceVariantDto(
    @Json(name = "book_source_id") val bookSourceId: String,
    @Json(name = "source_code") val sourceCode: String,
    @Json(name = "source_name") val sourceName: String,
    @Json(name = "series_name") val seriesName: String = "",
    @Json(name = "magnet_uri") val magnetUri: String = ""
)

data class BookCardDto(
    val id: String,
    val title: String,
    val authors: List<PersonDto> = emptyList(),
    val narrators: List<PersonDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "duration_seconds") val durationSeconds: Long = 0,
    @Json(name = "source_meta") val sourceMeta: String = "",
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "progress_percent") val progressPercent: Double = 0.0,
    @Json(name = "source_codes") val sourceCodes: List<String> = emptyList(),
    @Json(name = "primary_source") val primarySource: String = "",
    @Json(name = "canonical_title") val canonicalTitle: String = "",
    @Json(name = "source_series_name") val sourceSeriesName: String = "",
    @Json(name = "source_series_position") val sourceSeriesPosition: Int? = null,
    @Json(name = "audio_series") val audioSeries: List<AudioSeriesBriefDto> = emptyList(),
    val series: List<SeriesBriefDto> = emptyList()
) {
    val authorText: String get() = authors.joinToString(", ") { it.name }.ifBlank { "Автор не указан" }
    val narratorText: String get() = narrators.joinToString(", ") { it.name }
}

data class BookDetailDto(
    val id: String,
    val title: String,
    val authors: List<PersonDto> = emptyList(),
    val narrators: List<PersonDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "duration_seconds") val durationSeconds: Long = 0,
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "progress_percent") val progressPercent: Double = 0.0,
    @Json(name = "source_codes") val sourceCodes: List<String> = emptyList(),
    @Json(name = "primary_source") val primarySource: String = "",
    @Json(name = "selected_source") val selectedSource: String = "",
    @Json(name = "selected_book_source_id") val selectedBookSourceId: String = "",
    @Json(name = "source_variants") val sourceVariants: List<SourceVariantDto> = emptyList(),
    val description: String = "",
    @Json(name = "series_name") val seriesName: String = "",
    @Json(name = "series_position") val seriesPosition: Int? = null,
    @Json(name = "source_series_name") val sourceSeriesName: String = "",
    @Json(name = "source_series_position") val sourceSeriesPosition: Int? = null,
    @Json(name = "audio_series") val audioSeries: List<AudioSeriesBriefDto> = emptyList(),
    val series: List<SeriesBriefDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList()
) {
    val authorText: String get() = authors.joinToString(", ") { it.name }.ifBlank { "Автор не указан" }
    val narratorText: String get() = narrators.joinToString(", ") { it.name }
    fun asCard() = BookCardDto(
        id = id, title = title, authors = authors, narrators = narrators, genres = genres,
        coverUrl = coverUrl, durationSeconds = durationSeconds,
        isFavorite = isFavorite, progressPercent = progressPercent,
        sourceCodes = sourceCodes, primarySource = primarySource,
        sourceSeriesName = sourceSeriesName.ifBlank { seriesName },
        sourceSeriesPosition = sourceSeriesPosition ?: seriesPosition,
        audioSeries = audioSeries,
        series = series
    )
}

data class BookListResponse(
    val items: List<BookCardDto> = emptyList(),
    val page: Int = 1,
    val limit: Int = 30,
    val total: Int = 0
)

data class SeriesEntryDto(
    @Json(name = "external_work_id") val externalWorkId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val position: Double? = null,
    @Json(name = "published_year") val publishedYear: Int? = null,
    val available: Boolean = false,
    val book: BookCardDto? = null
)

data class SeriesDetailDto(
    val id: String,
    val name: String,
    val kind: String = "cycle",
    val provider: String = "fantlab",
    @Json(name = "books_count") val booksCount: Int = 0,
    @Json(name = "total_count") val totalCount: Int = 0,
    val description: String = "",
    val books: List<BookCardDto> = emptyList(),
    val entries: List<SeriesEntryDto> = emptyList()
)

data class SeriesProgressBookDto(
    val book: BookCardDto,
    val position: Double? = null,
    val state: String = "not_started",
    @Json(name = "progress_percent") val progressPercent: Double = 0.0
)

data class MySeriesDto(
    val id: String,
    val name: String,
    val provider: String,
    @Json(name = "external_id") val externalId: String = "",
    @Json(name = "source_name") val sourceName: String = "",
    @Json(name = "available_count") val availableCount: Int = 0,
    @Json(name = "total_count") val totalCount: Int = 0,
    @Json(name = "completed_count") val completedCount: Int = 0,
    @Json(name = "in_progress_count") val inProgressCount: Int = 0,
    @Json(name = "not_started_count") val notStartedCount: Int = 0,
    val status: String = "active",
    @Json(name = "last_activity_at") val lastActivityAt: String = "",
    @Json(name = "current_book") val currentBook: SeriesProgressBookDto? = null,
    @Json(name = "next_book") val nextBook: SeriesProgressBookDto? = null
) {
    val isCompleted: Boolean get() = status == "completed"
    val progressFraction: Float
        get() = if (availableCount > 0) (completedCount.toFloat() / availableCount.toFloat()).coerceIn(0f, 1f) else 0f
}

data class ProgressResponse(
    @Json(name = "book_id") val bookId: String,
    @Json(name = "book_source_id") val bookSourceId: String? = null,
    @Json(name = "chapter_id") val chapterId: String? = null,
    @Json(name = "chapter_index") val chapterIndex: Int = 0,
    @Json(name = "position_ms") val positionMs: Long = 0,
    @Json(name = "playback_speed") val playbackSpeed: Double = 1.0,
    val completed: Boolean = false,
    @Json(name = "updated_at") val updatedAt: String = ""
)

data class BookmarkDto(
    val id: String,
    @Json(name = "book_id") val bookId: String,
    @Json(name = "chapter_id") val chapterId: String? = null,
    @Json(name = "chapter_index") val chapterIndex: Int = 0,
    @Json(name = "position_ms") val positionMs: Long = 0,
    val note: String = ""
)

data class BookmarkUiItem(
    val bookmark: BookmarkDto,
    val bookTitle: String,
    val chapterTitle: String,
    val coverUrl: String
)

data class DownloadFileDto(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "chapter_id") val chapterId: String,
    @Json(name = "chapter_position") val chapterPosition: Int,
    val title: String,
    @Json(name = "duration_seconds") val durationSeconds: Long = 0L,
    val filename: String,
    @Json(name = "media_type") val mediaType: String = "audio/mpeg",
    @Json(name = "size_bytes") val sizeBytes: Long? = null,
    val delivery: String = "",
    @Json(name = "download_url") val downloadUrl: String
)

data class DownloadManifestDto(
    @Json(name = "manifest_version") val manifestVersion: Int = 1,
    @Json(name = "manifest_id") val manifestId: String,
    @Json(name = "book_id") val bookId: String,
    @Json(name = "book_source_id") val bookSourceId: String,
    @Json(name = "source_code") val sourceCode: String,
    @Json(name = "source_name") val sourceName: String,
    val title: String,
    @Json(name = "cover_url") val coverUrl: String = "",
    @Json(name = "duration_seconds") val durationSeconds: Long = 0L,
    @Json(name = "files_count") val filesCount: Int = 0,
    @Json(name = "total_size_bytes") val totalSizeBytes: Long? = null,
    @Json(name = "size_complete") val sizeComplete: Boolean = false,
    val files: List<DownloadFileDto> = emptyList()
)
