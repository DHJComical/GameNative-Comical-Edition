package app.gamenative.ui.screen.library.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.R
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameOptionsPanelTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionIsACommandWithoutFocusBackedSelectionState() {
        composeRule.setContent {
            PluviaTheme {
                GameOptionsPanel(
                    isOpen = true,
                    onDismiss = {},
                    options = listOf(AppMenuOption(AppOptionMenuType.EditContainer) {}),
                )
            }
        }

        val node = composeRule.onNodeWithText(string(R.string.option_edit_container))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNode()

        assertFalse(node.config.contains(SemanticsProperties.Selected))
    }

    @Test
    fun destructiveOptionStillRunsActionAndDismissesPanel() {
        var actionCount = 0
        var dismissCount = 0
        composeRule.setContent {
            PluviaTheme {
                GameOptionsPanel(
                    isOpen = true,
                    onDismiss = { dismissCount++ },
                    options = listOf(
                        AppMenuOption(AppOptionMenuType.Uninstall) { actionCount++ },
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.uninstall)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, actionCount)
            assertEquals(1, dismissCount)
        }
    }

    private fun string(resourceId: Int): String = composeRule.activity.getString(resourceId)
}
