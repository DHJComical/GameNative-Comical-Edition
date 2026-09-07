package app.gamenative.ui.screen.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryEntry
import app.gamenative.data.library.GameLibraryOperations
import app.gamenative.data.library.GameLibraryRemovalSummary
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GameLibrarySnapshot
import app.gamenative.data.library.InstalledLibrarySynchronizer
import app.gamenative.data.library.LibraryFileProgress
import app.gamenative.data.library.customLibraryDisplayName
import app.gamenative.ui.component.AppTabDensity
import app.gamenative.ui.component.AppTabItem
import app.gamenative.ui.component.AppTabLayout
import app.gamenative.ui.component.AppTabRow
import app.gamenative.ui.components.getPathFromTreeUri
import app.gamenative.ui.screen.library.GameMigrationDialog
import app.gamenative.ui.util.SnackbarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import app.gamenative.utils.StorageUtils
import timber.log.Timber

private val librarySources = listOf(
    GameSource.STEAM,
    GameSource.GOG,
    GameSource.EPIC,
    GameSource.AMAZON,
)

data class GameLibrariesUiState(
    val snapshot: GameLibrarySnapshot? = null,
    val entriesByLibrary: Map<String, List<GameLibraryEntry>> = emptyMap(),
    val isLoading: Boolean = false,
    val activeOperation: Boolean = false,
    val migration: GameLibraryMigrationUi? = null,
    val removalRequest: GameLibraryRemovalRequest? = null,
    val failedRemovalEntries: List<GameLibraryEntry> = emptyList(),
    val pendingCleanupIds: List<String> = emptyList(),
)

data class GameLibraryMigrationUi(
    val entry: GameLibraryEntry,
    val progress: LibraryFileProgress? = null,
    val copiedFiles: Int = 0,
    val totalFiles: Int = 1,
)

data class GameLibraryRemovalRequest(
    val library: GameLibrary,
    val summary: GameLibraryRemovalSummary,
)

