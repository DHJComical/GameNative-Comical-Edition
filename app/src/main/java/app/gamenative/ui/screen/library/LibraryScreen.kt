package app.gamenative.ui.screen.library

import android.content.Intent
import android.content.res.Configuration
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.ui.util.SnackbarManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.enums.LoginResult
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.LibraryActions
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.ui.model.AddGameCatalogState
import app.gamenative.ui.model.AddGameCatalogViewModel
import app.gamenative.ui.model.AddGameStore
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.library.components.LibraryCarouselPane
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.ui.screen.library.components.LibraryOptionsPanel
import app.gamenative.ui.screen.library.components.LibrarySearchBar
import app.gamenative.ui.screen.library.components.LibrarySourceNotLoggedInSplash
import app.gamenative.ui.screen.library.components.LibraryTabBar
import app.gamenative.ui.screen.library.components.AddGamesBottomSheet
import app.gamenative.ui.screen.auth.AmazonOAuthActivity
import app.gamenative.ui.screen.auth.EpicOAuthActivity
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import app.gamenative.ui.screen.library.components.SystemMenu
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.PlatformAuthUiHelpers
import app.gamenative.ui.util.PlatformLogoutCallbacks
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.PlatformOAuthHandlers
import app.gamenative.utils.SteamUtils
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

private const val LIBRARY_ROTARY_INTERACTION_THRESHOLD_PX = 0.5f
internal const val LIBRARY_DETAIL_ENTER_DURATION_MS = 360
internal const val LIBRARY_DETAIL_EXIT_DURATION_MS = 260
internal const val LIBRARY_DETAIL_INITIAL_SCALE = 0.9f
internal const val LIBRARY_DETAIL_INITIAL_CORNER_DP = 14

internal enum class LibraryDetailOrigin {
    MAIN,
    ADD_CATALOG,
}

internal data class LibraryDetailState(
    val item: LibraryItem,
    val origin: LibraryDetailOrigin,
)

internal fun shouldShowAddGameCatalog(
    catalogOpen: Boolean,
    detailState: LibraryDetailState?,
): Boolean = catalogOpen && detailState == null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    addGameCatalogViewModel: AddGameCatalogViewModel = hiltViewModel(),
    isActive: Boolean,
    onExit: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onDownloadsClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    isOffline: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addGameCatalogState by addGameCatalogViewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.appInfoSortType) {
        addGameCatalogViewModel.updateSteamAppTypeFilters(state.appInfoSortType)
    }

    LibraryScreenContent(
        state = state,
        isActive = isActive,
        onExit = onExit,
        listState = viewModel.listState,
        sheetState = sheetState,
        addGameCatalogState = addGameCatalogState,
        onOpenAddGameCatalog = addGameCatalogViewModel::open,
        onCloseAddGameCatalog = addGameCatalogViewModel::close,
        onAddGameStoreSelected = addGameCatalogViewModel::selectStore,
        onRefreshAddGameCatalog = addGameCatalogViewModel::refresh,
        onFilterChanged = viewModel::onFilterChanged,
        onPageChange = viewModel::onPageChange,
        onModalBottomSheet = viewModel::onModalBottomSheet,
        onIsSearching = viewModel::onIsSearching,
        onSearchQuery = viewModel::onSearchQuery,
        onRefresh = viewModel::onRefresh,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onPlayWithDiagnostics = onPlayWithDiagnostics,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
        onGoOnline = onGoOnline,
        onDownloadsClick = onDownloadsClick,
        onStorageClick = onStorageClick,
        onSourceToggle = viewModel::onSourceToggle,
        onAddCustomGameFolder = viewModel::addCustomGameFolder,
        onSortOptionChanged = viewModel::onSortOptionChanged,
        onOptionsPanelToggle = viewModel::onOptionsPanelToggle,
        onTabChanged = viewModel::onTabChanged,
        onPreviousTab = viewModel::onPreviousTab,
        onNextTab = viewModel::onNextTab,
        onLibraryUserInteraction = viewModel::onLibraryUserInteraction,
        isViewportResetPending = viewModel::isViewportResetPending,
        consumeViewportReset = viewModel::consumeViewportReset,
        isOffline = isOffline,
    )
}

private fun isGameControllerConnected(): Boolean =
    InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        val sources = device.sources
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

internal fun Modifier.notifyLibraryPointerInteraction(
    onLibraryUserInteraction: () -> Unit,
): Modifier = pointerInput(onLibraryUserInteraction) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                onLibraryUserInteraction()
            }
        }
    }
}.onRotaryScrollEvent {
    if (isLibraryRotaryMotion(it.verticalScrollPixels, it.horizontalScrollPixels)) {
        onLibraryUserInteraction()
    }
    false
}

internal fun isLibraryKeyDown(action: Int): Boolean = action == KeyEvent.ACTION_DOWN

internal fun reportLibraryInteractionIfReady(
    initialLoadComplete: Boolean,
    onLibraryUserInteraction: () -> Unit,
): Boolean {
    if (!initialLoadComplete) return false
    onLibraryUserInteraction()
    return true
}

internal fun isLibraryRotaryMotion(verticalPixels: Float, horizontalPixels: Float): Boolean =
    abs(verticalPixels) >= LIBRARY_ROTARY_INTERACTION_THRESHOLD_PX ||
        abs(horizontalPixels) >= LIBRARY_ROTARY_INTERACTION_THRESHOLD_PX

internal fun isLibraryGlobalControllerKey(action: Int, keyCode: Int): Boolean =
    isLibraryKeyDown(action) && when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        -> true

        else -> false
    }

internal fun isLibraryRootBackEnabled(
    isActive: Boolean,
    hasDetail: Boolean,
    isAddGameCatalogOpen: Boolean,
    isSearching: Boolean,
    isSystemMenuOpen: Boolean,
    isSystemMenuNavigationTransitionInProgress: Boolean,
    isOptionsPanelOpen: Boolean,
    isDialogOpen: Boolean,
): Boolean = isActive &&
    !hasDetail &&
    !isAddGameCatalogOpen &&
    !isSearching &&
    !isSystemMenuOpen &&
    !isSystemMenuNavigationTransitionInProgress &&
    !isOptionsPanelOpen &&
    !isDialogOpen

