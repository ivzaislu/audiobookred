package com.example.data.image

import android.content.Context
import android.net.Uri
import java.io.File

/** Accept only our generated imported-cover files; provider URLs cannot expose arbitrary private files. */
internal object LocalBookCover {
    fun uri(context: Context, raw: String?): Uri? = runCatching {
        val uri = Uri.parse(raw ?: return null)
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) return null
        val file = File(uri.path ?: return null).canonicalFile
        if (!file.name.matches(Regex("txt_[0-9a-f]{64}\\.cover"))) return null
        val parents = listOf("text-library", "text-audio-exports").map { File(context.filesDir, it).canonicalFile }
        if (file.parentFile !in parents) return null
        Uri.fromFile(file)
    }.getOrNull()
}
