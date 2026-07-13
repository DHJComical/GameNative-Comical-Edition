package app.gamenative.ui.screen.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryUserInteractionInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pointerPress_reportsInteractionWithoutConsumingChildClick() {
        var initialLoadComplete by mutableStateOf(false)
        var interactionCount = 0
        var clickCount = 0

        composeRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .notifyLibraryPointerInteraction {
                        reportLibraryInteractionIfReady(initialLoadComplete) { interactionCount++ }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CLICK_TARGET)
                        .clickable { clickCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(CLICK_TARGET).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(0, interactionCount)
            assertEquals(1, clickCount)
            initialLoadComplete = true
        }

        composeRule.onNodeWithTag(CLICK_TARGET).performTouchInput { click() }
        composeRule.runOnIdle {
            assertTrue(interactionCount > 0)
            assertEquals(2, clickCount)
        }
    }

    private companion object {
        const val CLICK_TARGET = "library_click_target"
    }
}
