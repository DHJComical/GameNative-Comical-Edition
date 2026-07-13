package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Summary for one store in a completed installed-library synchronization. */
data class InstalledStoreSynchronizationSummary(
    /** Store whose registered libraries were scanned. */
    val source: GameSource,
    /** Stable identifiers of every registered library supplied to the store scanner. */
    val libraryIds: List<String>,
    /** Number of unambiguous positive installations accepted for reconciliation. */
    val reconciledInstallCount: Int,
    /** Number of isolated conflicts, malformed records, or unsupported directories. */
    val issueCount: Int,
    /** Store-level failure when this store could not complete; null for a completed scan. */
    val failure: InstalledStoreSynchronizationFailure? = null,
)

/** Stable diagnostic details for one isolated store synchronization failure. */
data class InstalledStoreSynchronizationFailure(
    /** Runtime exception type retained without exposing a mutable Throwable in result state. */
    val exceptionType: String,
    /** Exception detail suitable for diagnostics and UI-independent reporting. */
    val message: String,
)

/** Complete per-store result from one serialized synchronization pass. */
data class InstalledLibrarySynchronizationResult(
    /** Summaries in deterministic managed-store order. */
    val stores: List<InstalledStoreSynchronizationSummary>,
)

/** Synchronizes trusted installed-game evidence across every registered managed library. */
interface InstalledLibrarySynchronizer {
    /**
     * Reads the current library snapshot, scans all libraries grouped by store, and persists only
     * positive installation evidence. Default-library preferences do not participate in discovery.
     */
    suspend fun synchronizeAll(): InstalledLibrarySynchronizationResult

    /**
     * Executes a library mutation while synchronization is idle and prevents a new scan from
     * starting until [block] completes.
     */
    suspend fun <T> withSynchronizationIdle(block: suspend () -> T): T
}

