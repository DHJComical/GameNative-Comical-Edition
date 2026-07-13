package app.gamenative.ui.screen.library.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemMenuStatusOptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusOptionExposesItsBusinessSelectionState() {
        var clickCount = 0
        composeRule.setContent {
            PluviaTheme {
                SystemMenuStatusOption(
                    text = STATUS_LABEL,
                    statusColor = Color.Green,
                    isSelected = true,
                    onClick = { clickCount++ },
                )
            }
        }

        composeRule.onNodeWithText(STATUS_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clickCount)
        }
    }

    @Test
    fun unselectedStatusOptionDoesNotReportSelected() {
        composeRule.setContent {
            PluviaTheme {
                SystemMenuStatusOption(
                    text = STATUS_LABEL,
                    statusColor = Color.Gray,
                    isSelected = false,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText(STATUS_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    private companion object {
        const val STATUS_LABEL = "Online"
    }
}
