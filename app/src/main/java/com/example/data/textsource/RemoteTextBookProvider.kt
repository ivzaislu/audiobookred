package com.example.data.textsource

internal data class TextSourceLink(val id: String, val name: String, val url: String)
internal data class RemoteTextDownload(
    val code: String,
    val label: String,
    val url: String,
    val extension: String,
) {
    val canImport: Boolean get() = extension.lowercase() in setOf("fb2.zip", "fb2", "epub", "txt", "zip")
    val mimeType: String get() = when (extension) {
        "fb2.zip", "zip" -> "application/zip"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
    fun fileName(title: String, maxStemBytes: Int = 240): String {
        require(maxStemBytes in 1..240)
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        val stem = StringBuilder()
        var byteCount = 0
        var index = 0
        while (index < clean.length) {
            val point = clean.codePointAt(index)
            index += Character.charCount(point)
            if (point in 0xD800..0xDFFF) continue // Ignore unpaired surrogates; never cut an emoji in half.
            val symbol = String(Character.toChars(point))
            val size = symbol.toByteArray(Charsets.UTF_8).size
            if (stem.length + symbol.length > 120 || byteCount + size > maxStemBytes) break
            stem.append(symbol)
            byteCount += size
        }
        return stem.toString().ifBlank { "book" } + ".$extension"
    }
}
internal data class RemoteTextBook(
    val id: String,
    val title: String,
    val url: String,
    val authors: List<TextSourceLink> = emptyList(),
    val coverUrl: String? = null,
    val description: String = "",
    val series: TextSourceLink? = null,
    val seriesNumber: String? = null,
    val year: String? = null,
    val downloadUrl: String? = null,
    val formats: List<String> = emptyList(),
    val downloads: List<RemoteTextDownload> = emptyList(),
)
internal data class RemoteTextPage(val title: String, val items: List<RemoteTextBook>, val nextUrl: String?)

/** Text files have their own import path; they are never advertised as playable audio streams. */
internal interface RemoteTextBookProvider {
    val code: String
    val name: String
    val catalogUrl: String
    val importExtension: String get() = "fb2.zip"
    fun bookId(url: String): String?
    fun bookUrl(id: String): String
    fun searchUrl(query: String): String
    fun isPageUrl(url: String): Boolean
    suspend fun page(url: String): RemoteTextPage
    suspend fun book(id: String): RemoteTextBook
    suspend fun download(book: RemoteTextBook): ByteArray
    suspend fun downloadFile(book: RemoteTextBook, option: RemoteTextDownload): ByteArray
}
