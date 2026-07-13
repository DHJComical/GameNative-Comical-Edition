package app.gamenative

import android.os.StrictMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.data.library.GameLibraryOperations
import app.gamenative.data.library.InstalledCatalogIdentitySource
import app.gamenative.data.library.InstalledCatalogIdentitySignature
import app.gamenative.data.library.InstalledLibrarySynchronizer
import app.gamenative.events.EventDispatcher
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.GameRuntimeLifecycleRegistryImpl
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.utils.ContainerMigrator
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.PlayIntegrity
import app.gamenative.utils.downloader.ContainerFilesDownloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.google.android.play.core.splitcompat.SplitCompatApplication
import com.posthog.PersonProfiles

// Add PostHog imports
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : SplitCompatApplication() {

    @Inject lateinit var gogGameDao: GOGGameDao
    @Inject lateinit var amazonGameDao: AmazonGameDao
    @Inject lateinit var gameLibraryOperations: GameLibraryOperations
    @Inject lateinit var installedCatalogIdentitySource: InstalledCatalogIdentitySource
    @Inject lateinit var installedLibrarySynchronizer: InstalledLibrarySynchronizer

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        preloadSystemLibraries()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        NetworkMonitor.init(this)

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Init our datastore preferences.
        PrefManager.init(this)
        FrontendSyncManager.init(this)

        // Initialize GOGConstants
        app.gamenative.service.gog.GOGConstants.init(this)

        DownloadService.populateDownloadService(this)

        migrateGogAmazonPaths()

        appScope.launch {
            recoverAndStartInstalledLibrarySynchronization(
                gameLibraryOperations,
                installedLibrarySynchronizer,
                installedCatalogIdentitySource,
                appScope,
            )
        }

        appScope.launch {
            ContainerMigrator.migrateLegacyContainersIfNeeded(
                context = applicationContext,
                onProgressUpdate = null,
                onComplete = null
            )
        }

        // Preload all container files in the background
        appScope.launch {
            ContainerFilesDownloader.preloadAllContainerFiles(applicationContext)
        }

        // Clear any stale temporary config overrides from previous app sessions
        try {
            IntentLaunchManager.clearAllTemporaryOverrides()
            Timber.d("[PluviaApp]: Cleared temporary config overrides from previous session")
        } catch (e: Exception) {
            Timber.e(e, "[PluviaApp]: Failed to clear temporary config overrides")
        }

        // Initialize PostHog Analytics
        val postHogConfig = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = BuildConfig.POSTHOG_HOST,
        ).apply {
            /* turn every event into an identified one */
            personProfiles = PersonProfiles.ALWAYS
        }
        PostHogAndroid.setup(this, postHogConfig)

        if (PrefManager.usageAnalyticsEnabled) {
            com.posthog.PostHog.capture(
                event = "\$set",
                properties = mapOf(
                    "\$set" to mapOf("recommendation_enabled" to PrefManager.showRecommendations),
                ),
            )
        }

        PlayIntegrity.warmUp(this)

    }

    /**
     * One-time migration: moves GOG/Amazon game directories from
     * {filesDir}/ to {dataDir}/ to match Steam/Epic, and updates DB paths.
     */
    private fun migrateGogAmazonPaths() {
        if (PrefManager.gogAmazonPathMigrated) return

        val dataDir = dataDir.path
        val filesDir = filesDir.absolutePath
        Timber.i("[Migration] Migrating GOG/Amazon install paths from $filesDir to $dataDir")

        val migrations = listOf(
            File(filesDir, "GOG") to File(dataDir, "GOG"),
            File(filesDir, "Amazon") to File(dataDir, "Amazon"),
        )

        for ((oldDir, newDir) in migrations) {
            if (!oldDir.exists()) continue
            if (newDir.exists()) {
                Timber.w("[Migration] Target already exists, skipping rename: ${newDir.path}")
                continue
            }
            val renamed = oldDir.renameTo(newDir)
            if (renamed) {
                Timber.i("[Migration] Renamed ${oldDir.path} -> ${newDir.path}")
            } else {
                Timber.w("[Migration] Failed to rename ${oldDir.path} -> ${newDir.path}")
            }
        }

        val oldPrefix = "$filesDir/"
        val newPrefix = "$dataDir/"

        runBlocking(Dispatchers.IO) {
            try {
                val gogGames = gogGameDao.getAllAsList()
                for (game in gogGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val updated = game.copy(installPath = game.installPath.replace(oldPrefix, newPrefix))
                        gogGameDao.update(updated)
                    }
                }
                Timber.i("[Migration] Updated ${gogGames.count { it.installPath.contains(oldPrefix) }} GOG install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update GOG DB paths")
            }

            try {
                val amazonGames = amazonGameDao.getAllAsList()
                for (game in amazonGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val newPath = game.installPath.replace(oldPrefix, newPrefix)
                        amazonGameDao.markAsInstalled(game.productId, newPath, game.installSize, game.versionId)
                    }
                }
                Timber.i("[Migration] Updated ${amazonGames.count { it.installPath.contains(oldPrefix) }} Amazon install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update Amazon DB paths")
            }
        }

        PrefManager.gogAmazonPathMigrated = true
        Timber.i("[Migration] GOG/Amazon path migration complete")
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var achievementWatcher: app.gamenative.service.AchievementWatcher? = null

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        private val runtimeTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val runtimeTeardownMutex = Mutex()
        private const val SETUP_TEARDOWN_TIMEOUT_MILLIS = 5_000L

        /** Requests teardown and returns immediately without blocking the caller thread. */
        fun shutdownEnvironment() {
            GameRuntimeLifecycleRegistryImpl.requestStop()
            runtimeTeardownScope.launch { shutdownEnvironmentAndAwait() }
        }

        /** Completes runtime teardown without ever waiting on the caller thread. */
        suspend fun shutdownEnvironmentAndAwait() = runtimeTeardownMutex.withLock {
            val runtimeToken = GameRuntimeLifecycleRegistryImpl.requestStop()
            if (runtimeToken != null) {
                val finishedInTime = GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(
                    runtimeToken,
                    SETUP_TEARDOWN_TIMEOUT_MILLIS,
                )
                if (!finishedInTime) {
                    Timber.e("Runtime setup teardown timed out; remaining STOPPING until setup exits")
                    GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(
                        runtimeToken,
                        GameRuntimeLifecycleRegistryImpl.WAIT_FOREVER,
                    )
                }
            }
            val env = xEnvironment
            Timber.i("shutdownEnvironment: env=%s", env != null)

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { achievementWatcher?.stop() }
                .onFailure { Timber.e(it, "shutdownEnvironment: achievementWatcher.stop") }
            runCatching { SteamService.clearCachedAchievements() }
                .onFailure { Timber.e(it, "shutdownEnvironment: clearCachedAchievements") }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Timber.e(it, "shutdownEnvironment: stopEnvironmentComponents") }

            withContext(Dispatchers.Main.immediate) {
                runCatching { touchpadView?.releasePointerCapture() }
                    .onFailure { Timber.e(it, "shutdownEnvironment: releasePointerCapture") }
                xEnvironment = null
                inputControlsView = null
                inputControlsManager = null
                touchpadView = null
                achievementWatcher = null
                clearActiveSuspendState()
            }
            ActiveGameRegistry.clear()
            GameRuntimeLifecycleRegistryImpl.markStopped(runtimeToken)
            SteamService.keepAlive = false
            SteamService.clearPlayingConflict()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    }

    /**
     * Some native libraries we dlopen at runtime (libsteamclient.so via SteamBootstrap,
     * the lsfg-vk layer, etc.) depend on `libjpeg.so`, which isn't on every device's
     * dynamic linker search path. Pre-load the system copy here with RTLD_GLOBAL
     * semantics (System.load is global) so all subsequent dlopens find its symbols.
     *
     * Single place for all: runs once in Application.onCreate before any other
     * native lib is loaded by this process. Failures are non-fatal — devices that
     * don't have the file (or have it elsewhere) just fall through.
     */
    private fun preloadSystemLibraries() {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val candidates = if (is64) {
            listOf("/system/lib64/libjpeg.so", "/system/lib/libjpeg.so")
        } else {
            listOf("/system/lib/libjpeg.so", "/system/lib64/libjpeg.so")
        }
        for (path in candidates) {
            if (!File(path).exists()) continue
            try {
                System.load(path)
                Timber.i("[PluviaApp]: Preloaded $path")
                return
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: System.load($path) failed")
            }
        }
        Timber.w("[PluviaApp]: Could not preload system libjpeg.so (none of the candidate paths worked)")
    }
}

