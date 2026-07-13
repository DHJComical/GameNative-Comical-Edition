package app.gamenative.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
    fun compactTabTouchBoundsMatchTrackHeight() {
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
        val track = composeRule.onNodeWithTag(APP_TAB_TRACK_TEST_TAG).bounds()
        val expectedHeight = with(composeRule.density) {
            AppTabDimensions.COMPACT_TRACK_HEIGHT.toPx()
        }

        assertEquals(expectedHeight, track.height, POSITION_TOLERANCE)
        assertEquals(track.height, tab.bounds().height, POSITION_TOLERANCE)
    }

    @Test
    fun standardTabTouchBoundsMatchTrackHeight() {
        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = FIRST_KEY,
                    onTabSelected = {},
                    density = AppTabDensity.Standard,
                )
            }
        }

        val track = composeRule.onNodeWithTag(APP_TAB_TRACK_TEST_TAG).bounds()
        val tab = composeRule.onNodeWithText(FIRST_LABEL).bounds()
        val expectedHeight = with(composeRule.density) {
            AppTabDimensions.STANDARD_TRACK_HEIGHT.toPx()
        }

        assertEquals(expectedHeight, track.height, POSITION_TOLERANCE)
        assertEquals(track.height, tab.height, POSITION_TOLERANCE)
    }

    @Test
    fun touchImmediatelyOutsideTrackDoesNotSelectTab() {
        var clickCount by mutableStateOf(0)

        composeRule.setContent {
            Box(modifier = Modifier.padding(OUTSIDE_TOUCH_TEST_PADDING)) {
                PluviaTheme {
                    AppTabRow(
                        items = ITEMS,
                        selectedKey = FIRST_KEY,
                        onTabSelected = { clickCount++ },
                        density = AppTabDensity.Compact,
                    )
                }
            }
        }

        val track = composeRule.onNodeWithTag(APP_TAB_TRACK_TEST_TAG)
        val trackBounds = track.bounds()
        val firstTabBounds = composeRule.onNodeWithText(FIRST_LABEL).bounds()
        val firstTabCenterX = firstTabBounds.center.x - trackBounds.left

        track.performTouchInput {
            click(Offset(firstTabCenterX, TRACK_INSIDE_Y_PX))
        }
        composeRule.runOnIdle { assertEquals(1, clickCount) }

        track.performTouchInput {
            click(Offset(firstTabCenterX, -OUTSIDE_TOUCH_DISTANCE_PX))
        }
        track.performTouchInput {
            click(Offset(firstTabCenterX, height + OUTSIDE_TOUCH_DISTANCE_PX))
        }

        composeRule.runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun selectedIndicatorIsInsetFromEveryTrackEdge() {
        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = listOf(AppTabItem(FIRST_KEY, FIRST_LABEL)),
                    selectedKey = FIRST_KEY,
                    onTabSelected = {},
                    density = AppTabDensity.Compact,
                )
            }
        }

        val track = composeRule.onNodeWithTag(APP_TAB_TRACK_TEST_TAG).bounds()
        val indicator = composeRule.onNodeWithTag(
            testTag = APP_TAB_SELECTED_INDICATOR_TEST_TAG,
            useUnmergedTree = true,
        ).bounds()
        val inset = with(composeRule.density) { INDICATOR_INSET.toPx() }
        val indicatorHeight = with(composeRule.density) {
            AppTabDimensions.COMPACT_INDICATOR_HEIGHT.toPx()
        }

        assertEquals(inset, indicator.left - track.left, POSITION_TOLERANCE)
        assertEquals(inset, indicator.top - track.top, POSITION_TOLERANCE)
        assertEquals(inset, track.right - indicator.right, POSITION_TOLERANCE)
        assertEquals(inset, track.bottom - indicator.bottom, POSITION_TOLERANCE)
        assertEquals(indicatorHeight, indicator.height, POSITION_TOLERANCE)
    }

    @Test
    fun focusedPillMatchesSelectedIndicatorGeometry() {
        val secondFocusRequester = FocusRequester()

        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = FIRST_KEY,
                    onTabSelected = {},
                    focusRequesters = mapOf(SECOND_KEY to secondFocusRequester),
                )
            }
        }

        composeRule.runOnIdle { secondFocusRequester.requestFocus() }
        val focusedPill = composeRule.onNodeWithTag(
            testTag = APP_TAB_FOCUSED_PILL_TEST_TAG,
            useUnmergedTree = true,
        ).bounds()
        val focusedTab = composeRule.onNodeWithText(SECOND_LABEL).bounds()
        val inset = with(composeRule.density) { INDICATOR_INSET.toPx() }

        assertEquals(focusedTab.left + inset, focusedPill.left, POSITION_TOLERANCE)
        assertEquals(focusedTab.right - inset, focusedPill.right, POSITION_TOLERANCE)
        assertEquals(focusedTab.top + inset, focusedPill.top, POSITION_TOLERANCE)
        assertEquals(focusedTab.bottom - inset, focusedPill.bottom, POSITION_TOLERANCE)
    }

    @Test
    fun selectionAnimatesSingleIndicatorToNewTab() {
        var selected by mutableStateOf(FIRST_KEY)

        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = selected,
                    onTabSelected = { selected = it },
                    modifier = Modifier.width(ANIMATION_TRACK_WIDTH),
                    layout = AppTabLayout.Scrollable,
                )
            }
        }
        composeRule.waitForIdle()

        val indicator = composeRule.onNodeWithTag(
            testTag = APP_TAB_SELECTED_INDICATOR_TEST_TAG,
            useUnmergedTree = true,
        )
        val start = indicator.bounds()
        val destinationTab = composeRule.onNodeWithText(SECOND_LABEL).bounds()
        val inset = with(composeRule.density) { INDICATOR_INSET.toPx() }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(SECOND_LABEL).performClick()
        composeRule.mainClock.advanceTimeBy(ANIMATION_HALF_DURATION_MILLIS)
        val middle = indicator.bounds()

        assertTrue(middle.left > start.left)
        assertTrue(middle.left < destinationTab.left + inset)
        assertTrue(middle.width < start.width)
        assertTrue(middle.width > destinationTab.width - inset * 2f)

        composeRule.mainClock.advanceTimeBy(ANIMATION_DURATION_MILLIS)
        val end = indicator.bounds()

        assertEquals(destinationTab.left + inset, end.left, POSITION_TOLERANCE)
        assertEquals(destinationTab.right - inset, end.right, POSITION_TOLERANCE)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun rapidSelectionChangeContinuesFromCurrentIndicatorPosition() {
        var selected by mutableStateOf(FIRST_KEY)

        composeRule.setContent {
            PluviaTheme {
                AppTabRow(
                    items = ITEMS,
                    selectedKey = selected,
                    onTabSelected = { selected = it },
                    modifier = Modifier.width(ANIMATION_TRACK_WIDTH),
                    layout = AppTabLayout.Scrollable,
                )
            }
        }
        composeRule.waitForIdle()

        val indicator = composeRule.onNodeWithTag(
            testTag = APP_TAB_SELECTED_INDICATOR_TEST_TAG,
            useUnmergedTree = true,
        )
        val firstTab = composeRule.onNodeWithText(FIRST_LABEL).bounds()
        val inset = with(composeRule.density) { INDICATOR_INSET.toPx() }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(SECOND_LABEL).performClick()
        composeRule.mainClock.advanceTimeBy(RAPID_SWITCH_DELAY_MILLIS)
        val outboundPosition = indicator.bounds().left

        composeRule.onNodeWithText(FIRST_LABEL).performClick()
        composeRule.mainClock.advanceTimeBy(RAPID_SWITCH_FRAME_MILLIS)
        val returningPosition = indicator.bounds().left
        assertTrue(returningPosition <= outboundPosition)

        composeRule.mainClock.advanceTimeBy(ANIMATION_DURATION_MILLIS)
        val end = indicator.bounds()
        assertEquals(firstTab.left + inset, end.left, POSITION_TOLERANCE)
        assertEquals(firstTab.right - inset, end.right, POSITION_TOLERANCE)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun roundedTrackClipsScrolledIndicatorAcrossCurvedViewportEdges() {
        composeRule.setContent {
            Box(modifier = Modifier.background(CLIP_TEST_OUTER_COLOR)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = CLIP_TEST_INDICATOR_COLOR,
                        surfaceVariant = CLIP_TEST_TRACK_COLOR,
                    ),
                ) {
                    AppTabRow(
                        items = SCROLL_ITEMS,
                        selectedKey = SCROLL_FIRST_KEY,
                        onTabSelected = {},
                        modifier = Modifier.width(SCROLL_VIEWPORT_WIDTH),
                        layout = AppTabLayout.Scrollable,
                    )
                }
            }
        }

        val track = composeRule.onNodeWithTag(APP_TAB_TRACK_TEST_TAG)
        val initialTrackBounds = track.bounds()
        val initialIndicatorBounds = composeRule.onNodeWithTag(
            testTag = APP_TAB_SELECTED_INDICATOR_TEST_TAG,
            useUnmergedTree = true,
        ).unclippedBounds()
        val dragDistance = with(composeRule.density) { CLIP_TEST_DRAG_DISTANCE.toPx() }

        track.performTouchInput {
            val start = Offset(
                x = (initialIndicatorBounds.center.x - initialTrackBounds.left)
                    .coerceIn(dragDistance + 1f, width - 1f),
                y = height / 2f,
            )
            swipe(
                start = start,
                end = start.copy(x = start.x - dragDistance),
                durationMillis = CLIP_TEST_DRAG_DURATION_MILLIS,
            )
        }
        composeRule.waitForIdle()

        val trackBounds = track.bounds()
        val indicatorBounds = composeRule.onNodeWithTag(
            testTag = APP_TAB_SELECTED_INDICATOR_TEST_TAG,
            useUnmergedTree = true,
        ).unclippedBounds()
        assertTrue(indicatorBounds.left < trackBounds.left)
        assertTrue(indicatorBounds.right > trackBounds.left)

        val pixels = track.captureToImage().toPixelMap()
        val outsideSample = with(composeRule.density) {
            CLIP_TEST_OUTSIDE_SAMPLE_X.roundToPx() to CLIP_TEST_SAMPLE_Y.roundToPx()
        }
        val insideSample = with(composeRule.density) {
            CLIP_TEST_INSIDE_SAMPLE_X.roundToPx() to CLIP_TEST_SAMPLE_Y.roundToPx()
        }
        val indicatorLeft = indicatorBounds.left - trackBounds.left
        val indicatorRight = indicatorBounds.right - trackBounds.left
        val indicatorRadius = indicatorBounds.height / 2f

        assertTrue(outsideSample.isOutsideTrackCircle(pixels.width, pixels.height))
        assertTrue(!insideSample.isOutsideTrackCircle(pixels.width, pixels.height))
        listOf(outsideSample, insideSample).forEach { sample ->
            assertTrue(sample.first >= indicatorLeft + indicatorRadius)
            assertTrue(sample.first <= indicatorRight - indicatorRadius)
        }

        assertTrue(
            "Expected indicator at $outsideSample to be clipped by the track curve",
            pixels[outsideSample.first, outsideSample.second].isOuterBackground(),
        )
        assertTrue(
            "Expected partially visible indicator at $insideSample",
            pixels[insideSample.first, insideSample.second].isIndicator(),
        )
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

    @Test
    fun flexibleScrollableTrackWrapsShortContent() {
        composeRule.setContent {
            PluviaTheme {
                Row(modifier = Modifier.width(FLEXIBLE_ROW_WIDTH)) {
                    Box(modifier = Modifier.size(ACTION_SIZE))
                    AppTabRow(
                        items = ITEMS,
                        selectedKey = FIRST_KEY,
                        onTabSelected = {},
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag(TRACK_TAG),
                        density = AppTabDensity.Compact,
                    )
                    Box(modifier = Modifier.size(ACTION_SIZE))
                }
            }
        }

        val trackWidth = composeRule.onNodeWithTag(TRACK_TAG).bounds().width
        val availableWidth = with(composeRule.density) {
            (FLEXIBLE_ROW_WIDTH - ACTION_SIZE * 2).toPx()
        }

        assertTrue(trackWidth < availableWidth)
    }

    @Test
    fun flexibleScrollableTrackConstrainsLongContentAndRemainsScrollable() {
        composeRule.setContent {
            PluviaTheme {
                Row(modifier = Modifier.width(FLEXIBLE_ROW_WIDTH)) {
                    Box(modifier = Modifier.size(ACTION_SIZE))
                    AppTabRow(
                        items = SCROLL_ITEMS,
                        selectedKey = SCROLL_FIRST_KEY,
                        onTabSelected = {},
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag(TRACK_TAG),
                    )
                    Box(modifier = Modifier.size(ACTION_SIZE))
                }
            }
        }

        val track = composeRule.onNodeWithTag(TRACK_TAG)
        val scrollContainer = composeRule.onNodeWithTag(
            testTag = APP_TAB_SCROLL_CONTAINER_TEST_TAG,
            useUnmergedTree = true,
        )
        val availableWidth = with(composeRule.density) {
            (FLEXIBLE_ROW_WIDTH - ACTION_SIZE * 2).toPx()
        }

        assertEquals(availableWidth, track.bounds().width, POSITION_TOLERANCE)
        scrollContainer.assert(
            SemanticsMatcher("has horizontal scroll range") { node ->
                node.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
            },
        )
    }

    private fun SemanticsNodeInteraction.bounds(): Rect =
        fetchSemanticsNode().boundsInRoot

    private fun SemanticsNodeInteraction.unclippedBounds(): Rect =
        fetchSemanticsNode().let { node -> Rect(node.positionInRoot, node.size.toSize()) }

    private fun Pair<Int, Int>.isOutsideTrackCircle(width: Int, height: Int): Boolean {
        val radius = height / 2f
        val centerX = radius
        val centerY = radius
        val deltaX = first + 0.5f - centerX
        val deltaY = second + 0.5f - centerY
        return deltaX * deltaX + deltaY * deltaY > radius * radius
    }

    private fun Color.isOuterBackground(): Boolean =
        red > CLIP_TEST_COLOR_THRESHOLD &&
            green < CLIP_TEST_COLOR_EPSILON &&
            blue > CLIP_TEST_COLOR_THRESHOLD

    private fun Color.isIndicator(): Boolean =
        red < CLIP_TEST_COLOR_EPSILON &&
            green > CLIP_TEST_COLOR_THRESHOLD &&
            blue < CLIP_TEST_COLOR_EPSILON

    private companion object {
        const val FIRST_KEY = "first"
        const val SECOND_KEY = "second"
        const val FIRST_LABEL = "Installed"
        const val SECOND_LABEL = "Steam"
        const val SCROLL_FIRST_KEY = "installed"
        const val SCROLL_LAST_KEY = "local"
        const val POSITION_TOLERANCE = 0.01f
        val FLEXIBLE_ROW_WIDTH = 360.dp
        val ACTION_SIZE = 48.dp
        val SCROLL_VIEWPORT_WIDTH = 220.dp
        val ANIMATION_TRACK_WIDTH = 240.dp
        val INDICATOR_INSET = 3.dp
        const val ANIMATION_HALF_DURATION_MILLIS = 90L
        const val ANIMATION_DURATION_MILLIS = 180L
        const val RAPID_SWITCH_DELAY_MILLIS = 60L
        const val RAPID_SWITCH_FRAME_MILLIS = 16L
        const val TRACK_INSIDE_Y_PX = 1f
        const val OUTSIDE_TOUCH_DISTANCE_PX = 1f
        const val CLIP_TEST_DRAG_DURATION_MILLIS = 300L
        const val CLIP_TEST_COLOR_THRESHOLD = 0.9f
        const val CLIP_TEST_COLOR_EPSILON = 0.1f
        const val TRACK_TAG = "app-tab-track"
        val OUTSIDE_TOUCH_TEST_PADDING = 24.dp
        val CLIP_TEST_DRAG_DISTANCE = 40.dp
        val CLIP_TEST_OUTSIDE_SAMPLE_X = 1.dp
        val CLIP_TEST_INSIDE_SAMPLE_X = 10.dp
        val CLIP_TEST_SAMPLE_Y = 8.dp
        val CLIP_TEST_OUTER_COLOR = Color.Magenta
        val CLIP_TEST_INDICATOR_COLOR = Color.Green
        val CLIP_TEST_TRACK_COLOR = Color.Blue
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
