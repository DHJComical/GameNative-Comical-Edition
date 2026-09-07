package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.BuildConfig
import app.gamenative.R
import app.gamenative.data.DepotInfo
import app.gamenative.data.GameSource
import app.gamenative.data.ManifestInfo
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.INVALID_APP_ID
import app.gamenative.service.SteamInstallStatusSnapshot
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.StorageUtils
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.collections.orEmpty
import timber.log.Timber

data class InstallSizeInfo(
    val downloadSize: String,
    val installSize: String,
    val availableSpace: String,
    val installBytes: Long,
    val availableBytes: Long,
)

internal fun resolveSteamInstallTarget(
    selectedLibrary: InstallLibraryOption?,
    gameId: Int,
): String? = selectedLibrary?.let { library ->
    File(library.installRoot, gameId.toString()).path
}

internal fun canEnableSteamInstall(
    installStatusLoaded: Boolean,
    librarySelected: Boolean,
    selectionConstraintsSatisfied: Boolean,
): Boolean = installStatusLoaded && librarySelected && selectionConstraintsSatisfied

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameManagerDialog(
    visible: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    branch: String? = null,
    onInstall: (List<Int>, String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val downloadableDepots = remember { mutableStateMapOf<Int, DepotInfo>() }
    val allDownloadableApps = remember { mutableStateListOf<Pair<Int, DepotInfo>>() }
    val selectedAppIds = remember { mutableStateMapOf<Int, Boolean>() }
    val enabledAppIds = remember { mutableStateMapOf<Int, Boolean>() }
    var selectedLibrary by remember { mutableStateOf<InstallLibraryOption?>(null) }

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId
    var selectedLibraryId by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var installStatus by remember(gameId) {
        mutableStateOf<SteamInstallStatusSnapshot?>(null)
    }
    val isBaseGameInstalled = installStatus?.completedInstallPaths?.containsKey(gameId) == true
    val installedApp = installStatus?.appInfoById?.get(gameId)
    val installedDlcIds = installedApp?.dlcDepots.orEmpty()

    val indirectDlcAppIds = remember(gameId) {
        SteamService.getDownloadableDlcAppsOf(gameId).orEmpty().map { it.id }
    }

    val mainAppDlcIdsWithoutProperDepotDlcIds = remember(gameId) {
        SteamService.getMainAppDlcIdsWithoutProperDepotDlcIds(gameId).toList()
    }

    LaunchedEffect(visible, gameId) {
        scrollState.animateScrollTo(0)

        installStatus = null
        downloadableDepots.clear()
        allDownloadableApps.clear()
        selectedAppIds.clear()
        enabledAppIds.clear()

        // Get Downloadable Depots
        val allPossibleDownloadableDepots = SteamService.getDownloadableDepots(gameId)
        val dlcDepots = allPossibleDownloadableDepots.values
            .filter { it.dlcAppId != INVALID_APP_ID }
            .groupBy { it.dlcAppId }
            .mapValues { it.value.first() }
        val status = runCatching {
            SteamService.loadInstallStatus(listOf(gameId) + dlcDepots.keys)
        }.getOrElse { error ->
            Timber.e(error, "Failed to load Steam install status for app %d", gameId)
            SnackbarManager.show(context.getString(R.string.game_libraries_update_failed))
            return@LaunchedEffect
        }
        val loadedDlcIds = status.appInfoById[gameId]?.dlcDepots.orEmpty()
        installStatus = status
        downloadableDepots.putAll(allPossibleDownloadableDepots)

        // Get Optional DLC IDs
        val optionalDlcIds = allPossibleDownloadableDepots
            .filter { it.value.optionalDlcId == it.value.dlcAppId }
            .map { it.value.dlcAppId }

        // Add DLCs
        dlcDepots
            .toSortedMap()
            .forEach { (_, depotInfo) ->
                allDownloadableApps.add(Pair(depotInfo.dlcAppId, depotInfo))
                val installed = status.completedInstallPaths.containsKey(depotInfo.dlcAppId)
                selectedAppIds[depotInfo.dlcAppId] =
                        installed || // For installed Base Game and Indirect DLC App
                        loadedDlcIds.contains(depotInfo.dlcAppId) || // For installed DLC from Main Depot
                        ( !indirectDlcAppIds.contains(depotInfo.dlcAppId) && !optionalDlcIds.contains(depotInfo.dlcAppId) ) // Not in indirect DLC and not in optional DLC ids

                enabledAppIds[depotInfo.dlcAppId] = !loadedDlcIds.contains(depotInfo.dlcAppId) && !installed
            }

        allDownloadableApps.sortBy { it.first }

        // Add Base Game
        val baseDepot = downloadableDepots.values.firstOrNull { it.dlcAppId == INVALID_APP_ID } ?: return@LaunchedEffect
        allDownloadableApps.add(0, Pair(gameId, baseDepot))
        selectedAppIds[gameId] = true
        enabledAppIds[gameId] = false
    }

    fun getDepotAppName(depotInfo: DepotInfo): String {
        if (depotInfo.dlcAppId == INVALID_APP_ID) {
            return displayInfo.name
        }

        val app = SteamService.getAppInfoOf(depotInfo.dlcAppId)
        if (app != null) {
            return app.name
        }

        return "DLC ${depotInfo.dlcAppId}"
    }

    val selectedBranch = branch ?: installedApp?.branch ?: "public"

    fun manifestFor(depot: DepotInfo): ManifestInfo? =
        depot.manifests[selectedBranch]
            ?: depot.encryptedManifests[selectedBranch]
            ?: depot.manifests["public"]

    fun getSizeInfo(dlcAppId: Int): Pair<String, String> {
        if (dlcAppId == INVALID_APP_ID || dlcAppId == gameId) {
            val depotsForBaseGame = downloadableDepots.filter { (_, depot) ->
                depot.dlcAppId == INVALID_APP_ID
            }

            val installBytes = depotsForBaseGame.values.sumOf {
                manifestFor(it)?.size ?: 0
            }
            val downloadBytes = depotsForBaseGame.values.sumOf {
                SteamUtils.getDownloadBytes(manifestFor(it))
            }

            return Pair(
                StorageUtils.formatBinarySize(downloadBytes),
                StorageUtils.formatBinarySize(installBytes)
            )
        }

        val depotsForDlc = downloadableDepots.filter { (_, depot) ->
            depot.dlcAppId == dlcAppId
        }

        val installBytes = depotsForDlc.values.sumOf {
            manifestFor(it)?.size ?: 0
        }
        val downloadBytes = depotsForDlc.values.sumOf {
            SteamUtils.getDownloadBytes(manifestFor(it))
        }

        return Pair(
            StorageUtils.formatBinarySize(downloadBytes),
            StorageUtils.formatBinarySize(installBytes)
        )
    }

    fun getInstallSizeInfo(library: InstallLibraryOption): InstallSizeInfo {
        val installTarget = checkNotNull(resolveSteamInstallTarget(library, gameId))
        val availableBytes = StorageUtils.getAvailableSpaceForUncreatedPath(
            installTarget,
        )

        val baseGameInstallBytes = if (!isBaseGameInstalled) {
            downloadableDepots
                .filter { (_, depot) ->
                    depot.dlcAppId == INVALID_APP_ID
                }.values.sumOf { manifestFor(it)?.size ?: 0 }
        } else {
            0L
        }

        val baseGameDownloadBytes = if (!isBaseGameInstalled) {
            downloadableDepots
                .filter { (_, depot) ->
                    depot.dlcAppId == INVALID_APP_ID
                }.values.sumOf {
                    SteamUtils.getDownloadBytes(manifestFor(it))
                }
        } else {
            0L
        }

        val selectedInstallBytes = downloadableDepots
            .filter { (_, depot) ->
                selectedAppIds[depot.dlcAppId] == true && enabledAppIds[depot.dlcAppId] == true
            }
            .values.sumOf { manifestFor(it)?.size ?: 0 }

        val selectedDownloadBytes = downloadableDepots
            .filter { (_, depot) ->
                selectedAppIds[depot.dlcAppId] == true && enabledAppIds[depot.dlcAppId] == true
            }
            .values.sumOf {
                SteamUtils.getDownloadBytes(manifestFor(it))
            }

        return InstallSizeInfo(
            downloadSize = StorageUtils.formatBinarySize(baseGameDownloadBytes + selectedDownloadBytes),
            installSize = StorageUtils.formatBinarySize(baseGameInstallBytes + selectedInstallBytes),
            availableSpace = StorageUtils.formatBinarySize(availableBytes),
            installBytes = baseGameInstallBytes + selectedInstallBytes,
            availableBytes = availableBytes
        )
    }

    val selectableAppIds by remember(enabledAppIds.toMap()) {
        derivedStateOf {
            enabledAppIds.filter { it.value }.keys.toList()
        }
    }

    val allSelectableSelected by remember(selectedAppIds.toMap(), selectableAppIds) {
        derivedStateOf {
            selectableAppIds.isNotEmpty() && selectableAppIds.all { selectedAppIds[it] == true }
        }
    }

    val installSizeInfo: InstallSizeInfo? by remember(
        downloadableDepots.keys.toSet(),
        selectedAppIds.toMap(),
        enabledAppIds.toMap(),
        selectedLibrary,
    ) {
        derivedStateOf {
            selectedLibrary?.let { library -> getInstallSizeInfo(library) }
        }
    }

    fun installSizeDisplay() : String {
        val info = installSizeInfo ?: return context.getString(R.string.game_libraries_loading)
        return context.getString(
            R.string.steam_install_space,
            info.downloadSize,
            info.installSize,
            info.availableSpace
        )
    }

    fun installButtonEnabled() : Boolean {
        val info = installSizeInfo ?: return false
        if (info.availableBytes < info.installBytes) {
            return false
        }

        if (isBaseGameInstalled) {
            val installed = installedDlcIds.toSet() - mainAppDlcIdsWithoutProperDepotDlcIds.toSet()
            val realSelectedAppIds = selectedAppIds.filter { it.value }.keys - installed
            return (realSelectedAppIds.size - 1) > 0 // -1 for main app
        }

        return selectedAppIds.filter { it.value }.isNotEmpty()
    }

    when {
        visible -> {
            Dialog(
                onDismissRequest = onDismissRequest,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        // Hero Section with Game Image Background
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                        ) {
                            // Hero background image
                            if (displayInfo.heroImageUrl != null) {
                                CoilImage(
                                    modifier = Modifier.fillMaxSize(),
                                    imageModel = { displayInfo.heroImageUrl },
                                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                    loading = { LoadingScreen() },
                                    failure = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            // Gradient background as fallback
                                            Surface(
                                                modifier = Modifier.fillMaxSize(),
                                                color = MaterialTheme.colorScheme.primary
                                            ) { }
                                        }
                                    },
                                    previewPlaceholder = painterResource(R.drawable.testhero),
                                )
                            } else {
                                // Fallback gradient background when no hero image
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary
                                ) { }
                            }

                            // Gradient overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.8f)
                                            )
                                        )
                                    )
                            )

                            // Back button (top left)
                            Box(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                BackButton(onClick = onDismissRequest)
                            }

                            // Game title and subtitle
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = displayInfo.name,
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            offset = Offset(0f, 2f),
                                            blurRadius = 10f
                                        )
                                    ),
                                    color = Color.White
                                )

                                Text(
                                    text = "${displayInfo.developer} • ${
                                        remember(displayInfo.releaseDate) {
                                            if (displayInfo.releaseDate > 0) {
                                                SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                                            } else {
                                                ""
                                            }
                                        }
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (branch != null && branch != "public") {
                                Text(
                                    text = "Installing the \"$branch\" branch",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }

                            // Select All toggle
                            if (selectableAppIds.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            val newState = !allSelectableSelected
                                            selectableAppIds.forEach { appId ->
                                                selectedAppIds[appId] = newState
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = if (allSelectableSelected) "Deselect all" else "Select all"
                                        )
                                    }
                                }
                            }

                            allDownloadableApps.forEach { (dlcAppId, depotInfo) ->
                                val checked = selectedAppIds[dlcAppId] ?: false
                                val enabled = enabledAppIds[dlcAppId] ?: false

                                ListItem(
                                    headlineContent = {
                                        Column {
                                            Text(
                                                text = getDepotAppName(depotInfo)
                                            )
                                            // Add size display
                                            val (downloadSize, installSize) = getSizeInfo(dlcAppId)
                                            Text(
                                                text = "$downloadSize download • $installSize install",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = checked,
                                            enabled = enabled,
                                            onCheckedChange = { isChecked ->
                                                // Update the local (unsaved) state only
                                                selectedAppIds[dlcAppId] = isChecked
                                            }
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = enabled) {
                                        // Toggle checkbox when ListItem is clicked
                                        selectedAppIds[dlcAppId] = !checked
                                    }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            InstallLibrarySelector(
                                source = GameSource.STEAM,
                                selectedLibraryId = selectedLibraryId,
                                onSelectionChanged = {
                                    selectedLibrary = it
                                    selectedLibraryId = it?.library?.id
                                },
                                lockedInstallPath = installStatus?.completedInstallPaths?.get(gameId),
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 8.dp, bottom = 8.dp, end = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    modifier = Modifier.weight(0.5f),
                                    text = installSizeDisplay()
                                )

                                val installLibrary = selectedLibrary
                                val installReady = installStatus != null && installLibrary != null
                                Button(
                                    enabled = canEnableSteamInstall(
                                        installStatusLoaded = installStatus != null,
                                        librarySelected = installLibrary != null,
                                        selectionConstraintsSatisfied = installReady && installButtonEnabled(),
                                    ),
                                    onClick = installLibrary?.let { library ->
                                        {
                                            val selectedIds = selectedAppIds
                                                .filter { selectedId ->
                                                    selectedId.key in enabledAppIds.filter { enabledId -> enabledId.value }
                                                }
                                                .filter { selectedId -> selectedId.value }
                                                .keys
                                                .toList()
                                            onInstall(selectedIds, library.library.id)
                                        }
                                    } ?: {},
                                ) {
                                    Text(stringResource(R.string.install))
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
fun Preview_GameManagerDialog() {
    val fakeApp = fakeAppInfo(1)
    val displayInfo = GameDisplayInfo(
        name = fakeApp.name,
        developer = fakeApp.developer,
        releaseDate = fakeApp.releaseDate,
        heroImageUrl = fakeApp.getHeroUrl(),
        iconUrl = fakeApp.iconUrl,
        gameId = fakeApp.id,
        appId = "STEAM_${fakeApp.id}",
        installLocation = null,
        sizeOnDisk = null,
        sizeFromStore = null,
        lastPlayedText = null,
        playtimeText = null,
    )

    PluviaTheme {
        GameManagerDialog(
            visible = true,
            onGetDisplayInfo = {
                return@GameManagerDialog displayInfo
            },
            onInstall = { _, _ -> },
            onDismissRequest = {}
        )
    }
}
