package com.example.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneSourceRegistryTest {
    @Test
    fun activeSourcesKeepStableCodesAndLabels() {
        assertEquals(
            listOf("audiopolka", "uknig", "audioboo", "knigavuhe", "bazaknig", "myaudiobooks", "audioknigalife", "rutracker"),
            StandaloneSourceRegistry.activeSources.map { it.code },
        )
        assertEquals("Audiopolka", StandaloneSourceRegistry.displayNameOrNull("AUDIOPOLKA"))
        assertEquals("уКниг", StandaloneSourceRegistry.displayNameOrNull(" uknig "))
        assertEquals("Baza-Knig", StandaloneSourceRegistry.displayNameOrNull("bazaknig"))
        assertEquals("MY-AUDIOBOOKS", StandaloneSourceRegistry.displayNameOrNull("myaudiobooks"))
        assertEquals("Audiokniga.Life", StandaloneSourceRegistry.displayNameOrNull("audioknigalife"))
        assertEquals("RuTracker", StandaloneSourceRegistry.displayNameOrNull("rutracker"))
    }


    @Test
    fun activeSourceCodesAreUnique() {
        val codes = StandaloneSourceRegistry.activeSources.map { it.code }
        assertEquals(codes.distinct(), codes)
    }

    @Test
    fun sourceCapabilitiesAreCentralized() {
        assertEquals("audiopolka", StandaloneSourceRegistry.DEFAULT_SOURCE_CODE)
        assertTrue(StandaloneSourceRegistry.supportsGenres("knigavuhe"))
        assertTrue(StandaloneSourceRegistry.supportsSeries("bazaknig"))
        assertTrue(StandaloneSourceRegistry.usesPagedSearch("knigavuhe"))
        assertFalse(StandaloneSourceRegistry.usesPagedSearch("audiopolka"))
        assertTrue(StandaloneSourceRegistry.usesPagedSearch("rutracker"))
        assertTrue(StandaloneSourceRegistry.supportsGenres("rutracker"))
        assertFalse(StandaloneSourceRegistry.supportsSeries("rutracker"))

        // MY-AUDIOBOOKS exposes source-native genre and series routes that are
        // parsed by the same DLE collection path as the regular catalog.
        assertTrue(StandaloneSourceRegistry.isActive("myaudiobooks"))
        assertTrue(StandaloneSourceRegistry.supportsGenres("myaudiobooks"))
        assertTrue(StandaloneSourceRegistry.supportsSeries("myaudiobooks"))
        assertTrue(StandaloneSourceRegistry.supportsGenres("audioknigalife"))
        assertTrue(StandaloneSourceRegistry.supportsSeries("audioknigalife"))
    }

    @Test
    fun rutrackerIsAnActiveSearchSource() {
        assertTrue(StandaloneSourceRegistry.isActive("rutracker"))
    }

    @Test
    fun unknownSourcesAreNotAccidentallyActivated() {
        assertFalse(StandaloneSourceRegistry.supportsGenres("future-source"))
        assertFalse(StandaloneSourceRegistry.supportsSeries("future-source"))
        assertEquals(null, StandaloneSourceRegistry.displayNameOrNull("future-source"))
    }
}
