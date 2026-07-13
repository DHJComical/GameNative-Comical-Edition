package app.gamenative.data.library

import app.gamenative.data.GameSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameLibraryOperationsTest {
    private lateinit var root: File
    private lateinit var sourceLibrary: GameLibrary
    private lateinit var targetLibrary: GameLibrary
    private lateinit var repository: FakeRepository
    private lateinit var store: FakeStore
    private lateinit var operations: GameLibraryOperations

    @Before
    fun setUp() {
        root = Files.createTempDirectory("game-library-operations").toFile()
        sourceLibrary = GameLibrary("source", GameSource.GOG, File(root, "source").path, false)
        targetLibrary = GameLibrary("target", GameSource.GOG, File(root, "target").path, false)
        repository = FakeRepository(listOf(sourceLibrary, targetLibrary))
        store = FakeStore()
        operations = GameLibraryOperationsImpl(repository, store, LibraryFileTransactionImpl())
        runBlocking { operations.recoverMigrations() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun migrateCommitsTargetAndDeletesSource() = runBlocking {
        val source = File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "game").apply {
            mkdirs()
            resolve("game.exe").writeText("binary")
        }
        val entry = entry(source)
        store.entries += entry

        val result = operations.migrateEntry(entry, targetLibrary.id)

        assertTrue(result.isSuccess)
        assertFalse(source.exists())
        assertEquals(
            File(GogLibraryLayout.installRoot(targetLibrary.rootPath), "game").canonicalPath,
            store.committedTarget?.installPath,
        )
        assertEquals(1, store.notifications)
    }

    @Test
    fun activeOperationRejectsMigrationBeforeFilesystemMutation() = runBlocking {
        val source = File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "game").apply { mkdirs() }
        val entry = entry(source)
        store.blockingReason = "active"

        val result = operations.migrateEntry(entry, targetLibrary.id)

        assertTrue(result.isFailure)
        assertTrue(source.exists())
        assertEquals(null, store.committedTarget)
    }

    @Test
    fun failedDeletionKeepsLibraryRegisteredAndReturnsFailures() = runBlocking {
        val first = entry(File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "one").apply { mkdirs() }, "one")
        val second = entry(File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "two").apply { mkdirs() }, "two")
        store.entries += listOf(first, second)
        store.failedDeletionKey = second.gameKey

        val result = operations.removeLibraryAndGames(sourceLibrary.id)

        assertFalse(result.removed)
        assertEquals(listOf(second), result.failedEntries)
        assertEquals(0, repository.removeCalls)
    }

    @Test
    fun everyStoreAndUnifiedRuntimeBlocksLibraryMutation() {
        val activeStates = listOf(
            LibraryOperationActivity(runtimeActive = true),
            LibraryOperationActivity(registryActive = true),
            LibraryOperationActivity(steamActive = true),
            LibraryOperationActivity(gogActive = true),
            LibraryOperationActivity(epicActive = true),
            LibraryOperationActivity(amazonActive = true),
        )

        assertTrue(activeStates.all { it.blockingReason() != null })
        assertEquals(null, LibraryOperationActivity().blockingReason())
    }

    @Test
    fun migrationSpacePreflightRejectsOneByteShort() {
        assertFalse(hasEnoughLibrarySpace(requiredBytes = 101L, usableBytes = 100L))
        assertTrue(hasEnoughLibrarySpace(requiredBytes = 100L, usableBytes = 100L))
    }

    @Test
    fun sameSourceMigrationsRemainSerializedAcrossTargets() = runBlocking {
        val third = GameLibrary("third", GameSource.GOG, File(root, "third").path, false)
        repository.addExisting(third)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val transaction = object : LibraryFileTransaction {
            override suspend fun migrate(
                sourceDirectory: File,
                targetDirectory: File,
                targetRoot: File,
                protocol: LibraryFileCommitProtocol,
                onProgress: (LibraryFileProgress) -> Unit,
            ): Result<Unit> {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                delay(50)
                active.decrementAndGet()
                return Result.success(Unit)
            }
            override suspend fun recover(targetRoot: File, protocol: LibraryFileCommitProtocol) =
                LibraryFileRecovery(0, emptyList())
        }
        val serialized = GameLibraryOperationsImpl(repository, store, transaction)
        serialized.recoverMigrations()
        val first = entry(File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "one").apply {
            mkdirs()
            resolve("file").writeText("1")
        }, "one")
        val second = entry(File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "two").apply {
            mkdirs()
            resolve("file").writeText("2")
        }, "two")

        listOf(
            async { serialized.migrateEntry(first, targetLibrary.id) },
            async { serialized.migrateEntry(second, third.id) },
        ).awaitAll()

        assertEquals(1, maximum.get())
    }

    @Test
    fun pendingPostCommitCleanupPreventsSecondRemoveFromBypassingRecord() = runBlocking {
        store.pendingDeletionLibraryId = sourceLibrary.id

        try {
            operations.removeLibraryAndGames(sourceLibrary.id)
            throw AssertionError("Pending cleanup must reject library removal")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, repository.removeCalls)
        }
    }

    @Test
    fun matchingLibraryMetadataOutsideDirectRootsIsRejected() = runBlocking {
        val outside = File(root, "outside/game").apply { mkdirs() }
        store.entries += entry(outside).copy(libraryId = sourceLibrary.id)

        try {
            operations.getEntries(sourceLibrary.id)
            throw AssertionError("Library metadata outside its roots must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected at the read boundary before the entry can reach migration or deletion.
        }
    }

    @Test
    fun taskLibraryIdCannotDisagreeWithOwningPath() = runBlocking {
        val owned = File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "game").apply { mkdirs() }
        store.entries += entry(owned).copy(libraryId = targetLibrary.id, kind = GameLibraryEntryKind.PARTIAL)

        try {
            operations.getEntries(sourceLibrary.id)
            throw AssertionError("Task library id must agree with its canonical owning root")
        } catch (_: IllegalArgumentException) {
            // Expected: OR-based ownership is forbidden.
        }
    }

    @Test
    fun repeatedRecoveryAndMigrationCannotOverlap() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val transaction = object : LibraryFileTransaction {
            private suspend fun tracked() {
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                delay(30)
                active.decrementAndGet()
            }
            override suspend fun migrate(
                sourceDirectory: File,
                targetDirectory: File,
                targetRoot: File,
                protocol: LibraryFileCommitProtocol,
                onProgress: (LibraryFileProgress) -> Unit,
            ): Result<Unit> {
                tracked()
                return Result.success(Unit)
            }
            override suspend fun recover(targetRoot: File, protocol: LibraryFileCommitProtocol): LibraryFileRecovery {
                tracked()
                return LibraryFileRecovery(0, emptyList())
            }
        }
        val serialized = GameLibraryOperationsImpl(repository, store, transaction)
        serialized.recoverMigrations()
        maximum.set(0)
        val source = File(GogLibraryLayout.installRoot(sourceLibrary.rootPath), "recover-race").apply {
            mkdirs()
            resolve("file").writeText("data")
        }
        val entry = entry(source, "recover-race")

        val recovery = async { serialized.recoverMigrations() }
        delay(5)
        val migration = async { serialized.migrateEntry(entry, targetLibrary.id) }
        recovery.await()
        migration.await()

        assertEquals(1, maximum.get())
    }

    private fun entry(path: File, key: String = "game") = GameLibraryEntry(
        GameSource.GOG,
        key,
        key.hashCode(),
        key,
        sourceLibrary.id,
        path.path,
        GameLibraryEntryKind.INSTALLED,
        6L,
    )

    private class FakeRepository(libraries: List<GameLibrary>) : GameLibraryRepository {
        private var snapshot = GameLibrarySnapshot(
            libraries = libraries,
            defaultLibraryIds = mapOf(GameSource.GOG to libraries.first().id),
        )
        var removeCalls = 0

        override suspend fun getSnapshot() = snapshot
        fun addExisting(library: GameLibrary) {
            snapshot = snapshot.copy(libraries = snapshot.libraries + library)
        }
        override suspend fun addLibrary(source: GameSource, rootPath: String) = error("Not used")
        override suspend fun setDefaultLibrary(source: GameSource, libraryId: String) = error("Not used")
        override suspend fun removeLibrary(libraryId: String): GameLibrarySnapshot {
            removeCalls++
            snapshot = snapshot.copy(libraries = snapshot.libraries.filterNot { it.id == libraryId })
            return snapshot
        }
        override suspend fun resolveInstallation(source: GameSource, libraryId: String): GameLibraryInstallation {
            val library = snapshot.libraries.single { it.id == libraryId && it.source == source }
            val layout = storeLibraryLayout(source)
            return GameLibraryInstallation(library, layout.installRoot(library.rootPath), layout.stagingRoot(library.rootPath))
        }
    }

    private class FakeStore : GameLibraryEntryStore {
        val entries = mutableListOf<GameLibraryEntry>()
        var blockingReason: String? = null
        var committedTarget: GameLibraryEntryLocation? = null
        var failedDeletionKey: String? = null
        var notifications = 0
        var pendingDeletionLibraryId: String? = null
        private val transactions = mutableMapOf<String, LibraryFileTransactionResolution>()

        override suspend fun getEntries(source: GameSource) = entries.filter { it.source == source }
        override fun getGlobalBlockingReason() = blockingReason
        override fun createCommitProtocol(entry: GameLibraryEntry, target: GameLibraryEntryLocation) = protocol(target)
        override fun createRecoveryProtocol(libraryRoot: File) = protocol(null)
        override suspend fun deleteEntry(entry: GameLibraryEntry): Result<Unit> =
            if (entry.gameKey == failedDeletionKey) Result.failure(IllegalStateException("failed")) else Result.success(Unit)
        override suspend fun recoverDeletions() = Unit
        override suspend fun hasPendingDeletions(libraryId: String) = pendingDeletionLibraryId == libraryId
        override fun notifyChanged(entry: GameLibraryEntry) { notifications++ }

        private fun protocol(target: GameLibraryEntryLocation?) = object : LibraryFileCommitProtocol {
            override suspend fun prepare(transaction: LibraryFileTransactionDescriptor) {
                transactions[transaction.transactionId] = LibraryFileTransactionResolution(
                    transaction,
                    LibraryFileCommitState.NOT_COMMITTED,
                )
            }
            override suspend fun markTargetFinalized(transactionId: String) {
                transactions.compute(transactionId) { _, value -> value?.copy(targetWasFinalized = true) }
            }
            override suspend fun commit(transactionId: String) {
                committedTarget = requireNotNull(target)
                transactions.compute(transactionId) { _, value -> value?.copy(commitState = LibraryFileCommitState.COMMITTED) }
            }
            override suspend fun resolve(transactionId: String) = transactions[transactionId]
            override suspend fun complete(transactionId: String) {
                transactions.compute(transactionId) { _, value -> value?.copy(cleanupComplete = true) }
            }
            override suspend fun forget(transactionId: String) { transactions.remove(transactionId) }
        }
    }
}
