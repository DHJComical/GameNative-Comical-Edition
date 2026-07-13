package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Tappable action row: an [accentColor]-tinted icon, a title, and the shared [focusRing].
 */
@Composable
fun AccentActionRow(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppMenuRow(
        headline = title,
        behavior = AppMenuRowBehavior.Command(onClick),
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        variant = AppMenuRowVariant.Accent,
        accentColor = accentColor,
    )
}
