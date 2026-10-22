package com.example.data.narration

import org.junit.Assert.*
import org.junit.Test

class TextAudioSyncMapTest {
    private val map = TextAudioSyncMap("manifest", "chapter", 24_000, listOf(
        TextAudioSyncEntry("first", 0, 48_000), TextAudioSyncEntry("second", 48_000, 192_000),
    ))

    @Test fun realPcmDurationsDetermineFragmentBoundariesInBothDirections() {
        map.validate(192_000, listOf("first", "second"))
        assertEquals("second", map.textAt(1_000)!!.first)
        assertEquals(0.5, map.textAt(2_500)!!.second, 0.000001)
        assertEquals(2_500L, map.timeAt("second", 0.5))
        assertEquals(500L, map.timeAt("first", 0.5))
        assertNull(map.timeAt("deleted", 0.0))
    }

    @Test fun clampingAndUnknownTimeStayWithinMap() {
        assertEquals("first" to 0.0, map.textAt(-1))
        assertEquals("second" to 1.0, map.textAt(Long.MAX_VALUE))
        assertEquals(1_000L, map.timeAt("second", -1.0))
        assertEquals(4_000L, map.timeAt("second", 2.0))
    }

    @Test fun roundTripIncludesManifestChapterRateAndPcmBoundaries() {
        assertEquals(map, TextAudioSyncMap.parse(map.json()).validate(192_000))
    }

    @Test fun atomicCheckpointRejectsRowsBeyondCommittedAudio() {
        reject { map.validate(48_000) }
        val firstCheckpoint = map.copy(entries = map.entries.take(1))
        assertEquals(firstCheckpoint, firstCheckpoint.validate(48_000, listOf("first")))
        // Crash after append but before checkpoint leaves the old committed prefix.
        assertEquals("first" to 1.0, firstCheckpoint.textAt(1_500))
    }

    @Test fun rejectsGapsDuplicatesWrongOrderAndInvalidSampleRates() {
        reject { map.copy(entries = listOf(map.entries[1])).validate(192_000) }
        reject { map.copy(entries = map.entries + TextAudioSyncEntry("first", 192_000, 200_000)).validate(200_000) }
        reject { map.validate(192_000, listOf("second", "first")) }
        reject { map.copy(sampleRate = 0).validate(192_000) }
        reject { map.copy(entries = listOf(TextAudioSyncEntry("first", 0, 101))).validate(101) }
    }

    @Test fun versionTwoCarriesBookChecksumAndActualMilliseconds() {
        val v2 = map.copy(bookId = "book", sourceSha256 = "a".repeat(64))
        val json = v2.json()
        assertEquals(2, json.getInt("version"))
        assertEquals(1000L, json.getJSONArray("entries").getJSONObject(1).getLong("startMs"))
        assertEquals(4000L, json.getJSONArray("entries").getJSONObject(1).getLong("endMs"))
        assertEquals(v2, TextAudioSyncMap.parse(json).validate(192_000))
        json.getJSONArray("entries").getJSONObject(1).put("startMs", 999)
        reject { TextAudioSyncMap.parse(json) }
    }

    @Test fun versionOneCheckpointRemainsReadableWhenExportResumes() {
        val old = map.json().put("version", 1)
        old.remove("bookId"); old.remove("sourceSha256")
        assertEquals(map, TextAudioSyncMap.parse(old).validate(192_000))
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid sync map must fail validation") }
        catch (_: IllegalArgumentException) { }
    }
}
