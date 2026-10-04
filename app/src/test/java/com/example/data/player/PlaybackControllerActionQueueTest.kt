package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackControllerActionQueueTest {
    @Test
    fun drainsOnlyActionsFromCurrentGeneration() {
        val queue = PlaybackControllerActionQueue<String>()
        val calls = mutableListOf<String>()

        queue.enqueue(1L) { calls += "old:$it" }
        queue.enqueue(2L) { calls += "new:$it" }

        queue.drain(2L).forEach { it("controller") }

        assertEquals(listOf("new:controller"), calls)
        assertEquals(0, queue.size())
    }

    @Test
    fun preservesOrderWithinGeneration() {
        val queue = PlaybackControllerActionQueue<Unit>()
        val calls = mutableListOf<Int>()

        queue.enqueue(7L) { calls += 1 }
        queue.enqueue(7L) { calls += 2 }
        queue.enqueue(7L) { calls += 3 }

        queue.drain(7L).forEach { it(Unit) }

        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test
    fun clearDropsAllPendingActions() {
        val queue = PlaybackControllerActionQueue<Unit>()
        queue.enqueue(1L) {}
        queue.enqueue(1L) {}

        queue.clear()

        assertEquals(0, queue.size())
        assertEquals(emptyList<(Unit) -> Unit>(), queue.drain(1L))
    }

    @Test
    fun drainingWrongGenerationStillClearsStaleEntries() {
        val queue = PlaybackControllerActionQueue<Unit>()
        queue.enqueue(3L) {}

        val drained = queue.drain(4L)

        assertEquals(0, drained.size)
        assertEquals(0, queue.size())
    }
}
