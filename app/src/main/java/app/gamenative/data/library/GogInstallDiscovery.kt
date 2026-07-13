package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/** Identifies the filesystem evidence that established a GOG installation. */
enum class GogInstallSource {
    /** GameNative's completion marker is associated with a catalog-provided game identity. */
    GAMENATIVE_MARKER,

    /** A native GOG info file supplies the game identity. */
    GOG_INFO,
}

/**
 * Supplies the catalog identity needed to interpret GameNative's identity-free marker.
 *
 * @property gameId Stable GOG product identifier.
 * @property directoryNames Exact historical or current install-directory names for the game.
 */
data class GogMarkerInstallCandidate(
    val gameId: String,
    val directoryNames: List<String>,
)

/**
 * Describes one trusted GOG installation found in a registered library.
 *
 * @property gameId Stable GOG product identifier proven by catalog input or native metadata.
 * @property installPath Canonical exact path of the installed game's directory.
 * @property libraryId Stable identifier of the registered library containing the installation.
 * @property libraryRoot Canonical root of that registered library.
 * @property source Filesystem evidence used to discover the installation.
 */
data class GogDiscoveredInstall(
    val gameId: String,
    val installPath: String,
    val libraryId: String,
    val libraryRoot: String,
    val source: GogInstallSource,
)

/** A discovery problem isolated to one metadata record or conflicting identity. */
sealed interface GogInstallDiscoveryIssue {
    /** Human-readable context suitable for logs and diagnostics. */
    val message: String
}

/** Reports native GOG metadata that could not provide a trustworthy identity. */
data class InvalidGogInfoIssue(
    /** Canonical path of the damaged metadata file. */
    val metadataPath: String,
    /** Parsing or validation detail. */
    val reason: String,
) : GogInstallDiscoveryIssue {
    override val message: String = "Invalid GOG metadata at $metadataPath: $reason"
}

/** Reports one directory whose independent evidence claims different game identifiers. */
data class GogInstallIdentityConflictIssue(
    /** Canonical installation directory with inconsistent evidence. */
    val installPath: String,
    /** Every distinct game identifier claimed for the directory. */
    val gameIds: Set<String>,
) : GogInstallDiscoveryIssue {
    override val message: String = "GOG install $installPath has conflicting identities: ${gameIds.sorted()}"
}

/** Reports one game identifier installed at multiple distinct registered-library locations. */
data class DuplicateGogInstallIssue(
    /** Conflicting GOG product identifier. */
    val gameId: String,
    /** Every distinct installation that claimed [gameId]. */
    val installs: List<GogDiscoveredInstall>,
) : GogInstallDiscoveryIssue {
    override val message: String =
        "GOG game $gameId is installed in multiple locations: ${installs.joinToString { it.installPath }}"
}

/** Complete discovery outcome; invalid records do not suppress valid unrelated installations. */
data class GogInstallDiscoveryResult(
    /** Unambiguous trusted installations safe to reconcile. */
    val installs: List<GogDiscoveredInstall>,
    /** Isolated invalid records and conflicts excluded from [installs]. */
    val issues: List<GogInstallDiscoveryIssue>,
)

/** Discovers completed GOG installations across an explicit set of registered libraries. */
interface GogInstallDiscovery {
    /**
     * Finds GameNative marker installs and native `goggame-*.info` installs without consulting a
     * default-library preference. Conflicting identities are reported and excluded.
     */
    fun discover(
        libraries: Collection<GameLibrary>,
        markerCandidates: Collection<GogMarkerInstallCandidate>,
    ): GogInstallDiscoveryResult
}

