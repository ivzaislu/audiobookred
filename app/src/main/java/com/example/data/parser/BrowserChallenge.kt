package com.example.data.parser

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

internal const val BROWSER_CHALLENGE_SOURCE_EXTRA = "browser_challenge_source"
internal const val BROWSER_CHALLENGE_REQUEST_ID_EXTRA = "browser_challenge_request_id"
internal const val BROWSER_CHALLENGE_DEFAULT_TIMEOUT_MS = 120_000L

internal data class BrowserChallengeUiState(
    val requestId: Long = 0L,
    val targetUrl: String = "",
    val required: Boolean = false,
)

internal data class BrowserChallengeSpec(
    val sourceId: String,
    val displayName: String,
    val defaultUrl: String,
    val trustedHosts: Set<String>,
    val successProbeScript: String,
    val requiresCookie: Boolean = true,
    val timeoutMs: Long = BROWSER_CHALLENGE_DEFAULT_TIMEOUT_MS,
    val includeSubdomains: Boolean = true,
) {
    init {
        require(sourceId.isNotBlank())
        require(displayName.isNotBlank())
        require(defaultUrl.isNotBlank())
        require(trustedHosts.isNotEmpty())
    }

    fun isTrustedNavigation(url: String): Boolean =
        trustedHosts.any { host ->
            isTrustedHttpsChallengeHost(
                url = url,
                host = host,
                includeSubdomains = includeSubdomains,
            )
        }
}

internal class BrowserChallengeCoordinator(
    private val defaultTargetUrl: String,
    private val timeoutMs: Long = BROWSER_CHALLENGE_DEFAULT_TIMEOUT_MS,
) {
    private val lock = Any()
    private var nextRequestId = 0L
    private val resolutions = LinkedHashMap<Long, CompletableDeferred<Boolean>>()

    private val _uiState = MutableStateFlow(
        BrowserChallengeUiState(targetUrl = defaultTargetUrl)
    )
    val uiState: StateFlow<BrowserChallengeUiState> = _uiState.asStateFlow()

    fun requireChallenge(targetUrl: String): Long = synchronized(lock) {
        val current = _uiState.value
        if (current.required) return@synchronized current.requestId

        val next = ++nextRequestId
        resolutions[next] = CompletableDeferred()
        pruneResolutionsLocked()
        _uiState.value = BrowserChallengeUiState(
            requestId = next,
            targetUrl = targetUrl.ifBlank { defaultTargetUrl },
            required = true,
        )
        next
    }

    fun completeChallenge(
        requestId: Long,
        beforeComplete: () -> Unit = {},
    ): Boolean {
        val resolution = synchronized(lock) {
            val current = _uiState.value
            if (!current.required || current.requestId != requestId) {
                return@synchronized null
            }
            beforeComplete()
            _uiState.value = BrowserChallengeUiState(targetUrl = defaultTargetUrl)
            resolutions[requestId]
        } ?: return false

        resolution.complete(true)
        return true
    }

    fun cancelChallenge(requestId: Long): Boolean {
        val resolution = synchronized(lock) {
            val current = _uiState.value
            if (!current.required || current.requestId != requestId) {
                return@synchronized null
            }
            _uiState.value = BrowserChallengeUiState(targetUrl = defaultTargetUrl)
            resolutions[requestId]
        } ?: return false

        resolution.complete(false)
        return true
    }

    suspend fun awaitResolution(
        requestId: Long,
        customTimeoutMs: Long = timeoutMs,
    ): Boolean {
        val resolution = synchronized(lock) {
            resolutions[requestId]
                ?: CompletableDeferred<Boolean>().also { resolutions[requestId] = it }
        }

        return try {
            val completed = withTimeoutOrNull(customTimeoutMs.coerceAtLeast(1L)) {
                resolution.await()
            }
            if (completed != null) {
                completed
            } else {
                cancelChallenge(requestId)
                false
            }
        } catch (cancelled: CancellationException) {
            cancelChallenge(requestId)
            throw cancelled
        }
    }

    private fun pruneResolutionsLocked() {
        while (resolutions.size > MAX_RESOLUTION_HISTORY) {
            val eldest = resolutions.entries.iterator()
            if (!eldest.hasNext()) return
            val entry = eldest.next()
            if (!entry.value.isCompleted) return
            eldest.remove()
        }
    }

    private companion object {
        const val MAX_RESOLUTION_HISTORY = 8
    }
}

