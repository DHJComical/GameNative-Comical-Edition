package app.gamenative.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppTabRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectionDoesNotChangeItemBounds() {
        var selected by mutableStateOf(FIRST_KEY)

        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = selected,
                    onTabSelected = { selected = it },
                    modifier = Modifier.fillMaxWidth(),
                    layout = AppTabLayout.EqualWidth,
                )
            }
        }

        val initialBounds = composeRule.onNodeWithText(SECOND_LABEL).bounds()
        composeRule.onNodeWithText(SECOND_LABEL).performClick()
        composeRule.waitForIdle()
        val selectedBounds = composeRule.onNodeWithText(SECOND_LABEL).bounds()

        assertEquals(initialBounds.left, selectedBounds.left, POSITION_TOLERANCE)
        assertEquals(initialBounds.top, selectedBounds.top, POSITION_TOLERANCE)
        assertEquals(initialBounds.width, selectedBounds.width, POSITION_TOLERANCE)
        assertEquals(initialBounds.height, selectedBounds.height, POSITION_TOLERANCE)
    }

    @Test
    fun tabExposesRoleAndMinimumTouchHeight() {
        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = FIRST_KEY,
                    onTabSelected = {},
                    density = AppTabDensity.Compact,
                )
            }
        }

        val tab = composeRule.onNodeWithText(FIRST_LABEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        val minimumHeight = with(composeRule.density) { MINIMUM_TOUCH_HEIGHT.toPx() }

        assertTrue(tab.bounds().height >= minimumHeight)
    }

    @Test
    fun scrollableSelectionKeepsItemMeasurementsAndRelativePositionsStable() {
        var selected by mutableStateOf(SCROLL_FIRST_KEY)

        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = SCROLL_ITEMS,
                    selectedKey = selected,
                    onTabSelected = { selected = it },
                    modifier = Modifier.width(SCROLL_VIEWPORT_WIDTH),
                    layout = AppTabLayout.Scrollable,
                )
            }
        }

        val initialBounds = SCROLL_ITEMS.associate { item ->
            item.key to composeRule.onNodeWithText(item.label).bounds()
        }
        composeRule.runOnIdle { selected = SCROLL_LAST_KEY }
        composeRule.waitForIdle()
        val selectedBounds = SCROLL_ITEMS.associate { item ->
            item.key to composeRule.onNodeWithText(item.label).bounds()
        }

        SCROLL_ITEMS.forEach { item ->
            val initial = initialBounds.getValue(item.key)
            val afterSelection = selectedBounds.getValue(item.key)
            assertEquals(initial.width, afterSelection.width, POSITION_TOLERANCE)
            assertEquals(initial.height, afterSelection.height, POSITION_TOLERANCE)
        }
        SCROLL_ITEMS.zipWithNext().forEach { (first, second) ->
            val initialSpacing = initialBounds.getValue(second.key).left - initialBounds.getValue(first.key).left
            val selectedSpacing = selectedBounds.getValue(second.key).left - selectedBounds.getValue(first.key).left
            assertEquals(initialSpacing, selectedSpacing, POSITION_TOLERANCE)
        }
    }

    private fun SemanticsNodeInteraction.bounds(): Rect =
        fetchSemanticsNode().boundsInRoot

    private companion object {
        const val FIRST_KEY = "first"
        const val SECOND_KEY = "second"
        const val FIRST_LABEL = "Installed"
        const val SECOND_LABEL = "Steam"
        const val SCROLL_FIRST_KEY = "installed"
        const val SCROLL_LAST_KEY = "local"
        const val POSITION_TOLERANCE = 0.01f
        val MINIMUM_TOUCH_HEIGHT = 48.dp
        val SCROLL_VIEWPORT_WIDTH = 220.dp
        val ITEMS = listOf(
            AppTabItem(FIRST_KEY, FIRST_LABEL),
            AppTabItem(SECOND_KEY, SECOND_LABEL),
        )
        val SCROLL_ITEMS = listOf(
            AppTabItem(SCROLL_FIRST_KEY, "All installed games"),
            AppTabItem("gog", "GOG"),
            AppTabItem(SCROLL_LAST_KEY, "Local folders and executables"),
        )
    }
}
