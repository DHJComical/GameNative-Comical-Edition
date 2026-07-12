package app.gamenative.service.epic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.DownloadStore
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.EpicGameToken
import app.gamenative.data.StoreDownloadOperation
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.storeLibraryLayout
import app.gamenative.db.dao.StoreDownloadTaskDao
import app.gamenative.utils.MarkerUtils
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.NotificationHelper
import com.winlator.container.Container
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.*
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/**
 * Epic Games Service - thin coordinator that delegates to other Epic managers.
 */
@AndroidEntryPoint
class EpicService : Service() {

    private var taskRecoveryJob: Job? = null

    companion object {
        private var instance: EpicService? = null

        private const val ACTION_SYNC_LIBRARY = "app.gamenative.EPIC_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.EPIC_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {

            Timber.tag("EPIC").d("Starting service...")
            // If already running, do nothing
            if (isRunning) {
                Timber.tag("EPIC").d("[EpicService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.tag("EPIC").i("[EpicService] First-time start - starting service with initial sync")
                val intent = Intent(context, EpicService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, EpicService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.tag("EPIC").i("[EpicService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.tag("EPIC").i("Starting service without sync - throttled (${remainingMinutes}min remaining)")
                // Start service without sync action
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.tag("EPIC").i("Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, EpicService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to EpicAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
            return EpicAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return EpicAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
            return EpicAuthManager.getStoredCredentials(context)
        }

        /**
         * Logout from Epic - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("EPIC").i("Logging out from Epic...")

                    // Clear stored credentials first, regardless of service state
                    val credentialsCleared = EpicAuthManager.clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.tag("Epic").e("Failed to clear credentials during logout")
                        return@withContext Result.failure(Exception("Failed to clear stored credentials"))
                    }

                    // Get instance to clean up service-specific data
                    val instance = getInstance()
                    if (instance != null) {
                        // Clear all nonInstalled Epic games from database
                        instance.epicManager.deleteAllNonInstalledGames()
                        Timber.tag("Epic").i("All Non-installed Epic games removed from database")

                        // Stop the service
                        stop()
                    } else {
                        Timber.tag("Epic").w("Service not running during logout, but credentials were cleared")
                    }

                    Timber.tag("Epic").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error during logout")
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
            if (inProgress) getInstance()?.notifierOrNull?.showSyncing(NotificationHelper.NOTIFICATION_ID_EPIC)
            else getInstance()?.notifierOrNull?.showIdle(NotificationHelper.NOTIFICATION_ID_EPIC)
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): EpicService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance EpicManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): Int? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(appId: Int): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(appId)
        }

        fun getActiveDownloads(): Map<Int, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        fun hasPartialDownload(context: Context, appId: Int): Boolean {
            val game = getEpicGameOf(appId) ?: return false
            if (game.isInstalled) return false
            return runBlocking(Dispatchers.IO) {
                val instance = getInstance() ?: return@runBlocking false
                instance.findPartialInstallPath(game)?.let(MarkerUtils::hasPartialInstall) == true
            }
        }

        suspend fun getPartialDownloads(): List<Int> {
            val instance = getInstance() ?: return emptyList()
            val partialInstallPaths = instance.findPartialInstallPathsAndImportLegacy()
            if (partialInstallPaths.isEmpty()) return emptyList()

            return buildList {
                instance.epicManager.getNonInstalledGames().forEach { game ->
                    if (instance.activeDownloads.containsKey(game.id) || game.appName.isBlank()) return@forEach
                    val directoryName = EpicConstants.sanitizeGameDirectoryName(game.appName)
                    val path = partialInstallPaths.firstOrNull { File(it).name == directoryName } ?: return@forEach
                    instance.persistDiscoveredPartial(game, path)
                    add(game.id)
                }
            }
        }

        suspend fun deleteGame(context: Context, appId: Int): Result<Unit> {
            val instance = getInstance()
            if (instance == null) {
                return Result.failure(Exception("Service not available"))
            }

            return try {
                // Get the game to find its install path
                val game = instance.epicManager.getGameById(appId)
                if (game == null) {
                    return Result.failure(Exception("Game not found: $appId"))
                }

                val path = instance.resolveExistingPath(game)
                    ?: return Result.failure(Exception("No managed Epic installation found for appId: $appId"))
                if (!instance.isRegisteredGamePath(path)) {
                    return Result.failure(SecurityException("Epic install path is outside registered libraries: $path"))
                }
                if (File(path).exists()) {
                    Timber.tag("Epic").i("Deleting installation folder: $path")
                    val deleted = File(path).deleteRecursively()
                    if (!deleted || File(path).exists()) {
                        val error = IllegalStateException("Failed to delete Epic installation folder: $path")
                        Timber.tag("Epic").e(error, "Epic database state was preserved")
                        return Result.failure(error)
                    }
                    Timber.tag("Epic").i("Successfully deleted installation folder")
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }

                // Uninstall from database (keeps the entry but marks as not installed)
                instance.epicManager.uninstall(appId)
                instance.storeDownloadTaskDao.delete(DownloadStore.EPIC, game.appName)

                // Delete container
                // Use game.id (the auto-generated numeric Room DB primary key) to match the container
                // ID format used at creation time: "EPIC_${libraryItem.gameId}" = "EPIC_${game.id}".
                // Previously used game.appName (the Legendary identifier, e.g. a UUID) which never
                // matched the stored container ID, causing orphaned containers.
                withContext(Dispatchers.Main) {
                    ContainerUtils.deleteContainer(context, "EPIC_${game.id}")
                }

                // Trigger library refresh event
                PluviaApp.events.emitJava(
                    AndroidEvent.LibraryInstallStatusChanged(appId, GameSource.EPIC),
                )

                Timber.tag("Epic").i("Game uninstalled: $appId")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to uninstall game: $appId")
                Result.failure(e)
            }
        }

        suspend fun cleanupDownload(context: Context, appId: Int) {
            withContext(Dispatchers.IO) {
                getInstance()?.let { instance ->
                    val game = instance.epicManager.getGameById(appId) ?: return@let
                    val path = instance.resolveExistingPath(game) ?: return@let
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    instance.storeDownloadTaskDao.updateState(
                        DownloadStore.EPIC,
                        game.appName,
                        StoreDownloadState.PAUSED,
                        System.currentTimeMillis(),
                    )
                }
            }
            getInstance()?.activeDownloads?.remove(appId)
        }

        fun cancelDownload(appId: Int): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(appId)

            return if (downloadInfo != null) {
                Timber.tag("EPIC").i("Cancelling download for Epic game: $appId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(appId)
                Timber.tag("EPIC").d("Download cancelled for Epic game: $appId")
                true
            } else {
                Timber.w("No active download found for Epic game: $appId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS
        // ==========================================================================

        fun getEpicGameOf(appId: Int): EpicGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameById(appId)
            }
        }

        fun getEpicGameByAppName(appName: String): EpicGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameByAppName(appName)
            }
        }

        fun getDLCForGame(appId: Int): List<EpicGame> {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getDLCForTitle(appId) ?: emptyList()
            }
        }

        suspend fun updateEpicGame(game: EpicGame) {
            getInstance()?.epicManager?.updateGame(game)
        }


        fun isGameInstalled(context: Context, appId: Int): Boolean {
            val game = getEpicGameOf(appId) ?: return false

            if (game.isInstalled && game.installPath.isNotEmpty()) {
                return MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            }

            val installPath = runBlocking(Dispatchers.IO) {
                getInstance()?.findCompletedInstallPath(game)
            } ?: return false

            val isDownloadComplete = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            if (isDownloadComplete && !isDownloadInProgress) {
                val updatedGame = game.copy(
                    isInstalled = true,
                    installPath = installPath,
                )
                runBlocking(Dispatchers.IO) {
                    getInstance()?.epicManager?.updateGame(updatedGame)
                }
                return true
            }

            return false
        }

        fun getInstallPath(appId: Int): String? {
            val game = getEpicGameOf(appId)
            return if (game?.isInstalled == true && game.installPath.isNotEmpty()) {
                game.installPath
            } else {
                null
            }
        }

        suspend fun getInstalledExe(appId: Int): String {
            return getInstance()?.epicManager?.getInstalledExe(appId) ?: ""
        }

        /**
         * Resolves the effective launch executable for an Epic game.
         * Container id is expected to be "EPIC_&lt;numericId&gt;" (from library). Returns empty if
         * game is not installed, no executable can be found, or containerId cannot be parsed.
         */
        suspend fun getLaunchExecutable(containerId: String): String {
            val gameId = try {
                ContainerUtils.extractGameIdFromContainerId(containerId)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to parse Epic containerId: $containerId")
                return ""
            }
            return getInstance()?.epicManager?.getLaunchExecutable(gameId) ?: ""
        }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))
            val result = instance.epicManager.refreshLibrary(context)
            if (result.isSuccess) instance.reconcileRegisteredInstallations()
            return result
        }

        suspend fun fetchManifestSizes(context: Context, appId: Int): EpicManager.ManifestSizes {
            return getInstance()?.epicManager?.fetchManifestSizes(context, appId)
                ?: EpicManager.ManifestSizes(installSize = 0L, downloadSize = 0L)
        }

        /** Starts or resumes an Epic download in a validated registered library. */
        fun downloadGame(
            context: Context,
            appId: Int,
            dlcGameIds: List<Int>,
            libraryId: String,
            containerLanguage: String,
        ): Result<DownloadInfo> = startDownload(
            context = context,
            appId = appId,
            dlcGameIds = dlcGameIds,
            requestedLibraryId = libraryId,
            containerLanguage = containerLanguage,
            requiredResumeTask = null,
        )

        /** Resumes only from the exact validated location and parameters stored in the durable task. */
        fun resumeDownload(context: Context, appId: Int): Result<DownloadInfo> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))
            val game = runBlocking(Dispatchers.IO) { instance.epicManager.getGameById(appId) }
                ?: return Result.failure(Exception("Game not found for appId: $appId"))
            val task = runBlocking(Dispatchers.IO) {
                instance.storeDownloadTaskDao.find(DownloadStore.EPIC, game.appName)
            } ?: return Result.failure(Exception("No durable Epic download task for appId: $appId"))
            return startDownload(
                context = context,
                appId = appId,
                dlcGameIds = task.dlcAppIds,
                requestedLibraryId = task.libraryId,
                containerLanguage = task.language,
                requiredResumeTask = task,
            )
        }

        /** Shared launch path for new installs and exact durable-task recovery. */
        private fun startDownload(
            context: Context,
            appId: Int,
            dlcGameIds: List<Int>,
            requestedLibraryId: String,
            containerLanguage: String,
            requiredResumeTask: StoreDownloadTask?,
        ): Result<DownloadInfo> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            val game = runBlocking { instance.epicManager.getGameById(appId) }
                ?: return Result.failure(Exception("Game not found for appId: $appId"))
            val gameId = game.id

            // Do not overwrite a running task with PREPARING when the UI submits twice.
            instance.activeDownloads[appId]?.let { activeDownload ->
                Timber.tag("Epic").w("Download already in progress for $appId")
                return Result.success(activeDownload)
            }

            val prepared = try {
                runBlocking(Dispatchers.IO) {
                    instance.prepareDownloadTask(
                        game,
                        requestedLibraryId,
                        dlcGameIds,
                        containerLanguage,
                        requiredResumeTask,
                    )
                }
            } catch (exception: Exception) {
                Timber.tag("Epic").e(exception, "Failed to prepare managed Epic download")
                return Result.failure(exception)
            }
            val installPath = prepared.installPath

            // Create DownloadInfo before launching coroutine to avoid race condition
            val downloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = appId,
                downloadingAppIds = CopyOnWriteArrayList<Int>(),
            )
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            instance.activeDownloads[appId] = downloadInfo
            downloadInfo.setActive(true)
            instance.notifierOrNull?.trackDownload(downloadInfo, game.title ?: "", NotificationHelper.NOTIFICATION_ID_EPIC)

            // Start download in background
            val job = instance.scope.launch {
                try {
                    instance.storeDownloadTaskDao.updateState(
                        DownloadStore.EPIC,
                        game.appName,
                        StoreDownloadState.RUNNING,
                        System.currentTimeMillis(),
                    )
                    val commonRedistDir = File(installPath, "_CommonRedist")
                    Timber.tag("Epic").i("Starting download for game: ${game.title}, gameId: ${game.id}")

                    val result = instance.epicDownloadManager.downloadGame(
                        context,
                        game,
                        installPath,
                        downloadInfo,
                        containerLanguage,
                        dlcGameIds,
                        commonRedistDir,
                    )
                    currentCoroutineContext().ensureActive()

                    Timber.tag("Epic").d("Download result: ${if (result.isSuccess) "SUCCESS" else "FAILURE: ${result.exceptionOrNull()?.message}"}")

                    if (result.isSuccess) {
                        Timber.i("[Download] Completed successfully for game $gameId")

                        instance.epicManager.updateGame(game.copy(isInstalled = true, installPath = installPath))
                        instance.storeDownloadTaskDao.delete(DownloadStore.EPIC, game.appName)

                        // Download cloud saves so they're ready before first launch.
                        // Status message keeps isDownloading() true so Play stays hidden during sync.
                        val epicAppId = "EPIC_$gameId"
                        if (game.cloudSaveEnabled && !ContainerUtils.isLocalSavesOnly(context, epicAppId)) {
                            downloadInfo.setPostInstallSyncing(true)
                            PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, true))
                            downloadInfo.updateStatusMessage("Syncing saves...")
                            try {
                                EpicCloudSavesManager.syncCloudSaves(
                                    context = context,
                                    appId = gameId,
                                    preferredAction = "download",
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                            } finally {
                                downloadInfo.setPostInstallSyncing(false)
                                downloadInfo.updateStatusMessage(null)
                                PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                            }
                        }

                        SnackbarManager.show("Download completed successfully!")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[Download] Failed for game $gameId")
                        instance.storeDownloadTaskDao.updateState(
                            DownloadStore.EPIC,
                            game.appName,
                            StoreDownloadState.FAILED,
                            System.currentTimeMillis(),
                        )
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)

                        SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                    }
                } catch (e: CancellationException) {
                    instance.storeDownloadTaskDao.updateState(
                        DownloadStore.EPIC,
                        game.appName,
                        StoreDownloadState.PAUSED,
                        System.currentTimeMillis(),
                    )
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    instance.storeDownloadTaskDao.updateState(
                        DownloadStore.EPIC,
                        game.appName,
                        StoreDownloadState.FAILED,
                        System.currentTimeMillis(),
                    )
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)

                    SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")
                } finally {
                    instance.activeDownloads.remove(appId)
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }
            downloadInfo.setDownloadJob(job)

            // Return the DownloadInfo immediately so caller can track progress
            return Result.success(downloadInfo)
        }

        suspend fun refreshSingleGame(appId: Int, context: Context): Result<EpicGame?> {
            // For now, just get from database
            val game = getInstance()?.epicManager?.getGameById(appId)
            // TODO: Fix this up.
            return if (game != null) {
                Result.success(game)
            } else {
                Result.failure(Exception("Game not found: $appId"))
            }
        }

        // ==========================================================================
        // Game Launcher Helpers
        // ==========================================================================

        suspend fun getGameLaunchToken(
            context: Context,
            namespace: String? = null,
            catalogItemId: String? = null,
            requiresOwnershipToken: Boolean = false
        ): Result<EpicGameToken> {
            return EpicAuthManager.getGameLaunchToken(context, namespace, catalogItemId, requiresOwnershipToken)
        }

        suspend fun buildLaunchParameters(
            context: Context,
            container: Container,
            game: EpicGame,
            offline: Boolean = false,
            languageCode: String = "en-US"
        ): Result<List<String>> {
            return EpicGameLauncher.buildLaunchParameters(context, container, game, offline, languageCode)
        }

        fun cleanupLaunchTokens(context: Context, container: Container? = null) {
            EpicGameLauncher.cleanupOwnershipTokens(context, container)
        }

        // ==========================================================================
        // EOS OVERLAY
        // ==========================================================================

        /**
         * Install (or re-install) the EOS overlay into [container].
         *
         * Downloads the latest overlay from Epic's CDN, replaces incompatible DLLs
         * with Wine-compatible stubs, and writes the overlay path to the Wine registry.
         *
         * @param context         Android context.
         * @param container       Target Wine container.
         * @param forceReinstall  Re-download even if the overlay appears installed.
         * @param onProgress      Optional callback: (downloadedChunks, totalChunks).
         */
        suspend fun installOverlay(
            context: Context,
            container: Container,
            forceReinstall: Boolean = false,
            onProgress: ((Int, Int) -> Unit)? = null,
        ): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("EpicService not running"))
            return instance.epicOverlayManager.installOverlay(
                context, container, forceReinstall, onProgress,
            )
        }

        /**
         * Returns true if the EOS overlay is installed in [container].
         */
        fun isOverlayInstalled(container: Container): Boolean =
            getInstance()?.epicOverlayManager?.isOverlayInstalled(container) ?: false

        /**
         * Remove the EOS overlay from [container] and clear its registry entry.
         */
        suspend fun removeOverlay(context: Context, container: Container): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("EpicService not running"))
            return instance.epicOverlayManager.removeOverlay(context, container)
        }

        // ==========================================================================
        // CLOUD SAVES HELPERS
        // ==========================================================================

        /**
         * Get the Epic account ID from stored credentials
         */
        fun getAccountId(): String? {
            return try {
                val context = getInstance()?.applicationContext ?: return null
                val credentialsResult = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    EpicAuthManager.getStoredCredentials(context)
                }
                credentialsResult.getOrNull()?.accountId
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to get account ID")
                null
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    private val notifierOrNull: NotificationHelper? get() = if (::notificationHelper.isInitialized) notificationHelper else null

    @Inject
    lateinit var epicManager: EpicManager

    @Inject
    lateinit var epicDownloadManager: EpicDownloadManager

    @Inject
    lateinit var epicOverlayManager: EpicOverlayManager

    @Inject
    lateinit var gameLibraryRepository: GameLibraryRepository

    @Inject
    lateinit var storeDownloadTaskDao: StoreDownloadTaskDao

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by GameNative Int ID
    private val activeDownloads = ConcurrentHashMap<Int, DownloadInfo>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    /** Resolves an exact game directory while preserving installed and resumable locations. */
    private suspend fun prepareDownloadTask(
        game: EpicGame,
        requestedLibraryId: String,
        dlcGameIds: List<Int>,
        containerLanguage: String,
        requiredResumeTask: StoreDownloadTask?,
    ): StoreDownloadTask {
        taskRecoveryJob?.join()
        require(game.appName.isNotBlank()) { "Epic app name is required for installation" }
        val existingTask = storeDownloadTaskDao.find(DownloadStore.EPIC, game.appName)
        if (requiredResumeTask != null) {
            require(existingTask == requiredResumeTask) { "Durable Epic task changed before resume" }
        }
        val installation = when {
            existingTask != null -> gameLibraryRepository.resolveInstallation(GameSource.EPIC, existingTask.libraryId)
            game.installPath.isNotBlank() -> installationForExistingPath(game.installPath)
            else -> gameLibraryRepository.resolveInstallation(GameSource.EPIC, requestedLibraryId)
        }
        val installPath = when {
            existingTask != null -> EpicDownloadTaskLocation.validate(
                existingTask,
                installation,
                game.id,
                game.appName,
            ).also { taskPath ->
                require(game.installPath.isBlank() || File(game.installPath).canonicalPath == taskPath) {
                    "Epic database and durable task install paths do not match"
                }
            }
            game.installPath.isNotBlank() -> game.installPath
            else -> EpicConstants.getGameInstallPath(installation, game.appName)
        }
        require(EpicConstants.isDirectGamePath(installation.installRoot, installPath)) {
            "Epic game path is not an exact child of the selected library: $installPath"
        }
        val expectedPath = EpicConstants.getGameInstallPath(installation, game.appName)
        require(File(installPath).canonicalFile == File(expectedPath).canonicalFile) {
            "Epic game path does not match its app name: $installPath"
        }
        val now = System.currentTimeMillis()
        return StoreDownloadTask(
            store = DownloadStore.EPIC,
            gameKey = game.appName,
            appId = game.id,
            libraryId = installation.library.id,
            libraryRoot = installation.library.rootPath,
            installPath = installPath,
            dlcAppIds = dlcGameIds,
            language = containerLanguage,
            operation = if (game.isInstalled) StoreDownloadOperation.UPDATE else StoreDownloadOperation.INSTALL,
            state = StoreDownloadState.PREPARING,
            createdAt = existingTask?.createdAt ?: now,
            updatedAt = now,
        ).also { storeDownloadTaskDao.upsert(it) }
    }

    /** Revalidates custom-library permission and returns the registered location owning [path]. */
    private suspend fun installationForExistingPath(path: String) =
        libraryForGamePath(path)?.let { gameLibraryRepository.resolveInstallation(GameSource.EPIC, it.id) }
            ?: throw SecurityException("Epic path is outside registered libraries: $path")

    /** Finds the Epic library whose install root directly owns [path]. */
    private suspend fun libraryForGamePath(path: String): GameLibrary? {
        val layout = storeLibraryLayout(GameSource.EPIC)
        return gameLibraryRepository.getSnapshot().libraries
            .asSequence()
            .filter { it.source == GameSource.EPIC && !it.requiresConflictResolution }
            .firstOrNull { EpicConstants.isDirectGamePath(layout.installRoot(it.rootPath), path) }
    }

    /** Returns a persisted or discovered partial path without silently changing its library. */
    private suspend fun findPartialInstallPath(game: EpicGame): String? {
        val task = storeDownloadTaskDao.find(DownloadStore.EPIC, game.appName)
        if (task != null && MarkerUtils.hasPartialInstall(task.installPath)) return task.installPath
        if (game.installPath.isNotBlank() && MarkerUtils.hasPartialInstall(game.installPath)) return game.installPath
        return findPartialInstallPathsAndImportLegacy().firstOrNull {
            File(it).name == EpicConstants.sanitizeGameDirectoryName(game.appName)
        }
    }

    /** Scans every registered Epic library and imports the former external default once when found. */
    private suspend fun findPartialInstallPathsAndImportLegacy(): Set<String> {
        var snapshot = gameLibraryRepository.getSnapshot()
        val layout = storeLibraryLayout(GameSource.EPIC)
        val registeredRoots = snapshot.libraries.filter { it.source == GameSource.EPIC }
            .map { layout.installRoot(it.rootPath) }
            .toMutableSet()
        if (PrefManager.externalStoragePath.isBlank()) {
            return registeredRoots.asSequence()
                .flatMap { MarkerUtils.findResumablePartialInstalls(it).asSequence() }
                .toSet()
        }
        val legacyInstallRoot = EpicConstants.externalEpicGamesPath()
        if (legacyInstallRoot !in registeredRoots && MarkerUtils.findResumablePartialInstalls(legacyInstallRoot).isNotEmpty()) {
            val legacyLibraryRoot = File(legacyInstallRoot).parentFile.canonicalPath
            try {
                snapshot = gameLibraryRepository.addLibrary(GameSource.EPIC, legacyLibraryRoot)
                registeredRoots += snapshot.libraries
                    .filter { it.source == GameSource.EPIC }
                    .map { layout.installRoot(it.rootPath) }
                Timber.tag("Epic").i("Imported legacy Epic library: $legacyLibraryRoot")
            } catch (exception: Exception) {
                Timber.tag("Epic").e(exception, "Failed to import legacy Epic library: $legacyLibraryRoot")
            }
        }
        return registeredRoots.asSequence()
            .flatMap { MarkerUtils.findResumablePartialInstalls(it).asSequence() }
            .toSet()
    }

    /** Persists the exact location of a partial discovered during the one-time legacy scan. */
    private suspend fun persistDiscoveredPartial(game: EpicGame, path: String) {
        if (storeDownloadTaskDao.find(DownloadStore.EPIC, game.appName) != null) return
        val library = libraryForGamePath(path) ?: return
        val now = System.currentTimeMillis()
        storeDownloadTaskDao.upsert(
            StoreDownloadTask(
                store = DownloadStore.EPIC,
                gameKey = game.appName,
                appId = game.id,
                libraryId = library.id,
                libraryRoot = library.rootPath,
                installPath = path,
                state = StoreDownloadState.PAUSED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Finds a completed installation in DB first, then across every registered Epic library. */
    private suspend fun findCompletedInstallPath(game: EpicGame): String? {
        if (game.installPath.isNotBlank() && MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
            return game.installPath
        }
        val directoryName = EpicConstants.sanitizeGameDirectoryName(game.appName)
        val layout = storeLibraryLayout(GameSource.EPIC)
        return gameLibraryRepository.getSnapshot().libraries.asSequence()
            .filter { it.source == GameSource.EPIC }
            .map { File(layout.installRoot(it.rootPath), directoryName).path }
            .firstOrNull { MarkerUtils.hasMarker(it, Marker.DOWNLOAD_COMPLETE_MARKER) }
    }

    /** Reconciles marker-backed installs after catalog refresh without moving game files. */
    private suspend fun reconcileRegisteredInstallations() {
        epicManager.getAllGames().forEach { game ->
            if (game.appName.isBlank()) return@forEach
            val path = findCompletedInstallPath(game) ?: return@forEach
            if (!game.isInstalled || File(game.installPath).canonicalFile != File(path).canonicalFile) {
                epicManager.updateGame(game.copy(isInstalled = true, installPath = path))
            }
        }
    }

    /** Resolves DB/task state for destructive operations without inventing a default path. */
    private suspend fun resolveExistingPath(game: EpicGame): String? =
        game.installPath.takeIf(String::isNotBlank)
            ?: storeDownloadTaskDao.find(DownloadStore.EPIC, game.appName)?.installPath

    /** Enforces the registered-library boundary immediately before recursive deletion. */
    private suspend fun isRegisteredGamePath(path: String): Boolean = libraryForGamePath(path) != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        taskRecoveryJob = scope.launch {
            val recovered = storeDownloadTaskDao.markInterruptedAsPaused(
                DownloadStore.EPIC,
                System.currentTimeMillis(),
            )
            if (recovered > 0) Timber.tag("Epic").i("Recovered $recovered interrupted Epic task(s)")
        }
        Timber.tag("Epic").i("[EpicService] Service created")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.emit(AndroidEvent.ServiceReady)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("EPIC").d("onStartCommand() - action: ${intent?.action}")

        val instance = getInstance()
        // Start as foreground service
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_EPIC, "Connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_EPIC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_EPIC, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_EPIC)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.tag("EPIC").i("Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.tag("EPIC").i("Automatic sync requested")
                true
            }

            null -> {
                // Service restarted by Android with null intent (START_STICKY behavior)
                // Only sync if we haven't done initial sync yet, or if it's been a while
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.tag("EPIC").i("Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)")
                    true
                } else {
                    Timber.tag("EPIC").d("Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.tag("EPIC").d(" Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.tag("EPIC").i("Starting background library sync")

            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.tag("EPIC").d("Starting background library sync")
                    val syncResult = epicManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        reconcileRegisteredInstallations()
                        Timber.tag("EPIC").i("Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.tag("EPIC").d("Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.tag("EPIC").w("Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Epic").i("[EpicService] Service destroyed")
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_EPIC)
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.tag("Epic").i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.tag("Epic").i("Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
