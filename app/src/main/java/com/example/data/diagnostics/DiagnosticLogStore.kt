package com.example.data.diagnostics

import java.io.File

/** Bounded two-segment local journal. All I/O is called on IO, except the fatal crash checkpoint. */
internal class DiagnosticLogStore(
    private val directory: File,
    private val segmentLimit: Int = 256 * 1024,
) {
    private val active = File(directory, "events.log")
    private val previous = File(directory, "events.previous.log")
    val crashFile = File(directory, "last-crash.txt")

    init {
        require(segmentLimit > 0)
        check(directory.isDirectory || directory.mkdirs())
    }

    @Synchronized
    fun append(timeMs: Long, event: String) {
        // ASCII event schema is bounded independently of source input sizes.
        val bytes = "$timeMs $event\n".toByteArray(Charsets.UTF_8)
        if (bytes.size > segmentLimit || bytes.size > 2048) return
        if (active.length() + bytes.size > segmentLimit) {
            check(!previous.exists() || previous.delete())
            check(!active.exists() || active.renameTo(previous))
        }
        active.appendBytes(bytes)
    }

    @Synchronized
    fun logBytes(): Long = active.length() + previous.length()

    @Synchronized
    fun logs(): String = buildString {
        // Limit even if an older/future version left an unexpected file behind.
        for (file in listOf(previous, active)) {
            if (file.isFile) append(file.inputStream().use { input ->
                val buffer = ByteArray(segmentLimit)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                String(buffer, 0, count, Charsets.UTF_8)
            })
        }
    }

    @Synchronized
    fun clear() {
        for (file in listOf(active, previous, crashFile, File(directory, "last-crash.txt.bak"), File(directory, "last-crash.txt.new"))) {
            check(!file.exists() || file.delete())
        }
    }
}
