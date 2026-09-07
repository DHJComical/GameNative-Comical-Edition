package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.BuildConfig
import app.gamenative.R
import app.gamenative.ui.component.AppTabDensity
import app.gamenative.ui.component.AppTabDimensions
import app.gamenative.ui.component.AppTabItem
import app.gamenative.ui.component.AppTabLayout
import app.gamenative.ui.component.AppTabRow
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.WindowWidthClass
import app.gamenative.ui.util.rememberWindowWidthClass

/**
 * Tab bar for library navigation with sliding pill indicator.
 * Adapts to screen width
 */
@Composable
fun LibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    showAddGameButton: Boolean = true,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit = {},
    onNextTab: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val widthClass = rememberWindowWidthClass()

    when (widthClass) {
        WindowWidthClass.COMPACT -> CompactLibraryTabBar(
            currentTab = currentTab,
            tabs = tabs,
            tabCounts = tabCounts,
            onTabSelected = onTabSelected,
            onOptionsClick = onOptionsClick,
            onSearchClick = onSearchClick,
            onAddGameClick = onAddGameClick,
            showAddGameButton = showAddGameButton,
            onMenuClick = onMenuClick,
            onNavigateDownToGrid = onNavigateDownToGrid,
            onPreviousTab = onPreviousTab,
            onNextTab = onNextTab,
            modifier = modifier,
        )

        else -> ExpandedLibraryTabBar(
            currentTab = currentTab,
            tabs = tabs,
            tabCounts = tabCounts,
            onTabSelected = onTabSelected,
            onOptionsClick = onOptionsClick,
            onSearchClick = onSearchClick,
            onAddGameClick = onAddGameClick,
            showAddGameButton = showAddGameButton,
            onMenuClick = onMenuClick,
            onNavigateDownToGrid = onNavigateDownToGrid,
            onPreviousTab = onPreviousTab,
            onNextTab = onNextTab,
            modifier = modifier,
        )
    }
}

/**
 * Compact tab bar for narrow screens.
 * Centered tabs with action buttons for Options, Search, Add Game, and Menu.
 */
@Composable
private fun CompactLibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    showAddGameButton: Boolean,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = tabs.map { tab ->
        AppTabItem(tab, libraryTabLabel(tab, tabCounts[tab]))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(top = 8.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onNavigateDownToGrid()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_L1 -> {
                                onPreviousTab()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_R1 -> {
                                onNextTab()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactIconButton(
                icon = Icons.Default.Tune,
                contentDescription = stringResource(R.string.options),
                onClick = onOptionsClick,
            )

            AppTabRow(
                items = items,
                selectedKey = currentTab,
                onTabSelected = onTabSelected,
                modifier = Modifier

                    .weight(1f, fill = false),
                density = AppTabDensity.Compact,
                layout = AppTabLayout.Scrollable,
            )

            CompactIconButton(
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                onClick = onSearchClick,
            )

            if (showAddGameButton && !BuildConfig.MODERN_ANDROID) {
                CompactIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_game),
                    onClick = onAddGameClick,
                )
            }
            CompactIconButton(
                icon = Icons.Default.Menu,
                contentDescription = stringResource(R.string.menu),
                onClick = onMenuClick,
            )
        }
    }
}

/**
 * Simple icon button for compact tab bar.
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(AppTabDimensions.COMPACT_TRACK_HEIGHT)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            )
            .focusRing(interactionSource, CircleShape, width = 2.dp)
            .selectable(
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Expanded tab bar for wide screens (landscape phone, tablet).
 */
@Composable
private fun ExpandedLibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    showAddGameButton: Boolean,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val items = tabs.map { tab ->
        AppTabItem(tab, libraryTabLabel(tab, tabCounts[tab]))
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(top = 8.dp, bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusGroup()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onNavigateDownToGrid()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_L1 -> {
                                onPreviousTab()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_R1 -> {
                                onNextTab()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconActionButton(
                icon = Icons.Default.Tune,
                contentDescription = stringResource(R.string.options),
                onClick = onOptionsClick,
            )

            AppTabRow(
                items = items,
                selectedKey = currentTab,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .weight(1f, fill = false),
                density = AppTabDensity.Standard,
                layout = AppTabLayout.Scrollable,
            )

            IconActionButton(
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                onClick = onSearchClick,
            )


            if (showAddGameButton && !BuildConfig.MODERN_ANDROID) {
                IconActionButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_game),
                    onClick = onAddGameClick,
                )
            }

            IconActionButton(
                icon = Icons.Default.Menu,
                contentDescription = stringResource(R.string.menu),
                onClick = onMenuClick,
            )
        }
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iconButtonScale",
    )

    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.7f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "iconButtonAlpha",
    )

    Box(
        modifier = modifier
            // Both the focus scale and alpha go in a SINGLE graphicsLayer above the ring. Previously
            // .scale (above) and .alpha (below) were two separate animating layers sandwiching the
            // focusRing draw node, which made the ring flicker off ~100ms after focus. One stable
            // layer wrapping clip/background/focusRing fixes it.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .size(AppTabDimensions.STANDARD_TRACK_HEIGHT)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = if (isFocused) {
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        )
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        )
                    },
                ),
            )
            .focusRing(interactionSource, CircleShape, width = 2.dp)
            .selectable(
                // These are actions, not toggles; feeding isFocused back as `selected` caused an
                // extra recomposition on every focus change.
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun libraryTabLabel(tab: LibraryTab, count: Int?): String =
    if (count != null && count > 0) {
        stringResource(R.string.library_tab_with_count, stringResource(tab.labelResId), count)
    } else {
        stringResource(tab.labelResId)
    }

@Preview(showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun Preview_LibraryTabBar() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            LibraryTabBar(
                currentTab = LibraryTab.ALL,
                tabs = LibraryTab.visibleEntries,
                tabCounts = mapOf(
                    LibraryTab.ALL to 42,
                    LibraryTab.STEAM to 30,
                    LibraryTab.GOG to 8,
                    LibraryTab.EPIC to 4,
                    LibraryTab.LOCAL to 3,
                ),
                onTabSelected = {},
                onOptionsClick = {},
                onSearchClick = {},
                onAddGameClick = {},
                onMenuClick = {},
                onNavigateDownToGrid = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun Preview_LibraryTabBar_Steam() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            LibraryTabBar(
                currentTab = LibraryTab.STEAM,
                tabs = LibraryTab.visibleEntries,
                tabCounts = mapOf(
                    LibraryTab.ALL to 42,
                    LibraryTab.STEAM to 30,
                    LibraryTab.GOG to 8,
                    LibraryTab.EPIC to 4,
                    LibraryTab.LOCAL to 3,
                ),
                onTabSelected = {},
                onOptionsClick = {},
                onSearchClick = {},
                onAddGameClick = {},
                onMenuClick = {},
                onNavigateDownToGrid = {},
            )
        }
    }
}
