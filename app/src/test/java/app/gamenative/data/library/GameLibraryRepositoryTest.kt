package app.gamenative.data.library

import app.gamenative.data.GameSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameLibraryRepositoryTest {
    private lateinit var root: File
    private lateinit var builtInRoots: Map<GameSource, String>

    @Before
    fun setUp() {
        root = Files.createTempDirectory("game-library-test").toFile()
        builtInRoots = MANAGED_GAME_SOURCES.associateWith { source ->
            File(root, "built-in/${source.name.lowercase()}").path
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun layoutsResolveStoreSpecificInstallAndStagingDirectories() {
        val libraryRoot = File(root, "selected").path

        assertPathEndsWith(storeLibraryLayout(GameSource.STEAM).installRoot(libraryRoot), "steamapps/common")
        assertPathEndsWith(storeLibraryLayout(GameSource.STEAM).stagingRoot(libraryRoot), "steamapps/staging")
        assertPathEndsWith(storeLibraryLayout(GameSource.GOG).installRoot(libraryRoot), "games/common")
        assertPathEndsWith(storeLibraryLayout(GameSource.GOG).stagingRoot(libraryRoot), "games/staging")
        assertPathEndsWith(storeLibraryLayout(GameSource.EPIC).installRoot(libraryRoot), "games")
        assertPathEndsWith(storeLibraryLayout(GameSource.EPIC).stagingRoot(libraryRoot), "staging")
        assertEquals(
            canonicalLibraryPath(libraryRoot),
            canonicalLibraryPath(storeLibraryLayout(GameSource.AMAZON).installRoot(libraryRoot)),
        )
        assertPathEndsWith(storeLibraryLayout(GameSource.AMAZON).stagingRoot(libraryRoot), ".staging")
        assertThrows(IllegalArgumentException::class.java) {
            storeLibraryLayout(GameSource.CUSTOM_GAME)
        }
    }

    @Test
    fun amazonLayoutPreservesExistingInternalAndExternalGamesRoots() {
        val internal = File(root, "data/Amazon").path
        val external = File(root, "storage/Amazon/games").path

        assertEquals(canonicalLibraryPath(internal), canonicalLibraryPath(AmazonLibraryLayout.installRoot(internal)))
        assertEquals(canonicalLibraryPath(external), canonicalLibraryPath(AmazonLibraryLayout.installRoot(external)))
    }

    @Test
    fun pathConflictRejectsEqualAncestorAndDescendantPaths() {
        val parent = File(root, "games")
        val child = File(parent, "steam")
        val sibling = File(root, "other")

        assertTrue(libraryPathsConflict(parent.path, parent.path + File.separator))
        assertTrue(libraryPathsConflict(File(parent, "../games").path, parent.path))
        assertTrue(libraryPathsConflict(parent.path, child.path))
        assertTrue(libraryPathsConflict(child.path, parent.path))
        assertFalse(libraryPathsConflict(parent.path, sibling.path))
    }

    @Test
    fun pathConflictConservativelyRejectsCaseOnlyAliases() {
        val mixedCase = File(root, "Shared/Games").path
        val lowerCase = File(root, "shared/games").path

        assertTrue(libraryPathsConflict(mixedCase, lowerCase))
    }

    @Test
    fun existingFilesystemAliasesResolveToTheSameIdentity() {
        val directory = File(root, "existing-alias").apply { mkdirs() }
        val relativeAlias = File(directory, "../existing-alias")

        assertTrue(libraryPathsConflict(directory.path, relativeAlias.path))
        assertEquals(libraryPathIdentity(directory.path), libraryPathIdentity(relativeAlias.path))
    }

    @Test
    fun firstReadCreatesBuiltInsAndMigratesSteamPreferencesOnlyOnce() = runBlocking {
        val legacySteam = File(root, "legacy-steam").path
        val storage = FakeStorage(
            legacy = LegacySteamLibraryPreferences(setOf(legacySteam), legacySteam),
        )
        val repository = repository(storage = storage)

        val first = repository.getSnapshot()
        val second = repository.getSnapshot()

        assertEquals(GameLibrarySnapshot.CURRENT_VERSION, first.version)
        assertEquals(5, first.libraries.size)
        val migratedId = first.libraries.single { !it.builtIn }.id
        assertEquals(migratedId, first.defaultLibraryIds.getValue(GameSource.STEAM))
        assertEquals(first, second)
    }

    @Test
    fun missingLegacyDefaultIsRegisteredAndSetOrderDoesNotChangeIdsOrOrdering() = runBlocking {
        val firstPath = File(root, "legacy-z").path
        val secondPath = File(root, "legacy-a").path
        val missingDefault = File(root, "legacy-default").path
        val first = repository(
            FakeStorage(legacy = LegacySteamLibraryPreferences(linkedSetOf(firstPath, secondPath), missingDefault)),
        ).getSnapshot()
        val second = repository(
            FakeStorage(legacy = LegacySteamLibraryPreferences(linkedSetOf(secondPath, firstPath), missingDefault)),
        ).getSnapshot()

        val firstCustom = first.libraries.filterNot(GameLibrary::builtIn)
        val secondCustom = second.libraries.filterNot(GameLibrary::builtIn)
        assertEquals(firstCustom, secondCustom)
        assertEquals(3, firstCustom.size)
        assertEquals(
            canonicalLibraryPath(missingDefault),
            first.libraries.single { it.id == first.defaultLibraryIds.getValue(GameSource.STEAM) }.rootPath,
        )
    }

    @Test
    fun conflictingMissingLegacyDefaultFallsBackToBuiltIn() = runBlocking {
        val steamBuiltIn = builtInRoots.getValue(GameSource.STEAM)
        val conflictingDefault = File(steamBuiltIn, "nested").path
        val repository = repository(
            FakeStorage(legacy = LegacySteamLibraryPreferences(emptySet(), conflictingDefault)),
        )
        val snapshot = repository.getSnapshot()

        val default = snapshot.libraries.single { it.id == snapshot.defaultLibraryIds.getValue(GameSource.STEAM) }
        val retainedConflict = snapshot.libraries.single {
            it.rootPath == canonicalLibraryPath(conflictingDefault)
        }
        assertTrue(default.builtIn)
        assertTrue(retainedConflict.requiresConflictResolution)
        assertEquals(5, snapshot.libraries.size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setDefaultLibrary(GameSource.STEAM, retainedConflict.id) }
        }
        Unit
    }

    @Test
    fun nestedLegacyLibrariesRemainForDiscoveryButCannotReceiveInstalls() = runBlocking {
        val parentPath = File(root, "legacy-parent").path
        val childPath = File(parentPath, "child").path
        val storage = FakeStorage(
            legacy = LegacySteamLibraryPreferences(setOf(parentPath, childPath), parentPath),
        )
        val repository = repository(storage)
        val snapshot = repository.getSnapshot()
        val conflicts = snapshot.libraries.filter(GameLibrary::requiresConflictResolution)

        assertEquals(2, conflicts.size)
        assertEquals(
            setOf(canonicalLibraryPath(parentPath), canonicalLibraryPath(childPath)),
            conflicts.map(GameLibrary::rootPath).toSet(),
        )
        val parent = conflicts.single { it.rootPath == canonicalLibraryPath(parentPath) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setDefaultLibrary(GameSource.STEAM, parent.id) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.resolveInstallation(GameSource.STEAM, parent.id) }
        }

        val child = conflicts.single { it.rootPath == canonicalLibraryPath(childPath) }
        val afterRemoval = repository.removeLibrary(child.id)
        val retainedParent = afterRemoval.libraries.single { it.id == parent.id }
        assertFalse(retainedParent.requiresConflictResolution)
        assertEquals(
            retainedParent.id,
            repository.setDefaultLibrary(GameSource.STEAM, retainedParent.id)
                .defaultLibraryIds.getValue(GameSource.STEAM),
        )
    }

    @Test
    fun canonicalLegacyDuplicatesMergeAndDefaultStillTargetsMergedLibrary() = runBlocking {
        val rootPath = File(root, "legacy-duplicate").path
        val aliasPath = File(root, "other/../legacy-duplicate").path
        val snapshot = repository(
            FakeStorage(
                legacy = LegacySteamLibraryPreferences(setOf(rootPath, aliasPath), aliasPath),
            ),
        ).getSnapshot()

        val custom = snapshot.libraries.filterNot(GameLibrary::builtIn)
        assertEquals(1, custom.size)
        assertFalse(custom.single().requiresConflictResolution)
        assertEquals(custom.single().id, snapshot.defaultLibraryIds.getValue(GameSource.STEAM))
    }

    @Test
    fun caseOnlyLegacyAliasesMergeAndKeepDeterministicId() = runBlocking {
        val mixedCase = File(root, "Legacy-Steam").path
        val lowerCase = File(root, "legacy-steam").path
        val first = repository(
            FakeStorage(legacy = LegacySteamLibraryPreferences(setOf(mixedCase, lowerCase), lowerCase)),
        ).getSnapshot().libraries.single { !it.builtIn }
        val second = repository(
            FakeStorage(legacy = LegacySteamLibraryPreferences(setOf(lowerCase), lowerCase)),
        ).getSnapshot().libraries.single { !it.builtIn }

        assertEquals(first.id, second.id)
        assertEquals(libraryPathIdentity(first.rootPath), libraryPathIdentity(second.rootPath))
    }

    @Test
    fun legacyRootExactlyMatchingBuiltInMergesIntoBuiltInAndKeepsDefaultMapping() = runBlocking {
        val steamBuiltIn = builtInRoots.getValue(GameSource.STEAM)
        val snapshot = repository(
            FakeStorage(
                legacy = LegacySteamLibraryPreferences(setOf(steamBuiltIn), steamBuiltIn),
            ),
        ).getSnapshot()

        val steamLibraries = snapshot.libraries.filter { it.source == GameSource.STEAM }
        assertEquals(1, steamLibraries.size)
        assertTrue(steamLibraries.single().builtIn)
        assertEquals(steamLibraries.single().id, snapshot.defaultLibraryIds.getValue(GameSource.STEAM))
    }

    @Test
    fun legacySteamRootMatchingAnotherStoresBuiltInIsRetainedAsSteamConflict() = runBlocking {
        val gogBuiltInPath = builtInRoots.getValue(GameSource.GOG)
        val snapshot = repository(
            FakeStorage(
                legacy = LegacySteamLibraryPreferences(setOf(gogBuiltInPath), gogBuiltInPath),
            ),
        ).getSnapshot()

        val retainedSteam = snapshot.libraries.single {
            it.source == GameSource.STEAM && it.rootPath == canonicalLibraryPath(gogBuiltInPath)
        }
        val steamDefault = snapshot.libraries.single {
            it.id == snapshot.defaultLibraryIds.getValue(GameSource.STEAM)
        }
        assertTrue(retainedSteam.requiresConflictResolution)
        assertTrue(steamDefault.builtIn)
        assertEquals(GameSource.STEAM, steamDefault.source)
    }

    @Test
    fun snapshotWhoseDefaultTargetsConflictLibraryFailsValidation() = runBlocking {
        val parentPath = File(root, "invalid-default-parent").path
        val childPath = File(parentPath, "child").path
        val migrated = repository(
            FakeStorage(
                legacy = LegacySteamLibraryPreferences(setOf(parentPath, childPath), ""),
            ),
        ).getSnapshot()
        val conflict = migrated.libraries.single {
            it.source == GameSource.STEAM && it.rootPath == canonicalLibraryPath(parentPath)
        }
        val invalid = migrated.copy(
            defaultLibraryIds = migrated.defaultLibraryIds + (GameSource.STEAM to conflict.id),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository(FakeStorage(Json.encodeToString(invalid))).getSnapshot()
            }
        }
        Unit
    }

    @Test
    fun addLibraryChecksPermissionAndGlobalNestedConflicts() = runBlocking {
        val denied = File(root, "denied").canonicalPath
        val storage = FakeStorage()
        val repository = repository(
            storage = storage,
            canAccess = { it != denied },
        )

        repository.getSnapshot()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addLibrary(GameSource.GOG, denied) }
        }

        val shared = File(root, "shared")
        val added = repository.addLibrary(GameSource.STEAM, shared.path)
        val steam = added.libraries.single { !it.builtIn }
        assertEquals(steam.id, added.defaultLibraryIds.getValue(GameSource.STEAM))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addLibrary(GameSource.EPIC, File(shared, "epic").path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addLibrary(GameSource.CUSTOM_GAME, File(root, "custom").path) }
        }
        Unit
    }

    @Test
    fun defaultSelectionRechecksAccessAndRemovalFallsBackToBuiltIn() = runBlocking {
        var accessAllowed = true
        val repository = repository(
            storage = FakeStorage(),
            canAccess = { accessAllowed },
        )
        val added = repository.addLibrary(GameSource.AMAZON, File(root, "amazon-custom").path)
        val custom = added.libraries.single { !it.builtIn }
        accessAllowed = false

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setDefaultLibrary(GameSource.AMAZON, custom.id) }
        }
        val removed = repository.removeLibrary(custom.id)
        val builtIn = removed.libraries.single { it.source == GameSource.AMAZON && it.builtIn }
        assertEquals(builtIn.id, removed.defaultLibraryIds.getValue(GameSource.AMAZON))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.removeLibrary(builtIn.id) }
        }
        Unit
    }

    @Test
    fun installationResolutionRechecksPermissionAndUsesSelectedStoreLayout() = runBlocking {
        var accessAllowed = true
        val repository = repository(FakeStorage(), canAccess = { accessAllowed })
        val snapshot = repository.addLibrary(GameSource.GOG, File(root, "gog-custom").path)
        val custom = snapshot.libraries.single { !it.builtIn }

        val resolved = repository.resolveInstallation(GameSource.GOG, custom.id)
        assertPathEndsWith(resolved.installRoot, "games/common")
        assertPathEndsWith(resolved.stagingRoot, "games/staging")

        accessAllowed = false
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.resolveInstallation(GameSource.GOG, custom.id) }
        }
        Unit
    }

    @Test
    fun concurrentAddsAreSerializedWithoutLosingEitherLibrary() = runBlocking {
        var nextId = 0
        val repository = repository(FakeStorage(), createId = { "id-${nextId++}" })

        val first = async(Dispatchers.Default) {
            repository.addLibrary(GameSource.STEAM, File(root, "concurrent-steam").path)
        }
        val second = async(Dispatchers.Default) {
            repository.addLibrary(GameSource.GOG, File(root, "concurrent-gog").path)
        }
        first.await()
        second.await()

        assertEquals(6, repository.getSnapshot().libraries.size)
    }

    @Test
    fun unsupportedOrMalformedSnapshotsFailFast() {
        val builtIns = MANAGED_GAME_SOURCES.map { source ->
            GameLibrary(
                id = "builtin-${source.name.lowercase()}",
                source = source,
                rootPath = canonicalLibraryPath(builtInRoots.getValue(source)),
                builtIn = true,
            )
        }
        val unsupported = GameLibrarySnapshot(
            version = 2,
            libraries = builtIns,
            defaultLibraryIds = builtIns.associate { it.source to it.id },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository(FakeStorage(Json.encodeToString(unsupported))).getSnapshot() }
        }
        assertThrows(Exception::class.java) {
            runBlocking { repository(FakeStorage("not-json")).getSnapshot() }
        }
    }

    private fun repository(
        storage: GameLibrarySnapshotStorage,
        canAccess: (String) -> Boolean = { true },
        createId: () -> String = { "custom-id" },
    ) = GameLibraryRepositoryImpl.forTest(
        builtInRoots = builtInRoots,
        pathAccessPolicy = object : PathAccessPolicy {
            override fun requireAccessibleDirectory(rootPath: String) {
                require(canAccess(rootPath)) { "Test path access denied: $rootPath" }
            }
        },
        storage = storage,
        createId = createId,
    )

    private fun assertPathEndsWith(actual: String, expected: String) {
        assertTrue(actual.replace('\\', '/').endsWith(expected))
    }

    private class FakeStorage(
        private var snapshotJson: String? = null,
        private val legacy: LegacySteamLibraryPreferences = LegacySteamLibraryPreferences(emptySet(), ""),
    ) : GameLibrarySnapshotStorage {
        private val mutex = Mutex()

        override suspend fun update(
            transform: (snapshotJson: String?, legacy: LegacySteamLibraryPreferences) -> String,
        ): String = mutex.withLock {
            snapshotJson = transform(snapshotJson, legacy)
            checkNotNull(snapshotJson)
        }
    }
}
