package com.example.data.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class UndoSeekPoint(
    val bookId: String,
    val sourceCode: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val expiresAtElapsedMs: Long,
)

data class PlayerUiState(
    val book: BookDetailDto? = null,
    val chapterIndex: Int = 0,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val undoSeek: UndoSeekPoint? = null,
    val error: String? = null
)

data class PlaybackStartEvent(
    val book: BookDetailDto,
    val chapterIndex: Int,
)

internal enum class PlayerPollingMode {
    NONE,
    POSITION,
    TRANSIENT,
}

internal fun playerPollingMode(
    isPlaying: Boolean,
    sleepTimerMode: SleepTimerMode,
    hasUndoSeek: Boolean,
): PlayerPollingMode = when {
    isPlaying -> PlayerPollingMode.POSITION
    sleepTimerMode == SleepTimerMode.MINUTES || hasUndoSeek -> PlayerPollingMode.TRANSIENT
    else -> PlayerPollingMode.NONE
}

internal object PlaybackMetadata {
    const val EXTRA_BOOK_ID = "audiobookred.book_id"
    const val EXTRA_CHAPTER_ID = "audiobookred.chapter_id"
    const val EXTRA_SOURCE_CODE = "audiobookred.source_code"
    const val EXTRA_BOOK_SOURCE_ID = "audiobookred.book_source_id"
    const val EXTRA_LOCAL_FILE = "audiobookred.local_file"
}

/**
 * UI-side facade over the Media3 MediaController.
 *
 * The actual ExoPlayer lives in [PlaybackService], so releasing this facade only
 * disconnects the Activity/ViewModel from the service and never stops active audio.
 * selfapk has one local playback identity, so no profile-switch invalidation gate
 * participates in controller actions or recovery.
 */