internal class BrowserChallengeRuntime(
    private val spec: BrowserChallengeSpec,
) {
    private val lock = Any()
    private val coordinator = BrowserChallengeCoordinator(
        defaultTargetUrl = spec.defaultUrl,
        timeoutMs = spec.timeoutMs,
    )
    private var launchedRequestId = 0L
    private var inlineHostCount = 0

    @Volatile
    private var applicationContext: Context? = null

    val uiState: StateFlow<BrowserChallengeUiState> = coordinator.uiState

    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(lock) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
        }
    }

    fun requireChallenge(targetUrl: String): Long {
        val requestId = coordinator.requireChallenge(targetUrl)
        scheduleChallengeActivityFallback(requestId)
        return requestId
    }

    fun attachInlineHost() {
        synchronized(lock) {
            inlineHostCount += 1
        }
    }

    fun detachInlineHost() {
        val requestId = synchronized(lock) {
            inlineHostCount = (inlineHostCount - 1).coerceAtLeast(0)
            val current = uiState.value
            if (
                inlineHostCount == 0 &&
                current.required &&
                current.requestId > 0L
            ) {
                current.requestId
            } else {
                0L
            }
        }
        if (requestId > 0L) {
            launchChallengeActivity(requestId)
        }
    }

    fun completeChallenge(
        requestId: Long,
        beforeComplete: () -> Unit = {},
    ): Boolean {
        val completed = coordinator.completeChallenge(requestId, beforeComplete)
        if (completed) clearLaunchedRequest(requestId)
        return completed
    }

    fun cancelChallenge(requestId: Long): Boolean {
        val canceled = coordinator.cancelChallenge(requestId)
        if (canceled) clearLaunchedRequest(requestId)
        return canceled
    }

    suspend fun awaitResolution(
        requestId: Long,
        timeoutMs: Long = spec.timeoutMs,
    ): Boolean {
        return try {
            coordinator.awaitResolution(
                requestId = requestId,
                customTimeoutMs = timeoutMs,
            ).also { resolved ->
                if (!resolved) clearLaunchedRequest(requestId)
            }
        } catch (cancelled: CancellationException) {
            clearLaunchedRequest(requestId)
            throw cancelled
        }
    }

    private fun scheduleChallengeActivityFallback(requestId: Long) {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                val shouldLaunch = synchronized(lock) {
                    inlineHostCount == 0
                }
                if (shouldLaunch) {
                    launchChallengeActivity(requestId)
                }
            },
            INLINE_HOST_GRACE_MS,
        )
    }

    private fun launchChallengeActivity(requestId: Long) {
        val context = applicationContext ?: return
        synchronized(lock) {
            if (inlineHostCount > 0 || launchedRequestId == requestId) return
            launchedRequestId = requestId
        }

        Handler(Looper.getMainLooper()).post {
            val current = uiState.value
            val inlineHostActive = synchronized(lock) { inlineHostCount > 0 }
            if (
                inlineHostActive ||
                !current.required ||
                current.requestId != requestId
            ) {
                clearLaunchedRequest(requestId)
                return@post
            }

            val intent = Intent().apply {
                setClassName(context.packageName, BROWSER_CHALLENGE_ACTIVITY)
                putExtra(BROWSER_CHALLENGE_SOURCE_EXTRA, spec.sourceId)
                putExtra(BROWSER_CHALLENGE_REQUEST_ID_EXTRA, requestId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure {
                    clearLaunchedRequest(requestId)
                    cancelChallenge(requestId)
                }
        }
    }

    private fun clearLaunchedRequest(requestId: Long) {
        synchronized(lock) {
            if (launchedRequestId == requestId) launchedRequestId = 0L
        }
    }

    private companion object {
        const val BROWSER_CHALLENGE_ACTIVITY = "com.example.ui.BrowserChallengeActivity"
        const val INLINE_HOST_GRACE_MS = 300L
    }
}

internal interface BrowserChallengeSession {
    val challengeSpec: BrowserChallengeSpec
    val uiState: StateFlow<BrowserChallengeUiState>

    val challengeSourceId: String
        get() = challengeSpec.sourceId
    val challengeDisplayName: String
        get() = challengeSpec.displayName
    val defaultChallengeUrl: String
        get() = challengeSpec.defaultUrl
    val successProbeScript: String
        get() = challengeSpec.successProbeScript

    fun initialize(context: Context)
    fun requireChallenge(targetUrl: String): Long
    suspend fun awaitResolution(
        requestId: Long,
        timeoutMs: Long = challengeSpec.timeoutMs,
    ): Boolean
    fun completeChallenge(
        requestId: Long,
        targetUrl: String,
        cookieHeader: String,
        userAgent: String,
    )
    fun cancelChallenge(requestId: Long)
    fun attachInlineHost()
    fun detachInlineHost()

    fun isTrustedBrowserNavigation(url: String): Boolean =
        challengeSpec.isTrustedNavigation(url)

    fun browserCookieLookupUrl(
        currentUrl: String,
        targetUrl: String,
    ): String =
        currentUrl.takeIf { isTrustedBrowserNavigation(it) }
            ?: targetUrl.takeIf { isTrustedBrowserNavigation(it) }
            ?: defaultChallengeUrl
}

internal abstract class BaseBrowserChallengeSession(
    final override val challengeSpec: BrowserChallengeSpec,
) : BrowserChallengeSession {
    private val runtime = BrowserChallengeRuntime(challengeSpec)

    @Volatile
    private var systemBrowserUserAgent: String = ""

    final override val uiState: StateFlow<BrowserChallengeUiState> = runtime.uiState

    final override fun initialize(context: Context) {
        val app = context.applicationContext
        runtime.initialize(app)
        if (systemBrowserUserAgent.isBlank()) {
            systemBrowserUserAgent = runCatching {
                WebSettings.getDefaultUserAgent(app)
            }.getOrDefault("").trim().ifBlank {
                System.getProperty("http.agent").orEmpty().trim()
            }
        }
        initializeProvider(app)
    }

    fun userAgent(): String =
        persistedUserAgent().trim().ifBlank { systemBrowserUserAgent }

    protected abstract fun persistedUserAgent(): String

    protected abstract fun initializeProvider(context: Context)

    final override fun requireChallenge(targetUrl: String): Long =
        runtime.requireChallenge(targetUrl)

    final override suspend fun awaitResolution(
        requestId: Long,
        timeoutMs: Long,
    ): Boolean =
        runtime.awaitResolution(
            requestId = requestId,
            timeoutMs = timeoutMs,
        )

    final override fun completeChallenge(
        requestId: Long,
        targetUrl: String,
        cookieHeader: String,
        userAgent: String,
    ) {
        runtime.completeChallenge(requestId) {
            persistChallengeResult(
                targetUrl = targetUrl,
                cookieHeader = cookieHeader,
                userAgent = userAgent,
            )
        }
    }

    protected abstract fun persistChallengeResult(
        targetUrl: String,
        cookieHeader: String,
        userAgent: String,
    )

    final override fun cancelChallenge(requestId: Long) {
        runtime.cancelChallenge(requestId)
    }

    final override fun attachInlineHost() {
        runtime.attachInlineHost()
    }

    final override fun detachInlineHost() {
        runtime.detachInlineHost()
    }
}

internal object BrowserChallengeRegistry {
    val sessions: List<BrowserChallengeSession>
        get() = listOf(
            AudiobooCloudflareSession,
            RuTrackerCloudflareSession,
        )

    fun find(sourceId: String): BrowserChallengeSession? = when (sourceId) {
        AudiobooCloudflareSession.challengeSourceId -> AudiobooCloudflareSession
        RuTrackerCloudflareSession.challengeSourceId -> RuTrackerCloudflareSession
        else -> null
    }
}

internal suspend fun BrowserChallengeSession.awaitChallengeForRetry(
    targetUrl: String,
    timeoutMs: Long = challengeSpec.timeoutMs,
): Boolean {
    val requestId = requireChallenge(targetUrl)
    return awaitResolution(
        requestId = requestId,
        timeoutMs = timeoutMs,
    )
}

internal fun shouldCancelHostedBrowserChallenge(
    hostedRequestId: Long,
    current: BrowserChallengeUiState,
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean =
    hostedRequestId > 0L &&
        isFinishing &&
        !isChangingConfigurations &&
        current.required &&
        current.requestId == hostedRequestId

internal fun isTrustedHttpsChallengeHost(
    url: String,
    host: String,
    includeSubdomains: Boolean = true,
): Boolean =
    runCatching {
        val parsed = URI(url)
        val actualHost = parsed.host.orEmpty().lowercase()
        val expectedHost = host.lowercase()
        parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.userInfo == null &&
            (parsed.port == -1 || parsed.port == 443) &&
            (
                actualHost == expectedHost ||
                    (includeSubdomains && actualHost.endsWith(".$expectedHost"))
            )
    }.getOrDefault(false)
