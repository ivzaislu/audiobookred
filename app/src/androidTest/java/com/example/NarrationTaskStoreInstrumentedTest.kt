package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.example.data.narration.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NarrationTaskStoreInstrumentedTest {
    private fun withStore(block: (NarrationTaskStore, File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "narration-store-test-${UUID.randomUUID()}")
        val journals = File(root, "journals")
        val audio = File(root, "audio")
        try {
            val store = NarrationTaskStore(journals, audio)
            store.initialize()
            block(store, journals, audio)
        } finally { root.deleteRecursively() }
    }

    private fun writeWav(file: File) {
        RandomAccessFile(file, "rw").use {
            fun int(value: Int) = it.writeInt(Integer.reverseBytes(value))
            fun short(value: Int) = it.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            it.writeBytes("RIFF"); int(40); it.writeBytes("WAVEfmt ")
            int(16); short(1); short(1); int(24000); int(48000); short(2); short(16)
            it.writeBytes("data"); int(4); short(100); short(-100)
        }
    }

    @Test fun interruptedGenerationDiscardsPartAndRequiresRetry() = withStore { store, journals, audio ->
        val spec = NarrationSpec.create("Книга.", "xenia-ru", 10100)
        store.write(StoredNarrationTask(spec, NarrationStatus.GENERATING, NarrationPriority.PLAYBACK, 1))
        writeWav(store.part(spec.key))
        val reopened = NarrationTaskStore(journals, audio)
        assertEquals(NarrationStatus.INTERRUPTED, reopened.initialize().single().status)
        assertFalse(reopened.part(spec.key).exists())
        assertNull(reopened.completedAudio(spec.key))
    }

    @Test fun ruVoiceJournalsRestoreVoiceVersionAndRevision() = withStore { store, journals, audio ->
        val original = NarrationSpec.create("Обычная проба.", "xenia-ru", 10100)
        val updated = NarrationSpec.create("Проба с другим голосом.", "aidar-ru", 10100, 2)
        store.write(StoredNarrationTask(original, NarrationStatus.INTERRUPTED, NarrationPriority.PREVIEW, 1))
        store.write(StoredNarrationTask(updated, NarrationStatus.INTERRUPTED, NarrationPriority.PREVIEW, 2))

        val reopened = NarrationTaskStore(journals, audio).initialize().associateBy { it.spec.key }
        assertEquals(2, reopened.size)
        assertEquals("xenia-ru", reopened[original.key]?.spec?.voiceName)
        assertEquals(10100L, reopened[original.key]?.spec?.engineVersion)
        assertEquals("aidar-ru", reopened[updated.key]?.spec?.voiceName)
        assertEquals(2L, reopened[updated.key]?.spec?.settingsRevision)
    }

    @Test fun renameBeforeJournalCompletionIsRecoverableAndCorruptionIsRejected() = withStore { store, journals, audio ->
        val spec = NarrationSpec.create("Книга.", "xenia-ru", 10100)
        store.write(StoredNarrationTask(spec, NarrationStatus.GENERATING, NarrationPriority.PLAYBACK, 1))
        writeWav(store.part(spec.key))
        assertFalse(store.commit(spec.key).cacheHit)
        val reopened = NarrationTaskStore(journals, audio)
        val task = reopened.initialize().single()
        assertEquals(NarrationStatus.READY, task.status)
        assertEquals(spec.voiceName, task.spec.voiceName)
        assertEquals(spec.engineVersion, task.spec.engineVersion)
        assertTrue(reopened.completedAudio(spec.key)!!.cacheHit)
        reopened.audio(spec.key).writeText("incomplete")
        assertEquals(NarrationStatus.INTERRUPTED, NarrationTaskStore(journals, audio).initialize().single().status)
        assertFalse(reopened.audio(spec.key).exists())
    }
}
