package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.enums.Marker
import app.gamenative.service.amazon.AmazonConstants
import java.io.File
import timber.log.Timber

/** Catalog identity required to interpret Amazon's identity-free GameNative marker. */
data class AmazonInstallCandidate(
    /** Stable Amazon product identifier. */
    val productId: String,
    /** Exact catalog title used by GameNative's deterministic directory mapping. */
    val title: String,
)

/** One Amazon installation proven by a marker at the strict catalog-derived path. */
data class AmazonDiscoveredInstall(
    /** Catalog product identifier. */
    val productId: String,
    /** Canonical exact game-directory path. */
    val installPath: String,
    /** Registered library containing the game. */
    val libraryId: String,
)

/** Reports one Amazon product installed at multiple distinct registered locations. */
class AmazonInstallConflictException(
    /** Conflicting product identifier. */
    val productId: String,
    /** Every distinct marker-backed installation. */
    val installs: List<AmazonDiscoveredInstall>,
) : IllegalStateException(
    "Amazon product $productId is installed in multiple locations: ${installs.joinToString { it.installPath }}",
)

/** Describes a directory that cannot be associated because Amazon identity metadata is unsupported. */
data class AmazonUnsupportedInstallDirectory(
    /** Canonical directory path retained for diagnostics. */
    val directoryPath: String,
    /** Explicit reason no catalog association was attempted. */
    val reason: String,
)

/** Complete positive-only Amazon scan outcome. */
data class AmazonInstallDiscoveryResult(
    /** Unambiguous marker-backed installations. */
    val installs: List<AmazonDiscoveredInstall>,
    /** Product identifiers found in multiple libraries. */
    val conflicts: List<AmazonInstallConflictException>,
    /** Directories ignored because no trustworthy native identity format exists. */
    val unsupportedDirectories: List<AmazonUnsupportedInstallDirectory>,
)

/** Discovers supported Amazon installations across explicit registered libraries. */
interface AmazonInstallDiscovery {
    /** Scans all supplied libraries without consulting the default-library preference. */
    fun discover(
        libraries: Collection<GameLibrary>,
        candidates: Collection<AmazonInstallCandidate>,
    ): AmazonInstallDiscoveryResult
}

/** Filesystem implementation limited to GameNative markers at strict catalog-derived paths. */
class AmazonInstallDiscoveryImpl : AmazonInstallDiscovery {
    override fun discover(
        libraries: Collection<GameLibrary>,
        candidates: Collection<AmazonInstallCandidate>,
    ): AmazonInstallDiscoveryResult {
        require(libraries.all { it.source == GameSource.AMAZON }) { "Amazon discovery accepts only Amazon libraries" }
        validateCandidates(candidates)
        val expectedNames = candidates.associateBy { AmazonConstants.gameDirectoryName(it.title) }
        val found = libraries.flatMap { library -> discoverMarkers(library, expectedNames) }
            .sortedWith(compareBy(AmazonDiscoveredInstall::productId, AmazonDiscoveredInstall::installPath))
        val conflicts = found.groupBy(AmazonDiscoveredInstall::productId)
            .filterValues { installs -> installs.map(AmazonDiscoveredInstall::installPath).distinct().size > 1 }
            .map { (productId, installs) -> AmazonInstallConflictException(productId, installs) }
            .sortedBy(AmazonInstallConflictException::productId)
        val conflicted = conflicts.mapTo(mutableSetOf(), AmazonInstallConflictException::productId)
        val supportedPaths = found.mapTo(mutableSetOf(), AmazonDiscoveredInstall::installPath)
        val unsupported = libraries.flatMap { library ->
            File(AmazonLibraryLayout.installRoot(library.rootPath)).canonicalFile
                .listFiles { file -> file.isDirectory && file.name != STAGING_DIRECTORY }
                ?.filterNot { it.canonicalPath in supportedPaths }
                ?.map { directory ->
                    Timber.tag(TAG).w(
                        "Ignoring Amazon directory without supported identity evidence: ${directory.canonicalPath}",
                    )
                    AmazonUnsupportedInstallDirectory(directory.canonicalPath, UNSUPPORTED_REASON)
                }
                .orEmpty()
        }.distinctBy(AmazonUnsupportedInstallDirectory::directoryPath)
            .sortedBy(AmazonUnsupportedInstallDirectory::directoryPath)
        return AmazonInstallDiscoveryResult(found.filterNot { it.productId in conflicted }, conflicts, unsupported)
    }

