package app.gamenative.ui.component

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenEffectsPanelSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun adjustmentRowDoesNotExposeFocusAsSelection() {
        composeRule.setContent {
            PluviaTheme {
                ScreenEffectAdjustmentRow(
                    title = ADJUSTMENT_LABEL,
                    valueText = "50%",
                    progress = 0.5f,
                    onDecrease = {},
                    onIncrease = {},
                )
            }
        }

        val node = composeRule.onNodeWithText(ADJUSTMENT_LABEL).fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
    }

    @Test
    fun toggleRowExposesSwitchRoleAndCheckedState() {
        composeRule.setContent {
            PluviaTheme {
                ScreenEffectToggleRow(
                    title = TOGGLE_LABEL,
                    enabled = true,
                    onToggle = {},
                )
            }
        }

        composeRule.onNodeWithText(TOGGLE_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                ),
            )
    }

    @Test
    fun radioRowExposesBusinessSelectionAndHandlesClick() {
        var clickCount = 0
        composeRule.setContent {
            PluviaTheme {
                ScreenEffectRadioRow(
                    title = RADIO_LABEL,
                    selected = true,
                    onSelect = { clickCount++ },
                )
            }
        }

        composeRule.onNodeWithText(RADIO_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clickCount)
        }
    }

    private companion object {
        const val ADJUSTMENT_LABEL = "Brightness"
        const val TOGGLE_LABEL = "Vivid colors"
        const val RADIO_LABEL = "FSR"
    }
}
