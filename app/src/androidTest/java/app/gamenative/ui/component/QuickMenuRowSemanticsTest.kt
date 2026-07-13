package app.gamenative.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickMenuRowSemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun adjustmentFocusTargetDoesNotExposeSelectionState() {
        var decreases = 0
        var increases = 0
        composeRule.setContent {
            PluviaTheme {
                QuickMenuAdjustmentRow(
                    title = ADJUSTMENT_TITLE,
                    valueText = "50%",
                    progress = 0.5f,
                    onDecrease = { decreases++ },
                    onIncrease = { increases++ },
                    accentColor = Color.Magenta,
                )
            }
        }

        val node = composeRule.onNodeWithText(ADJUSTMENT_TITLE).fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
        composeRule.onNodeWithText("-").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.runOnIdle {
            assertEquals(1, decreases)
            assertEquals(1, increases)
        }
    }

    @Test
    fun toggleExposesSwitchRoleAndBusinessCheckedState() {
        var toggleCount = 0
        composeRule.setContent {
            PluviaTheme {
                QuickMenuToggleRow(
                    title = TOGGLE_TITLE,
                    enabled = true,
                    onToggle = { toggleCount++ },
                    accentColor = Color.Cyan,
                )
            }
        }

        val toggle = composeRule.onNodeWithText(TOGGLE_TITLE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                ),
            )

        assertFalse(toggle.fetchSemanticsNode().config.contains(SemanticsProperties.Selected))
        toggle.performClick()
        composeRule.runOnIdle { assertEquals(1, toggleCount) }
    }

    @Test
    fun processAndItemRowsAreCommandsWithoutSelectionState() {
        var processCount = 0
        var itemCount = 0
        composeRule.setContent {
            PluviaTheme {
                Column {
                    QuickMenuProcessRow(
                        title = PROCESS_TITLE,
                        subtitle = "Running",
                        accentColor = Color.Red,
                        onEndProcess = { processCount++ },
                    )
                    QuickMenuItemRow(
                        item = QuickMenuItem(
                            id = 1,
                            icon = Icons.Default.Settings,
                            labelResId = R.string.settings_text,
                        ),
                        isActive = true,
                        onClick = { itemCount++ },
                    )
                }
            }
        }

        assertCommandWithoutSelection(PROCESS_TITLE)
        assertCommandWithoutSelection(string(R.string.settings_text))
        composeRule.onNodeWithText(PROCESS_TITLE).performClick()
        composeRule.onNodeWithText(string(R.string.settings_text)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, processCount)
            assertEquals(1, itemCount)
        }
    }

    @Test
    fun tabAndChoiceExposeTheirSpecificSelectionRoles() {
        composeRule.setContent {
            PluviaTheme {
                Column {
                    QuickMenuTabButton(
                        icon = Icons.Default.Settings,
                        contentDescriptionResId = R.string.settings_text,
                        selected = true,
                        accentColor = Color.Magenta,
                        onSelected = {},
                    )
                    QuickMenuChoiceChip(
                        text = CHOICE_LABEL,
                        selected = false,
                        accentColor = Color.Magenta,
                        onClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.settings_text))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithText(CHOICE_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    private fun assertCommandWithoutSelection(text: String) {
        val node = composeRule.onNodeWithText(text)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
    }

    private fun string(resourceId: Int): String = composeRule.activity.getString(resourceId)

    private companion object {
        const val ADJUSTMENT_TITLE = "Sharpness"
        const val TOGGLE_TITLE = "Frame limiter"
        const val PROCESS_TITLE = "wine64-preloader"
        const val CHOICE_LABEL = "Large"
    }
}
