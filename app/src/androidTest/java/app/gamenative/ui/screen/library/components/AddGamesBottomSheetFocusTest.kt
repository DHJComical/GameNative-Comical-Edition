package app.gamenative.ui.screen.library.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.model.AddGameStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddGamesBottomSheetFocusTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failedOrHiddenFocusWaitsUntilEnabledAndSurvivesTargetRecreation() {
        val showTarget = mutableStateOf(false)
        val focusEnabled = mutableStateOf(true)
        val targetFocusable = mutableStateOf(false)

        composeRule.setContent {
            if (showTarget.value) {
                val focusRequester = remember { FocusRequester() }
                Box(
                    modifier = Modifier
                        .size(if (targetFocusable.value) 81.dp else 80.dp)
                        .initialAddGameFocusTarget(
                            requester = focusRequester,
                            selectedStore = AddGameStore.STEAM,
                            appId = "STEAM_10",
                            userInteracted = false,
                            focusEnabled = focusEnabled.value,
                        )
                        .focusProperties { canFocus = targetFocusable.value }
                        .focusable()
                        .testTag(TARGET_TAG),
                )
            }
        }

        composeRule.onAllNodesWithTag(TARGET_TAG).assertCountEquals(0)

        composeRule.runOnIdle { showTarget.value = true }
        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotFocused()

        composeRule.runOnIdle { targetFocusable.value = true }
        composeRule.onNodeWithTag(TARGET_TAG).assertIsFocused()

        composeRule.runOnIdle {
            focusEnabled.value = false
            showTarget.value = false
        }
        composeRule.onAllNodesWithTag(TARGET_TAG).assertCountEquals(0)

        composeRule.runOnIdle { showTarget.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { focusEnabled.value = true }
        composeRule.onNodeWithTag(TARGET_TAG).assertIsFocused()
    }

    private companion object {
        const val TARGET_TAG = "add-game-first-focus-target"
    }
}
