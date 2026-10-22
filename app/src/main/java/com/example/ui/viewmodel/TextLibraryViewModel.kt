package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.textbook.TextReaderSession
import com.example.data.textbook.TextReadingPositionEntity
import com.example.data.narration.TextAudioReadingPosition
import com.example.data.textbook.TextBookEntity
import com.example.data.narration.TextListeningProgressRepository
import com.example.data.textbook.TextLibraryRepository
import com.example.data.textbook.TextReadingSnapshot
import com.example.data.textbook.TextBookMetadata
import com.example.data.textbook.EbookSelectionRequired
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.narration.TextAudioExportRepository
import com.example.data.narration.TEXT_AUDIO_SOURCE
import com.example.data.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal data class TextLibraryUiState(
    val books: List<TextBookEntity> = emptyList(),
    val membership: TextLibraryMembership = TextLibraryMembership(),
    val membershipError: String? = null,
    val importing: Boolean = false,
    val opening: Boolean = false,
    val reader: TextReadingSnapshot? = null,
    val readerSession: Long = 0L,
    val readerIdentity: String = "",
    val preview: Boolean = false,
    val following: Boolean = false,
    val autoFollowAllowed: Boolean = true,
    val committing: Boolean = false,
    val readingJump: TextAudioReadingPosition? = null,
    val message: String? = null,
    val audioExports: List<DownloadBookEntity> = emptyList(),
    val startingAudioExport: Boolean = false,
    val metadata: Map<String, TextBookMetadata> = emptyMap(),
    val archiveEntries: List<String> = emptyList(),
)

