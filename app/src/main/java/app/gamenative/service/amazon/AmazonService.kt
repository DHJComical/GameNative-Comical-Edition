package app.gamenative.service.amazon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.AmazonCredentials
import app.gamenative.data.AmazonGame
import app.gamenative.data.DownloadInfo
import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadOperation
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.AmazonLibraryLayout
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryInstallation
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.StoreDownloadTaskDao
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ExecutableSelectionUtils
import app.gamenative.utils.MarkerUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/** Amazon Games foreground service. */
@AndroidEntryPoint
class AmazonService : Service() {

    /** Entry point to access [AmazonGameDao] when service instance is unavailable. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AmazonDaoEntryPoint {
        fun amazonGameDao(): AmazonGameDao
    }

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var amazonManager: AmazonManager

    @Inject
    lateinit var amazonDownloadManager: AmazonDownloadManager

    @Inject
    lateinit var gameLibraryRepository: GameLibraryRepository

    @Inject
    lateinit var storeDownloadTaskDao: StoreDownloadTaskDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var taskRecoveryJob: Job? = null

    // Active downloads keyed by Amazon product ID (e.g. "amzn1.adg.product.XXXX")
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    // Active install paths keyed by Amazon product ID (used for robust partial-download detection)
    private val activeDownloadPaths = ConcurrentHashMap<String, String>()

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.AMAZON_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.AMAZON_MANUAL_SYNC"
        private const val ACTION_DOWNLOAD_RECOVERY = "app.gamenative.AMAZON_DOWNLOAD_RECOVERY"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes
        private var instance: AmazonService? = null

        // Sync tracking variables
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private val catalogSyncMutex = Mutex()

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        /** Returns true when sync or download work is still active. */
        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()
        }

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (isRunning) {
                Timber.d("[Amazon] Service already running")
                return
            }

            val intent = Intent(context, AmazonService::class.java)

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[Amazon] First-time start — starting service with initial sync")
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[Amazon] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.i("[Amazon] Starting service without sync — throttled (${remainingMinutes}min remaining)")
            }
            context.startForegroundService(intent)
        }

        /** Starts persisted download recovery without refreshing the owned catalog. */
        fun startForDownloadRecovery(context: Context) {
            if (!isRunning) {
                context.startForegroundService(Intent(context, AmazonService::class.java).apply {
                    action = ACTION_DOWNLOAD_RECOVERY
                })
            }
        }

        /** Starts or refreshes Amazon's owned catalog on explicit user request. */
        fun requestLibrarySync(context: Context) {
            val intent = Intent(context, AmazonService::class.java).apply {
                action = ACTION_SYNC_LIBRARY
            }
            context.startForegroundService(intent)
        }

        /** Performs an explicit catalog refresh and completes with its real result. */
        suspend fun refreshLibrary(): Result<Unit> {
            val service = instance ?: return Result.failure(IllegalStateException("Amazon service is not available"))
            return catalogSyncMutex.withLock {
            setSyncInProgress(true)
            try {
                service.amazonManager.refreshLibrary().getOrThrow()
                service.importLegacyExternalLibraryIfNeeded()
                service.reconcileRegisteredInstallations()
                lastSyncTimestamp = System.currentTimeMillis()
                hasPerformedInitialSync = true
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "[Amazon] Explicit library sync failed")
                Result.failure(error)
            } finally {
                setSyncInProgress(false)
            }
            }
        }

        fun stop() {
            instance?.stopSelf()
        }

        fun getInstance(): AmazonService? = instance

        fun hasStoredCredentials(context: Context): Boolean =
            AmazonAuthManager.hasStoredCredentials(context)

        /** Authenticate with Amazon using a PKCE authorization code. */
        suspend fun authenticateWithCode(
            context: Context,
            authCode: String,
        ): Result<AmazonCredentials> = AmazonAuthManager.authenticateWithCode(context, authCode)

        /** Logout, clear credentials, delete non-installed entries, and stop the service. */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Amazon").i("Starting logout...")

                    // Deregister device and clear credentials
                    AmazonAuthManager.logout(context)
                    Timber.tag("Amazon").i("Credentials cleared")

                    // Delete non-installed games from database
                    val svc = instance
                    if (svc != null) {
                        svc.amazonManager.deleteAllNonInstalledGames()
                        Timber.tag("Amazon").i("All non-installed Amazon games removed from database")
                    } else {
                        Timber.tag("Amazon").w("Service not running during logout — cleaning up DB via entry point")
                        val dao = EntryPointAccessors
                            .fromApplication(context.applicationContext, AmazonDaoEntryPoint::class.java)
                            .amazonGameDao()
                        withContext(Dispatchers.IO) { dao.deleteAllNonInstalledGames() }
                        Timber.tag("Amazon").i("Non-installed Amazon games removed from database (service was stopped)")
                    }

                    // Stop the service
                    stop()

                    Timber.tag("Amazon").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Error during logout")
                    Result.failure(e)
                }
            }
        }

        /** Trigger a manual library sync, bypassing throttle. */
        fun triggerLibrarySync(context: Context) {
            Timber.i("[Amazon] Manual sync requested — bypassing throttle")
            val intent = Intent(context, AmazonService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        // ── Install queries ───────────────────────────────────────────────────

        /** Fetch and cache total download size for a game. */
        suspend fun fetchDownloadSize(productId: String): Long? {
            val svc = instance ?: return null
            val game = svc.amazonManager.getGameById(productId) ?: return null
            if (game.entitlementId.isBlank()) return null

            val token = svc.amazonManager.getBearerToken() ?: return null
            val size = AmazonApiClient.fetchDownloadSize(game.entitlementId, token) ?: return null

            // Cache in DB so we don't have to re-fetch next time
            svc.amazonManager.updateDownloadSize(productId, size)
            return size
        }

        /** Return whether a game is installed, using marker-based detection with DB reconciliation. */
        fun isGameInstalled(context: Context, productId: String): Boolean {
            val game = getAmazonGameOf(productId) ?: return false
            val service = instance ?: return false

            if (game.isInstalled && game.installPath.isNotEmpty()) {
                val validatedPath = runBlocking(Dispatchers.IO) { service.validateInstalledPath(game) }
                if (MarkerUtils.hasMarker(validatedPath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                    return true
                }
                Timber.tag("Amazon").w(
                    "Stored install path is incomplete; scanning registered libraries: ${game.installPath}",
                )
            }

            val installPath = runBlocking(Dispatchers.IO) {
                service.validatedTaskPath(game)
                    ?: service.findCompletedInstallPath(game)
            } ?: return false

            val isDownloadComplete = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            if (isDownloadComplete && !isDownloadInProgress) {
                runBlocking(Dispatchers.IO) {
                    service.amazonManager.markInstalled(productId, installPath, 0L)
                    service.storeDownloadTaskDao.delete(DownloadStore.AMAZON, productId)
                }
                return true
            }

            return false
        }

        /** Return whether a game is installed, looked up by appId. */
        fun isGameInstalledByAppId(context: Context, appId: Int): Boolean {
            val game = getAmazonGameByAppId(appId) ?: return false
            return isGameInstalled(context, game.productId)
        }

        /** Return expected install path for [appId], even when partially downloaded. */
        fun getExpectedInstallPathByAppId(context: Context, appId: Int): String? {
            val game = getAmazonGameByAppId(appId) ?: return null

            instance?.activeDownloadPaths?.get(game.productId)?.let { return it }
            val service = instance ?: return game.title.ifBlank { null }?.let {
                AmazonConstants.getGameInstallPath(context, it)
            }
            return runBlocking(Dispatchers.IO) {
                if (game.installPath.isNotBlank()) service.validateInstalledPath(game) else null
            } ?: runBlocking(Dispatchers.IO) {
                service.validatedTaskPath(game)
                    ?: service.findCompletedInstallPath(game)
                    ?: game.title.ifBlank { null }?.let { title ->
                        val snapshot = service.gameLibraryRepository.getSnapshot()
                        val defaultId = snapshot.defaultLibraryIds.getValue(GameSource.AMAZON)
                        val installation = service.gameLibraryRepository.resolveInstallation(
                            GameSource.AMAZON,
                            defaultId,
                        )
                        AmazonConstants.getGameInstallPath(installation.installRoot, title)
                    }
            }
        }

        /** Steam-style partial detection: directory exists and completion marker is absent. */
        fun hasPartialDownloadByAppId(context: Context, appId: Int): Boolean {
            if (getDownloadInfoByAppId(appId) != null) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=true reason=active_download")
                return true
            }
            if (isGameInstalledByAppId(context, appId)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=installed")
                return false
            }

            val expectedPath = getExpectedInstallPathByAppId(context, appId) ?: return false
            val installDir = File(expectedPath)
            if (!installDir.exists()) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=path_missing path=$expectedPath")
                return false
            }

            if (MarkerUtils.hasMarker(expectedPath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=complete_marker path=$expectedPath")
                return false
            }
            if (MarkerUtils.hasMarker(expectedPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=true reason=in_progress_marker path=$expectedPath")
                return true
            }

            val children = installDir.listFiles() ?: return false
            if (children.isEmpty()) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=empty_dir path=$expectedPath")
                return false
            }

            val hasPartialPayload = children.any { child ->
                when (child.name) {
                    Marker.DOWNLOAD_COMPLETE_MARKER.fileName,
                    Marker.DOWNLOAD_IN_PROGRESS_MARKER.fileName,
                    ".DownloadInfo" -> {
                        child.isDirectory && (child.listFiles()?.any { it.isFile && it.length() > 0L } == true)
                    }
                    else -> true
                }
            }

            val childNames = children.joinToString(limit = 8) { it.name }
            Timber.tag("Amazon").d(
                "[PARTIAL] appId=$appId partial=$hasPartialPayload reason=dir_scan path=$expectedPath children=$childNames"
            )
            return hasPartialPayload
        }

        /** Return [AmazonGame] for a product ID, or null if unavailable. */
        fun getAmazonGameOf(productId: String): AmazonGame? {
            return runBlocking(Dispatchers.IO) {
                instance?.amazonManager?.getGameById(productId)
            }
        }

        /** Return [AmazonGame] for an appId, or null if unavailable. */
        fun getAmazonGameByAppId(appId: Int): AmazonGame? {
            return runBlocking(Dispatchers.IO) {
                instance?.amazonManager?.getGameByAppId(appId)
            }
        }

        /** Return install path for [productId], or null if not installed. */
        fun getInstallPath(productId: String): String? {
            val game = getAmazonGameOf(productId) ?: return null
            return if (game.isInstalled && game.installPath.isNotEmpty()) game.installPath else null
        }

        /** Return install path for [appId], or null if not installed. */
        fun getInstallPathByAppId(appId: Int): String? {
            val game = getAmazonGameByAppId(appId) ?: return null
            return if (game.isInstalled && game.installPath.isNotEmpty()) game.installPath else null
        }

        /** Convert appId to productId via DB lookup. */
        fun getProductIdByAppId(appId: Int): String? {
            return getAmazonGameByAppId(appId)?.productId
        }

        /**
         * Resolves the effective launch executable for an Amazon game.
         * Returns empty string if no executable can be found.
         */
        fun getLaunchExecutable(containerId: String): String {
            val appId = runCatching { ContainerUtils.extractGameIdFromContainerId(containerId) }.getOrElse { return "" }
            if (appId <= 0) return ""

            val installPath = getInstallPathByAppId(appId) ?: return ""
            val installDir = File(installPath)
            if (!installDir.isDirectory) return ""

            val exeFile = ExecutableSelectionUtils.choosePrimaryExeFromDisk(
                installDir = installDir,
                gameName = installDir.name,
            ) ?: return ""

            return exeFile.path
        }

        /** Deprecated name kept for call-site compatibility — delegates to [getInstallPath]. */
        fun getInstalledGamePath(gameId: String): String? = getInstallPath(gameId)

        /** Check whether an installed game has a newer live version. */
        suspend fun isUpdatePending(productId: String): Boolean {
            val svc = instance ?: return false
            val game = svc.amazonManager.getGameById(productId) ?: return false
            if (!game.isInstalled || game.versionId.isEmpty()) return false
            val token = svc.amazonManager.getBearerToken() ?: return false
            return AmazonApiClient.isUpdateAvailable(productId, game.versionId, token) ?: false
        }

        // ── Download management ───────────────────────────────────────────────

        /** Returns the active [DownloadInfo] for [productId], or null if not downloading. */
        fun getDownloadInfo(productId: String): DownloadInfo? =
            getInstance()?.activeDownloads?.get(productId)

        fun getActiveDownloads(): Map<String, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        private suspend fun getPartialInstallPaths(instance: AmazonService): Set<String> {
            instance.importLegacyExternalLibraryIfNeeded()
            val roots = instance.gameLibraryRepository.getSnapshot().libraries
                .filter { it.source == GameSource.AMAZON }
                .map { AmazonLibraryLayout.installRoot(it.rootPath) }
            val scanned = roots.asSequence()
                .flatMap { root -> MarkerUtils.findResumablePartialInstalls(root).asSequence() }
            val games = instance.amazonManager.getAllGames().associateBy(AmazonGame::productId)
            val persisted = instance.storeDownloadTaskDao.getAll()
                .filter { it.store == DownloadStore.AMAZON }
                .mapNotNull { task ->
                    val game = games[task.gameKey]
                        ?: throw IllegalStateException("Amazon task references an unknown game: ${task.gameKey}")
                    instance.validateTaskPath(task, game).takeIf { File(it).exists() }
                }
            return (scanned + persisted.asSequence()).toSet()
        }

        suspend fun getPartialDownloads(context: Context): List<String> {
            val instance = getInstance() ?: return emptyList()
            instance.taskRecoveryJob?.join()
            val partialInstallPaths = getPartialInstallPaths(instance)
            if (partialInstallPaths.isEmpty()) return emptyList()
            val tasks = instance.storeDownloadTaskDao.getAll()
                .filter { it.store == DownloadStore.AMAZON }
                .associateBy(StoreDownloadTask::gameKey)

            return buildList {
                instance.amazonManager.getNonInstalledGames().forEach { game ->
                    if (instance.activeDownloads.containsKey(game.productId)) return@forEach
                    val task = tasks[game.productId]
                    val isPartial = if (task != null) {
                        partialInstallPaths.contains(task.installPath)
                    } else {
                        val matching = partialInstallPaths.filter {
                            File(it).name == AmazonConstants.gameDirectoryName(game.title)
                        }
                        require(matching.size <= 1) {
                            "Multiple Amazon partials match ${game.productId}: ${matching.joinToString()}"
                        }
                        matching.singleOrNull()?.let { path ->
                            instance.persistDiscoveredPartial(game, path)
                            true
                        } ?: false
                    }
                    if (isPartial) add(game.productId)
                }
            }
        }

        /** Returns the active [DownloadInfo] for [appId], or null if not downloading. */
        fun getDownloadInfoByAppId(appId: Int): DownloadInfo? {
            val productId = getProductIdByAppId(appId) ?: return null
            return getDownloadInfo(productId)
        }

        /** Cancel an in-progress download by [appId]. */
        fun cancelDownloadByAppId(appId: Int): Boolean {
            val productId = getProductIdByAppId(appId) ?: return false
            return cancelDownload(productId)
        }

        /** Check whether an installed game (by appId) has an update available. */
        suspend fun isUpdatePendingByAppId(appId: Int): Boolean {
            val productId = getProductIdByAppId(appId) ?: return false
            return isUpdatePending(productId)
        }

        /** Returns true if there is at least one active download. */
        fun hasActiveDownload(): Boolean =
            getInstance()?.activeDownloads?.isNotEmpty() == true

        /** Begins or resumes a download after resolving and persisting its exact managed path. */
        suspend fun downloadGame(
            context: Context,
            productId: String,
            installPath: String? = null,
            libraryId: String? = null,
        ): Result<DownloadInfo> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            // Already downloading?
            instance.activeDownloads[productId]?.let { existing ->
                Timber.tag("Amazon").w("Download already in progress for $productId")
                return Result.success(existing)
            }

            val game = withContext(Dispatchers.IO) {
                instance.amazonManager.getGameById(productId)
            } ?: return Result.failure(Exception("Game not found: $productId"))

            val task = try {
                instance.prepareDownloadTask(game, libraryId, installPath)
            } catch (exception: Exception) {
                Timber.tag("Amazon").e(exception, "Failed to prepare managed install for $productId")
                return Result.failure(exception)
            }
            val exactInstallPath = task.installPath

            val downloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = game.appId,
                downloadingAppIds = CopyOnWriteArrayList(),
            )
            downloadInfo.setPersistencePath(exactInstallPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(exactInstallPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            downloadInfo.setActive(true)
            instance.activeDownloads[productId] = downloadInfo
            instance.activeDownloadPaths[productId] = exactInstallPath

            // Fresh install/update run should clear stale completion marker before starting
            MarkerUtils.removeMarker(exactInstallPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            instance.storeDownloadTaskDao.updateState(
                DownloadStore.AMAZON,
                productId,
                StoreDownloadState.RUNNING,
                System.currentTimeMillis(),
            )

            PluviaApp.events.emitJava(
                AndroidEvent.DownloadStatusChanged(game.appId, true)
            )

            val job = instance.serviceScope.launch {
                try {
                    val result = instance.amazonDownloadManager.downloadGame(
                        context = context,
                        game = game,
                        installPath = exactInstallPath,
                        downloadInfo = downloadInfo,
                    )

                    if (result.isSuccess) {
                        Timber.tag("Amazon").i("Download succeeded for $productId")
                        downloadInfo.setActive(false)
                        downloadInfo.clearPersistedBytesDownloaded(exactInstallPath)
                        instance.storeDownloadTaskDao.delete(DownloadStore.AMAZON, productId)
                        SnackbarManager.show("Download completed: ${game.title}")
                        PluviaApp.events.emitJava(
                            AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON)
                        )
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.tag("Amazon").e(error, "Download failed for $productId")
                        downloadInfo.setActive(false)
                        instance.storeDownloadTaskDao.updateState(
                            DownloadStore.AMAZON,
                            productId,
                            StoreDownloadState.FAILED,
                            System.currentTimeMillis(),
                        )
                        SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Timber.tag("Amazon").d("Download cancelled for $productId")
                        instance.storeDownloadTaskDao.updateState(
                            DownloadStore.AMAZON,
                            productId,
                            StoreDownloadState.PAUSED,
                            System.currentTimeMillis(),
                        )
                    } else {
                        Timber.tag("Amazon").e(e, "Download exception for $productId")
                        instance.storeDownloadTaskDao.updateState(
                            DownloadStore.AMAZON,
                            productId,
                            StoreDownloadState.FAILED,
                            System.currentTimeMillis(),
                        )
                    }
                    downloadInfo.setActive(false)
                } finally {
                    instance.activeDownloads.remove(productId)
                    instance.activeDownloadPaths.remove(productId)
                    PluviaApp.events.emitJava(
                        AndroidEvent.DownloadStatusChanged(game.appId, false)
                    )
                }
            }

            downloadInfo.setDownloadJob(job)
            return Result.success(downloadInfo)
        }

        /** Cancel an in-progress download for [productId]. */
        fun cancelDownload(productId: String): Boolean {
            val instance = getInstance() ?: return false
            val downloadInfo = instance.activeDownloads[productId] ?: run {
                Timber.tag("Amazon").w("No active download for $productId")
                return false
            }
            Timber.tag("Amazon").i("Cancelling download for $productId")
            downloadInfo.cancel()
            return true
        }

        suspend fun deleteGame(context: Context, productId: String): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            return withContext(Dispatchers.IO) {
                try {
                    val game = instance.amazonManager.getGameById(productId)
                        ?: return@withContext Result.failure(Exception("Game not found: $productId"))

                    val path = instance.resolveExistingPath(game)
                        ?: return@withContext Result.failure(
                            IllegalStateException("No managed Amazon installation found: $productId"),
                        )
                    if (!instance.isRegisteredGamePath(path)) {
                        return@withContext Result.failure(
                            SecurityException("Amazon install path is outside registered libraries: $path"),
                        )
                    }
                    if (File(path).exists()) {
                        val installDir = File(path)
                        val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")

                        if (manifestFile.exists()) {
                            // ── Manifest-based uninstall ─────────────────────────
                            Timber.tag("Amazon").i("Manifest-based uninstall for $productId")
                            try {
                                val manifest = AmazonManifest.parse(manifestFile.readBytes())
                                var deletedFiles = 0
                                var failedFiles = 0

                                for (mf in manifest.allFiles) {
                                    val file = File(installDir, mf.unixPath)
                                    if (!AmazonConstants.isGameInstallPath(installDir.path, file.path)) {
                                        return@withContext Result.failure(
                                            SecurityException(
                                                "Amazon manifest path escapes installation: ${mf.unixPath}",
                                            ),
                                        )
                                    }
                                    if (file.exists()) {
                                        if (file.delete()) {
                                            deletedFiles++
                                        } else {
                                            failedFiles++
                                            Timber.tag("Amazon").w("Failed to delete: ${file.absolutePath}")
                                        }
                                    }
                                }

                                // Walk directories bottom-up and remove empty ones
                                val dirs = mutableSetOf<File>()
                                for (mf in manifest.allFiles) {
                                    var parent = File(installDir, mf.unixPath).parentFile
                                    while (parent != null && parent != installDir && parent.toPath().startsWith(installDir.toPath())) {
                                        dirs.add(parent)
                                        parent = parent.parentFile
                                    }
                                }
                                // Sort deepest-first so child dirs are removed before parents
                                for (dir in dirs.sortedByDescending { it.absolutePath.length }) {
                                    if (dir.exists() && dir.isDirectory && (dir.listFiles()?.isEmpty() == true)) {
                                        dir.delete()
                                    }
                                }

                                // Remove the install dir itself if it's now empty
                                if (installDir.exists() && installDir.isDirectory &&
                                    (installDir.listFiles()?.isEmpty() == true)
                                ) {
                                    installDir.delete()
                                }

                                if (failedFiles > 0) {
                                    return@withContext Result.failure(
                                        IllegalStateException("Failed to delete $failedFiles Amazon game files at $path"),
                                    )
                                }
                                Timber.tag("Amazon").i(
                                    "Manifest-based uninstall complete: $deletedFiles deleted, $failedFiles failed"
                                )
                            } catch (e: Exception) {
                                Timber.tag("Amazon").w(e, "Manifest parse failed — falling back to recursive delete")
                                if (!installDir.deleteRecursively() || installDir.exists()) {
                                    return@withContext Result.failure(
                                        IllegalStateException("Failed to delete Amazon installation: $path"),
                                    )
                                }
                            }
                        } else {
                            // ── Fallback: recursive delete ───────────────────────
                            Timber.tag("Amazon").i("No cached manifest — recursive delete: $path")
                            if (!installDir.deleteRecursively() || installDir.exists()) {
                                return@withContext Result.failure(
                                    IllegalStateException("Failed to delete Amazon installation: $path"),
                                )
                            }
                        }

                        MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                        MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                        // Remove metadata residue and ensure uninstall leaves no resumable state behind.
                        val downloadInfoDir = File(installDir, ".DownloadInfo")
                        if (downloadInfoDir.exists()) {
                            downloadInfoDir.deleteRecursively()
                        }

                        if (installDir.exists()) {
                            val installCanonical = installDir.canonicalFile
                            if (!instance.isRegisteredGamePath(installCanonical.path)) {
                                return@withContext Result.failure(
                                    SecurityException("Amazon cleanup path left registered libraries: ${installCanonical.path}"),
                                )
                            }
                            if (!installCanonical.deleteRecursively() || installCanonical.exists()) {
                                return@withContext Result.failure(
                                    IllegalStateException("Failed final Amazon cleanup: ${installCanonical.path}"),
                                )
                            }

                            Timber.tag("Amazon").i(
                                "[UNINSTALL] cleanup productId=$productId installDirExists=${installCanonical.exists()} path=${installCanonical.path}"
                            )
                        }
                    }

                    instance.amazonManager.markUninstalled(productId)
                    instance.storeDownloadTaskDao.delete(DownloadStore.AMAZON, productId)

                    // Delete cached manifest
                    try {
                        val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")
                        if (manifestFile.exists()) {
                            manifestFile.delete()
                            Timber.tag("Amazon").d("Deleted cached manifest for $productId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Amazon").w(e, "Failed to delete cached manifest (non-fatal)")
                    }

                    withContext(Dispatchers.Main) {
                        ContainerUtils.deleteContainer(context, "AMAZON_${game.appId}")
                    }

                    val postUninstallPath = path
                    val postInstallDirExists = File(postUninstallPath).exists()
                    val completeMarkerExists = MarkerUtils.hasMarker(postUninstallPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    val inProgressMarkerExists = MarkerUtils.hasMarker(postUninstallPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    Timber.tag("Amazon").i(
                        "[UNINSTALL] final_state productId=$productId appId=${game.appId} installDirExists=$postInstallDirExists completeMarker=$completeMarkerExists inProgressMarker=$inProgressMarkerExists"
                    )

                    PluviaApp.events.emitJava(
                        AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON)
                    )

                    Timber.tag("Amazon").i("Game uninstalled: $productId")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Failed to uninstall $productId")
                    Result.failure(e)
                }
            }
        }

        // ── Game verification ─────────────────────────────────────────────────

        /** Result of verifying installed files against cached manifest. */
        data class VerificationResult(
            val totalFiles: Int,
            val verifiedOk: Int,
            val missingFiles: Int,
            val sizeMismatch: Int,
            val hashMismatch: Int,
            val failedFiles: List<String>,
        ) {
            val isValid: Boolean get() = failedFiles.isEmpty()
        }

        /** Verify installed files for [productId] against cached manifest. */
        suspend fun verifyGame(context: Context, productId: String): Result<VerificationResult> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            return withContext(Dispatchers.IO) {
                try {
                    val game = instance.amazonManager.getGameById(productId)
                        ?: return@withContext Result.failure(Exception("Game not found: $productId"))

                    if (!game.isInstalled || game.installPath.isEmpty()) {
                        return@withContext Result.failure(Exception("Game is not installed"))
                    }

                    val installDir = File(game.installPath)
                    if (!installDir.exists()) {
                        return@withContext Result.failure(Exception("Install directory not found: ${game.installPath}"))
                    }

                    val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")
                    if (!manifestFile.exists()) {
                        return@withContext Result.failure(Exception("No cached manifest — reinstall to enable verification"))
                    }

                    val manifest = AmazonManifest.parse(manifestFile.readBytes())
                    val files = manifest.allFiles

                    Timber.tag("Amazon").i("Verifying ${files.size} files for $productId at ${game.installPath}")

                    var verifiedOk = 0
                    var missingFiles = 0
                    var sizeMismatch = 0
                    var hashMismatch = 0
                    val failedFiles = mutableListOf<String>()

                    for (mf in files) {
                        val file = File(installDir, mf.unixPath)

                        if (!file.exists()) {
                            missingFiles++
                            failedFiles.add(mf.unixPath)
                            Timber.tag("Amazon").d("Verify MISSING: ${mf.unixPath}")
                            continue
                        }

                        if (file.length() != mf.size) {
                            sizeMismatch++
                            failedFiles.add(mf.unixPath)
                            Timber.tag("Amazon").d(
                                "Verify SIZE MISMATCH: ${mf.unixPath} (expected=${mf.size}, actual=${file.length()})"
                            )
                            continue
                        }

                        // SHA-256 check (algorithm 0) — skip if hash not available
                        if (mf.hashAlgorithm == 0 && mf.hashBytes.isNotEmpty()) {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            file.inputStream().buffered().use { input ->
                                val buf = ByteArray(8192)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    digest.update(buf, 0, read)
                                }
                            }
                            val computed = digest.digest()
                            if (!computed.contentEquals(mf.hashBytes)) {
                                hashMismatch++
                                failedFiles.add(mf.unixPath)
                                Timber.tag("Amazon").d("Verify HASH MISMATCH: ${mf.unixPath}")
                                continue
                            }
                        }

                        verifiedOk++
                    }

                    val result = VerificationResult(
                        totalFiles = files.size,
                        verifiedOk = verifiedOk,
                        missingFiles = missingFiles,
                        sizeMismatch = sizeMismatch,
                        hashMismatch = hashMismatch,
                        failedFiles = failedFiles,
                    )

                    if (result.isValid) {
                        Timber.tag("Amazon").i("Verification PASSED: ${result.verifiedOk}/${result.totalFiles} files OK")
                    } else {
                        Timber.tag("Amazon").w(
                            "Verification FAILED: ${result.verifiedOk}/${result.totalFiles} OK, " +
                                "${result.missingFiles} missing, ${result.sizeMismatch} size mismatch, " +
                                "${result.hashMismatch} hash mismatch"
                        )
                    }

                    Result.success(result)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Verification failed for $productId")
                    Result.failure(e)
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        stop()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        taskRecoveryJob = serviceScope.launch { recoverInterruptedTasks() }
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.emit(AndroidEvent.ServiceReady)
        Timber.i("[Amazon] Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_AMAZON, "Connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_AMAZON, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_AMAZON, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_AMAZON)

        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[Amazon] Manual sync requested — bypassing throttle")
                true
            }
            ACTION_SYNC_LIBRARY -> {
                Timber.i("[Amazon] Automatic sync requested")
                true
            }
            ACTION_DOWNLOAD_RECOVERY -> false
            null -> false
            else -> {
                Timber.d("[Amazon] Service started without sync action")
                false
            }
        }

        if (shouldSync) {
            if (syncInProgress) {
                Timber.i("[Amazon] Sync already in progress — ignoring duplicate request")
            } else {
                backgroundSyncJob = serviceScope.launch { syncLibrary() }
            }
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[Amazon] Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_AMAZON)
        instance = null
        super.onDestroy()
        Timber.i("[Amazon] Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.i("[Amazon] Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.i("[Amazon] Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Instance helpers (for callers that hold a direct reference) ───────────

    /** Instance-method accessor for callers using [getInstance]?. */
    fun getInstalledGamePath(gameId: String): String? = getInstallPath(gameId)

    /** Normalizes only Amazon tasks interrupted by process death without touching active stores. */
    private suspend fun recoverInterruptedTasks() {
        val recovered = storeDownloadTaskDao.markInterruptedAsPaused(
            DownloadStore.AMAZON,
            System.currentTimeMillis(),
        )
        if (recovered > 0) Timber.tag("Amazon").i("Recovered $recovered interrupted Amazon task(s)")
    }

    /** Resolves and persists an exact registered-library target before any download work starts. */
    private suspend fun prepareDownloadTask(
        game: AmazonGame,
        requestedLibraryId: String?,
        requestedInstallPath: String?,
    ): StoreDownloadTask {
        taskRecoveryJob?.join()
        require(activeDownloads[game.productId]?.isActive() != true) {
            "Amazon game ${game.productId} is already downloading"
        }
        val existingTask = storeDownloadTaskDao.find(DownloadStore.AMAZON, game.productId)
        val snapshot = gameLibraryRepository.getSnapshot()
        val libraries = snapshot.libraries.filter { it.source == GameSource.AMAZON }
        val selectedLibraryId = existingTask?.libraryId
            ?: game.installPath.takeIf { game.isInstalled && it.isNotBlank() }
                ?.let { path -> findOwningLibrary(libraries, path)?.id }
            ?: requestedLibraryId
            ?: requestedInstallPath?.let { path -> findOwningLibrary(libraries, path)?.id }
            ?: snapshot.defaultLibraryIds.getValue(GameSource.AMAZON)
        val installation = gameLibraryRepository.resolveInstallation(GameSource.AMAZON, selectedLibraryId)
        val resolvedPath = when {
            existingTask != null -> AmazonDownloadTaskLocation.validateTask(
                existingTask,
                installation,
                game.productId,
                game.appId,
                game.title,
            )
            game.isInstalled && game.installPath.isNotBlank() ->
                AmazonDownloadTaskLocation.validateInstallPath(installation, game.installPath, game.title)
            requestedInstallPath != null ->
                AmazonDownloadTaskLocation.validateInstallPath(installation, requestedInstallPath, game.title)
            else -> AmazonConstants.getGameInstallPath(installation.installRoot, game.title)
        }
        val now = System.currentTimeMillis()
        val task = StoreDownloadTask(
            store = DownloadStore.AMAZON,
            gameKey = game.productId,
            appId = game.appId,
            libraryId = installation.library.id,
            libraryRoot = installation.library.rootPath,
            installPath = resolvedPath,
            operation = existingTask?.operation ?: if (game.isInstalled) {
                StoreDownloadOperation.UPDATE
            } else {
                StoreDownloadOperation.INSTALL
            },
            state = StoreDownloadState.PREPARING,
            createdAt = existingTask?.createdAt ?: now,
            updatedAt = now,
        )
        storeDownloadTaskDao.upsert(task)
        return task
    }

    /** Finds the single Amazon library whose exact direct game area owns [installPath]. */
    private fun findOwningLibrary(libraries: List<GameLibrary>, installPath: String): GameLibrary? =
        libraries.singleOrNull { library ->
            val canonicalRoot = File(AmazonLibraryLayout.installRoot(library.rootPath)).canonicalFile
            File(installPath).canonicalFile.parentFile == canonicalRoot
        }

    /** Finds a complete install from DB first, then all registered Amazon libraries. */
    private suspend fun findCompletedInstallPath(game: AmazonGame): String? {
        if (game.installPath.isNotBlank() &&
            MarkerUtils.hasMarker(validateInstalledPath(game), Marker.DOWNLOAD_COMPLETE_MARKER)
        ) {
            return validateInstalledPath(game)
        }
        val directoryName = AmazonConstants.gameDirectoryName(game.title)
        return gameLibraryRepository.getSnapshot().libraries.asSequence()
            .filter { it.source == GameSource.AMAZON }
            .map { File(AmazonLibraryLayout.installRoot(it.rootPath), directoryName).path }
            .firstOrNull { MarkerUtils.hasMarker(it, Marker.DOWNLOAD_COMPLETE_MARKER) }
    }

    /** Resolves existing state and rejects stale or tampered DB/task paths before destruction. */
    private suspend fun resolveExistingPath(game: AmazonGame): String? {
        val task = storeDownloadTaskDao.find(DownloadStore.AMAZON, game.productId)
        if (task != null) {
            return validateTaskPath(task, game)
        }
        val path = game.installPath.takeIf(String::isNotBlank) ?: findCompletedInstallPath(game) ?: return null
        val library = findOwningLibrary(
            gameLibraryRepository.getSnapshot().libraries.filter { it.source == GameSource.AMAZON },
            path,
        ) ?: throw SecurityException("Amazon install path is outside registered libraries: $path")
        return AmazonDownloadTaskLocation.validateInstallPath(
            amazonInstallation(library),
            path,
            game.title,
        )
    }

    /** Resolves a task's registered library again and validates every persisted identity field. */
    private suspend fun validateTaskPath(task: StoreDownloadTask, game: AmazonGame): String {
        val installation = gameLibraryRepository.resolveInstallation(GameSource.AMAZON, task.libraryId)
        return AmazonDownloadTaskLocation.validateTask(
            task,
            installation,
            game.productId,
            game.appId,
            game.title,
        )
    }

    /** Returns the validated durable location for [game], or null when it has no task. */
    private suspend fun validatedTaskPath(game: AmazonGame): String? =
        storeDownloadTaskDao.find(DownloadStore.AMAZON, game.productId)?.let { validateTaskPath(it, game) }

    /** Validates an Amazon catalog DB path against its registered library and expected game name. */
    private suspend fun validateInstalledPath(game: AmazonGame): String {
        val libraries = gameLibraryRepository.getSnapshot().libraries.filter { it.source == GameSource.AMAZON }
        val library = findOwningLibrary(libraries, game.installPath)
            ?: throw SecurityException("Amazon DB install path is outside registered libraries: ${game.installPath}")
        return AmazonDownloadTaskLocation.validateInstallPath(
            amazonInstallation(library),
            game.installPath,
            game.title,
        )
    }

    /** Builds the immutable Amazon layout used to validate discovery and destructive operations. */
    private fun amazonInstallation(library: GameLibrary): GameLibraryInstallation =
        GameLibraryInstallation(
            library = library,
            installRoot = AmazonLibraryLayout.installRoot(library.rootPath),
            stagingRoot = AmazonLibraryLayout.stagingRoot(library.rootPath),
        )

    /** Enforces that recursive operations stay beneath any registered Amazon library. */
    private suspend fun isRegisteredGamePath(path: String): Boolean =
        findOwningLibrary(
            gameLibraryRepository.getSnapshot().libraries.filter { it.source == GameSource.AMAZON },
            path,
        ) != null

    /** Persists a marker-backed pre-library partial so recovery no longer depends on guesses. */
    private suspend fun persistDiscoveredPartial(game: AmazonGame, path: String) {
        if (storeDownloadTaskDao.find(DownloadStore.AMAZON, game.productId) != null) return
        val snapshot = gameLibraryRepository.getSnapshot()
        val library = findOwningLibrary(
            snapshot.libraries.filter { it.source == GameSource.AMAZON },
            path,
        ) ?: throw IllegalStateException("Amazon partial is outside registered libraries: $path")
        val now = System.currentTimeMillis()
        storeDownloadTaskDao.upsert(
            StoreDownloadTask(
                store = DownloadStore.AMAZON,
                gameKey = game.productId,
                appId = game.appId,
                libraryId = library.id,
                libraryRoot = library.rootPath,
                installPath = path,
                state = StoreDownloadState.PAUSED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Registers the old `Amazon/games` root when it still contains complete or partial installs. */
    private suspend fun importLegacyExternalLibraryIfNeeded() {
        if (PrefManager.externalStoragePath.isBlank()) return
        val legacyRoot = File(AmazonConstants.externalAmazonGamesPath()).canonicalFile
        val hasManagedInstall = legacyRoot.listFiles()?.any { gameDir ->
            gameDir.isDirectory &&
                (MarkerUtils.hasMarker(gameDir.path, Marker.DOWNLOAD_COMPLETE_MARKER) ||
                    MarkerUtils.hasResumablePartialInstall(gameDir.path))
        } == true
        if (!hasManagedInstall) return
        val snapshot = gameLibraryRepository.getSnapshot()
        if (snapshot.libraries.any {
                it.source == GameSource.AMAZON && File(it.rootPath).canonicalFile == legacyRoot
            }
        ) {
            return
        }
        val previousDefault = snapshot.defaultLibraryIds.getValue(GameSource.AMAZON)
        try {
            gameLibraryRepository.addLibrary(GameSource.AMAZON, legacyRoot.path)
            gameLibraryRepository.setDefaultLibrary(GameSource.AMAZON, previousDefault)
            Timber.tag("Amazon").i("Imported legacy Amazon library: ${legacyRoot.path}")
        } catch (exception: Exception) {
            Timber.tag("Amazon").e(exception, "Failed to import legacy Amazon library: ${legacyRoot.path}")
            throw exception
        }
    }

    /** Reconciles complete markers found in any registered library back into the catalog DB. */
    private suspend fun reconcileRegisteredInstallations() {
        amazonManager.getAllGames().forEach { game ->
            val path = findCompletedInstallPath(game) ?: return@forEach
            if (!game.isInstalled || File(game.installPath).canonicalFile != File(path).canonicalFile) {
                amazonManager.markInstalled(game.productId, path, game.installSize, game.versionId)
                storeDownloadTaskDao.delete(DownloadStore.AMAZON, game.productId)
            }
        }
    }

    private suspend fun syncLibrary() {
        catalogSyncMutex.withLock {
        setSyncInProgress(true)
        try {
            amazonManager.refreshLibrary().getOrThrow()
            importLegacyExternalLibraryIfNeeded()
            reconcileRegisteredInstallations()
            lastSyncTimestamp = System.currentTimeMillis()
            hasPerformedInitialSync = true
            Timber.i("[Amazon] Sync complete — next auto-sync in 15 minutes")
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Library sync failed")
        } finally {
            setSyncInProgress(false)
        }
        }
    }

}
