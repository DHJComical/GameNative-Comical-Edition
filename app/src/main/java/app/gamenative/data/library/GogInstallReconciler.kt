package app.gamenative.data.library

import app.gamenative.data.GOGGame
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.service.gog.GOGConstants
import timber.log.Timber

/** Summary of a positive-only GOG installation reconciliation. */
data class GogInstallReconciliationResult(
    /** Catalog rows whose exact installation state was updated. */
    val updatedGameIds: Set<String>,
    /** Discovered trusted identities absent from the local catalog and therefore not persisted. */
    val unknownGameIds: Set<String>,
    /** Discovery issues that prevented unsafe installation choices. */
    val issues: List<GogInstallDiscoveryIssue>,
)

/** Reconciles trusted filesystem discoveries into existing GOG catalog rows. */
interface GogInstallReconciler {
    /**
     * Scans only [libraries], marks unambiguous existing catalog games installed, and never clears
     * state when a path is temporarily unavailable or absent.
     */
    suspend fun reconcile(libraries: Collection<GameLibrary>): GogInstallReconciliationResult
}

/** DAO-backed positive-only [GogInstallReconciler]. */
class GogInstallReconcilerImpl(
    private val gogGameDao: GOGGameDao,
    private val discovery: GogInstallDiscovery,
) : GogInstallReconciler {
    override suspend fun reconcile(libraries: Collection<GameLibrary>): GogInstallReconciliationResult {
        val catalog = gogGameDao.getAllAsList()
        val catalogById = catalog.associateBy(GOGGame::id)
        val candidates = catalog.map { game ->
            GogMarkerInstallCandidate(game.id, listOf(GOGConstants.gameDirectoryName(game.title)))
        }
        val discoveryResult = discovery.discover(libraries, candidates)
        val updated = mutableSetOf<String>()
        val unknown = mutableSetOf<String>()
        discoveryResult.installs.forEach { install ->
            val game = catalogById[install.gameId]
            if (game == null) {
                unknown += install.gameId
                Timber.w("Ignoring discovered GOG install for unknown catalog game ${install.gameId} at ${install.installPath}")
            } else {
                val reconciled = game.copy(isInstalled = true, installPath = install.installPath)
                if (reconciled != game) {
                    gogGameDao.update(reconciled)
                    updated += game.id
                }
            }
        }
        return GogInstallReconciliationResult(updated, unknown, discoveryResult.issues)
    }
}