@HiltViewModel
internal class TextLibraryViewModel @Inject constructor(
    private val repository: TextLibraryRepository,
    private val listeningProgress: TextListeningProgressRepository,
    private val audioExports: TextAudioExportRepository,
    private val downloadStore: DownloadStore,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TextLibraryUiState())
    val state = mutableState.asStateFlow()
    private var opening: Job? = null
    private var readerRequest = 0L
    private val session = TextReaderSession()
    private var pendingImport: Uri? = null

    init {
        viewModelScope.launch {
            try {
                downloadStore.observeBooks().collect { rows ->
                    mutableState.update { it.copy(audioExports = rows.filter { row -> row.sourceCode == TEXT_AUDIO_SOURCE }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "Не удалось прочитать состояние аудиокниг.") }
        }
        viewModelScope.launch {
            try {
                combine(repository.observeBooks(), repository.observeActivity(), listeningProgress.observe()) { books, _, progress ->
                    repository.restoreReadingActivity(books)
                    val metadata = books.associate { it.id to repository.metadata(it.id) }
                    val membership = withContext(Dispatchers.Default) { textLibraryMembership(books, metadata, repository.observeActivity().value, progress) }
                    Triple(books, metadata, membership)
                }.collect { (books, metadata, membership) ->
                    mutableState.update { it.copy(books = books, metadata = metadata, membership = membership) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "Не удалось открыть текстовую библиотеку.") }
        }
    }

    fun importTxt(uri: Uri) = importBook(uri)

    fun importBook(uri: Uri, entry: String? = null) {
        if (state.value.importing) return
        pendingImport = null
        mutableState.update { it.copy(importing = true, message = null, archiveEntries = emptyList()) }
        viewModelScope.launch {
            try {
                val result = repository.importBook(uri, entry)
                message(if (result.duplicate) "Эта книга уже есть в библиотеке. Позиция чтения сохранена." else "Книга импортирована. Можно читать, слушать и сохранять аудио.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (choice: EbookSelectionRequired) {
                pendingImport = uri
                mutableState.update { it.copy(archiveEntries = choice.entries) }
            }
            catch (error: Exception) { message(error.message ?: "Не удалось импортировать книгу.") }
            finally { mutableState.update { it.copy(importing = false) } }
        }
    }

    fun chooseArchiveEntry(entry: String) { pendingImport?.let { if (entry in state.value.archiveEntries) importBook(it, entry) } }
    fun cancelArchiveImport() { pendingImport = null; mutableState.update { it.copy(archiveEntries = emptyList()) } }

    fun ensureOpen(bookId: String) {
        if (!state.value.opening && state.value.reader?.book?.id != bookId) open(bookId)
    }

    fun open(bookId: String, chapterId: String? = null, preview: TextAudioReadingPosition? = null, allowFollowing: Boolean = true) {
        val generation = session.open(preview != null, allowFollowing)
        val token = ++readerRequest
        opening?.cancel()
        mutableState.update { it.copy(opening = true, reader = null, message = null, readerSession = generation, readerIdentity = session.identity,
            preview = preview != null, following = false, autoFollowAllowed = allowFollowing, committing = false, readingJump = preview) }
        opening = viewModelScope.launch {
            try {
                val reader = repository.open(bookId, chapterId)
                if (token == readerRequest) mutableState.update { it.copy(reader = reader, opening = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == readerRequest) mutableState.update {
                    it.copy(opening = false, message = error.message ?: "Не удалось открыть книгу.")
                }
            }
        }
    }

    fun closeReader(token: Long = state.value.readerSession, fragmentId: String? = null, scrollOffsetPx: Int = 0) {
        if (token != state.value.readerSession) return
        // Capture one final NORMAL cursor before revoking all disposable/debounced callbacks.
        // Launching this ordered repository write before returning also fences the next open().
        val bookId = state.value.reader?.book?.id?.takeIf { fragmentId != null && session.canSave(token) }
        if (bookId != null && fragmentId != null) viewModelScope.launch {
            try { repository.savePosition(bookId, fragmentId, scrollOffsetPx) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Не удалось сохранить позицию чтения.") }
        }
        session.close()
        readerRequest++
        opening?.cancel()
        mutableState.update { it.copy(reader = null, opening = false, preview = false, following = false, committing = false, readingJump = null) }
    }

    fun savePosition(token: Long, bookId: String, chapterId: String, fragmentId: String, scrollOffsetPx: Int) {
        if (!session.canSave(token)) return
        val reader = state.value.reader ?: return
        if (reader.book.id != bookId || reader.chapter.id != chapterId) return
        viewModelScope.launch {
            try { repository.savePosition(bookId, fragmentId, scrollOffsetPx) { session.canSave(token) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Не удалось сохранить позицию чтения. Проверьте свободное место.") }
        }
    }

    fun setFollowing(token: Long, enabled: Boolean) {
        val current = state.value
        if (current.reader == null || token != current.readerSession || current.committing) return
        if (enabled && !session.canFollow(token)) return
        if (enabled && !current.preview) {
            // Following is activated automatically from a global preference. Do not write
            // the visible cursor: only explicit reading may move the saved bookmark.
            if (!session.canSave(token)) return
            val next = session.open(true)
            mutableState.update { it.copy(readerSession = next, preview = true, following = true, readingJump = null) }
        } else mutableState.update { it.copy(following = enabled) }
    }

    /** Explicit listening resumes the global follow preference after a manual reading action. */
    fun allowFollowing(token: Long) {
        session.allowFollowing(token)
        if (state.value.readerSession == token) mutableState.update { it.copy(autoFollowAllowed = session.canFollow(token)) }
    }

    fun readingJumpApplied(token: Long) {
        if (state.value.readerSession == token) mutableState.update { it.copy(readingJump = null) }
    }

    fun returnToBookmark(token: Long) {
        if (state.value.readerSession != token || !state.value.preview || state.value.committing) return
        state.value.reader?.book?.id?.let { open(it, allowFollowing = false) }
    }

    fun commitPreview(token: Long, fragmentId: String, scrollOffsetPx: Int) {
        val reader = state.value.reader ?: return
        if (!session.beginCommit(token)) return
        mutableState.update { it.copy(committing = true) }
        viewModelScope.launch {
            try {
                check(repository.savePosition(reader.book.id, fragmentId, scrollOffsetPx) { session.canCommit(token) })
                if (!session.canCommit(token)) return@launch
                val generation = session.finishCommit(token, true)
                val position = TextReadingPositionEntity(reader.book.id, fragmentId, scrollOffsetPx.coerceAtLeast(0), System.currentTimeMillis())
                mutableState.update { it.copy(readerSession = generation, preview = false, following = false, autoFollowAllowed = false, committing = false,
                    readingJump = null, reader = reader.copy(position = position)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session.canCommit(token)) {
                    session.finishCommit(token, false)
                    mutableState.update { it.copy(committing = false, message = "Не удалось сохранить закладку. Временный просмотр продолжается.") }
                }
            }
        }
    }

    fun remove(bookId: String) {
        viewModelScope.launch {
            try {
                // Stop export leases before removing their source; saved audio remains available.
                downloadStore.books().filter { it.bookSourceId.startsWith("$TEXT_AUDIO_SOURCE:$bookId:") && it.state in setOf("queued", "downloading") }
                    .forEach { downloads.pause(it.bookSourceId) }
                repository.remove(bookId)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "Не удалось удалить текстовую книгу.") }
        }
    }

    fun restoreLegacyHistory(timestamps: Map<String, Long>) = membershipCommand {
        timestamps.forEach { (id, timestamp) -> repository.restoreAudioActivity(id, timestamp) }
    }
    fun removeHistory(bookId: String) = membershipCommand { repository.hideHistory(bookId) }
    fun removeSeries(seriesKey: String) = membershipCommand { repository.hideSeries(seriesKey) }
    private fun membershipCommand(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(membershipError = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(membershipError = error.message ?: "Не удалось обновить историю книг.") }
            }
        }
    }

    fun saveAudio(bookId: String, chapterId: String?) {
        if (state.value.startingAudioExport) return
        mutableState.update { it.copy(startingAudioExport = true, message = null) }
        viewModelScope.launch {
            try { audioExports.start(bookId, chapterId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "Не удалось начать сохранение аудио.") }
            finally { mutableState.update { it.copy(startingAudioExport = false) } }
        }
    }

    fun pauseAudio(sourceId: String) = audioCommand { downloads.pause(sourceId) }
    fun resumeAudio(sourceId: String) = audioCommand { downloads.resume(sourceId) }
    fun removeAudio(sourceId: String) = audioCommand { downloads.remove(sourceId) }
    private fun audioCommand(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "Не удалось изменить подготовку аудио.") }
        }
    }

    fun message(message: String) { mutableState.update { it.copy(message = message) } }
}
