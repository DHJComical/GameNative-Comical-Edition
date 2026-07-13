package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.enums.Marker
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.manifest.EpicManifest
import java.io.File
import timber.log.Timber

/** Filesystem evidence accepted for an Epic installation. */
enum class EpicInstallSource {
    /** GameNative's completion marker at the catalog-derived directory. */
    GAMENATIVE_MARKER,

    /** Epic manifest retained beneath the installed game's `.egstore` directory. */
    EPIC_MANIFEST,
}

/** Catalog identity used to resolve marker paths and validate native manifest identities. */
data class EpicInstallCandidate(
    /** Stable Epic catalog identifier retained by the database. */
    val catalogId: String,
    /** Legendary/Epic app name expected in manifests. */
    val appName: String,
)

/** One Epic installation proven by supported filesystem evidence. */
data class EpicDiscoveredInstall(
    /** Catalog identifier matched without title guessing. */
    val catalogId: String,
    /** Epic app name supplied by catalog and, for native installs, by the manifest. */
    val appName: String,
    /** Canonical exact game-directory path. */
    val installPath: String,
    /** Registered library containing the game. */
    val libraryId: String,
    /** Evidence used for this result. */
    val source: EpicInstallSource,
)

/** Reports an Epic app name installed at multiple distinct paths. */
class EpicInstallConflictException(
    /** Conflicting Epic app name. */
    val appName: String,
    /** Every distinct location claiming [appName]. */
    val installs: List<EpicDiscoveredInstall>,
) : IllegalStateException(
    "Epic app $appName is installed in multiple locations: ${installs.joinToString { it.installPath }}",
)

/** Reports one malformed Epic manifest without aborting the remaining library scan. */
class EpicManifestReadException(
    /** Canonical path of the unreadable manifest. */
    val manifestPath: String,
    cause: Throwable,
) : IllegalArgumentException("Invalid Epic manifest at $manifestPath", cause)

/** Records a valid native manifest whose app name is absent from the local catalog. */
data class EpicUnmatchedManifest(
    /** Canonical manifest path retained for diagnostics. */
    val manifestPath: String,
    /** App name parsed from the manifest. */
    val appName: String,
)

/** Complete positive-only Epic scan outcome. */
data class EpicInstallDiscoveryResult(
    /** Valid installs excluding conflicted identities. */
    val installs: List<EpicDiscoveredInstall>,
    /** Duplicate identities that require explicit resolution. */
    val conflicts: List<EpicInstallConflictException>,
    /** Damaged individual manifests skipped by the scan. */
    val manifestErrors: List<EpicManifestReadException>,
    /** Valid identities not present in catalog and therefore not synthesized. */
    val unmatchedManifests: List<EpicUnmatchedManifest>,
)

/** Discovers Epic installations in an explicit set of registered libraries. */
interface EpicInstallDiscovery {
    /** Scans every supplied library without consulting a default-library preference. */
    fun discover(
        libraries: Collection<GameLibrary>,
        candidates: Collection<EpicInstallCandidate>,
    ): EpicInstallDiscoveryResult
}

/** Filesystem implementation accepting only GameNative markers and structured Epic manifests. */
class EpicInstallDiscoveryImpl : EpicInstallDiscovery {
    override fun discover(
        libraries: Collection<GameLibrary>,
        candidates: Collection<EpicInstallCandidate>,
    ): EpicInstallDiscoveryResult {
        require(libraries.all { it.source == GameSource.EPIC }) { "Epic discovery accepts only Epic libraries" }
        validateCandidates(candidates)
        val byAppName = candidates.associateBy(EpicInstallCandidate::appName)
        val errors = mutableListOf<EpicManifestReadException>()
        val unmatched = mutableListOf<EpicUnmatchedManifest>()
        val found = libraries.flatMap { library ->
            discoverMarkers(library, candidates) + discoverNativeManifests(library, byAppName, errors, unmatched)
        }
        val merged = found.groupBy { Triple(it.appName, it.libraryId, it.installPath) }
            .map { (_, sameLocation) ->
                sameLocation.first().copy(
                    source = if (sameLocation.any { it.source == EpicInstallSource.EPIC_MANIFEST }) {
                        EpicInstallSource.EPIC_MANIFEST
                    } else {
                        EpicInstallSource.GAMENATIVE_MARKER
                    },
                )
            }
            .sortedWith(compareBy(EpicDiscoveredInstall::appName, EpicDiscoveredInstall::installPath))
        val conflicts = merged.groupBy(EpicDiscoveredInstall::appName)
            .filterValues { installs -> installs.map(EpicDiscoveredInstall::installPath).distinct().size > 1 }
            .map { (appName, installs) -> EpicInstallConflictException(appName, installs) }
            .sortedBy(EpicInstallConflictException::appName)
        val conflicted = conflicts.mapTo(mutableSetOf(), EpicInstallConflictException::appName)
        return EpicInstallDiscoveryResult(
            installs = merged.filterNot { it.appName in conflicted },
            conflicts = conflicts,
            manifestErrors = errors.sortedBy(EpicManifestReadException::manifestPath),
            unmatchedManifests = unmatched.distinct().sortedBy(EpicUnmatchedManifest::manifestPath),
        )
    }

