package com.example.data.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSettingsRepositoryTest {
    @Test
    fun storageDirectoryBytesCountsFinalPartialAndOrphanFiles() {
        val root = Files.createTempDirectory("abred-storage-test").toFile()
        try {
            val book = root.resolve("book-a").apply { mkdirs() }
            book.resolve("chapter-1.mp3").writeBytes(ByteArray(11))
            book.resolve("chapter-2.mp3.part").writeBytes(ByteArray(7))
            root.resolve("orphan.bin").writeBytes(ByteArray(5))

            assertEquals(23L, storageDirectoryBytes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingStorageDirectoryUsesZeroBytes() {
        val root = Files.createTempDirectory("abred-storage-missing").toFile()
        root.deleteRecursively()

        assertEquals(0L, storageDirectoryBytes(root))
    }
}
