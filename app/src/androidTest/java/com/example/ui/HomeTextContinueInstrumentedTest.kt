package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.BookCardDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeTextContinueInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun continueAndOpenUseSeparateActionsAfterRecreation() {
        var reading = 0
        var listening = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ContinueHeroCard(
                    book = BookCardDto("txt_" + "a".repeat(64), "Тестовая книга"),
                    showProgressPercent = true, isTextBook = true,
                    onOpen = { reading++ }, onContinue = { listening++ },
                )
            }
        }
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("Продолжить слушать").performClick()
        compose.runOnIdle { assertEquals(0, reading); assertEquals(1, listening) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Тестовая книга").performClick()
        compose.runOnIdle { assertEquals(1, reading); assertEquals(1, listening) }
    }
}
