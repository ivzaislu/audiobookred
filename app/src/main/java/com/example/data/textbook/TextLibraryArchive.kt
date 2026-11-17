package com.example.data.textbook

import android.graphics.BitmapFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ArchivedTextBook(
    val book: TextBookEntity,
    val chapters: List<TextChapterEntity>,
    val fragments: List<TextFragmentEntity>,
    val position: TextReadingPositionEntity?,
    val assets: List<String>,
    val contents: TextContentsEdits? = null,
)

internal class TextLibraryArchive(private val folder: File) {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ArchivedTextBook::class.java)

    suspend fun write(output: OutputStream, books: List<TextBookEntity>, dao: TextLibraryDao) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.entry(VERSION) {}
            for ((index, book) in books.withIndex()) {
                currentCoroutineContext().ensureActive()
                val assets = assetNames(book).filter { File(folder, it).isFile }
                require(book.sourceFileName in assets) { "В библиотеке отсутствует исходник книги." }
                val chapters = dao.chapters(book.id)
                val fragments = dao.allFragments(book.id)
                val edits = TextContentsStore(folder).read(book.id).validate(chapters, fragments)
                val record = ArchivedTextBook(book, chapters, fragments, dao.position(book.id), assets, edits)
                zip.entry("book-$index.json") { zip.write(adapter.toJson(record).toByteArray(Charsets.UTF_8)) }
                for (name in assets) {
                    zip.entry(name) {
                        File(folder, name).inputStream().use { input ->
                            val hash = input.copyBounded(zip, assetLimit(book, name))
                            if (name == book.sourceFileName) {
                                require(hash == book.sourceSha256) { "Исходник книги повреждён." }
                            }
                        }
                    }
                }
            }
            zip.entry(END) {}
        }
    }

    suspend fun read(input: InputStream, dao: TextLibraryDao, published: MutableMap<File, String>): Int {
        check(folder.isDirectory || folder.mkdirs()) { "Не удалось открыть папку книг." }
        var added = 0
        val seen = hashSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            require(zip.nextEntry?.name == VERSION) { "Неизвестный формат архива текстовых книг." }
            require(zip.read() == -1)
            while (true) {
                currentCoroutineContext().ensureActive()
                val name = zip.nextEntry?.name
                if (name == END) {
                    require(zip.read() == -1 && zip.nextEntry == null)
                    break
                }
                require(name == "book-${seen.size}.json") { "Архив текстовых книг повреждён или неполон." }
                val record = checkNotNull(adapter.fromJson(zip.readBounded(32 * MIB).toString(Charsets.UTF_8)))
                val book = record.book
                validate(record)
                require(seen.add(book.id))
                val newBook = dao.book(book.id) == null
                for (asset in record.assets) {
                    require(zip.nextEntry?.name == asset)
                    val target = File(folder, asset)
                    val temporary = File(folder, ".archive-${book.id}.part")
                    try {
                        val limit = assetLimit(book, asset)
                        if (asset == book.sourceFileName) {
                            val hash = if (newBook) {
                                temporary.outputStream().use { stream ->
                                    zip.copyBounded(stream, limit).also { stream.fd.sync() }
                                }
                            } else {
                                zip.copyBounded(null, limit)
                            }
                            require(hash == book.sourceSha256)
                        } else {
                            val bytes = zip.readBounded(limit)
                            if (asset.endsWith(".cover")) validateCover(bytes)
                            if (newBook) {
                                temporary.outputStream().use { stream ->
                                    stream.write(bytes)
                                    stream.fd.sync()
                                }
                            }
                        }
                        if (newBook) {
                            // Only publish assets for IDs that have no committed database row.
                            check(temporary.renameTo(target)) { "Не удалось сохранить файл книги." }
                            published[target] = book.id
                        }
                    } finally {
                        temporary.delete()
                    }
                }
                if (newBook) {
                    val contentsStore = TextContentsStore(folder)
                    val contentsFile = contentsStore.file(book.id)
                    if (record.contents != null) {
                        contentsStore.save(book.id, record.contents)
                        published[contentsFile] = book.id
                    } else contentsStore.remove(book.id)
                    (assetNames(book) - record.assets.toSet()).forEach { orphan ->
                        val file = File(folder, orphan)
                        check(!file.exists() || file.delete())
                    }
                    dao.insertBook(book)
                    dao.insertChapters(record.chapters)
                    record.fragments.chunked(250).forEach { dao.insertFragments(it) }
                    record.position?.let { dao.putPosition(it) }
                    added++
                }
            }
        }
        return added
    }

    private fun assetLimit(book: TextBookEntity, asset: String): Int = when (asset) {
        book.sourceFileName -> EbookImportParser.MAX_SOURCE_BYTES
        "${book.id}.cover" -> EbookImportParser.MAX_COVER_BYTES
        else -> MIB
    }

    private fun validateCover(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000 &&
            bounds.outMimeType in setOf("image/png", "image/jpeg"))
    }

    private fun validate(record: ArchivedTextBook) {
        val book = record.book
        val allowed = assetNames(book)
        require(record.assets.distinct() == record.assets && record.assets.all { it in allowed })
        require(book.sourceFileName in record.assets)
        require(record.chapters.size == book.chapterCount && record.chapters.isNotEmpty())
        require(record.fragments.size == book.fragmentCount && record.fragments.isNotEmpty())
        require(record.chapters.withIndex().all { (index, chapter) ->
            chapter.bookId == book.id && chapter.ordinal == index && chapter.id == "${book.id}:c$index"
        })
        val chapters = record.chapters.associateBy { it.id }
        record.contents?.validate(record.chapters, record.fragments)
        var offset = 0
        val grouped = record.fragments.groupBy { it.chapterId }
        require(grouped.keys == chapters.keys)
        for (chapter in record.chapters) {
            require(chapter.startOffset == offset)
            for ((index, fragment) in grouped.getValue(chapter.id).withIndex()) {
                require(fragment.ordinal == index && fragment.id == "${chapter.id}:f$index")
                require(fragment.startOffset == offset && fragment.text.isNotEmpty())
                offset += fragment.text.length
                require(fragment.endOffset == offset)
            }
            require(chapter.endOffset == offset)
        }
        require(offset == book.characterCount)
        record.position?.let { position ->
            require(position.bookId == book.id && position.scrollOffsetPx >= 0 &&
                record.fragments.any { it.id == position.fragmentId })
        }
    }

    private fun assetNames(book: TextBookEntity): List<String> {
        require(book.id.matches(Regex("txt_[0-9a-f]{64}")))
        require(book.sourceFileName in listOf("${book.id}.source", "${book.id}.txt"))
        return listOf(book.sourceFileName, "${book.id}.json", "${book.id}.cover")
    }

    private suspend fun InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyBounded(output, limit)
        return output.toByteArray()
    }

    private suspend fun InputStream.copyBounded(output: OutputStream?, limit: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            require(count <= remaining) { "Запись в архиве превышает допустимый размер." }
            remaining -= count
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun ZipOutputStream.entry(name: String, writeEntry: () -> Unit) {
        putNextEntry(ZipEntry(name))
        writeEntry()
        closeEntry()
    }

    private companion object {
        const val VERSION = "abred-text-library-v1"
        const val END = "complete"
        const val MIB = 1024 * 1024
    }
}
