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
import app.gamenative.ui.screen.library.HomeLibraryScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.theme.PluviaTheme
import com.materialkolor.PaletteStyle

private const val HOME_PAGE_TRANSITION_DURATION_MS = 360

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onChat: (Long) -> Unit,
    onClickExit: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onLogout: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onGoOnline: () -> Unit,
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    isOffline: Boolean = false,
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        HomeLibraryScreen(
            onClickPlay = onClickPlay,
            onTestGraphics = onTestGraphics,
            onPlayWithDiagnostics = onPlayWithDiagnostics,
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
            isOffline = isOffline,
        )

        AnimatedVisibility(
            visible = homeState.currentDestination == HomeDestination.Downloads,
            enter = slideInHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            exit = slideOutHorizontally(tween(HOME_PAGE_TRANSITION_DURATION_MS)) { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeDownloadsScreen(
                onBack = { viewModel.onDestination(HomeDestination.Library) },
                onClickPlay = onClickPlay,
                onTestGraphics = onTestGraphics,
                onPlayWithDiagnostics = onPlayWithDiagnostics,
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

    // Register after the layered pages so this takes priority over handlers in the retained Library UI.
    BackHandler {
        if (homeState.currentDestination != HomeDestination.Library) {
            viewModel.onDestination(HomeDestination.Library)
        } else {
            onClickExit()
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
