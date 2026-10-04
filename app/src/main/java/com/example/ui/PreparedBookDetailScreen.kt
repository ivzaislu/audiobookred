package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.player.PlayerUiState
import com.example.ui.viewmodel.BookDetailViewModel

/** Nav3-scoped Book Detail host. Display state belongs only to BookDetailViewModel. */
@Composable
internal fun PreparedBookDetailScreen(
    routeId: String,
    downloaded: Boolean,
    playerState: PlayerUiState,
    showProgressPercent: Boolean,
    onResumeBook: (BookDetailDto) -> Unit,
    onTogglePlayback: () -> Unit,
    onPlayBookmark: (BookDetailDto, BookmarkDto) -> Unit,
    onBack: () -> Unit,
    onPlayer: () -> Unit,
    onSourceSeries: (String, String?) -> Unit,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
    onGenre: (GenreDto) -> Unit,
    onSimilarBook: (String) -> Unit,
    onSearchOtherSources: (String, String) -> Unit,
) {
    val viewModel: BookDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadUi by viewModel.downloadUi.collectAsStateWithLifecycle()
    val browserChallengeSession = rememberInlineBrowserChallengeSession()
    val browserChallengeUiState = browserChallengeSession
        ?.uiState
        ?.collectAsStateWithLifecycle()
        ?.value
    val browserChallengeRequestId = browserChallengeUiState
        ?.takeIf { it.required }
        ?.requestId
    val context = LocalContext.current

    var pendingDownloadAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDownloadBookSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }

    val executeDownload: (String, String) -> Unit = { action, bookSourceId ->
        when (action) {
            "start" -> viewModel.startDownload(bookSourceId)
            "resume" -> viewModel.resumeDownload(bookSourceId)
            "retry" -> viewModel.retryDownload(bookSourceId)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        val action = pendingDownloadAction
        val bookSourceId = pendingDownloadBookSourceId
        pendingDownloadAction = null
        pendingDownloadBookSourceId = null
        if (action != null && bookSourceId != null) executeDownload(action, bookSourceId)
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownloadAction
        val bookSourceId = pendingDownloadBookSourceId
        pendingDownloadAction = null
        pendingDownloadBookSourceId = null
        if (granted && action != null && bookSourceId != null) {
            executeDownload(action, bookSourceId)
        } else if (!granted) {
            Toast.makeText(
                context,
                "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val requestDownload: (String, String) -> Unit = { action, bookSourceId ->
        val legacyStoragePermissionNeeded =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        val notificationPermissionNeeded =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        when {
            legacyStoragePermissionNeeded -> {
                pendingDownloadAction = action
                pendingDownloadBookSourceId = bookSourceId
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            notificationPermissionNeeded && !notificationPermissionRequested -> {
                notificationPermissionRequested = true
                pendingDownloadAction = action
                pendingDownloadBookSourceId = bookSourceId
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> executeDownload(action, bookSourceId)
        }
    }

    LaunchedEffect(routeId, downloaded) {
        viewModel.load(routeId, downloaded)
    }

    val book = state.book
    if (book == null) {
        if (browserChallengeSession != null && browserChallengeRequestId != null) {
            BrowserChallengeGate(
                session = browserChallengeSession,
                hostedRequestId = browserChallengeRequestId,
                inline = true,
            )
            return
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.error == null && state.loading) {
                CircularProgressIndicator()
            } else {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        state.error ?: "Не удалось открыть книгу",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    val alternateQuery = state.alternateSearchQuery
                    val excludedSource = state.alternateSearchExcludedSource
                    if (!alternateQuery.isNullOrBlank() && !excludedSource.isNullOrBlank()) {
                        Button(
                            onClick = { onSearchOtherSources(alternateQuery, excludedSource) },
                        ) {
                            Text("Поиск в других источниках")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = viewModel::retry) { Text("Повторить") }
                    } else {
                        Button(onClick = viewModel::retry) { Text("Повторить") }
                    }
                }
            }
        }
        return
    }

    val selectedProvider = book.selectedSource.takeIf { it.isNotBlank() }
    val canNavigateSeries = canOpenBookSeries(book)
    val alternateQuery = state.alternateSearchQuery
    val excludedSource = state.alternateSearchExcludedSource
    val loadError = state.error?.takeIf { it.isNotBlank() }
    val showAlternateSearch = !alternateQuery.isNullOrBlank() && !excludedSource.isNullOrBlank()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            PagingBookDetailHost(
                book = book,
                browserChallengeSession = browserChallengeSession,
                browserChallengeRequestId = browserChallengeRequestId,
                audioSeries = state.audioSeries,
                bookmarks = state.bookmarks,
                selectedDownload = state.selectedDownload,
                downloadUi = downloadUi,
                playerState = playerState,
                showProgressPercent = showProgressPercent,
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onResumeBook = { onResumeBook(book) },
                onTogglePlayback = onTogglePlayback,
                onPlayBookmark = { bookmark -> onPlayBookmark(book, bookmark) },
                onToggleFavorite = viewModel::toggleFavorite,
                onSelectSource = viewModel::selectSource,
                onRemoveBookmark = viewModel::removeBookmark,
                onStartDownload = { bookSourceId -> requestDownload("start", bookSourceId) },
                onResumeDownload = { bookSourceId -> requestDownload("resume", bookSourceId) },
                onRetryDownload = { bookSourceId -> requestDownload("retry", bookSourceId) },
                onBack = onBack,
                onPlayer = onPlayer,
                // Series metadata can be rendered even when the source-native listing
                // contract is intentionally disabled. Do not create a destination until
                // that capability is verified in StandaloneSourceRegistry.
                onSeries = {
                    if (canNavigateSeries) onSourceSeries(book.id, selectedProvider)
                },
                onSourceSeries = { id ->
                    if (canNavigateSeries) {
                        onSourceSeries(id, selectedProvider.takeIf { id == book.id })
                    }
                },
                onAuthor = onAuthor,
                onNarrator = onNarrator,
                onGenre = onGenre,
                onSimilarBook = onSimilarBook,
            )

            if (state.loading && browserChallengeRequestId == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }

        when {
            showAlternateSearch -> {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            loadError ?: "Доступен только ознакомительный фрагмент",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onSearchOtherSources(alternateQuery!!, excludedSource!!) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                contentColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text("Поиск в других источниках")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = viewModel::retry,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                            ),
                        ) {
                            Text("Повторить")
                        }
                    }
                }
            }

            loadError != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            loadError,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = viewModel::retry) {
                            Text("Повторить")
                        }
                    }
                }
            }
        }
    }
}
