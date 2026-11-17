package com.example.data.textbook

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextLibraryArchiveInstrumentedTest {
    @Test fun oldArchivesRestoreAndInvalidContentsAnchorsRollBack() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "contents-transfer-${System.nanoTime()}").apply { mkdirs() }
        val sourceContext = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "source").apply { mkdirs() } }
        val targetContext = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "target").apply { mkdirs() } }
        val sourceDb = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(base, TextLibraryDatabase::class.java).build()
        try {
            val text = File(root, "book.txt").apply { writeText("Глава 1\nКнига для переноса меток.") }
            val source = TextLibraryRepository(sourceContext, sourceDb)
            val target = TextLibraryRepository(targetContext, targetDb)
            val id = source.importBook(Uri.fromFile(text)).bookId
            val fragment = sourceDb.library().allFragments(id).first()
            source.saveContentsMarker(id, fragment.id, 0, "Начало")
            val archive = File(root, "full.zip")
            source.exportArchive(archive)
            fun rewrite(name: String, transform: (org.json.JSONObject) -> Unit): File {
                val output = File(root, name)
                ZipFile(archive).use { input -> ZipOutputStream(output.outputStream()).use { zip ->
                    for (entry in input.entries()) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        val bytes = input.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == "book-0.json") {
                            val json = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                            transform(json)
                            zip.write(json.toString().toByteArray(Charsets.UTF_8))
                        } else zip.write(bytes)
                        zip.closeEntry()
                    }
                } }
                return output
            }
            val invalid = rewrite("invalid.zip") {
                it.getJSONObject("contents").getJSONArray("markers").getJSONObject(0).put("fragmentId", "foreign:f0")
            }
            try { target.restoreArchive(Uri.fromFile(invalid)); fail("Foreign marker accepted") }
            catch (_: IllegalArgumentException) { assertNull(targetDb.library().book(id)) }
            val legacy = rewrite("legacy.zip") { it.remove("contents") }
            assertEquals(1, target.restoreArchive(Uri.fromFile(legacy)))
            assertEquals(TextContentsEdits(), target.open(id).contents)
        } finally {
            sourceDb.close()
            targetDb.close()
            root.deleteRecursively()
        }
    }

    @Test fun incompleteArchiveRollsBackAndRetryRestoresPositionWithoutOverwritingLocalProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDb = Room.inMemoryDatabaseBuilder(context, TextLibraryDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, TextLibraryDatabase::class.java).build()
        val original = File.createTempFile("archive-source-", ".txt", context.cacheDir)
        val archive = File.createTempFile("archive-complete-", ".zip", context.cacheDir)
        val incomplete = File.createTempFile("archive-incomplete-", ".zip", context.cacheDir)
        val targetFolder = File(context.cacheDir, "archive-target-${original.name}").apply { mkdir() }
        val targetContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = targetFolder
        }
        var importedId: String? = null
        try {
            original.writeText("Глава 1\nТекст книги ${original.name}.\n\nГлава 2\nПродолжение.")
            val source = TextLibraryRepository(context, sourceDb)
            val target = TextLibraryRepository(targetContext, targetDb)
            val id = source.importBook(Uri.fromFile(original)).bookId
            importedId = id
            val bookmark = sourceDb.library().allFragments(id).last()
            assertTrue(source.savePosition(id, bookmark.id, 42))
            val originalNarration = source.narrationBook(id)
            source.renameContentsChapter(id, originalNarration.chapters.first().id, "Моё название")
            source.saveContentsMarker(id, bookmark.id, 1, "Моя метка")
            assertEquals(originalNarration, source.narrationBook(id))
            val contents = source.open(id).contents
            assertEquals(42, sourceDb.library().position(id)?.scrollOffsetPx)
            assertEquals(1, source.exportArchive(archive))
            ZipFile(archive).use { complete ->
                ZipOutputStream(incomplete.outputStream()).use { partial ->
                    for (entry in complete.entries()) {
                        if (entry.name == "complete") continue
                        partial.putNextEntry(ZipEntry(entry.name))
                        complete.getInputStream(entry).use { it.copyTo(partial) }
                        partial.closeEntry()
                    }
                }
            }
            try {
                target.restoreArchive(Uri.fromFile(incomplete))
                fail("Archive without its completion entry was accepted")
            } catch (_: IllegalArgumentException) {
                assertNull(targetDb.library().book(id))
                assertFalse(File(targetFolder, "text-library/$id.source").exists())
                assertFalse(File(targetFolder, "text-library/$id.contents.json").exists())
            }
            assertEquals(1, target.restoreArchive(Uri.fromFile(archive)))
            assertEquals(sourceDb.library().allFragments(id), targetDb.library().allFragments(id))
            assertEquals(sourceDb.library().position(id), targetDb.library().position(id))
            assertEquals(contents, target.open(id).contents)
            val reopened = TextLibraryRepository(targetContext, targetDb)
            assertEquals(contents, reopened.open(id).contents)
            reopened.saveContentsMarker(id, bookmark.id, 1, "Локальное название")
            assertTrue(target.savePosition(id, bookmark.id, 90))
            assertEquals(0, target.restoreArchive(Uri.fromFile(archive)))
            assertEquals(90, targetDb.library().position(id)?.scrollOffsetPx)
            assertEquals("Локальное название", reopened.open(id).contents.markers.single().title)
        } finally {
            importedId?.let { id ->
                val folder = File(context.filesDir, "text-library")
                File(folder, "$id.source").delete()
                TextBookMetadataStore(folder).remove(id)
                TextContentsStore(folder).remove(id)
            }
            targetFolder.deleteRecursively()
            sourceDb.close()
            targetDb.close()
            original.delete()
            archive.delete()
            incomplete.delete()
        }
    }
}
