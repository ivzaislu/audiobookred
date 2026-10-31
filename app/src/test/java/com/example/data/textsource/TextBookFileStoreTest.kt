package com.example.data.textsource

import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextBookFileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val txt = RemoteTextDownload("txt", "TXT", "https://source.test/book", "txt")
    private val bytes = "Оригинальный текст книги".toByteArray()

    @Test fun originalIsSavedInsideBookWithItsChosenExtension() {
        val file = TextBookFileStore(folder.root).save("source", "12", "Название", txt, bytes)
        assertTrue(file.relativeTo(folder.root).invariantSeparatorsPath.startsWith("book/"))
        assertTrue(file.name.endsWith("Название.txt"))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(file), File(folder.root, "book").walkTopDown().filter { it.isFile }.toList())
    }
    @Test fun repeatedSaveReusesTheSameOriginal() {
        val store = TextBookFileStore(folder.root)
        val file = store.save("source", "12", "Название", txt, bytes)
        assertEquals(file, store.save("source", "12", "Название", txt, bytes))
        assertEquals(1, folder.root.walkTopDown().count { it.isFile })
    }
    @Test fun matchingRemoteIdsFromDifferentSourcesDoNotSharePaths() {
        val store = TextBookFileStore(folder.root)
        val a = store.save("first", "12", "Название", txt, bytes)
        val b = store.save("second", "12", "Название", txt, bytes)
        val c = store.save("first", "13", "Название", txt, bytes)
        assertEquals(3, setOf(a, b, c).size)
    }
    @Test fun changedContentAndFormatsKeepEarlierOriginals() {
        val store = TextBookFileStore(folder.root)
        val first = store.save("source", "12", "Название", txt, bytes)
        val second = store.save("source", "12", "Название", txt, "Новая редакция".toByteArray())
        val epub = store.save("source", "12", "Название", txt.copy(code = "epub", extension = "epub"), bytes)
        assertEquals(3, setOf(first, second, epub).size)
        assertArrayEquals(bytes, first.readBytes())
        assertTrue(epub.name.endsWith(".epub"))
    }
    @Test fun cancelledWriteDoesNotPublishAPartialBook() {
        var checkpoints = 0
        assertThrows(CancellationException::class.java) {
            TextBookFileStore(folder.root).save("source", "12", "Книга", txt, ByteArray(100_000) { 1 }) {
                if (++checkpoints == 3) throw CancellationException("Cancelled")
            }
        }
        assertEquals(0, folder.root.walkTopDown().count { it.isFile })
    }
    @Test fun corruptExistingOriginalIsNeverSilentlyOverwritten() {
        val store = TextBookFileStore(folder.root)
        val first = store.save("source", "12", "Книга", txt, bytes)
        first.writeText("Повреждённый файл")
        assertThrows(IllegalStateException::class.java) { store.save("source", "12", "Книга", txt, bytes) }
        assertEquals("Повреждённый файл", first.readText())
    }
    @Test fun unsupportedAndEmptyFilesAreRejectedBeforeWriting() {
        val store = TextBookFileStore(folder.root)
        assertFalse(txt.copy(extension = "mobi").canImport)
        assertThrows(IllegalArgumentException::class.java) { store.save("source", "12", "Книга", txt.copy(extension = "mobi"), bytes) }
        assertThrows(IllegalArgumentException::class.java) { store.save("source", "12", "Книга", txt, byteArrayOf()) }
        assertEquals(0, folder.root.walkTopDown().count { it.isFile })
    }
    @Test fun unsafeTitlesAndRemoteIdsCannotEscapeBookDirectory() {
        val file = TextBookFileStore(folder.root).save("../../source", "../../id", "../../Книга\n/", txt, bytes)
        assertTrue(file.canonicalPath.startsWith(File(folder.root, "book").canonicalPath + File.separator))
        assertTrue(file.name.endsWith(".txt"))
    }
    @Test fun longCyrillicCjkAndEmojiTitlesFitFilesystemByteLimitWithoutBrokenUnicode() {
        val store = TextBookFileStore(folder.root)
        for (title in listOf("Длинное название ".repeat(30), "漢字".repeat(100), "📚".repeat(100))) {
            val file = store.save("source", "12", title, txt, bytes)
            assertTrue(file.name.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(Charsets.UTF_8.newEncoder().canEncode(file.name))
            assertTrue(file.name.endsWith(".txt"))
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun importedFilesKeepTheirFormatAndReuseTheSameManagedOriginal() {
        val store = TextBookFileStore(folder.root)
        val first = store.saveImported("Архив.fb2.zip", bytes)
        assertTrue(first.name.endsWith("Архив.fb2.zip"))
        assertEquals(first, store.saveImported("Архив.fb2.zip", bytes))
        assertArrayEquals(bytes, first.readBytes())
        val longName = store.saveImported("📚".repeat(150) + ".epub", bytes)
        assertTrue(longName.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(longName.name.endsWith(".epub"))
    }

    @Test fun sourceLookupFindsLegacyDownloadsAndImportedCopiesButNotOtherBooks() {
        val store = TextBookFileStore(folder.root)
        val downloaded = store.save("source", "12", "Книга", txt, bytes)
        val imported = store.saveImported("Книга.txt", bytes)
        val other = store.saveImported("Другая.txt", "Другой текст".toByteArray())
        assertEquals(setOf(downloaded, imported), store.originalsFor(downloaded.name.take(64)).toSet())
        assertEquals(listOf(other), store.originalsFor(other.name.take(64)))
        assertTrue(store.originalsFor("../").isEmpty())
        assertArrayEquals(bytes, downloaded.readBytes())
    }

    @Test fun sourceLookupDoesNotFollowLinksOutsideTheManagedDirectory() {
        val app = folder.newFolder("app")
        val outside = folder.newFolder("outside")
        val hash = "a".repeat(64)
        val original = File(outside, "$hash-book.txt").apply { writeBytes(bytes) }
        val root = File(app, "book").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(TextBookFileStore(app).originalsFor(hash).isEmpty())
        assertTrue(original.isFile)
    }

    @Test fun sourceLookupPreservesThePathUsedToTrackThePublicCopy() {
        val actual = folder.newFolder("actual-files")
        val alias = File(folder.root, "files-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val store = TextBookFileStore(alias)
        val original = store.save("source", "12", "Книга", txt, bytes)
        assertEquals(original.absolutePath, store.originalsFor(original.name.take(64)).single().absolutePath)
    }
}