internal enum class LibraryPreviewKeyAction {
    IGNORE,
    CONSUME,
    PROCESS,
}

internal fun libraryPreviewKeyAction(
    isActive: Boolean,
    isSystemMenuNavigationTransitionInProgress: Boolean,
    keyAction: Int,
): LibraryPreviewKeyAction = when {
    !isActive -> LibraryPreviewKeyAction.IGNORE
    isSystemMenuNavigationTransitionInProgress && isLibraryKeyDown(keyAction) ->
        LibraryPreviewKeyAction.CONSUME
    isSystemMenuNavigationTransitionInProgress -> LibraryPreviewKeyAction.IGNORE
    else -> LibraryPreviewKeyAction.PROCESS
}

internal fun shouldRestoreLibraryFocus(
    isActive: Boolean,
    isSystemMenuNavigationTransitionInProgress: Boolean,
    systemMenuJustClosed: Boolean,
    optionsPanelJustClosed: Boolean,
    isSearching: Boolean,
): Boolean = isActive &&
    !isSystemMenuNavigationTransitionInProgress &&
    (systemMenuJustClosed || optionsPanelJustClosed) &&
    !isSearching

internal fun isLibraryDirectionalMotion(
    actionMasked: Int,
    hatX: Float,
    hatY: Float,
    leftX: Float,
    leftY: Float,
): Boolean = actionMasked == MotionEvent.ACTION_MOVE && (
    abs(hatX) >= 0.5f ||
        abs(hatY) >= 0.5f ||
        abs(leftX) >= 0.6f ||
        abs(leftY) >= 0.6f
    )

