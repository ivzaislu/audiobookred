package com.example.data.parser

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksSeries404PolicyTest {
    @Test
    fun providerSeries404IsTreatedAsBrokenSourceCollection() {
        assertTrue(
            isMyAudiobooksSeriesHttp404(
                IOException(
                    "MY-AUDIOBOOKS HTTP 404 for https://my-audiobooks.com/xfsearch/series/master-trav/"
                )
            )
        )
    }

    @Test
    fun otherFailuresAreNotHiddenAsBrokenSeries() {
        assertFalse(
            isMyAudiobooksSeriesHttp404(
                IOException(
                    "MY-AUDIOBOOKS HTTP 500 for https://my-audiobooks.com/xfsearch/series/master-trav/"
                )
            )
        )
        assertFalse(isMyAudiobooksSeriesHttp404(IOException("socket timeout")))
        assertFalse(isMyAudiobooksSeriesHttp404(IllegalStateException("404")))
    }
}
