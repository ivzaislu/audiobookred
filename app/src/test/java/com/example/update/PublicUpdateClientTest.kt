package com.example.update

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicUpdateClientTest {
    private val repository = PublicUpdateRepository("ivzaislu", "audiobookred")

    private class BlockingResponseCall : Call {
        private val canceled = AtomicBoolean(false)
        val consumeStarted = CountDownLatch(1)
        val releaseConsume = CountDownLatch(1)
        val cancelObserved = CountDownLatch(1)

        @Volatile
        var callback: Callback? = null
            private set

        private val request = Request.Builder()
            .url("https://example.com/update.apk")
            .build()

        override fun request(): Request = request

        override fun execute(): Response =
            throw UnsupportedOperationException("Synchronous execution is not expected")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        fun deliverResponse() {
            checkNotNull(callback).onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("payload".toResponseBody())
                    .build(),
            )
        }

        override fun cancel() {
            canceled.set(true)
            cancelObserved.countDown()
            releaseConsume.countDown()
        }

        override fun isExecuted(): Boolean = callback != null

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = BlockingResponseCall()
    }

    private class HangingCall : Call {
        private val canceled = AtomicBoolean(false)
        var enqueued: Boolean = false
            private set

        private val request = Request.Builder()
            .url("https://example.com/update.apk")
            .build()

        override fun request(): Request = request

        override fun execute(): Response =
            throw UnsupportedOperationException("Synchronous execution is not expected")

        override fun enqueue(responseCallback: Callback) {
            enqueued = true
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = enqueued

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = HangingCall()
    }

    @Test
    fun manifestMustBePublicRawGithubLatestJson() {
        val valid = "https://raw.githubusercontent.com/ivzaislu/audiobookred/main/latest.json"
        assertEquals(valid, PublicUpdateClient.normalizedManifestUrl(valid))
        assertNull(PublicUpdateClient.normalizedManifestUrl("http://raw.githubusercontent.com/ivzaislu/audiobookred/main/latest.json"))
        assertNull(PublicUpdateClient.normalizedManifestUrl("https://github.com/ivzaislu/audiobookred/latest.json"))
        assertNull(PublicUpdateClient.normalizedManifestUrl("https://raw.githubusercontent.com/ivzaislu/audiobookred/main/other.json"))
        assertNull(PublicUpdateClient.normalizedManifestUrl("https://raw.githubusercontent.com/ivzaislu/audiobookred/main/latest.json?x=1"))
    }

    @Test
    fun updateHttpClientHasBoundedReadAndOverallTimeouts() {
        val client = PublicUpdateClient.createUpdateHttpClient()
        assertEquals(
            TimeUnit.SECONDS.toMillis(PublicUpdateClient.UPDATE_READ_TIMEOUT_SECONDS),
            client.readTimeoutMillis.toLong(),
        )
        assertEquals(
            TimeUnit.MINUTES.toMillis(PublicUpdateClient.UPDATE_CALL_TIMEOUT_MINUTES),
            client.callTimeoutMillis.toLong(),
        )
    }

    @Test
    fun rangeHeaderIsAddedOnlyForResumedDownloads() {
        val full = PublicUpdateClient.buildUpdateDownloadRequest(
            downloadUrl = "https://github.com/ivzaislu/audiobookred/releases/download/v1/app.apk",
            resumeFromBytes = 0L,
        )
        val resumed = PublicUpdateClient.buildUpdateDownloadRequest(
            downloadUrl = "https://github.com/ivzaislu/audiobookred/releases/download/v1/app.apk",
            resumeFromBytes = 12_345L,
        )

        assertNull(full.header("Range"))
        assertEquals("bytes=12345-", resumed.header("Range"))
    }

    @Test
    fun coroutineCancellationCancelsActiveOkHttpCall() = runBlocking {
        val call = HangingCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            executeCancellableUpdateCall(call) {
                error("A hanging call must never produce a response")
            }
        }

        assertTrue(call.enqueued)
        job.cancelAndJoin()

        assertTrue(call.isCanceled())
    }

    @Test
    fun cancellationWaitsUntilActiveResponseConsumerHasExited() = runBlocking {
        val call = BlockingResponseCall()
        val consumerFinished = AtomicBoolean(false)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            executeCancellableUpdateCall(call) {
                call.consumeStarted.countDown()
                check(call.releaseConsume.await(10, TimeUnit.SECONDS))
                consumerFinished.set(true)
            }
        }
        assertTrue(call.isExecuted())

        val responseThread = Thread(call::deliverResponse)
        responseThread.start()
        assertTrue(call.consumeStarted.await(10, TimeUnit.SECONDS))

        job.cancelAndJoin()
        responseThread.join(10_000L)

        assertTrue(call.cancelObserved.await(10, TimeUnit.SECONDS))
        assertTrue(call.isCanceled())
        assertFalse(responseThread.isAlive)
        assertTrue(consumerFinished.get())
    }

    @Test
    fun repositoryIsDerivedFromManifestPath() {
        assertEquals(
            repository,
            PublicUpdateClient.repositoryFromManifestUrl(
                "https://raw.githubusercontent.com/ivzaislu/audiobookred/main/latest.json"
            ),
        )
    }

    @Test
    fun releaseDownloadMustStayInsideConfiguredPublicRepository() {
        assertTrue(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://github.com/ivzaislu/audiobookred/releases/download/android-v0.5.3.8/abred-android-0.5.3.8.apk",
                repository,
            )
        )
        assertFalse(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://github.com/ivzaislu/other/releases/download/android-v0.5.3.8/abred.apk",
                repository,
            )
        )
        assertFalse(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://example.com/ivzaislu/audiobookred/releases/download/android-v0.5.3.8/abred.apk",
                repository,
            )
        )
        assertFalse(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://github.com/ivzaislu/audiobookred/releases/download/android-v0.5.3.8/notes.txt",
                repository,
            )
        )
        assertFalse(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://github.com/ivzaislu/audiobookred/releases/download/android-v0.5.3.8/abred.apk?token=x",
                repository,
            )
        )
        assertFalse(
            PublicUpdateClient.isReleaseDownloadForRepository(
                "https://user@github.com/ivzaislu/audiobookred/releases/download/android-v0.5.3.8/abred.apk",
                repository,
            )
        )
    }
}
