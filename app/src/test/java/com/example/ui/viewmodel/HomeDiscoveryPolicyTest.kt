
package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDiscoveryPolicyTest {
    @Test
    fun cacheDurationUsesConfiguredDays() {
        assertEquals(HOME_CACHE_DAY_MS, homeDiscoveryTtlMs(1))
        assertEquals(3L * HOME_CACHE_DAY_MS, homeDiscoveryTtlMs(3))
        assertEquals(10L * HOME_CACHE_DAY_MS, homeDiscoveryTtlMs(10))
    }

    @Test
    fun cachedShelfRefreshesOnlyAfterConfiguredTtl() {
        val now = 1_000_000_000L
        val ttl = homeDiscoveryTtlMs(3)

        assertFalse(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - ttl + 1L,
                nowMs = now,
                ttlMs = ttl,
            )
        )
        assertTrue(
            shouldRefreshHomeDiscovery(
                savedAtMs = now - ttl,
                nowMs = now,
                ttlMs = ttl,
            )
        )
    }

    @Test
    fun missingCacheAndManualRefreshAlwaysRequestNetwork() {
        val ttl = homeDiscoveryTtlMs(5)
        assertTrue(shouldRefreshHomeDiscovery(null, 1_000L, ttl))
        assertTrue(
            shouldRefreshHomeDiscovery(
                savedAtMs = 999L,
                nowMs = 1_000L,
                ttlMs = ttl,
                force = true,
            )
        )
    }
}
