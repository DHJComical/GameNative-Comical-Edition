package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * Selection and focus only affect drawing, so switching tabs cannot move or resize neighboring
 * items. The 40dp visual pill is hosted in a 48dp interaction target for touch and controller use.
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
    val shape = RoundedCornerShape(AppTabTokens.PillRadius)
    val contentPadding = when (density) {
        AppTabDensity.Compact -> AppTabTokens.CompactHorizontalPadding
        AppTabDensity.Standard -> AppTabTokens.StandardHorizontalPadding
    }
    val spacing = when (density) {
        AppTabDensity.Compact -> AppTabTokens.CompactSpacing
        AppTabDensity.Standard -> AppTabTokens.StandardSpacing
    }

    when (layout) {
        AppTabLayout.Scrollable -> Row(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = AppTabTokens.TrackPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                AppTab(
                    item = item,
                    selected = item.key == selectedKey,
                    onClick = { onTabSelected(item.key) },
                    horizontalPadding = contentPadding,
                    density = density,
                    focusRequester = focusRequesters[item.key],
                    bringSelectedIntoView = true,
                    fillPillWidth = false,
                )
            }
        }

        AppTabLayout.EqualWidth -> Row(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .selectableGroup()
                .padding(horizontal = AppTabTokens.TrackPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                AppTab(
                    item = item,
                    selected = item.key == selectedKey,
                    onClick = { onTabSelected(item.key) },
                    horizontalPadding = contentPadding,
                    density = density,
                    focusRequester = focusRequesters[item.key],
                    bringSelectedIntoView = false,
                    fillPillWidth = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun <T> RowScope.AppTab(
    item: AppTabItem<T>,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp,
    density: AppTabDensity,
    focusRequester: FocusRequester?,
    bringSelectedIntoView: Boolean,
    fillPillWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val bringIntoViewRequester = remember(item.key) { BringIntoViewRequester() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppTabTokens.PillRadius)

    LaunchedEffect(selected, bringSelectedIntoView) {
        if (selected && bringSelectedIntoView) {
            bringIntoViewRequester.bringIntoView()
        }
    }

    Box(
        modifier = modifier
            .heightIn(min = AppTabTokens.TouchHeight)
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {}
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
            modifier = (if (fillPillWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = AppTabTokens.PillHeight)
                .clip(shape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    },
                )
                .focusRing(interactionSource, shape, width = AppTabTokens.FocusWidth)
                .padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.label,
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

private object AppTabTokens {
    val TouchHeight = 48.dp
    val PillHeight = 40.dp
    val PillRadius = 20.dp
    val FocusWidth = 2.dp
    val TrackPadding = 4.dp
    val CompactHorizontalPadding = 14.dp
    val StandardHorizontalPadding = 20.dp
    val CompactSpacing = 4.dp
    val StandardSpacing = 8.dp
}
