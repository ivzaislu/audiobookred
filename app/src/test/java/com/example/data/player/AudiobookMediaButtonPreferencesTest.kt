package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class AudiobookMediaButtonPreferencesTest {
    @Test
    fun backIconsMatchSupportedPlayerSteps() {
        assertEquals(CommandButton.ICON_SKIP_BACK_5, audiobookSeekBackIcon(5))
        assertEquals(CommandButton.ICON_SKIP_BACK_10, audiobookSeekBackIcon(10))
        assertEquals(CommandButton.ICON_SKIP_BACK_15, audiobookSeekBackIcon(15))
        assertEquals(CommandButton.ICON_SKIP_BACK_30, audiobookSeekBackIcon(30))
    }

    @Test
    fun forwardIconsMatchSupportedPlayerStepsAndUseGenericForSixty() {
        assertEquals(CommandButton.ICON_SKIP_FORWARD_10, audiobookSeekForwardIcon(10))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_15, audiobookSeekForwardIcon(15))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, audiobookSeekForwardIcon(30))
        assertEquals(CommandButton.ICON_SKIP_FORWARD, audiobookSeekForwardIcon(60))
    }
}
