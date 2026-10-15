package com.example.data.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.repository.PlaybackLibraryRepository
import com.example.domain.playback.PreparedPlayback
import com.example.domain.playback.PreparePlaybackUseCase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the Android Auto/AAOS MediaLibrary callback and playback-resumption flow.
 *
 * PlaybackService still owns ExoPlayer and MediaLibrarySession lifecycles. This
 * controller owns only the car-facing browse/search protocol and pending
 * resumption bookkeeping.
 */
@OptIn(UnstableApi::class)
internal class AndroidAutoSessionController(
    private val player: Player,
    private val library: AndroidAutoMediaLibrary,
    private val resumeStore: PlaybackResumeStore,
    private val localCacheStore: LocalCacheStore,
    private val downloadStore: DownloadStore,
    private val playbackLibraryRepository: PlaybackLibraryRepository,
    private val preparePlayback: PreparePlaybackUseCase,
    private val scope: CoroutineScope,
) {
    private var session: MediaLibrarySession? = null
    private var pendingResumptionBook: BookDetailDto? = null
    private var pendingResumptionEpoch: Long? = null
    private var lastContinueKey: String? = null

    private val selectionPreparation = GuardedSessionPreparation(scope)
    val sessionPlayer = GuardedPlaybackResumptionPlayer(
        player, scope, selectionPreparation::invalidate,
        { selectionPreparation.shouldIgnoreQueuedPlayback(session?.controllerForCurrentRequest) },
    ) {
        preparePlaybackResumption(isForPlayback = true)
    }

    val callback: MediaLibrarySession.Callback = PlaybackSessionCallback()

    fun attachSession(value: MediaLibrarySession) {
        session = value
    }

    fun detachSession() {
        sessionPlayer.close()
        session = null
    }

    fun notifyLibraryChanged() {
        val currentSession = session ?: return
        listOf(
            ANDROID_AUTO_ROOT_ID,
            ANDROID_AUTO_CONTINUE_ID,
            ANDROID_AUTO_DOWNLOADS_ID,
            ANDROID_AUTO_FAVORITES_ID,
            ANDROID_AUTO_HISTORY_ID,
        ).forEach { parentId ->
            currentSession.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
        }
    }

    fun notifyContinueIfChanged(
        mediaItem: MediaItem?,
        force: Boolean = false,
    ) {
        val extras = mediaItem?.mediaMetadata?.extras
        val bookId = extras?.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        val sourceCode = extras?.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        val key = bookId.takeIf(String::isNotBlank)?.let {
            "$it|${sourceCode.trim().lowercase()}"
        }
        if (!force && key == lastContinueKey) return
        lastContinueKey = key

        val currentSession = session ?: return
        currentSession.notifyChildrenChanged(ANDROID_AUTO_ROOT_ID, Int.MAX_VALUE, null)
        currentSession.notifyChildrenChanged(ANDROID_AUTO_CONTINUE_ID, Int.MAX_VALUE, null)
    }

    fun recordPendingResumptionPlaybackStarted() {
        val book = pendingResumptionBook ?: return
        val playbackWriteEpoch = pendingResumptionEpoch ?: return

        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        if (extras.getString(PlaybackMetadata.EXTRA_BOOK_ID) != book.id) return
        val chapterIndex = player.currentMediaItemIndex
        if (chapterIndex !in book.chapters.indices) return
        val chapterId = extras.getString(PlaybackMetadata.EXTRA_CHAPTER_ID)
        if (!chapterId.isNullOrBlank() && book.chapters[chapterIndex].id != chapterId) return
        if (!UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(playbackWriteEpoch)) {
            pendingResumptionBook = null
            pendingResumptionEpoch = null
            return
        }

        pendingResumptionBook = null
        pendingResumptionEpoch = null
        scope.launch(Dispatchers.IO) {
            try {
                playbackLibraryRepository.recordPlaybackStarted(book, chapterIndex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to record resumed playback start", error)
            }
        }
    }

    private fun <T> backgroundFuture(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch(Dispatchers.IO) {
            try {
                result.set(block())
            } catch (error: CancellationException) {
                result.cancel(false)
                throw error
            } catch (error: Exception) {
                result.setException(error)
            }
        }
        return result
    }

    private inner class PlaybackSessionCallback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            // Keep Media3's trusted/untrusted defaults and library commands;
            // only remove modes that can replay a finished book or reorder it.
            return Futures.immediateFuture(audiobookConnectionResult(session, controller))
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands,
        ) {
            sessionPlayer.onPlayerInteractionFinished(playerCommands)
            selectionPreparation.onInteractionFinished(controllerInfo)
            val commands = (0 until playerCommands.size()).joinToString(",") { playerCommands[it].toString() }
            tracePlaybackEvent(
                "controller_commands", player,
                "controller=${controllerInfo.packageName} commands=$commands",
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(library.rootItem(), params)
        )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = backgroundFuture {
            library.item(mediaId)?.let { item ->
                LibraryResult.ofItem(item, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = backgroundFuture {
            val parent = library.item(parentId)
            if (parent?.mediaMetadata?.isBrowsable != true) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                // Android Auto/AAOS do not reliably paginate browse trees. The
                // library itself keeps every folder driver-safe and bounded.
                LibraryResult.ofItemList(library.children(parentId), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = backgroundFuture {
            val items = library.search(query)
            withContext(Dispatchers.Main.immediate) {
                session.notifySearchResultChanged(browser, query, items.size, params)
            }
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = backgroundFuture {
            LibraryResult.ofItemList(library.search(query), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            sessionPlayer.cancelPendingResumption()
            if (mediaItems.none(::isAndroidAutoPlaybackRequest)) {
                return Futures.immediateFuture(mediaItems)
            }
            val epoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
            var selected: PreparedPlayback? = null
            return selectionPreparation.future(
                owner = controller,
                isAuthorized = { UserDataRestoreGate.playbackWriteEpochCurrent(epoch) },
                prepare = { buildList {
                    mediaItems.forEach { request ->
                        if (!isAndroidAutoPlaybackRequest(request)) {
                            add(request)
                        } else {
                            val prepared = resolvePreparedPlayback(request)
                            addAll(buildPlaybackMediaItems(prepared.book, downloadStore))
                            selected = prepared
                        }
                    }
                } },
                onAccepted = {
                    selected?.let { prepared ->
                        pendingResumptionBook = prepared.book
                        pendingResumptionEpoch = epoch
                    }
                },
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            sessionPlayer.cancelPendingResumption()
            val request = mediaItems.singleOrNull()
            if (request == null || !isAndroidAutoPlaybackRequest(request)) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex,
                        startPositionMs,
                    )
                )
            }

            val epoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
            var selected: PreparedPlayback? = null
            return selectionPreparation.future(
                owner = controller,
                isAuthorized = { UserDataRestoreGate.playbackWriteEpochCurrent(epoch) },
                prepare = {
                    val prepared = resolvePreparedPlayback(request)
                    playbackItems(prepared).also { selected = prepared }
                },
                onAccepted = {
                    val prepared = requireNotNull(selected)
                    pendingResumptionBook = prepared.book
                    pendingResumptionEpoch = epoch
                    player.playbackParameters = PlaybackParameters(sanitizePlaybackSpeed(prepared.speed))
                },
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            tracePlaybackEvent(
                "session_resumption", player,
                "controller=${controller.packageName} forPlayback=$isForPlayback",
            )
            // Media3 calls play even on resumption-future failure. Resolve real
            // playback in sessionPlayer.play(), outside that SDK fallback path.
            if (isForPlayback) return Futures.immediateFuture(emptyPlaybackResumptionItems())
            return backgroundFuture { preparePlaybackResumption(false).mediaItems }
        }
    }

    private fun isAndroidAutoPlaybackRequest(item: MediaItem): Boolean =
        parseAndroidAutoPlaybackTarget(item.mediaId) != null ||
            !item.requestMetadata.searchQuery.isNullOrBlank()

    private suspend fun resolvePreparedPlayback(request: MediaItem): PreparedPlayback {
        val playbackWriteEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        val directTarget = parseAndroidAutoPlaybackTarget(request.mediaId)
        val target = directTarget ?: request.requestMetadata.searchQuery
            ?.takeIf { it.isNotBlank() }
            ?.let { query -> library.firstSearchResult(query) }
            ?.let { result -> parseAndroidAutoPlaybackTarget(result.mediaId) }
            ?: error("Android Auto не нашёл книгу для воспроизведения")

        val prepared = when (target) {
            is AndroidAutoPlaybackTarget.Book -> preparePlayback.resume(target.bookId)
                ?: error("Не удалось подготовить книгу для Android Auto")
            is AndroidAutoPlaybackTarget.Resume ->
                preparePlayback.resume(target.bookId, target.sourceCode)
                    ?: error("Сохранённая аудиодорожка книги сейчас недоступна")
            is AndroidAutoPlaybackTarget.Download ->
                preparePlayback.downloaded(target.bookSourceId)
        }
        if (!UserDataRestoreGate.playbackWriteEpochCurrent(playbackWriteEpoch)) {
            error("Состояние воспроизведения изменилось во время подготовки")
        }

        return prepared.copy(playbackWriteEpoch = playbackWriteEpoch)
    }

    private suspend fun playbackItems(
        prepared: PreparedPlayback,
    ): MediaSession.MediaItemsWithStartPosition {
        val items = buildPlaybackMediaItems(prepared.book, downloadStore)
        if (items.isEmpty()) error("Не удалось подготовить плейлист для Android Auto")
        val chapterIndex = prepared.chapterIndex.coerceIn(0, items.lastIndex)
        val positionMs = prepared.positionMs.coerceAtLeast(0L)

        return MediaSession.MediaItemsWithStartPosition(
            items,
            chapterIndex,
            positionMs,
        )
    }

    private suspend fun preparePlaybackResumption(
        isForPlayback: Boolean,
    ): SessionPlaybackResumption {
        val playbackWriteEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()

        val snapshot = resumeStore.latestSnapshot()
            ?: error("Нет сохранённой позиции для продолжения")
        val sourceCode = snapshot.sourceCode
            .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: standalonePlaybackSourceFromBookId(snapshot.bookId)
        val cachedProgress = localCacheStore.readProgress(snapshot.bookId, sourceCode)
        if (cachedProgress?.completed == true || snapshot.progressPercent >= 100.0) {
            error("Последняя книга уже завершена")
        }

        val prepared = preparePlayback.resumption(snapshot)
            ?: error("Не удалось подготовить последнюю книгу для продолжения")
        if (!UserDataRestoreGate.playbackWriteEpochCurrent(playbackWriteEpoch)) {
            error("Состояние воспроизведения изменилось во время восстановления")
        }

        val items = buildPlaybackMediaItems(prepared.book, downloadStore)
        if (items.isEmpty()) error("Не удалось восстановить плейлист книги")
        val chapterIndex = prepared.chapterIndex.coerceIn(0, items.lastIndex)
        val positionMs = prepared.positionMs.coerceAtLeast(0L)
        if (localCacheStore.readProgress(snapshot.bookId, sourceCode)?.completed == true) {
            error("Последняя книга уже завершена")
        }
        fun authorized(): Boolean =
            UserDataRestoreGate.playbackWriteEpochCurrent(playbackWriteEpoch) &&
                resumeStore.latestSnapshot() == snapshot

        if (isForPlayback) {
            return SessionPlaybackResumption(
                mediaItems = MediaSession.MediaItemsWithStartPosition(items, chapterIndex, positionMs),
                isAuthorized = ::authorized,
                onApplied = {
                    pendingResumptionBook = prepared.book
                    pendingResumptionEpoch = playbackWriteEpoch
                    player.playbackParameters = PlaybackParameters(sanitizePlaybackSpeed(prepared.speed))
                },
            )
        }

        if (!authorized()) error("Сохранённая позиция изменилась во время восстановления")
        return SessionPlaybackResumption(
            MediaSession.MediaItemsWithStartPosition(listOf(items[chapterIndex]), 0, positionMs),
        )
    }

    private companion object {
        const val TAG = "AndroidAutoSession"
    }
}