/** Filesystem-backed [GogInstallDiscovery] using structured JSON metadata parsing. */
class GogInstallDiscoveryImpl(
    private val infoFileFinder: GogInfoFileFinder = GogInfoFileFinderImpl(),
) : GogInstallDiscovery {
    override fun discover(
        libraries: Collection<GameLibrary>,
        markerCandidates: Collection<GogMarkerInstallCandidate>,
    ): GogInstallDiscoveryResult {
        require(libraries.all { it.source == GameSource.GOG }) {
            "GOG install discovery accepts only GOG libraries"
        }
        val candidates = validateMarkerCandidates(markerCandidates)
        val issues = mutableListOf<GogInstallDiscoveryIssue>()
        val evidence = libraries.flatMap { library -> discoverLibrary(library, candidates, issues) }
        val consistentLocations = excludeIdentityConflicts(evidence, issues)
        val installs = excludeDuplicateGameIds(consistentLocations, issues)
        issues.forEach { Timber.w(it.message) }
        return GogInstallDiscoveryResult(
            installs = installs.sortedWith(compareBy(GogDiscoveredInstall::gameId, GogDiscoveredInstall::installPath)),
            issues = issues.toList(),
        )
    }

    private fun validateMarkerCandidates(
        candidates: Collection<GogMarkerInstallCandidate>,
    ): List<GogMarkerInstallCandidate> {
        candidates.forEach { candidate ->
            require(candidate.gameId.isNotBlank()) { "Marker candidate gameId must not be blank" }
            require(candidate.directoryNames.isNotEmpty()) {
                "Marker candidate ${candidate.gameId} must have at least one directory name"
            }
            require(candidate.directoryNames.all { it.isNotBlank() }) {
                "Marker candidate ${candidate.gameId} contains a blank directory name"
            }
        }
        require(candidates.groupingBy(GogMarkerInstallCandidate::gameId).eachCount().values.none { it > 1 }) {
            "Marker candidates contain duplicate gameIds"
        }
        return candidates.toList()
    }

    private fun discoverLibrary(
        library: GameLibrary,
        candidates: List<GogMarkerInstallCandidate>,
        issues: MutableList<GogInstallDiscoveryIssue>,
    ): List<GogDiscoveredInstall> {
        val installRoot = File(GogLibraryLayout.installRoot(library.rootPath)).canonicalFile
        if (!installRoot.isDirectory) return emptyList()
        val directories = installRoot.listFiles(File::isDirectory)?.sortedBy(File::getName).orEmpty()
        return directories.flatMap { directory ->
            discoverMarkers(directory, installRoot, library, candidates) + discoverInfo(directory, library, issues)
        }
    }

    private fun discoverMarkers(
        directory: File,
        installRoot: File,
        library: GameLibrary,
        candidates: List<GogMarkerInstallCandidate>,
    ): List<GogDiscoveredInstall> {
        if (!File(directory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).isFile) return emptyList()
        return candidates.filter { candidate ->
            candidate.directoryNames.any { name -> directChild(installRoot, name) == directory.canonicalFile }
        }.map { candidate -> discoveredInstall(candidate.gameId, directory, library, GogInstallSource.GAMENATIVE_MARKER) }
    }

    private fun discoverInfo(
        directory: File,
        library: GameLibrary,
        issues: MutableList<GogInstallDiscoveryIssue>,
    ): List<GogDiscoveredInstall> = infoFileFinder.find(directory).mapNotNull { infoFile ->
        val gameId = try {
            JSONObject(infoFile.readText(Charsets.UTF_8)).optString(GAME_ID_KEY).trim().takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("gameId is missing or blank")
        } catch (exception: Exception) {
            issues += InvalidGogInfoIssue(infoFile.canonicalPath, exception.message ?: "cannot parse JSON")
            null
        }
        gameId?.let { discoveredInstall(it, directory, library, GogInstallSource.GOG_INFO) }
    }

    private fun directChild(root: File, directoryName: String): File {
        val directory = File(root, directoryName).canonicalFile
        require(directory.parentFile == root) { "GOG install directory must be a direct child of ${root.path}" }
        return directory
    }

    private fun discoveredInstall(
        gameId: String,
        directory: File,
        library: GameLibrary,
        source: GogInstallSource,
    ) = GogDiscoveredInstall(
        gameId = gameId,
        installPath = directory.canonicalPath,
        libraryId = library.id,
        libraryRoot = File(library.rootPath).canonicalPath,
        source = source,
    )

    private fun excludeIdentityConflicts(
        evidence: List<GogDiscoveredInstall>,
        issues: MutableList<GogInstallDiscoveryIssue>,
    ): List<GogDiscoveredInstall> = evidence.groupBy(GogDiscoveredInstall::installPath).flatMap { (path, samePath) ->
        val gameIds = samePath.map(GogDiscoveredInstall::gameId).toSet()
        if (gameIds.size > 1) {
            issues += GogInstallIdentityConflictIssue(path, gameIds)
            emptyList()
        } else {
            listOf(
                samePath.first().copy(
                    source = if (samePath.any { it.source == GogInstallSource.GOG_INFO }) {
                        GogInstallSource.GOG_INFO
                    } else {
                        GogInstallSource.GAMENATIVE_MARKER
                    },
                ),
            )
        }
    }

    private fun excludeDuplicateGameIds(
        installs: List<GogDiscoveredInstall>,
        issues: MutableList<GogInstallDiscoveryIssue>,
    ): List<GogDiscoveredInstall> = installs.groupBy(GogDiscoveredInstall::gameId).flatMap { (gameId, copies) ->
        if (copies.size > 1) {
            issues += DuplicateGogInstallIssue(gameId, copies.sortedBy(GogDiscoveredInstall::installPath))
            emptyList()
        } else {
            copies
        }
    }

    private companion object {
        const val GAME_ID_KEY = "gameId"
    }
}
