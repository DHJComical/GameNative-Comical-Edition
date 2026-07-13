package app.gamenative.data.library

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Legacy Steam preferences read in the same transaction as the new snapshot.
 *
 * @property paths Previously registered custom Steam roots.
 * @property defaultPath Previously selected custom Steam root, or blank for the built-in library.
 */
internal data class LegacySteamLibraryPreferences(
    val paths: Set<String>,
    val defaultPath: String,
)

/** Atomic persistence boundary used to directly test repository transactions. */
internal interface GameLibrarySnapshotStorage {
    /** Reads legacy/current state and commits the returned snapshot as one indivisible update. */
    suspend fun update(
        transform: (snapshotJson: String?, legacy: LegacySteamLibraryPreferences) -> String,
    ): String
}

/** Production snapshot storage backed by the application's Preferences DataStore. */
private object GameLibrarySnapshotStorageImpl : GameLibrarySnapshotStorage {
    override suspend fun update(
        transform: (snapshotJson: String?, legacy: LegacySteamLibraryPreferences) -> String,
    ): String = PrefManager.updateGameLibrarySnapshot(transform)
}

/**
 * DataStore-backed repository implementation; consumers should depend on [GameLibraryRepository].
 *
 * @property builtInRoots Current application-owned root for every downloadable store.
 * @property pathAccessPolicy Platform and filesystem checks repeated at each write/install entry point.
 * @property rootResolver Store-aware normalization for user selections containing existing games.
 * @property storage Atomic snapshot persistence boundary.
 * @property createId Stable-id source used only when registering a new custom library.
 */
