package app.gamenative.ui.component

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
class OptionRadioItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun radioItemExposesBusinessSelectionAndHandlesClick() {
        var clickCount = 0
        composeRule.setContent {
            PluviaTheme {
                OptionRadioItem(
                    text = RADIO_LABEL,
                    selected = true,
                    onClick = { clickCount++ },
                )
            }
        }

        composeRule.onNodeWithText(RADIO_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clickCount)
        }
    }

    private companion object {
        const val RADIO_LABEL = "Installed first"
    }
}