internal fun resetLibraryViewport(
    token: Long,
    isViewportResetPending: (Long) -> Boolean,
    resetFocusTargets: () -> Unit,
    requestGridAtTop: () -> Unit,
    requestCarouselAtTop: () -> Unit,
    consumeViewportReset: (Long) -> Boolean,
): Boolean {
    if (token == 0L || !isViewportResetPending(token)) return false
    resetFocusTargets()
    requestGridAtTop()
    requestCarouselAtTop()
    return consumeViewportReset(token)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    state: LibraryState,
    isActive: Boolean,
    onExit: () -> Unit,
    listState: LazyGridState,
    sheetState: SheetState,
    addGameCatalogState: AddGameCatalogState,
    onOpenAddGameCatalog: () -> Unit,
    onCloseAddGameCatalog: () -> Unit,
    onAddGameStoreSelected: (AddGameStore) -> Unit,
    onRefreshAddGameCatalog: () -> Unit,
    onFilterChanged: (AppFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onDownloadsClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onSourceToggle: (GameSource) -> Unit,
    onAddCustomGameFolder: (String) -> Unit,
    onSortOptionChanged: (SortOption) -> Unit,
    onOptionsPanelToggle: (Boolean) -> Unit,
    onTabChanged: (LibraryTab) -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    onLibraryUserInteraction: () -> Unit,
    isViewportResetPending: (Long) -> Boolean,
    consumeViewportReset: (Long) -> Boolean,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope

    val gogOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleGogAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.gog_login_success_title))
                    onAddGameStoreSelected(AddGameStore.GOG)
                },
                onDialogClose = { },
            )
        }
    }

    val epicOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleEpicAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.epic_login_success_title))
                    onAddGameStoreSelected(AddGameStore.EPIC)
                },
                onDialogClose = { },
            )
        }
    }

    val amazonOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleAmazonAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.amazon_login_success_title))
                    onAddGameStoreSelected(AddGameStore.AMAZON)
                },
                onDialogClose = { },
            )
        }
    }

    val detailScope = rememberCoroutineScope()
    var detailState by remember { mutableStateOf<LibraryDetailState?>(null) }
    var detailVisible by remember { mutableStateOf(false) }
    var detailExitJob by remember { mutableStateOf<Job?>(null) }
    val addGameCatalogGridState = rememberLazyGridState()

    fun openDetail(item: LibraryItem, origin: LibraryDetailOrigin) {
        detailExitJob?.cancel()
        detailState = LibraryDetailState(item = item, origin = origin)
        detailVisible = true
    }

    fun closeDetail() {
        if (!detailVisible) return
        detailVisible = false
        detailExitJob?.cancel()
        detailExitJob = detailScope.launch {
            delay(LIBRARY_DETAIL_EXIT_DURATION_MS.toLong())
            detailState = null
        }
    }
    val carouselListState = rememberLazyListState()
    val isViewWide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var currentPaneType by remember { mutableStateOf(PrefManager.libraryLayout) }

    // Initialize layout if undecided
    LaunchedEffect(Unit) {
        if (currentPaneType == PaneType.UNDECIDED) {
            currentPaneType = if (isViewWide) PaneType.GRID_HERO else PaneType.GRID_CAPSULE
            PrefManager.libraryLayout = currentPaneType
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    val gridFirstItemFocusRequester = remember { FocusRequester() }
    val carouselFocusRequester = remember { FocusRequester() }
    var gridFocusTargetListIndex by remember { mutableIntStateOf(0) }
    var carouselFocusTargetListIndex by remember { mutableIntStateOf(0) }
    var pendingGridFocusRequest by remember { mutableStateOf(false) }
    var pendingCarouselFocusRequest by remember { mutableStateOf(false) }

    var isSystemMenuOpen by remember { mutableStateOf(false) }
    var isSystemMenuNavigationTransitionInProgress by remember { mutableStateOf(false) }
    // Track previous overlay states to detect when they close
    var wasSystemMenuOpen by remember { mutableStateOf(false) }
    var wasOptionsPanelOpen by remember { mutableStateOf(false) }
    val filterFabExpanded by remember(currentPaneType, listState, carouselListState) {
        derivedStateOf {
            if (currentPaneType == PaneType.CAROUSEL) {
                carouselListState.firstVisibleItemIndex == 0
            } else {
                listState.firstVisibleItemIndex == 0
            }
        }
    }

    // Dialog state for add custom game prompt
    var showAddCustomGameDialog by remember { mutableStateOf(false) }
    var dontShowAgain by remember { mutableStateOf(false) }
    var previousAppCount by remember { mutableIntStateOf(state.appInfoList.size) }
    var controllerBootstrapNeeded by remember { mutableStateOf(true) }
    var rootHasFocus by remember { mutableStateOf(false) }
    // True while focus lives in the top tab bar. The delayed focus-restoration effects below must
    // not yank focus back to the grid when the user has moved up into the tab bar (the action
    // buttons would otherwise light up for ~100ms and then lose focus).
    var tabBarHasFocus by remember { mutableStateOf(false) }
    var lastBootstrapAtMs by remember { mutableLongStateOf(0L) }
    var completedViewportResetToken by remember { mutableLongStateOf(0L) }

    fun resetContentFocusTargets() {
        gridFocusTargetListIndex = 0
        carouselFocusTargetListIndex = 0
    }

    fun selectTab(tab: LibraryTab) {
        if (tab != state.currentTab) {
            onLibraryUserInteraction()
            onTabChanged(tab)
        }
    }

    fun firstVisibleContentIndex(): Int {
        val lastIndex = state.appInfoList.lastIndex
        if (lastIndex < 0) return 0

        return if (currentPaneType == PaneType.CAROUSEL) {
            carouselListState.firstVisibleItemIndex.coerceIn(0, lastIndex)
        } else {
            listState.firstVisibleItemIndex.coerceIn(0, lastIndex)
        }
    }

    fun currentCarouselFocusTargetIndex(): Int {
        val lastIndex = state.appInfoList.lastIndex
        if (lastIndex < 0) return 0

        return carouselFocusTargetListIndex.coerceIn(0, lastIndex)
    }

    fun preferredContentFocusIndex(): Int =
        if (currentPaneType == PaneType.CAROUSEL) currentCarouselFocusTargetIndex() else firstVisibleContentIndex()

    val inputModeManager = LocalInputModeManager.current
    fun ensureKeyboardInputMode() {
        if (isGameControllerConnected()) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
    }

    fun requestGridFocusOrDefer() {
        if (state.appInfoList.isEmpty()) return
        ensureKeyboardInputMode()
        try {
            gridFirstItemFocusRequester.requestFocus()
            pendingGridFocusRequest = false
            lastBootstrapAtMs = SystemClock.uptimeMillis()
        } catch (_: IllegalStateException) {
            pendingGridFocusRequest = true
        }
    }

    fun requestCarouselFocusOrDefer(targetListIndex: Int = currentCarouselFocusTargetIndex()) {
        if (state.appInfoList.isEmpty()) return
        ensureKeyboardInputMode()
        carouselFocusTargetListIndex = targetListIndex.coerceIn(0, state.appInfoList.lastIndex)
        try {
            carouselFocusRequester.requestFocus()
            pendingCarouselFocusRequest = false
            lastBootstrapAtMs = SystemClock.uptimeMillis()
        } catch (_: IllegalStateException) {
            pendingCarouselFocusRequest = true
        }
    }

    fun requestContentFocusOrDefer(targetListIndex: Int = preferredContentFocusIndex()) {
        if (state.appInfoList.isEmpty()) return
        if (currentPaneType == PaneType.CAROUSEL) {
            requestCarouselFocusOrDefer(targetListIndex)
        } else {
            gridFocusTargetListIndex = targetListIndex
            requestGridFocusOrDefer()
        }
    }

    fun requestRootFocusSafe() {
        ensureKeyboardInputMode()
        try {
            rootFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {}
    }

    LaunchedEffect(state.viewportResetToken) {
        val token = state.viewportResetToken
        if (token != 0L) {
            val consumed = resetLibraryViewport(
                token = token,
                isViewportResetPending = isViewportResetPending,
                resetFocusTargets = ::resetContentFocusTargets,
                requestGridAtTop = { listState.requestScrollToItem(0) },
                requestCarouselAtTop = { carouselListState.requestScrollToItem(0) },
                consumeViewportReset = consumeViewportReset,
            )
            if (consumed) {
                completedViewportResetToken = token
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    var storeBeforeFolderPicker by remember { mutableStateOf(AddGameStore.STEAM) }
    val restoreStoreAfterFolderPicker: () -> Unit = {
        if (addGameCatalogState.isOpen) {
            onAddGameStoreSelected(storeBeforeFolderPicker)
        }
    }

    val folderPicker = rememberCustomGameFolderPicker(
        onPathSelected = { path ->
            // When a folder is selected via OpenDocumentTree, the user has already granted
            // URI permissions for that specific folder. We should verify we can access it
            // rather than checking for broad storage permissions.
            val folder = java.io.File(path)
            val canAccess = try {
                folder.exists() && (folder.isDirectory && folder.canRead())
            } catch (e: Exception) {
                false
            }

            // Only request permissions if we can't access the folder AND it's outside the sandbox
            // (folders selected via OpenDocumentTree should already be accessible)
            if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                requestPermissionsForPath(context, path, storagePermissionLauncher)
            }
            onAddCustomGameFolder(path)
            onCloseAddGameCatalog()
        },
        onFailure = { message ->
            SnackbarManager.show(message)
            restoreStoreAfterFolderPicker()
        },
        onCancel = restoreStoreAfterFolderPicker,
    )

    // Handle opening folder picker (with dialog check)
    val onAddCustomGameClick = {
        if (PrefManager.showAddCustomGameDialog) {
            showAddCustomGameDialog = true
        } else {
            folderPicker.launchPicker()
        }
    }

    val launchAddGameStoreLogin: (AddGameStore) -> Unit = { store ->
        when (store) {
            AddGameStore.STEAM -> onGoOnline()
            AddGameStore.GOG -> gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java))
            AddGameStore.EPIC -> epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java))
            AddGameStore.AMAZON -> amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java))
            AddGameStore.LOCAL_FOLDER -> throw IllegalStateException("Local folder does not require login")
        }
    }

    LaunchedEffect(addGameCatalogState.isOpen, addGameCatalogState.requiresLogin, addGameCatalogState.selectedStore) {
        if (!addGameCatalogState.isOpen || !addGameCatalogState.requiresLogin) return@LaunchedEffect
        if (addGameCatalogState.selectedStore == AddGameStore.STEAM && SteamService.isLoggedIn) {
            onAddGameStoreSelected(AddGameStore.STEAM)
        } else {
            launchAddGameStoreLogin(addGameCatalogState.selectedStore)
        }
    }

    val latestAddGameState by rememberUpdatedState(addGameCatalogState)
    val latestSelectAddGameStore by rememberUpdatedState(onAddGameStoreSelected)
    DisposableEffect(Unit) {
        val onSteamLogonEnded: (SteamEvent.LogonEnded) -> Unit = { event ->
            val current = latestAddGameState
            if (event.loginResult == LoginResult.Success && current.isOpen && current.selectedStore == AddGameStore.STEAM) {
                latestSelectAddGameStore(AddGameStore.STEAM)
            }
        }
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onSteamLogonEnded)
        onDispose {
            PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onSteamLogonEnded)
        }
    }

    BackHandler(enabled = isActive && state.isOptionsPanelOpen) {
        onOptionsPanelToggle(false)
    }

    BackHandler(enabled = isActive && state.isSearching && detailState == null) {
        onIsSearching(false)
        onSearchQuery("")
    }

    BackHandler(enabled = isActive && detailState != null) {
        closeDetail()
    }

    BackHandler(
        enabled = isLibraryRootBackEnabled(
            isActive = isActive,
            hasDetail = detailState != null,
            isAddGameCatalogOpen = addGameCatalogState.isOpen,
            isSearching = state.isSearching,
            isSystemMenuOpen = isSystemMenuOpen,
            isSystemMenuNavigationTransitionInProgress = isSystemMenuNavigationTransitionInProgress,
            isOptionsPanelOpen = state.isOptionsPanelOpen,
            isDialogOpen = showAddCustomGameDialog,
        ),
        onBack = onExit,
    )

    // Restore focus when returning from game detail (without reloading list)
    LaunchedEffect(detailState) {
        if (detailState != null) {
            controllerBootstrapNeeded = true
        }
        if (detailState == null) {
            // Brief delay to let the UI settle after transition
            kotlinx.coroutines.delay(100)
            // Restore focus to content area
            if (state.appInfoList.isNotEmpty()) {
                requestContentFocusOrDefer()
            } else {
                requestRootFocusSafe()
            }
        }
    }


    // Padding for the library *list* view (tab bar, grid, search bar) so content
    // never draws behind the display cutout. The window now opts in to
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES via Theme.Pluvia, so:
    //   * Status bar visible (portrait):   statusBars insets already cover the top notch.
    //   * Status bar hidden (portrait):    statusBars insets are 0; displayCutout supplies
    //                                       the notch height so content isn't behind the notch.
    //   * Landscape (cutout on a side):    statusBars is top-only; displayCutout supplies
    //                                       the side inset so the tab bar isn't clipped.
    // Bottom is intentionally excluded so scroll content can reach the bottom edge.
    //
    // The detail (game) page deliberately does NOT use this — the hero image is meant
    // to bleed through the cutout, so AppScreenContent insets only the elements that
    // need to stay tappable (e.g. the back button) instead.
    val safePaddingModifier = Modifier.windowInsetsPadding(
        WindowInsets.statusBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )

    // Restore focus after tab change - handles both empty and populated tabs
    LaunchedEffect(state.currentTab) {
        // Brief delay to let list populate after tab change
        kotlinx.coroutines.delay(150)

        // The user may have moved focus up into the tab bar during the delay; don't yank it back.
        if (tabBarHasFocus) return@LaunchedEffect

        if (state.appInfoList.isEmpty()) {
            // Empty tab - focus root so bumpers still work
            requestRootFocusSafe()
        } else {
            // Tab has content - focus the first content item/container
            requestContentFocusOrDefer(targetListIndex = 0)
        }
    }

    LaunchedEffect(
        pendingGridFocusRequest,
        gridFocusTargetListIndex,
        state.appInfoList.size,
        detailState,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        if (pendingGridFocusRequest && state.appInfoList.isNotEmpty()) {
            if (detailState == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching) {
                var retries = 0
                while (pendingGridFocusRequest && retries < 8) {
                    try {
                        gridFirstItemFocusRequester.requestFocus()
                        pendingGridFocusRequest = false
                    } catch (_: IllegalStateException) {
                        retries++
                        // FocusRequester can be temporarily detached during recomposition.
                        kotlinx.coroutines.delay(32)
                    }
                }
            }
        }
    }

    LaunchedEffect(
        pendingCarouselFocusRequest,
        carouselFocusTargetListIndex,
        state.appInfoList.size,
        detailState,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        if (pendingCarouselFocusRequest && state.appInfoList.isNotEmpty()) {
            if (detailState == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching) {
                val targetIndex = currentCarouselFocusTargetIndex()
                if (carouselListState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                    carouselListState.scrollToItem(targetIndex)
                }
                var retries = 0
                while (pendingCarouselFocusRequest && retries < 8) {
                    try {
                        carouselFocusRequester.requestFocus()
                        pendingCarouselFocusRequest = false
                    } catch (_: IllegalStateException) {
                        retries++
                        kotlinx.coroutines.delay(32)
                    }
                }
            }
        }
    }

    // If the app list starts empty and populates later, bootstrap controller focus once content is ready.
    LaunchedEffect(
        state.appInfoList.size,
        detailState,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        val currentCount = state.appInfoList.size
        val listBecameNonEmpty = previousAppCount == 0 && currentCount > 0
        val listBecameEmpty = previousAppCount > 0 && currentCount == 0

        if (listBecameNonEmpty && detailState == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching && !tabBarHasFocus) {
            requestContentFocusOrDefer()
        }
        if (listBecameEmpty && detailState == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching && !tabBarHasFocus) {
            // Empty tabs can drop focused children; re-anchor focus at the root so bumper nav keeps working.
            requestRootFocusSafe()
        }

        previousAppCount = currentCount
    }

    // Restore focus when System Menu or Options Panel closes
    LaunchedEffect(
        isActive,
        isSystemMenuOpen,
        isSystemMenuNavigationTransitionInProgress,
        state.isOptionsPanelOpen,
    ) {
        val systemMenuJustClosed = wasSystemMenuOpen && !isSystemMenuOpen
        val optionsPanelJustClosed = wasOptionsPanelOpen && !state.isOptionsPanelOpen

        if (shouldRestoreLibraryFocus(
                isActive = isActive,
                isSystemMenuNavigationTransitionInProgress = isSystemMenuNavigationTransitionInProgress,
                systemMenuJustClosed = systemMenuJustClosed,
                optionsPanelJustClosed = optionsPanelJustClosed,
                isSearching = state.isSearching,
            )
        ) {
            // Give a brief moment for the overlay to animate out
            kotlinx.coroutines.delay(50)
            // Restore focus to the active content layout
            if (state.appInfoList.isNotEmpty()) {
                requestContentFocusOrDefer()
            } else {
                // Empty list - focus root so bumpers still work
                requestRootFocusSafe()
            }
        }

        // Update previous state trackers
        wasSystemMenuOpen = isSystemMenuOpen
        wasOptionsPanelOpen = state.isOptionsPanelOpen
    }

    // Global key/motion bootstrap path for cases where Compose focus was lost by touch mode.
    // This runs at the app event bus layer, independent of current Compose focus target.
    // Helper functions defined in composable scope to capture latest state on each recomposition.
    val canBootstrapContentFocus: () -> Boolean = {
        val now = SystemClock.uptimeMillis()
        detailState == null &&
            !isSystemMenuOpen &&
            !state.isOptionsPanelOpen &&
            !state.isSearching &&
            state.appInfoList.isNotEmpty() &&
            controllerBootstrapNeeded &&
            !rootHasFocus &&
            !tabBarHasFocus &&
            (now - lastBootstrapAtMs) > 250L
    }
    val canNavigateTabsWithoutFocus: () -> Boolean = {
        detailState == null &&
            !isSystemMenuOpen &&
            !state.isOptionsPanelOpen &&
            !state.isSearching &&
            !rootHasFocus
    }
    val latestInitialLoadComplete by rememberUpdatedState(state.initialLoadComplete)
    val latestIsActive by rememberUpdatedState(isActive)
    val latestAddGameCatalogOpen by rememberUpdatedState(addGameCatalogState.isOpen)
    val latestCanBootstrapContentFocus by rememberUpdatedState(canBootstrapContentFocus)
    val latestCanNavigateTabsWithoutFocus by rememberUpdatedState(canNavigateTabsWithoutFocus)
    val latestOnLibraryUserInteraction by rememberUpdatedState(onLibraryUserInteraction)
    val latestOnPreviousTab by rememberUpdatedState(onPreviousTab)
    val latestOnNextTab by rememberUpdatedState(onNextTab)
    val latestRequestRootFocus by rememberUpdatedState { requestRootFocusSafe() }
    val latestRequestContentFocus by rememberUpdatedState { requestContentFocusOrDefer() }

    DisposableEffect(Unit) {
        val onGlobalKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (!latestIsActive ||
                latestAddGameCatalogOpen ||
                !isLibraryGlobalControllerKey(event.action, event.keyCode)
            ) {
                false
            } else {
                reportLibraryInteractionIfReady(latestInitialLoadComplete, latestOnLibraryUserInteraction)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (latestInitialLoadComplete && latestCanNavigateTabsWithoutFocus()) {
                            latestOnPreviousTab()
                            latestRequestRootFocus()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (latestInitialLoadComplete && latestCanNavigateTabsWithoutFocus()) {
                            latestOnNextTab()
                            latestRequestRootFocus()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BUTTON_L2,
                    KeyEvent.KEYCODE_BUTTON_R2,
                    KeyEvent.KEYCODE_BUTTON_THUMBL,
                    KeyEvent.KEYCODE_BUTTON_THUMBR,
                    -> {
                        if (latestInitialLoadComplete && latestCanBootstrapContentFocus()) {
                            latestRequestContentFocus()
                            // Do not consume: let normal key routing continue after bootstrap.
                            false
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }

        val onGlobalMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (event == null) {
                false
            } else {
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val leftX = event.getAxisValue(MotionEvent.AXIS_X)
                val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
                val isDirectionalMotion = isLibraryDirectionalMotion(
                    actionMasked = event.actionMasked,
                    hatX = hatX,
                    hatY = hatY,
                    leftX = leftX,
                    leftY = leftY,
                )

                if (isDirectionalMotion) {
                    reportLibraryInteractionIfReady(latestInitialLoadComplete, latestOnLibraryUserInteraction)
                    if (latestInitialLoadComplete && latestCanBootstrapContentFocus()) {
                        latestRequestContentFocus()
                    }
                    // Do not consume: allow normal movement handling after bootstrap.
                    false
                } else {
                    false
                }
            }
        }

        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)

        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onFocusChanged { focusState ->
                rootHasFocus = focusState.hasFocus
                if (focusState.hasFocus) {
                    controllerBootstrapNeeded = false
                } else {
                    controllerBootstrapNeeded = true
                }
            }
            .focusGroup()
            .notifyLibraryPointerInteraction {
                reportLibraryInteractionIfReady(state.initialLoadComplete, onLibraryUserInteraction)
            }
            .onPreviewKeyEvent { keyEvent ->
                when (libraryPreviewKeyAction(
                    isActive = isActive,
                    isSystemMenuNavigationTransitionInProgress = isSystemMenuNavigationTransitionInProgress,
                    keyAction = keyEvent.nativeKeyEvent.action,
                )) {
                    LibraryPreviewKeyAction.IGNORE -> return@onPreviewKeyEvent false
                    LibraryPreviewKeyAction.CONSUME -> return@onPreviewKeyEvent true
                    LibraryPreviewKeyAction.PROCESS -> Unit
                }
                if (addGameCatalogState.isOpen) return@onPreviewKeyEvent false
                // TODO: consider abstracting this
                // Handle gamepad buttons
                if (state.initialLoadComplete && isLibraryKeyDown(keyEvent.nativeKeyEvent.action)) {
                    onLibraryUserInteraction()
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    val canBootstrapContentFocus = detailState == null &&
                        !state.isOptionsPanelOpen &&
                        !isSystemMenuOpen &&
                        !state.isSearching &&
                        state.appInfoList.isNotEmpty() &&
                        controllerBootstrapNeeded &&
                        // Don't pull focus to the grid while the user is on the tab bar (D-pad
                        // up/left/right and analog nudges aren't consumed by the bar otherwise).
                        !tabBarHasFocus

                    when (keyCode) {
                        // Navigation keys should bootstrap focus even before any item is selected.
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_BUTTON_L2,
                        KeyEvent.KEYCODE_BUTTON_R2,
                        KeyEvent.KEYCODE_BUTTON_THUMBL,
                        KeyEvent.KEYCODE_BUTTON_THUMBR,
                        -> {
                            if (canBootstrapContentFocus) {
                                requestContentFocusOrDefer()
                                false
                            } else {
                                false
                            }
                        }

                        // L1 button - previous tab
                        KeyEvent.KEYCODE_BUTTON_L1 -> {
                            if (detailState == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                if (canBootstrapContentFocus) {
                                    requestContentFocusOrDefer()
                                }
                                onPreviousTab()
                                true
                            } else {
                                false
                            }
                        }

                        // R1 button - next tab
                        KeyEvent.KEYCODE_BUTTON_R1 -> {
                            if (detailState == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                if (canBootstrapContentFocus) {
                                    requestContentFocusOrDefer()
                                }
                                onNextTab()
                                true
                            } else {
                                false
                            }
                        }

                        // SELECT button - toggle options panel (library filters/sort)
                        KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            if (detailState == null && !isSystemMenuOpen) {
                                onOptionsPanelToggle(!state.isOptionsPanelOpen)
                                true
                            } else {
                                false
                            }
                        }

                        // START button - toggle system menu (profile/settings)
                        KeyEvent.KEYCODE_BUTTON_START,
                        KeyEvent.KEYCODE_MENU,
                        -> {
                            if (detailState == null && !state.isOptionsPanelOpen) {
                                isSystemMenuOpen = !isSystemMenuOpen
                                true
                            } else {
                                false
                            }
                        }

                        // Y button - toggle search
                        KeyEvent.KEYCODE_BUTTON_Y -> {
                            if (detailState == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                onIsSearching(!state.isSearching)
                                true
                            } else {
                                false
                            }
                        }

                        // X button - add custom game
                        KeyEvent.KEYCODE_BUTTON_X -> {
                            if (detailState == null && !state.isSearching && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                onOpenAddGameCatalog()
                                true
                            } else {
                                false
                            }
                        }

                        // B button - contextual back / open system menu
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            if (detailState != null) {
                                // Let LibraryAppScreen handle its own B-button
                                false
                            } else if (isSystemMenuOpen) {
                                isSystemMenuOpen = false
                                true
                            } else if (state.isOptionsPanelOpen) {
                                onOptionsPanelToggle(false)
                                true
                            } else if (state.isSearching) {
                                onIsSearching(false)
                                onSearchQuery("")
                                true
                            } else {
                                // Root library view: open system menu
                                isSystemMenuOpen = true
                                true
                            }
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // Keep the library composed beneath the detail window so scroll, focus and images survive the round trip.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(safePaddingModifier)
                .focusProperties { canFocus = detailState == null }
                .then(if (detailState != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
                // When on Steam/GOG/Epic/Amazon tab and not logged in, or LOCAL tab with no custom games, show splash
                val showEmptyStateSplash = when (state.currentTab) {
                    LibraryTab.STEAM -> !SteamUtils.hasStoredCredentials() && !state.isLoading
                    LibraryTab.GOG,
                    LibraryTab.EPIC,
                    LibraryTab.AMAZON,
                    -> false
                    LibraryTab.LOCAL -> PrefManager.customGamesCount == 0
                    else -> false
                }
                if (showEmptyStateSplash) {
                    val (messageResId, buttonResId, onAction) = when (state.currentTab) {
                        LibraryTab.STEAM -> Triple(
                            R.string.library_source_not_logged_in_steam,
                            R.string.steam_sign_in,
                            onGoOnline,
                        )
                        LibraryTab.GOG -> Triple(
                            R.string.library_source_not_logged_in_gog,
                            R.string.gog_settings_login_title,
                            { gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java)) },
                        )
                        LibraryTab.EPIC -> Triple(
                            R.string.library_source_not_logged_in_epic,
                            R.string.epic_settings_login_title,
                            { epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java)) },
                        )
                        LibraryTab.AMAZON -> Triple(
                            R.string.library_source_not_logged_in_amazon,
                            R.string.amazon_settings_login_title,
                            { amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java)) },
                        )
                        LibraryTab.LOCAL -> Triple(
                            R.string.library_source_no_custom_games,
                            R.string.add_custom_game_dialog_title,
                            onAddCustomGameClick,
                        )
                        else -> throw IllegalStateException("showEmptyStateSplash is true only for Steam/GOG/Epic/Amazon/LOCAL")
                    }
                    LibrarySourceNotLoggedInSplash(
                        messageResId = messageResId,
                        signInButtonLabelResId = buttonResId,
                        onSignInClick = onAction,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Library list (content scrolls behind tab bar)
                    if (currentPaneType == PaneType.CAROUSEL) {
                        LibraryCarouselPane(
                            state = state,
                            listState = carouselListState,
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                onLibraryUserInteraction()
                                openDetail(
                                    item = state.appInfoList.first { it.appId == appId },
                                    origin = LibraryDetailOrigin.MAIN,
                                )
                            },
                            onRefresh = {
                                onLibraryUserInteraction()
                                onRefresh()
                            },
                            modifier = Modifier.fillMaxSize(),
                            firstCarouselItemFocusRequester = carouselFocusRequester,
                            focusTargetListIndex = currentCarouselFocusTargetIndex(),
                            onFocusedIndexChanged = { carouselFocusTargetListIndex = it },
                            completedViewportResetToken = completedViewportResetToken,
                        )
                    } else {
                        LibraryListPane(
                            state = state,
                            listState = listState,
                            currentLayout = currentPaneType,
                            firstGridItemFocusRequester = gridFirstItemFocusRequester,
                            focusTargetListIndex = gridFocusTargetListIndex,
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                onLibraryUserInteraction()
                                openDetail(
                                    item = state.appInfoList.first { it.appId == appId },
                                    origin = LibraryDetailOrigin.MAIN,
                                )
                            },
                            onRefresh = {
                                onLibraryUserInteraction()
                                onRefresh()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Top overlay: Tab bar OR Search bar
                if (state.isSearching) {
                    // Search overlay replaces tab bar when searching
                    // TODO: Gamepad focus is a bit wonky whenever we show the search bar
                    LibrarySearchBar(
                        isVisible = true,
                        searchQuery = state.searchQuery,
                        resultCount = state.totalAppsInFilter,
                        onScrollToTop = {
                            if (currentPaneType == PaneType.CAROUSEL) {
                                carouselFocusTargetListIndex = 0
                                carouselListState.scrollToItem(0)
                            } else {
                                listState.scrollToItem(0)
                            }
                        },
                        onSearchQuery = onSearchQuery,
                        onDismiss = { onIsSearching(false) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                } else {
                    // Tab bar when not searching
                    LibraryTabBar(
                        currentTab = state.currentTab,
                        tabCounts = mapOf(
                            LibraryTab.ALL to state.allCount,
                            LibraryTab.STEAM to state.steamCount,
                            LibraryTab.GOG to state.gogCount,
                            LibraryTab.EPIC to state.epicCount,
                            LibraryTab.AMAZON to state.amazonCount,
                            LibraryTab.LOCAL to state.localCount,
                        ),
                        onTabSelected = ::selectTab,
                        onOptionsClick = {
                            onLibraryUserInteraction()
                            onOptionsPanelToggle(true)
                        },
                        onSearchClick = {
                            onLibraryUserInteraction()
                            onIsSearching(true)
                        },
                        onAddGameClick = {
                            onLibraryUserInteraction()
                            onOpenAddGameCatalog()
                        },
                        showAddGameButton = false,
                        onMenuClick = {
                            onLibraryUserInteraction()
                            isSystemMenuOpen = true
                        },
                        onNavigateDownToGrid = {
                            if (state.appInfoList.isNotEmpty()) {
                                requestContentFocusOrDefer()
                            }
                        },
                        onPreviousTab = onPreviousTab,
                        onNextTab = onNextTab,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                tabBarHasFocus = focusState.hasFocus
                                // Cancel any deferred grid-focus so the retry loop can't pull focus
                                // back off a tab-bar button the user just landed on.
                                if (focusState.hasFocus) {
                                    pendingGridFocusRequest = false
                                    pendingCarouselFocusRequest = false
                                }
                            },
                    )
                    }
        }

        if (detailState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                    .clearAndSetSemantics { },
            )
        }

        AnimatedVisibility(
            visible = detailVisible,
            enter = scaleIn(
                initialScale = LIBRARY_DETAIL_INITIAL_SCALE,
                transformOrigin = TransformOrigin.Center,
                animationSpec = tween(LIBRARY_DETAIL_ENTER_DURATION_MS),
            ) + fadeIn(animationSpec = tween(LIBRARY_DETAIL_ENTER_DURATION_MS)),
            exit = scaleOut(
                targetScale = LIBRARY_DETAIL_INITIAL_SCALE,
                transformOrigin = TransformOrigin.Center,
                animationSpec = tween(LIBRARY_DETAIL_EXIT_DURATION_MS),
            ) + fadeOut(animationSpec = tween(LIBRARY_DETAIL_EXIT_DURATION_MS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val cornerRadius by transition.animateDp(label = "detailWindowCorner") { transitionState ->
                if (transitionState == EnterExitState.Visible) 0.dp else LIBRARY_DETAIL_INITIAL_CORNER_DP.dp
            }
            val detail = detailState
            if (detail != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(cornerRadius),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LibraryDetailPane(
                        libraryItem = detail.item,
                        onBack = ::closeDetail,
                        onClickPlay = { onClickPlay(detail.item.appId, it) },
                        onTestGraphics = { onTestGraphics(detail.item.appId) },
                        onPlayWithDiagnostics = { onPlayWithDiagnostics(detail.item.appId) },
                    )
                }
            }
        }

        // Bottom action bar
        if (detailState == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
            val libraryActions = if (state.isSearching) {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.back,
                        onClick = {
                            onIsSearching(false)
                            onSearchQuery("")
                        },
                    ),
                )
            } else {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.SELECT,
                        labelResId = R.string.options,
                        onClick = { onOptionsPanelToggle(true) },
                    ),
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.action_system,
                        onClick = { isSystemMenuOpen = true },
                    ),
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.menu,
                        onClick = { isSystemMenuOpen = true },
                    ),
                    GamepadAction(
                        button = GamepadButton.Y,
                        labelResId = R.string.search,
                        onClick = {
                            onLibraryUserInteraction()
                            onIsSearching(true)
                        },
                    ),
                ) + if (!BuildConfig.MODERN_ANDROID) {
                    listOf(
                        GamepadAction(
                            button = GamepadButton.X,
                            labelResId = R.string.action_add_game,
                            onClick = onOpenAddGameCatalog,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            GamepadActionBar(
                actions = libraryActions,
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = true,
            )
        }

        if (detailState == null &&
            !state.isSearching &&
            !state.isOptionsPanelOpen &&
            !isSystemMenuOpen &&
            !addGameCatalogState.isOpen
        ) {
            FloatingActionButton(
                onClick = onOpenAddGameCatalog,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 72.dp)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_game),
                )
            }
        }

        // Options panel (SELECT) - renders on top of everything
        if (detailState == null) {
            LibraryOptionsPanel(
                isOpen = state.isOptionsPanelOpen,
                onDismiss = { onOptionsPanelToggle(false) },
                selectedFilters = state.appInfoSortType,
                onFilterChanged = { filter ->
                    onLibraryUserInteraction()
                    onFilterChanged(filter)
                },
                currentSortOption = state.currentSortOption,
                onSortOptionChanged = { sortOption ->
                    if (sortOption != state.currentSortOption) {
                        onLibraryUserInteraction()
                        onSortOptionChanged(sortOption)
                    }
                },
                currentView = currentPaneType,
                onViewChanged = { newPaneType ->
                    PrefManager.libraryLayout = newPaneType
                    currentPaneType = newPaneType
                },
            )

            // System menu (START) - renders on top of everything
            val context = LocalContext.current
            val gogLoggedIn = app.gamenative.service.gog.GOGAuthManager.hasStoredCredentials(context)
            val epicLoggedIn = app.gamenative.service.epic.EpicAuthManager.hasStoredCredentials(context)
            val amazonLoggedIn = app.gamenative.service.amazon.AmazonAuthManager.hasStoredCredentials(context)

            SystemMenu(
                isActive = isActive,
                isOpen = isSystemMenuOpen,
                onDismiss = { isSystemMenuOpen = false },
                onNavigationTransitionChanged = {
                    isSystemMenuNavigationTransitionInProgress = it
                },
                onNavigateRoute = onNavigateRoute,
                onDownloadsClick = onDownloadsClick,
                onStorageClick = onStorageClick,
                onLogout = onLogout,
                onGoOnline = onGoOnline,
                isOffline = isOffline,
                gogLoggedIn = gogLoggedIn,
                epicLoggedIn = epicLoggedIn,
                amazonLoggedIn = amazonLoggedIn,
                onGogLoginClick = {
                    gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java))
                },
                onGogLogoutClick = {
                    PlatformAuthUiHelpers.logoutGog(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
                onEpicLoginClick = {
                    epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java))
                },
                onEpicLogoutClick = {
                    PlatformAuthUiHelpers.logoutEpic(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
                onAmazonLoginClick = {
                    amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java))
                },
                onAmazonLogoutClick = {
                    PlatformAuthUiHelpers.logoutAmazon(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
            )
        }

        // Add custom game dialog
        if (showAddCustomGameDialog) {
            AlertDialog(
                onDismissRequest = { showAddCustomGameDialog = false },
                title = { Text(stringResource(R.string.add_custom_game_dialog_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.add_custom_game_dialog_message),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = dontShowAgain,
                                onCheckedChange = { dontShowAgain = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.add_custom_game_dont_show_again),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (dontShowAgain) {
                                PrefManager.showAddCustomGameDialog = false
                            }
                            showAddCustomGameDialog = false
                            folderPicker.launchPicker()
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddCustomGameDialog = false },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        if (isActive && shouldShowAddGameCatalog(addGameCatalogState.isOpen, detailState)) {
            AddGamesBottomSheet(
                sheetState = sheetState,
                state = addGameCatalogState,
                gridState = addGameCatalogGridState,
                onStoreSelected = onAddGameStoreSelected,
                onLocalFolder = {
                    storeBeforeFolderPicker = addGameCatalogState.selectedStore
                    onAddGameStoreSelected(AddGameStore.LOCAL_FOLDER)
                    folderPicker.launchPicker()
                },
                onGameClick = { item ->
                    detailScope.launch {
                        sheetState.hide()
                        openDetail(item = item, origin = LibraryDetailOrigin.ADD_CATALOG)
                    }
                },
                onRetry = {
                    if (addGameCatalogState.requiresLogin) {
                        launchAddGameStoreLogin(addGameCatalogState.selectedStore)
                    } else {
                        onRefreshAddGameCatalog()
                    }
                },
                onDismiss = onCloseAddGameCatalog,
            )
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@OptIn(ExperimentalMaterial3Api::class)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1080px,height=1920px,dpi=440,orientation=landscape",
)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "id:pixel_tablet",
)
@Composable
private fun Preview_LibraryScreenContent() {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    PrefManager.init(context)
    var state by remember {
        mutableStateOf(
            LibraryState(
                initialLoadComplete = true,
                appInfoList = List(15) { idx ->
                    val item = fakeAppInfo(idx)
                    LibraryItem(
                        index = idx,
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.iconHash,
                    )
                },
                // Add compatibility map for preview
                compatibilityMap = mapOf(
                    "Game 0" to GameCompatibilityStatus.COMPATIBLE,
                    "Game 1" to GameCompatibilityStatus.GPU_COMPATIBLE,
                    "Game 2" to GameCompatibilityStatus.NOT_COMPATIBLE,
                    "Game 3" to GameCompatibilityStatus.UNKNOWN,
                ),
            ),
        )
    }
    PluviaTheme {
        LibraryScreenContent(
            listState = rememberLazyGridState(),
            state = state,
            isActive = true,
            onExit = { },
            sheetState = sheetState,
            addGameCatalogState = AddGameCatalogState(),
            onOpenAddGameCatalog = { },
            onCloseAddGameCatalog = { },
            onAddGameStoreSelected = { },
            onRefreshAddGameCatalog = { },
            onIsSearching = {},
            onSearchQuery = {},
            onFilterChanged = { },
            onPageChange = { },
            onModalBottomSheet = {
                val currentState = state.modalBottomSheet
                println("State: $currentState")
                state = state.copy(modalBottomSheet = !currentState)
            },
            onClickPlay = { _, _ -> },
            onTestGraphics = { },
            onPlayWithDiagnostics = { },
            onRefresh = { },
            onNavigateRoute = {},
            onLogout = {},
            onGoOnline = {},
            onSourceToggle = {},
            onAddCustomGameFolder = {},
            onSortOptionChanged = {},
            onOptionsPanelToggle = { isOpen ->
                state = state.copy(isOptionsPanelOpen = isOpen)
            },
            onTabChanged = { tab ->
                state = state.copy(currentTab = tab)
            },
            onPreviousTab = {},
            onNextTab = {},
            onLibraryUserInteraction = {},
            isViewportResetPending = { false },
            consumeViewportReset = { false },
        )
    }
}
