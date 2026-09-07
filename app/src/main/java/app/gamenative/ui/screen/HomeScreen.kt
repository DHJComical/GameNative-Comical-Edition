package app.gamenative.ui.screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.ui.enums.HomeDestination
import app.gamenative.enums.AppTheme
import app.gamenative.ui.model.HomeViewModel
import app.gamenative.ui.screen.downloads.HomeDownloadsScreen
import app.gamenative.ui.screen.downloads.DownloadsSection
import app.gamenative.ui.screen.library.HomeLibraryScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.screen.settings.GameLibrariesScreen
import app.gamenative.ui.theme.PluviaTheme
import com.materialkolor.PaletteStyle

private const val HOME_PAGE_TRANSITION_DURATION_MS = 360

internal enum class HomeBackAction {
    NOT_HANDLED,
    CONSUME,
    NAVIGATE_LIBRARY,
    NAVIGATE_STORAGE,
}

internal fun homeBackAction(
    destination: HomeDestination,
    gameLibraryOperationActive: Boolean,
): HomeBackAction = when {
    destination == HomeDestination.Library -> HomeBackAction.NOT_HANDLED
    destination == HomeDestination.GameLibraries && gameLibraryOperationActive -> HomeBackAction.CONSUME
    destination == HomeDestination.GameLibraries -> HomeBackAction.NAVIGATE_STORAGE
    else -> HomeBackAction.NAVIGATE_LIBRARY
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onChat: (Long) -> Unit,
    onClickExit: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onAiDebugRun: (String) -> Unit,
    onLogout: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onGoOnline: () -> Unit,
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    isOffline: Boolean = false,
    isSteamConnected: Boolean = false,
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    var gameLibraryOperationActive by remember { mutableStateOf(false) }

    val backAction = homeBackAction(homeState.currentDestination, gameLibraryOperationActive)
    // Register the destination fallback before child pages so their nested BackHandlers take priority.
    BackHandler(enabled = backAction != HomeBackAction.NOT_HANDLED) {
        when (backAction) {
            HomeBackAction.NOT_HANDLED,
            HomeBackAction.CONSUME,
            -> Unit
            HomeBackAction.NAVIGATE_LIBRARY -> viewModel.onDestination(HomeDestination.Library)
            HomeBackAction.NAVIGATE_STORAGE -> viewModel.onDestination(HomeDestination.Storage)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HomeLibraryScreen(
            isActive = homeState.currentDestination == HomeDestination.Library,
            onExit = onClickExit,
            onClickPlay = onClickPlay,
            onTestGraphics = onTestGraphics,
            onPlayWithDiagnostics = onPlayWithDiagnostics,
            onAiDebugRun = onAiDebugRun,
            onNavigateRoute = { route ->
                if (route == PluviaScreen.Settings.route) {
                    viewModel.onDestination(HomeDestination.Settings)
                } else {
                    onNavigateRoute(route)
                }
            },
            onLogout = onLogout,
            onGoOnline = onGoOnline,
            onDownloadsClick = { viewModel.onDestination(HomeDestination.Downloads) },
            onStorageClick = { viewModel.onDestination(HomeDestination.Storage) },
            isOffline = isOffline,
            isSteamConnected = isSteamConnected,
        )

        AnimatedVisibility(
            visible = homeState.currentDestination == HomeDestination.Downloads,
            enter = slideInHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            exit = slideOutHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeDownloadsScreen(
                section = DownloadsSection.Downloads,
                onBack = { viewModel.onDestination(HomeDestination.Library) },
                onClickPlay = onClickPlay,
                onTestGraphics = onTestGraphics,
                onPlayWithDiagnostics = onPlayWithDiagnostics,
                onAiDebugRun = onAiDebugRun,
            )
        }

        AnimatedVisibility(
            visible = homeState.currentDestination == HomeDestination.Storage ||
                homeState.currentDestination == HomeDestination.GameLibraries,
            enter = slideInHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            exit = slideOutHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeDownloadsScreen(
                section = DownloadsSection.Storage,
                onBack = { viewModel.onDestination(HomeDestination.Library) },
                onClickPlay = onClickPlay,
                onTestGraphics = onTestGraphics,
                onPlayWithDiagnostics = onPlayWithDiagnostics,
                onGameLibrariesClick = { viewModel.onDestination(HomeDestination.GameLibraries) },
                onAiDebugRun = onAiDebugRun,
            )
        }

        AnimatedVisibility(
            visible = homeState.currentDestination == HomeDestination.GameLibraries,
            enter = slideInHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            exit = slideOutHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            GameLibrariesScreen(
                onBack = { viewModel.onDestination(HomeDestination.Storage) },
                onOperationActiveChanged = { gameLibraryOperationActive = it },
            )
        }

        AnimatedVisibility(
            visible = homeState.currentDestination == HomeDestination.Settings,
            enter = slideInHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            exit = slideOutHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            SettingsScreen(
                appTheme = appTheme,
                paletteStyle = paletteStyle,
                onAppTheme = onAppTheme,
                onPaletteStyle = onPaletteStyle,
                onBack = { viewModel.onDestination(HomeDestination.Library) },
            )
        }
    }

}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1080px,height=1920px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_HomeScreenContent() {
    PluviaTheme {
        var destination: HomeDestination by remember {
            mutableStateOf(HomeDestination.Library)
        }
        HomeScreen(
            onChat = {},
            onClickPlay = { _, _ -> },
            onTestGraphics = { },
            onPlayWithDiagnostics = { },
            onAiDebugRun = { },
            onLogout = {},
            onNavigateRoute = {},
            onClickExit = {},
            onGoOnline = {},
            appTheme = AppTheme.AUTO,
            paletteStyle = PaletteStyle.TonalSpot,
            onAppTheme = {},
            onPaletteStyle = {},
        )
    }
}
