package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dpadDownMovesFocusFromEditTextToComposeTarget() {
        val targetFocusRequester = FocusRequester()

        composeRule.setContent {
            PluviaTheme {
                Column {
                    LibrarySearchBar(
                        isVisible = true,
                        searchQuery = "",
                        resultCount = 0,
                        onScrollToTop = {},
                        onSearchQuery = {},
                        onDismiss = {},
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .focusRequester(targetFocusRequester)
                            .focusable()
                            .testTag(COMPOSE_FOCUS_TARGET),
                    )
                }
            }
        }

        lateinit var editText: EditText
        composeRule.waitUntil {
            editText = findEditText(composeRule.activity.window.decorView) ?: return@waitUntil false
            editText.hasFocus()
        }

        composeRule.runOnIdle {
            assertTrue(editText.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        }

        composeRule.onNodeWithTag(COMPOSE_FOCUS_TARGET).assertIsFocused()
    }

    @Test
    fun skeletonBlocksTouchesUntilFullyHidden() {
        val blockerAlpha = mutableFloatStateOf(1f)
        var clickCount = 0

        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { clickCount++ }
                        .testTag(UNDERLYING_TARGET),
                )
                LibraryInputBlocker(
                    alpha = blockerAlpha.floatValue,
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }

        composeRule.onNodeWithTag(UNDERLYING_TARGET).performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(clickCount == 0) }

        composeRule.runOnIdle { blockerAlpha.floatValue = 0.01f }
        composeRule.onNodeWithTag(UNDERLYING_TARGET).performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(clickCount == 0) }

        composeRule.runOnIdle { blockerAlpha.floatValue = 0f }
        composeRule.onNodeWithTag(UNDERLYING_TARGET).performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(clickCount == 1) }
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findEditText(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val COMPOSE_FOCUS_TARGET = "composeFocusTarget"
        const val UNDERLYING_TARGET = "underlyingTarget"
    }
}
