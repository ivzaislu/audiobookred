package com.example.data.player

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread ownership of asynchronous controller selection, including its side effects. */
internal class GuardedSessionPreparation(private val scope: CoroutineScope) {
    private val guard = PlaybackRecoveryGuard()
    private var pendingFuture: SettableFuture<*>? = null
    private var pendingJob: Job? = null
    private var pendingOwner: Any? = null
    private val cancelledOwners = mutableSetOf<Any>()

    fun shouldIgnoreQueuedPlayback(owner: Any?): Boolean = owner != null && owner in cancelledOwners

    fun onInteractionFinished(owner: Any) {
        cancelledOwners.remove(owner)
    }

    fun invalidate() {
        guard.invalidate()
        val future = pendingFuture
        val job = pendingJob
        val owner = pendingOwner
        // Completing a Media3 future can synchronously drain its command queue
        // and start a new request. Detach the old state before invoking callbacks.
        pendingOwner = null
        pendingFuture = null
        pendingJob = null
        if (future?.isDone == false) owner?.let(cancelledOwners::add)
        future?.cancel(false)
        job?.cancel()
    }

    fun <T> future(
        owner: Any,
        isAuthorized: () -> Boolean,
        prepare: suspend () -> T,
        onAccepted: (T) -> Unit = {},
    ): ListenableFuture<T> {
        invalidate()
        cancelledOwners.remove(owner)
        val request = guard.capture()
        val result = SettableFuture.create<T>()
        pendingFuture = result
        pendingOwner = owner
        pendingJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val prepared = withContext(Dispatchers.IO) { prepare() }
                if (!guard.isCurrent(request) || result.isCancelled || !isAuthorized()) {
                    if (!result.isDone) {
                        cancelledOwners.add(owner)
                        result.cancel(false)
                    }
                    return@launch
                }
                // Media3 transforms this future inline on its application thread.
                // No await between the final check, side effects and SDK handoff.
                onAccepted(prepared)
                result.set(prepared)
            } catch (error: CancellationException) {
                result.cancel(false)
                throw error
            } catch (error: Exception) {
                if (!result.isDone) {
                    cancelledOwners.add(owner)
                    result.setException(error)
                }
            }
        }
        return result
    }
}
