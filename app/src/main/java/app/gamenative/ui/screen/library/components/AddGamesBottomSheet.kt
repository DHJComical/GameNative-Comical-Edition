package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.AddGameCatalogState
import app.gamenative.ui.model.AddGameStore

internal val addGameStoreTabs = listOf(
    AddGameStore.STEAM,
    AddGameStore.GOG,
    AddGameStore.EPIC,
    AddGameStore.AMAZON,
    AddGameStore.LOCAL_FOLDER,
)

internal val addGamesDragHandleWidth = 32.dp
internal val addGamesDragHandleHeight = 4.dp
internal val addGamesDragHandleVerticalPadding = 8.dp

internal fun nextAddGameStore(current: AddGameStore, direction: Int): AddGameStore {
    val currentIndex = addGameStoreTabs.indexOf(current).coerceAtLeast(0)
    return addGameStoreTabs[(currentIndex + direction).mod(addGameStoreTabs.size)]
}

internal fun handleAddGamesSheetKey(
    action: Int,
    keyCode: Int,
    selectedStore: AddGameStore,
    onStoreSelected: (AddGameStore) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (action != KeyEvent.ACTION_DOWN) return false
    return when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1 -> {
            onStoreSelected(nextAddGameStore(selectedStore, -1))
            true
        }
        KeyEvent.KEYCODE_BUTTON_R1 -> {
            onStoreSelected(nextAddGameStore(selectedStore, 1))
            true
        }
        KeyEvent.KEYCODE_BUTTON_B -> {
            onDismiss()
            true
        }
        else -> false
    }
}

internal fun shouldRequestInitialAddGameFocus(
    hasItems: Boolean,
    initialFocusRequested: Boolean,
    userInteracted: Boolean,
): Boolean = hasItems && !initialFocusRequested && !userInteracted

internal fun shouldFocusAddGamesSheetRoot(
    requiresLogin: Boolean,
    hasError: Boolean,
    hasItems: Boolean,
): Boolean = requiresLogin || hasError || !hasItems

@Composable
private fun AddGamesDragHandle() {
    Surface(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.padding(vertical = addGamesDragHandleVerticalPadding),
    ) {
        Box(
            modifier = Modifier.size(
                width = addGamesDragHandleWidth,
                height = addGamesDragHandleHeight,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddGamesBottomSheet(
    sheetState: SheetState,
    state: AddGameCatalogState,
    onStoreSelected: (AddGameStore) -> Unit,
    onLocalFolder: () -> Unit,
    onGameClick: (LibraryItem) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val configuration = LocalConfiguration.current
    val sheetHeight = addGamesSheetHeight(configuration.screenHeightDp)
    val rootFocusRequester = remember { FocusRequester() }
    val firstGameFocusRequester = remember { FocusRequester() }
    var userInteracted by remember(state.selectedStore) { mutableStateOf(false) }
    var initialFocusRequested by remember(state.selectedStore) { mutableStateOf(false) }
    val selectStore: (AddGameStore) -> Unit = { store ->
        if (store == AddGameStore.LOCAL_FOLDER) onLocalFolder() else onStoreSelected(store)
    }

    LaunchedEffect(state.selectedStore, state.requiresLogin, state.error, state.items.isNotEmpty()) {
        if (shouldFocusAddGamesSheetRoot(
                requiresLogin = state.requiresLogin,
                hasError = state.error != null,
                hasItems = state.items.isNotEmpty(),
            )
        ) {
            rootFocusRequester.requestFocus()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        dragHandle = { AddGamesDragHandle() },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding()
                .pointerInput(state.selectedStore) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        userInteracted = true
                    }
                }
                .onPreviewKeyEvent { event ->
                    val nativeEvent = event.nativeKeyEvent
                    if (nativeEvent.action == KeyEvent.ACTION_DOWN &&
                        nativeEvent.keyCode != KeyEvent.KEYCODE_BUTTON_L1 &&
                        nativeEvent.keyCode != KeyEvent.KEYCODE_BUTTON_R1
                    ) {
                        userInteracted = true
                    }
                    handleAddGamesSheetKey(
                        action = nativeEvent.action,
                        keyCode = nativeEvent.keyCode,
                        selectedStore = state.selectedStore,
                        onStoreSelected = selectStore,
                        onDismiss = onDismiss,
                    )
                }
                .focusRequester(rootFocusRequester)
                .focusable()
                .focusGroup(),
        ) {
            ScrollableTabRow(
                selectedTabIndex = addGameStoreTabs.indexOf(state.selectedStore).coerceAtLeast(0),
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                addGameStoreTabs.forEach { store ->
                    Tab(
                        selected = store == state.selectedStore,
                        onClick = { selectStore(store) },
                        text = {
                            Text(
                                text = stringResource(store.labelResId()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.requiresLogin -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(state.selectedStore.loginMessageResId()),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onRetry) {
                            Text(text = stringResource(state.selectedStore.loginButtonResId()))
                        }
                    }
                    state.isLoading && state.items.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.error != null -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.add_game_catalog_load_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(
                                text = stringResource(R.string.connection_retry),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    state.items.isEmpty() -> Text(
                        text = stringResource(R.string.game_libraries_no_games),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    else -> {
                        LaunchedEffect(state.selectedStore, state.items.isNotEmpty()) {
                            if (shouldRequestInitialAddGameFocus(
                                    hasItems = state.items.isNotEmpty(),
                                    initialFocusRequested = initialFocusRequested,
                                    userInteracted = userInteracted,
                                )
                            ) {
                                firstGameFocusRequester.requestFocus()
                                initialFocusRequested = true
                            }
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(130.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = state.items,
                                key = { _, item -> item.appId },
                            ) { index, item ->
                                AppItem(
                                    modifier = if (index == 0) {
                                        Modifier.focusRequester(firstGameFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    appInfo = item,
                                    onClick = { onGameClick(item) },
                                    paneType = PaneType.GRID_CAPSULE,
                                    onFocus = { },
                                )
                            }
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun addGamesSheetHeight(screenHeightDp: Int): Dp =
    screenHeightDp.coerceAtLeast(0).dp.let { availableHeight ->
        (availableHeight * 0.82f).coerceAtMost(availableHeight)
    }

private fun AddGameStore.labelResId(): Int = when (this) {
    AddGameStore.STEAM -> R.string.tab_steam
    AddGameStore.GOG -> R.string.tab_gog
    AddGameStore.EPIC -> R.string.tab_epic
    AddGameStore.AMAZON -> R.string.tab_amazon
    AddGameStore.LOCAL_FOLDER -> R.string.add_game_local_folder
}

private fun AddGameStore.loginMessageResId(): Int = when (this) {
    AddGameStore.STEAM -> R.string.library_source_not_logged_in_steam
    AddGameStore.GOG -> R.string.library_source_not_logged_in_gog
    AddGameStore.EPIC -> R.string.library_source_not_logged_in_epic
    AddGameStore.AMAZON -> R.string.library_source_not_logged_in_amazon
    AddGameStore.LOCAL_FOLDER -> throw IllegalStateException("Local folder does not require login")
}

private fun AddGameStore.loginButtonResId(): Int = when (this) {
    AddGameStore.STEAM -> R.string.steam_sign_in
    AddGameStore.GOG -> R.string.gog_settings_login_title
    AddGameStore.EPIC -> R.string.epic_settings_login_title
    AddGameStore.AMAZON -> R.string.amazon_settings_login_title
    AddGameStore.LOCAL_FOLDER -> throw IllegalStateException("Local folder does not require login")
}
