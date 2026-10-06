package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerTimelineInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dragCommitsDraggedPosition() {
        var committedPositionMs = -1L

        composeRule.setContent {
            MaterialTheme {
                PlayerTimeline(
                    positionMs = 1_000L,
                    durationMs = 10_000L,
                    onSeekTo = { committedPositionMs = it },
                )
            }
        }

        composeRule
            .onNodeWithTag(PLAYER_TIMELINE_TEST_TAG)
            .performTouchInput {
                swipe(
                    start = Offset(width * 0.1f, center.y),
                    end = Offset(width * 0.8f, center.y),
                    durationMillis = 300L,
                )
            }

        composeRule.runOnIdle {
            assertTrue(
                "drag should commit the touched timeline position, was $committedPositionMs",
                committedPositionMs in 6_000L..9_500L,
            )
        }
    }

    @Test
    fun externalPositionUpdateBecomesNextDragBaseline() {
        var committedPositionMs = -1L
        var positionMs by mutableLongStateOf(1_000L)

        composeRule.setContent {
            MaterialTheme {
                PlayerTimeline(
                    positionMs = positionMs,
                    durationMs = 10_000L,
                    onSeekTo = { committedPositionMs = it },
                )
            }
        }

        composeRule.runOnIdle {
            positionMs = 7_000L
        }

        composeRule
            .onNodeWithTag(PLAYER_TIMELINE_TEST_TAG)
            .performTouchInput {
                swipe(
                    start = Offset(width * 0.7f, center.y),
                    end = Offset(width * 0.9f, center.y),
                    durationMillis = 250L,
                )
            }

        composeRule.runOnIdle {
            assertTrue(
                "updated playback position should reset slider state before the next drag, was $committedPositionMs",
                committedPositionMs in 7_500L..10_000L,
            )
        }
    }
}
