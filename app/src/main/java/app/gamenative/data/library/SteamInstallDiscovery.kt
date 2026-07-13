package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File

/** Identifies the filesystem evidence that established a Steam installation. */
enum class SteamInstallSource {
    /** GameNative's completion marker establishes an installation known by catalog metadata. */
    GAMENATIVE_MARKER,

    /** Steam's native appmanifest establishes an installation independently of GameNative metadata. */
    STEAM_MANIFEST,
}

/**
 * Supplies the catalog identity needed to interpret GameNative's otherwise identity-free marker.
 *
 * @property appId Steam application identifier associated with every supplied directory name.
 * @property directoryNames Exact historical or current Steam install-directory names for the app.
 */
data class SteamMarkerInstallCandidate(
    val appId: Int,
    val directoryNames: List<String>,
)

/**
 * Describes one installation found under a registered Steam library.
 *
 * @property appId Steam application identifier proven by catalog input or a native manifest.
 * @property installPath Canonical exact path of the installed game's directory.
 * @property libraryId Stable identifier of the registered library containing the installation.
 * @property libraryRoot Canonical root of that registered library.
 * @property source Filesystem evidence used to discover the installation.
 * @property manifestName User-visible name from a native manifest when Steam supplied one.
 */
data class SteamDiscoveredInstall(
    val appId: Int,
    val installPath: String,
    val libraryId: String,
    val libraryRoot: String,
    val source: SteamInstallSource,
    val manifestName: String?,
)

/** Reports that one app identifier resolves to multiple registered library installations. */
class SteamInstallConflictException(
    /** Conflicting Steam application identifier. */
    val appId: Int,
    /** Every distinct installation that claimed [appId]. */
    val installs: List<SteamDiscoveredInstall>,
) : IllegalStateException(
    "Steam app $appId is installed in multiple locations: " + installs.joinToString { it.installPath },
)

