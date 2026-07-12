package app.gamenative.service.gog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.PrefManager
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadOperation
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GogLibraryLayout
import app.gamenative.db.dao.StoreDownloadTaskDao
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber


/**
 * GOG Service - thin abstraction layer that delegates to managers.
 *
 * Architecture:
 * - GOGApiClient: Api Layer for interacting with GOG's APIs
 * - GOGDownloadManager: Handles Download Logic for Games
 * - GOGConstants: Shared Constants for our GOG-related data
 * - GOGCloudSavesManager: Handler for Cloud Saves
 * - GOGAuthManager: Authentication and account management
 * - GOGManager: Game library, downloads, and installation
 * - GOGManifestParser: Parses and has utils for parsing/extracting/decompressing manifests.
 * - GOGDataMdoels: Data Models for GOG-related Data types such as API responses
 *
 */
@AndroidEntryPoint
class GOGService : Service() {

    private var taskRecoveryJob: Job? = null

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.GOG_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.GOG_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: GOGService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            // If already running, do nothing
            if (isRunning) {
                Timber.d("[GOGService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[GOGService] First-time start - starting service with initial sync")
                val intent = Intent(context, GOGService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, GOGService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[GOGService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.d("[GOGService] Starting service without sync - throttled (${remainingMinutes}min remaining)")
                // Start service without sync action
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[GOGService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, GOGService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to GOGAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
            return GOGAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return GOGAuthManager.getStoredCredentials(context)
        }

        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return GOGAuthManager.validateCredentials(context)
        }

        fun clearStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.clearStoredCredentials(context)
        }

        /**
         * Logout from GOG - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.i("[GOGService] Logging out from GOG...")

                    // Get instance first before stopping the service
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.w("[GOGService] Service instance not available during logout")
                        return@withContext Result.failure(Exception("Service not running"))
                    }

                    // Clear stored credentials
                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.w("[GOGService] Failed to clear credentials during logout")
                    }

                    // Clear all non-installed GOG games from database
                    instance.gogManager.deleteAllNonInstalledGames()
                    Timber.i("[GOGService] All non-installed GOG games removed from database")

                    // Stop the service
                    stop()

                    Timber.i("[GOGService] Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService] Error during logout")
                    Result.failure(e)
                }
            }
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
            if (inProgress) getInstance()?.notifierOrNull?.showSyncing(NotificationHelper.NOTIFICATION_ID_GOG)
            else getInstance()?.notifierOrNull?.showIdle(NotificationHelper.NOTIFICATION_ID_GOG)
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): GOGService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }

        fun getActiveDownloads(): Map<String, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        private fun hasPartialDownload(game: GOGGame): Boolean {
            if (game.isInstalled) return false
            val instance = getInstance() ?: return false
            return runBlocking(Dispatchers.IO) {
                val task = instance.storeDownloadTaskDao.find(DownloadStore.GOG, game.id)
                task != null && task.state != StoreDownloadState.PREPARING && File(task.installPath).exists()
            }
        }

        fun hasPartialDownload(gameId: String, fallbackTitle: String? = null): Boolean {
            getGOGGameOf(gameId)?.let { return hasPartialDownload(it) }
            fallbackTitle?.ifBlank { null } ?: return false
            val instance = getInstance() ?: return false
            return runBlocking(Dispatchers.IO) {
                instance.storeDownloadTaskDao.find(DownloadStore.GOG, gameId)
                    ?.let { it.state != StoreDownloadState.PREPARING && File(it.installPath).exists() }
                    ?: false
            }
        }

        private suspend fun getPartialInstallPaths(instance: GOGService): Set<String> {
            val snapshot = instance.gameLibraryRepository.getSnapshot()
            val registeredRoots = snapshot.libraries
                .filter { it.source == GameSource.GOG }
                .map { GogLibraryLayout.installRoot(it.rootPath) }
            val scanned = registeredRoots.asSequence()
                .flatMap { MarkerUtils.findResumablePartialInstalls(it).asSequence() }
            val persisted = instance.storeDownloadTaskDao.getAll().asSequence()
                .filter { it.store == DownloadStore.GOG }
                .map(StoreDownloadTask::installPath)
                .filter { File(it).exists() }
            return (scanned + persisted).toSet()
        }

        suspend fun getPartialDownloads(): List<String> {
            val instance = getInstance() ?: return emptyList()
            instance.importLegacyPartialDownloadsOnce()
            val partialInstallPaths = getPartialInstallPaths(instance)
            if (partialInstallPaths.isEmpty()) return emptyList()
            val taskPaths = instance.storeDownloadTaskDao.getAll()
                .filter { it.store == DownloadStore.GOG }
                .associate { it.gameKey to it.installPath }

            return instance.gogManager.getNonInstalledGames()
                .asSequence()
                .filter { game -> !instance.activeDownloads.containsKey(game.id) }
                .filter { game ->
                    val taskPath = taskPaths[game.id]
                    taskPath != null && partialInstallPaths.contains(taskPath)
                }
                .map { it.id }
                .toList()
        }

        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }

        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(gameId)
                Timber.d("Download cancelled for game: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameFromDbById(gameId)
            }
        }

        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                if (game?.isInstalled != true) {
                    return@runBlocking false
                }

                // Verify the installation is actually valid
                val (isValid, errorMessage) = getInstance()?.gogManager?.verifyInstallation(gameId)
                    ?: Pair(false, "Service not available")
                if (!isValid) {
                    Timber.w("Game $gameId marked as installed but verification failed: $errorMessage")
                }
                isValid
            }
        }

        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }

        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            return getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }

        suspend fun getInstalledExe(libraryItem: LibraryItem): String {
            return getInstance()?.gogManager?.getInstalledExe(libraryItem)
                ?: ""
        }

        /**
         * Resolves the effective launch executable for a GOG game (container config or auto-detected).
         * Returns empty string if no executable can be found.
         */
        suspend fun getLaunchExecutable(appId: String, container: Container): String {
            return getInstance()?.gogManager?.getLaunchExecutable(appId, container) ?: ""
        }

        fun getGogWineStartCommand(
            libraryItem: LibraryItem,
            container: Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: EnvVars,
            guestProgramLauncherComponent: GuestProgramLauncherComponent,
            gameId: Int,
        ): String {
            return getInstance()?.gogManager?.getGogWineStartCommand(
                libraryItem, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent, gameId,
            ) ?: "\"explorer.exe\""
        }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        /** Starts or resumes a download in the selected registered GOG library. */
        suspend fun downloadGame(
            context: Context,
            gameId: String,
            libraryId: String,
            containerLanguage: String,
        ): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))
            return instance.startDownload(context, gameId, libraryId, containerLanguage)
        }

        /** Returns the durable task path first, then an installed game's exact database path. */
        suspend fun getResumableInstallPath(gameId: String): String? {
            val instance = getInstance() ?: return null
            return instance.storeDownloadTaskDao.find(DownloadStore.GOG, gameId)?.installPath
                ?: instance.gogManager.getGameFromDbById(gameId)?.installPath?.takeIf { it.isNotBlank() }
        }

        /** Resumes GOG from the exact path and language stored before process death. */
        suspend fun resumeDownload(context: Context, gameId: String): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))
            val task = instance.storeDownloadTaskDao.find(DownloadStore.GOG, gameId)
                ?: return Result.failure(IllegalStateException("No durable GOG download task for game: $gameId"))
            return instance.startDownload(
                context = context,
                gameId = gameId,
                requestedLibraryId = task.libraryId,
                containerLanguage = task.language,
                requestedInstallPath = task.installPath,
            )
        }

        /**
         * Compatibility entry point for callers that already hold an exact installed/partial path.
         * The path must still belong to a currently registered and accessible GOG library.
         */
        suspend fun downloadGameAtPath(
            context: Context,
            gameId: String,
            installPath: String,
            containerLanguage: String,
        ): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))
            return instance.startDownload(context, gameId, null, containerLanguage, installPath)
        }

        private suspend fun GOGService.startDownload(
            context: Context,
            gameId: String,
            requestedLibraryId: String?,
            containerLanguage: String,
            requestedInstallPath: String? = null,
        ): Result<DownloadInfo?> {
            val prepared = try {
                prepareDownload(gameId, requestedLibraryId, requestedInstallPath, containerLanguage)
            } catch (exception: Exception) {
                Timber.e(exception, "[Download] Failed to resolve GOG installation for $gameId")
                return Result.failure(exception)
            }
            val installPath = prepared.installPath

            // Create DownloadInfo for progress tracking
            val downloadInfo = DownloadInfo(jobCount = 1, gameId = 0, downloadingAppIds = CopyOnWriteArrayList<Int>())
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            // Track in activeDownloads first
            activeDownloads[gameId] = downloadInfo
            notifierOrNull?.trackDownload(downloadInfo, "", NotificationHelper.NOTIFICATION_ID_GOG)

            // Launch download in service scope so it runs independently
            val job = scope.launch {
                try {
                    storeDownloadTaskDao.updateState(
                        DownloadStore.GOG,
                        gameId,
                        StoreDownloadState.RUNNING,
                        System.currentTimeMillis(),
                    )
                    Timber.d("[Download] Starting download for game $gameId")
                    val commonRedistDir = File(installPath, "_CommonRedist")
                    Timber.tag("GOG").d("Will install dependencies to _CommonRedist")

                    val result = gogDownloadManager.downloadGame(
                        gameId, File(installPath),
                        downloadInfo, prepared.language, true, commonRedistDir,
                    )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[Download] Failed for game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                        storeDownloadTaskDao.updateState(
                            DownloadStore.GOG,
                            gameId,
                            StoreDownloadState.FAILED,
                            System.currentTimeMillis(),
                        )

                        SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                    } else {
                        Timber.i("[Download] Completed successfully for game $gameId")

                        // Download cloud saves so they're ready before first launch.
                        // Status message keeps isDownloading() true so Play stays hidden during sync.
                        val appId = "GOG_$gameId"
                        val numericGameId = gameId.toIntOrNull()
                        try {
                            val gogGame = gogManager.getGameFromDbById(gameId)
                            val locations = if (gogGame != null) gogManager.getSaveDirectoryPath(context, appId, gogGame.title) else null
                            if (numericGameId != null && !locations.isNullOrEmpty() && !ContainerUtils.isLocalSavesOnly(context, appId)) {
                                try {
                                    downloadInfo.setPostInstallSyncing(true)
                                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(numericGameId, true))
                                    downloadInfo.updateStatusMessage("Syncing saves...")
                                    syncCloudSaves(context, appId, preferredAction = "download")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                                } finally {
                                    downloadInfo.setPostInstallSyncing(false)
                                    downloadInfo.updateStatusMessage(null)
                                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(numericGameId, false))
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                        }

                        SnackbarManager.show("Download completed successfully!")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                        storeDownloadTaskDao.delete(DownloadStore.GOG, gameId)
                    }
                } catch (e: CancellationException) {
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toIntOrNull() ?: -1, false))
                    storeDownloadTaskDao.updateState(
                        DownloadStore.GOG,
                        gameId,
                        StoreDownloadState.PAUSED,
                        System.currentTimeMillis(),
                    )
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toIntOrNull() ?: -1, false))
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)
                    storeDownloadTaskDao.updateState(
                        DownloadStore.GOG,
                        gameId,
                        StoreDownloadState.FAILED,
                        System.currentTimeMillis(),
                    )

                    SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")
                } finally {
                    // Remove from activeDownloads for both success and failure
                    // so UI knows download is complete and to prevent stale entries
                    activeDownloads.remove(gameId)
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }
            downloadInfo.setDownloadJob(job)

            return Result.success(downloadInfo)
        }

        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Delete/uninstall a GOG game
         * Delegates to GOGManager.deleteGame
         */
        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            return getInstance()?.gogManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Sync GOG cloud saves for a game
         * @param context Android context
         * @param appId Game app ID (e.g., "gog_123456")
         * @param preferredAction Preferred sync action: "download", "upload", or "none"
         * @return true if sync succeeded, false otherwise
         */
        suspend fun syncCloudSaves(
            context: Context,
            appId: String,
            preferredAction: String = "none",
        ): Boolean = withContext(Dispatchers.IO) {
            try {
                Timber.tag("GOG").d("[Cloud Saves] syncCloudSaves called for $appId with action: $preferredAction")

                // Check if there's already a sync in progress for this appId
                val serviceInstance = getInstance()
                if (serviceInstance == null) {
                    Timber.tag("GOG").e("[Cloud Saves] Service instance not available for sync start")
                    return@withContext false
                }

                if (!serviceInstance.gogManager.startSync(appId)) {
                    Timber.tag("GOG").w("[Cloud Saves] Sync already in progress for $appId, skipping duplicate sync")
                    return@withContext false
                }

                try {
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.tag("GOG").e("[Cloud Saves] Service instance not available")
                        return@withContext false
                    }

                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        Timber.tag("GOG").e("[Cloud Saves] Cannot sync saves: not authenticated")
                        return@withContext false
                    }

                    val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                    Timber.tag("GOG").d("[Cloud Saves] Using auth config path: $authConfigPath")

                    // Get game info
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    Timber.tag("GOG").d("[Cloud Saves] Extracted game ID: $gameId from appId: $appId")
                    val game = instance.gogManager.getGameFromDbById(gameId.toString())

                    if (game == null) {
                        Timber.tag("GOG").e("[Cloud Saves] Game not found for appId: $appId")
                        return@withContext false
                    }
                    Timber.tag("GOG").d("[Cloud Saves] Found game: ${game.title}")

                    // Get save directory paths (Android runs games through Wine, so always Windows)
                    Timber.tag("GOG").d("[Cloud Saves] Resolving save directory paths for $appId")
                    val saveLocations = instance.gogManager.getSaveDirectoryPath(context, appId, game.title)

                    if (saveLocations == null || saveLocations.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] No save locations found for game $appId (cloud saves may not be enabled)")
                        return@withContext false
                    }
                    Timber.tag("GOG").i("[Cloud Saves] Found ${saveLocations.size} save location(s) for $appId")

                    var allSucceeded = true

                    // Sync each save location
                    for ((index, location) in saveLocations.withIndex()) {
                        try {
                            Timber.tag("GOG").d("[Cloud Saves] Processing location ${index + 1}/${saveLocations.size}: '${location.name}'")

                            // Log directory state BEFORE sync
                            try {
                                val saveDir = java.io.File(location.location)
                                Timber.tag("GOG").d("[Cloud Saves] [BEFORE] Checking directory: ${location.location}")
                                Timber.tag("GOG").d("[Cloud Saves] [BEFORE] Directory exists: ${saveDir.exists()}, isDirectory: ${saveDir.isDirectory}")
                                if (saveDir.exists() && saveDir.isDirectory) {
                                    val filesBefore = saveDir.listFiles()
                                    if (filesBefore != null && filesBefore.isNotEmpty()) {
                                        Timber.tag("GOG").i(
                                            "[Cloud Saves] [BEFORE] ${filesBefore.size} files in '${location.name}': ${filesBefore.joinToString(", ") {
                                                it.name
                                            }}",
                                        )
                                    } else {
                                        Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' is empty")
                                    }
                                } else {
                                    Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' does not exist yet")
                                }
                            } catch (e: Exception) {
                                Timber.tag("GOG").e(e, "[Cloud Saves] [BEFORE] Failed to check directory")
                            }

                            // Get stored timestamp for this location
                            val timestampStr = instance.gogManager.getCloudSaveSyncTimestamp(appId, location.name)
                            val timestamp = timestampStr.toLongOrNull() ?: 0L

                            Timber.tag("GOG").i("[Cloud Saves] Syncing '${location.name}' for game $gameId (clientId: ${location.clientId}, path: ${location.location}, timestamp: $timestamp, action: $preferredAction)")

                            // Validate clientSecret is available
                            if (location.clientSecret.isEmpty()) {
                                Timber.tag("GOG").e("[Cloud Saves] Missing clientSecret for '${location.name}', skipping sync")
                                continue
                            }

                            val cloudSavesManager = GOGCloudSavesManager(context)
                            val newTimestamp = cloudSavesManager.syncSaves(
                                clientId = location.clientId,
                                clientSecret = location.clientSecret,
                                localPath = location.location,
                                dirname = location.name,
                                lastSyncTimestamp = timestamp,
                                preferredAction = preferredAction,
                            )

                            if (newTimestamp != timestamp) {
                                if (newTimestamp > 0) {
                                    // Success - store new timestamp
                                    instance.gogManager.setCloudSaveSyncTimestamp(appId, location.name, newTimestamp.toString())
                                    Timber.tag("GOG").d("[Cloud Saves] Updated timestamp for '${location.name}': $newTimestamp")

                                    // Log the save files in the directory after sync
                                    try {
                                        val saveDir = java.io.File(location.location)
                                        if (saveDir.exists() && saveDir.isDirectory) {
                                            val files = saveDir.listFiles()
                                            if (files != null && files.isNotEmpty()) {
                                                val fileList = files.joinToString(", ") { it.name }
                                                Timber.tag("GOG")
                                                    .i("[Cloud Saves] [$preferredAction] Files in '${location.name}': $fileList (${files.size} files)")

                                                // Log detailed file info
                                                files.forEach { file ->
                                                    val size = if (file.isFile) "${file.length()} bytes" else "directory"
                                                    Timber.tag("GOG").d("[Cloud Saves] [$preferredAction]   - ${file.name} ($size)")
                                                }
                                            } else {
                                                Timber.tag("GOG")
                                                    .w("[Cloud Saves] [$preferredAction] Directory '${location.name}' is empty at: ${location.location}")
                                            }
                                        } else {
                                            Timber.tag("GOG")
                                                .w("[Cloud Saves] [$preferredAction] Directory not found: ${location.location}")
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("GOG").e(e, "[Cloud Saves] Failed to list files in directory: ${location.location}")
                                    }

                                    Timber.tag("GOG")
                                        .i("[Cloud Saves] Successfully synced save location '${location.name}' for game $gameId")
                                } else {
                                    Timber.tag("GOG")
                                        .e("[Cloud Saves] Failed to sync save location '${location.name}' for game $gameId (timestamp: $newTimestamp)")
                                    allSucceeded = false
                                }
                            } else {
                                Timber.tag("GOG").i("[Cloud Saves] No save changes found for $appId")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "[Cloud Saves] Exception syncing save location '${location.name}' for game $gameId")
                            allSucceeded = false
                        }
                    }

                    if (allSucceeded) {
                        Timber.tag("GOG").i("[Cloud Saves] All save locations synced successfully for $appId")
                        return@withContext true
                    } else {
                        Timber.tag("GOG").w("[Cloud Saves] Some save locations failed to sync for $appId")
                        return@withContext false
                    }
                } finally {
                    // Always end the sync, even if an exception occurred
                    getInstance()?.gogManager?.endSync(appId)
                    Timber.tag("GOG").d("[Cloud Saves] Sync completed and lock released for $appId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Failed to sync cloud saves for App ID: $appId")
                return@withContext false
            }
        }

        data class GogConflict(
            val localTimestamp: Long,
            val remoteTimestamp: Long,
        )

        /**
         * Check every save location for a conflict (both local and cloud changed since the last
         * sync) WITHOUT uploading or downloading anything. Returns the first conflicting location's
         * timestamps (millis), or null if there is no conflict.
         *
         * Takes the per-app sync lock (startSync/endSync) for the duration of the read so it never
         * observes a half-synced snapshot: a concurrent syncSaves mutates local files, remote
         * metadata, and the stored timestamp, any of which would otherwise yield a wrong result.
         * If a sync is already running for this app, skips detection and returns null — that sync
         * reconciles state, and a real conflict surfaces on a later launch.
         */
        suspend fun detectCloudSaveConflict(
            context: Context,
            appId: String,
        ): GogConflict? = withContext(Dispatchers.IO) {
            val instance = getInstance() ?: return@withContext null
            if (!GOGAuthManager.hasStoredCredentials(context)) return@withContext null

            if (!instance.gogManager.startSync(appId)) {
                Timber.tag("GOG").d("[Cloud Saves] Sync already in progress for $appId, skipping conflict detection")
                return@withContext null
            }
            try {
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                val game = instance.gogManager.getGameFromDbById(gameId.toString()) ?: return@withContext null
                val saveLocations = instance.gogManager.getSaveDirectoryPath(context, appId, game.title)
                    ?: return@withContext null
                val manager = GOGCloudSavesManager(context)

                for (location in saveLocations) {
                    if (location.clientSecret.isEmpty()) continue
                    val timestamp = instance.gogManager
                        .getCloudSaveSyncTimestamp(appId, location.name).toLongOrNull() ?: 0L
                    val conflict = manager.detectConflict(
                        clientId = location.clientId,
                        clientSecret = location.clientSecret,
                        localPath = location.location,
                        dirname = location.name,
                        lastSyncTimestamp = timestamp,
                    )
                    if (conflict != null) {
                        Timber.tag("GOG").i("[Cloud Saves] Conflict in '${location.name}' for $appId")
                        return@withContext GogConflict(conflict.localTimestamp, conflict.remoteTimestamp)
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Conflict detection failed for $appId")
                null
            } finally {
                instance.gogManager.endSync(appId)
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    private val notifierOrNull: NotificationHelper? get() = if (::notificationHelper.isInitialized) notificationHelper else null

    @Inject
    lateinit var gogManager: GOGManager

    @Inject
    lateinit var gogDownloadManager: GOGDownloadManager

    @Inject
    lateinit var gameLibraryRepository: GameLibraryRepository

    @Inject
    lateinit var storeDownloadTaskDao: StoreDownloadTaskDao

    @Inject
    lateinit var gogLibraryPathPolicy: GogLibraryPathPolicy

    @Inject
    lateinit var gogDownloadTaskPolicy: GogDownloadTaskPolicy

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()
    private val legacyPartialMigrationMutex = Mutex()

    /** Imports legacy marker-only partial installs exactly once into durable store tasks. */
    private suspend fun importLegacyPartialDownloadsOnce() = legacyPartialMigrationMutex.withLock {
        val preferences = getSharedPreferences("gog_library_migration", Context.MODE_PRIVATE)
        if (preferences.getBoolean("partial_tasks_imported", false)) return@withLock

        val legacyRoots = buildList {
            add(GOGConstants.internalGOGGamesPath)
            if (PrefManager.externalStoragePath.isNotBlank()) add(GOGConstants.externalGOGGamesPath)
        }.distinct()
        val partialPaths = legacyRoots
            .flatMap(MarkerUtils::findResumablePartialInstalls)
            .distinct()
        var snapshot = gameLibraryRepository.getSnapshot()

        val legacyExternalRoot = PrefManager.externalStoragePath.takeIf { it.isNotBlank() }
            ?.let { GOGConstants.externalGOGGamesPath }
        val externalPartials = legacyExternalRoot?.let { root ->
            val canonicalRoot = File(root).canonicalFile.toPath()
            partialPaths.filter {
                File(it).canonicalFile.toPath().startsWith(canonicalRoot)
            }
        }.orEmpty()
        if (externalPartials.isNotEmpty()) {
            val externalRoot = File(requireNotNull(legacyExternalRoot)).parentFile?.parentFile
                ?: throw IllegalStateException("Invalid legacy external GOG path")
            if (snapshot.libraries.none { it.source == GameSource.GOG && it.rootPath == externalRoot.canonicalPath }) {
                val previousDefault = snapshot.defaultLibraryIds.getValue(GameSource.GOG)
                snapshot = gameLibraryRepository.addLibrary(GameSource.GOG, externalRoot.path)
                gameLibraryRepository.setDefaultLibrary(GameSource.GOG, previousDefault)
                snapshot = gameLibraryRepository.getSnapshot()
            }
        }

        val games = gogManager.getNonInstalledGames()
        val importedPaths = storeDownloadTaskDao.getAll()
            .filter { it.store == DownloadStore.GOG }
            .mapTo(mutableSetOf(), StoreDownloadTask::installPath)
        for (game in games) {
            if (storeDownloadTaskDao.find(DownloadStore.GOG, game.id) != null) continue
            val matchingPaths = partialPaths.filter { candidate ->
                File(candidate).name == GOGConstants.gameDirectoryName(game.title)
            }
            require(matchingPaths.size <= 1) {
                "Multiple legacy GOG partials match ${game.id}: ${matchingPaths.joinToString()}"
            }
            val partialPath = matchingPaths.singleOrNull() ?: continue
            val validatedPath = gogLibraryPathPolicy.validate(
                snapshot.libraries.filter { it.source == GameSource.GOG },
                null,
                game.title,
                partialPath,
            )
            val now = System.currentTimeMillis()
            storeDownloadTaskDao.upsert(
                StoreDownloadTask(
                    store = DownloadStore.GOG,
                    gameKey = game.id,
                    appId = game.id.toIntOrNull()
                        ?: throw IllegalArgumentException("GOG game id is not a 32-bit integer: ${game.id}"),
                    libraryId = validatedPath.library.id,
                    libraryRoot = validatedPath.library.rootPath,
                    installPath = validatedPath.installPath,
                    language = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
                    state = StoreDownloadState.PAUSED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            importedPaths += partialPath
            Timber.i("Imported legacy GOG partial task ${game.id} at $partialPath")
        }
        require(importedPaths.containsAll(partialPaths)) {
            "Some legacy GOG partial installs could not be matched to library games"
        }
        check(preferences.edit().putBoolean("partial_tasks_imported", true).commit()) {
            "Failed to persist GOG partial-task migration state"
        }
    }

    /** Resolves an exact path and persists the complete task before network or filesystem work. */
    private suspend fun prepareDownload(
        gameId: String,
        requestedLibraryId: String?,
        requestedInstallPath: String?,
        language: String,
    ): StoreDownloadTask {
        taskRecoveryJob?.join()
        require(activeDownloads[gameId]?.isActive() != true) { "GOG game $gameId is already downloading" }
        val game = gogManager.getGameFromDbById(gameId)
            ?: throw IllegalArgumentException("Unknown GOG game: $gameId")
        val existingTask = storeDownloadTaskDao.find(DownloadStore.GOG, gameId)
        val snapshot = gameLibraryRepository.getSnapshot()
        val libraries = snapshot.libraries.filter { it.source == GameSource.GOG }

        val exactPath = existingTask?.installPath
            ?: game.installPath.takeIf { game.isInstalled && it.isNotBlank() }
            ?: requestedInstallPath
        val exactPathOwner = exactPath?.let { path ->
            gogLibraryPathPolicy.validate(libraries, existingTask?.libraryRoot, game.title, path)
        }
        val selectedLibraryId = existingTask?.libraryId
            ?: exactPathOwner?.library?.id
            ?: requestedLibraryId
            ?: snapshot.defaultLibraryIds.getValue(GameSource.GOG)
        val installation = gameLibraryRepository.resolveInstallation(GameSource.GOG, selectedLibraryId)
        if (existingTask != null) {
            gogDownloadTaskPolicy.validate(existingTask, gameId, game.title, libraries, installation.library)
        }
        val candidatePath = exactPath ?: GOGConstants.getGameInstallPath(installation.installRoot, game.title)
        val validatedPath = gogLibraryPathPolicy.validate(
            libraries,
            installation.library.rootPath,
            game.title,
            candidatePath,
        )
        require(validatedPath.library.id == installation.library.id) {
            "GOG install path belongs to a different library"
        }
        val installPath = validatedPath.installPath
        val now = System.currentTimeMillis()
        val operation = existingTask?.operation ?: if (game.isInstalled) {
            StoreDownloadOperation.UPDATE
        } else {
            StoreDownloadOperation.INSTALL
        }
        val task = StoreDownloadTask(
            store = DownloadStore.GOG,
            gameKey = gameId,
            appId = gameId.toIntOrNull()
                ?: throw IllegalArgumentException("GOG game id is not a 32-bit integer: $gameId"),
            libraryId = installation.library.id,
            libraryRoot = installation.library.rootPath,
            installPath = installPath,
            language = existingTask?.language ?: language,
            operation = operation,
            state = StoreDownloadState.PREPARING,
            createdAt = existingTask?.createdAt ?: now,
            updatedAt = now,
        )
        storeDownloadTaskDao.upsert(task)
        return task
    }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    // GOGManager is injected by Hilt
    override fun onCreate() {
        super.onCreate()
        instance = this
        taskRecoveryJob = scope.launch {
            val recovered = storeDownloadTaskDao.markInterruptedAsPaused(
                DownloadStore.GOG,
                System.currentTimeMillis(),
            )
            if (recovered > 0) Timber.tag("GOG").i("Recovered $recovered interrupted GOG task(s)")
        }

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.emit(AndroidEvent.ServiceReady)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[GOGService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_GOG, "Connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_GOG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_GOG, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_GOG)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[GOGService] Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.i("[GOGService] Automatic sync requested")
                true
            }

            null -> {
                // Service restarted by Android with null intent (START_STICKY behavior)
                // Only sync if we haven't done initial sync yet, or if it's been a while
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.i("[GOGService] Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)")
                    true
                } else {
                    Timber.d("[GOGService] Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.d("[GOGService] Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.i("[GOGService] Starting background library sync")
            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.d("[GOGService]: Starting background library sync")

                    val syncResult = gogManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.i("[GOGService]: Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService]: Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.d("[GOGService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[GOGService] Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_GOG)
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.tag("GOG").i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.tag("GOG").i("Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
