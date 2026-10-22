package com.example.data.narration

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NarrationQueueTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Storage(private val folder: File, private val restored: List<StoredNarrationTask> = emptyList()) : NarrationStorage {
        private val cached = mutableMapOf<String, NarrationAudio>()
        override fun initialize() = restored
        override fun write(task: StoredNarrationTask) = Unit
        override fun completedAudio(key: String) = cached[key]?.copy(cacheHit = true)
        override fun part(key: String) = File(folder, "$key.part.wav")
        override fun commit(key: String): NarrationAudio = NarrationAudio(File(folder, "$key.wav"), PreviewWav(1000, 48044, 24000), false).also { cached[key] = it }
        override fun delete(key: String) { cached.remove(key) }
        override fun trim(protectedKeys: Set<String>) = emptySet<String>()
    }

    @Test fun duplicateOwnersShareInferenceAndOneOwnerCanCancel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val queue = NarrationQueue(Storage(temporary.root)) { _, _ -> calls.incrementAndGet(); started.complete(Unit); finish.await() }
        try {
            withTimeout(5000) {
                val spec = NarrationSpec.create("Одна книга.")
                val first = queue.enqueue(spec, NarrationPriority.PREFETCH)
                started.await()
                val second = queue.enqueue(spec, NarrationPriority.PLAYBACK)
                first.release()
                first.state.first { it.status == NarrationStatus.CANCELLED }
                finish.complete(Unit)
                assertFalse(second.await().cacheHit)
                assertEquals(1, calls.get())
                val cached = queue.enqueue(spec, NarrationPriority.PREVIEW)
                assertTrue(cached.await().cacheHit)
                assertEquals(1, calls.get())
                second.release(); cached.release()
            }
        } finally { finish.complete(Unit); queue.close() }
    }

    @Test fun waitingWorkUsesPriorityAndNeverOverlaps() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val active = AtomicInteger()
        val queue = NarrationQueue(Storage(temporary.root)) { spec, _ ->
            assertEquals(1, active.incrementAndGet())
            try {
                order += spec.source
                if (spec.source == "Первая.") { started.complete(Unit); finish.await() }
            } finally { active.decrementAndGet() }
        }
        try {
            withTimeout(5000) {
                val first = queue.enqueue(NarrationSpec.create("Первая."), NarrationPriority.SAVE_CHAPTER)
                started.await()
                val low = queue.enqueue(NarrationSpec.create("Сохранение."), NarrationPriority.SAVE_CHAPTER)
                val prefetch = queue.enqueue(NarrationSpec.create("Предзагрузка."), NarrationPriority.PREFETCH)
                val high = queue.enqueue(NarrationSpec.create("Воспроизведение."), NarrationPriority.PLAYBACK)
                finish.complete(Unit)
                listOf(first, low, prefetch, high).forEach { it.await(); it.release() }
                assertEquals(
                    listOf("Первая.", "Воспроизведение.", "Сохранение.", "Предзагрузка."),
                    order,
                )
            }
        } finally { finish.complete(Unit); queue.close() }
    }

    @Test fun cancelledNativeCallFinishesBeforeRetryOfTheSameKey() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishNative = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val queue = NarrationQueue(Storage(temporary.root)) { _, _ ->
            assertEquals(1, active.incrementAndGet())
            try {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { finishNative.await() }
                    currentCoroutineContext().ensureActive()
                }
            } finally { active.decrementAndGet() }
        }
        try {
            withTimeout(5000) {
                val spec = NarrationSpec.create("Повтор.")
                val cancelled = queue.enqueue(spec, NarrationPriority.PREVIEW)
                entered.await()
                cancelled.release()
                cancelled.state.first { it.status == NarrationStatus.CANCELLED }
                val retry = queue.enqueue(spec, NarrationPriority.PREVIEW)
                finishNative.complete(Unit)
                retry.await()
                assertEquals(2, calls.get())
                assertEquals(NarrationStatus.READY, retry.state.value.status)
                retry.release()
            }
        } finally { finishNative.complete(Unit); queue.close() }
    }

    @Test fun recoveredWorkWaitsForExplicitRetryAndKeepsRuVoiceVoiceSnapshot() = runBlocking {
        val spec = NarrationSpec.create("Замок.", "xenia-ru", 10100)
        val calls = AtomicInteger()
        val restored = StoredNarrationTask(spec, NarrationStatus.INTERRUPTED, NarrationPriority.PREFETCH, 1)
        val queue = NarrationQueue(Storage(temporary.root, listOf(restored))) { input, _ ->
            calls.incrementAndGet()
            assertEquals(spec.voiceName, input.voiceName)
            assertEquals(spec.engineVersion, input.engineVersion)
        }
        try {
            withTimeout(5000) {
                assertEquals(NarrationStatus.INTERRUPTED, queue.listTasks().single().status)
                assertEquals(0, calls.get())
                val ticket = queue.retry(spec.key, NarrationPriority.PREVIEW)
                ticket.await(); ticket.release()
                assertEquals(1, calls.get())
                assertNotEquals(spec.key, NarrationSpec.create("Замок.", "aidar-ru", 10100).key)
            }
        } finally { queue.close() }
    }
}