/** Runs installed-library discovery only after migration recovery establishes a consistent filesystem. */
internal suspend fun recoverAndStartInstalledLibrarySynchronization(
    operations: GameLibraryOperations,
    synchronizer: InstalledLibrarySynchronizer,
    identitySource: InstalledCatalogIdentitySource,
    observerScope: CoroutineScope,
): Job? {
    val recovery = try {
        operations.recoverMigrations()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Timber.e(exception, "Library migration startup recovery failed; installed-library scan skipped")
        return null
    }
    if (recovery.pendingTransactionIds.isNotEmpty()) {
        Timber.e(
            "Library migration recovery remains pending=%s; installed-library scan skipped",
            recovery.pendingTransactionIds,
        )
        return null
    }
    if (recovery.removedTransactions > 0) {
        Timber.i(
            "Library migration recovery removed=%d",
            recovery.removedTransactions,
        )
    }
    return startInstalledLibrarySynchronization(observerScope, identitySource, synchronizer)
}

/** Subscribes to a baseline catalog signature before scanning so concurrent catalog upserts cannot be missed. */
internal suspend fun startInstalledLibrarySynchronization(
    observerScope: CoroutineScope,
    identitySource: InstalledCatalogIdentitySource,
    synchronizer: InstalledLibrarySynchronizer,
    initialRetryDelayMillis: Long = 1_000L,
    maxRetryDelayMillis: Long = 30_000L,
    retryDelay: suspend (Long) -> Unit = { delayMillis -> delay(delayMillis) },
): Job {
    require(initialRetryDelayMillis in 1..maxRetryDelayMillis) {
        "Catalog observer retry delay bounds are invalid"
    }
    val baselineReady = CompletableDeferred<CancellationException?>()
    val observer = observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var hasSignature = false
        var lastSignature: InstalledCatalogIdentitySignature? = null
        var nextRetryDelayMillis = initialRetryDelayMillis
        try {
            while (true) {
                var emitted = false
                try {
                    identitySource.observeIdentitySignatures()
                        .distinctUntilChanged()
                        .collect { signature ->
                            emitted = true
                            nextRetryDelayMillis = initialRetryDelayMillis
                            if (!hasSignature) {
                                hasSignature = true
                                lastSignature = signature
                                baselineReady.complete(null)
                            } else if (signature != lastSignature) {
                                lastSignature = signature
                                synchronizeInstalledLibraries(synchronizer, "catalog identity change")
                            }
                        }
                    Timber.w("Installed catalog identity observer completed; retrying")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Timber.e(exception, "Installed catalog identity observer failed; retrying")
                }
                retryDelay(nextRetryDelayMillis)
                if (!emitted) {
                    nextRetryDelayMillis = (nextRetryDelayMillis * 2).coerceAtMost(maxRetryDelayMillis)
                }
            }
        } catch (exception: CancellationException) {
            baselineReady.complete(exception)
            throw exception
        }
    }
    try {
        baselineReady.await()?.let { throw it }
    } catch (exception: CancellationException) {
        observer.cancel()
        throw exception
    }
    try {
        synchronizeInstalledLibraries(synchronizer, "startup")
    } catch (throwable: Throwable) {
        observer.cancel()
        throw throwable
    }
    return observer
}

/** Logs only systemic synchronization failures; isolated store failures are represented in the result. */
private suspend fun synchronizeInstalledLibraries(
    synchronizer: InstalledLibrarySynchronizer,
    trigger: String,
) {
    try {
        synchronizer.synchronizeAll()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Timber.e(exception, "Installed-library synchronization failed for trigger=%s", trigger)
    }
}
