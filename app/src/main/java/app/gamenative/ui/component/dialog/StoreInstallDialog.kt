package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.StorageUtils

/** Compact install confirmation used by stores without DLC configuration. */
@Composable
fun StoreInstallDialog(
    source: GameSource,
    title: String,
    requiredBytes: Long,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedLibrary by remember { mutableStateOf<InstallLibraryOption?>(null) }
    var selectedLibraryId by rememberSaveable(source, title) { mutableStateOf<String?>(null) }
    val availableBytes = selectedLibrary?.let {
        StorageUtils.getAvailableSpaceForUncreatedPath(it.installRoot)
    } ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.install_library_space_summary,
                        StorageUtils.formatBinarySize(requiredBytes),
                        StorageUtils.formatBinarySize(availableBytes),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InstallLibrarySelector(
                    source = source,
                    selectedLibraryId = selectedLibraryId,
                    onSelectionChanged = {
                        selectedLibrary = it
                        selectedLibraryId = it?.library?.id
                    },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = selectedLibrary != null && availableBytes >= requiredBytes,
                onClick = { onInstall(selectedLibrary!!.library.id) },
            ) {
                Text(stringResource(R.string.download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
