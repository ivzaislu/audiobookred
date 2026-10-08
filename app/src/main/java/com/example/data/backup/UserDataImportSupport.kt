package com.example.data.backup

import com.example.BuildConfig
import com.example.data.model.BookmarkDto
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.AppThemeMode
import com.example.data.settings.HomePopularDefaultPeriod
import com.example.data.settings.PlayerSettings
import com.example.data.settings.sanitizeHomeCacheDays
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal fun validateBackup(backup: UserDataBackup) {
    if (backup.schema != USER_DATA_BACKUP_SCHEMA) {
        throw IOException("Неподдерживаемый формат резервной копии: ${backup.schema}")
    }
    if (
        !backupApplicationIdIsAccepted(
            backupApplicationId = backup.applicationId,
            currentApplicationId = BuildConfig.APPLICATION_ID,
            debugBuild = BuildConfig.DEBUG,
        )
    ) {
        throw IOException("Резервная копия создана для другого приложения")
    }
}

internal fun checkpointsFromBackup(backup: UserDataBackup): List<PlaybackResumeStore.Snapshot> =
    backup.playbackCheckpoints
        .filter { it.bookId.isNotBlank() }
        .map { checkpoint ->
            PlaybackResumeStore.Snapshot(
                bookId = checkpoint.bookId,
                sourceCode = checkpoint.sourceCode,
                chapterId = checkpoint.chapterId,
                chapterIndex = checkpoint.chapterIndex,
                positionMs = checkpoint.positionMs,
                speed = checkpoint.speed,
                progressPercent = checkpoint.progressPercent,
                savedAtMs = checkpoint.savedAtMs,
                dirty = true,
            )
        }

internal fun enabledSourcesFromBackup(
    value: BackupSettings,
    fallback: Set<String>,
): Set<String> = value.enabledSources
    ?.mapTo(linkedSetOf()) { it.trim().lowercase(Locale.US) }
    ?.filterTo(linkedSetOf()) { it.isNotBlank() }
    ?.takeIf { it.isNotEmpty() }
    ?: fallback

internal fun settingsFromBackup(value: BackupSettings, fallbackTheme: AppThemeMode): PlayerSettings {
    val theme = runCatching {
        AppThemeMode.valueOf(value.themeMode.trim().uppercase(Locale.US))
    }.getOrDefault(fallbackTheme)
    val homePopularDefaultPeriod = runCatching {
        HomePopularDefaultPeriod.valueOf(value.homePopularDefaultPeriod.trim().uppercase(Locale.US))
    }.getOrDefault(HomePopularDefaultPeriod.WEEK)
    return PlayerSettings(
        defaultSpeed = value.defaultSpeed,
        rememberBookSpeed = value.rememberBookSpeed,
        rewindSeconds = value.rewindSeconds,
        forwardSeconds = value.forwardSeconds,
        autoNextChapter = value.autoNextChapter,
        simplifyChapterTitles = value.simplifyChapterTitles,
        // Historical backup-v1 cadence is intentionally ignored. Current
        // runtime always uses the fixed internal checkpoint cadence.
        showProgressPercent = value.showProgressPercent,
        skipSilenceEnabled = value.skipSilenceEnabled,
        smartRewindAfterPause = value.smartRewindAfterPause,
        pinBottomNavigation = value.pinBottomNavigation,
        homeShowNew = value.homeShowNew,
        homeShowPopular = value.homeShowPopular,
        homeShowContinue = value.homeShowContinue,
        homeShowDownloads = value.homeShowDownloads,
        homePopularDefaultPeriod = homePopularDefaultPeriod,
        homeCacheDays = sanitizeHomeCacheDays(value.homeCacheDays),
        // Historical backup-v1 show_continue_series/show_series_navigation
        // values are intentionally ignored: both behaviors are always enabled
        // in the current runtime and are no longer configurable settings.
        downloadWifiOnly = value.downloadWifiOnly,
        catalogCacheEnabled = value.catalogCacheEnabled,
        themeMode = theme,
    )
}

private fun part(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

internal fun bookmarksKey(bookId: String): String = "bookmarks:${part(bookId)}"

internal fun progressKey(bookId: String, sourceCode: String?): String =
    "progress:${part(bookId)}:${part(sourceCode.orEmpty())}"
