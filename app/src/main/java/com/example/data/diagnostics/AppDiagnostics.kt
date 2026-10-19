package com.example.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.example.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.exitProcess

internal data class DiagnosticStatus(
    val available: Boolean = false,
    val recording: Boolean = false,
    val logBytes: Long = 0,
    val storedBytes: Long = 0,
    val crashSavedAt: Long? = null,
    val storageError: Boolean = false,
)

/** On-device structured diagnostics. Never reads logcat or sends a network request. */
internal object AppDiagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DiagnosticSession(SystemClock::elapsedRealtime)
    private val mutex = Mutex()
    private val pending = java.util.ArrayDeque<Entry>()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(DiagnosticStatus())
    val state = _state.asStateFlow()
    @Volatile private var store: DiagnosticLogStore? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var generation: Long = 0
    private val handlingCrash = AtomicBoolean(false)

    val isRecording: Boolean get() = store != null && session.isActive()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            store = DiagnosticLogStore(File(context.noBackupFilesDir, "diagnostics"))
        }.onFailure { _state.value = DiagnosticStatus(storageError = true) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (handlingCrash.compareAndSet(false, true)) saveCrash(error)
            } catch (_: Throwable) {
                // A failed report must never replace Android's original crash handling.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
        scope.launch {
            for (signal in wakeWriter) {
                mutex.withLock {
                    runCatching { drainPending() }.onFailure { storageFailed() }
                }
            }
        }
        scope.launch {
            refresh()
            while (isActive) {
                delay(10_000)
                mutex.withLock {
                    if (_state.value.recording && !session.isActive()) {
                        session.stop()
                        runCatching { append("time_limit") }.onFailure { storageFailed() }
                    }
                    updateStatus()
                }
            }
        }
    }

    /** Called on any thread; bounded queue keeps filesystem work off player/UI threads. */
    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        if (!isRecording) return
        val safe = DiagnosticPrivacy.event(event, fields) ?: return
        synchronized(pending) {
            if (!isRecording) return
            if (pending.size >= 128) pending.removeFirst()
            pending.addLast(Entry(generation, System.currentTimeMillis(), safe))
        }
        wakeWriter.trySend(Unit)
    }

    suspend fun setRecording(enabled: Boolean) = mutex.withLock {
        checkNotNull(store) { "Diagnostics unavailable" }
        try {
            drainPending()
            generation++
            if (enabled) {
                session.start()
                append("recording_started")
            } else {
                session.stop()
                append("recording_stopped")
            }
            _state.value = _state.value.copy(storageError = false)
            updateStatus()
        } catch (error: Exception) {
            storageFailed()
            throw error
        }
    }

    suspend fun refresh() = mutex.withLock { updateStatus() }

    suspend fun clear() = mutex.withLock {
        synchronized(pending) {
            generation++ // Discard queued entries from before the clear.
            pending.clear()
        }
        store?.clear()
        reportDirectory().listFiles()?.forEach { check(it.delete() || !it.exists()) }
        _state.value = _state.value.copy(storageError = false)
        updateStatus()
    }

    /** Returns one explicitly shareable file. Only the report directory is exposed by FileProvider. */
    suspend fun exportReport(crashOnly: Boolean): File = mutex.withLock {
        val files = checkNotNull(store)
        drainPending()
        val context = checkNotNull(appContext)
        val crash = readCrash(files)
        if (crashOnly) check(crash != null) { "No saved crash" }
        val directory = reportDirectory()
        check(directory.isDirectory || directory.mkdirs())
        directory.listFiles()?.sortedByDescending(File::lastModified)?.drop(2)?.forEach { it.delete() }
        val report = File(directory, "abred-report-${UUID.randomUUID()}.txt")
        report.writeText(if (crashOnly) crash!! else buildString {
            append(metadata(context)).append('\n')
            append("Structured events (bounded; no logcat):\n").append(files.logs())
            if (crash != null) append("\nLast uncaught JVM exception:\n").append(crash)
        }, Charsets.UTF_8)
        updateStatus()
        report
    }

    /** A bounded flush includes pending events before a stop/export without waiting on a worker. */
    private fun drainPending() {
        repeat(128) {
            val entry = synchronized(pending) { pending.pollFirst() } ?: return
            if (entry.generation == generation) store?.append(entry.timeMs, entry.event)
        }
    }

    private fun reportDirectory(): File = File(checkNotNull(appContext).cacheDir, "diagnostic_reports")

    private fun append(event: String) {
        val safe = checkNotNull(DiagnosticPrivacy.event(event, emptyMap()))
        store?.append(System.currentTimeMillis(), safe)
    }

    private fun storageFailed() {
        session.stop()
        _state.value = _state.value.copy(available = store != null, recording = false, storageError = true)
    }

    private fun updateStatus() {
        val files = store ?: return
        // Older AtomicFile implementations can leave only .bak after an interrupted write.
        // Recover it before deciding whether the "last crash" action should be shown.
        if (File(files.crashFile.path + ".bak").isFile) {
            runCatching { AtomicFile(files.crashFile).openRead().close() }
                .onFailure { storageFailed() }
        }
        val logBytes = files.logBytes()
        val reportBytes = reportDirectory().listFiles()?.filter(File::isFile)?.sumOf { it.length() } ?: 0L
        _state.value = _state.value.copy(
            available = true,
            recording = session.isActive(),
            logBytes = logBytes,
            storedBytes = logBytes + files.crashFile.length() + reportBytes,
            crashSavedAt = files.crashFile.takeIf(File::isFile)?.lastModified(),
        )
    }

    private fun saveCrash(error: Throwable) {
        val files = store ?: return
        val text = metadata(checkNotNull(appContext)) + "\n" + DiagnosticPrivacy.crash(error)
        val target = AtomicFile(files.crashFile)
        val output = target.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun readCrash(files: DiagnosticLogStore): String? {
        if (!files.crashFile.exists() && !File(files.crashFile.path + ".bak").exists()) return null
        return AtomicFile(files.crashFile).openRead().bufferedReader().use { reader ->
            val buffer = CharArray(72 * 1024)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count)
        }
    }

    private fun metadata(context: Context): String = buildString {
        append("AudioBookRed diagnostic report\n")
        append("diagnosticSchema=2\n")
        append("Chapter indexes are zero-based; positions/durations are milliseconds.\n")
        append("operationId links parser and HTTP; requestId links a request and its follow-ups.\n")
        append("hostKey is opaque and scoped to this process; unknown sources stay other.\n")
        append("timestamp=").append(System.currentTimeMillis()).append('\n')
        append("version=").append(BuildConfig.VERSION_NAME).append(" code=").append(BuildConfig.VERSION_CODE).append('\n')
        append("package=").append(context.packageName).append('\n')
        append("android=").append(headerValue(Build.VERSION.RELEASE)).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        append("manufacturer=").append(headerValue(Build.MANUFACTURER)).append('\n')
        append("model=").append(headerValue(Build.MODEL)).append('\n')
        append("No URLs, credentials, book titles, exception messages or logcat.\n")
    }

    private fun headerValue(value: String): String = value.filter { !it.isISOControl() }.take(96)
    private data class Entry(val generation: Long, val timeMs: Long, val event: String)
}
