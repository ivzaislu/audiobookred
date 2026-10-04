package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoMediaLibraryTest {
    @Test
    fun bookMediaIdRoundTripsProviderIdsAndSpaces() {
        val bookId = "bazaknig:123097-poslednij dar/книга"

        assertEquals(
            AndroidAutoPlaybackTarget.Book(bookId),
            parseAndroidAutoPlaybackTarget(androidAutoBookMediaId(bookId)),
        )
    }

    @Test
    fun resumeMediaIdRoundTripsBookAndExactSource() {
        val bookId = "uknig:42/source?book=1"
        val sourceCode = "reader:variant 2/русский"

        assertEquals(
            AndroidAutoPlaybackTarget.Resume(bookId, sourceCode),
            parseAndroidAutoPlaybackTarget(androidAutoResumeMediaId(bookId, sourceCode)),
        )
    }

    @Test
    fun malformedResumeMediaIdIsRejected() {
        assertNull(parseAndroidAutoPlaybackTarget("auto:resume:book-without-source"))
        assertNull(parseAndroidAutoPlaybackTarget("auto:resume::source:reader"))
    }

    @Test
    fun downloadMediaIdRoundTripsOpaqueBookSourceId() {
        val bookSourceId = "live:uknig:42/source?variant=reader 1"

        assertEquals(
            AndroidAutoPlaybackTarget.Download(bookSourceId),
            parseAndroidAutoPlaybackTarget(androidAutoDownloadMediaId(bookSourceId)),
        )
    }

    @Test
    fun browseFoldersAreNeverParsedAsPlaybackTargets() {
        assertNull(parseAndroidAutoPlaybackTarget(ANDROID_AUTO_ROOT_ID))
        assertNull(parseAndroidAutoPlaybackTarget(ANDROID_AUTO_CONTINUE_ID))
        assertNull(parseAndroidAutoPlaybackTarget(ANDROID_AUTO_DOWNLOADS_ID))
        assertNull(parseAndroidAutoPlaybackTarget(ANDROID_AUTO_FAVORITES_ID))
        assertNull(parseAndroidAutoPlaybackTarget(ANDROID_AUTO_HISTORY_ID))
    }
}
