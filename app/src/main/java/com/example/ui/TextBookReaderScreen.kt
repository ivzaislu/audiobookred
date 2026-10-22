package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ContentCopy
import com.example.data.narration.TextPlaybackHighlight
import com.example.data.textbook.textHighlightRange
import com.example.data.textbook.textPositionAtOffset
import com.example.data.textbook.textSelectionPosition
import androidx.compose.material.icons.filled.BookmarkBorder
import com.example.ui.theme.readerHighlightColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.example.data.textbook.readingContents
import com.example.data.narration.TextAudioReadingPosition
import com.example.data.textbook.textReadingPercent
import kotlinx.coroutines.launch
import com.example.data.settings.ReaderSettings
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.textbook.TextReadingSnapshot
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.TextReaderDesign
import com.example.ui.theme.readerTextStyle
import com.example.ui.theme.readerColors
import androidx.compose.foundation.background
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.ReaderSettingsViewModel
import com.example.ui.viewmodel.TextLibraryUiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun TextBookReaderScreen(
    state: TextLibraryUiState, onBack: () -> Unit,
    onPosition: (String, String, String, Int) -> Unit,
    onListen: (String, String?) -> Unit,
    audioBookId: String?, audioPlaying: Boolean, audioBuffering: Boolean, audioError: String?, onToggleAudio: () -> Unit,
    audioSpeed: Float, onSetAudioSpeed: (Float) -> Unit,
    onRuVoiceVoiceSelected: (String, String) -> Unit,
    onReaderFocusChanged: (Boolean) -> Unit,
    onSaveAudio: (String, String?) -> Unit, onPauseAudio: (String) -> Unit,
    onResumeAudio: (String) -> Unit, onRemoveAudio: (String) -> Unit, onPlaySavedAudio: (String) -> Unit,
    readingJump: TextAudioReadingPosition? = null,
    onReadingJumpApplied: () -> Unit = {},
    onCommitPreview: (String, Int) -> Unit = { _, _ -> },
    onReturnToBookmark: () -> Unit = {},
    onListenFromText: (String, String, Double) -> Unit = { book, fragment, _ -> onListen(book, fragment) },
    onListenLive: (String) -> Unit = { onListen(it, null) },
    settings: ReaderSettings? = null,
    onBackAtPosition: ((String, Int) -> Unit)? = null,
    textHighlight: TextPlaybackHighlight? = null,
    onFollowChanged: (Boolean) -> Unit = {},
) {
    val reader = state.reader
    if (reader == null) {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().padding(AbredSpacing.ScreenHorizontal)) {
            TextButton(onClick = onBack) { Text("Назад в библиотеку") }
            if (state.opening) CircularProgressIndicator()
            state.message?.let { Text(it) }
        }
        return
    }
    key(reader.book.id, state.readerIdentity, state.readerSession) {
        TextChapterReader(reader, state, onBack, onPosition, onListen,
            audioBookId, audioPlaying, audioBuffering, audioError, onToggleAudio, audioSpeed, onSetAudioSpeed,
            onRuVoiceVoiceSelected, onReaderFocusChanged,
            onSaveAudio, onPauseAudio, onResumeAudio, onRemoveAudio, onPlaySavedAudio,
            readingJump, onReadingJumpApplied, onCommitPreview, onReturnToBookmark, onListenFromText, onListenLive, settings, onBackAtPosition, textHighlight, onFollowChanged)
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun TextChapterReader(
    reader: TextReadingSnapshot, state: TextLibraryUiState, onBack: () -> Unit,
    onPosition: (String, String, String, Int) -> Unit,
    onListen: (String, String?) -> Unit,
    audioBookId: String?, audioPlaying: Boolean, audioBuffering: Boolean, audioError: String?, onToggleAudio: () -> Unit,
    audioSpeed: Float, onSetAudioSpeed: (Float) -> Unit,
    onRuVoiceVoiceSelected: (String, String) -> Unit,
    onReaderFocusChanged: (Boolean) -> Unit,
    onSaveAudio: (String, String?) -> Unit, onPauseAudio: (String) -> Unit,
    onResumeAudio: (String) -> Unit, onRemoveAudio: (String) -> Unit, onPlaySavedAudio: (String) -> Unit,
    readingJump: TextAudioReadingPosition? = null,
    onReadingJumpApplied: () -> Unit = {},
    onCommitPreview: (String, Int) -> Unit = { _, _ -> },
    onReturnToBookmark: () -> Unit = {},
    onListenFromText: (String, String, Double) -> Unit = { book, fragment, _ -> onListen(book, fragment) },
    onListenLive: (String) -> Unit = { onListen(it, null) },
    settings: ReaderSettings? = null,
    onBackAtPosition: ((String, Int) -> Unit)? = null,
    textHighlight: TextPlaybackHighlight? = null,
    onFollowChanged: (Boolean) -> Unit = {},
) {
    val readerSettings = settings ?: run {
        val readerSettingsVm: ReaderSettingsViewModel = hiltViewModel()
        val value by readerSettingsVm.settings.collectAsStateWithLifecycle()
        value
    }
    val colors = readerColors(readerSettings.appearance)
    val blocks = reader.blocks
    val fragmentsById = remember(reader.fragments) { reader.fragments.associateBy { it.id } }
    val blocksById = remember(blocks) { blocks.associateBy { it.anchorId } }
    val blockByFragment = remember(blocks) { buildMap { blocks.forEachIndexed { index, block ->
        block.fragmentIds.forEach { put(it, index) }
    } } }
    val spoken = textHighlight?.takeIf { it.bookId == reader.book.id }
    val highlightColor = readerHighlightColor(readerSettings.appearance)
    val contents = remember(reader.chapters) { readingContents(reader.chapters) }
    val characterStarts = remember(blocks) {
        IntArray(blocks.size + 1).also { starts -> blocks.forEachIndexed { index, block -> starts[index + 1] = starts[index] + block.text.length } }
    }
    val textLayouts = remember(blocks) { mutableStateMapOf<String, TextLayoutResult>() }
    var controlsVisible by rememberSaveable(reader.book.id) { mutableStateOf(true) }
    val currentFocusCallback by rememberUpdatedState(onReaderFocusChanged)
    DisposableEffect(controlsVisible) {
        currentFocusCallback(!controlsVisible)
        onDispose { currentFocusCallback(false) }
    }
    val jump = readingJump?.takeIf {
        it.bookId == reader.book.id && it.chapterId == reader.chapter.id
    }
    val anchorId = jump?.fragmentId ?: reader.position?.fragmentId
    val anchor = blocks.indexOfFirst { anchorId in it.fragmentIds }.coerceAtLeast(0)
    val listState = rememberLazyListState(anchor, reader.position?.scrollOffsetPx ?: 0)
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var audioJumpHint by rememberSaveable(reader.book.id) { mutableStateOf(false) }
    var selectionBlockId by rememberSaveable(reader.book.id) { mutableStateOf<String?>(null) }
    var selectionStart by rememberSaveable(reader.book.id) { mutableStateOf(0) }
    var selectionEnd by rememberSaveable(reader.book.id) { mutableStateOf(0) }
    val selectedBlock = blocksById[selectionBlockId]
    val selectedAudioTarget = selectedBlock?.let { textSelectionPosition(it, fragmentsById, selectionStart, selectionEnd) }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val textInteraction = remember { MutableInteractionSource() }
    val selectingText by textInteraction.collectIsPressedAsState()
    fun clearSelection() { selectionBlockId = null; selectionStart = 0; selectionEnd = 0 }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var showExports by remember { mutableStateOf(false) }
    val requestAudioSave = rememberBookDownloadRequester(
        onStart = { onSaveAudio(reader.book.id, it.takeUnless { value -> value == "all" }) },
        onResume = onResumeAudio, onRetry = onResumeAudio,
    )
    val followObstructed = selectingText || selectedAudioTarget != null || bookmarkMenu ||
        showChapters || saveMenu || showExports

    LaunchedEffect(jump?.fragmentId, jump?.fractionInFragment) {
        val destination = jump ?: return@LaunchedEffect
        val blockIndex = blocks.indexOfFirst { destination.fragmentId in it.fragmentIds }
        if (blockIndex >= 0) {
            listState.scrollToItem(blockIndex)
            val block = blocks[blockIndex]
            val fragmentById = reader.fragments.associateBy { it.id }
            val offsetBefore = block.fragmentIds.takeWhile { it != destination.fragmentId }
                .sumOf { fragmentById[it]?.text?.length ?: 0 }
            val targetLength = fragmentById[destination.fragmentId]?.text?.length ?: 0
            val position = (offsetBefore + (targetLength *
                destination.fractionInFragment.coerceIn(0.0, 1.0)).toInt())
                .coerceIn(0, block.text.length)
            val layout = withTimeoutOrNull(5000) {
                snapshotFlow { textLayouts[block.anchorId] }.filterNotNull().first()
            }
            if (layout != null && block.text.isNotEmpty()) {
                val line = layout.getLineForOffset(position.coerceAtMost(block.text.length - 1))
                listState.scrollToItem(blockIndex, layout.getLineTop(line).toInt().coerceAtLeast(0))
            }
            audioJumpHint = destination.approximate
        }
        onReadingJumpApplied()
    }
    LaunchedEffect(readerSettings.followNarration, audioPlaying, spoken?.bookId, state.autoFollowAllowed, state.committing) {
        // The preference is global; the session flag only protects the reading bookmark.
        // Start following only when this book actually has a spoken fragment.
        val enabled = readerSettings.followNarration && audioPlaying && spoken != null && state.autoFollowAllowed && !state.committing
        if (enabled != state.following && (enabled || !readerSettings.followNarration)) {
            onFollowChanged(enabled)
        }
    }
    LaunchedEffect(readerSettings.followNarration, state.following, state.autoFollowAllowed, state.committing, spoken?.fragmentId, audioPlaying, followObstructed) {
        // A follow session is PREVIEW before any scroll; all save paths reject it.
        if (!readerSettings.followNarration || !state.following || !state.autoFollowAllowed || state.committing || !state.preview || !audioPlaying || followObstructed) return@LaunchedEffect
        val id = spoken?.fragmentId ?: return@LaunchedEffect
        // An old read-only field must not bring its cursor back over the audio scroll.
        focusManager.clearFocus()
        val index = blockByFragment[id] ?: return@LaunchedEffect
        val block = blocks[index]
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        val layout = withTimeoutOrNull(1000) {
            snapshotFlow { textLayouts[block.anchorId] }.filterNotNull().first()
        }
        val range = textHighlightRange(block, fragmentsById, id)
        val offset = if (layout != null && range != null && !range.isEmpty())
            layout.getLineTop(layout.getLineForOffset(range.first.coerceAtMost(layout.layoutInput.text.lastIndex))).toInt().coerceAtLeast(0)
            else 0
        // Move once per fragment, not on every playback tick or speed update.
        listState.animateScrollToItem(index, offset)
    }
    val progress by remember(blocks, listState) { derivedStateOf {
        val index = listState.firstVisibleItemIndex.coerceIn(0, blocks.lastIndex)
        val block = blocks[index]
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val layout = textLayouts[block.anchorId]
        val offset = if (layout != null && visible != null) {
            val y = (listState.layoutInfo.viewportStartOffset - visible.offset).coerceAtLeast(0).toFloat()
            layout.getOffsetForPosition(Offset(0f, y)).coerceIn(0, block.text.length)
        } else 0
        val measured = listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        textReadingPercent(characterStarts.last(), characterStarts[index] + offset, measured && !listState.canScrollForward)
    } }
    val currentChapter by remember(reader, listState) { derivedStateOf {
        val chapterId = blocks.getOrNull(listState.firstVisibleItemIndex)?.chapterId
        reader.chapters.firstOrNull { it.id == chapterId } ?: reader.chapter
    } }
    val active = audioBookId == reader.book.id || state.audioExports.any {
        it.bookId == audioBookId && it.bookSourceId.startsWith("text_audio:${reader.book.id}:")
    }
    fun save(index: Int = listState.firstVisibleItemIndex, offset: Int = listState.firstVisibleItemScrollOffset) {
        if (state.preview || state.committing) return
        blocks.getOrNull(index)?.let {
            onPosition(reader.book.id, reader.chapter.id, it.anchorId, offset)
        }
    }
    fun listen() {
        save()
        if (active && (audioPlaying || audioBuffering)) onToggleAudio()
        else onListen(reader.book.id, null)
    }
    fun visibleFragment(): Pair<String, Double>? {
        val block = blocks.getOrNull(listState.firstVisibleItemIndex) ?: return null
        val layout = textLayouts[block.anchorId]
        val offset = layout?.getOffsetForPosition(Offset(0f, listState.firstVisibleItemScrollOffset.toFloat())) ?: 0
        return textPositionAtOffset(block, fragmentsById, offset)?.let { it.fragmentId to it.fraction }
    }

    fun fromReading() {
        val target = selectedAudioTarget?.let { it.fragmentId to it.fraction } ?: visibleFragment()
        focusManager.clearFocus()
        target?.let { (fragment, fraction) -> onListenFromText(reader.book.id, fragment, fraction) }
        clearSelection()
    }
    fun back() {
        val block = blocks.getOrNull(listState.firstVisibleItemIndex)
        if (block != null && onBackAtPosition != null) onBackAtPosition(block.anchorId, listState.firstVisibleItemScrollOffset)
        else { save(); onBack() }
    }
    fun selectChapter(chapterId: String) {
        val target = blocks.indexOfFirst { it.chapterId == chapterId }.coerceAtLeast(0)
        showChapters = false
        clearSelection()
        scope.launch { listState.scrollToItem(target); save() }
    }
    BackHandler { back() }
    LaunchedEffect(listState, reader.chapter.id) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(300L).collect { (index, offset) -> save(index, offset) }
    }
    DisposableEffect(owner, state.readerSession) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) save()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { save(); owner.lifecycle.removeObserver(observer) }
    }
    if (showExports) {
        ModalBottomSheet(onDismissRequest = { showExports = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm)) {
                Text("Сохранение аудио", style = MaterialTheme.typography.titleLarge)
                TextBookAudioExports(
                    reader = reader.copy(chapter = currentChapter), state = state,
                    onPause = onPauseAudio, onResume = onResumeAudio,
                    onRemove = onRemoveAudio, onPlaySaved = onPlaySavedAudio,
                )
            }
        }
    }
    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            Text("Главы", modifier = Modifier.padding(AbredSpacing.ScreenHorizontal), style = MaterialTheme.typography.titleLarge)
            LazyColumn {
                items(contents, key = { it.chapterId }) { chapter ->
                    TextButton(onClick = { selectChapter(chapter.chapterId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if ((chapter.wholeBook || chapter.chapterId == currentChapter.id)) "• ${chapter.title}" else chapter.title,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerMaxHeight = maxHeight * TextReaderDesign.HeaderMaxHeightFraction
        Column(
            Modifier.fillMaxSize().widthIn(max = AbredSizes.ContentMaxWidth),
        ) {
            if (controlsVisible) Column(Modifier.heightIn(max = headerMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = AbredSpacing.ScreenHorizontal)) {
                Row {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад в библиотеку") }
                    Column(Modifier.weight(1f).padding(vertical = AbredSpacing.Xs)) {
                        Text(reader.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (reader.metadata.author.isNotBlank()) Text(reader.metadata.author, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(if (currentChapter.automatic) "Текст книги" else currentChapter.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    if (state.preview) Box {
                        IconButton(onClick = { bookmarkMenu = true }) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = "Закладка чтения")
                        }
                        DropdownMenu(expanded = bookmarkMenu, onDismissRequest = { bookmarkMenu = false }) {
                            DropdownMenuItem(text = { Text("Читать отсюда") }, enabled = !state.committing, onClick = {
                                bookmarkMenu = false
                                blocks.getOrNull(listState.firstVisibleItemIndex)?.let {
                                    onCommitPreview(it.anchorId, listState.firstVisibleItemScrollOffset)
                                }
                            })
                            DropdownMenuItem(text = { Text("К моей закладке") }, enabled = !state.committing, onClick = {
                                bookmarkMenu = false
                                onReturnToBookmark()
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Оглавление")
                    }
                    IconButton(onClick = ::listen) {
                        val paused = active && (audioPlaying || audioBuffering)
                        Icon(if (paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (paused) "Пауза" else "Продолжить слушать")
                    }
                    Box {
                        IconButton(onClick = { saveMenu = true }) {
                            Icon(Icons.Default.Download, contentDescription = "Сохранить аудио")
                        }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            if (!currentChapter.automatic) DropdownMenuItem(text = { Text("Текущую главу") }, enabled = !state.startingAudioExport, onClick = {
                                saveMenu = false
                                requestAudioSave(BookDownloadAction.Start, currentChapter.id)
                            })
                            DropdownMenuItem(text = { Text("Всю книгу") }, enabled = !state.startingAudioExport, onClick = {
                                saveMenu = false
                                requestAudioSave(BookDownloadAction.Start, "all")
                            })
                            if (state.audioExports.any { it.bookSourceId.startsWith("text_audio:${reader.book.id}:") && it.deletedAtMs == null && it.state != "purged" }) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Управление сохранением") }, onClick = {
                                    saveMenu = false
                                    showExports = true
                                })
                            }
                        }
                    }
                    IconButton(onClick = ::fromReading) {
                        Icon(Icons.Default.PlayCircleOutline, contentDescription = "Слушать с этого места")
                    }
                    if (selectedAudioTarget != null && selectedBlock != null) IconButton(onClick = {
                        val text = selectedBlock.text.removeSuffix("\n")
                        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
                        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
                        clipboard.setText(AnnotatedString(text.substring(start, end)))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать выделенный текст")
                    }
                }
                if (spoken?.approximate == true) Text("Подсветка приблизительная: у этой записи нет карты времени.", style = MaterialTheme.typography.bodySmall)
                if (state.preview) Text("Просмотр места озвучки", style = MaterialTheme.typography.bodySmall)
                if (audioJumpHint && !state.following) Text("Положение внутри фрагмента приблизительное.", style = MaterialTheme.typography.bodySmall)
                if (active && audioBuffering) Text("Подготовка к воспроизведению…", style = MaterialTheme.typography.bodySmall)
                if (active && !audioError.isNullOrBlank()) Text(audioError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (controlsVisible) HorizontalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().background(colors.first).testTag("text-reader-content"), state = listState,
                contentPadding = PaddingValues(horizontal = if (readerSettings.wideMargins) AbredSpacing.Lg else AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
            ) {
                items(blocks, key = { it.anchorId }) { block ->
                    val range = textHighlightRange(block, fragmentsById, spoken?.fragmentId)
                    val content = AnnotatedString.Builder(block.text.removeSuffix("\n")).apply {
                        if (range != null && !range.isEmpty()) addStyle(SpanStyle(background = highlightColor), range.first, range.last + 1)
                    }.toAnnotatedString()
                    val selection = if (selectionBlockId == block.anchorId) TextRange(selectionStart, selectionEnd) else TextRange.Zero
                    BasicTextField(
                        value = TextFieldValue(content, selection),
                        onValueChange = { value ->
                            if (value.selection.collapsed) {
                                if (selectionBlockId == block.anchorId) clearSelection()
                            } else {
                                selectionBlockId = block.anchorId
                                selectionStart = value.selection.start
                                selectionEnd = value.selection.end
                            }
                        },
                        readOnly = true,
                        textStyle = readerTextStyle(readerSettings).copy(color = colors.second),
                        interactionSource = textInteraction,
                        modifier = Modifier.fillMaxWidth().testTag("reader-paragraph:${block.anchorId}")
                            .then(if (range != null) Modifier.testTag("spoken-fragment") else Modifier),
                        onTextLayout = { layout ->
                            textLayouts[block.anchorId] = layout
                            // Keep only a small set of visible/prefetched layouts, not the whole book.
                            if (textLayouts.size > 16) {
                                val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                                textLayouts.keys.toList().filter { it !in visibleKeys && it != block.anchorId }
                                    .take(textLayouts.size - 16).forEach { textLayouts.remove(it) }
                            }
                        },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().background(colors.first)) {
                if (controlsVisible) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(horizontal = AbredSpacing.ScreenHorizontal), verticalAlignment = Alignment.CenterVertically) {
                    Text("Прочитано $progress%", style = MaterialTheme.typography.labelMedium,
                        color = colors.second, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { controlsVisible = !controlsVisible }) {
                        Icon(if (controlsVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (controlsVisible) "Скрыть панели чтения" else "Показать панели чтения", tint = colors.second)
                    }
                }
            }
        }
    }
}
