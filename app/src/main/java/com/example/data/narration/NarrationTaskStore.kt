package com.example.data.narration

import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

internal data class StoredNarrationTask(
    val spec: NarrationSpec,
    val status: NarrationStatus,
    val priority: NarrationPriority,
    val updatedAtMs: Long,
    val message: String? = null,
)

/** Called on IO under the queue mutex. Journals are durable; audio is a regenerable bounded cache. */
internal class NarrationTaskStore(private val journalFolder: File, private val audioFolder: File) : NarrationStorage {
    companion object {
        const val MAX_TASKS = 64
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        private val keyPattern = Regex("[0-9a-f]{64}")
    }

    override fun initialize(): List<StoredNarrationTask> {
        check(journalFolder.isDirectory || journalFolder.mkdirs()) { "Не удалось создать журнал озвучки." }
        check(audioFolder.isDirectory || audioFolder.mkdirs()) { "Не удалось создать кеш озвучки." }
        audioFolder.listFiles()?.filter { it.name.endsWith(".part.wav") }?.forEach { it.delete() }
        trim(emptySet())
        // AtomicFile.openRead recovers a legacy .bak / ignores incomplete .new writes.
        val keys = journalFolder.listFiles().orEmpty().map { it.name.substringBefore('.') }
            .filter { keyPattern.matches(it) }.distinct()
        return keys.mapNotNull { key ->
            try {
                val bytes = AtomicFile(journal(key)).openRead().use { input ->
                    val buffer = ByteArray(128 * 1024 + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    require(count < buffer.size)
                    buffer.copyOf(count)
                }
                val data = JSONObject(bytes.toString(Charsets.UTF_8))
                require(data.getInt("version") == 1)
                val spec = NarrationSpec.create(
                    data.getString("source"),
                    data.optString("voiceName", ""),
                    data.optLong("engineVersion", 0L),
                    data.optLong("settingsRevision", 0L),
                )
                require(spec.key == key)
                val old = NarrationStatus.valueOf(data.getString("status"))
                val audio = readAudio(key, touch = false)
                val recovered = when {
                    audio != null -> NarrationStatus.READY
                    old == NarrationStatus.QUEUED || old == NarrationStatus.GENERATING || old == NarrationStatus.READY -> NarrationStatus.INTERRUPTED
                    else -> old
                }
                StoredNarrationTask(spec, recovered, NarrationPriority.valueOf(data.getString("priority")), data.getLong("updatedAtMs"),
                    if (recovered == NarrationStatus.INTERRUPTED) "Подготовка прервана. Повторите запрос, чтобы продолжить." else null)
            } catch (_: Exception) {
                AtomicFile(journal(key)).delete()
                audio(key).delete()
                null
            }
        }.sortedByDescending { it.updatedAtMs }.let { tasks ->
            tasks.drop(MAX_TASKS).forEach { delete(it.spec.key) }
            tasks.take(MAX_TASKS)
        }
    }

    override fun write(task: StoredNarrationTask) {
        val data = JSONObject().put("version", 1).put("source", task.spec.source)
            .put("status", task.status.name).put("priority", task.priority.name).put("updatedAtMs", task.updatedAtMs)
        data.put("voiceName", task.spec.voiceName).put("engineVersion", task.spec.engineVersion)
            .put("settingsRevision", task.spec.settingsRevision)
        val atomic = AtomicFile(journal(task.spec.key))
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override fun completedAudio(key: String): NarrationAudio? = readAudio(key, touch = true)

    private fun readAudio(key: String, touch: Boolean): NarrationAudio? {
        val file = audio(key)
        if (!file.isFile) return null
        return try {
            val wav = PreviewWav.read(file)
            if (touch) file.setLastModified(System.currentTimeMillis())
            NarrationAudio(file, wav, cacheHit = true)
        } catch (_: Exception) { file.delete(); null }
    }

    override fun commit(key: String): NarrationAudio {
        val temporary = part(key)
        val wav = PreviewWav.read(temporary)
        RandomAccessFile(temporary, "rw").use { it.fd.sync() }
        val target = audio(key)
        check(temporary.renameTo(target)) { "Не удалось сохранить готовый аудиофрагмент." }
        return NarrationAudio(target, wav, cacheHit = false)
    }

    override fun part(key: String): File = File(audioFolder, "$key.part.wav")
    fun audio(key: String): File = File(audioFolder, "$key.wav")
    private fun journal(key: String): File = File(journalFolder, "$key.json")
    override fun delete(key: String) { AtomicFile(journal(key)).delete(); audio(key).delete(); part(key).delete() }

    /** Never remove pinned output, waiting jobs or the native call's current .part file. */
    override fun trim(protectedKeys: Set<String>): Set<String> {
        val removed = mutableSetOf<String>()
        val files = audioFolder.listFiles().orEmpty().filter { it.name.endsWith(".wav") && !it.name.endsWith(".part.wav") }
        var bytes = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (bytes > MAX_CACHE_BYTES && file.name.removeSuffix(".wav") !in protectedKeys) {
                val size = file.length()
                if (file.delete()) { bytes -= size; removed += file.name.removeSuffix(".wav") }
            }
        }
        return removed
    }
}
