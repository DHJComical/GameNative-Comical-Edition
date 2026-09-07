package app.gamenative.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun OptionListItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showCheckmark: Boolean = true,
    trailingText: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    AppMenuRow(
        headline = text,
        behavior = AppMenuRowBehavior.Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
        ),
        modifier = modifier,
        icon = icon,
        focusRequester = focusRequester,
        interactionSource = interactionSource,
        trailing = if ((showCheckmark && selected) || trailingText != null) {
            {
                if (trailingText != null) {
                    Text(
                        text = trailingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = if (showCheckmark && selected) 8.dp else 0.dp),
                    )
                }
                if (showCheckmark && selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun OptionRadioItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val interactionSource = remember { MutableInteractionSource() }
    AppMenuRow(
        headline = text,
        behavior = AppMenuRowBehavior.Radio(
            selected = selected,
            onClick = onClick,
        ),
        modifier = modifier,
        leading = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(
                            width = 2.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }

                if (icon != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        focusRequester = focusRequester,
        interactionSource = interactionSource,
    )
}

@Composable
fun OptionSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview_OptionListItem() {
    PluviaTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OptionSectionHeader(text = "Sort By")
                OptionRadioItem(
                    text = "Installed First",
                    selected = true,
                    onClick = {},
                    icon = Icons.Default.Download
                )
                OptionRadioItem(
                    text = "Name (A-Z)",
                    selected = false,
                    onClick = {},
                    icon = Icons.Default.SortByAlpha
                )

                Spacer(modifier = Modifier.height(16.dp))

                OptionSectionHeader(text = "Filter By Type")
                OptionListItem(
                    text = "Games",
                    selected = true,
                    onClick = {},
                )
                OptionListItem(
                    text = "Applications",
                    selected = false,
                    onClick = {},
                )
                OptionListItem(
                    text = "Tools",
                    selected = true,
                    onClick = {},
                )
            }
        }
    }
}