    private fun validateCandidates(candidates: Collection<AmazonInstallCandidate>) {
        require(candidates.all { it.productId.isNotBlank() && it.title.isNotBlank() }) {
            "Amazon product identifiers and titles must not be blank"
        }
        require(candidates.map(AmazonInstallCandidate::productId).distinct().size == candidates.size) {
            "Amazon catalog contains duplicate product identifiers"
        }
        require(candidates.map { AmazonConstants.gameDirectoryName(it.title) }.distinct().size == candidates.size) {
            "Amazon catalog titles collide in the deterministic directory mapping"
        }
    }

    private fun discoverMarkers(
        library: GameLibrary,
        candidatesByDirectory: Map<String, AmazonInstallCandidate>,
    ): List<AmazonDiscoveredInstall> {
        val root = File(AmazonLibraryLayout.installRoot(library.rootPath)).canonicalFile
        return candidatesByDirectory.mapNotNull { (directoryName, candidate) ->
            val directory = File(root, directoryName).canonicalFile
            check(directory.parentFile == root) { "Amazon marker path escaped its registered install root" }
            if (!directory.isDirectory || !File(directory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).isFile) null else {
                AmazonDiscoveredInstall(candidate.productId, directory.canonicalPath, library.id)
            }
        }
    }

    private companion object {
        const val TAG = "AmazonInstallDiscovery"
        const val STAGING_DIRECTORY = ".staging"
        const val UNSUPPORTED_REASON = "No supported Amazon native-install identity metadata is available"
    }
}

/** Summary of database changes made from trusted Amazon discoveries. */
data class AmazonInstallReconcileResult(
    /** Product identifiers whose catalog rows were marked installed. */
    val updatedProductIds: List<String>,
    /** Product identifiers missing from catalog and therefore not synthesized. */
    val missingCatalogProductIds: List<String>,
)

/** Reconciles marker-backed Amazon installs while preserving catalog metadata. */
interface AmazonInstallReconciler {
    /** Updates only installed state and exact path for each positive discovery. */
    suspend fun reconcile(installs: Collection<AmazonDiscoveredInstall>): AmazonInstallReconcileResult
}

/** Room-backed Amazon reconciler that never guesses or creates catalog records. */
class AmazonInstallReconcilerImpl(
    /** DAO owning the existing Amazon catalog. */
    private val amazonGameDao: AmazonGameDao,
) : AmazonInstallReconciler {
    override suspend fun reconcile(installs: Collection<AmazonDiscoveredInstall>): AmazonInstallReconcileResult {
        require(installs.groupingBy(AmazonDiscoveredInstall::productId).eachCount().values.none { it > 1 }) {
            "Amazon reconciliation received duplicate product identifiers"
        }
        val updated = mutableListOf<String>()
        val missing = mutableListOf<String>()
        installs.sortedBy(AmazonDiscoveredInstall::productId).forEach { install ->
            if (amazonGameDao.getByProductId(install.productId) == null) {
                missing += install.productId
                Timber.tag(TAG).w("Amazon discovered product is absent from catalog: ${install.productId}")
            } else {
                amazonGameDao.updateDiscoveredInstallation(install.productId, install.installPath)
                updated += install.productId
            }
        }
        return AmazonInstallReconcileResult(updated, missing)
    }

    private companion object {
        const val TAG = "AmazonInstallReconciler"
    }
}
