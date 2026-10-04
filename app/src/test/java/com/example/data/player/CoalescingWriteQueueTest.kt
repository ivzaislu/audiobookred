package com.example.data.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescingWriteQueueTest {
    private data class Write(val key: String, val value: Int)
    private data class ProgressWrite(
        val key: String,
        val positionMs: Long,
        val completed: Boolean,
    )

    @Test
    fun newestPendingValueWinsPerKeyWithoutDroppingOtherKeys() = runBlocking {
        val queue = CoalescingWriteQueue<String, Write>(Write::key)

        assertTrue(queue.offer(Write("book-a", 1)))
        assertTrue(queue.offer(Write("book-a", 2)))
        assertTrue(queue.offer(Write("book-b", 3)))

        assertEquals(
            listOf(Write("book-a", 2), Write("book-b", 3)),
            queue.nextBatch(),
        )
        queue.close()
        assertNull(queue.nextBatch())
    }

    @Test
    fun laterRestartMaySupersedePendingCompletedCheckpoint() = runBlocking {
        val queue = CoalescingWriteQueue<String, ProgressWrite>(ProgressWrite::key)

        assertTrue(queue.offer(ProgressWrite("book-a", positionMs = 90_000L, completed = true)))
        assertTrue(queue.offer(ProgressWrite("book-a", positionMs = 5_000L, completed = false)))

        assertEquals(
            listOf(ProgressWrite("book-a", positionMs = 5_000L, completed = false)),
            queue.nextBatch(),
        )
    }

    @Test
    fun closeStillExposesAlreadyPendingValuesThenEnds() = runBlocking {
        val queue = CoalescingWriteQueue<String, Write>(Write::key)
        assertTrue(queue.offer(Write("book-a", 7)))

        queue.close()

        assertEquals(listOf(Write("book-a", 7)), queue.nextBatch())
        assertNull(queue.nextBatch())
        assertFalse(queue.offer(Write("book-a", 8)))
    }
}
