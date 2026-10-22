package com.example.data.narration

import android.util.AtomicFile
import com.example.data.local.DownloadStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Sidecar stays in the manifest directory after the private WAV is published to Downloads. */
@Singleton
internal class TextAudioSyncStore @Inject constructor(private val downloads: DownloadStore) {
    suspend fun read(sourceId: String, chapterId: String, fragmentIds: List<String>, bookId: String? = null, sourceSha256: String? = null): TextAudioSyncMap? = withContext(Dispatchers.IO) {
        val book = downloads.book(sourceId) ?: return@withContext null
        val file = downloads.files(sourceId).firstOrNull { it.chapterId == chapterId && it.state == "completed" }
            ?: return@withContext null
        // Missing/old/damaged sidecars are optional: never trigger synthesis to repair one.
        runCatching {
            val sidecar = AtomicFile(File(downloads.finalFile(file).path + ".sync.json"))
            require(sidecar.baseFile.length() <= 16L * 1024 * 1024)
            val data = sidecar.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            TextAudioSyncMap.parse(data).also {
                require(it.manifestId == book.manifestId && it.chapterId == chapterId)
                if (it.bookId.isNotBlank()) {
                    require(bookId == null || it.bookId == bookId)
                    require(sourceSha256 == null || it.sourceSha256 == sourceSha256)
                }
                it.validate(file.downloadedBytes - 44L, fragmentIds)
            }
        }.getOrNull()
    }
}
