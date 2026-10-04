package com.example.data.player

import android.content.Context
import android.content.SharedPreferences
import com.example.data.backup.UserDataRestoreGate

internal fun sanitizePlaybackProgressPercent(value: Double): Double = value
    .takeIf { it.isFinite() }
    ?.coerceIn(0.0, 100.0)
    ?: 0.0

/**
 * Persistent source-aware playback checkpoints.
 *
 * A book can contain several independent audio sources whose chapter IDs and
 * timing do not match. Checkpoints therefore belong to (book, source), not only
 * to book. The on-disk `dirty` flag is retained for upgrade compatibility; in
 * standalone mode it means that the fast SharedPreferences checkpoint should
 * take precedence over the durable Room mirror when playback is prepared.
 */
class PlaybackResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    data class Snapshot(
        val bookId: String,
        val sourceCode: String,
        val chapterId: String?,
        val chapterIndex: Int,
        val positionMs: Long,
        val speed: Float,
        val progressPercent: Double,
        val savedAtMs: Long,
        val dirty: Boolean
    )

    @Synchronized
    fun save(
        bookId: String,
        sourceCode: String,
        chapterId: String?,
        chapterIndex: Int,
        positionMs: Long,
        speed: Float,
        progressPercent: Double? = null,
        allowZero: Boolean = false,
    ) {
        if (!UserDataRestoreGate.playbackWritesAllowed()) return
        write(
            bookId = bookId,
            sourceCode = sourceCode,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            speed = speed,
            dirty = true,
            synchronous = false,
            allowZero = allowZero,
            progressPercent = progressPercent,
        )
    }

    @Synchronized
    fun saveImmediate(
        bookId: String,
        sourceCode: String,
        chapterId: String?,
        chapterIndex: Int,
        positionMs: Long,
        speed: Float,
        progressPercent: Double? = null,
        allowZero: Boolean = false,
    ) {
        if (!UserDataRestoreGate.playbackWritesAllowed()) return
        write(
            bookId = bookId,
            sourceCode = sourceCode,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            speed = speed,
            dirty = true,
            synchronous = true,
            allowZero = allowZero,
            progressPercent = progressPercent,
        )
    }

    /**
     * Mirror a durable Room checkpoint into the fast source-aware resume store
     * without replacing a foreground checkpoint that is marked as preferred.
     */
    @Synchronized
    fun cachePersistedCheckpoint(
        bookId: String,
        sourceCode: String,
        chapterId: String?,
        chapterIndex: Int,
        positionMs: Long,
        speed: Float,
    ) {
        val existing = get(bookId, sourceCode)
        if (existing?.dirty == true) return
        write(
            bookId = bookId,
            sourceCode = sourceCode,
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            speed = speed,
            dirty = false,
            synchronous = false,
            allowZero = true,
            progressPercent = existing?.progressPercent,
        )
    }

    private fun write(
        bookId: String,
        sourceCode: String,
        chapterId: String?,
        chapterIndex: Int,
        positionMs: Long,
        speed: Float,
        dirty: Boolean,
        synchronous: Boolean,
        allowZero: Boolean = false,
        progressPercent: Double? = null,
    ) {
        if (bookId.isBlank() || (!allowZero && positionMs <= 0L)) return
        val source = normalizeSource(sourceCode)
        val token = token(bookId, source)
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty().toMutableSet().apply { add(token) }
        val editor = prefs.edit()
            .putStringSet(KEY_TOKENS, tokens)
            .putString(key(token, FIELD_BOOK_ID), bookId)
            .putString(key(token, FIELD_SOURCE), source)
            .putString(key(token, FIELD_CHAPTER_ID), chapterId)
            .putInt(key(token, FIELD_CHAPTER_INDEX), chapterIndex.coerceAtLeast(0))
            .putLong(key(token, FIELD_POSITION_MS), positionMs.coerceAtLeast(0L))
            .putFloat(key(token, FIELD_SPEED), sanitizePlaybackSpeed(speed))
            .putLong(key(token, FIELD_SAVED_AT_MS), System.currentTimeMillis())
            .putBoolean(key(token, FIELD_DIRTY), dirty)
        progressPercent?.takeIf { it.isFinite() }?.let {
            editor.putFloat(key(token, FIELD_PROGRESS_PERCENT), sanitizePlaybackProgressPercent(it).toFloat())
        }
        if (synchronous) editor.commit() else editor.apply()
    }

    fun get(bookId: String, sourceCode: String? = null): Snapshot? {
        if (bookId.isBlank()) return null
        val source = sourceCode?.takeIf { it.isNotBlank() }
        if (source != null) {
            readToken(token(bookId, normalizeSource(source)))?.let { return it }

            // In the standalone app the live book id itself carries the provider
            // identity (`uknig:...`, `audioboo:...`, etc.). A fresh detail DTO can
            // temporarily have missing/stale source metadata, but that must never
            // make an existing checkpoint unreachable and restart the book at 0.
            if (standalonePlaybackSourceFromBookId(bookId) != null) {
                snapshotsForBook(bookId).maxByOrNull { it.savedAtMs }?.let { return it }
            }

            // Backward compatibility with pre-0.3.0 single-book snapshot. The
            // caller still validates chapter_id against the selected source.
            readLegacy(bookId)?.let { return it }
            return null
        }
        return snapshotsForBook(bookId).maxByOrNull { it.savedAtMs } ?: readLegacy(bookId)
    }

    private fun snapshotsForBook(bookId: String): List<Snapshot> = allSnapshots().filter { it.bookId == bookId }

    /** Most recently persisted playback checkpoint across all books/sources. */
    fun latestSnapshot(): Snapshot? {
        val modern = allSnapshots().maxByOrNull { it.savedAtMs }
        val legacy = prefs.getString(LEGACY_KEY_BOOK_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::readLegacy)
        return listOfNotNull(modern, legacy).maxByOrNull { it.savedAtMs }
    }

    /** One synchronized image for backup export, including a surviving legacy checkpoint. */
    @Synchronized
    internal fun backupSnapshots(): List<Snapshot> {
        val legacy = prefs.getString(LEGACY_KEY_BOOK_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::readLegacy)
        return (allSnapshots() + listOfNotNull(legacy))
            .sortedByDescending(Snapshot::savedAtMs)
            .distinctBy { snapshot -> token(snapshot.bookId, normalizeSource(snapshot.sourceCode)) }
    }

    @Synchronized
    fun updateProgressPercent(bookId: String, sourceCode: String, progressPercent: Double) {
        if (!UserDataRestoreGate.playbackWritesAllowed()) return
        if (bookId.isBlank() || !progressPercent.isFinite()) return
        val token = token(bookId, normalizeSource(sourceCode))
        if (readToken(token) == null) return
        prefs.edit()
            .putFloat(key(token, FIELD_PROGRESS_PERCENT), sanitizePlaybackProgressPercent(progressPercent).toFloat())
            .apply()
    }

    /** Exact SharedPreferences image used only to roll back a failed backup restore. */
    @Synchronized
    internal fun rawSnapshot(): Map<String, Any?> = prefs.all.mapValues { (_, value) ->
        if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
    }

    /** Restore an exact pre-import SharedPreferences image synchronously. */
    @Synchronized
    internal fun restoreRawSnapshot(values: Map<String, Any?>): Boolean {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        return editor.commit()
    }

    /**
     * Replace every modern checkpoint in one synchronous SharedPreferences
     * commit. The supplied timestamps are preserved so Continue ordering survives
     * backup/restore instead of being rewritten to import time.
     */
    @Synchronized
    internal fun replaceForRestore(values: List<Snapshot>): Boolean {
        val cleaned = values
            .asSequence()
            .filter { it.bookId.isNotBlank() }
            .groupBy { token(it.bookId, normalizeSource(it.sourceCode)) }
            .values
            .mapNotNull { group -> group.maxByOrNull(Snapshot::savedAtMs) }
            .sortedBy(Snapshot::savedAtMs)

        val editor = prefs.edit().clear()
        val tokens = linkedSetOf<String>()
        val fallbackBase = System.currentTimeMillis()
        cleaned.forEachIndexed { index, snapshot ->
            val source = normalizeSource(snapshot.sourceCode)
            val token = token(snapshot.bookId, source)
            tokens += token
            editor
                .putString(key(token, FIELD_BOOK_ID), snapshot.bookId)
                .putString(key(token, FIELD_SOURCE), source)
                .putString(key(token, FIELD_CHAPTER_ID), snapshot.chapterId)
                .putInt(key(token, FIELD_CHAPTER_INDEX), snapshot.chapterIndex.coerceAtLeast(0))
                .putLong(key(token, FIELD_POSITION_MS), snapshot.positionMs.coerceAtLeast(0L))
                .putFloat(key(token, FIELD_SPEED), sanitizePlaybackSpeed(snapshot.speed))
                .putFloat(
                    key(token, FIELD_PROGRESS_PERCENT),
                    sanitizePlaybackProgressPercent(snapshot.progressPercent).toFloat(),
                )
                .putLong(
                    key(token, FIELD_SAVED_AT_MS),
                    snapshot.savedAtMs.takeIf { it > 0L } ?: fallbackBase + index,
                )
                .putBoolean(key(token, FIELD_DIRTY), snapshot.dirty)
        }
        editor.putStringSet(KEY_TOKENS, tokens)
        return editor.commit()
    }

    @Synchronized
    fun clear(bookId: String, sourceCode: String? = null) {
        clearInternal(bookId, sourceCode, commit = false)
    }

    /**
     * Synchronous variant for one-time destructive maintenance. The caller may
     * persist its durable completion marker only after this returns true.
     */
    @Synchronized
    internal fun clearCommitted(bookId: String, sourceCode: String? = null): Boolean =
        clearInternal(bookId, sourceCode, commit = true)

    private fun clearInternal(
        bookId: String,
        sourceCode: String?,
        commit: Boolean,
    ): Boolean {
        if (bookId.isBlank()) return true
        val targets = if (sourceCode.isNullOrBlank()) {
            allSnapshots().filter { it.bookId == bookId }
        } else {
            listOfNotNull(readToken(token(bookId, normalizeSource(sourceCode))))
        }
        if (targets.isEmpty()) return true
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty().toMutableSet()
        val editor = prefs.edit()
        targets.forEach { snapshot ->
            val token = token(snapshot.bookId, normalizeSource(snapshot.sourceCode))
            tokens.remove(token)
            ALL_FIELDS.forEach { field -> editor.remove(key(token, field)) }
        }
        editor.putStringSet(KEY_TOKENS, tokens)
        return if (commit) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun allSnapshots(): List<Snapshot> = prefs.getStringSet(KEY_TOKENS, emptySet())
        .orEmpty()
        .mapNotNull(::readToken)

    private fun readToken(token: String): Snapshot? {
        val bookId = prefs.getString(key(token, FIELD_BOOK_ID), null)?.takeIf { it.isNotBlank() } ?: return null
        val position = prefs.getLong(key(token, FIELD_POSITION_MS), 0L)
        if (position < 0L) return null
        return Snapshot(
            bookId = bookId,
            sourceCode = prefs.getString(key(token, FIELD_SOURCE), SOURCE_UNKNOWN).orEmpty(),
            chapterId = prefs.getString(key(token, FIELD_CHAPTER_ID), null),
            chapterIndex = prefs.getInt(key(token, FIELD_CHAPTER_INDEX), 0).coerceAtLeast(0),
            positionMs = position,
            speed = sanitizePlaybackSpeed(prefs.getFloat(key(token, FIELD_SPEED), 1f)),
            progressPercent = sanitizePlaybackProgressPercent(
                prefs.getFloat(key(token, FIELD_PROGRESS_PERCENT), 0f).toDouble()
            ),
            savedAtMs = prefs.getLong(key(token, FIELD_SAVED_AT_MS), 0L),
            dirty = prefs.getBoolean(key(token, FIELD_DIRTY), false),
        )
    }

    private fun readLegacy(bookId: String): Snapshot? {
        if (prefs.getString(LEGACY_KEY_BOOK_ID, null) != bookId) return null
        val position = prefs.getLong(LEGACY_KEY_POSITION_MS, 0L)
        if (position <= 0L) return null
        return Snapshot(
            bookId = bookId,
            sourceCode = SOURCE_UNKNOWN,
            chapterId = prefs.getString(LEGACY_KEY_CHAPTER_ID, null),
            chapterIndex = prefs.getInt(LEGACY_KEY_CHAPTER_INDEX, 0).coerceAtLeast(0),
            positionMs = position,
            speed = sanitizePlaybackSpeed(prefs.getFloat(LEGACY_KEY_SPEED, 1f)),
            progressPercent = 0.0,
            savedAtMs = prefs.getLong(LEGACY_KEY_SAVED_AT_MS, 0L),
            dirty = true,
        )
    }

    private fun normalizeSource(sourceCode: String): String = sourceCode.trim().lowercase().ifBlank { SOURCE_UNKNOWN }
    private fun token(bookId: String, sourceCode: String): String = "$bookId::$sourceCode"
    private fun key(token: String, field: String): String = "snapshot:$token:$field"

    private companion object {
        const val PREFS_NAME = "audiobookred_playback_resume"
        const val KEY_TOKENS = "snapshot_tokens_v2"
        const val SOURCE_UNKNOWN = "unknown"

        const val FIELD_BOOK_ID = "book_id"
        const val FIELD_SOURCE = "source"
        const val FIELD_CHAPTER_ID = "chapter_id"
        const val FIELD_CHAPTER_INDEX = "chapter_index"
        const val FIELD_POSITION_MS = "position_ms"
        const val FIELD_SPEED = "speed"
        const val FIELD_PROGRESS_PERCENT = "progress_percent"
        const val FIELD_SAVED_AT_MS = "saved_at_ms"
        // Keep this exact key for snapshots written by backend-era APKs.
        const val FIELD_DIRTY = "dirty"
        val ALL_FIELDS = listOf(
            FIELD_BOOK_ID,
            FIELD_SOURCE,
            FIELD_CHAPTER_ID,
            FIELD_CHAPTER_INDEX,
            FIELD_POSITION_MS,
            FIELD_SPEED,
            FIELD_PROGRESS_PERCENT,
            FIELD_SAVED_AT_MS,
            FIELD_DIRTY,
        )

        // Keys used by 0.1.x/0.2.0. Keep reading them for migration safety.
        const val LEGACY_KEY_BOOK_ID = "book_id"
        const val LEGACY_KEY_CHAPTER_ID = "chapter_id"
        const val LEGACY_KEY_CHAPTER_INDEX = "chapter_index"
        const val LEGACY_KEY_POSITION_MS = "position_ms"
        const val LEGACY_KEY_SPEED = "speed"
        const val LEGACY_KEY_SAVED_AT_MS = "saved_at_ms"
    }
}
