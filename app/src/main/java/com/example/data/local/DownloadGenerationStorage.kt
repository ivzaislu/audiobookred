package com.example.data.local

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns process-wide download generation liveness and obsolete-generation cleanup.
 *
 * The lifecycle mutex registry is intentionally process-wide and shared by every
 * DownloadStore/DownloadGenerationStorage instance. prepare/delete/publication in
 * DownloadStore obtain this exact mutex through [lifecycleMutex], so GC cannot race
 * those lifecycle transitions.
 */
internal class DownloadGenerationStorage(
    private val dao: DownloadDao,
    private val fileLayout: DownloadFileLayout,
) {
    fun lifecycleMutex(bookSourceId: String): Mutex =
        DownloadGenerationLifecycle.lifecycleMutex(bookSourceId)

    fun markActive(bookSourceId: String, manifestId: String) {
        DownloadGenerationLifecycle.activeGenerations.markActive(bookSourceId, manifestId)
    }

    fun markInactive(bookSourceId: String, manifestId: String) {
        DownloadGenerationLifecycle.activeGenerations.markInactive(bookSourceId, manifestId)
    }

    suspend fun cleanupObsoleteManifestDirectories(bookSourceId: String) {
        lifecycleMutex(bookSourceId).withLock {
            // Keep current-manifest lookup and physical deletion atomic with
            // prepare/delete/publication for this book.
            val currentManifestId = dao.book(bookSourceId)
                ?.takeIf { it.deletedAtMs == null && it.state != "purged" }
                ?.manifestId
            val root = fileLayout.sourceDirectory(bookSourceId)
            val directories = root.listFiles()?.filter(File::isDirectory).orEmpty()
            if (directories.isEmpty()) return@withLock

            // Hold the registry monitor through candidate selection + deletion.
            // A worker that is already active stays protected until markInactive.
            DownloadGenerationLifecycle.activeGenerations.withActiveManifestIds(bookSourceId) { activeManifestIds ->
                val obsoleteNames = obsoleteDownloadGenerationNames(
                    childDirectoryNames = directories.map(File::getName),
                    currentManifestId = currentManifestId,
                    activeManifestIds = activeManifestIds,
                )
                directories
                    .filter { it.name in obsoleteNames }
                    .forEach { directory ->
                        runCatching { directory.deleteRecursively() }
                            .onSuccess { deleted ->
                                if (!deleted && directory.exists()) {
                                    Log.w(
                                        TAG,
                                        "Obsolete download generation was not deleted: ${directory.absolutePath}",
                                    )
                                }
                            }
                            .onFailure { error ->
                                Log.w(
                                    TAG,
                                    "Failed to delete obsolete download generation: ${directory.absolutePath}",
                                    error,
                                )
                            }
                    }
            }
        }
    }

    private companion object {
        const val TAG = "DownloadGenerationStorage"
    }
}

/** Single process-wide owner of lifecycle locks and active-generation liveness. */
private object DownloadGenerationLifecycle {
    private val lifecycleLocks = ConcurrentHashMap<String, Mutex>()
    val activeGenerations = ActiveDownloadGenerationRegistry()

    fun lifecycleMutex(bookSourceId: String): Mutex =
        lifecycleLocks.getOrPut(bookSourceId) { Mutex() }
}

/**
 * Reference-counted registry for download generations that still have a live worker.
 *
 * Registration and GC selection share the same monitor. WorkManager REPLACE is
 * asynchronous, so workers may overlap; cleanup must not observe a generation as
 * inactive between another worker's registration and its file access.
 */
internal class ActiveDownloadGenerationRegistry {
    private val activeCounts = mutableMapOf<String, MutableMap<String, Int>>()

    @Synchronized
    fun markActive(bookSourceId: String, manifestId: String) {
        if (bookSourceId.isBlank() || manifestId.isBlank()) return
        val counts = activeCounts.getOrPut(bookSourceId) { mutableMapOf() }
        val current = counts[manifestId] ?: 0
        counts[manifestId] = if (current == Int.MAX_VALUE) current else current + 1
    }

    @Synchronized
    fun markInactive(bookSourceId: String, manifestId: String) {
        if (bookSourceId.isBlank() || manifestId.isBlank()) return
        val counts = activeCounts[bookSourceId] ?: return
        val current = counts[manifestId] ?: return
        if (current <= 1) counts.remove(manifestId) else counts[manifestId] = current - 1
        if (counts.isEmpty()) activeCounts.remove(bookSourceId)
    }

    @Synchronized
    fun activeManifestIds(bookSourceId: String): Set<String> =
        activeCounts[bookSourceId]?.keys?.toSet().orEmpty()

    fun <R> withActiveManifestIds(bookSourceId: String, block: (Set<String>) -> R): R =
        synchronized(this) {
            block(activeCounts[bookSourceId]?.keys?.toSet().orEmpty())
        }
}

internal fun obsoleteDownloadGenerationNames(
    childDirectoryNames: List<String>,
    currentManifestId: String?,
    activeManifestIds: Set<String>,
): Set<String> {
    val protectedNames = buildSet {
        currentManifestId
            ?.takeIf(String::isNotBlank)
            ?.let { add(stableDownloadSegment(it)) }
        activeManifestIds
            .asSequence()
            .filter(String::isNotBlank)
            .map(::stableDownloadSegment)
            .forEach(::add)
    }
    return childDirectoryNames
        .asSequence()
        .filter(String::isNotBlank)
        .filterNot(protectedNames::contains)
        .toCollection(linkedSetOf())
}
