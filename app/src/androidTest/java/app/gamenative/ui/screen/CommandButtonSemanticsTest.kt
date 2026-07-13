package app.gamenative.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.screen.downloads.DownloadActionButton
import app.gamenative.ui.screen.downloads.DownloadsBackButton
import app.gamenative.ui.screen.downloads.GameArtworkButton
import app.gamenative.ui.screen.settings.SettingsBackButton
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandButtonSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsBackControlIsAButton() {
        composeRule.setContent {
            PluviaTheme {
                SettingsBackButton(onClick = {})
            }
        }

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }

    @Test
    fun downloadsBackControlIsAButton() {
        composeRule.setContent {
            PluviaTheme {
                DownloadsBackButton(onClick = {})
            }
        }

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }

    @Test
    fun gameArtworkControlIsAButton() {
        composeRule.setContent {
            PluviaTheme {
                GameArtworkButton(
                    imageUrl = "",
                    contentDescription = "Open game",
                    placeholderIcon = Icons.Default.PlayArrow,
                    onClick = {},
                )
            }
        }

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }

    @Test
    fun downloadActionControlIsAButton() {
        composeRule.setContent {
            PluviaTheme {
                DownloadActionButton(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume download",
                    onClick = {},
                )
            }
        }

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }
}
