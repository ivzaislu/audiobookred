package com.example.ui

import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.narration.TextAudioReadingPosition
import com.example.data.narration.TextPlaybackHighlight
import com.example.ui.viewmodel.TextLibraryViewModel
import kotlinx.coroutines.launch

/** Reading owns a navigation entry and never changes the selected Library tab. */
@Composable
internal fun PreparedTextReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onPlayText: (String, String?) -> Unit,
    onListenFromText: (String, String, Double) -> Unit,
    textHighlight: TextPlaybackHighlight?,
    audioBookId: String?,
    audioPlaying: Boolean,
    audioBuffering: Boolean,
    audioError: String?,
    onToggleAudio: () -> Unit,
    audioSpeed: Float,
    onSeekBy: (Long) -> Unit,
    onSetAudioSpeed: (Float) -> Unit,
    rewindSeconds: Int,
    forwardSeconds: Int,
    requestedAudioReadingPosition: TextAudioReadingPosition?,
    onAudioReadingPositionConsumed: () -> Unit,
) {
    val textViewModel: TextLibraryViewModel = hiltViewModel()
    val textState by textViewModel.state.collectAsStateWithLifecycle()
    val readerSession = textState.readerSession
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(bookId, requestedAudioReadingPosition) {
        val jump = requestedAudioReadingPosition?.takeIf { it.bookId == bookId }
        if (jump != null) textViewModel.open(bookId, jump.chapterId, preview = jump)
        else textViewModel.ensureOpen(bookId)
        onAudioReadingPositionConsumed()
    }
    TextBookReaderScreen(
        state = textState,
        onBack = {
            textViewModel.closeReader()
            onBack()
        },
        onBackAtPosition = { fragment, offset ->
            if (!closing) {
                closing = true
                scope.launch {
                    if (textViewModel.closeReaderAndSave(readerSession, fragment, offset)) onBack()
                    else closing = false
                }
            }
        },
        textHighlight = textHighlight,
        onFollowChanged = { enabled -> textViewModel.setFollowing(readerSession, enabled) },
        onResumeFollowing = { textViewModel.resumeFollowing(readerSession) },
        readingJump = textState.readingJump,
        onReadingJumpApplied = { textViewModel.readingJumpApplied(readerSession) },
        onPosition = { book, chapter, fragment, offset ->
            textViewModel.savePosition(readerSession, book, chapter, fragment, offset)
        },
        onCommitPreview = { fragment, offset -> textViewModel.commitPreview(readerSession, fragment, offset) },
        onReturnToBookmark = { textViewModel.returnToBookmark(readerSession) },
        onListen = { book, fragment ->
            textViewModel.allowFollowing(readerSession)
            onPlayText(book, fragment)
        },
        onListenFromText = { book, fragment, fraction ->
            textViewModel.allowFollowing(readerSession)
            onListenFromText(book, fragment, fraction)
        },
        audioBookId = audioBookId, audioPlaying = audioPlaying, audioBuffering = audioBuffering,
        audioError = audioError,
        onToggleAudio = onToggleAudio,
        audioSpeed = audioSpeed,
        onSeekBy = onSeekBy,
        onSetAudioSpeed = onSetAudioSpeed,
        rewindSeconds = rewindSeconds,
        forwardSeconds = forwardSeconds,
    )
}
