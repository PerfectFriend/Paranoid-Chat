package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.ui.DisableSystemKeyboard
import com.example.ui.InAppKeyboardController
import com.example.ui.LocalParanoidKeyboard
import com.example.ui.ParanoidInAppKeyboard
import com.example.ui.ParanoidKeyboardLayout
import com.example.ui.ParanoidOutlinedTextField
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InAppKeyboardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `system IME is blocked and custom in-app keyboard types and sends`() {
        var systemImeInvoked = false
        var sentMessage = ""
        val controller = InAppKeyboardController()

        composeTestRule.setContent {
            MyApplicationTheme {
                // Outer spy interceptor verifies no text input request ever leaks to OS IME
                InterceptPlatformTextInput(
                    interceptor = { request, nextHandler ->
                        systemImeInvoked = true
                        nextHandler.startInputMethod(request)
                    }
                ) {
                    DisableSystemKeyboard(disable = true) {
                        CompositionLocalProvider(LocalParanoidKeyboard provides controller) {
                            var text by remember { mutableStateOf("") }
                            Column(modifier = Modifier.fillMaxSize()) {
                                ParanoidOutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    fieldId = "test_chat_input",
                                    actionLabel = "SEND",
                                    onAction = {
                                        sentMessage = text
                                        text = ""
                                    },
                                    modifier = Modifier.testTag("test_input")
                                )
                                if (controller.isVisible) {
                                    ParanoidInAppKeyboard(controller = controller)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Focus field by tapping it
        composeTestRule.onNodeWithTag("test_input").performClick()
        composeTestRule.waitForIdle()

        assertFalse("System IME must never be invoked", systemImeInvoked)
        composeTestRule.onNodeWithTag("paranoid_keyboard_container").assertIsDisplayed()

        // Type "Hi" using shift + h + i
        composeTestRule.onNodeWithTag("kb_shift").performClick()
        composeTestRule.onNodeWithTag("kb_key_H").performClick()
        composeTestRule.onNodeWithTag("kb_key_i").performClick()
        composeTestRule.onNodeWithTag("kb_space").performClick()

        // Switch to Russian layout and type "д"
        composeTestRule.onNodeWithTag("kb_lang").performClick()
        composeTestRule.onNodeWithTag("kb_key_д").performClick()

        // Backspace removes "д"
        composeTestRule.onNodeWithTag("kb_backspace").performClick()

        // Switch back to EN and type "1"
        composeTestRule.onNodeWithTag("kb_lang").performClick()
        composeTestRule.onNodeWithTag("kb_key_1").performClick()

        // Trigger SEND action
        composeTestRule.onNodeWithTag("kb_action").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Hi 1", sentMessage)
        assertFalse("System IME must remain blocked after typing", systemImeInvoked)
    }

    @Test
    fun `numeric layout enforces digits only and supports scramble and clear`() {
        val controller = InAppKeyboardController()
        var currentDigits = ""

        composeTestRule.setContent {
            MyApplicationTheme {
                DisableSystemKeyboard(disable = true) {
                    CompositionLocalProvider(LocalParanoidKeyboard provides controller) {
                        var digits by remember { mutableStateOf("") }
                        Column(modifier = Modifier.fillMaxSize()) {
                            ParanoidOutlinedTextField(
                                value = digits,
                                onValueChange = {
                                    digits = it
                                    currentDigits = it
                                },
                                fieldId = "test_numeric_input",
                                preferredLayout = ParanoidKeyboardLayout.NUMERIC,
                                digitsOnly = true,
                                modifier = Modifier.testTag("numeric_input")
                            )
                            if (controller.isVisible) {
                                ParanoidInAppKeyboard(controller = controller)
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("numeric_input").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("kb_key_3").performClick()
        composeTestRule.onNodeWithTag("kb_key_2").performClick()
        assertEquals("32", currentDigits)

        // Toggle scramble mode and verify digits still work
        composeTestRule.onNodeWithTag("kb_scramble").performClick()
        assertTrue(controller.isScrambled)
        composeTestRule.onNodeWithTag("kb_key_6").performClick()
        assertEquals("326", currentDigits)

        // Clear all
        composeTestRule.onNodeWithTag("kb_clear").performClick()
        assertEquals("", currentDigits)

        // Hide keyboard
        composeTestRule.onNodeWithTag("kb_hide").performClick()
        composeTestRule.waitForIdle()
        assertFalse(controller.isVisible)
    }
}