@HiltViewModel
class GameLibrariesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GameLibraryRepository,
    private val operations: GameLibraryOperations,
    private val installedLibrarySynchronizer: InstalledLibrarySynchronizer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GameLibrariesUiState())
    val state: StateFlow<GameLibrariesUiState> = mutableState.asStateFlow()

    fun load() = refresh(recover = true)

    fun addLibrary(source: GameSource, rootPath: String) = mutate {
        addRegisteredLibrary(repository, installedLibrarySynchronizer, source, rootPath)
    }

    fun setDefault(source: GameSource, libraryId: String) = mutate {
        setDefaultRegisteredLibrary(repository, source, libraryId)
    }

    fun requestRemoval(library: GameLibrary) {
        if (mutableState.value.activeOperation || library.builtIn) return
        viewModelScope.launch {
            mutableState.update { it.copy(activeOperation = true, failedRemovalEntries = emptyList()) }
            runCatching { operations.getRemovalSummary(library.id) }
                .onSuccess { summary ->
                    mutableState.update {
                        it.copy(
                            activeOperation = false,
                            removalRequest = GameLibraryRemovalRequest(library, summary),
                        )
                    }
                }
                .onFailure(::handleOperationFailure)
        }
    }

    fun dismissRemoval() {
        if (!mutableState.value.activeOperation) mutableState.update { it.copy(removalRequest = null) }
    }

    fun confirmDetach() {
        val request = mutableState.value.removalRequest ?: return
        if (mutableState.value.activeOperation) return
        viewModelScope.launch {
            mutableState.update { it.copy(activeOperation = true) }
            try {
                installedLibrarySynchronizer.withSynchronizationIdle {
                    operations.detachLibrary(request.library.id)
                }
                refreshState(recover = false)
                mutableState.update { it.copy(removalRequest = null, activeOperation = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleOperationFailure(exception)
            }
        }
    }

    fun confirmRemoval() {
        val request = mutableState.value.removalRequest ?: return
        if (mutableState.value.activeOperation) return
        viewModelScope.launch {
            mutableState.update { it.copy(activeOperation = true) }
            try {
                val result = installedLibrarySynchronizer.withSynchronizationIdle {
                    operations.removeLibraryAndGames(request.library.id)
                }
                refreshState(recover = false)
                if (result.removed) {
                    mutableState.update { it.copy(removalRequest = null, activeOperation = false) }
                } else {
                    mutableState.update {
                        it.copy(activeOperation = false, failedRemovalEntries = result.failedEntries)
                    }
                    SnackbarManager.show(context.getString(R.string.game_libraries_remove_partial_failure))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                handleOperationFailure(exception)
            }
        }
    }

    fun migrate(entry: GameLibraryEntry, targetLibraryId: String) {
        if (mutableState.value.activeOperation) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    activeOperation = true,
                    migration = GameLibraryMigrationUi(entry = entry),
                )
            }
            try {
                val totalFiles = withContext(Dispatchers.IO) {
                    File(entry.installPath).walkTopDown().count(File::isFile).coerceAtLeast(1)
                }
                val copiedPaths = linkedSetOf<String>()
                mutableState.update {
                    it.copy(
                        migration = GameLibraryMigrationUi(
                            entry = entry,
                            totalFiles = totalFiles,
                        ),
                    )
                }
                val result = operations.migrateEntry(entry, targetLibraryId) { progress ->
                    copiedPaths += progress.relativePath
                    mutableState.update {
                        it.copy(
                            migration = it.migration?.copy(
                                progress = progress,
                                copiedFiles = copiedPaths.size.coerceAtMost(totalFiles),
                            ),
                        )
                    }
                }
                result.getOrThrow()
                refreshState(recover = false)
                mutableState.update { it.copy(activeOperation = false, migration = null) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(migration = null) }
                handleOperationFailure(exception)
            }
        }
    }

    private fun mutate(block: suspend () -> GameLibrarySnapshot) {
        if (mutableState.value.isLoading || mutableState.value.activeOperation) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                block()
                refreshState(recover = false)
                mutableState.update { it.copy(isLoading = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.e(exception, "Failed to update game libraries")
                mutableState.update { it.copy(isLoading = false) }
                SnackbarManager.show(
                    exception.message ?: context.getString(R.string.game_libraries_update_failed),
                )
            }
        }
    }

    private fun refresh(recover: Boolean) {
        if (mutableState.value.isLoading || mutableState.value.activeOperation) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                refreshState(recover)
                mutableState.update { it.copy(isLoading = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.e(exception, "Failed to load game libraries")
                mutableState.update { it.copy(isLoading = false) }
                SnackbarManager.show(context.getString(R.string.game_libraries_operation_failed, exception.message.orEmpty()))
            }
        }
    }

    private suspend fun refreshState(recover: Boolean) {
        val recovery = if (recover) operations.recoverMigrations() else null
        val snapshot = repository.getSnapshot()
        val entries = snapshot.libraries.associate { library ->
            library.id to operations.getEntries(library.id)
        }
        mutableState.update {
            it.copy(
                snapshot = snapshot,
                entriesByLibrary = entries,
                pendingCleanupIds = recovery?.pendingTransactionIds ?: it.pendingCleanupIds,
                failedRemovalEntries = emptyList(),
            )
        }
    }

    private fun handleOperationFailure(exception: Throwable) {
        Timber.e(exception, "Game library operation failed")
        mutableState.update { it.copy(activeOperation = false) }
        SnackbarManager.show(
            context.getString(R.string.game_libraries_operation_failed, exception.message.orEmpty()),
        )
    }
}

