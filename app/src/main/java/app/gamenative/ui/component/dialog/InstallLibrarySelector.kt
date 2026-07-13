package app.gamenative.ui.component.dialog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.content.ContextCompat
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.customLibraryDisplayName
import app.gamenative.data.library.storeLibraryLayout
import app.gamenative.ui.util.SnackbarManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import timber.log.Timber

/** Immutable install target exposed to store-specific confirmation dialogs. */
data class InstallLibraryOption(
    val library: GameLibrary,
    val installRoot: String,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface InstallLibraryEntryPoint {
    fun gameLibraryRepository(): GameLibraryRepository
}

/** Lists writable libraries for one store and owns the custom-path permission transition. */
@Composable
fun InstallLibrarySelector(
    source: GameSource,
    selectedLibraryId: String?,
    onSelectionChanged: (InstallLibraryOption?) -> Unit,
    lockedInstallPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { context.gameLibraryRepository() }
    var options by remember(source) { mutableStateOf<List<InstallLibraryOption>>(emptyList()) }
    var pendingOption by remember { mutableStateOf<InstallLibraryOption?>(null) }

    val finishPermissionRequest: (Boolean) -> Unit = { granted ->
        val requested = pendingOption
        pendingOption = null
        if (requested != null && granted) {
            onSelectionChanged(requested)
        } else {
            onSelectionChanged(null)
            SnackbarManager.show(context.getString(R.string.install_library_permission_denied))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishPermissionRequest(hasLibraryStorageAccess(context))
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        finishPermissionRequest(hasLibraryStorageAccess(context))
    }

    LaunchedEffect(source, lockedInstallPath) {
        val snapshot = runCatching { repository.getSnapshot() }.getOrElse { error ->
            Timber.e(error, "Failed to load install libraries for %s", source)
            onSelectionChanged(null)
            return@LaunchedEffect
        }
        options = snapshot.libraries
            .filter { it.source == source && !it.requiresConflictResolution }
            .map { library ->
                InstallLibraryOption(
                    library = library,
                    installRoot = storeLibraryLayout(source).installRoot(library.rootPath),
                )
            }
        val lockedOption = runCatching {
            lockedInstallPath?.let { path ->
                val parentPath = File(path).canonicalFile.parentFile?.path
                    ?: error("Installed game path has no parent: $path")
                options.singleOrNull { File(it.installRoot).canonicalPath == parentPath }
                    ?: error("Installed game is outside registered $source libraries: $path")
            }
        }.getOrElse { error ->
            Timber.e(error, "Failed to lock %s install library", source)
            onSelectionChanged(null)
            return@LaunchedEffect
        }
        val initialId = selectedLibraryId ?: snapshot.defaultLibraryIds.getValue(source)
        val initialOption = lockedOption ?: options.firstOrNull { it.library.id == initialId }
            ?: options.firstOrNull { it.library.builtIn }
        if (initialOption?.library?.builtIn == false && !hasLibraryStorageAccess(context)) {
            onSelectionChanged(null)
            SnackbarManager.show(context.getString(R.string.install_library_permission_revoked))
        } else {
            onSelectionChanged(initialOption)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val selected = options.firstOrNull { it.library.id == selectedLibraryId }
        if (selected?.library?.builtIn == false && !hasLibraryStorageAccess(context)) {
            onSelectionChanged(null)
            SnackbarManager.show(context.getString(R.string.install_library_permission_revoked))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.install_library_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                ListItem(
                headlineContent = {
                    Text(
                        text = if (option.library.builtIn) {
                            stringResource(R.string.install_library_built_in)
                        } else {
                            customLibraryDisplayName(option.library.rootPath)
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Text(
                        text = option.installRoot,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = selectedLibraryId == option.library.id,
                        onClick = null,
                    )
                },
                modifier = Modifier.clickable(enabled = lockedInstallPath == null) {
                    if (option.library.builtIn || hasLibraryStorageAccess(context)) {
                        onSelectionChanged(option)
                    } else {
                        pendingOption = option
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            permissionLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                },
                            )
                        } else {
                            legacyPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ),
                            )
                        }
                    }
                },
                )
            }
        }
    }
}

private fun Context.gameLibraryRepository(): GameLibraryRepository =
    EntryPointAccessors.fromApplication(
        applicationContext,
        InstallLibraryEntryPoint::class.java,
    ).gameLibraryRepository()

private fun hasLibraryStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