class AudiobookPlayerManager(
    context: Context,
    private val resumeStore: PlaybackResumeStore,
    private val downloadStore: DownloadStore,
    cacheStore: LocalCacheStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bookRecovery = PlaybackBookRecoveryRepository(cacheStore, resumeStore)
    private val sleepTimerStore = PlaybackSleepTimerStore(appContext)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingControllerActions = PlaybackControllerActionQueue<MediaController>()
    private var desiredSpeed: Float = 1f
    private var released = false
    private var loadGeneration: Long = 0L
    private val recoveryGuard = PlaybackRecoveryGuard()
    private var pendingPlaybackStart: PendingPlaybackStart? = null
    private var attemptedBookRecovery: BookRecoveryKey? = null
    private var attemptedEmptyPlayerRecovery: EmptyPlayerRecoveryKey? = null
    private var mediaLoadError: String? = null
    private var undoSeekPoint: UndoSeekPoint? = null
    private var positionTickerJob: Job? = null
    private var transientTickerJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _playbackStarts = MutableSharedFlow<PlaybackStartEvent>(extraBufferCapacity = 1)
    val playbackStarts: SharedFlow<PlaybackStartEvent> = _playbackStarts.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
            ) {
                recoveryGuard.invalidate()
            }
            updateState(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateAdaptivePolling(controller)
            if (!isPlaying) return
            mediaLoadError = null
            val pending = pendingPlaybackStart ?: return
            val c = controller ?: return
            val book = pending.book
            val currentBookId = c.currentMediaItem
                ?.mediaMetadata
                ?.extras
                ?.getString(PlaybackMetadata.EXTRA_BOOK_ID)
            if (currentBookId != book.id) return
            val index = c.currentMediaItemIndex
            if (index == C.INDEX_UNSET || index !in book.chapters.indices) return
            pendingPlaybackStart = null
            _playbackStarts.tryEmit(
                PlaybackStartEvent(
                    book = book,
                    chapterIndex = index,
                )
            )
        }
    }

    init {
        scheduleInitialConnectAfterFirstFrame()
    }

    /**
     * Do not start PlaybackService/MediaController in the same frame that creates
     * the root playback ViewModel. Two Choreographer callbacks guarantee that one
     * UI frame can traverse and draw before the UI-side controller connects.
     * Any real playback action bypasses this delay through [withController].
     */
    private fun scheduleInitialConnectAfterFirstFrame() {
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            if (released || controller != null || controllerFuture != null) return@postFrameCallback
            choreographer.postFrameCallback {
                connect()
            }
        }
    }

    private fun connect() {
        if (released || controller != null || controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (released) return@addListener
                val connected = try {
                    future.get()
                } catch (error: Exception) {
                    controllerFuture = null
                    pendingControllerActions.clear()
                    pendingPlaybackStart = null
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = error.message ?: "Не удалось подключить фоновый плеер"
                    )
                    return@addListener
                }
                controllerFuture = null
                controller = connected
                connected.addListener(listener)
                pendingControllerActions.drain(LOCAL_CONTROLLER_GENERATION).forEach { it(connected) }
                updateState(connected)
                updateAdaptivePolling(connected)
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    fun play(
        book: BookDetailDto,
        chapterIndex: Int,
        startPositionMs: Long = 0,
        playbackWriteEpoch: Long? = null,
        recoveryRequest: Long? = null,
    ) {
        if (book.chapters.isEmpty()) return
        if (recoveryRequest != null && !isRecoveryRequestCurrent(recoveryRequest)) return
        recoveryGuard.invalidate()
        val loadRecoveryRequest = recoveryRequest?.let { recoveryGuard.capture() }
        val index = chapterIndex.coerceIn(0, book.chapters.lastIndex)
        val registeredPlaybackWriteEpoch = if (playbackWriteEpoch != null) {
            UserDataRestoreGate.registerPreparedPlaybackTarget(
                bookId = book.id,
                sourceCode = book.selectedSource,
                expectedEpoch = playbackWriteEpoch,
            ) ?: return
        } else {
            UserDataRestoreGate.registerPreparedPlaybackTarget(
                bookId = book.id,
                sourceCode = book.selectedSource,
            )
        }
        mediaLoadError = null
        undoSeekPoint = null

        _state.value = _state.value.copy(
            book = book,
            chapterIndex = index,
            isLoading = true,
            undoSeek = null,
            error = null
        )

        withController { c ->
            loadBook(
                c = c,
                book = book,
                chapterIndex = index,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = true,
                playbackWriteEpoch = registeredPlaybackWriteEpoch,
                recoveryRequest = loadRecoveryRequest,
            )
        }
    }

    fun pause() = withController { c ->
        recoveryGuard.invalidate()
        tracePlaybackEvent("app_pause", c)
        c.pause()
        updateState(c)
    }

    fun toggle() = withController { c ->
        recoveryGuard.invalidate()
        tracePlaybackEvent("app_toggle", c)
        if (c.isPlaying) {
            c.pause()
            updateState(c)
            return@withController
        }

        val book = _state.value.book
        if (!hasUsablePlaybackMedia(
                mediaItemCount = c.mediaItemCount,
                hasCurrentMediaItem = c.currentMediaItem != null,
                currentMediaItemIndex = c.currentMediaItemIndex,
            )
        ) {
            val snapshot = book?.let { resumeStore.get(it.id, it.selectedSource) }
            val recovery = book?.let {
                resolvePlaybackRecoveryPoint(
                    book = it,
                    snapshot = snapshot,
                    fallbackChapterIndex = _state.value.chapterIndex,
                    fallbackPositionMs = _state.value.positionMs,
                    fallbackSpeed = _state.value.speed,
                )
            }
            if (book != null && recovery != null) {
                Log.i(
                    TAG,
                    "toggle: rebuilding empty player for ${book.id} at ${recovery.chapterIndex}/${recovery.positionMs}"
                )
                loadBook(
                    c,
                    book,
                    recovery.chapterIndex,
                    recovery.positionMs,
                    autoPlay = true,
                )
            } else {
                _state.value = PlayerUiState(
                    speed = c.playbackParameters.speed,
                    sleepTimer = currentSleepTimer(),
                    error = "Сессия плеера завершена. Откройте книгу и нажмите «Продолжить»."
                )
            }
            return@withController
        }

        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED || c.playerError != null) {
            book?.let { currentBook ->
                resumeStore.get(currentBook.id, currentBook.selectedSource)?.let { snapshot ->
                    val index = snapshot.chapterIndex.coerceIn(0, c.mediaItemCount - 1)
                    c.seekTo(index, snapshot.positionMs.coerceAtLeast(0L))
                }
            }
            Log.i(TAG, "toggle: preparing stale session state=${c.playbackState} items=${c.mediaItemCount}")
            c.prepare()
        }
        c.playWhenReady = true
        c.play()
        updateState(c)
    }

    fun needsRecovery(): Boolean {
        val c = controller ?: return true
        return playbackSessionNeedsRecovery(
            mediaItemCount = c.mediaItemCount,
            hasCurrentMediaItem = c.currentMediaItem != null,
            currentMediaItemIndex = c.currentMediaItemIndex,
            playbackState = c.playbackState,
            hasPlayerError = c.playerError != null,
        )
    }

    fun seekTo(positionMs: Long) = withController { c ->
        recoveryGuard.invalidate()
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, duration)
        captureUndoSeek(c, c.currentMediaItemIndex, target)
        c.seekTo(target)
        updateState(c)
    }

    fun seekBy(deltaMs: Long) = withController { c ->
        recoveryGuard.invalidate()
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
        val target = (c.currentPosition + deltaMs).coerceIn(0L, duration)
        c.seekTo(target)
        updateState(c)
    }

    fun next() = withController { c ->
        recoveryGuard.invalidate()
        if (c.hasNextMediaItem()) {
            captureUndoSeek(c, c.currentMediaItemIndex + 1, 0L)
            c.seekToNextMediaItem()
        }
        updateState(c)
    }

    fun previous() = withController { c ->
        recoveryGuard.invalidate()
        if (c.currentPosition > 5_000L) {
            captureUndoSeek(c, c.currentMediaItemIndex, 0L)
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            captureUndoSeek(c, c.currentMediaItemIndex - 1, 0L)
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0L)
        }
        updateState(c)
    }

    fun undoLastSeek() = withController { c ->
        recoveryGuard.invalidate()
        val point = currentUndoSeek(c) ?: return@withController
        undoSeekPoint = null
        c.seekTo(point.chapterIndex, point.positionMs)
        updateState(c)
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerStore.setMinutes(minutes)
        refreshTransientState()
    }

    fun setSleepTimerEndOfChapter() {
        val current = _state.value
        val book = current.book ?: return
        sleepTimerStore.setEndOfChapter(
            bookId = book.id,
            sourceCode = book.selectedSource.takeIf { it.isNotBlank() },
            chapterIndex = current.chapterIndex,
        )
        refreshTransientState()
    }

    fun cancelSleepTimer() {
        sleepTimerStore.clear()
        refreshTransientState()
    }

    internal fun captureRecoveryRequest(): Long? {
        val current = controller ?: return null
        if (released || current.playbackState == Player.STATE_ENDED) return null
        return recoveryGuard.capture()
    }

    internal fun isRecoveryRequestCurrent(request: Long): Boolean =
        !released && recoveryGuard.isCurrent(request) &&
            controller != null && controller?.playbackState != Player.STATE_ENDED

    internal fun cancelPendingRecovery() {
        recoveryGuard.invalidate()
    }

    private fun loadBook(
        c: MediaController,
        book: BookDetailDto,
        chapterIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean,
        playbackWriteEpoch: Long? = null,
        recoveryRequest: Long? = null,
    ) {
        val generation = ++loadGeneration
        tracePlaybackEvent(
            "load_requested", c,
            "generation=$generation targetChapter=$chapterIndex targetPosition=$startPositionMs autoPlay=$autoPlay",
        )
        mediaLoadError = null
        if (autoPlay) {
            pendingPlaybackStart = PendingPlaybackStart(
                generation = generation,
                book = book,
            )
        }
        scope.launch {
            val items = try {
                withContext(Dispatchers.IO) {
                    buildPlaybackMediaItems(book, downloadStore)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                clearPendingPlaybackStart(generation)
                if (released || generation != loadGeneration) return@launch
                if (recoveryRequest != null && !isRecoveryRequestCurrent(recoveryRequest)) {
                    updateState(c)
                    return@launch
                }
                val message = error.message ?: "Не удалось подготовить аудио для воспроизведения"
                mediaLoadError = message
                persistOutgoingPlaybackCheckpoint(c)
                c.stop()
                c.clearMediaItems()
                _state.value = _state.value.copy(
                    isLoading = false,
                    isPlaying = false,
                    error = message,
                )
                return@launch
            }
            if (released || generation != loadGeneration || items.isEmpty() ||
                (playbackWriteEpoch != null &&
                    !UserDataRestoreGate.playbackWriteEpochCurrent(playbackWriteEpoch))
            ) {
                clearPendingPlaybackStart(generation)
                return@launch
            }
            // Building media items also suspends. Recheck at the final handoff,
            // before stop/clear/setMediaItems can reopen a completed book.
            if (recoveryRequest != null && !isRecoveryRequestCurrent(recoveryRequest)) {
                clearPendingPlaybackStart(generation)
                tracePlaybackEvent("source_recovery_discarded", c, "stage=media_items")
                updateState(c)
                return@launch
            }
            mediaLoadError = null
            val index = chapterIndex.coerceIn(0, items.lastIndex)
            tracePlaybackEvent(
                "load_applied", c,
                "generation=$generation targetChapter=$index targetPosition=$startPositionMs autoPlay=$autoPlay",
            )
            replacePlaybackMedia(
                persistCurrent = { persistOutgoingPlaybackCheckpoint(c) },
                stopCurrent = { c.stop() },
                clearCurrent = { c.clearMediaItems() },
                setNextMedia = {
                    c.setMediaItems(items, index, startPositionMs.coerceAtLeast(0L))
                    playbackWriteEpoch?.let(UserDataRestoreGate::allowPlaybackWritesAfterFreshPrepare)
                },
                applyNextSpeed = {
                    c.playbackParameters = PlaybackParameters(desiredSpeed)
                },
                prepareNext = { c.prepare() },
            )
            c.playWhenReady = autoPlay
            if (autoPlay) c.play()
            updateState(c)
        }
    }

    /**
     * Persist the outgoing Media3 item before a different book replaces it.
     *
     * This deliberately reads identity from the current MediaItem instead of
     * [_state], because play() has already published the target book while its
     * playlist is being prepared. The write is synchronous so switching A -> B
     * cannot race the service callback and make A appear to restart from zero.
     */
    private fun persistOutgoingPlaybackCheckpoint(c: MediaController) {
        if (!hasUsablePlaybackMedia(
                mediaItemCount = c.mediaItemCount,
                hasCurrentMediaItem = c.currentMediaItem != null,
                currentMediaItemIndex = c.currentMediaItemIndex,
            )
        ) return

        val item = c.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        val positionMs = c.currentPosition.coerceAtLeast(0L)
        if (positionMs <= 0L) return

        val chapterIndex = c.currentMediaItemIndex.coerceAtLeast(0)
        val chapterId = extras.getString(PlaybackMetadata.EXTRA_CHAPTER_ID)
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        val currentDurationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val storedPercent = resumeStore.get(bookId, sourceCode)?.progressPercent ?: 0.0
        val progressPercent = estimateControllerPlaylistProgressPercent(
            c = c,
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            currentDurationMs = currentDurationMs,
            storedProgressPercent = storedPercent,
        )

        resumeStore.saveImmediate(
            bookId = bookId,
            sourceCode = sourceCode,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            speed = c.playbackParameters.speed,
            progressPercent = progressPercent,
        )
    }

    private fun estimateControllerPlaylistProgressPercent(
        c: MediaController,
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        currentDurationMs: Long,
        storedProgressPercent: Double,
    ): Double? {
        if (c.mediaItemCount <= 0 || chapterIndex !in 0 until c.mediaItemCount) return null
        val chapterDurationsMs = ArrayList<Long>(c.mediaItemCount)
        var totalDurationMs = 0L

        for (index in 0 until c.mediaItemCount) {
            val mediaItem = runCatching { c.getMediaItemAt(index) }.getOrNull() ?: return null
            val extras = mediaItem.mediaMetadata.extras ?: return null
            if (extras.getString(PlaybackMetadata.EXTRA_BOOK_ID) != bookId) return null
            if (!extras.containsKey(EXTRA_CHAPTER_DURATION_MS) ||
                !extras.containsKey(EXTRA_BOOK_DURATION_MS)
            ) return null

            chapterDurationsMs += extras.getLong(EXTRA_CHAPTER_DURATION_MS).coerceAtLeast(0L)
            val itemTotalDurationMs = extras.getLong(EXTRA_BOOK_DURATION_MS).coerceAtLeast(0L)
            if (index == 0) {
                totalDurationMs = itemTotalDurationMs
            } else if (itemTotalDurationMs != totalDurationMs) {
                return null
            }
        }

        return estimateOverallProgressPercent(
            chapterDurationsMs = chapterDurationsMs,
            totalDurationMs = totalDurationMs,
            storedProgressPercent = storedProgressPercent,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            currentDurationMs = currentDurationMs,
        )
    }

    internal fun setDefaultSpeed(speed: Float) {
        desiredSpeed = speed.coerceIn(0.5f, 3f)
        _state.value = _state.value.copy(speed = desiredSpeed)
    }

    fun setSpeed(speed: Float) {
        desiredSpeed = speed.coerceIn(0.5f, 3f)
        _state.value = _state.value.copy(speed = desiredSpeed)
        withController { c ->
            c.playbackParameters = PlaybackParameters(desiredSpeed)
            updateState(c)
        }
    }

    fun release() {
        if (released) return
        released = true
        pendingControllerActions.clear()
        pendingPlaybackStart = null
        attemptedBookRecovery = null
        attemptedEmptyPlayerRecovery = null
        undoSeekPoint = null
        mediaLoadError = null
        stopPositionTicker()
        stopTransientTicker()
        sleepTimerStore.close()
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        scope.cancel()
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            action(c)
        } else {
            pendingControllerActions.enqueue(LOCAL_CONTROLLER_GENERATION, action)
            connect()
        }
    }

    private fun clearPendingPlaybackStart(generation: Long) {
        if (pendingPlaybackStart?.generation == generation) {
            pendingPlaybackStart = null
        }
    }

    private fun captureUndoSeek(c: MediaController, newChapterIndex: Int, newPositionMs: Long) {
        if (!hasUsablePlaybackMedia(c.mediaItemCount, c.currentMediaItem != null, c.currentMediaItemIndex)) return
        val oldChapterIndex = c.currentMediaItemIndex
        val oldPositionMs = c.currentPosition.coerceAtLeast(0L)
        if (!shouldOfferUndoSeek(oldChapterIndex, oldPositionMs, newChapterIndex, newPositionMs)) return
        val extras = c.currentMediaItem?.mediaMetadata?.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        undoSeekPoint = UndoSeekPoint(
            bookId = bookId,
            sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty(),
            chapterIndex = oldChapterIndex,
            positionMs = oldPositionMs,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + UNDO_SEEK_WINDOW_MS,
        )
    }

    private fun currentUndoSeek(player: Player? = controller): UndoSeekPoint? {
        val point = undoSeekPoint ?: return null
        if (SystemClock.elapsedRealtime() >= point.expiresAtElapsedMs) {
            undoSeekPoint = null
            return null
        }
        val current = player ?: return point
        val extras = current.currentMediaItem?.mediaMetadata?.extras ?: return null
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        if (bookId != point.bookId || sourceCode != point.sourceCode) {
            undoSeekPoint = null
            return null
        }
        return point
    }

    private fun currentSleepTimer(): SleepTimerState {
        val timer = sleepTimerStore.state.value
        if (timer.mode == SleepTimerMode.MINUTES && timer.remainingMs() <= 0L) return SleepTimerState()
        return timer
    }

    private fun refreshTransientState() {
        _state.value = _state.value.copy(
            sleepTimer = currentSleepTimer(),
            undoSeek = currentUndoSeek(),
        )
        updateAdaptivePolling(controller)
    }

    private fun updateAdaptivePolling(player: Player?) {
        val mode = playerPollingMode(
            isPlaying = player?.isPlaying == true,
            sleepTimerMode = currentSleepTimer().mode,
            hasUndoSeek = currentUndoSeek(player) != null,
        )
        when (mode) {
            PlayerPollingMode.POSITION -> {
                stopTransientTicker()
                ensurePositionTicker()
            }
            PlayerPollingMode.TRANSIENT -> {
                stopPositionTicker()
                ensureTransientTicker()
            }
            PlayerPollingMode.NONE -> {
                stopPositionTicker()
                stopTransientTicker()
            }
        }
    }

    private fun ensurePositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val current = controller ?: break
                if (!current.isPlaying) break
                updateState(current)
            }
        }
    }

    private fun ensureTransientTicker() {
        if (transientTickerJob?.isActive == true) return
        transientTickerJob = scope.launch {
            while (isActive) {
                delay(TRANSIENT_TICK_MS)
                val current = controller
                if (current?.isPlaying == true) break
                refreshTransientState()
                if (
                    playerPollingMode(
                        isPlaying = false,
                        sleepTimerMode = currentSleepTimer().mode,
                        hasUndoSeek = undoSeekPoint != null,
                    ) != PlayerPollingMode.TRANSIENT
                ) {
                    break
                }
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun stopTransientTicker() {
        transientTickerJob?.cancel()
        transientTickerJob = null
    }

    private fun recoverBookFromCache(bookId: String, sourceCode: String?) {
        val source = playbackSourceOrNull(sourceCode)
        val key = BookRecoveryKey(bookId, source)
        if (attemptedBookRecovery == key) return
        attemptedBookRecovery = key

        scope.launch {
            val recovered = bookRecovery.recoverBook(bookId, source) ?: return@launch
            if (released) return@launch

            val c = controller ?: return@launch
            val currentExtras = c.currentMediaItem?.mediaMetadata?.extras ?: return@launch
            if (currentExtras.getString(PlaybackMetadata.EXTRA_BOOK_ID) != bookId) return@launch
            val currentSource = playbackSourceOrNull(
                currentExtras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE)
            )
            if (!playbackSourceMatches(currentSource, source)) return@launch

            _state.value = _state.value.copy(book = recovered)
            updateState(c)
        }
    }

    private fun recoverEmptyPlayerFromLatestSnapshot(player: Player) {
        val snapshot = resumeStore.latestSnapshot() ?: return
        val source = playbackSourceOrNull(snapshot.sourceCode)
        val key = EmptyPlayerRecoveryKey(
            bookId = snapshot.bookId,
            sourceCode = source,
            savedAtMs = snapshot.savedAtMs,
        )
        if (attemptedEmptyPlayerRecovery == key) return
        attemptedEmptyPlayerRecovery = key

        scope.launch {
            val recovered = bookRecovery.recoverSnapshot(snapshot) ?: return@launch
            if (released) return@launch

            val c = controller ?: return@launch
            if (hasUsablePlaybackMedia(
                    mediaItemCount = c.mediaItemCount,
                    hasCurrentMediaItem = c.currentMediaItem != null,
                    currentMediaItemIndex = c.currentMediaItemIndex,
                )
            ) return@launch

            val recovery = resolvePlaybackRecoveryPoint(
                book = recovered,
                snapshot = snapshot,
                fallbackChapterIndex = 0,
                fallbackPositionMs = 0L,
                fallbackSpeed = desiredSpeed,
            ) ?: return@launch
            _state.value = PlayerUiState(
                book = recovered,
                chapterIndex = recovery.chapterIndex,
                isLoading = false,
                isPlaying = false,
                positionMs = recovery.positionMs,
                durationMs = recovery.durationMs,
                speed = recovery.speed,
                sleepTimer = currentSleepTimer(),
                undoSeek = currentUndoSeek(c),
                error = mediaLoadError ?: player.playerError?.message,
            )
        }
    }

    private fun updateState(player: Player) {
        updateAdaptivePolling(player)
        val hasMedia = hasUsablePlaybackMedia(
            mediaItemCount = player.mediaItemCount,
            hasCurrentMediaItem = player.currentMediaItem != null,
            currentMediaItemIndex = player.currentMediaItemIndex,
        )

        if (!hasMedia) {
            attemptedBookRecovery = null
            val cachedBook = _state.value.book
            val snapshot = cachedBook?.let { resumeStore.get(it.id, it.selectedSource) }
            val recovery = cachedBook?.let {
                resolvePlaybackRecoveryPoint(
                    book = it,
                    snapshot = snapshot,
                    fallbackChapterIndex = _state.value.chapterIndex,
                    fallbackPositionMs = _state.value.positionMs,
                    fallbackSpeed = _state.value.speed,
                )
            }
            if (cachedBook != null && recovery != null) {
                _state.value = _state.value.copy(
                    book = cachedBook,
                    chapterIndex = recovery.chapterIndex,
                    isLoading = false,
                    isPlaying = false,
                    positionMs = recovery.positionMs,
                    durationMs = recovery.durationMs,
                    speed = recovery.speed,
                    sleepTimer = currentSleepTimer(),
                    undoSeek = currentUndoSeek(player),
                    error = mediaLoadError ?: player.playerError?.message
                )
            } else {
                recoverEmptyPlayerFromLatestSnapshot(player)
                _state.value = PlayerUiState(
                    speed = desiredSpeed,
                    sleepTimer = currentSleepTimer(),
                    error = mediaLoadError ?: player.playerError?.message
                )
            }
            return
        }

        attemptedEmptyPlayerRecovery = null
        val currentExtras = player.currentMediaItem?.mediaMetadata?.extras
        val currentBookId = currentExtras?.getString(PlaybackMetadata.EXTRA_BOOK_ID)
        val currentSource = playbackSourceOrNull(
            currentExtras?.getString(PlaybackMetadata.EXTRA_SOURCE_CODE)
        )
        val stateBook = _state.value.book
            ?.takeIf { book ->
                (currentBookId.isNullOrBlank() || book.id == currentBookId) &&
                    playbackSourceMatches(book.selectedSource, currentSource)
            }
        if (stateBook == null && !currentBookId.isNullOrBlank()) {
            recoverBookFromCache(currentBookId, currentSource)
        }
        val book = stateBook
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val resume = if (book != null && shouldUsePlaybackResumeFallback(
                playbackState = player.playbackState,
                isPlaying = player.isPlaying,
                positionMs = currentPosition,
            )
        ) {
            resumeStore.get(book.id, book.selectedSource)
        } else {
            null
        }
        val activePoint = resolvePlaybackActivePoint(
            book = book,
            currentChapterIndex = player.currentMediaItemIndex,
            currentPositionMs = currentPosition,
            currentDurationMs = player.duration,
            resume = resume,
        )

        _state.value = _state.value.copy(
            book = book,
            chapterIndex = activePoint.chapterIndex,
            isLoading = player.playbackState == Player.STATE_BUFFERING,
            isPlaying = player.isPlaying,
            positionMs = activePoint.positionMs,
            durationMs = activePoint.durationMs,
            speed = player.playbackParameters.speed,
            sleepTimer = currentSleepTimer(),
            undoSeek = currentUndoSeek(player),
            error = mediaLoadError ?: player.playerError?.message
        )
    }

    private data class PendingPlaybackStart(
        val generation: Long,
        val book: BookDetailDto,
    )

    private data class BookRecoveryKey(
        val bookId: String,
        val sourceCode: String?,
    )

    private data class EmptyPlayerRecoveryKey(
        val bookId: String,
        val sourceCode: String?,
        val savedAtMs: Long,
    )

    private companion object {
        const val TAG = "AudioBookRedPlayer"
        const val LOCAL_CONTROLLER_GENERATION = 0L
        const val UNDO_SEEK_WINDOW_MS = 10_000L
        const val POSITION_TICK_MS = 500L
        const val TRANSIENT_TICK_MS = 1_000L
    }
}
