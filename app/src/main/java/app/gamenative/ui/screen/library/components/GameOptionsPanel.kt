package app.gamenative.ui.screen.library.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.AppMenuRow
import app.gamenative.ui.component.AppMenuRowBehavior
import app.gamenative.ui.component.AppMenuRowVariant
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.util.adaptivePanelWidth
import timber.log.Timber

@Composable
fun GameOptionsPanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    options: List<AppMenuOption>,
    modifier: Modifier = Modifier,
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            try {
                firstItemFocusRequester.requestFocus()
            } catch (error: IllegalStateException) {
                Timber.w(error, "Game Options initial focus target is not ready")
            }
        }
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeIn(),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
        ) + fadeOut(),
        modifier = modifier
            .fillMaxHeight()
            .width(adaptivePanelWidth(360.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .padding(vertical = 24.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup(),
        ) {
            Text(
                text = stringResource(R.string.game_options_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            val groupedOptionsByCategory = groupOptions(options)

            // Map category keys to localized strings
            val categoryLabels = mapOf(
                OptionCategory.QUICK_ACTIONS to stringResource(R.string.game_options_quick_actions),
                OptionCategory.GAME_MANAGEMENT to stringResource(R.string.game_options_game_management),
                OptionCategory.CONTAINER to stringResource(R.string.game_options_container),
                OptionCategory.CLOUD_SAVES to stringResource(R.string.game_options_cloud_saves),
                OptionCategory.HELP_INFO to stringResource(R.string.game_options_help_info),
            )

            var isFirstItem = true
            groupedOptionsByCategory.forEach { (category, categoryOptions) ->
                if (categoryOptions.isNotEmpty()) {
                    OptionSection(
                        title = categoryLabels[category] ?: category.name,
                        options = categoryOptions,
                        onOptionClick = { option ->
                            option.onClick()
                            onDismiss()
                        },
                        firstItemFocusRequester = if (isFirstItem) firstItemFocusRequester else null,
                    )
                    isFirstItem = false
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private enum class OptionCategory {
    QUICK_ACTIONS,
    GAME_MANAGEMENT,
    CONTAINER,
    CLOUD_SAVES,
    HELP_INFO,
}

@Composable
private fun OptionSection(
    title: String,
    options: List<AppMenuOption>,
    onOptionClick: (AppMenuOption) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        options.forEachIndexed { index, option ->
            OptionItem(
                option = option,
                onClick = { onOptionClick(option) },
                focusRequester = if (index == 0) firstItemFocusRequester else null,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun OptionItem(
    option: AppMenuOption,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val isDestructive = option.optionType == AppOptionMenuType.Uninstall ||
        option.optionType == AppOptionMenuType.ResetToDefaults ||
        option.optionType == AppOptionMenuType.ResetDrm

    AppMenuRow(
        headline = stringResource(option.optionType.title),
        behavior = AppMenuRowBehavior.Command(onClick),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        icon = getIconForOption(option.optionType),
        variant = if (isDestructive) {
            AppMenuRowVariant.Destructive
        } else {
            AppMenuRowVariant.Default
        },
        focusRequester = focusRequester,
    )
}

private fun getIconForOption(type: AppOptionMenuType): ImageVector {
    return when (type) {
        AppOptionMenuType.StorePage -> Icons.AutoMirrored.Filled.OpenInNew
        AppOptionMenuType.CreateShortcut -> Icons.AutoMirrored.Filled.AddToHomeScreen
        AppOptionMenuType.ExportFrontend -> Icons.Default.Share
        AppOptionMenuType.RunContainer -> Icons.Default.PlayArrow
        AppOptionMenuType.EditContainer -> Icons.Default.Settings
        AppOptionMenuType.ResetToDefaults -> Icons.Default.RestartAlt
        AppOptionMenuType.GetSupport -> Icons.AutoMirrored.Filled.Help
        AppOptionMenuType.SubmitFeedback -> Icons.Default.Feedback
        AppOptionMenuType.ResetDrm -> Icons.Default.Key
        AppOptionMenuType.UseKnownConfig -> Icons.Default.Build
        AppOptionMenuType.Uninstall -> Icons.Default.Delete
        AppOptionMenuType.VerifyFiles -> Icons.Default.VerifiedUser
        AppOptionMenuType.Update -> Icons.Default.Update
        AppOptionMenuType.MoveToExternalStorage -> Icons.Default.SdStorage
        AppOptionMenuType.MoveToInternalStorage -> Icons.Default.Storage
        AppOptionMenuType.ForceCloudSync -> Icons.Default.Sync
        AppOptionMenuType.BrowseOnlineSaves -> Icons.AutoMirrored.Filled.OpenInNew
        AppOptionMenuType.ForceDownloadRemote -> Icons.Default.CloudDownload
        AppOptionMenuType.ForceUploadLocal -> Icons.Default.CloudUpload
        AppOptionMenuType.FetchSteamGridDBImages -> Icons.Default.Image
        AppOptionMenuType.TestGraphics -> Icons.Default.Build
        AppOptionMenuType.PlayWithDiagnostics -> Icons.Default.BugReport
        AppOptionMenuType.ShareDiagnostics -> Icons.Default.Share
        AppOptionMenuType.ImportConfig -> Icons.Default.ArrowDownward
        AppOptionMenuType.ExportConfig -> Icons.Default.ArrowUpward
        AppOptionMenuType.ImportSaves -> Icons.Default.ArrowDownward
        AppOptionMenuType.ExportSaves -> Icons.Default.ArrowUpward
        AppOptionMenuType.ManageGameContent -> Icons.Default.Apps
        AppOptionMenuType.ManageWorkshop -> Icons.Default.Build
        AppOptionMenuType.ChangeBranch -> Icons.AutoMirrored.Filled.CallSplit
    }
}

private fun groupOptions(options: List<AppMenuOption>): Map<OptionCategory, List<AppMenuOption>> {
    val quickActions = mutableListOf<AppMenuOption>()
    val gameManagement = mutableListOf<AppMenuOption>()
    val containerSettings = mutableListOf<AppMenuOption>()
    val cloudSaves = mutableListOf<AppMenuOption>()
    val helpInfo = mutableListOf<AppMenuOption>()

    options.forEach { option ->
        when (option.optionType) {
            // Quick Actions
            AppOptionMenuType.EditContainer,
            AppOptionMenuType.RunContainer,
            AppOptionMenuType.CreateShortcut,
            AppOptionMenuType.ExportFrontend,
            -> quickActions.add(option)

            // Game Management
            AppOptionMenuType.Uninstall,
            AppOptionMenuType.VerifyFiles,
            AppOptionMenuType.Update,
            AppOptionMenuType.MoveToExternalStorage,
            AppOptionMenuType.MoveToInternalStorage,
            AppOptionMenuType.ChangeBranch,
            -> gameManagement.add(option)

            // Container Settings
            AppOptionMenuType.ResetToDefaults,
            AppOptionMenuType.ResetDrm,
            AppOptionMenuType.UseKnownConfig,
            AppOptionMenuType.ImportConfig,
            AppOptionMenuType.ExportConfig,
            AppOptionMenuType.ImportSaves,
            AppOptionMenuType.ExportSaves,
            -> containerSettings.add(option)

            // Cloud Saves
            AppOptionMenuType.ForceCloudSync,
            AppOptionMenuType.BrowseOnlineSaves,
            AppOptionMenuType.ForceDownloadRemote,
            AppOptionMenuType.ForceUploadLocal,
            -> cloudSaves.add(option)

            // Help & Info
            AppOptionMenuType.StorePage,
            AppOptionMenuType.GetSupport,
            AppOptionMenuType.SubmitFeedback,
            AppOptionMenuType.FetchSteamGridDBImages,
            AppOptionMenuType.TestGraphics,
            AppOptionMenuType.PlayWithDiagnostics,
            AppOptionMenuType.ShareDiagnostics,
            AppOptionMenuType.ManageGameContent,
            AppOptionMenuType.ManageWorkshop
            -> helpInfo.add(option)
        }
    }

    return linkedMapOf(
        OptionCategory.QUICK_ACTIONS to quickActions,
        OptionCategory.GAME_MANAGEMENT to gameManagement,
        OptionCategory.CONTAINER to containerSettings,
        OptionCategory.CLOUD_SAVES to cloudSaves,
        OptionCategory.HELP_INFO to helpInfo,
    )
}
