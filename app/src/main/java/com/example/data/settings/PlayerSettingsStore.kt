package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode { DARK, LIGHT, SYSTEM }

enum class HomePopularDefaultPeriod { TODAY, WEEK, MONTH }

internal val DEFAULT_APP_THEME_MODE = AppThemeMode.DARK

data class PlayerSettings(
    val defaultSpeed: Float = 1f,
    val rememberBookSpeed: Boolean = true,
    val rewindSeconds: Int = 10,
    val forwardSeconds: Int = 30,
    val autoNextChapter: Boolean = true,
    val simplifyChapterTitles: Boolean = true,
    val showProgressPercent: Boolean = true,
    val skipSilenceEnabled: Boolean = false,
    val smartRewindAfterPause: Boolean = true,
    val pinBottomNavigation: Boolean = true,
    val homeShowNew: Boolean = true,
    val homeShowPopular: Boolean = true,
    val homeShowContinue: Boolean = true,
    val homeShowDownloads: Boolean = true,
    val homePopularDefaultPeriod: HomePopularDefaultPeriod = HomePopularDefaultPeriod.WEEK,
    val homeCacheDays: Int = DEFAULT_HOME_CACHE_DAYS,
    val downloadWifiOnly: Boolean = true,
    val catalogCacheEnabled: Boolean = true,
    val themeMode: AppThemeMode = DEFAULT_APP_THEME_MODE
)

internal fun sanitizeDefaultPlaybackSpeed(value: Float): Float = value
    .takeIf { it.isFinite() }
    ?.coerceIn(MIN_DEFAULT_PLAYBACK_SPEED, MAX_DEFAULT_PLAYBACK_SPEED)
    ?: DEFAULT_PLAYBACK_SPEED

class PlayerSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<PlayerSettings> = _state.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setDefaultSpeed(value: Float) = update { copy(defaultSpeed = sanitizeDefaultPlaybackSpeed(value)) }
    fun setRememberBookSpeed(value: Boolean) = update { copy(rememberBookSpeed = value) }
    fun setRewindSeconds(value: Int) = update { copy(rewindSeconds = value.coerceIn(5, 60)) }
    fun setForwardSeconds(value: Int) = update { copy(forwardSeconds = value.coerceIn(5, 120)) }
    fun setAutoNextChapter(value: Boolean) = update { copy(autoNextChapter = value) }
    fun setSimplifyChapterTitles(value: Boolean) = update { copy(simplifyChapterTitles = value) }

    fun setShowProgressPercent(value: Boolean) = update { copy(showProgressPercent = value) }
    fun setSkipSilenceEnabled(value: Boolean) = update { copy(skipSilenceEnabled = value) }
    fun setSmartRewindAfterPause(value: Boolean) = update { copy(smartRewindAfterPause = value) }
    fun setPinBottomNavigation(value: Boolean) = update { copy(pinBottomNavigation = value) }
    fun setHomeShowNew(value: Boolean) = update { copy(homeShowNew = value) }
    fun setHomeShowPopular(value: Boolean) = update { copy(homeShowPopular = value) }
    fun setHomeShowContinue(value: Boolean) = update { copy(homeShowContinue = value) }
    fun setHomeShowDownloads(value: Boolean) = update { copy(homeShowDownloads = value) }
    fun setHomePopularDefaultPeriod(value: HomePopularDefaultPeriod) =
        update { copy(homePopularDefaultPeriod = value) }
    fun setHomeCacheDays(value: Int) = update { copy(homeCacheDays = sanitizeHomeCacheDays(value)) }
    fun setDownloadWifiOnly(value: Boolean) = update { copy(downloadWifiOnly = value) }
    fun setCatalogCacheEnabled(value: Boolean) = update { copy(catalogCacheEnabled = value) }
    fun setThemeMode(value: AppThemeMode) = update { copy(themeMode = value) }

    /**
     * Synchronously replaces the complete settings snapshot. Backup restore uses
     * this instead of a sequence of apply() calls so failure can be detected and
     * rolled back before Room state is committed.
     */
    internal fun replace(value: PlayerSettings): Boolean {
        val next = normalize(value)
        val committed = write(next, synchronous = true)
        if (committed) _state.value = next
        return committed
    }

    fun reset() {
        val theme = _state.value.themeMode
        val next = PlayerSettings(themeMode = theme)
        write(next, synchronous = false)
        _state.value = next
    }

    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private inline fun update(block: PlayerSettings.() -> PlayerSettings) {
        val next = normalize(_state.value.block())
        write(next, synchronous = false)
        _state.value = next
    }

    private fun normalize(value: PlayerSettings): PlayerSettings = value.copy(
        defaultSpeed = sanitizeDefaultPlaybackSpeed(value.defaultSpeed),
        rewindSeconds = value.rewindSeconds.coerceIn(5, 60),
        forwardSeconds = value.forwardSeconds.coerceIn(5, 120),
        homeCacheDays = sanitizeHomeCacheDays(value.homeCacheDays),
    )

    private fun write(next: PlayerSettings, synchronous: Boolean): Boolean {
        val editor = prefs.edit()
            .putFloat(KEY_DEFAULT_SPEED, next.defaultSpeed)
            .putBoolean(KEY_REMEMBER_SPEED, next.rememberBookSpeed)
            .putInt(KEY_REWIND_SECONDS, next.rewindSeconds)
            .putInt(KEY_FORWARD_SECONDS, next.forwardSeconds)
            .putBoolean(KEY_AUTO_NEXT, next.autoNextChapter)
            .putBoolean(KEY_SIMPLIFY_CHAPTER_TITLES, next.simplifyChapterTitles)
            // Historical runtime tuning is gone. Remove the old preference on
            // the next settings write while backup-v1 keeps its JSON field.
            .remove(KEY_SAVE_INTERVAL)
            .putBoolean(KEY_SHOW_PERCENT, next.showProgressPercent)
            .putBoolean(KEY_SKIP_SILENCE, next.skipSilenceEnabled)
            .putBoolean(KEY_SMART_REWIND_AFTER_PAUSE, next.smartRewindAfterPause)
            .putBoolean(KEY_PIN_BOTTOM_NAVIGATION, next.pinBottomNavigation)
            .putBoolean(KEY_HOME_SHOW_NEW, next.homeShowNew)
            .putBoolean(KEY_HOME_SHOW_POPULAR, next.homeShowPopular)
            .putBoolean(KEY_HOME_SHOW_CONTINUE, next.homeShowContinue)
            .putBoolean(KEY_HOME_SHOW_DOWNLOADS, next.homeShowDownloads)
            .putString(KEY_HOME_POPULAR_DEFAULT_PERIOD, next.homePopularDefaultPeriod.name)
            .putInt(KEY_HOME_CACHE_DAYS, next.homeCacheDays)
            // Historical series visibility is no longer configurable at runtime.
            // Drop the old persisted toggle on the next settings write while
            // backup-v1 keeps both historical JSON fields.
            .remove(KEY_SHOW_CONTINUE_SERIES)
            .putBoolean(KEY_DOWNLOAD_WIFI_ONLY, next.downloadWifiOnly)
            .putBoolean(KEY_CATALOG_CACHE_ENABLED, next.catalogCacheEnabled)
            .putString(KEY_THEME_MODE, next.themeMode.name)
        return if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    private fun read(): PlayerSettings {
        val theme = prefs.getString(KEY_THEME_MODE, DEFAULT_APP_THEME_MODE.name)
            ?.let { stored ->
                runCatching { AppThemeMode.valueOf(stored) }
                    .getOrDefault(DEFAULT_APP_THEME_MODE)
            }
            ?: DEFAULT_APP_THEME_MODE
        return PlayerSettings(
            defaultSpeed = sanitizeDefaultPlaybackSpeed(prefs.getFloat(KEY_DEFAULT_SPEED, DEFAULT_PLAYBACK_SPEED)),
            rememberBookSpeed = prefs.getBoolean(KEY_REMEMBER_SPEED, true),
            rewindSeconds = prefs.getInt(KEY_REWIND_SECONDS, 10),
            forwardSeconds = prefs.getInt(KEY_FORWARD_SECONDS, 30),
            autoNextChapter = prefs.getBoolean(KEY_AUTO_NEXT, true),
            simplifyChapterTitles = prefs.getBoolean(KEY_SIMPLIFY_CHAPTER_TITLES, true),
            showProgressPercent = prefs.getBoolean(KEY_SHOW_PERCENT, true),
            skipSilenceEnabled = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            smartRewindAfterPause = prefs.getBoolean(KEY_SMART_REWIND_AFTER_PAUSE, true),
            pinBottomNavigation = prefs.getBoolean(KEY_PIN_BOTTOM_NAVIGATION, true),
            homeShowNew = prefs.getBoolean(KEY_HOME_SHOW_NEW, true),
            homeShowPopular = prefs.getBoolean(KEY_HOME_SHOW_POPULAR, true),
            homeShowContinue = prefs.getBoolean(KEY_HOME_SHOW_CONTINUE, true),
            homeShowDownloads = prefs.getBoolean(KEY_HOME_SHOW_DOWNLOADS, true),
            homePopularDefaultPeriod = prefs.getString(
                KEY_HOME_POPULAR_DEFAULT_PERIOD,
                HomePopularDefaultPeriod.WEEK.name,
            )?.let { stored ->
                runCatching { HomePopularDefaultPeriod.valueOf(stored) }
                    .getOrDefault(HomePopularDefaultPeriod.WEEK)
            } ?: HomePopularDefaultPeriod.WEEK,
            homeCacheDays = sanitizeHomeCacheDays(
                prefs.getInt(KEY_HOME_CACHE_DAYS, DEFAULT_HOME_CACHE_DAYS)
            ),
            downloadWifiOnly = prefs.getBoolean(KEY_DOWNLOAD_WIFI_ONLY, true),
            catalogCacheEnabled = prefs.getBoolean(KEY_CATALOG_CACHE_ENABLED, true),
            themeMode = theme
        )
    }

    private companion object {
        const val PREFS_NAME = "abred_player_settings"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val KEY_REMEMBER_SPEED = "remember_book_speed"
        const val KEY_REWIND_SECONDS = "rewind_seconds"
        const val KEY_FORWARD_SECONDS = "forward_seconds"
        const val KEY_AUTO_NEXT = "auto_next_chapter"
        const val KEY_SIMPLIFY_CHAPTER_TITLES = "simplify_chapter_titles"
        const val KEY_SAVE_INTERVAL = "save_progress_interval_seconds"
        const val KEY_SHOW_PERCENT = "show_progress_percent"
        const val KEY_SKIP_SILENCE = "skip_silence_enabled"
        const val KEY_SMART_REWIND_AFTER_PAUSE = "smart_rewind_after_pause"
        const val KEY_PIN_BOTTOM_NAVIGATION = "pin_bottom_navigation"
        const val KEY_HOME_SHOW_NEW = "home_show_new"
        const val KEY_HOME_SHOW_POPULAR = "home_show_popular"
        const val KEY_HOME_SHOW_CONTINUE = "home_show_continue"
        const val KEY_HOME_SHOW_DOWNLOADS = "home_show_downloads"
        const val KEY_HOME_POPULAR_DEFAULT_PERIOD = "home_popular_default_period"
        const val KEY_HOME_CACHE_DAYS = "home_cache_days"
        const val KEY_SHOW_CONTINUE_SERIES = "show_continue_series"
        const val KEY_DOWNLOAD_WIFI_ONLY = "download_wifi_only"
        const val KEY_CATALOG_CACHE_ENABLED = "catalog_cache_enabled"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

private const val MIN_DEFAULT_PLAYBACK_SPEED = 0.5f
private const val MAX_DEFAULT_PLAYBACK_SPEED = 3.0f
private const val DEFAULT_PLAYBACK_SPEED = 1.0f
internal const val DEFAULT_HOME_CACHE_DAYS = 3
internal val HOME_CACHE_DAY_OPTIONS = listOf(1, 2, 3, 5, 10)
internal fun sanitizeHomeCacheDays(value: Int): Int =
    value.takeIf { it in HOME_CACHE_DAY_OPTIONS } ?: DEFAULT_HOME_CACHE_DAYS
internal const val PROGRESS_SAVE_INTERVAL_SECONDS = 10
