package com.example.data.player

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.api.ApiClient
import com.example.data.local.DownloadStore
import com.example.data.local.LibraryCacheStore
import com.example.data.local.LocalCacheStore
import com.example.data.repository.PlaybackLibraryRepository
import com.example.data.settings.PROGRESS_SAVE_INTERVAL_SECONDS
import com.example.data.settings.PlayerSettingsStore
import com.example.data.torrserve.TorrServeClient
import com.example.domain.playback.PreparePlaybackUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Owns playback independently from the Activity.
 *
 * selfapk keeps playback state entirely on this device: an immediate resume
 * checkpoint is written to PlaybackResumeStore and serialized checkpoints are
 * mirrored into Room. No profile, sync outbox or backend write participates.
 *
 * The same player/session also serves a small driver-safe MediaLibrary tree to
 * Android Auto, so Bluetooth/headset playback and car playback never diverge
 * into separate player instances.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progressController: PlaybackProgressController
    private lateinit var sleepTimerController: PlaybackSleepTimerController
    private lateinit var androidAutoController: AndroidAutoSessionController
    private var pauseAnchor: PauseAnchor? = null

    @Inject lateinit var settingsStore: PlayerSettingsStore
    @Inject lateinit var resumeStore: PlaybackResumeStore
    @Inject lateinit var localCacheStore: LocalCacheStore
    @Inject lateinit var libraryCacheStore: LibraryCacheStore
    @Inject lateinit var downloadStore: DownloadStore
    @Inject lateinit var playbackLibraryRepository: PlaybackLibraryRepository
    @Inject lateinit var preparePlayback: PreparePlaybackUseCase
    @Inject lateinit var torrServeClient: TorrServeClient

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        // Media3 uses the same guarded OkHttp policy as the other standalone
        // transports. Every redirect hop is checked before a socket is opened,
        // so a provider redirect cannot escape into a retired backend/IP URL.
        val httpClient = ApiClient.createHttpClient(readTimeoutSeconds = 30L)
        val httpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent("AudioBookRed/${BuildConfig.VERSION_NAME} (Android)")
        val torrServeHttpDataSourceFactory = OkHttpDataSource.Factory(
            torrServeClient.streamingHttpClient()
        ).setUserAgent("AudioBookRed/${BuildConfig.VERSION_NAME} (Android)")
        val remoteDataSourceFactory = RedirectToChunkedDataSource.Factory(
            upstreamFactory = httpDataSourceFactory,
            rutrackerUpstreamFactory = torrServeHttpDataSourceFactory,
        )
        val dataSourceFactory = DefaultDataSource.Factory(this, remoteDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(dataSourceFactory)
                .build()
        )

        player = ExoPlayer.Builder(this, AudiobookRenderersFactory(this))
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                setSkipSilenceEnabled(settingsStore.state.value.skipSilenceEnabled)
                setSeekBackIncrementMs(settingsStore.state.value.rewindSeconds.toLong() * 1_000L)
                setSeekForwardIncrementMs(settingsStore.state.value.forwardSeconds.toLong() * 1_000L)

            }

        progressController = PlaybackProgressController(
            player = player,
            resumeStore = resumeStore,
            localCacheStore = localCacheStore,
            scope = serviceScope,
        ).also(PlaybackProgressController::start)
        sleepTimerController = PlaybackSleepTimerController(
            player = player,
            store = PlaybackSleepTimerStore(this),
            settingsStore = settingsStore,
            scope = serviceScope,
            saveCheckpoint = {
                progressController.saveLocalSnapshot()
                progressController.saveProgressAsync()
            },
        ).also {
            it.start()
            it.applyPauseAtEndPolicy()
        }
        androidAutoController = AndroidAutoSessionController(
            player = player,
            library = AndroidAutoMediaLibrary(
                cacheStore = localCacheStore,
                libraryStore = libraryCacheStore,
                downloadStore = downloadStore,
                resumeStore = resumeStore,
            ),
            resumeStore = resumeStore,
            localCacheStore = localCacheStore,
            downloadStore = downloadStore,
            playbackLibraryRepository = playbackLibraryRepository,
            preparePlayback = preparePlayback,
            scope = serviceScope,
        )

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                tracePlaybackEvent("is_playing", player, "value=$isPlaying")
                if (isPlaying) {
                    androidAutoController.recordPendingResumptionPlaybackStarted()
                } else {
                    progressController.saveLocalSnapshot()
                    progressController.saveProgressAsync()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                tracePlaybackEvent("play_when_ready", player, "value=$playWhenReady reason=$reason")
                if (playWhenReady) {
                    maybeApplySmartRewindAfterPause()
                } else {
                    capturePauseAnchor()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                tracePlaybackEvent("playback_state", player, "value=$playbackState")
                if (playbackState == Player.STATE_ENDED) {
                    progressController.saveLocalSnapshot()
                    val trulyCompleted =
                        player.currentMediaItemIndex == player.mediaItemCount - 1
                    progressController.saveProgressAsync(
                        completedOverride = trulyCompleted
                    )
                    androidAutoController.notifyContinueIfChanged(
                        player.currentMediaItem,
                        force = true,
                    )
                    sleepTimerController.onPlaybackEnded()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                tracePlaybackEvent("media_transition", player, "reason=$reason")
                androidAutoController.notifyContinueIfChanged(mediaItem)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                tracePlaybackEvent("repeat_mode", player, "value=$repeatMode")
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                tracePlaybackEvent("shuffle_mode", player, "value=$shuffleModeEnabled")
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                tracePlaybackEvent("suppression", player, "reason=$playbackSuppressionReason")
            }

            override fun onPlayerError(error: PlaybackException) {
                tracePlaybackEvent("player_error", player, "code=${error.errorCode}")
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                tracePlaybackEvent("timeline", player, "reason=$reason")
                progressController.onTimelineChanged()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                tracePlaybackEvent(
                    "position_discontinuity", player,
                    "reason=$reason from=${oldPosition.mediaItemIndex}:${oldPosition.positionMs}" +
                        " to=${newPosition.mediaItemIndex}:${newPosition.positionMs}",
                )
                when (reason) {
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                        progressController.saveAutoTransitionCheckpoint(oldPosition)
                        sleepTimerController.onAutoTransition(oldPosition.mediaItemIndex)
                    }
                    Player.DISCONTINUITY_REASON_SEEK -> {
                        progressController.saveLocalSnapshot(allowZero = true)
                        progressController.saveProgressAsync(allowZero = true)
                        sleepTimerController.onSeekTargetChanged(newPosition.mediaItemIndex)
                    }
                }
            }

            override fun onPlaybackParametersChanged(
                playbackParameters: PlaybackParameters,
            ) {
                if (!player.isPlaying) {
                    progressController.saveLocalSnapshot()
                    progressController.saveProgressAsync()
                }
            }
        })

        tracePlaybackEvent("service_created", player, "version=${BuildConfig.VERSION_NAME}")

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val initialSettings = settingsStore.state.value
        mediaSession = MediaLibrarySession.Builder(this, player, androidAutoController.callback)
            .setBitmapLoader(bitmapLoader)
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(
                audiobookMediaButtonPreferences(
                    rewindSeconds = initialSettings.rewindSeconds,
                    forwardSeconds = initialSettings.forwardSeconds,
                )
            )
            .build()
        mediaSession?.let(androidAutoController::attachSession)

        serviceScope.launch {
            settingsStore.state.collectLatest { settings ->
                player.setSkipSilenceEnabled(settings.skipSilenceEnabled)
                player.setSeekBackIncrementMs(settings.rewindSeconds.toLong() * 1_000L)
                player.setSeekForwardIncrementMs(settings.forwardSeconds.toLong() * 1_000L)
                mediaSession?.setMediaButtonPreferences(
                    audiobookMediaButtonPreferences(
                        rewindSeconds = settings.rewindSeconds,
                        forwardSeconds = settings.forwardSeconds,
                    )
                )
                sleepTimerController.applyPauseAtEndPolicy()
            }
        }

        // Notify subscribed Android Auto/AAOS browsers when normalized Library
        // rows change on the phone. The Library-only invalidation stream excludes
        // cached progress payloads, avoiding rebuilds on periodic checkpoints.
        serviceScope.launch(Dispatchers.IO) {
            combine(
                libraryCacheStore.observeLibraryOnly(),
                downloadStore.observeBooks(),
            ) { library, downloads ->
                Triple(
                    library.favorites.map { it.id },
                    library.history.map { it.id },
                    downloads
                        .filter { it.deletedAtMs == null && it.state == "completed" }
                        .map { it.bookSourceId },
                )
            }
                .distinctUntilChanged()
                .collectLatest {
                    withContext(Dispatchers.Main.immediate) {
                        androidAutoController.notifyLibraryChanged()
                    }
                }
        }

        // Player.Listener does not emit normal playback progression. Poll at
        // the fixed internal checkpoint cadence, but only while media is
        // actually advancing. Paused/idle books are persisted by events.
        serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_SECONDS * 1_000L)
                if (player.isPlaying) progressController.saveProgressAsync()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    // Do not stop playback when the task is removed. MediaLibraryService keeps
    // active playback alive and exposes the same standard system media controls.
    override fun onTaskRemoved(rootIntent: Intent?) {
        progressController.saveLocalSnapshot()
        progressController.saveProgressAsync()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::player.isInitialized) tracePlaybackEvent("service_destroyed", player)
        // PlaybackResumeStore is written synchronously before teardown, so the
        // latest position survives even if queued Room work is cancelled below.
        if (::progressController.isInitialized) {
            progressController.saveLocalSnapshot()
            progressController.close()
        }
        if (::sleepTimerController.isInitialized) sleepTimerController.close()
        if (::androidAutoController.isInitialized) androidAutoController.detachSession()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun capturePauseAnchor() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        pauseAnchor = PauseAnchor(
            bookId = bookId,
            sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty(),
            chapterIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            pausedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun maybeApplySmartRewindAfterPause() {
        val anchor = pauseAnchor ?: return
        pauseAnchor = null
        if (!settingsStore.state.value.smartRewindAfterPause) return
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        val chapterIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        if (bookId != anchor.bookId || sourceCode != anchor.sourceCode || chapterIndex != anchor.chapterIndex) return
        // A manual seek while paused is intentional and must not be shifted again on resume.
        if (abs(currentPosition - anchor.positionMs) > 2_500L) return

        val pauseDuration = (SystemClock.elapsedRealtime() - anchor.pausedAtElapsedMs).coerceAtLeast(0L)
        val rewindMs = smartRewindMsForPause(pauseDuration)
        if (rewindMs <= 0L || currentPosition <= 0L) return
        player.seekTo((currentPosition - rewindMs).coerceAtLeast(0L))
    }

    private data class PauseAnchor(
        val bookId: String,
        val sourceCode: String,
        val chapterIndex: Int,
        val positionMs: Long,
        val pausedAtElapsedMs: Long,
    )


}
