package app.gamenative.data.library

import app.gamenative.data.GameSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.Serializable

/** Stores supported by the managed game-library domain. */
val MANAGED_GAME_SOURCES: Set<GameSource> = setOf(
    GameSource.STEAM,
    GameSource.GOG,
    GameSource.EPIC,
    GameSource.AMAZON,
)

/**
 * A registered filesystem library for one downloadable store.
 *
 * [rootPath] follows that store's native library-root contract: Steam is the directory containing
 * `steamapps`, GOG the directory containing `games/common`, Epic the directory containing `games`,
 * while Amazon is directly the directory containing per-game folders. This keeps both the existing
 * internal `dataDir/Amazon` and external `Amazon/games` locations representable without rewriting.
 *
 * @property id Stable identifier persisted in install and download records.
 * @property source Downloadable store owning this library.
 * @property rootPath Canonical store-native library root.
 * @property builtIn True for the application-owned library that must always remain registered.
 * @property requiresConflictResolution True only for a migrated legacy root that overlaps another
 * library. Such roots remain visible for installed-game discovery but cannot receive installations.
 */
@Serializable
data class GameLibrary(
    val id: String,
    val source: GameSource,
    val rootPath: String,
    val builtIn: Boolean,
    val requiresConflictResolution: Boolean = false,
)

/**
 * Complete, versioned state persisted as one atomic preference value.
 *
 * @property version Schema version used to reject snapshots requiring an unsupported migration.
 * @property libraries All built-in and user-registered libraries across supported stores.
 * @property defaultLibraryIds Exactly one default library identifier for every supported store.
 */
@Serializable
data class GameLibrarySnapshot(
    val version: Int = CURRENT_VERSION,
    val libraries: List<GameLibrary>,
    val defaultLibraryIds: Map<GameSource, String>,
) {
    companion object {
        /** Snapshot schema understood by this build. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * Validated filesystem targets resolved immediately before an install starts.
 *
 * @property library Registered library selected by its stable identifier.
 * @property installRoot Directory containing the selected store's per-game directories.
 * @property stagingRoot Directory reserved for incomplete installation state.
 */
data class GameLibraryInstallation(
    val library: GameLibrary,
    val installRoot: String,
    val stagingRoot: String,
)

/** Resolves a store library root into the directories used by installers. */
interface StoreLibraryLayout {
    /** Store handled by this layout. */
    val source: GameSource

    /** Directory containing installed game directories. */
    fun installRoot(rootPath: String): String

    /** Directory used for incomplete installation data. */
    fun stagingRoot(rootPath: String): String
}

/**
 * Shared pure path resolver for layouts expressed as segments relative to a registered root.
 *
 * @property source Store whose native root contract is represented.
 * @property installSegments Segments from the registered root to per-game directories.
 * @property stagingSegments Segments from the registered root to incomplete installation state.
 */
abstract class RelativeStoreLibraryLayout(
    final override val source: GameSource,
    private val installSegments: List<String>,
    private val stagingSegments: List<String>,
) : StoreLibraryLayout {
    /** Resolves [installSegments] without touching the filesystem. */
    final override fun installRoot(rootPath: String): String =
        resolve(rootPath, installSegments)

    /** Resolves [stagingSegments] without touching the filesystem. */
    final override fun stagingRoot(rootPath: String): String =
        resolve(rootPath, stagingSegments)

    /** Applies relative segments using platform path rules. */
    private fun resolve(rootPath: String, segments: List<String>): String =
        segments.fold(Path.of(rootPath)) { path, segment -> path.resolve(segment) }.normalize().toString()
}

/** Steam-compatible library directory layout. */
object SteamLibraryLayout : RelativeStoreLibraryLayout(
    GameSource.STEAM,
    listOf("steamapps", "common"),
    listOf("steamapps", "staging"),
)

/** GOG library directory layout. */
object GogLibraryLayout : RelativeStoreLibraryLayout(
    GameSource.GOG,
    listOf("games", "common"),
    listOf("games", "staging"),
)

/** Epic Games library directory layout. */
object EpicLibraryLayout : RelativeStoreLibraryLayout(
    GameSource.EPIC,
    listOf("games"),
    listOf("staging"),
)

/**
 * Amazon Games layout, whose library root directly contains game directories.
 * Existing internal `dataDir/Amazon` and external `Amazon/games` roots therefore map identically.
 */
object AmazonLibraryLayout : RelativeStoreLibraryLayout(
    GameSource.AMAZON,
    emptyList(),
    listOf(".staging"),
)

/** Returns the layout for a downloadable store and rejects unsupported sources. */
fun storeLibraryLayout(source: GameSource): StoreLibraryLayout = when (source) {
    GameSource.STEAM -> SteamLibraryLayout
    GameSource.GOG -> GogLibraryLayout
    GameSource.EPIC -> EpicLibraryLayout
    GameSource.AMAZON -> AmazonLibraryLayout
    GameSource.CUSTOM_GAME -> throw IllegalArgumentException("Custom games do not have managed libraries")
}

/** Canonicalizes paths before global equality and nesting checks. */
fun canonicalLibraryPath(path: String): String {
    require(path.isNotBlank()) { "Library path must not be blank" }
    return File(path).canonicalFile.path
}

/**
 * Returns the user-facing name of a custom library from its selected directory.
 *
 * Regular directories use their final path segment. A filesystem or volume root has no final
 * segment, so its canonical path is retained as a stable readable name instead of substituting a
 * generic custom-library label.
 */
fun customLibraryDisplayName(rootPath: String): String {
    val root = File(canonicalLibraryPath(rootPath))
    return root.name.ifBlank { root.path }
}

/**
 * Produces the conservative identity used by custom libraries and migrated legacy identifiers.
 * Shared Android storage is commonly case-insensitive even though JVM path comparison is not.
 */
fun libraryPathIdentity(path: String): String =
    canonicalLibraryPath(path).lowercase(Locale.ROOT)

/** True when two paths identify the same file, compare equally, or contain one another. */
fun libraryPathsConflict(firstPath: String, secondPath: String): Boolean {
    val firstCanonical = canonicalLibraryPath(firstPath)
    val secondCanonical = canonicalLibraryPath(secondPath)
    val first = Path.of(firstCanonical)
    val second = Path.of(secondCanonical)
    val sameExistingFile = Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second)
    if (sameExistingFile) return true

    val firstIdentity = Path.of(libraryPathIdentity(firstCanonical))
    val secondIdentity = Path.of(libraryPathIdentity(secondCanonical))
    return firstIdentity == secondIdentity ||
        firstIdentity.startsWith(secondIdentity) ||
        secondIdentity.startsWith(firstIdentity)
}
