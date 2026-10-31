package com.example.data.textsource

import android.content.Context
import com.example.data.textbook.TextLibraryActivity
import com.example.data.textbook.TextImportResult
import com.example.data.textbook.TextLibraryRepository
import com.example.data.storage.PublicTextBookStorage
import com.example.data.storage.TextBookOriginalPublisher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class RemoteTextBookRepository internal constructor(
    private val context: Context,
    val registry: RemoteTextSourceRegistry,
    private val books: TextLibraryRepository,
    private val originals: TextBookOriginalPublisher,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        registry: RemoteTextSourceRegistry,
        books: TextLibraryRepository,
    ) : this(context, registry, books, PublicTextBookStorage(context))

    val provider: RemoteTextBookProvider get() = registry.default
    fun observeLocalBooks() = combine(books.observeBooks(), books.observeActivity()) { rows, _ -> rows }
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
        val existing = TextBookFileStore.lifecycle.withLock {
            localBookId(book.id, sourceCode)?.also { rememberCatalog(book, sourceCode, it) }
        }
        if (existing != null) return@withLock TextImportResult(existing, true)
        check(originals.canPublish()) { "Разрешите доступ к хранилищу для сохранения книг в Download/AudioBookRed/Books." }
        val selected = option ?: book.downloads.firstOrNull { it.canImport }
        require(selected != null || book.downloads.isEmpty()) { "Источник не предлагает формат для чтения в приложении." }
        val bytes = if (selected != null) source.downloadFile(book, selected) else source.download(book)
        val original = selected ?: RemoteTextDownload("default", source.importExtension,
            book.downloadUrl.orEmpty(), source.importExtension)
        withContext(Dispatchers.IO) {
            TextBookFileStore.lifecycle.withLock {
                val coroutine = currentCoroutineContext()
                val fileStore = TextBookFileStore(context.filesDir)
                val existed = fileStore.originalFile(sourceCode, book.id, book.title, original, bytes).exists()
                val file = fileStore.save(sourceCode, book.id, book.title, original, bytes) {
                    coroutine.ensureActive()
                }
                // Do not publish an HTML error page or a broken archive into Books.
                val result = try {
                    coroutine.ensureActive()
                    books.importDownloaded(file, original.fileName(book.title))
                } catch (error: Throwable) {
                    if (!existed) {
                        if (file.exists() && !file.delete()) error.addSuppressed(IOException("Не удалось убрать незавершённую загрузку книги."))
                        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
                    }
                    throw error
                }
                coroutine.ensureActive()
                try {
                    originals.publish(file, original.fileName(book.title), original.mimeType, bytes) {
                        coroutine.ensureActive()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    throw IOException("Книга добавлена в библиотеку, но сохранить оригинал в Books не удалось. Повторите загрузку из источника.", error)
                }
                coroutine.ensureActive()
                check(links.edit().putString(key(book.id, sourceCode), result.bookId).commit()) {
                    "Книга добавлена, но не удалось сохранить связь с источником. Повторите открытие."
                }
                rememberCatalog(book, sourceCode, result.bookId)
                result
            }
        }
    }

    suspend fun rememberCatalog(book: RemoteTextBook, sourceCode: String, localId: String) {
        val source = registry.provider(sourceCode)
        books.saveSourceDetails(localId, book.description, book.authors.map { it.name })
        books.saveCatalogActivity(TextLibraryActivity(
            bookId = localId, sourceCode = source.code, sourceName = source.name, remoteBookId = book.id,
            seriesId = book.series?.id.orEmpty(), seriesName = book.series?.name.orEmpty(), seriesNumber = book.seriesNumber.orEmpty(),
        ))
    }
}
