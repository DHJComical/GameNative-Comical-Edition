package app.gamenative.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppMenuRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun commandUsesButtonRoleWithoutSelectionState() {
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = COMMAND_LABEL,
                    behavior = AppMenuRowBehavior.Command(onClick = {}),
                )
            }
        }

        val node = composeRule.onNodeWithText(COMMAND_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
    }

    @Test
    fun radioExposesRoleAndBusinessSelectionThenInvokesCallback() {
        var clicked = false
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = RADIO_LABEL,
                    behavior = AppMenuRowBehavior.Radio(
                        selected = true,
                        onClick = { clicked = true },
                    ),
                )
            }
        }

        composeRule.onNodeWithText(RADIO_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()

        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun checkboxExposesRoleAndCheckedStateThenInvokesCallback() {
        var requestedState: Boolean? = null
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = CHECKBOX_LABEL,
                    behavior = AppMenuRowBehavior.Checkbox(
                        checked = true,
                        onCheckedChange = { requestedState = it },
                    ),
                )
            }
        }

        val node = composeRule.onNodeWithText(CHECKBOX_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

        assertFalse(node.fetchSemanticsNode().config.contains(SemanticsProperties.Selected))

        node.performClick()
        composeRule.runOnIdle { assertFalse(checkNotNull(requestedState)) }
    }

    @Test
    fun rowProvidesMinimumInteractionHeight() {
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = COMMAND_LABEL,
                    behavior = AppMenuRowBehavior.Command(onClick = {}),
                )
            }
        }

        val minimumHeight = with(composeRule.density) { MINIMUM_HEIGHT.toPx() }

        assertTrue(composeRule.onNodeWithText(COMMAND_LABEL).bounds().height >= minimumHeight)
    }

    @Test
    fun toggleUsesSwitchRoleAndCheckedStateWithoutSelectionState() {
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = TOGGLE_LABEL,
                    behavior = AppMenuRowBehavior.Toggle(
                        checked = true,
                        onCheckedChange = {},
                    ),
                )
            }
        }

        val node = composeRule.onNodeWithText(TOGGLE_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
    }

    @Test
    fun contentDoesNotInventClickOrSelectionSemantics() {
        composeRule.setContent {
            PluviaTheme {
                AppMenuRow(
                    headline = CONTENT_LABEL,
                    behavior = AppMenuRowBehavior.Content(),
                )
            }
        }

        val config = composeRule.onNodeWithText(CONTENT_LABEL).fetchSemanticsNode().config

        assertFalse(config.contains(SemanticsActions.OnClick))
        assertFalse(config.contains(SemanticsProperties.Selected))
        assertFalse(config.contains(SemanticsProperties.ToggleableState))
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private companion object {
        const val COMMAND_LABEL = "Open settings"
        const val RADIO_LABEL = "Online"
        const val CHECKBOX_LABEL = "Games"
        const val TOGGLE_LABEL = "Show hidden games"
        const val CONTENT_LABEL = "Adjust value"
        val MINIMUM_HEIGHT = 48.dp
    }
}
