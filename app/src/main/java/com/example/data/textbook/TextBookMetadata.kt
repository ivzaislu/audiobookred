package com.example.data.textbook

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

internal data class TextBookMetadata(val author: String = "", val format: String = "TXT", val coverUri: String = "")

/** Optional descriptive assets. Room remains authoritative for book/source/reading identity. */
internal class TextBookMetadataStore(private val folder: File) {
    private fun name(id: String): String { require(id.matches(Regex("txt_[0-9a-f]{64}"))); return id }
    fun read(id: String): TextBookMetadata = runCatching {
        val data = AtomicFile(File(folder, "${name(id)}.json")).openRead().use { JSONObject(it.bufferedReader().readText()) }
        val cover = File(folder, "${name(id)}.cover")
        TextBookMetadata(data.optString("author").take(1000), data.optString("format", "TXT"),
            if (cover.isFile) Uri.fromFile(cover).toString() else "")
    }.getOrDefault(TextBookMetadata())

    fun save(id: String, ebook: ImportedEbook) {
        folder.mkdirs()
        val cover = ebook.cover?.bytes?.takeIf { bytes ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 16_000_000
        }
        if (cover != null) write(AtomicFile(File(folder, "${name(id)}.cover")), cover)
        write(AtomicFile(File(folder, "${name(id)}.json")), JSONObject().put("version", 1).put("author", ebook.author.take(1000))
            .put("entry", ebook.entry.orEmpty()).put("format", ebook.format).toString().toByteArray(Charsets.UTF_8))
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    fun remove(id: String) {
        AtomicFile(File(folder, "${name(id)}.json")).delete()
        AtomicFile(File(folder, "${name(id)}.cover")).delete()
    }
}