/** Reports a malformed or semantically invalid native Steam appmanifest. */
class SteamAppManifestException(
    /** Manifest whose content could not be trusted. */
    val manifestPath: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException("Invalid Steam appmanifest at $manifestPath: $message", cause)

/** Describes one catalog directory name that could not be safely inspected for a marker. */
data class SteamMarkerInstallIssue(
    /** Steam application identifier supplied by the catalog candidate. */
    val appId: Int,
    /** Exact catalog directory name that failed validation or filesystem resolution. */
    val directoryName: String,
    /** Registered library in which the candidate was being inspected. */
    val libraryId: String,
    /** Stable diagnostic reason for rejecting only this candidate name. */
    val reason: String,
)

/**
 * Contains every independently usable result of one Steam library scan.
 *
 * @property installs Valid, unambiguous installations that callers may reconcile.
 * @property conflicts App identifiers found at more than one distinct registered location.
 * @property manifestErrors Individual malformed manifests that did not stop the remaining scan.
 * @property markerIssues Individual unsafe marker candidates that did not stop the remaining scan.
 */
data class SteamInstallDiscoveryResult(
    val installs: List<SteamDiscoveredInstall>,
    val conflicts: List<SteamInstallConflictException>,
    val manifestErrors: List<SteamAppManifestException>,
    val markerIssues: List<SteamMarkerInstallIssue> = emptyList(),
)

/** Discovers completed Steam installations across an explicit set of registered libraries. */
interface SteamInstallDiscovery {
    /**
     * Finds GameNative marker installs and native Steam appmanifest installs without consulting a
     * default-library preference. Per-app conflicts and per-file parse errors are reported without
     * hiding unrelated valid installations.
     */
    fun discover(
        libraries: Collection<GameLibrary>,
        markerCandidates: Collection<SteamMarkerInstallCandidate>,
    ): SteamInstallDiscoveryResult
}

/** Filesystem-backed [SteamInstallDiscovery] using JavaSteam's structured KeyValue parser. */
class SteamInstallDiscoveryImpl : SteamInstallDiscovery {
    override fun discover(
        libraries: Collection<GameLibrary>,
        markerCandidates: Collection<SteamMarkerInstallCandidate>,
    ): SteamInstallDiscoveryResult {
        require(libraries.all { it.source == GameSource.STEAM }) {
            "Steam install discovery accepts only Steam libraries"
        }
        val normalizedCandidates = validateMarkerCandidates(markerCandidates)
        val manifestErrors = mutableListOf<SteamAppManifestException>()
        val markerIssues = mutableListOf<SteamMarkerInstallIssue>()
        val discoveries = libraries.flatMap { library ->
            discoverMarkers(library, normalizedCandidates, markerIssues) + discoverManifests(library, manifestErrors)
        }
        val merged = mergeSameLocationEvidence(discoveries)
            .sortedWith(compareBy(SteamDiscoveredInstall::appId, SteamDiscoveredInstall::installPath))
        val conflicts = merged.groupBy(SteamDiscoveredInstall::appId)
            .filterValues { it.size > 1 }
            .map { (appId, installs) -> SteamInstallConflictException(appId, installs) }
            .sortedBy(SteamInstallConflictException::appId)
        val conflictedAppIds = conflicts.mapTo(mutableSetOf(), SteamInstallConflictException::appId)
        return SteamInstallDiscoveryResult(
            installs = merged.filterNot { it.appId in conflictedAppIds },
            conflicts = conflicts,
            manifestErrors = manifestErrors.sortedBy(SteamAppManifestException::manifestPath),
            markerIssues = markerIssues.sortedWith(
                compareBy(
                    SteamMarkerInstallIssue::appId,
                    SteamMarkerInstallIssue::directoryName,
                    SteamMarkerInstallIssue::libraryId,
                ),
            ),
        )
    }

    private fun validateMarkerCandidates(
        candidates: Collection<SteamMarkerInstallCandidate>,
    ): List<SteamMarkerInstallCandidate> {
        candidates.forEach { candidate ->
            require(candidate.appId > 0) { "Marker candidate appId must be positive: ${candidate.appId}" }
            require(candidate.directoryNames.isNotEmpty()) {
                "Marker candidate ${candidate.appId} must have at least one directory name"
            }
            require(candidate.directoryNames.all { it.isNotBlank() }) {
                "Marker candidate ${candidate.appId} contains a blank directory name"
            }
        }
        require(candidates.groupingBy { it.appId }.eachCount().values.none { it > 1 }) {
            "Marker candidates contain duplicate appIds"
        }
        return candidates.toList()
    }

    private fun discoverMarkers(
        library: GameLibrary,
        candidates: List<SteamMarkerInstallCandidate>,
        issues: MutableList<SteamMarkerInstallIssue>,
    ): List<SteamDiscoveredInstall> {
        val installRoot = File(SteamLibraryLayout.installRoot(library.rootPath)).canonicalFile
        return candidates.flatMap { candidate ->
            candidate.directoryNames.distinct().mapNotNull { directoryName ->
                try {
                    val installDirectory = resolveDirectInstallDirectory(installRoot, directoryName, null)
                    val marker = File(installDirectory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName)
                    if (!installDirectory.isDirectory || !marker.isFile) {
                        null
                    } else {
                        discoveredInstall(
                            candidate.appId,
                            installDirectory,
                            library,
                            SteamInstallSource.GAMENATIVE_MARKER,
                            null,
                        )
                    }
                } catch (exception: Exception) {
                    issues += SteamMarkerInstallIssue(
                        appId = candidate.appId,
                        directoryName = directoryName,
                        libraryId = library.id,
                        reason = exception.message ?: exception.javaClass.simpleName,
                    )
                    null
                }
            }
        }
    }

    private fun discoverManifests(
        library: GameLibrary,
        errors: MutableList<SteamAppManifestException>,
    ): List<SteamDiscoveredInstall> {
        val steamApps = File(library.rootPath, STEAM_APPS_DIRECTORY).canonicalFile
        val manifests = steamApps.listFiles { file ->
            file.isFile && file.name.startsWith(APP_MANIFEST_PREFIX) && file.extension.equals(ACF_EXTENSION, true)
        }?.sortedBy(File::getName).orEmpty()
        val installRoot = File(steamApps, COMMON_DIRECTORY).canonicalFile
        return manifests.mapNotNull { manifest ->
            try {
                val manifestPath = manifest.canonicalPath
                val fileAppId = parseManifestFileAppId(manifest, manifestPath)
                parseManifest(manifest, manifestPath, fileAppId, installRoot, library)
            } catch (exception: SteamAppManifestException) {
                errors += exception
                null
            }
        }
    }

    private fun parseManifest(
        manifest: File,
        manifestPath: String,
        fileAppId: Int,
        installRoot: File,
        library: GameLibrary,
    ): SteamDiscoveredInstall? {
        val root = try {
            KeyValue.loadFromString(manifest.readText(Charsets.UTF_8))
        } catch (exception: Exception) {
            throw SteamAppManifestException(manifestPath, "cannot read manifest", exception)
        } ?: throw SteamAppManifestException(manifestPath, "malformed KeyValue content")
        if (!root.name.equals(APP_STATE_ROOT, ignoreCase = true)) {
            throw SteamAppManifestException(manifestPath, "root key must be $APP_STATE_ROOT")
        }

        val appId = requiredValue(root, APP_ID_KEY, manifestPath).toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw SteamAppManifestException(manifestPath, "appid must be a positive integer")
        if (appId != fileAppId) {
            throw SteamAppManifestException(
                manifestPath,
                "filename appid $fileAppId does not match AppState appid $appId",
            )
        }
        val stateFlags = requiredValue(root, STATE_FLAGS_KEY, manifestPath).toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: throw SteamAppManifestException(manifestPath, "StateFlags must be a non-negative integer")
        if (stateFlags and FULLY_INSTALLED_STATE == 0) return null

        val installDirName = requiredValue(root, INSTALL_DIR_KEY, manifestPath)
        val installDirectory = resolveDirectInstallDirectory(installRoot, installDirName, manifestPath)
        if (!installDirectory.isDirectory) return null
        val manifestName = root[NAME_KEY].value?.takeIf(String::isNotBlank)
        return discoveredInstall(
            appId,
            installDirectory,
            library,
            SteamInstallSource.STEAM_MANIFEST,
            manifestName,
        )
    }

    private fun parseManifestFileAppId(manifest: File, manifestPath: String): Int {
        val match = APP_MANIFEST_FILE_PATTERN.matchEntire(manifest.name)
            ?: throw SteamAppManifestException(manifestPath, "filename must match appmanifest_<positiveInt>.acf")
        return match.groupValues[1].toIntOrNull()
            ?: throw SteamAppManifestException(manifestPath, "filename appid exceeds the supported integer range")
    }

    private fun requiredValue(root: KeyValue, key: String, manifestPath: String): String =
        root[key].value?.takeIf(String::isNotBlank)
            ?: throw SteamAppManifestException(manifestPath, "$key is missing or blank")

    private fun resolveDirectInstallDirectory(
        installRoot: File,
        directoryName: String,
        manifestPath: String?,
    ): File {
        val directory = File(installRoot, directoryName).canonicalFile
        if (directory.parentFile != installRoot) {
            val message = "installdir must name a direct child of ${installRoot.path}"
            if (manifestPath != null) throw SteamAppManifestException(manifestPath, message)
            throw IllegalArgumentException(message)
        }
        return directory
    }

    private fun discoveredInstall(
        appId: Int,
        installDirectory: File,
        library: GameLibrary,
        source: SteamInstallSource,
        manifestName: String?,
    ) = SteamDiscoveredInstall(
        appId = appId,
        installPath = installDirectory.canonicalPath,
        libraryId = library.id,
        libraryRoot = File(library.rootPath).canonicalPath,
        source = source,
        manifestName = manifestName,
    )

    private fun mergeSameLocationEvidence(
        discoveries: List<SteamDiscoveredInstall>,
    ): List<SteamDiscoveredInstall> = discoveries
        .groupBy { it.appId to it.installPath }
        .map { (_, sameLocation) ->
            sameLocation.singleOrNull() ?: sameLocation.first().copy(
                source = if (sameLocation.any { it.source == SteamInstallSource.STEAM_MANIFEST }) {
                    SteamInstallSource.STEAM_MANIFEST
                } else {
                    SteamInstallSource.GAMENATIVE_MARKER
                },
                manifestName = sameLocation.firstNotNullOfOrNull(SteamDiscoveredInstall::manifestName),
            )
        }

    private companion object {
        const val STEAM_APPS_DIRECTORY = "steamapps"
        const val COMMON_DIRECTORY = "common"
        const val APP_MANIFEST_PREFIX = "appmanifest_"
        const val ACF_EXTENSION = "acf"
        const val APP_STATE_ROOT = "AppState"
        const val APP_ID_KEY = "appid"
        const val STATE_FLAGS_KEY = "StateFlags"
        const val INSTALL_DIR_KEY = "installdir"
        const val NAME_KEY = "name"
        const val FULLY_INSTALLED_STATE = 4
        val APP_MANIFEST_FILE_PATTERN = Regex("appmanifest_([1-9][0-9]*)\\.acf", RegexOption.IGNORE_CASE)
    }
}
