package com.example.data.narration

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface NarrationStorage {
    fun initialize(): List<StoredNarrationTask>
    fun write(task: StoredNarrationTask)
    fun completedAudio(key: String): NarrationAudio?
    fun part(key: String): File
    fun commit(key: String): NarrationAudio
    fun delete(key: String)
    fun trim(protectedKeys: Set<String>): Set<String>
}

/** One native call at a time. Priority orders waiting work; it never interrupts another owner's call. */
internal class NarrationQueue(
    private val storage: NarrationStorage,
    private val synthesize: suspend (NarrationSpec, File) -> Unit,
) {
    private data class Owner(val ticket: NarrationTicket, val priority: NarrationPriority)
    private class Entry(var task: StoredNarrationTask, var sequence: Long) {
        val owners = linkedMapOf<String, Owner>()
        var epoch = 0L
    }
    private data class Running(val entry: Entry, val epoch: Long, val job: Job)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val entries = linkedMapOf<String, Entry>()
    private val mutableStates = MutableStateFlow<List<NarrationTaskState>>(emptyList())
    val states = mutableStates.asStateFlow()
    private var initialized = false
    private var sequence = 0L
    private var running: Running? = null

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val work = mutex.withLock {
                        val entry = entries.values.filter { it.task.status == NarrationStatus.QUEUED && it.owners.isNotEmpty() }
                            .sortedWith(compareByDescending<Entry> { priority(it).rank }.thenBy { it.sequence }).firstOrNull()
                            ?: return@withLock null
                        try {
                            change(entry, NarrationStatus.GENERATING)
                            val epoch = entry.epoch
                            val job = scope.launch(start = CoroutineStart.LAZY) { execute(entry, epoch) }
                            running = Running(entry, epoch, job)
                            job.start()
                            job
                        } catch (error: Exception) {
                            fail(entry, error)
                            wake.trySend(Unit)
                            null
                        }
                    }
                    if (work == null) break
                    // Cancellation may be waiting for an uninterruptible native forward().
                    work.join()
                    mutex.withLock { if (running?.job === work) running = null }
                }
            }
        }
    }

    suspend fun enqueue(spec: NarrationSpec, priority: NarrationPriority): NarrationTicket {
        var registered: NarrationTicket? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    initializeLocked()
                    var entry = entries[spec.key]
                    if (entry == null) {
                        if (entries.size >= NarrationTaskStore.MAX_TASKS) {
                            val oldest = entries.values.filter { it.owners.isEmpty() && running?.entry !== it }
                                .minByOrNull { it.task.updatedAtMs }
                            checkNotNull(oldest) { "Очередь озвучки заполнена. Дождитесь завершения или отмените задачи." }
                            storage.delete(oldest.task.spec.key)
                            entries.remove(oldest.task.spec.key)
                        }
                        entry = Entry(StoredNarrationTask(spec, NarrationStatus.QUEUED, priority, System.currentTimeMillis()), sequence++)
                        // Persist before making a new task visible to the scheduler.
                        storage.write(entry.task)
                        entries[spec.key] = entry
                    }
                    val ticket = NarrationTicket(UUID.randomUUID().toString(), MutableStateFlow(state(entry)), CompletableDeferred(), ::release)
                    entry.owners[ticket.id] = Owner(ticket, priority)
                    try {
                        val cached = storage.completedAudio(spec.key)
                        when {
                            cached != null -> {
                                change(entry, NarrationStatus.READY)
                                ticket.result.complete(cached)
                            }
                            entry.task.status != NarrationStatus.GENERATING && entry.task.status != NarrationStatus.QUEUED -> { entry.sequence = sequence++; change(entry, NarrationStatus.QUEUED) }
                            else -> { entry.task = entry.task.copy(priority = priority(entry)); storage.write(entry.task); publish() }
                        }
                    } catch (error: Exception) {
                        entry.owners.remove(ticket.id)
                        throw error
                    }
                    wake.trySend(Unit)
                    ticket.also { registered = it }
                }
            }
        } catch (cancelled: CancellationException) {
            registered?.release() // Also handles prompt cancellation on the withContext return boundary.
            throw cancelled
        }
    }

    /** Recovery is lazy, never starts model inference or background work on application startup. */
    private fun initializeLocked() {
        if (initialized) return
        val restored = storage.initialize()
        restored.forEach { entries[it.spec.key] = Entry(it, sequence++) }
        initialized = true
        publish()
    }

    suspend fun listTasks(): List<NarrationTaskState> = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked(); mutableStates.value }
    }

    suspend fun retry(key: String, priority: NarrationPriority): NarrationTicket {
        val spec = withContext(Dispatchers.IO) {
            mutex.withLock { initializeLocked(); checkNotNull(entries[key]) { "Задача больше не хранится в очереди." }.task.spec }
        }
        return enqueue(spec, priority)
    }

    private fun release(id: String) {
        scope.launch {
            mutex.withLock {
                val entry = entries.values.firstOrNull { id in it.owners } ?: return@withLock
                val owner = entry.owners.remove(id) ?: return@withLock
                owner.ticket.result.cancel(CancellationException("Запрос озвучки отменён."))
                owner.ticket.mutableState.value = state(entry).copy(status = NarrationStatus.CANCELLED)
                if (entry.owners.isEmpty() && entry.task.status in setOf(NarrationStatus.QUEUED, NarrationStatus.GENERATING)) {
                    entry.epoch++ // A new request can enqueue this key while the old call is stopping.
                    running?.takeIf { it.entry === entry }?.job?.cancel()
                    entry.task = entry.task.copy(status = NarrationStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
                    runCatching { storage.write(entry.task) }
                } else if (entry.owners.isNotEmpty()) {
                    entry.task = entry.task.copy(priority = priority(entry))
                }
                trim()
                publish()
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun execute(entry: Entry, epoch: Long) {
        try {
            // A 1,000-char fragment can now contain several short RuVoice requests,
            // each with its own 85-second timeout and one restart on failure.
            // Do not abort a healthy series merely because the old 3-minute wall elapsed.
            withTimeout(15L * 60_000L) {
                synthesize(entry.task.spec, storage.part(entry.task.spec.key))
            }
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                if (epoch != entry.epoch || entry.owners.isEmpty()) return@withLock
                val audio = storage.commit(entry.task.spec.key)
                change(entry, NarrationStatus.READY)
                entry.owners.values.forEach { it.ticket.result.complete(audio) }
                trim()
            }
        } catch (timeout: TimeoutCancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (epoch == entry.epoch) fail(entry, IllegalStateException(
                        "RuVoice не завершил серию коротких фрагментов за 15 минут. " +
                            "Приостановите сохранение и повторите попытку.", timeout,
                    ))
                }
            }
        } catch (cancelled: CancellationException) {
            // Pause/cancel is not a RuVoice synthesis timeout. Releasing the last
            // owner increments the epoch, so a paused export never becomes failed.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (epoch == entry.epoch) fail(entry, IllegalStateException(
                        "Озвучка остановлена.", cancelled,
                    ))
                }
            }
        } catch (error: LinkageError) {
            mutex.withLock { if (epoch == entry.epoch) fail(entry, IllegalStateException("Не удалось запустить движок RuVoice: ${error.message}", error)) }
        } catch (error: Exception) {
            mutex.withLock { if (epoch == entry.epoch) fail(entry, error) }
        } finally {
            // This runs only after native forward() and engine.close() have returned.
            storage.part(entry.task.spec.key).delete()
        }
    }

    private fun fail(entry: Entry, error: Exception) {
        entry.task = entry.task.copy(status = NarrationStatus.FAILED, message = error.message, updatedAtMs = System.currentTimeMillis())
        runCatching { storage.write(entry.task) }
        entry.owners.values.forEach {
            it.ticket.mutableState.value = state(entry)
            it.ticket.result.completeExceptionally(error)
        }
        entry.owners.clear()
        publish()
    }

    private fun change(entry: Entry, status: NarrationStatus) {
        val next = entry.task.copy(status = status, priority = priority(entry), updatedAtMs = System.currentTimeMillis(), message = null)
        storage.write(next)
        entry.task = next
        publish()
    }

    internal suspend fun close() { scope.coroutineContext.job.cancelAndJoin() }

    private fun priority(entry: Entry): NarrationPriority = entry.owners.values.maxByOrNull { it.priority.rank }?.priority ?: entry.task.priority
    private fun state(entry: Entry) = NarrationTaskState(entry.task.spec.key, entry.task.status, priority(entry), entry.task.message)
    private fun publish() {
        entries.values.forEach { entry -> entry.owners.values.forEach { it.ticket.mutableState.value = state(entry) } }
        mutableStates.value = entries.values.sortedByDescending { it.task.updatedAtMs }.map(::state)
    }
    private fun trim() {
        // Cleanup is best effort; it must not invalidate a successful pinned result.
        runCatching {
            val removed = storage.trim(entries.values.filter { it.owners.isNotEmpty() || running?.entry === it }.map { it.task.spec.key }.toSet())
            removed.forEach { key ->
                entries[key]?.let { entry ->
                    if (entry.task.status == NarrationStatus.READY) {
                        entry.task = entry.task.copy(status = NarrationStatus.INTERRUPTED, message = "Фрагмент удалён из кеша. Повторите запрос.")
                        storage.write(entry.task)
                    }
                }
            }
            if (removed.isNotEmpty()) publish()
        }
    }
}
