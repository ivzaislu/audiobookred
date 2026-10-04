package com.example.ui

import com.example.data.parser.AudiobooCloudflareSession
import com.example.data.parser.RuTrackerCloudflareSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerCloudflareNavigationPolicyTest {
    @Test
    fun browserUserAgentKeepsActualSystemWebViewIdentity() {
        val actualSystemUa =
            "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 Chrome/151.0.0.0 Mobile Safari/537.36"
        val fallbackSystemUa =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36"

        assertEquals(
            actualSystemUa,
            chooseBrowserChallengeUserAgent(actualSystemUa, fallbackSystemUa),
        )
        assertEquals(
            fallbackSystemUa,
            chooseBrowserChallengeUserAgent("", fallbackSystemUa),
        )
    }

    @Test
    fun eachProviderAllowsOnlyItsTrustedHttpsMainFrame() {
        assertTrue(
            RuTrackerCloudflareSession.isTrustedBrowserNavigation(
                "https://rutracker.org/forum/"
            )
        )
        assertTrue(
            RuTrackerCloudflareSession.isTrustedBrowserNavigation(
                "https://static.rutracker.org/forum/"
            )
        )
        assertTrue(
            AudiobooCloudflareSession.isTrustedBrowserNavigation(
                "https://audioboo.org/"
            )
        )

        assertFalse(
            RuTrackerCloudflareSession.isTrustedBrowserNavigation(
                "https://audioboo.org/"
            )
        )
        assertFalse(
            AudiobooCloudflareSession.isTrustedBrowserNavigation(
                "https://rutracker.org/forum/"
            )
        )
    }

    @Test
    fun insecureExternalAndNonStandardMainFramesAreBlocked() {
        listOf(
            "http://rutracker.org/forum/",
            "https://rutracker.org:8443/forum/",
            "https://user@rutracker.org/forum/",
            "https://evil.example/challenge",
            "https://rutracker.org.evil.example/",
        ).forEach { url ->
            assertTrue(
                shouldBlockBrowserChallengeNavigation(
                    session = RuTrackerCloudflareSession,
                    url = url,
                    isForMainFrame = true,
                )
            )
        }

        assertTrue(
            shouldBlockBrowserChallengeNavigation(
                session = AudiobooCloudflareSession,
                url = "https://evil.example/challenge",
                isForMainFrame = true,
            )
        )
    }

    @Test
    fun onlyTrustedMainFrameFailuresBecomeChallengePageErrors() {
        assertTrue(
            shouldShowBrowserChallengeLoadError(
                session = RuTrackerCloudflareSession,
                url = "https://rutracker.org/forum/",
                isForMainFrame = true,
            )
        )
        assertFalse(
            shouldShowBrowserChallengeLoadError(
                session = RuTrackerCloudflareSession,
                url = "https://challenges.cloudflare.com/turnstile/v0/api.js",
                isForMainFrame = false,
            )
        )
        assertFalse(
            shouldShowBrowserChallengeLoadError(
                session = RuTrackerCloudflareSession,
                url = "https://evil.example/",
                isForMainFrame = true,
            )
        )
    }

    @Test
    fun externalSubresourcesRemainAvailableForChallengeWidgets() {
        assertFalse(
            shouldBlockBrowserChallengeNavigation(
                session = RuTrackerCloudflareSession,
                url = "https://challenges.cloudflare.com/turnstile/v0/api.js",
                isForMainFrame = false,
            )
        )
        assertFalse(
            shouldBlockBrowserChallengeNavigation(
                session = AudiobooCloudflareSession,
                url = "https://challenges.cloudflare.com/turnstile/v0/api.js",
                isForMainFrame = false,
            )
        )
    }
}
