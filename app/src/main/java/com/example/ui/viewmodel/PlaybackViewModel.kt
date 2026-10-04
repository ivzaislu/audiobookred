package com.example.ui.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.BookmarkUiItem
import com.example.data.player.AudiobookPlayerManager
import com.example.data.player.PlaybackRefreshRecoveryGate
import com.example.data.player.tracePlaybackEvent
import com.example.data.repository.AudiobookRepository
import com.example.data.repository.PlaybackLibraryRepository
import com.example.data.torrserve.TorrServePreparationStage
import com.example.domain.playback.PreparedPlayback
import com.example.domain.playback.PreparePlaybackUseCase
import com.example.domain.playback.RememberPlaybackSpeedUseCase
import com.example.ui.shouldMarkTorrServePreparationReady
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PlaybackPreparationUiState(
    val active: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val step: Int = 0,
    val totalSteps: Int = 0,
    val ready: Boolean = false,
)

private data class TorrServePlaybackHandoff(
    val generation: Long,
    val bookId: String,
)

private data class ProactivePlaybackRefresh(
    val identity: String,
    val startedAtElapsedMs: Long,
    val deferred: Deferred<BookDetailDto?>,
)

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val player: AudiobookPlayerManager,
    private val playbackLibraryRepository: PlaybackLibraryRepository,
    private val preparePlayback: PreparePlaybackUseCase,
    private val rememberPlaybackSpeed: RememberPlaybackSpeedUseCase,
    private val audiobookRepository: AudiobookRepository,
    private val cacheStore: LocalCacheStore,
    private val downloadStore: DownloadStore,
) : ViewModel() {
    val playerState = player.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _preparationState = MutableStateFlow(PlaybackPreparationUiState())
    val preparationState: StateFlow<PlaybackPreparationUiState> = _preparationState.asStateFlow()
    private val preparationGeneration = AtomicLong(0L)
    private var torrServePlaybackHandoff: TorrServePlaybackHandoff? = null

    private val refreshRecoveryGate = PlaybackRefreshRecoveryGate()
    private var refreshRecoveryInFlight = false
    private var proactivePlaybackRefresh: ProactivePlaybackRefresh? = null

    init {
        viewModelScope.launch {
            player.playbackStarts.collect { event ->
                markTorrServePlaybackStarted(
                    bookId = event.book.id,
                    sourceCode = event.book.selectedSource,
                )
                try {
                    playbackLibraryRepository.recordPlaybackStarted(
                        book = event.book,
                        chapterIndex = event.chapterIndex,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _messages.emit(PLAYBACK_ACTIVITY_ERROR)
                }
            }
        }
        viewModelScope.launch {
            playerState.collect { state ->
                updateTorrServePlaybackLifecycle(state)
                val book = state.book
                val hasPlaybackError = !state.error.isNullOrBlank() && !state.isPlaying
                val recoveryRequest = if (hasPlaybackError) player.captureRecoveryRequest() else null
                val isLocalFile = if (book != null && hasPlaybackError) {
                    hasUsableLocalPlaybackFile(book, state.chapterIndex)
                } else {
                    false
                }
                val recoveryStillCurrent = !hasPlaybackError ||
                    (recoveryRequest != null && player.isRecoveryRequestCurrent(recoveryRequest))
                if (recoveryStillCurrent && refreshRecoveryGate.shouldRecover(
                        bookId = book?.id,
                        sourceCode = book?.selectedSource,
                        isPlaying = state.isPlaying,
                        isLocalFile = isLocalFile,
                        error = state.error,
                    ) && book != null
                ) {
                    refreshPlaybackSource(book, emitFailure = false, expectedRecoveryRequest = recoveryRequest)
                }
            }
        }
    }

    fun resume(
        book: BookDetailDto,
        onStarted: (() -> Unit)? = null,
    ) = viewModelScope.launch {
        val torrServePreparationStarted = AtomicBoolean(false)
        if (
            prepareAndPlay { onTorrServeStage ->
                preparePlayback.resume(book) { stage ->
                    torrServePreparationStarted.set(true)
                    onTorrServeStage(stage)
                }
            }
        ) {
            if (!torrServePreparationStarted.get()) {
                onStarted?.invoke()
            }
        }
    }

    fun resume(bookId: String, onStarted: (() -> Unit)? = null) = viewModelScope.launch {
        val torrServePreparationStarted = AtomicBoolean(false)
        if (
            prepareAndPlay { onTorrServeStage ->
                preparePlayback.resume(bookId) { stage ->
                    torrServePreparationStarted.set(true)
                    onTorrServeStage(stage)
                }
            }
        ) {
            if (!torrServePreparationStarted.get()) {
                onStarted?.invoke()
            }
        }
    }

    fun play(book: BookDetailDto, chapterIndex: Int) = viewModelScope.launch {
        prepareAndPlay { preparePlayback.chapter(book, chapterIndex) }
    }

    fun playBookmark(
        item: BookmarkUiItem,
        onStarted: (() -> Unit)? = null,
    ) = viewModelScope.launch {
        val torrServePreparationStarted = AtomicBoolean(false)
        if (
            prepareAndPlay { onTorrServeStage ->
                preparePlayback.bookmark(item) { stage ->
                    torrServePreparationStarted.set(true)
                    onTorrServeStage(stage)
                }
            }
        ) {
            if (!torrServePreparationStarted.get()) {
                onStarted?.invoke()
            }
        }
    }

    fun playBookmark(
        book: BookDetailDto,
        bookmark: BookmarkDto,
        onStarted: (() -> Unit)? = null,
    ) = viewModelScope.launch {
        val torrServePreparationStarted = AtomicBoolean(false)
        if (
            prepareAndPlay { onTorrServeStage ->
                preparePlayback.bookmark(book, bookmark) { stage ->
                    torrServePreparationStarted.set(true)
                    onTorrServeStage(stage)
                }
            }
        ) {
            if (!torrServePreparationStarted.get()) {
                onStarted?.invoke()
            }
        }
    }

    fun togglePlayback() = viewModelScope.launch {
        val before = playerState.value
        val book = before.book

        if (!before.isPlaying && book != null && player.needsRecovery()) {
            val recoveryRequest = player.captureRecoveryRequest()
            val isLocalFile = hasUsableLocalPlaybackFile(book, before.chapterIndex)
            if (!before.error.isNullOrBlank() && !isLocalFile) {
                if (recoveryRequest != null && player.isRecoveryRequestCurrent(recoveryRequest)) {
                    refreshPlaybackSource(book, emitFailure = true, expectedRecoveryRequest = recoveryRequest)
                }
            } else {
                prepareAndPlay { onTorrServeStage ->
                    preparePlayback.resume(book, onTorrServeStage)
                }
            }
            return@launch
        }

        player.toggle()
        if (!before.isPlaying && book != null) {
            startProactivePlaybackRefresh(book, before.chapterIndex)
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        player.seekBy(deltaMs)
    }

    fun nextChapter() {
        player.next()
    }

    fun previousChapter() {
        player.previous()
    }

    fun undoLastSeek() {
        player.undoLastSeek()
    }

    fun setSleepTimerMinutes(minutes: Int) {
        player.setSleepTimerMinutes(minutes)
    }

    fun setSleepTimerEndOfChapter() {
        player.setSleepTimerEndOfChapter()
    }

    fun cancelSleepTimer() {
        player.cancelSleepTimer()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setSpeed(speed)
        val state = playerState.value
        val book = state.book ?: return
        rememberPlaybackSpeed.remember(
            book = book,
            chapterIndex = state.chapterIndex,
            positionMs = state.positionMs,
            speed = state.speed,
        )
    }

    fun addBookmark(note: String = "") = viewModelScope.launch {
        val state = playerState.value
        val book = state.book ?: return@launch
        playbackLibraryRepository.addBookmark(
            book = book,
            chapterIndex = state.chapterIndex,
            positionMs = state.positionMs,
            note = note,
        )
    }

    fun playDownloadedBook(bookSourceId: String) = viewModelScope.launch {
        prepareAndPlay { preparePlayback.downloaded(bookSourceId) }
    }

    private fun startProactivePlaybackRefresh(
        book: BookDetailDto,
        chapterIndex: Int,
    ) {
        val identity = playbackRefreshIdentity(book)
        val existing = proactivePlaybackRefresh
        if (existing?.identity == identity && existing.deferred.isActive) return
        if (existing?.deferred?.isActive == true) {
            existing.deferred.cancel()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val deferred = viewModelScope.async {
            if (hasUsableLocalPlaybackFile(book, chapterIndex)) {
                return@async null
            }
            Log.i(TAG, "playback refresh: background start $identity")
            try {
                fetchFreshPlaybackBook(book).also {
                    Log.i(TAG, "playback refresh: cached $identity")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "playback refresh: background failed $identity", error)
                null
            }
        }
        proactivePlaybackRefresh = ProactivePlaybackRefresh(
            identity = identity,
            startedAtElapsedMs = startedAt,
            deferred = deferred,
        )
    }

    private suspend fun refreshPlaybackSource(
        book: BookDetailDto,
        emitFailure: Boolean,
        expectedRecoveryRequest: Long? = null,
    ): Boolean {
        if (refreshRecoveryInFlight) return false
        val recoveryRequest = expectedRecoveryRequest ?: player.captureRecoveryRequest() ?: return false
        if (!player.isRecoveryRequestCurrent(recoveryRequest)) return false
        tracePlaybackEvent(
            "source_recovery_requested",
            details = "manual=$emitFailure chapter=${playerState.value.chapterIndex}" +
                " position=${playerState.value.positionMs}",
        )
        refreshRecoveryInFlight = true
        val preparationEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        return try {
            val sourceHint = book.selectedSource.trim().takeIf { it.isNotBlank() }
            val fresh = freshPlaybackBookForRecovery(book)
            val current = playerState.value.book
            val stillSameBook = current?.id == book.id
            val stillSameSource = sourceHint == null ||
                current?.selectedSource.orEmpty().trim().equals(sourceHint, ignoreCase = true)
            if (!stillSameBook || !stillSameSource || !player.isRecoveryRequestCurrent(recoveryRequest)) {
                tracePlaybackEvent("source_recovery_discarded", details = "stage=source_refresh")
                false
            } else {
                // Resume selection can itself suspend and may choose the start
                // of a completed book. It must not outlive the playback it repairs.
                executePreparedPlayback(
                    prepare = { preparePlayback.resume(fresh) },
                    preparationEpoch = preparationEpoch,
                    isCurrent = { player.isRecoveryRequestCurrent(recoveryRequest) },
                    play = { prepared ->
                        tracePlaybackEvent(
                            "source_recovery_applied",
                            details = "targetChapter=${prepared.chapterIndex} targetPosition=${prepared.positionMs}" +
                                " currentChapter=${playerState.value.chapterIndex}" +
                                " currentPosition=${playerState.value.positionMs}",
                        )
                        playPrepared(
                            prepared,
                            startBackgroundRefresh = false,
                            recoveryRequest = recoveryRequest,
                        )
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!player.isRecoveryRequestCurrent(recoveryRequest)) return false
            Log.e(TAG, "playback source refresh failed", error)
            if (emitFailure) {
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.java.simpleName.takeIf(String::isNotBlank)
                    ?: "Не удалось обновить источник воспроизведения"
                _messages.emit(detail)
            }
            false
        } finally {
            refreshRecoveryInFlight = false
        }
    }

    private suspend fun freshPlaybackBookForRecovery(book: BookDetailDto): BookDetailDto {
        val identity = playbackRefreshIdentity(book)
        val proactive = proactivePlaybackRefresh?.takeIf { refresh ->
            refresh.identity == identity &&
                SystemClock.elapsedRealtime() - refresh.startedAtElapsedMs <= PROACTIVE_REFRESH_REUSE_WINDOW_MS
        }
        if (proactive != null) {
            val refreshed = try {
                proactive.deferred.await()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (refreshed != null) {
                Log.i(TAG, "playback refresh: reusing background result $identity")
                return refreshed
            }
        }
        return fetchFreshPlaybackBook(book)
    }

    private suspend fun fetchFreshPlaybackBook(book: BookDetailDto): BookDetailDto {
        val sourceHint = book.selectedSource.trim().takeIf { it.isNotBlank() }
        return audiobookRepository.book(book.id, sourceHint).also { refreshed ->
            // Book detail and playback progress are stored separately. Updating URLs/metadata
            // in the cache must never replace the user's local checkpoint.
            cacheStore.writeBook(refreshed)
        }
    }

    private fun playbackRefreshIdentity(book: BookDetailDto): String {
        return "${book.id}|${book.selectedSource.trim().lowercase()}"
    }

    private suspend fun hasUsableLocalPlaybackFile(
        book: BookDetailDto,
        chapterIndex: Int,
    ): Boolean {
        val bookSourceId = book.selectedBookSourceId.trim().takeIf { it.isNotBlank() }
            ?: return false
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return false
        val rows = try {
            downloadStore.files(bookSourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val completed = rows.filter { it.state == "completed" }
        val row = completed.firstOrNull { it.chapterId == chapter.id }
            ?: completed.firstOrNull { it.chapterPosition == chapter.position }
            ?: return false
        return downloadStore.playbackUri(row) != null
    }

    private suspend fun prepareAndPlay(
        block: suspend ((TorrServePreparationStage) -> Unit) -> PreparedPlayback?,
    ): Boolean {
        val preparationEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        val generation = preparationGeneration.incrementAndGet()
        player.cancelPendingRecovery()
        torrServePlaybackHandoff = null
        _preparationState.value = PlaybackPreparationUiState()

        val onTorrServeStage: (TorrServePreparationStage) -> Unit = { stage ->
            updateTorrServePreparationStage(generation, stage)
        }

        return try {
            val started = executePreparedPlayback(
                prepare = { block(onTorrServeStage) },
                preparationEpoch = preparationEpoch,
                isCurrent = { preparationGeneration.get() == generation },
            ) { prepared ->
                if (
                    _preparationState.value.active &&
                    prepared.book.selectedSource.equals("rutracker", ignoreCase = true)
                ) {
                    torrServePlaybackHandoff = TorrServePlaybackHandoff(
                        generation = generation,
                        bookId = prepared.book.id,
                    )
                    updateTorrServePreparationStage(
                        generation,
                        TorrServePreparationStage.BUFFERING,
                    )
                }
                playPrepared(prepared, startBackgroundRefresh = true)
            }
            if (!started && preparationGeneration.get() == generation) {
                val message = if (!UserDataRestoreGate.playbackWriteEpochCurrent(preparationEpoch)) {
                    "Воспроизведение отложено: восстановление пользовательских данных ещё не завершено."
                } else {
                    "Источник не вернул данные для запуска воспроизведения."
                }
                Log.w(TAG, "playback preparation rejected: $message")
                _messages.emit(message)
            }
            started
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "playback preparation failed", error)
            val detail = error.message?.takeIf { it.isNotBlank() }
                ?: error::class.java.simpleName.takeIf(String::isNotBlank)
                ?: PREPARATION_ERROR
            _messages.emit(detail)
            false
        } finally {
            if (
                preparationGeneration.get() == generation &&
                torrServePlaybackHandoff?.generation != generation
            ) {
                _preparationState.value = PlaybackPreparationUiState()
            }
        }
    }

    private fun updateTorrServePreparationStage(
        generation: Long,
        stage: TorrServePreparationStage,
    ) {
        if (preparationGeneration.get() != generation) return
        _preparationState.value = PlaybackPreparationUiState(
            active = true,
            title = "TorrServer загрузка",
            detail = torrServePreparationLabel(stage),
            step = torrServePreparationStep(stage),
            totalSteps = 4,
            ready = stage == TorrServePreparationStage.READY_TO_PLAY,
        )
    }

    private fun updateTorrServePlaybackLifecycle(state: com.example.data.player.PlayerUiState) {
        val handoff = torrServePlaybackHandoff ?: return
        if (preparationGeneration.get() != handoff.generation) {
            torrServePlaybackHandoff = null
            return
        }

        val book = state.book ?: return
        if (
            book.id != handoff.bookId ||
            !book.selectedSource.equals("rutracker", ignoreCase = true)
        ) {
            return
        }

        if (!state.error.isNullOrBlank() && !state.isPlaying) {
            torrServePlaybackHandoff = null
            _preparationState.value = PlaybackPreparationUiState()
            return
        }

        if (state.isLoading) {
            updateTorrServePreparationStage(
                handoff.generation,
                TorrServePreparationStage.BUFFERING,
            )
        }
    }

    private fun markTorrServePlaybackStarted(
        bookId: String,
        sourceCode: String?,
    ) {
        val handoff = torrServePlaybackHandoff ?: return
        if (preparationGeneration.get() != handoff.generation) {
            torrServePlaybackHandoff = null
            return
        }
        if (
            !shouldMarkTorrServePreparationReady(
                pendingBookId = handoff.bookId,
                startedBookId = bookId,
                startedSourceCode = sourceCode,
            )
        ) {
            return
        }

        torrServePlaybackHandoff = null
        updateTorrServePreparationStage(
            handoff.generation,
            TorrServePreparationStage.READY_TO_PLAY,
        )
        viewModelScope.launch {
            delay(TORRSERVER_READY_VISIBLE_MS)
            if (
                preparationGeneration.get() == handoff.generation &&
                torrServePlaybackHandoff == null &&
                _preparationState.value.ready
            ) {
                _preparationState.value = PlaybackPreparationUiState()
            }
        }
    }

    private fun torrServePreparationLabel(stage: TorrServePreparationStage): String = when (stage) {
        TorrServePreparationStage.CONNECTING -> "Подключение к TorrServer"
        TorrServePreparationStage.WAITING_FOR_METADATA -> "Получение метаданных"
        TorrServePreparationStage.BUFFERING -> "Буферизация аудиопотока"
        TorrServePreparationStage.READY_TO_PLAY -> "Готово к воспроизведению"
    }

    private fun torrServePreparationStep(stage: TorrServePreparationStage): Int = when (stage) {
        TorrServePreparationStage.CONNECTING -> 1
        TorrServePreparationStage.WAITING_FOR_METADATA -> 2
        TorrServePreparationStage.BUFFERING -> 3
        TorrServePreparationStage.READY_TO_PLAY -> 4
    }

    private fun playPrepared(
        prepared: PreparedPlayback,
        startBackgroundRefresh: Boolean = true,
        recoveryRequest: Long? = null,
    ) {
        // Keep the current MediaItem at its own speed until the manager swaps playlists.
        // Otherwise PlaybackService can persist the next book's speed into the old book
        // when loadBook() stops the current player.
        player.setDefaultSpeed(prepared.speed)
        player.play(
            prepared.book,
            prepared.chapterIndex,
            prepared.positionMs,
            playbackWriteEpoch = prepared.playbackWriteEpoch,
            recoveryRequest = recoveryRequest,
        )
        if (startBackgroundRefresh) {
            startProactivePlaybackRefresh(prepared.book, prepared.chapterIndex)
        }
    }

    override fun onCleared() {
        proactivePlaybackRefresh?.deferred?.cancel()
        proactivePlaybackRefresh = null
        player.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlaybackViewModel"
        const val PROACTIVE_REFRESH_REUSE_WINDOW_MS = 60_000L
        const val TORRSERVER_READY_VISIBLE_MS = 1_500L
        const val PREPARATION_ERROR = "Не удалось подготовить воспроизведение"
        const val PLAYBACK_ACTIVITY_ERROR = "Не удалось сохранить активность прослушивания"
    }
}
