package com.example.data.textbook

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class TextContentsStore(private val folder: File) {
    fun file(bookId: String): File {
        require(bookId.matches(Regex("txt_[0-9a-f]{64}")))
        return File(folder, "$bookId.contents.json")
    }

    fun read(bookId: String): TextContentsEdits {
        // Legacy/non-file-backed readers may have no managed sidecar identity.
        // They remain readable; never construct a filesystem path from such IDs.
        if (!bookId.matches(Regex("txt_[0-9a-f]{64}"))) return TextContentsEdits()
        val atomic = AtomicFile(file(bookId))
        val bytes = try { atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } } catch (_: java.io.FileNotFoundException) { return TextContentsEdits() }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val names = json.getJSONObject("chapterTitles")
        val titles = names.keys().asSequence().associateWith { names.getString(it) }
        val markers = json.getJSONArray("markers")
        return TextContentsEdits(titles, (0 until markers.length()).map {
            val marker = markers.getJSONObject(it)
            TextContentsMarker(marker.getString("fragmentId"), marker.getInt("offset"), marker.getString("title"))
        })
    }

    fun save(bookId: String, edits: TextContentsEdits) {
        check(folder.isDirectory || folder.mkdirs())
        val json = JSONObject().put("version", 1).put("chapterTitles", JSONObject(edits.chapterTitles))
            .put("markers", JSONArray().also { array -> edits.markers.forEach {
                array.put(JSONObject().put("fragmentId", it.fragmentId).put("offset", it.offset).put("title", it.title))
            } })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024)
        val file = AtomicFile(file(bookId))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    fun remove(bookId: String) = AtomicFile(file(bookId)).delete()
}
