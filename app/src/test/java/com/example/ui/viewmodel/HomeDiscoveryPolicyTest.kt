package com.example.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDiscoveryPolicyTest {
    @Test
    fun newShelfRefreshesAfterOneHourButKeepsStaleCacheUsable() {
        val now = 10_000_000L
        assertFalse(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - HOME_NEW_TTL_MS + 1L,
                nowMs = now,
                ttlMs = HOME_NEW_TTL_MS,
            )
        )
        assertTrue(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - HOME_NEW_TTL_MS,
                nowMs = now,
                ttlMs = HOME_NEW_TTL_MS,
            )
        )
    }

    @Test
    fun popularShelfRefreshesAfterTwelveHours() {
        val now = 100_000_000L
        assertFalse(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - HOME_POPULAR_TTL_MS + 1L,
                nowMs = now,
                ttlMs = HOME_POPULAR_TTL_MS,
            )
        )
        assertTrue(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - HOME_POPULAR_TTL_MS,
                nowMs = now,
                ttlMs = HOME_POPULAR_TTL_MS,
            )
        )
    }

    @Test
    fun missingCacheAndManualRefreshAlwaysRequestNetwork() {
        assertTrue(shouldRefreshHomeDiscovery(null, 1_000L, HOME_NEW_TTL_MS))
        assertTrue(
            shouldRefreshHomeDiscovery(
                savedAtMs = 999L,
                nowMs = 1_000L,
                ttlMs = HOME_POPULAR_TTL_MS,
                force = true,
            )
        )
    }
}