/** Mutex-serialized implementation coordinating the four store-specific discovery boundaries. */
class InstalledLibrarySynchronizerImpl(
    private val repository: GameLibraryRepository,
    private val steamReconciler: SteamInstallReconciler,
    private val gogReconciler: GogInstallReconciler,
    private val epicDiscovery: EpicInstallDiscovery,
    private val epicReconciler: EpicInstallReconciler,
    private val amazonDiscovery: AmazonInstallDiscovery,
    private val amazonReconciler: AmazonInstallReconciler,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
) : InstalledLibrarySynchronizer {
    private val synchronizationMutex = Mutex()

    override suspend fun synchronizeAll(): InstalledLibrarySynchronizationResult = synchronizationMutex.withLock {
        val librariesBySource = repository.getSnapshot().libraries.groupBy(GameLibrary::source)
        InstalledLibrarySynchronizationResult(
            stores = listOf(
                synchronizeStore(GameSource.STEAM, librariesBySource) { synchronizeSteam(it) },
                synchronizeStore(GameSource.GOG, librariesBySource) { synchronizeGog(it) },
                synchronizeStore(GameSource.EPIC, librariesBySource) { synchronizeEpic(it) },
                synchronizeStore(GameSource.AMAZON, librariesBySource) { synchronizeAmazon(it) },
            ),
        )
    }

    override suspend fun <T> withSynchronizationIdle(block: suspend () -> T): T =
        synchronizationMutex.withLock { block() }

    private suspend fun synchronizeStore(
        source: GameSource,
        librariesBySource: Map<GameSource, List<GameLibrary>>,
        synchronize: suspend (List<GameLibrary>) -> InstalledStoreSynchronizationSummary,
    ): InstalledStoreSynchronizationSummary {
        val libraries = librariesBySource[source].orEmpty()
        return try {
            synchronize(libraries)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Timber.e(exception, "%s installed-library synchronization failed", source)
            summary(
                source = source,
                libraries = libraries,
                reconciledInstallCount = 0,
                issueCount = 0,
                failure = InstalledStoreSynchronizationFailure(
                    exceptionType = exception.javaClass.name,
                    message = exception.message.orEmpty(),
                ),
            )
        }
    }

    private suspend fun synchronizeSteam(libraries: List<GameLibrary>): InstalledStoreSynchronizationSummary {
        val result = steamReconciler.reconcile(libraries)
        return summary(
            GameSource.STEAM,
            libraries,
            result.reconciledAppIds.size,
            result.conflictedAppIds.size + result.malformedManifestPaths.size + result.markerIssues.size,
        )
    }

    private suspend fun synchronizeGog(libraries: List<GameLibrary>): InstalledStoreSynchronizationSummary {
        val result = gogReconciler.reconcile(libraries)
        result.issues.forEach { issue -> Timber.w("GOG install synchronization issue: %s", issue.message) }
        result.unknownGameIds.forEach { gameId ->
            Timber.w("GOG discovered game is absent from catalog: %s", gameId)
        }
        return summary(
            GameSource.GOG,
            libraries,
            result.updatedGameIds.size,
            result.issues.size + result.unknownGameIds.size,
        )
    }

    private suspend fun synchronizeEpic(libraries: List<GameLibrary>): InstalledStoreSynchronizationSummary {
        val catalog = epicGameDao.getAllAsList()
        val candidates = catalog.mapNotNull { game ->
            if (game.catalogId.isBlank() || game.appName.isBlank()) {
                Timber.e("Ignoring Epic catalog row without strict identity: id=%d", game.id)
                null
            } else {
                EpicInstallCandidate(game.catalogId, game.appName)
            }
        }
        val discovery = epicDiscovery.discover(libraries, candidates)
        discovery.conflicts.forEach { conflict -> Timber.e(conflict, "%s", conflict.message) }
        discovery.manifestErrors.forEach { error -> Timber.e(error, "%s", error.message) }
        discovery.unmatchedManifests.forEach { unmatched ->
            Timber.w(
                "Epic manifest identity is absent from catalog: path=%s appName=%s",
                unmatched.manifestPath,
                unmatched.appName,
            )
        }
        val reconciliation = epicReconciler.reconcile(discovery.installs)
        reconciliation.missingCatalogAppNames.forEach { appName ->
            Timber.w("Epic discovered app is absent from catalog during reconciliation: %s", appName)
        }
        return summary(
            GameSource.EPIC,
            libraries,
            reconciliation.updatedAppNames.size,
            catalog.size - candidates.size + discovery.conflicts.size + discovery.manifestErrors.size +
                discovery.unmatchedManifests.size + reconciliation.missingCatalogAppNames.size,
        )
    }

    private suspend fun synchronizeAmazon(libraries: List<GameLibrary>): InstalledStoreSynchronizationSummary {
        val catalog = amazonGameDao.getAllAsList()
        val candidates = catalog.mapNotNull { game ->
            if (game.productId.isBlank() || game.title.isBlank()) {
                Timber.e("Ignoring Amazon catalog row without strict identity: appId=%d", game.appId)
                null
            } else {
                AmazonInstallCandidate(game.productId, game.title)
            }
        }
        val discovery = amazonDiscovery.discover(libraries, candidates)
        discovery.conflicts.forEach { conflict -> Timber.e(conflict, "%s", conflict.message) }
        discovery.unsupportedDirectories.forEach { unsupported ->
            Timber.w(
                "Amazon native install cannot be identified: path=%s reason=%s",
                unsupported.directoryPath,
                unsupported.reason,
            )
        }
        val reconciliation = amazonReconciler.reconcile(discovery.installs)
        reconciliation.missingCatalogProductIds.forEach { productId ->
            Timber.w("Amazon discovered product is absent from catalog during reconciliation: %s", productId)
        }
        return summary(
            GameSource.AMAZON,
            libraries,
            reconciliation.updatedProductIds.size,
            catalog.size - candidates.size + discovery.conflicts.size + discovery.unsupportedDirectories.size +
                reconciliation.missingCatalogProductIds.size,
        )
    }

    private fun summary(
        source: GameSource,
        libraries: List<GameLibrary>,
        reconciledInstallCount: Int,
        issueCount: Int,
        failure: InstalledStoreSynchronizationFailure? = null,
    ) = InstalledStoreSynchronizationSummary(
        source = source,
        libraryIds = libraries.map(GameLibrary::id),
        reconciledInstallCount = reconciledInstallCount,
        issueCount = issueCount,
        failure = failure,
    )
}