class GameLibraryRepositoryImpl private constructor(
    private val builtInRoots: Map<GameSource, String>,
    private val pathAccessPolicy: PathAccessPolicy,
    private val rootResolver: GameLibraryRootResolver,
    private val storage: GameLibrarySnapshotStorage,
    private val createId: () -> String,
) : GameLibraryRepository {
    /** Creates the production repository using the application's single preference transaction. */
    constructor(
        builtInRoots: Map<GameSource, String>,
        pathAccessPolicy: PathAccessPolicy,
        rootResolver: GameLibraryRootResolver,
    ) : this(
        builtInRoots,
        pathAccessPolicy,
        rootResolver,
        GameLibrarySnapshotStorageImpl,
        { UUID.randomUUID().toString() },
    )

    init {
        require(builtInRoots.keys == MANAGED_GAME_SOURCES) {
            "Built-in roots must be provided for Steam, GOG, Epic, and Amazon"
        }
        builtInRoots.values.forEach(::canonicalLibraryPath)
        validateNoConflicts(
            builtInRoots.map { (source, path) -> builtInLibrary(source, path) },
        )
    }

    override suspend fun getSnapshot(): GameLibrarySnapshot = updateSnapshot { it }

    override suspend fun addLibrary(source: GameSource, rootPath: String): GameLibrarySnapshot {
        requireManagedSource(source)
        val resolvedRoot = rootResolver.resolve(source, rootPath)
        requireCustomPathAccess(resolvedRoot)
        return updateSnapshot { snapshot ->
            require(snapshot.libraries.none { libraryPathsConflict(it.rootPath, resolvedRoot) }) {
                "Library path conflicts with an existing library: $resolvedRoot"
            }
            val library = GameLibrary(
                id = createId(),
                source = source,
                rootPath = resolvedRoot,
                builtIn = false,
            )
            snapshot.copy(
                libraries = snapshot.libraries + library,
                defaultLibraryIds = snapshot.defaultLibraryIds + (source to library.id),
            )
        }
    }

    override suspend fun setDefaultLibrary(source: GameSource, libraryId: String): GameLibrarySnapshot {
        requireManagedSource(source)
        return updateSnapshot { snapshot ->
            val library = snapshot.libraries.singleOrNull { it.id == libraryId }
                ?: throw IllegalArgumentException("Unknown library id: $libraryId")
            require(library.source == source) { "Library $libraryId does not belong to $source" }
            require(!library.requiresConflictResolution) {
                "Library $libraryId must resolve its legacy path conflict before becoming default"
            }
            if (!library.builtIn) requireCustomPathAccess(library.rootPath)
            snapshot.copy(defaultLibraryIds = snapshot.defaultLibraryIds + (source to library.id))
        }
    }

    override suspend fun removeLibrary(libraryId: String): GameLibrarySnapshot = updateSnapshot { snapshot ->
        val library = snapshot.libraries.singleOrNull { it.id == libraryId }
            ?: throw IllegalArgumentException("Unknown library id: $libraryId")
        require(!library.builtIn) { "Built-in libraries cannot be removed" }
        val remaining = markLegacyConflicts(snapshot.libraries - library)
        val defaults = if (snapshot.defaultLibraryIds[library.source] == library.id) {
            val builtIn = remaining.single { it.source == library.source && it.builtIn }
            snapshot.defaultLibraryIds + (library.source to builtIn.id)
        } else {
            snapshot.defaultLibraryIds
        }
        snapshot.copy(libraries = remaining, defaultLibraryIds = defaults)
    }

    override suspend fun resolveInstallation(
        source: GameSource,
        libraryId: String,
    ): GameLibraryInstallation {
        requireManagedSource(source)
        val snapshot = getSnapshot()
        val library = snapshot.libraries.singleOrNull { it.id == libraryId }
            ?: throw IllegalArgumentException("Unknown library id: $libraryId")
        require(library.source == source) { "Library $libraryId does not belong to $source" }
        require(!library.requiresConflictResolution) {
            "Library $libraryId is discovery-only until its legacy path conflict is resolved"
        }
        if (!library.builtIn) requireCustomPathAccess(library.rootPath)
        val layout = storeLibraryLayout(source)
        return GameLibraryInstallation(
            library = library,
            installRoot = layout.installRoot(library.rootPath),
            stagingRoot = layout.stagingRoot(library.rootPath),
        )
    }

    private suspend fun updateSnapshot(
        transform: (GameLibrarySnapshot) -> GameLibrarySnapshot,
    ): GameLibrarySnapshot {
        val encoded = try {
            storage.update { storedJson, legacy ->
                val current = storedJson?.let(::decodeSnapshot) ?: migrateLegacySteamPreferences(legacy)
                val updated = transform(current)
                validateSnapshot(updated)
                Json.encodeToString(updated)
            }
        } catch (exception: Exception) {
            Timber.e(exception, "Game library snapshot transaction failed")
            throw exception
        }
        return decodeSnapshot(encoded)
    }

    /** Repairs evidence-backed v1 custom roots without rechecking access or changing identity. */
    private fun migrateSnapshotV1(snapshot: GameLibrarySnapshot): GameLibrarySnapshot {
        require(snapshot.version == 1) { "Only game library snapshot v1 can be migrated" }
        val normalizedLibraries = snapshot.libraries.map { library ->
            if (library.builtIn) {
                library
            } else {
                val resolvedRoot = rootResolver.resolve(library.source, library.rootPath)
                if (resolvedRoot == library.rootPath) {
                    library
                } else {
                    Timber.i(
                        "Normalized persisted ${library.source} library ${library.id}: " +
                            "${library.rootPath} -> $resolvedRoot",
                    )
                    library.copy(rootPath = resolvedRoot)
                }
            }
        }
        val conflictLibraries = markLegacyConflicts(normalizedLibraries)
        val repairedDefaults = snapshot.defaultLibraryIds.toMutableMap()
        MANAGED_GAME_SOURCES.forEach { source ->
            val defaultId = repairedDefaults[source] ?: return@forEach
            val defaultLibrary = conflictLibraries.singleOrNull { it.id == defaultId && it.source == source }
            if (defaultLibrary?.requiresConflictResolution == true) {
                val builtIn = conflictLibraries.single { it.source == source && it.builtIn }
                repairedDefaults[source] = builtIn.id
                Timber.w(
                    "Migrated $source default library ${defaultLibrary.id} has a path conflict; using ${builtIn.id}",
                )
            }
        }
        return snapshot.copy(
            version = GameLibrarySnapshot.CURRENT_VERSION,
            libraries = conflictLibraries,
            defaultLibraryIds = repairedDefaults,
        ).also(::validateSnapshot)
    }

    /** Converts both old Steam keys into the initial snapshot without moving filesystem data. */
    private fun migrateLegacySteamPreferences(
        legacy: LegacySteamLibraryPreferences,
    ): GameLibrarySnapshot {
        val builtIns = MANAGED_GAME_SOURCES.map { source ->
            builtInLibrary(source, builtInRoots.getValue(source))
        }
        val canonicalLegacyPaths = legacy.paths.map(::canonicalLibraryPath).toMutableSet()
        val defaultCanonical = legacy.defaultPath.takeIf(String::isNotBlank)?.let(::canonicalLibraryPath)
        if (defaultCanonical != null && canonicalLegacyPaths.none {
                libraryPathIdentity(it) == libraryPathIdentity(defaultCanonical)
            }
        ) {
            canonicalLegacyPaths += defaultCanonical
        }
        val distinctLegacyPaths = canonicalLegacyPaths
            .groupBy(::libraryPathIdentity)
            .values
            .map { aliases -> aliases.minOrNull() ?: error("Legacy path group is empty") }
            .filterNot { path ->
                builtIns.any {
                    it.source == GameSource.STEAM &&
                        libraryPathIdentity(it.rootPath) == libraryPathIdentity(path)
                }
            }
            .sortedBy(::libraryPathIdentity)
        val migratedSteamLibraries = distinctLegacyPaths.map { path ->
            GameLibrary(
                id = deterministicLibraryId(GameSource.STEAM, path),
                source = GameSource.STEAM,
                rootPath = path,
                builtIn = false,
            )
        }
        val libraries = builtIns + migratedSteamLibraries
        val defaults = builtIns.associate { it.source to it.id }.toMutableMap()
        if (defaultCanonical != null) {
            val requestedDefault = libraries.singleOrNull {
                it.source == GameSource.STEAM &&
                    libraryPathIdentity(it.rootPath) == libraryPathIdentity(defaultCanonical)
            }
            if (requestedDefault != null) {
                defaults[GameSource.STEAM] = requestedDefault.id
            }
        }
        Timber.i("Migrated ${migratedSteamLibraries.size} legacy Steam libraries without moving files")
        return migrateSnapshotV1(
            GameLibrarySnapshot(version = 1, libraries = libraries, defaultLibraryIds = defaults),
        )
    }

    /** Decodes and validates persisted state before it reaches any caller. */
    private fun decodeSnapshot(json: String): GameLibrarySnapshot {
        return try {
            val snapshot = Json.decodeFromString<GameLibrarySnapshot>(json)
            when (snapshot.version) {
                1 -> migrateSnapshotV1(snapshot)
                GameLibrarySnapshot.CURRENT_VERSION -> snapshot.also(::validateSnapshot)
                else -> throw IllegalArgumentException(
                    "Unsupported game library snapshot version: ${snapshot.version}",
                )
            }
        } catch (exception: Exception) {
            Timber.e(exception, "Failed to decode or validate game library snapshot")
            throw exception
        }
    }

    /** Enforces schema, source, default, built-in, canonical-path, and conflict invariants. */
    private fun validateSnapshot(snapshot: GameLibrarySnapshot) {
        require(snapshot.version == GameLibrarySnapshot.CURRENT_VERSION) {
            "Unsupported game library snapshot version: ${snapshot.version}"
        }
        require(snapshot.libraries.map(GameLibrary::id).distinct().size == snapshot.libraries.size) {
            "Library ids must be unique"
        }
        require(snapshot.libraries.all { it.source in MANAGED_GAME_SOURCES }) {
            "Snapshot contains an unsupported game source"
        }
        require(snapshot.libraries.none { it.builtIn && it.requiresConflictResolution }) {
            "Built-in libraries cannot require conflict resolution"
        }
        MANAGED_GAME_SOURCES.forEach { source ->
            val builtIns = snapshot.libraries.filter { it.source == source && it.builtIn }
            require(builtIns.size == 1) {
                "$source must have exactly one built-in library"
            }
            require(builtIns.single() == builtInLibrary(source, builtInRoots.getValue(source))) {
                "$source built-in library does not match the application-owned path"
            }
            val defaultId = snapshot.defaultLibraryIds[source]
                ?: throw IllegalStateException("$source has no default library")
            require(snapshot.libraries.any { it.id == defaultId && it.source == source }) {
                "$source default library is invalid"
            }
            require(
                snapshot.libraries.single { it.id == defaultId }.requiresConflictResolution.not(),
            ) {
                "$source default library requires conflict resolution"
            }
        }
        require(snapshot.defaultLibraryIds.keys == MANAGED_GAME_SOURCES) {
            "Snapshot defaults must contain exactly the managed game sources"
        }
        require(snapshot.libraries.all { it.rootPath == canonicalLibraryPath(it.rootPath) }) {
            "Library roots must be stored in canonical form"
        }
        validateConflictState(snapshot.libraries)
    }

    /** Applies strict global equality and nesting checks when registering a new root. */
    private fun validateNoConflicts(libraries: List<GameLibrary>) {
        libraries.forEachIndexed { index, first ->
            libraries.drop(index + 1).forEach { second ->
                require(!libraryPathsConflict(first.rootPath, second.rootPath)) {
                    "Library paths conflict: ${first.rootPath} and ${second.rootPath}"
                }
            }
        }
    }

    /** Marks every migrated custom root participating in a non-identical path overlap. */
    private fun markLegacyConflicts(libraries: List<GameLibrary>): List<GameLibrary> =
        libraries.map { library ->
            if (library.builtIn) {
                library.copy(requiresConflictResolution = false)
            } else {
                val conflicts = libraries.any { other ->
                    other.id != library.id && libraryPathsConflict(library.rootPath, other.rootPath)
                }
                library.copy(requiresConflictResolution = conflicts)
            }
        }

    /** Ensures persisted conflict flags exactly describe all allowed legacy overlaps. */
    private fun validateConflictState(libraries: List<GameLibrary>) {
        val expected = markLegacyConflicts(libraries)
        require(libraries == expected) { "Legacy library conflict flags are inconsistent" }
        libraries.forEachIndexed { index, first ->
            libraries.drop(index + 1).forEach { second ->
                if (libraryPathsConflict(first.rootPath, second.rootPath)) {
                    require(first.requiresConflictResolution || second.requiresConflictResolution) {
                        "Unmarked library path conflict: ${first.rootPath} and ${second.rootPath}"
                    }
                }
            }
        }
    }

    /** Rejects a custom root when Android no longer grants raw filesystem access. */
    private fun requireCustomPathAccess(path: String) {
        pathAccessPolicy.requireAccessibleDirectory(path)
    }

    /** Rejects custom games and any future source until its layout is deliberately added. */
    private fun requireManagedSource(source: GameSource) {
        require(source in MANAGED_GAME_SOURCES) { "$source does not support managed libraries" }
    }

    /** Creates the deterministic, non-removable entry for one application-owned root. */
    private fun builtInLibrary(source: GameSource, rootPath: String) = GameLibrary(
        id = "builtin-${source.name.lowercase()}",
        source = source,
        rootPath = canonicalLibraryPath(rootPath),
        builtIn = true,
        requiresConflictResolution = false,
    )

    /** Derives the same migration id from a source and canonical path on every run. */
    private fun deterministicLibraryId(source: GameSource, canonicalPath: String): String =
        UUID.nameUUIDFromBytes(
            "${source.name}\u0000${libraryPathIdentity(canonicalPath)}".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    internal companion object {
        /** Constructs the repository with deterministic test dependencies and no reflection. */
        fun forTest(
            builtInRoots: Map<GameSource, String>,
            pathAccessPolicy: PathAccessPolicy,
            storage: GameLibrarySnapshotStorage,
            createId: () -> String,
            rootResolver: GameLibraryRootResolver = GameLibraryRootResolverImpl(),
        ): GameLibraryRepositoryImpl = GameLibraryRepositoryImpl(
            builtInRoots,
            pathAccessPolicy,
            rootResolver,
            storage,
            createId,
        )
    }
}