/** Registers a library and waits for its installed games to be reconciled before UI refresh. */
internal suspend fun addRegisteredLibrary(
    repository: GameLibraryRepository,
    synchronizer: InstalledLibrarySynchronizer,
    source: GameSource,
    rootPath: String,
): GameLibrarySnapshot {
    val snapshot = repository.addLibrary(source, rootPath)
    try {
        val synchronization = synchronizer.synchronizeAll()
        synchronization.stores.filter { it.failure != null }.forEach { store ->
            Timber.w(
                "Library was added but %s installed-library synchronization failed: %s",
                store.source,
                store.failure?.message,
            )
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Timber.e(exception, "Library was added but installed-library synchronization failed")
    }
    return snapshot
}

/** Changes only the install destination preference; installed-game discovery is unaffected. */
internal suspend fun setDefaultRegisteredLibrary(
    repository: GameLibraryRepository,
    source: GameSource,
    libraryId: String,
): GameLibrarySnapshot = repository.setDefaultLibrary(source, libraryId)

@Composable
fun GameLibrariesScreen(
    onBack: () -> Unit,
    onOperationActiveChanged: (Boolean) -> Unit = {},
    viewModel: GameLibrariesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingPicker by rememberSaveable { mutableStateOf(false) }
    var expandedLibraryIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var migrationEntryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSource = librarySources[selectedTab]

    LaunchedEffect(state.activeOperation) {
        onOperationActiveChanged(state.activeOperation)
    }
    BackHandler(enabled = state.activeOperation) {}

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { getPathFromTreeUri(context, it) }
        if (path == null) {
            if (uri != null) SnackbarManager.show(context.getString(R.string.game_libraries_invalid_path))
        } else {
            viewModel.addLibrary(selectedSource, path)
        }
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (granted && pendingPicker) folderPicker.launch(null)
        if (!granted) SnackbarManager.show(context.getString(R.string.game_libraries_permission_denied))
        pendingPicker = false
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        if (granted && pendingPicker) folderPicker.launch(null)
        if (!granted) SnackbarManager.show(context.getString(R.string.game_libraries_permission_denied))
        pendingPicker = false
    }

    fun requestAddLibrary() {
        if (hasBroadStorageAccess(context)) {
            folderPicker.launch(null)
            return
        }
        pendingPicker = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    LaunchedEffect(viewModel) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .displayCutoutPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack, enabled = !state.activeOperation) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.game_libraries_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
                AppTabRow(
                    items = librarySources.mapIndexed { index, source ->
                        AppTabItem(key = index, label = storeName(source))
                    },
                    selectedKey = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    density = AppTabDensity.Standard,
                    layout = AppTabLayout.Scrollable,
                )

                val snapshot = state.snapshot
                if (snapshot == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val libraries = snapshot.libraries.filter { it.source == selectedSource }
                    val defaultId = snapshot.defaultLibraryIds.getValue(selectedSource)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                FilledTonalButton(
                                    onClick = ::requestAddLibrary,
                                    enabled = !state.isLoading && !state.activeOperation,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(stringResource(R.string.game_libraries_add))
                                }
                            }
                        }
                        if (state.pendingCleanupIds.isNotEmpty()) {
                            item {
                                OperationNotice(
                                    text = stringResource(
                                        R.string.game_libraries_pending_cleanup,
                                        state.pendingCleanupIds.size,
                                        state.pendingCleanupIds.joinToString(),
                                    ),
                                    isError = true,
                                )
                            }
                        }
                        if (state.failedRemovalEntries.isNotEmpty()) {
                            item {
                                OperationNotice(
                                    text = stringResource(
                                        R.string.game_libraries_failed_removals,
                                        state.failedRemovalEntries.joinToString { it.title },
                                    ),
                                    isError = true,
                                )
                            }
                        }
                        items(libraries, key = GameLibrary::id) { library ->
                            val expanded = library.id in expandedLibraryIds
                            GameLibraryCard(
                                library = library,
                                isDefault = library.id == defaultId,
                                entries = state.entriesByLibrary[library.id].orEmpty(),
                                expanded = expanded,
                                enabled = !state.isLoading && !state.activeOperation,
                                onSetDefault = { viewModel.setDefault(selectedSource, library.id) },
                                onToggleExpanded = {
                                    expandedLibraryIds = if (expanded) {
                                        expandedLibraryIds - library.id
                                    } else {
                                        expandedLibraryIds + library.id
                                    }
                                },
                                onMigrate = { entry -> migrationEntryKey = entry.stableUiKey() },
                                onRemove = { viewModel.requestRemoval(library) },
                            )
                        }
                    }
                }
        }
    }

    val migrationEntry = state.entriesByLibrary.values.flatten()
        .firstOrNull { it.stableUiKey() == migrationEntryKey }
    if (migrationEntry != null) {
        val targets = state.snapshot?.libraries.orEmpty().filter {
            it.source == migrationEntry.source &&
                it.id != migrationEntry.libraryId &&
                !it.requiresConflictResolution
        }
        MigrationTargetDialog(
            entry = migrationEntry,
            targets = targets,
            onTarget = { targetId ->
                migrationEntryKey = null
                viewModel.migrate(migrationEntry, targetId)
            },
            onDismiss = { migrationEntryKey = null },
        )
    }

    state.removalRequest?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = {
                Text(
                    stringResource(R.string.game_libraries_remove_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.game_libraries_remove_warning,
                        request.summary.installedCount,
                        request.summary.partialCount,
                        StorageUtils.formatBinarySize(request.summary.totalBytes),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::dismissRemoval, enabled = !state.activeOperation) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = viewModel::confirmDetach, enabled = !state.activeOperation) {
                        Text(stringResource(R.string.game_libraries_detach_confirm))
                    }
                    Button(
                        onClick = viewModel::confirmRemoval,
                        enabled = !state.activeOperation,
                    ) {
                        if (state.activeOperation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.game_libraries_remove_confirm))
                        }
                    }
                }
            },
        )
    }

    state.migration?.let { migration ->
        val progress = migration.progress
        GameMigrationDialog(
            progress = if (progress == null || progress.totalBytes == 0L) {
                0f
            } else {
                (progress.copiedBytes.toDouble() / progress.totalBytes).toFloat().coerceIn(0f, 1f)
            },
            currentFile = progress?.relativePath ?: migration.entry.title,
            movedFiles = migration.copiedFiles,
            totalFiles = migration.totalFiles,
        )
    }
}

