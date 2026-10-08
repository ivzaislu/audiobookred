package com.example.data.backup

import com.example.data.cache.AppCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkDto
import com.example.data.model.ProgressResponse
import com.example.data.settings.PROGRESS_SAVE_INTERVAL_SECONDS
import com.example.data.settings.DEFAULT_HOME_CACHE_DAYS
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.data.settings.PlayerSettings
import com.example.data.source.StandaloneSourceRegistry
import com.squareup.moshi.Json

const val USER_DATA_BACKUP_SCHEMA = "abred.user-data-backup/v1"

/**
 * Portable user-owned state. Download records and downloaded media are deliberately absent.
 * The schema is versioned so a restore flow can be added without depending on Room internals.
 */
data class UserDataBackup(
    val schema: String = USER_DATA_BACKUP_SCHEMA,
    @Json(name = "application_id") val applicationId: String,
    @Json(name = "version_name") val versionName: String,
    @Json(name = "version_code") val versionCode: Int,
    @Json(name = "exported_at_ms") val exportedAtMs: Long,
    val library: AppCacheStore.LibraryCache = AppCacheStore.LibraryCache(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    @Json(name = "playback_checkpoints") val playbackCheckpoints: List<BackupPlaybackCheckpoint> = emptyList(),
    @Json(name = "book_metadata") val bookMetadata: List<BookCardDto> = emptyList(),
    @Json(name = "source_preferences") val sourcePreferences: Map<String, String> = emptyMap(),
    val settings: BackupSettings = BackupSettings(),
    @Json(name = "excluded_data") val excludedData: BackupExclusions = BackupExclusions(),
)

data class BackupProgress(
    @Json(name = "book_id") val bookId: String,
    @Json(name = "source_code") val sourceCode: String? = null,
    val value: ProgressResponse,
)

data class BackupPlaybackCheckpoint(
    @Json(name = "book_id") val bookId: String,
    @Json(name = "source_code") val sourceCode: String,
    @Json(name = "chapter_id") val chapterId: String? = null,
    @Json(name = "chapter_index") val chapterIndex: Int = 0,
    @Json(name = "position_ms") val positionMs: Long = 0L,
    val speed: Float = 1f,
    @Json(name = "progress_percent") val progressPercent: Double = 0.0,
    @Json(name = "saved_at_ms") val savedAtMs: Long = 0L,
)

data class BackupSettings(
    @Json(name = "default_speed") val defaultSpeed: Float = 1f,
    @Json(name = "remember_book_speed") val rememberBookSpeed: Boolean = true,
    @Json(name = "rewind_seconds") val rewindSeconds: Int = 10,
    @Json(name = "forward_seconds") val forwardSeconds: Int = 30,
    @Json(name = "auto_next_chapter") val autoNextChapter: Boolean = true,
    @Json(name = "simplify_chapter_titles") val simplifyChapterTitles: Boolean = true,
    @Json(name = "save_progress_interval_seconds") val saveProgressIntervalSeconds: Int = 10,
    @Json(name = "show_progress_percent") val showProgressPercent: Boolean = true,
    @Json(name = "skip_silence_enabled") val skipSilenceEnabled: Boolean = false,
    @Json(name = "smart_rewind_after_pause") val smartRewindAfterPause: Boolean = true,
    @Json(name = "pin_bottom_navigation") val pinBottomNavigation: Boolean = true,
    @Json(name = "home_show_new") val homeShowNew: Boolean = true,
    @Json(name = "home_show_popular") val homeShowPopular: Boolean = true,
    @Json(name = "home_show_continue") val homeShowContinue: Boolean = true,
    @Json(name = "home_show_downloads") val homeShowDownloads: Boolean = true,
    @Json(name = "home_popular_default_period") val homePopularDefaultPeriod: String = HomePopularDefaultPeriod.WEEK.name,
    @Json(name = "home_cache_days") val homeCacheDays: Int = DEFAULT_HOME_CACHE_DAYS,
    @Json(name = "show_continue_series") val showContinueSeries: Boolean = true,
    @Json(name = "show_series_navigation") val showSeriesNavigation: Boolean = true,
    @Json(name = "download_wifi_only") val downloadWifiOnly: Boolean = true,
    @Json(name = "catalog_cache_enabled") val catalogCacheEnabled: Boolean = true,
    @Json(name = "theme_mode") val themeMode: String = "DARK",
    @Json(name = "enabled_sources") val enabledSources: List<String>? = null,
) {
    companion object {
        fun from(
            value: PlayerSettings,
            enabledSources: Set<String>? = null,
        ) = BackupSettings(
            defaultSpeed = value.defaultSpeed,
            rememberBookSpeed = value.rememberBookSpeed,
            rewindSeconds = value.rewindSeconds,
            forwardSeconds = value.forwardSeconds,
            autoNextChapter = value.autoNextChapter,
            simplifyChapterTitles = value.simplifyChapterTitles,
            // Historical backup-v1 field remains stable even though runtime
            // playback cadence is no longer user-configurable.
            saveProgressIntervalSeconds = PROGRESS_SAVE_INTERVAL_SECONDS,
            showProgressPercent = value.showProgressPercent,
            skipSilenceEnabled = value.skipSilenceEnabled,
            smartRewindAfterPause = value.smartRewindAfterPause,
            pinBottomNavigation = value.pinBottomNavigation,
            homeShowNew = value.homeShowNew,
            homeShowPopular = value.homeShowPopular,
            homeShowContinue = value.homeShowContinue,
            homeShowDownloads = value.homeShowDownloads,
            homePopularDefaultPeriod = value.homePopularDefaultPeriod.name,
            homeCacheDays = value.homeCacheDays,
            // Historical backup-v1 fields remain stable even though current
            // runtime no longer exposes either series-visibility toggle.
            showContinueSeries = true,
            showSeriesNavigation = true,
            downloadWifiOnly = value.downloadWifiOnly,
            catalogCacheEnabled = value.catalogCacheEnabled,
            themeMode = value.themeMode.name,
            enabledSources = enabledSources
                ?.asSequence()
                ?.map(StandaloneSourceRegistry::normalize)
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.toList(),
        )
    }
}

data class BackupExclusions(
    @Json(name = "downloaded_audio") val downloadedAudio: Boolean = true,
    @Json(name = "download_records") val downloadRecords: Boolean = true,
    @Json(name = "catalog_cache") val catalogCache: Boolean = true,
    @Json(name = "image_cache") val imageCache: Boolean = true,
)
