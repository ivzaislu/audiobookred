package com.example.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Accept only our generated imported-cover files; provider URLs cannot expose arbitrary private files. */
internal object LocalBookCover {
    /**
     * Media sessions are read by SystemUI outside our app UID. Never publish a
     * private file:// URI as artwork: send a small compressed preview instead.
     * The exact allowlist in uri() keeps arbitrary files inaccessible.
     */
    fun mediaArtwork(context: Context, raw: String?): ByteArray? {
        val cover = uri(context, raw)?.path?.let(::File) ?: return null
        if (!cover.isFile || cover.length() !in 1L..(8L * 1024 * 1024)) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cover.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                cover.path, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null

            try {
                val scale = minOf(1f, 96f / decoded.width, 96f / decoded.height)
                val width = maxOf(1, (decoded.width * scale).roundToInt())
                val height = maxOf(1, (decoded.height * scale).roundToInt())
                val preview = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                try {
                    Canvas(preview).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(decoded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    ByteArrayOutputStream().use { stream ->
                        if (!preview.compress(Bitmap.CompressFormat.JPEG, 70, stream)) return@runCatching null
                        stream.toByteArray().takeIf { it.size <= 12 * 1024 }
                    }
                } finally {
                    preview.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

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
