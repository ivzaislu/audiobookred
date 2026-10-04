package com.example.data.backup

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Crash journal for a user-data restore.
 *
 * SharedPreferences and Room cannot participate in one transaction. The journal
 * is therefore persisted before either side is changed. A process that dies with
 * PREPARED on disk rolls the requested backup forward on the next launch. Once
 * both stores are coherent, COMMITTED prevents an undeletable journal from
 * replaying an old backup over newer user changes. ABORTED serves the same role
 * after a successful in-process rollback.
 */
internal class UserDataRestoreJournal internal constructor(
    private val baseFile: File,
) {
    private val file = AtomicFile(baseFile)

    constructor(context: Context) : this(journalFile(context.applicationContext))

    data class Entry(
        @Json(name = "journal_schema") val journalSchema: String = JOURNAL_SCHEMA,
        val state: String,
        val backup: UserDataBackup,
    )

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(Entry::class.java)

    fun exists(): Boolean = journalExists(baseFile)

    fun writePrepared(backup: UserDataBackup): Boolean = write(
        Entry(state = STATE_PREPARED, backup = backup)
    )

    fun markCommitted(backup: UserDataBackup): Boolean = write(
        Entry(state = STATE_COMMITTED, backup = backup)
    )

    fun markAborted(backup: UserDataBackup): Boolean = write(
        Entry(state = STATE_ABORTED, backup = backup)
    )

    @Throws(IOException::class)
    fun read(): Entry? {
        if (!exists()) return null
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_JOURNAL_BYTES) {
                    throw IOException("Журнал восстановления слишком большой")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val entry = runCatching {
            adapter.fromJson(bytes.toString(StandardCharsets.UTF_8))
        }.getOrElse { error ->
            throw IOException("Не удалось прочитать журнал восстановления", error)
        } ?: throw IOException("Журнал восстановления пуст")

        if (entry.journalSchema != JOURNAL_SCHEMA) {
            throw IOException("Неподдерживаемый формат журнала восстановления")
        }
        if (entry.state !in VALID_STATES) {
            throw IOException("Некорректное состояние журнала восстановления")
        }
        return entry
    }

    /** Delete is best-effort; terminal journal states are safe to retain. */
    fun clear(): Boolean {
        file.delete()
        return !exists()
    }

    private fun write(entry: Entry): Boolean {
        val bytes = runCatching { adapter.toJson(entry).toByteArray(StandardCharsets.UTF_8) }
            .getOrNull()
            ?: return false
        if (bytes.size > MAX_JOURNAL_BYTES) return false

        var output: FileOutputStream? = null
        return try {
            val stream = file.startWrite()
            output = stream
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            output?.let(file::failWrite)
            false
        }
    }

    companion object {
        const val STATE_PREPARED = "prepared"
        const val STATE_COMMITTED = "committed"
        const val STATE_ABORTED = "aborted"

        private const val JOURNAL_SCHEMA = "abred.user-data-restore-journal/v1"
        private const val FILE_NAME = "user-data-restore-journal.json"
        private const val MAX_JOURNAL_BYTES = 10 * 1024 * 1024
        private val VALID_STATES = setOf(STATE_PREPARED, STATE_COMMITTED, STATE_ABORTED)

        fun exists(context: Context): Boolean =
            journalExists(journalFile(context.applicationContext))

        private fun journalFile(context: Context): File =
            File(context.noBackupFilesDir, FILE_NAME)

        private fun journalExists(baseFile: File): Boolean =
            baseFile.exists() || File("${baseFile.path}.bak").exists()
    }
}
