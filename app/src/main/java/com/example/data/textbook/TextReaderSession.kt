package com.example.data.textbook

/** A revoked generation never becomes writable again, even after a preview is committed. */
internal class TextReaderSession {
    // Prevent saved LazyList state from a dead preview being restored into a new NORMAL session.
    val identity: String = java.util.UUID.randomUUID().toString()
    enum class Mode { CLOSED, NORMAL, PREVIEW, COMMITTING }
    var generation: Long = 0L
        private set
    private var followAllowed = false
    @Volatile var mode = Mode.CLOSED
        private set

    @Synchronized fun open(preview: Boolean, allowFollowing: Boolean = true): Long {
        generation++
        followAllowed = allowFollowing
        mode = if (preview) Mode.PREVIEW else Mode.NORMAL
        return generation
    }
    @Synchronized fun close() { generation++; mode = Mode.CLOSED; followAllowed = false }
    @Synchronized fun canFollow(token: Long) = token == generation && followAllowed &&
        mode in setOf(Mode.NORMAL, Mode.PREVIEW)
    @Synchronized fun allowFollowing(token: Long) {
        if (token == generation && mode in setOf(Mode.NORMAL, Mode.PREVIEW)) followAllowed = true
    }
    @Synchronized fun canSave(token: Long) = token == generation && mode == Mode.NORMAL
    @Synchronized fun canCommit(token: Long) = token == generation && mode == Mode.COMMITTING
    @Synchronized fun beginCommit(token: Long): Boolean {
        if (token != generation || mode != Mode.PREVIEW) return false
        mode = Mode.COMMITTING
        return true
    }
    @Synchronized fun finishCommit(token: Long, success: Boolean): Long {
        if (!canCommit(token)) return generation
        if (success) { generation++; mode = Mode.NORMAL; followAllowed = false } else mode = Mode.PREVIEW
        return generation
    }
}
