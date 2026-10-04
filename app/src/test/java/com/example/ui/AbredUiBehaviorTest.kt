package com.example.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredUiBehaviorTest {
    @Test
    fun miniPlayerDismissesAfterSwipeInEitherDirection() {
        assertTrue(shouldDismissMiniPlayer(-120f))
        assertTrue(shouldDismissMiniPlayer(120f))
        assertTrue(shouldDismissMiniPlayer(-180f))
        assertTrue(shouldDismissMiniPlayer(180f))
        assertFalse(shouldDismissMiniPlayer(-119.9f))
        assertFalse(shouldDismissMiniPlayer(119.9f))
    }
}
