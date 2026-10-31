package com.example.data.textbook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.example.data.storage.PublicTextBookStorage
import com.example.data.textsource.TextBookFileStore
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TextImportResult(
    val bookId: String, val duplicate: Boolean,
    val originalMoved: Boolean = false, val moveError: String? = null,
)
internal data class TextNarrationBook(val book: TextBookEntity, val chapters: List<TextChapterEntity>, val fragments: List<TextFragmentEntity>, val metadata: TextBookMetadata = TextBookMetadata())
internal data class TextReadingSnapshot(
    val book: TextBookEntity, val chapters: List<TextChapterEntity>, val chapter: TextChapterEntity,
    val fragments: List<TextFragmentEntity>, val position: TextReadingPositionEntity?,
    val metadata: TextBookMetadata = TextBookMetadata(),
    val blocks: List<TextReadingBlock> = readingBlocks(fragments),
)

@Singleton
internal class TextLibraryRepository private constructor(
    private val context: Context,
    databaseFactory: () -> TextLibraryDatabase,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, { TextLibraryDatabase.open(context) })
    internal constructor(context: Context, database: TextLibraryDatabase) : this(context, { database })
    private val database by lazy(databaseFactory)
    private val dao get() = database.library()
    // Acquire before dispatching IO: writes and reader reopen keep the caller's order.
    private val mutations = Mutex()
    private val sourceFolder get() = File(context.filesDir, "text-library")
    private val metadataStore get() = TextBookMetadataStore(sourceFolder)
    private val originals by lazy { PublicTextBookStorage(context) }
    private val originalFiles by lazy { TextBookFileStore(context.filesDir) }
    private val activityStore by lazy { TextLibraryActivityStore(context) }
    private val metadataRevision = MutableStateFlow(0L)
    fun observeMetadataChanges() = metadataRevision.asStateFlow()
    suspend fun saveSourceDetails(bookId: String, description: String, authors: List<String>) = mutations.withLock {
        withContext(Dispatchers.IO) {
            if (dao.book(bookId) != null && metadataStore.saveSourceDetails(bookId, description, authors)) {
                metadataRevision.value += 1
            }
        }
    }
    fun observeActivity() = activityStore.state
    suspend fun saveCatalogActivity(value: TextLibraryActivity) = mutations.withLock {
        withContext(Dispatchers.IO) { if (dao.book(value.bookId) != null) activityStore.catalog(value) }
    }
    suspend fun recordActivity(bookId: String) = mutations.withLock {
        withContext(Dispatchers.IO) { if (dao.book(bookId) != null) activityStore.visit(bookId) }
    }
    suspend fun restoreReadingActivity(books: List<TextBookEntity>) = mutations.withLock {
        withContext(Dispatchers.IO) {
            books.filter { it.lastOpenedAtMs > it.importedAtMs && (activityStore.state.value[it.id]?.lastActivityAtMs ?: 0L) == 0L }.forEach {
                if (dao.book(it.id) != null) activityStore.restoreActivity(it.id, it.lastOpenedAtMs)
            }
        }
    }
    suspend fun restoreAudioActivity(bookId: String, timestamp: Long) = mutations.withLock {
        withContext(Dispatchers.IO) { if (dao.book(bookId) != null) activityStore.restoreActivity(bookId, timestamp) }
    }
    suspend fun hideHistory(bookId: String) = withContext(Dispatchers.IO) { activityStore.hideHistory(bookId) }
    suspend fun hideSeries(key: String) = withContext(Dispatchers.IO) { activityStore.hideSeries(key) }

    suspend fun metadata(bookId: String): TextBookMetadata = withContext(Dispatchers.IO) { metadataStore.read(bookId) }

    fun observeBooks(): Flow<List<TextBookEntity>> = dao.observeBooks()
    suspend fun chapters(bookId: String): List<TextChapterEntity> = withContext(Dispatchers.IO) { dao.chapters(bookId) }
    suspend fun narrationFragment(bookId: String, fragmentId: String): TextFragmentEntity? = withContext(Dispatchers.IO) {
        val fragment = dao.narrationFragment(bookId, fragmentId) ?: return@withContext null
        if (fragment.text.isNotBlank()) return@withContext fragment
        // A reader cursor may point at a legacy whitespace-only fragment.
        // Prefer nearby speech in the same chapter without rewriting its stored ID.
        val spoken = dao.allFragments(bookId).filter { it.text.isNotBlank() }
        spoken.firstOrNull { it.chapterId == fragment.chapterId && it.ordinal > fragment.ordinal }
            ?: spoken.lastOrNull { it.chapterId == fragment.chapterId && it.ordinal < fragment.ordinal }
            ?: spoken.firstOrNull { it.startOffset >= fragment.endOffset }
            ?: spoken.lastOrNull { it.endOffset <= fragment.startOffset }
    }

    suspend fun narrationBook(bookId: String): TextNarrationBook = withContext(Dispatchers.IO) {
        database.withTransaction {
            val book = checkNotNull(dao.book(bookId)) { "Текстовая книга удалена." }
            // Filter only the audio snapshot: existing text, fragment IDs and reading
            // bookmarks stay intact, including books imported by earlier versions.
            val fragments = dao.allFragments(bookId).filter { it.text.isNotBlank() }
            val spokenChapters = fragments.mapTo(mutableSetOf()) { it.chapterId }
            TextNarrationBook(book, dao.chapters(bookId).filter { it.id in spokenChapters }, fragments, metadataStore.read(bookId))
        }
    }

    suspend fun importTxt(uri: Uri): TextImportResult = importBook(uri)

    suspend fun importBook(uri: Uri, selection: String? = null, moveOriginal: Boolean = false): TextImportResult =
        TextBookFileStore.lifecycle.withLock {
            val result = importSource(
                resolveName = {
                    val displayName = if (uri.scheme == "file") uri.path?.let { File(it).name } else
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    val name = displayName?.takeIf { it.isNotBlank() } ?: "Книга.txt"
                    val suffix = listOf(".fb2.zip", ".epub", ".fb2", ".zip", ".txt").firstOrNull { name.endsWith(it, true) }.orEmpty()
                    name.dropLast(suffix.length).take(240 - suffix.length) + suffix
                },
                selection = selection,
                openInput = { checkNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть книгу." } },
            )
            if (moveOriginal) moveImportedOriginal(uri, result) else result
        }

    private suspend fun moveImportedOriginal(uri: Uri, result: TextImportResult): TextImportResult = withContext(Dispatchers.IO) {
        var published = false
        try {
            val book = checkNotNull(dao.book(result.bookId)) { "Книга больше не находится в библиотеке." }
            val privateSource = File(sourceFolder, book.sourceFileName)
            require(privateSource.length() in 1L..EbookImportParser.MAX_SOURCE_BYTES.toLong()) {
                "Сохранённый исходник книги отсутствует или повреждён."
            }
            val bytes = privateSource.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == book.sourceSha256) { "Сохранённый исходник книги повреждён. Исходный файл не перемещался." }
            val coroutine = currentCoroutineContext()
            val file = originalFiles.saveImported(book.originalName, bytes) { coroutine.ensureActive() }
            originals.publishForMove(file, file.name.substring(65), when {
                file.name.endsWith(".zip", true) -> "application/zip"
                file.name.endsWith(".epub", true) -> "application/epub+zip"
                file.name.endsWith(".txt", true) -> "text/plain"
                else -> "application/octet-stream"
            }, bytes) { coroutine.ensureActive() }
            published = true
            coroutine.ensureActive()
            check(originals.matches(uri, bytes) { coroutine.ensureActive() }) {
                "Исходный файл изменился или стал недоступен. Он не удалялся приложением."
            }
            val deleted = if (uri.scheme == "file") {
                uri.path?.let { source -> File(source).let { !it.exists() || it.delete() } } ?: false
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
            check(deleted) { "Источник не разрешил удалить исходный файл." }
            result.copy(originalMoved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = if (published) "Книга добавлена, копия сохранена в Books. Перенос исходного файла не завершён: "
                else "Книга добавлена, но перенести исходный файл в Books не удалось: "
            result.copy(moveError = message + (error.message ?: "проверьте доступ к файлу."))
        }
    }

    suspend fun importDownloaded(file: File, displayName: String): TextImportResult =
        importSource({ displayName.take(240) }, null, { file.inputStream() })

    suspend fun contains(bookId: String): Boolean = withContext(Dispatchers.IO) { dao.book(bookId) != null }

    private suspend fun importSource(
        resolveName: () -> String, selection: String?, openInput: () -> InputStream,
    ): TextImportResult = mutations.withLock {
        withContext(Dispatchers.IO) {
            val name = resolveName()
            val folder = sourceFolder
            check(folder.isDirectory || folder.mkdirs()) { "Не удалось создать папку текстовых книг." }
            check(folder.usableSpace >= EbookImportParser.MAX_SOURCE_BYTES * 3L) { "Для импорта освободите хотя бы 96 МБ." }
            folder.listFiles()?.filter { it.name.startsWith(".import-") }?.forEach { it.delete() }
            val temporary = File(folder, ".import-${UUID.randomUUID()}")
            var destination: Pair<String, File>? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArrayOutputStream().use { memory ->
                    openInput().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(memory.size().toLong() + count <= EbookImportParser.MAX_SOURCE_BYTES) { "Выберите книгу или архив до 32 МБ." }
                                memory.write(buffer, 0, count)
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                    }
                    memory.toByteArray()
                }
                val ebook = EbookImportParser.parse(bytes, name, selection)
                // Keep legacy TXT identities; distinct selected members of one archive get distinct IDs.
                val rawDigest = digest.digest()
                val sourceHash = rawDigest.joinToString("") { "%02x".format(it) }
                val identityDigest = ebook.entry?.let { entry ->
                    MessageDigest.getInstance("SHA-256").digest(rawDigest + byteArrayOf(0) + entry.toByteArray(Charsets.UTF_8))
                } ?: rawDigest
                val hash = identityDigest.joinToString("") { "%02x".format(it) }
                val bookId = "txt_$hash"
                if (dao.book(bookId) != null) return@withContext TextImportResult(bookId, true)
                val parsed = ebook.text
                currentCoroutineContext().ensureActive()
                val file = File(folder, "$bookId.source")
                // An orphan can remain after process death before the Room transaction commits.
                check(!file.exists() || file.delete()) { "Не удалось заменить незавершённый импорт." }
                check(temporary.renameTo(file)) { "Не удалось сохранить исходный TXT." }
                destination = bookId to file
                metadataStore.remove(bookId)
                metadataStore.save(bookId, ebook)
                val now = System.currentTimeMillis()
                val chapters = parsed.chapters.map {
                    TextChapterEntity("$bookId:c${it.ordinal}", bookId, it.ordinal, it.title, it.startOffset, it.endOffset, it.automatic)
                }
                database.withTransaction {
                    dao.insertBook(TextBookEntity(
                        id = bookId, title = ebook.title.ifBlank { (ebook.entry ?: name).substringAfterLast('/').substringBeforeLast('.') }.ifBlank { "Текстовая книга" }.take(240),
                        originalName = name, sourceFileName = file.name, sourceSha256 = sourceHash, encoding = parsed.encoding,
                        parserVersion = TxtBookParser.VERSION, importedAtMs = now, lastOpenedAtMs = now,
                        characterCount = parsed.normalizedText.length, chapterCount = chapters.size,
                        fragmentCount = parsed.chapters.sumOf { it.fragments.size },
                    ))
                    dao.insertChapters(chapters)
                    parsed.chapters.forEachIndexed { index, chapter ->
                        currentCoroutineContext().ensureActive()
                        chapter.fragments.chunked(250).forEach { batch ->
                            dao.insertFragments(batch.map {
                                TextFragmentEntity("${chapters[index].id}:f${it.ordinal}", chapters[index].id, it.ordinal, it.startOffset, it.endOffset, it.text)
                            })
                        }
                    }
                }
                TextImportResult(bookId, false)
            } finally {
                temporary.delete()
                destination?.let { (bookId, file) ->
                    withContext(NonCancellable) {
                        if (dao.book(bookId) == null) { file.delete(); metadataStore.remove(bookId) }
                    }
                }
            }
        }
    }

    suspend fun open(bookId: String, chapterId: String? = null): TextReadingSnapshot = mutations.withLock {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val book = checkNotNull(dao.book(bookId)) { "Текстовая книга больше не найдена." }
                val chapters = dao.chapters(bookId)
                val position = dao.position(bookId)
                val resumeChapter = position?.let { dao.chapterForFragment(bookId, it.fragmentId) }
                val selected = chapters.firstOrNull { it.id == (chapterId ?: resumeChapter) } ?: chapters.first()
                val fragments = dao.allFragments(bookId)
                check(fragments.isNotEmpty()) { "В этой главе нет текста." }
                val restored = position?.takeIf { saved -> fragments.any { it.id == saved.fragmentId && it.chapterId == selected.id } }
                    ?: TextReadingPositionEntity(bookId, fragments.first { it.chapterId == selected.id }.id, 0, System.currentTimeMillis())
                dao.touch(bookId, System.currentTimeMillis())
                activityStore.visit(bookId)
                TextReadingSnapshot(book, chapters, selected, fragments, restored, metadataStore.read(bookId))
            }
        }
    }

    suspend fun savePosition(bookId: String, fragmentId: String, scrollOffsetPx: Int, permitted: () -> Boolean = { true }): Boolean = mutations.withLock {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (dao.chapterForFragment(bookId, fragmentId) != null && permitted()) {
                    dao.putPosition(TextReadingPositionEntity(bookId, fragmentId, scrollOffsetPx.coerceAtLeast(0), System.currentTimeMillis()))
                    true
                } else false
            }
        }
    }

    suspend fun needsOriginalRemovalPermission(bookId: String): Boolean = withContext(Dispatchers.IO) {
        if (originals.canPublish()) return@withContext false
        val book = dao.book(bookId) ?: return@withContext false
        if (dao.otherSourceOwners(book.sourceSha256, bookId) > 0) return@withContext false
        originalFiles.originalsFor(book.sourceSha256).any { originals.hasPublicCopies(it) }
    }

    suspend fun remove(bookId: String) = TextBookFileStore.lifecycle.withLock {
        mutations.withLock {
            withContext(Dispatchers.IO) {
                val book = dao.book(bookId) ?: return@withContext
                // Several selected books can share one downloaded/imported archive.
                if (dao.otherSourceOwners(book.sourceSha256, bookId) == 0) {
                    originalFiles.originalsFor(book.sourceSha256).forEach { original ->
                        currentCoroutineContext().ensureActive()
                        originals.remove(original)
                        check(!original.exists() || original.delete()) { "Не удалось удалить оригинал книги из папки приложения." }
                        original.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
                    }
                }
                val source = File(sourceFolder, book.sourceFileName)
                check(source.canonicalFile.parentFile == sourceFolder.canonicalFile)
                check(!source.exists() || source.delete()) { "Не удалось удалить импортированную копию книги." }
                dao.delete(bookId)
                activityStore.remove(bookId)
                metadataStore.remove(bookId)
            }
        }
    }
}
