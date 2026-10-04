package com.example.data.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Stateless command gateway to the app Media3 session.
 *
 * Screen/data owners that must coordinate with active playback can issue a
 * command without depending on the Activity-scoped AudiobookPlayerManager or
 * owning a second copy of player UI state.
 */
@Singleton
class PlaybackSessionCommands @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    suspend fun pause() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                mainExecutor.execute { MediaController.releaseFuture(future) }
            }
            future.addListener(
                {
                    val error = try {
                        val controller = future.get()
                        try {
                            controller.pause()
                        } finally {
                            controller.release()
                        }
                        null
                    } catch (error: Exception) {
                        error
                    }
                    if (!continuation.isActive) return@addListener
                    if (error == null) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                },
                mainExecutor,
            )
        }
    }
}
