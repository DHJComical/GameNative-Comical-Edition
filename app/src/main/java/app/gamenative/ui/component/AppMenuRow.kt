package app.gamenative.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Semantic color treatments shared by application menu rows. */
enum class AppMenuRowVariant {
    Default,
    Accent,
    Destructive,
}

/**
 * Interaction semantics for an application menu row.
 *
 * Commands, radios, checkboxes, and switches expose their matching accessibility role and business
 * state. Keyboard or controller focus never changes or substitutes for that state.
 */
sealed interface AppMenuRowBehavior {
    data class Command(val onClick: () -> Unit) : AppMenuRowBehavior

    data class Radio(
        val selected: Boolean,
        val onClick: () -> Unit,
    ) : AppMenuRowBehavior

    data class Checkbox(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : AppMenuRowBehavior

    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : AppMenuRowBehavior

    /**
     * Layout-only row behavior for callers that own keyboard or controller interaction.
     * [interactionModifier] must not add selection semantics; use [Radio], [Checkbox], or [Toggle]
     * instead.
     */
    data class Content(
        val interactionModifier: Modifier = Modifier,
    ) : AppMenuRowBehavior
}

/**
 * Shared row for commands and genuine selectable menu options.
 *
 * Focus feedback is drawn and optionally scaled without affecting measurement, keeping adjacent
 * rows stable while navigating with a controller. [accentColor] is reserved for commands that are
 * always visually accented. [interactionColor] customizes a default row only while it is focused
 * or active, leaving its idle headline on the normal surface color.
 */
@Composable
fun AppMenuRow(
    headline: String,
    behavior: AppMenuRowBehavior,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    variant: AppMenuRowVariant = AppMenuRowVariant.Default,
    accentColor: Color? = null,
    interactionColor: Color? = null,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    scaleOnFocus: Boolean = true,
) {
    require(icon == null || leading == null) {
        "AppMenuRow accepts either icon or leading, not both"
    }
    require(accentColor == null || variant == AppMenuRowVariant.Accent) {
        "AppMenuRow accentColor is only valid with AppMenuRowVariant.Accent"
    }
    require(variant != AppMenuRowVariant.Accent || behavior is AppMenuRowBehavior.Command) {
        "AppMenuRowVariant.Accent is reserved for accent commands"
    }
    require(interactionColor == null || variant == AppMenuRowVariant.Default) {
        "AppMenuRow interactionColor is only valid with AppMenuRowVariant.Default"
    }

    val focused by interactionSource.collectIsFocusedAsState()
    val active = when (behavior) {
        is AppMenuRowBehavior.Radio -> behavior.selected
        is AppMenuRowBehavior.Checkbox -> behavior.checked
        is AppMenuRowBehavior.Toggle -> behavior.checked
        is AppMenuRowBehavior.Command,
        is AppMenuRowBehavior.Content -> false
    }
    val shape = RoundedCornerShape(AppMenuRowTokens.Radius)
    val resolvedAccentColor = when (variant) {
        AppMenuRowVariant.Default -> interactionColor ?: MaterialTheme.colorScheme.primary
        AppMenuRowVariant.Accent -> accentColor ?: MaterialTheme.colorScheme.primary
        AppMenuRowVariant.Destructive -> MaterialTheme.colorScheme.error
    }
    val enabledAlpha = if (enabled) 1f else AppMenuRowTokens.DisabledAlpha
    val backgroundColor by animateColorAsState(
        targetValue = when {
            active -> resolvedAccentColor.copy(alpha = AppMenuRowTokens.SelectedBackgroundAlpha)
            focused -> resolvedAccentColor.copy(alpha = AppMenuRowTokens.FocusedBackgroundAlpha)
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "appMenuRowBackground",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused && scaleOnFocus) AppMenuRowTokens.FocusedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "appMenuRowScale",
    )
    val usesInteractionContentColor = shouldUseAppMenuRowInteractionContentColor(
        variant = variant,
        focused = focused,
        active = active,
        hasInteractionColor = interactionColor != null,
    )
    val headlineColor = if (usesInteractionContentColor) {
        resolvedAccentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }.copy(alpha = enabledAlpha)
    val secondaryColor = if (usesInteractionContentColor) {
        resolvedAccentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }.copy(alpha = enabledAlpha)

    val focusModifier = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }
    val interactionModifier = when (behavior) {
        is AppMenuRowBehavior.Command -> Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = behavior.onClick,
        )

        is AppMenuRowBehavior.Radio -> Modifier.selectable(
            selected = behavior.selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = interactionSource,
            indication = null,
            onClick = behavior.onClick,
        )

        is AppMenuRowBehavior.Checkbox -> Modifier.toggleable(
            value = behavior.checked,
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = behavior.onCheckedChange,
        )

        is AppMenuRowBehavior.Toggle -> Modifier.toggleable(
            value = behavior.checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = behavior.onCheckedChange,
        )

        is AppMenuRowBehavior.Content -> behavior.interactionModifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppMenuRowTokens.MinimumHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(backgroundColor)
            .focusRing(
                interactionSource = interactionSource,
                shape = shape,
                width = AppMenuRowTokens.FocusRingWidth,
                color = resolvedAccentColor,
            )
            .then(focusModifier)
            .then(interactionModifier)
            .padding(
                horizontal = AppMenuRowTokens.HorizontalPadding,
                vertical = AppMenuRowTokens.VerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null || leading != null) {
            CompositionLocalProvider(LocalContentColor provides secondaryColor) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(AppMenuRowTokens.IconSize),
                    )
                } else {
                    leading?.invoke()
                }
            }
            Spacer(modifier = Modifier.width(AppMenuRowTokens.ContentGap))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                color = headlineColor,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor,
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(AppMenuRowTokens.ContentGap))
            CompositionLocalProvider(LocalContentColor provides secondaryColor) {
                trailing()
            }
        }
    }
}

internal fun shouldUseAppMenuRowInteractionContentColor(
    variant: AppMenuRowVariant,
    focused: Boolean,
    active: Boolean,
    hasInteractionColor: Boolean,
): Boolean = when (variant) {
    AppMenuRowVariant.Accent,
    AppMenuRowVariant.Destructive -> true
    AppMenuRowVariant.Default -> active || (focused && hasInteractionColor)
}

private object AppMenuRowTokens {
    val Radius = 12.dp
    val HorizontalPadding = 16.dp
    val VerticalPadding = 10.dp
    val IconSize = 22.dp
    val ContentGap = 12.dp
    val MinimumHeight = 48.dp
    val FocusRingWidth = 2.dp
    const val FocusedScale = 1.02f
    const val DisabledAlpha = 0.38f
    const val SelectedBackgroundAlpha = 0.12f
    const val FocusedBackgroundAlpha = 0.14f
}
