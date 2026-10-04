package com.example.data.backup

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local barrier used while a user-data backup is being restored.
 *
 * The high bits hold a monotonically increasing restore epoch. The low bits
 * distinguish a blocked gate from an actively mutating restore. Fresh playback
 * preparation may reopen writes only after the restore has reached a coherent
 * Room + preferences state; it can never reopen writes while restore IO is active.
 */
internal object UserDataRestoreGate {
    private const val BLOCKED_BIT = 1L
    private const val RESTORE_ACTIVE_BIT = 1L shl 1
    private const val STATE_BITS = 2

    private val playbackState = AtomicLong(0L)
    private val preparedTargets = ConcurrentHashMap<String, Long>()

    /**
     * Starts a user-requested restore only when no restore IO is already active.
     * Unlike [blockPlaybackWrites], this is deliberately non-idempotent: a second
     * import must not share the first import's rollback image, journal or epoch.
     */
    fun tryBeginImportRestore(): Boolean {
        while (true) {
            val current = playbackState.get()
            if (restoreActive(current)) return false
            val next = encode(
                epoch = epoch(current) + 1L,
                blocked = true,
                restoreActive = true,
            )
            if (playbackState.compareAndSet(current, next)) {
                preparedTargets.clear()
                return true
            }
        }
    }

    /**
     * Starts (or preserves) an active restore epoch for crash recovery. Repeated
     * calls while recovery is already active are intentionally idempotent because
     * application startup may fail closed before constructing the importer.
     */
    fun blockPlaybackWrites() {
        while (true) {
            val current = playbackState.get()
            if (restoreActive(current)) return
            val next = encode(
                epoch = epoch(current) + 1L,
                blocked = true,
                restoreActive = true,
            )
            if (playbackState.compareAndSet(current, next)) {
                preparedTargets.clear()
                return
            }
        }
    }

    /**
     * Marks restore IO complete while keeping writes blocked. Advancing the epoch
     * here invalidates every target whose preparation overlapped active restore IO.
     * Only a target prepared from the coherent restored state may reopen writes.
     */
    fun finishRestoreAwaitingFreshPrepare() {
        while (true) {
            val current = playbackState.get()
            if (!restoreActive(current)) return
            val next = encode(
                epoch = epoch(current) + 1L,
                blocked = true,
                restoreActive = false,
            )
            if (playbackState.compareAndSet(current, next)) {
                preparedTargets.clear()
                return
            }
        }
    }

    /**
     * Used after rollback/terminal recovery. Advancing the epoch here invalidates
     * any playback target that may have been prepared against transient restore
     * state before the rollback completed.
     */
    fun allowPlaybackWritesAfterFreshPrepare() {
        while (true) {
            val current = playbackState.get()
            if (!blocked(current) && !restoreActive(current)) return
            val next = encode(
                epoch = epoch(current) + 1L,
                blocked = false,
                restoreActive = false,
            )
            if (playbackState.compareAndSet(current, next)) {
                preparedTargets.clear()
                return
            }
        }
    }

    /**
     * Re-opens writes only for a playback target prepared after restore IO has
     * completed in the current epoch. Active restore IO can never be unlocked by
     * playback preparation.
     */
    fun allowPlaybackWritesAfterFreshPrepare(expectedEpoch: Long): Boolean {
        while (true) {
            val current = playbackState.get()
            if (epoch(current) != expectedEpoch || restoreActive(current)) return false
            if (!blocked(current)) return true
            val next = encode(
                epoch = expectedEpoch,
                blocked = false,
                restoreActive = false,
            )
            if (playbackState.compareAndSet(current, next)) return true
        }
    }

    fun playbackWritesAllowed(): Boolean {
        val current = playbackState.get()
        return !blocked(current) && !restoreActive(current)
    }

    fun currentPlaybackWriteEpoch(): Long = epoch(playbackState.get())

    /**
     * Captures an epoch only while writes are currently open. If a restore starts
     * immediately afterwards, [playbackWriteEpochAllowed] makes this token stale.
     */
    fun capturePlaybackWriteEpoch(): Long? {
        val current = playbackState.get()
        return epoch(current).takeIf { !blocked(current) && !restoreActive(current) }
    }

    /** A queued checkpoint from an older epoch can never become valid again. */
    fun playbackWriteEpochAllowed(expectedEpoch: Long?): Boolean {
        if (expectedEpoch == null) return false
        return playbackState.get() == encode(
            epoch = expectedEpoch,
            blocked = false,
            restoreActive = false,
        )
    }

    /**
     * Check used before installing an asynchronously prepared target. Preparation
     * is rejected while restore IO is active even when the epoch itself matches.
     */
    fun playbackWriteEpochCurrent(expectedEpoch: Long?): Boolean {
        if (expectedEpoch == null) return false
        val current = playbackState.get()
        return epoch(current) == expectedEpoch && !restoreActive(current)
    }

    /**
     * Records a direct player target in the current epoch. A target recorded while
     * restore IO is active cannot unlock writes, and completion of that restore
     * advances the epoch before fresh playback can be installed.
     */
    fun registerPreparedPlaybackTarget(bookId: String, sourceCode: String): Long {
        val key = targetKey(bookId, sourceCode)
        while (true) {
            val before = playbackState.get()
            val currentEpoch = epoch(before)
            preparedTargets[key] = currentEpoch
            if (playbackState.get() == before) return currentEpoch
            preparedTargets.remove(key, currentEpoch)
        }
    }

    /**
     * Records a target only when preparation began in [expectedEpoch], that epoch
     * is still current, and restore IO is no longer active. The second validation
     * closes the race between the initial check and publishing the registration.
     */
    fun registerPreparedPlaybackTarget(
        bookId: String,
        sourceCode: String,
        expectedEpoch: Long,
    ): Long? {
        if (!playbackWriteEpochCurrent(expectedEpoch)) return null
        val key = targetKey(bookId, sourceCode)
        preparedTargets[key] = expectedEpoch
        if (!playbackWriteEpochCurrent(expectedEpoch)) {
            preparedTargets.remove(key, expectedEpoch)
            return null
        }
        return expectedEpoch
    }

    fun preparedPlaybackTargetEpoch(bookId: String, sourceCode: String): Long? {
        val key = targetKey(bookId, sourceCode)
        val targetEpoch = preparedTargets[key] ?: return null
        return targetEpoch.takeIf(::playbackWriteEpochCurrent)
    }

    private fun targetKey(bookId: String, sourceCode: String): String =
        "${bookId.trim()}\u0000${sourceCode.trim().lowercase()}"

    private fun epoch(state: Long): Long = state ushr STATE_BITS
    private fun blocked(state: Long): Boolean = state and BLOCKED_BIT != 0L
    private fun restoreActive(state: Long): Boolean = state and RESTORE_ACTIVE_BIT != 0L

    private fun encode(epoch: Long, blocked: Boolean, restoreActive: Boolean): Long =
        (epoch shl STATE_BITS) or
            (if (blocked) BLOCKED_BIT else 0L) or
            (if (restoreActive) RESTORE_ACTIVE_BIT else 0L)
}
