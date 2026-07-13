package app.gamenative.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A stable key and visible label for one application tab. */
data class AppTabItem<T>(
    val key: T,
    val label: String,
)

/** Visual density variants for full-screen and space-constrained tab rows. */
enum class AppTabDensity {
    Compact,
    Standard,
}

/** Available tab distribution strategies. */
enum class AppTabLayout {
    Scrollable,
    EqualWidth,
}

/**
 * Shared pill-style application tab row.
 *
 * Each tab owns exactly the visible track height for both layout and input. A single indicator
 * moves behind the stable tab labels, while the clipped track prevents scrolling content from
 * painting outside its rounded ends.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun <T> AppTabRow(
    items: List<AppTabItem<T>>,
    selectedKey: T,
    onTabSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    density: AppTabDensity = AppTabDensity.Standard,
    layout: AppTabLayout = AppTabLayout.Scrollable,
    focusRequesters: Map<T, FocusRequester> = emptyMap(),
) {
    val horizontalPadding = when (density) {
        AppTabDensity.Compact -> AppTabTokens.COMPACT_HORIZONTAL_PADDING
        AppTabDensity.Standard -> AppTabTokens.STANDARD_HORIZONTAL_PADDING
    }
    val spacing = when (density) {
        AppTabDensity.Compact -> AppTabTokens.COMPACT_SPACING
        AppTabDensity.Standard -> AppTabTokens.STANDARD_SPACING
    }
    val trackHeight = when (density) {
        AppTabDensity.Compact -> AppTabDimensions.COMPACT_TRACK_HEIGHT
        AppTabDensity.Standard -> AppTabDimensions.STANDARD_TRACK_HEIGHT
    }
    val indicatorHeight = when (density) {
        AppTabDensity.Compact -> AppTabDimensions.COMPACT_INDICATOR_HEIGHT
        AppTabDensity.Standard -> AppTabDimensions.STANDARD_INDICATOR_HEIGHT
    }
    val tabBounds = remember(items) { mutableStateMapOf<T, AppTabBounds>() }
    val indicatorMotion = remember { AppTabIndicatorMotion() }
    val selectedBounds = tabBounds[selectedKey]
    val indicatorInsetPx = with(LocalDensity.current) { AppTabTokens.INDICATOR_INSET.toPx() }

    LaunchedEffect(selectedKey, selectedBounds, indicatorInsetPx) {
        selectedBounds?.let { indicatorMotion.moveTo(selectedKey, it, indicatorInsetPx) }
    }

    val trackModifier = modifier
        .height(trackHeight)
        .testTag(APP_TAB_TRACK_TEST_TAG)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)

    when (layout) {
        AppTabLayout.Scrollable -> {
            val scrollState = rememberScrollState()
            Box(modifier = trackModifier) {
                Box(
                    modifier = Modifier
                        .testTag(APP_TAB_SCROLL_CONTAINER_TEST_TAG)
                        .horizontalScroll(scrollState),
                ) {
                    AppTabContent(
                        items = items,
                        selectedKey = selectedKey,
                        onTabSelected = onTabSelected,
                        density = density,
                        horizontalPadding = horizontalPadding,
                        spacing = spacing,
                        focusRequesters = focusRequesters,
                        tabBounds = tabBounds,
                        indicatorMotion = indicatorMotion,
                        indicatorHeight = indicatorHeight,
                        bringSelectedIntoView = true,
                    )
                }
            }
        }

        AppTabLayout.EqualWidth -> Box(modifier = trackModifier) {
            AppTabContent(
                items = items,
                selectedKey = selectedKey,
                onTabSelected = onTabSelected,
                density = density,
                horizontalPadding = horizontalPadding,
                spacing = spacing,
                focusRequesters = focusRequesters,
                tabBounds = tabBounds,
                indicatorMotion = indicatorMotion,
                indicatorHeight = indicatorHeight,
                bringSelectedIntoView = false,
                equalWidth = true,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun <T> AppTabContent(
    items: List<AppTabItem<T>>,
    selectedKey: T,
    onTabSelected: (T) -> Unit,
    density: AppTabDensity,
    horizontalPadding: Dp,
    spacing: Dp,
    focusRequesters: Map<T, FocusRequester>,
    tabBounds: MutableMap<T, AppTabBounds>,
    indicatorMotion: AppTabIndicatorMotion,
    indicatorHeight: Dp,
    bringSelectedIntoView: Boolean,
    equalWidth: Boolean = false,
) {
    Box(modifier = if (equalWidth) Modifier.fillMaxWidth() else Modifier) {
        AppTabIndicator(indicatorMotion, indicatorHeight)
        Row(
            modifier = (if (equalWidth) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                AppTab(
                    item = item,
                    selected = item.key == selectedKey,
                    onClick = { onTabSelected(item.key) },
                    horizontalPadding = horizontalPadding,
                    density = density,
                    focusRequester = focusRequesters[item.key],
                    bringSelectedIntoView = bringSelectedIntoView,
                    modifier = (if (equalWidth) Modifier.weight(1f) else Modifier)
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInParent()
                            val updated = AppTabBounds(position.x, coordinates.size.width.toFloat())
                            if (tabBounds[item.key] != updated) {
                                tabBounds[item.key] = updated
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun AppTabIndicator(motion: AppTabIndicatorMotion, height: Dp) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = motion.x.value.roundToInt(),
                    y = AppTabTokens.INDICATOR_INSET.roundToPx(),
                )
            }
            .width(
                with(density) {
                    motion.width.value.toDp()
                },
            )
            .height(height)
            .testTag(APP_TAB_SELECTED_INDICATOR_TEST_TAG)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun <T> AppTab(
    item: AppTabItem<T>,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp,
    density: AppTabDensity,
    focusRequester: FocusRequester?,
    bringSelectedIntoView: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bringIntoViewRequester = remember(item.key) { BringIntoViewRequester() }
    val focused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(selected, bringSelectedIntoView) {
        if (selected && bringSelectedIntoView) {
            bringIntoViewRequester.bringIntoView()
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {}
            .rejectTouchesOutsideLayoutBounds()
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppTabTokens.INDICATOR_INSET)
                .then(if (focused) Modifier.testTag(APP_TAB_FOCUSED_PILL_TEST_TAG) else Modifier)
                .clip(CircleShape)
                .background(
                    if (focused && !selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                )
                .focusRing(interactionSource, CircleShape, width = AppTabTokens.FOCUS_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.label,
                modifier = Modifier.padding(horizontal = horizontalPadding),
                style = when (density) {
                    AppTabDensity.Compact -> MaterialTheme.typography.labelMedium
                    AppTabDensity.Standard -> MaterialTheme.typography.labelLarge
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    focused -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Modifier.rejectTouchesOutsideLayoutBounds(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            val rejectedPointers = mutableSetOf<Long>()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    val position = change.position
                    if (
                        change.changedToDownIgnoreConsumed() &&
                        (
                            position.x < 0f ||
                                position.x >= size.width ||
                                position.y < 0f ||
                                position.y >= size.height
                            )
                    ) {
                        rejectedPointers += change.id.value
                    }
                    if (change.id.value in rejectedPointers) {
                        change.consume()
                    }
                    if (!change.pressed) {
                        rejectedPointers -= change.id.value
                    }
                }
            }
        }
    }

private data class AppTabBounds(
    val x: Float,
    val width: Float,
)

private class AppTabIndicatorMotion {
    val x = Animatable(0f)
    val width = Animatable(0f)
    private var currentKey: Any? = null
    private var initialized = false

    suspend fun moveTo(key: Any?, bounds: AppTabBounds, inset: Float) {
        val densityIndependentTarget = bounds.copy(
            x = bounds.x + inset,
            width = (bounds.width - inset * 2f).coerceAtLeast(0f),
        )
        val keyChanged = initialized && currentKey != key
        currentKey = key

        if (!initialized || !keyChanged) {
            initialized = true
            x.snapTo(densityIndependentTarget.x)
            width.snapTo(densityIndependentTarget.width)
            return
        }

        coroutineScope {
            launch {
                x.animateTo(
                    targetValue = densityIndependentTarget.x,
                    animationSpec = tween(AppTabTokens.SELECTION_ANIMATION_MILLIS),
                )
            }
            launch {
                width.animateTo(
                    targetValue = densityIndependentTarget.width,
                    animationSpec = tween(AppTabTokens.SELECTION_ANIMATION_MILLIS),
                )
            }
        }
    }
}

/** Shared dimensions for tab rows and adjacent actions. */
internal object AppTabDimensions {
    val COMPACT_TRACK_HEIGHT = 36.dp
    val STANDARD_TRACK_HEIGHT = 44.dp
    val COMPACT_INDICATOR_HEIGHT = 30.dp
    val STANDARD_INDICATOR_HEIGHT = 38.dp
}

internal const val APP_TAB_TRACK_TEST_TAG = "app-tab-track"
internal const val APP_TAB_SCROLL_CONTAINER_TEST_TAG = "app-tab-scroll-container"
internal const val APP_TAB_SELECTED_INDICATOR_TEST_TAG = "app-tab-selected-indicator"
internal const val APP_TAB_FOCUSED_PILL_TEST_TAG = "app-tab-focused-pill"

private object AppTabTokens {
    const val SELECTION_ANIMATION_MILLIS = 180
    val INDICATOR_INSET = 3.dp
    val FOCUS_WIDTH = 2.dp
    val COMPACT_HORIZONTAL_PADDING = 10.dp
    val STANDARD_HORIZONTAL_PADDING = 14.dp
    val COMPACT_SPACING = 4.dp
    val STANDARD_SPACING = 8.dp
}