@Composable
private fun GameLibraryCard(
    library: GameLibrary,
    isDefault: Boolean,
    entries: List<GameLibraryEntry>,
    expanded: Boolean,
    enabled: Boolean,
    onSetDefault: () -> Unit,
    onToggleExpanded: () -> Unit,
    onMigrate: (GameLibraryEntry) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (isDefault) Icons.Default.CheckCircle else Icons.Default.Storage,
                    contentDescription = null,
                    tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (library.builtIn) {
                            stringResource(R.string.game_libraries_built_in)
                        } else {
                            customLibraryDisplayName(library.rootPath)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isDefault) {
                        Text(
                            text = stringResource(R.string.game_libraries_default),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = library.rootPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (library.requiresConflictResolution) {
                    Text(
                        text = stringResource(R.string.game_libraries_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                }
                IconButton(onClick = onToggleExpanded, enabled = enabled) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.game_libraries_toggle_games),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isDefault && !library.requiresConflictResolution) {
                    TextButton(onClick = onSetDefault, enabled = enabled) {
                        Text(stringResource(R.string.game_libraries_set_default))
                    }
                }
                if (!library.builtIn) {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.game_libraries_remove_title),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (expanded) {
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.game_libraries_no_games),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.title, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(
                                        R.string.game_libraries_game_details,
                                        StorageUtils.formatBinarySize(entry.sizeBytes),
                                        entry.installPath,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onMigrate(entry) }, enabled = enabled) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                    contentDescription = stringResource(R.string.game_libraries_migrate),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MigrationTargetDialog(
    entry: GameLibraryEntry,
    targets: List<GameLibrary>,
    onTarget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_libraries_migrate_title, entry.title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (targets.isEmpty()) {
                    item { Text(stringResource(R.string.game_libraries_no_migration_target)) }
                } else {
                    items(targets, key = GameLibrary::id) { target ->
                        TextButton(onClick = { onTarget(target.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (target.builtIn) {
                                        stringResource(R.string.game_libraries_built_in)
                                    } else {
                                        customLibraryDisplayName(target.rootPath)
                                    },
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    target.rootPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun OperationNotice(text: String, isError: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun GameLibraryEntry.stableUiKey(): String = "$libraryId:$gameKey:$installPath"

private fun hasBroadStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun storeName(source: GameSource): String = stringResource(
    when (source) {
        GameSource.STEAM -> R.string.store_steam
        GameSource.GOG -> R.string.store_gog
        GameSource.EPIC -> R.string.store_epic_games
        GameSource.AMAZON -> R.string.store_amazon_games
        GameSource.CUSTOM_GAME -> error("Custom games do not have managed libraries")
    },
)
