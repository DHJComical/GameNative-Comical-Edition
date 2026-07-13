package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.epic.manifest.EpicManifest
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/** Resolves a user-selected existing directory to its store-native registered library root. */
interface GameLibraryRootResolver {
    /**
     * Returns the canonical store root proven by supported installation evidence, or the canonical
     * selected directory when no safe rewrite can be established.
     */
    fun resolve(source: GameSource, selectedPath: String): String
}

/** Filesystem-backed [GameLibraryRootResolver] limited to evidence supported by local discovery. */
class GameLibraryRootResolverImpl(
    private val gogInfoFileFinder: GogInfoFileFinder = GogInfoFileFinderImpl(),
) : GameLibraryRootResolver {
    override fun resolve(source: GameSource, selectedPath: String): String {
        require(source in MANAGED_GAME_SOURCES) { "$source does not support managed libraries" }
        val selected = File(canonicalLibraryPath(selectedPath))
        if (hasStoreEvidence(source, selected)) return selected.path

        return alternateRoots(source, selected)
            .firstOrNull { candidate -> hasStoreEvidence(source, candidate) }
            ?.path
            ?: selected.path
    }

    private fun alternateRoots(source: GameSource, selected: File): List<File> = when (source) {
        GameSource.STEAM -> steamAlternateRoots(selected)
        GameSource.GOG -> gogAlternateRoots(selected)
        GameSource.EPIC -> namedParent(selected, GAMES_DIRECTORY)
        GameSource.AMAZON -> listOf(File(selected, GAMES_DIRECTORY).canonicalFile)
        GameSource.CUSTOM_GAME -> emptyList()
    }

    private fun steamAlternateRoots(selected: File): List<File> = when {
        selected.name.equals(COMMON_DIRECTORY, ignoreCase = true) &&
            selected.parentFile?.name.equals(STEAM_APPS_DIRECTORY, ignoreCase = true) -> {
            listOfNotNull(selected.parentFile?.parentFile?.canonicalFile)
        }
        selected.name.equals(STEAM_APPS_DIRECTORY, ignoreCase = true) -> listOfNotNull(selected.parentFile?.canonicalFile)
        else -> emptyList()
    }

    private fun gogAlternateRoots(selected: File): List<File> = when {
        selected.name.equals(COMMON_DIRECTORY, ignoreCase = true) &&
            selected.parentFile?.name.equals(GAMES_DIRECTORY, ignoreCase = true) -> {
            listOfNotNull(selected.parentFile?.parentFile?.canonicalFile)
        }
        selected.name.equals(GAMES_DIRECTORY, ignoreCase = true) -> listOfNotNull(selected.parentFile?.canonicalFile)
        else -> emptyList()
    }

    private fun namedParent(selected: File, expectedName: String): List<File> =
        if (selected.name.equals(expectedName, ignoreCase = true)) {
            listOfNotNull(selected.parentFile?.canonicalFile)
        } else {
            emptyList()
        }

    private fun hasStoreEvidence(source: GameSource, root: File): Boolean = when (source) {
        GameSource.STEAM -> hasSteamEvidence(root)
        GameSource.GOG -> hasGogEvidence(root)
        GameSource.EPIC -> hasEpicEvidence(root)
        GameSource.AMAZON -> hasDirectMarkerEvidence(root)
        GameSource.CUSTOM_GAME -> false
    }

    private fun hasSteamEvidence(root: File): Boolean {
        val steamApps = File(root, STEAM_APPS_DIRECTORY).canonicalFile
        val common = File(steamApps, COMMON_DIRECTORY).canonicalFile
        if (hasDirectMarkerEvidence(common)) return true
        val manifests = steamApps.listFiles { file ->
            file.isFile && file.name.startsWith(APP_MANIFEST_PREFIX) && file.extension.equals(ACF_EXTENSION, true)
        }?.sortedBy(File::getName).orEmpty()
        return manifests.any { manifest -> steamManifestHasInstalledDirectory(manifest, common) }
    }

    private fun steamManifestHasInstalledDirectory(manifest: File, common: File): Boolean {
        return try {
            val root = KeyValue.loadFromString(manifest.readText(Charsets.UTF_8)) ?: return false
            if (!root.name.equals(APP_STATE_ROOT, ignoreCase = true)) return false
            val fileAppId = APP_MANIFEST_FILE_PATTERN.matchEntire(manifest.name)?.groupValues?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: return false
            val appId = root[APP_ID_KEY].value?.toIntOrNull()?.takeIf { it > 0 } ?: return false
            if (appId != fileAppId) return false
            val stateFlags = root[STATE_FLAGS_KEY].value?.toIntOrNull()?.takeIf { it >= 0 } ?: return false
            if (stateFlags and FULLY_INSTALLED_STATE == 0) return false
            val installDir = root[INSTALL_DIR_KEY].value?.takeIf(String::isNotBlank) ?: return false
            val directory = File(common, installDir).canonicalFile
            directory.parentFile == common && directory.isDirectory
        } catch (exception: Exception) {
            Timber.w(exception, "Ignoring unreadable Steam manifest while resolving library root: ${manifest.path}")
            false
        }
    }

    private fun hasGogEvidence(root: File): Boolean {
        val common = File(root, "$GAMES_DIRECTORY${File.separator}$COMMON_DIRECTORY").canonicalFile
        return directGameDirectories(common).any { directory ->
            hasMarker(directory) || gogInfoFileFinder.find(directory).any(::isValidGogInfo)
        }
    }

    private fun isValidGogInfo(infoFile: File): Boolean = try {
        JSONObject(infoFile.readText(Charsets.UTF_8)).optString(GAME_ID_KEY).isNotBlank()
    } catch (exception: Exception) {
        Timber.w(exception, "Ignoring unreadable GOG info while resolving library root: ${infoFile.path}")
        false
    }

    private fun hasEpicEvidence(root: File): Boolean {
        val games = File(root, GAMES_DIRECTORY).canonicalFile
        return directGameDirectories(games).any { directory ->
            hasMarker(directory) || File(directory, EPIC_METADATA_DIRECTORY).listFiles { file ->
                file.isFile && file.extension.equals(MANIFEST_EXTENSION, ignoreCase = true)
            }?.any(::isValidEpicManifest) == true
        }
    }

    private fun isValidEpicManifest(manifest: File): Boolean = try {
        EpicManifest.readAll(manifest.readBytes()).meta?.appName?.isNotBlank() == true
    } catch (exception: Exception) {
        Timber.w(exception, "Ignoring unreadable Epic manifest while resolving library root: ${manifest.path}")
        false
    }

    private fun hasDirectMarkerEvidence(root: File): Boolean = directGameDirectories(root).any(::hasMarker)

    private fun directGameDirectories(root: File): List<File> =
        root.listFiles { file -> file.isDirectory }?.toList().orEmpty()

    private fun hasMarker(directory: File): Boolean =
        File(directory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).isFile

    private companion object {
        const val STEAM_APPS_DIRECTORY = "steamapps"
        const val GAMES_DIRECTORY = "games"
        const val COMMON_DIRECTORY = "common"
        const val APP_MANIFEST_PREFIX = "appmanifest_"
        const val ACF_EXTENSION = "acf"
        const val APP_STATE_ROOT = "AppState"
        const val APP_ID_KEY = "appid"
        const val STATE_FLAGS_KEY = "StateFlags"
        const val INSTALL_DIR_KEY = "installdir"
        const val FULLY_INSTALLED_STATE = 4
        const val GAME_ID_KEY = "gameId"
        const val EPIC_METADATA_DIRECTORY = ".egstore"
        const val MANIFEST_EXTENSION = "manifest"
        val APP_MANIFEST_FILE_PATTERN = Regex("appmanifest_([1-9][0-9]*)\\.acf", RegexOption.IGNORE_CASE)
    }
}