    private fun validateCandidates(candidates: Collection<EpicInstallCandidate>) {
        require(candidates.all { it.catalogId.isNotBlank() && it.appName.isNotBlank() }) {
            "Epic catalog identifiers and app names must not be blank"
        }
        require(candidates.map(EpicInstallCandidate::catalogId).distinct().size == candidates.size) {
            "Epic catalog contains duplicate catalog identifiers"
        }
        require(candidates.map(EpicInstallCandidate::appName).distinct().size == candidates.size) {
            "Epic catalog contains duplicate app names"
        }
    }

    private fun discoverMarkers(
        library: GameLibrary,
        candidates: Collection<EpicInstallCandidate>,
    ): List<EpicDiscoveredInstall> {
        val root = File(EpicLibraryLayout.installRoot(library.rootPath)).canonicalFile
        return candidates.mapNotNull { candidate ->
            val directory = File(root, EpicConstants.sanitizeGameDirectoryName(candidate.appName)).canonicalFile
            check(directory.parentFile == root) { "Epic marker path escaped its registered install root" }
            if (!directory.isDirectory || !File(directory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).isFile) null else {
                discovered(candidate, directory, library, EpicInstallSource.GAMENATIVE_MARKER)
            }
        }
    }

    private fun discoverNativeManifests(
        library: GameLibrary,
        candidates: Map<String, EpicInstallCandidate>,
        errors: MutableList<EpicManifestReadException>,
        unmatched: MutableList<EpicUnmatchedManifest>,
    ): List<EpicDiscoveredInstall> {
        val root = File(EpicLibraryLayout.installRoot(library.rootPath)).canonicalFile
        val directories = root.listFiles { file -> file.isDirectory }?.sortedBy(File::getName).orEmpty()
        return directories.flatMap { directory ->
            val manifests = File(directory, EPIC_METADATA_DIRECTORY)
                .listFiles { file -> file.isFile && file.extension.equals(MANIFEST_EXTENSION, true) }
                ?.sortedBy(File::getName)
                .orEmpty()
            manifests.mapNotNull { manifest ->
                val manifestPath = manifest.canonicalPath
                try {
                    val appName = EpicManifest.readAll(manifest.readBytes()).meta?.appName.orEmpty()
                    require(appName.isNotBlank()) { "Epic manifest app name is blank" }
                    val candidate = candidates[appName]
                    if (candidate == null) {
                        unmatched += EpicUnmatchedManifest(manifestPath, appName)
                        Timber.tag(TAG).w(
                            "Ignoring Epic manifest absent from catalog: $manifestPath (appName=$appName)",
                        )
                        null
                    } else {
                        discovered(candidate, directory, library, EpicInstallSource.EPIC_MANIFEST)
                    }
                } catch (exception: Exception) {
                    val error = EpicManifestReadException(manifestPath, exception)
                    errors += error
                    Timber.tag(TAG).e(exception, "Unable to parse Epic install manifest: $manifestPath")
                    null
                }
            }
        }
    }

    private fun discovered(
        candidate: EpicInstallCandidate,
        directory: File,
        library: GameLibrary,
        source: EpicInstallSource,
    ) = EpicDiscoveredInstall(candidate.catalogId, candidate.appName, directory.canonicalPath, library.id, source)

    private companion object {
        const val TAG = "EpicInstallDiscovery"
        const val EPIC_METADATA_DIRECTORY = ".egstore"
        const val MANIFEST_EXTENSION = "manifest"
    }
}

/** Summary of database changes made from trusted Epic discoveries. */
data class EpicInstallReconcileResult(
    /** App names whose existing catalog rows were marked installed. */
    val updatedAppNames: List<String>,
    /** App names missing at reconciliation time and therefore not synthesized. */
    val missingCatalogAppNames: List<String>,
)

/** Reconciles trusted Epic discoveries while preserving non-install metadata. */
interface EpicInstallReconciler {
    /** Updates only installation state and exact path for each positive discovery. */
    suspend fun reconcile(installs: Collection<EpicDiscoveredInstall>): EpicInstallReconcileResult
}

/** Room-backed reconciler that never creates catalog rows from local files. */
class EpicInstallReconcilerImpl(
    /** DAO owning the existing Epic catalog. */
    private val epicGameDao: EpicGameDao,
) : EpicInstallReconciler {
    override suspend fun reconcile(installs: Collection<EpicDiscoveredInstall>): EpicInstallReconcileResult {
        require(installs.groupingBy(EpicDiscoveredInstall::appName).eachCount().values.none { it > 1 }) {
            "Epic reconciliation received duplicate app names"
        }
        val updated = mutableListOf<String>()
        val missing = mutableListOf<String>()
        installs.sortedBy(EpicDiscoveredInstall::appName).forEach { install ->
            val existing = epicGameDao.getByAppName(install.appName)
            if (existing == null || existing.catalogId != install.catalogId) {
                missing += install.appName
                Timber.tag(TAG).w("Epic discovered app is absent from catalog: ${install.appName}")
            } else {
                epicGameDao.updateDiscoveredInstallation(install.appName, install.installPath)
                updated += install.appName
            }
        }
        return EpicInstallReconcileResult(updated, missing)
    }

    private companion object {
        const val TAG = "EpicInstallReconciler"
    }
}
