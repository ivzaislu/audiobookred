package com.example.data.textsource

import com.example.data.textbook.EbookImportParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class FantasyWorldsProvider(client: OkHttpClient) : RemoteTextBookProvider {
    override val code = "fantasy_worlds"
    override val name = "Fantasy Worlds"
    override val catalogUrl = FantasyWorldsHtmlParser.ORIGIN + "/lib/1"
    override fun bookId(url: String) = FantasyWorldsHtmlParser.bookId(url)
    override fun bookUrl(id: String) = FantasyWorldsHtmlParser.bookUrl(id)
    override fun isPageUrl(url: String) = FantasyWorldsHtmlParser.isPageUrl(url)
    override fun searchUrl(query: String) = FantasyWorldsHtmlParser.ORIGIN + "/search/?q=" + java.net.URLEncoder.encode(query.take(300), "UTF-8")
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private data class CachedBook(val value: RemoteTextBook, val savedAtNanos: Long)
    private val detailsCache = LinkedHashMap<String, CachedBook>(128, 0.75f, true)
    private val detailsSlots = Semaphore(2)
    private fun cachedBook(id: String): RemoteTextBook? = synchronized(detailsCache) {
        detailsCache[id]?.takeIf { System.nanoTime() - it.savedAtNanos < 10L * 60 * 1_000_000_000 }?.value
    }
    override suspend fun page(url: String): RemoteTextPage {
        require(FantasyWorldsHtmlParser.isPageUrl(url))
        val bytes = get(url, HTML_LIMIT)
        return withContext(Dispatchers.Default) { FantasyWorldsHtmlParser.page(bytes.toString(Charsets.UTF_8), url) }
    }
    override suspend fun book(id: String): RemoteTextBook {
        val url = FantasyWorldsHtmlParser.bookUrl(id)
        cachedBook(id)?.let { return it }
        return detailsSlots.withPermit {
            cachedBook(id)?.let { return@withPermit it }
            val bytes = get(url, HTML_LIMIT)
            val book = withContext(Dispatchers.Default) { FantasyWorldsHtmlParser.book(bytes.toString(Charsets.UTF_8), url) }
            synchronized(detailsCache) {
                detailsCache[id] = CachedBook(book, System.nanoTime())
                while (detailsCache.size > 128) detailsCache.remove(detailsCache.keys.first())
            }
            book
        }
    }
    override suspend fun download(book: RemoteTextBook): ByteArray {
        val url = book.downloadUrl ?: error("На сайте нет доступной ссылки FB2 для этой книги.")
        require(FantasyWorldsHtmlParser.isDownloadUrl(url, book.id))
        return get(url, EbookImportParser.MAX_SOURCE_BYTES, book.url).also {
            require(isBookPayload(it)) { "Вместо книги сайт вернул другую страницу. Загрузка не импортирована." }
        }
    }
    override suspend fun downloadFile(book: RemoteTextBook, option: RemoteTextDownload): ByteArray {
        require(option in book.downloads && FantasyWorldsHtmlParser.isFileDownloadUrl(option.url, book.id)) { "Формат загрузки недоступен." }
        require(bookId(book.url) == book.id)
        return get(option.url, EbookImportParser.MAX_SOURCE_BYTES, book.url).also { bytes ->
            require(isDownloadPayload(bytes, option.code)) { "Вместо файла книги сайт вернул неподдерживаемый ответ." }
        }
    }
    private suspend fun get(initialUrl: String, limit: Int, referer: String = FantasyWorldsHtmlParser.ORIGIN + "/"): ByteArray {
        var url = initialUrl
        repeat(6) {
            val safe = FantasyWorldsHtmlParser.safeUrl(url) ?: error("Недопустимый редирект Fantasy Worlds.")
            val request = Request.Builder().url(safe).header("Referer", referer)
                .header("User-Agent", "Mozilla/5.0 ABRedAndroid").build()
            val payload = consume(http.newCall(request), limit)
            if (payload.redirect == null) return payload.bytes
            url = FantasyWorldsHtmlParser.safeUrl(payload.redirect, safe) ?: error("Сайт перенаправил загрузку на неподдерживаемый адрес.")
        }
        error("Слишком много перенаправлений Fantasy Worlds.")
    }
    private data class Payload(val bytes: ByteArray = byteArrayOf(), val redirect: String? = null)
    private suspend fun consume(call: Call, limit: Int): Payload = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val result = if (response.code in setOf(301, 302, 303, 307, 308)) {
                            Payload(redirect = response.header("Location") ?: error("Пустой редирект источника."))
                        } else {
                            check(response.isSuccessful) { "Fantasy Worlds: HTTP ${response.code}." }
                            val body = response.body ?: error("Пустой ответ источника.")
                            require(body.contentLength() <= limit) { "Файл источника слишком большой." }
                            val data = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(data.size().toLong() + count <= limit) { "Файл источника слишком большой." }
                                    data.write(buffer, 0, count)
                                }
                            }
                            Payload(data.toByteArray())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
    companion object {
        private const val HTML_LIMIT = 4 * 1024 * 1024
        internal fun isDownloadPayload(bytes: ByteArray, format: String): Boolean {
            val prefix = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1).replace("\u0000", "").trimStart()
            if (bytes.isEmpty() || Regex("<(?:!doctype\\s+html|html|head|body)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) return false
            return when (format) {
                "fb2" -> isBookPayload(bytes)
                "epub" -> bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
                "mobi" -> bytes.size >= 68 && bytes.copyOfRange(60, 68).toString(Charsets.US_ASCII) == "BOOKMOBI"
                "txt" -> !bytes.take(4096).contains(0.toByte())
                else -> false
            }
        }
        internal fun isBookPayload(bytes: ByteArray): Boolean =
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                Regex("<(?:[A-Za-z0-9_-]+:)?FictionBook(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(
                    bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1).replace("\u0000", ""))
    }
}
