package com.example.data.textsource

import android.content.Context
import com.example.data.textbook.TextLibraryActivity
import com.example.data.textbook.TextImportResult
import com.example.data.textbook.TextLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class RemoteTextBookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val registry: RemoteTextSourceRegistry,
    private val books: TextLibraryRepository,
) {
    val provider: RemoteTextBookProvider get() = registry.default
    private val imports = Mutex()
    private val links = context.getSharedPreferences("abred_remote_text_origins", Context.MODE_PRIVATE)
    private fun key(id: String, sourceCode: String): String {
        val source = registry.provider(sourceCode)
        require(source.bookId(source.bookUrl(id)) == id)
        return "${source.code}:$id"
    }

    suspend fun localBookId(id: String, sourceCode: String = provider.code): String? = withContext(Dispatchers.IO) {
        links.getString(key(id, sourceCode), null)?.takeIf { books.contains(it) }
    }

    /** Keep existing origin links and book IDs; downloading another format never resets progress. */
    suspend fun importBook(
        book: RemoteTextBook,
        sourceCode: String = provider.code,
        option: RemoteTextDownload? = null,
    ): TextImportResult = imports.withLock {
        val source = registry.provider(sourceCode)
        require(source.bookId(book.url) == book.id)
        if (option != null) require(option in book.downloads && option.canImport) { "Для библиотеки выберите FB2, EPUB или TXT." }
        localBookId(book.id, sourceCode)?.let {
            rememberCatalog(book, sourceCode, it)
            return@withLock TextImportResult(it, true)
        }
        val selected = option ?: book.downloads.firstOrNull { it.canImport }
        require(selected != null || book.downloads.isEmpty()) { "Источник не предлагает формат для чтения в приложении." }
        val bytes = if (selected != null) source.downloadFile(book, selected) else source.download(book)
        val original = selected ?: RemoteTextDownload("default", source.importExtension,
            book.downloadUrl.orEmpty(), source.importExtension)
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val file = TextBookFileStore(context.filesDir).save(sourceCode, book.id, book.title, original, bytes) {
                coroutine.ensureActive()
            }
            coroutine.ensureActive()
            // Import precisely the chosen file. Originals remain in files/book after import or failure.
            val result = books.importDownloaded(file, original.fileName(book.title))
            check(links.edit().putString(key(book.id, sourceCode), result.bookId).commit()) {
                "Книга добавлена, но не удалось сохранить связь с источником. Повторите открытие."
            }
            rememberCatalog(book, sourceCode, result.bookId)
            result
        }
    }

    suspend fun rememberCatalog(book: RemoteTextBook, sourceCode: String, localId: String) {
        val source = registry.provider(sourceCode)
        books.saveCatalogActivity(TextLibraryActivity(
            bookId = localId, sourceCode = source.code, sourceName = source.name, remoteBookId = book.id,
            seriesId = book.series?.id.orEmpty(), seriesName = book.series?.name.orEmpty(), seriesNumber = book.seriesNumber.orEmpty(),
        ))
    }
}
