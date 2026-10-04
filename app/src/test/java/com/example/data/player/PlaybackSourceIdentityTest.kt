package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceIdentityTest {
    @Test
    fun blankAndUnknownSourcesAreUnconstrained() {
        assertEquals(null, playbackSourceOrNull(null))
        assertEquals(null, playbackSourceOrNull(""))
        assertEquals(null, playbackSourceOrNull("   "))
        assertEquals(null, playbackSourceOrNull("unknown"))
        assertEquals(null, playbackSourceOrNull("UNKNOWN"))
    }

    @Test
    fun sourceIsTrimmedWithoutChangingItsIdentity() {
        assertEquals("Audiopolka", playbackSourceOrNull("  Audiopolka  "))
    }

    @Test
    fun unconstrainedRequestMatchesAnyCandidate() {
        assertTrue(playbackSourceMatches("rutracker", null))
        assertTrue(playbackSourceMatches(null, "unknown"))
    }

    @Test
    fun constrainedRequestRequiresKnownCandidate() {
        assertFalse(playbackSourceMatches(null, "rutracker"))
        assertFalse(playbackSourceMatches("unknown", "rutracker"))
    }

    @Test
    fun constrainedMatchIgnoresCaseAndOuterWhitespace() {
        assertTrue(playbackSourceMatches("  RuTracker ", "rutracker"))
        assertFalse(playbackSourceMatches("audiopolka", "rutracker"))
    }

    @Test
    fun standaloneBookIdProvidesStableSourceIdentity() {
        assertEquals("uknig", standalonePlaybackSourceFromBookId("uknig:12345"))
        assertEquals("audioboo", standalonePlaybackSourceFromBookId("  audioboo:book-7  "))
        assertEquals("knigavuhe", standalonePlaybackSourceFromBookId("knigavuhe:99"))
        assertEquals("rutracker", standalonePlaybackSourceFromBookId("rutracker:topic-1"))
    }

    @Test
    fun canonicalOrMalformedIdsDoNotPretendToBeStandaloneSources() {
        assertEquals(null, standalonePlaybackSourceFromBookId("book-1"))
        assertEquals(null, standalonePlaybackSourceFromBookId("fantlab:123"))
        assertEquals(null, standalonePlaybackSourceFromBookId("uknig:"))
        assertEquals(null, standalonePlaybackSourceFromBookId(":123"))
    }
}
