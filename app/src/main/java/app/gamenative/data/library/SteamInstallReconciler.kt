package app.gamenative.data.library

import androidx.room.withTransaction
import app.gamenative.data.AppInfo
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.db.PluviaDatabase
import java.io.File
import timber.log.Timber

/**
 * Summarizes the positive changes made by one Steam installation reconciliation.
 *
 * @property reconciledAppIds App identifiers whose unambiguous filesystem evidence was persisted.
 * @property conflictedAppIds App identifiers intentionally left unchanged due to multiple locations.
 * @property malformedManifestPaths Manifest paths skipped after structured parsing failed.
 * @property markerIssues Unsafe catalog marker candidates skipped without stopping reconciliation.
 */
data class SteamInstallReconciliationResult(
    val reconciledAppIds: List<Int>,
    val conflictedAppIds: List<Int>,
    val malformedManifestPaths: List<String>,
    val markerIssues: List<SteamMarkerInstallIssue> = emptyList(),
)

/** Reconciles trustworthy Steam filesystem evidence into catalog and installation database rows. */
interface SteamInstallReconciler {
    /**
     * Scans every explicitly registered Steam library and positively marks discovered apps as
     * installed. Missing filesystem evidence never removes or downgrades existing database state.
     */
    suspend fun reconcile(libraries: Collection<GameLibrary>): SteamInstallReconciliationResult
}

/** Room-backed [SteamInstallReconciler] that commits catalog stubs and AppInfo rows together. */
class SteamInstallReconcilerImpl(
    /** Database whose transaction prevents catalog and installation rows from diverging. */
    private val database: PluviaDatabase,
    /** Filesystem scanner kept behind an interface so reconciliation logic is directly testable. */
    private val discovery: SteamInstallDiscovery,
) : SteamInstallReconciler {
    override suspend fun reconcile(
        libraries: Collection<GameLibrary>,
    ): SteamInstallReconciliationResult {
        require(libraries.all { it.source == GameSource.STEAM }) {
            "Steam install reconciliation accepts only Steam libraries"
        }

        val catalog = database.steamAppDao().getAllAsList()
        val discoveryResult = discovery.discover(libraries, catalog.mapNotNull(::markerCandidate))
        discoveryResult.manifestErrors.forEach { error ->
            Timber.e(error, "Skipping malformed Steam manifest at ${error.manifestPath}")
        }
        discoveryResult.conflicts.forEach { conflict ->
            val contexts = conflict.installs.joinToString { installContext(it) }
            Timber.e(
                conflict,
                "Skipping conflicting Steam app ${conflict.appId}: $contexts",
            )
        }
        discoveryResult.markerIssues.forEach { issue ->
            Timber.e(
                "Skipping unsafe Steam marker candidate: appId=%d directoryName=%s libraryId=%s reason=%s",
                issue.appId,
                issue.directoryName,
                issue.libraryId,
                issue.reason,
            )
        }

        val installs = discoveryResult.installs
        if (installs.isNotEmpty()) {
            database.withTransaction {
                reconcileCatalog(installs)
                reconcileAppInfo(installs)
            }
        }
        return SteamInstallReconciliationResult(
            reconciledAppIds = installs.map(SteamDiscoveredInstall::appId).sorted(),
            conflictedAppIds = discoveryResult.conflicts.map(SteamInstallConflictException::appId).sorted(),
            malformedManifestPaths = discoveryResult.manifestErrors
                .map(SteamAppManifestException::manifestPath)
                .sorted(),
            markerIssues = discoveryResult.markerIssues,
        )
    }

    /** Builds all exact historical directory names available in the current Steam catalog row. */
    private fun markerCandidate(app: SteamApp): SteamMarkerInstallCandidate? {
        val directoryNames = listOf(app.config.installDir, app.installDir, app.name)
            .filter(String::isNotBlank)
            .distinct()
        if (directoryNames.isEmpty()) return null
        return SteamMarkerInstallCandidate(appId = app.id, directoryNames = directoryNames)
    }

    /** Inserts manifest-backed stubs and fills only a missing name on an existing catalog row. */
    private suspend fun reconcileCatalog(installs: List<SteamDiscoveredInstall>) {
        val appDao = database.steamAppDao()
        val existingById = appDao.findSteamAppWithAppIds(installs.map { it.appId }.distinct())
            .associateBy(SteamApp::id)
        installs.forEach { install ->
            val existing = existingById[install.appId]
            val manifestName = install.manifestName.orEmpty()
            when {
                existing == null -> appDao.insert(SteamApp(id = install.appId, name = manifestName))
                existing.name.isBlank() && manifestName.isNotBlank() -> {
                    appDao.update(existing.copy(name = manifestName))
                }
            }
        }
    }

    /** Merges positive installation state while retaining every unrelated AppInfo field. */
    private suspend fun reconcileAppInfo(installs: List<SteamDiscoveredInstall>) {
        val appInfoDao = database.appInfoDao()
        val existingById = appInfoDao.getByIds(installs.map { it.appId }.distinct())
            .associateBy(AppInfo::id)
        val updates = installs.map { install ->
            val existing = existingById[install.appId] ?: AppInfo(id = install.appId)
            val managedPath = managedPathFor(existing, install)
            existing.copy(
                isDownloaded = true,
                managedInstallPath = managedPath,
            )
        }
        appInfoDao.upsertAll(updates)
    }

    /** Preserves an explicit custom import when discovery points at a different managed location. */
    private fun managedPathFor(existing: AppInfo, install: SteamDiscoveredInstall): String {
        if (existing.customInstallPath.isBlank()) return install.installPath
        val canonicalCustomPath = File(existing.customInstallPath).canonicalPath
        if (canonicalCustomPath == install.installPath) return install.installPath
        Timber.e(
            "Steam app ${install.appId} custom path $canonicalCustomPath conflicts with discovered managed path " +
                "${install.installPath}; preserving custom path and existing managed path " +
                existing.managedInstallPath,
        )
        return existing.managedInstallPath
    }

    /** Formats all evidence needed to diagnose a duplicate app identifier across libraries. */
    private fun installContext(install: SteamDiscoveredInstall): String =
        "libraryId=${install.libraryId}, libraryRoot=${install.libraryRoot}, " +
            "installPath=${install.installPath}, source=${install.source}"
}
