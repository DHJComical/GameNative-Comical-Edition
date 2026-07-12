package app.gamenative.data.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeNoException
import org.junit.Test

class LibraryFileTransactionTest {
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = Files.createTempDirectory("library-file-transaction").toFile()
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun migrateVerifiesCommitsThenDeletesSource() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        val progress = mutableListOf<LibraryFileProgress>()

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol, progress::add)

        assertTrue(result.isSuccess)
        assertFalse(source.exists())
        assertEquals("binary-data", File(target, "bin/game.exe").readText())
        assertTrue(File(target, "empty").isDirectory)
        assertEquals(progress.last().totalBytes, progress.last().copiedBytes)
        assertTrue(protocol.transactions.isEmpty())
        assertNoJournals(library)
    }

    @Test
    fun commitFailureRollsBackOwnedTargetAndKeepsSource() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol(failCommit = true)

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertFalse(target.exists())
        assertNoJournals(library)
    }

    @Test
    fun hardCrashAfterCommitReturnRecoversCommittedTargetAndDeletesOnlySource() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        val crashing = LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMIT_RETURNED))

        val result = crashing.migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertEquals("binary-data", File(target, "bin/game.exe").readText())
        assertEquals(LibraryFileCommitState.COMMITTED, protocol.onlyResolution().commitState)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertFalse(source.exists())
        assertEquals("binary-data", File(target, "bin/game.exe").readText())
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun hardCrashBeforeCommitRecoversByRemovingOnlyOwnedTarget() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        val crashing = LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMITTING))

        val result = crashing.migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertTrue(target.exists())
        assertEquals(LibraryFileCommitState.NOT_COMMITTED, protocol.onlyResolution().commitState)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertFalse(target.exists())
        assertSourceComplete(source)
    }

    @Test
    fun crashAfterMoveBeforeTrustedFinalizationPreservesTarget() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()

        val result = LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.MOVED))
            .migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertFalse(protocol.onlyResolution().targetWasFinalized)
        assertSourceComplete(source)
        assertTrue(target.isDirectory)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(0, recovery.removedTransactions)
        assertEquals(1, recovery.pendingTransactionIds.size)
        assertSourceComplete(source)
        assertTrue(target.isDirectory)
    }

    @Test
    fun crashAfterTrustedFinalizationRollsBackOwnedTarget() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()

        val result = LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.TARGET_FINALIZED))
            .migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertTrue(protocol.onlyResolution().targetWasFinalized)
        assertSourceComplete(source)
        assertTrue(target.isDirectory)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertSourceComplete(source)
        assertFalse(target.exists())
    }

    @Test
    fun crashAfterCompletingFailedInitialJournalKeepsTemporaryRecoveryCarrier() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val protocol = InMemoryProtocol()
        var failInitialPublication = true
        val injector = LibraryFileFaultInjector { point ->
            when {
                point == LibraryFileTransactionPoint.JOURNAL_TEMP_DURABLE && failInitialPublication -> {
                    failInitialPublication = false
                    throw IOException("journal publication failed")
                }
                point == LibraryFileTransactionPoint.COMPLETED -> throw SimulatedProcessDeath(point)
            }
        }

        val result = LibraryFileTransactionImpl(injector).migrate(
            source,
            File(library, "games/game"),
            library,
            protocol,
        )

        assertTrue(result.isFailure)
        assertTrue(protocol.onlyResolution().cleanupComplete)
        assertEquals(1, File(library, ".gamenative-migrations")
            .listFiles { file -> file.name.endsWith(".properties.tmp") }.orEmpty().size)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertTrue(protocol.transactions.isEmpty())
        assertSourceComplete(source)
    }

    @Test
    fun crashAtEachFilesystemBoundaryRecoversAccordingToCommitState() = runBlocking {
        listOf(
            LibraryFileTransactionPoint.COPY_VERIFIED to false,
            LibraryFileTransactionPoint.FINALIZED to false,
            LibraryFileTransactionPoint.COMMITTED to true,
        ).forEach { (point, committed) ->
            val source = createSource("$point/source")
            val library = File(testRoot, "$point/library")
            val target = File(library, "games/game")
            val protocol = InMemoryProtocol()

            val result = LibraryFileTransactionImpl(crashingAt(point)).migrate(source, target, library, protocol)
            assertTrue(result.isFailure)

            val recovery = LibraryFileTransactionImpl().recover(library, protocol)

            assertEquals(1, recovery.removedTransactions)
            assertTrue(recovery.pendingTransactionIds.isEmpty())
            if (committed) {
                assertFalse(source.exists())
                assertTrue(target.isDirectory)
            } else {
                assertSourceComplete(source)
                assertFalse(target.exists())
            }
        }
    }

    @Test
    fun completeIsSafelyReplayedAfterCrashBeforeJournalDeletion() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()

        val result = LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMPLETED))
            .migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertFalse(source.exists())
        assertEquals(1, protocol.completeCalls)

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertEquals(2, protocol.completeCalls)
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun committedRecoveryPreservesSourceWhenTargetContentIsCorrupt() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMIT_RETURNED))
            .migrate(source, target, library, protocol)
        File(target, "bin/game.exe").writeText("tampered-data")

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(0, recovery.removedTransactions)
        assertEquals(1, recovery.pendingTransactionIds.size)
        assertSourceComplete(source)
        assertNotNull(protocol.onlyResolution())
    }

    @Test
    fun sourceMutationDuringCopyFailsHashVerificationAndPreservesSource() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        var changed = false

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol) {
            if (!changed) {
                File(source, "config.ini").writeText("changed=true")
                changed = true
            }
        }

        assertTrue(result.isFailure)
        assertTrue(source.isDirectory)
        assertEquals("changed=true", File(source, "config.ini").readText())
        assertFalse(target.exists())
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun forgedJournalCannotDeletePathsOutsideTrustedLibrary() = runBlocking {
        val source = createSource("protected/source")
        val protectedTarget = createSource("protected/target")
        val library = File(testRoot, "library").apply { mkdirs() }
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb14"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/safe", testNonce(1)))
        }
        val journalRoot = File(library, ".gamenative-migrations").apply { mkdirs() }
        val journal = File(journalRoot, "$id.properties")
        Properties().apply {
            setProperty("id", id)
            setProperty("target", protectedTarget.canonicalPath)
            setProperty("state", "READY")
            setProperty("digest", "forged")
        }.also { properties -> journal.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(0, recovery.removedTransactions)
        assertEquals(listOf(id), recovery.pendingTransactionIds)
        assertSourceComplete(source)
        assertSourceComplete(protectedTarget)
        assertTrue(journal.exists())
    }

    @Test
    fun forgedJournalWithCorrectHashCannotDeleteTrustedTargetWithoutOwnership() = runBlocking {
        val donorSource = createSource("donor/source")
        val donorLibrary = File(testRoot, "donor/library")
        val donorProtocol = InMemoryProtocol()
        LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMITTING)).migrate(
            donorSource,
            File(donorLibrary, "games/donor"),
            donorLibrary,
            donorProtocol,
        )
        val digest = Properties().apply {
            File(donorLibrary, ".gamenative-migrations").listFiles { file -> file.extension == "properties" }
                .orEmpty().single().inputStream().use(::load)
        }.getProperty("digest")

        val source = createSource("victim/source")
        val library = File(testRoot, "victim/library").apply { mkdirs() }
        val target = createSource("victim/library/games/protected")
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb15"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/protected", testNonce(2)))
        }
        val journal = File(library, ".gamenative-migrations/$id.properties").apply {
            requireNotNull(parentFile).mkdirs()
        }
        Properties().apply {
            setProperty("id", id)
            setProperty("target", "games/protected")
            setProperty("state", "READY")
            setProperty("digest", digest)
        }.also { properties -> journal.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(0, recovery.removedTransactions)
        assertEquals(listOf(id), recovery.pendingTransactionIds)
        assertSourceComplete(source)
        assertSourceComplete(target)
        assertTrue(journal.exists())
    }

    @Test
    fun forgetFailureIsRetriedFromDurableCleanupMarker() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val protocol = InMemoryProtocol(failForget = true)

        val result = LibraryFileTransactionImpl().migrate(source, File(library, "games/game"), library, protocol)

        assertTrue(result.isFailure)
        assertTrue(File(library, ".gamenative-migrations").listFiles { file -> file.extension == "cleanup" }
            .orEmpty().isNotEmpty())
        protocol.failForget = false

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun temporaryCleanupMarkerRetriesCompletedMetadataCleanup() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library").apply { mkdirs() }
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb17"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/game", testNonce(4)))
            complete(id)
        }
        val marker = File(library, ".gamenative-migrations/$id.cleanup.tmp").apply {
            requireNotNull(parentFile).mkdirs()
        }
        Properties().apply {
            setProperty("id", id)
            setProperty("nonce", testNonce(4))
        }.also { properties -> marker.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertFalse(marker.exists())
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun nonNormalizedTrustedTargetIsRejectedWithoutFilesystemMutation() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library").apply { mkdirs() }
        val target = createSource("library/games/safe")
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb16"
        val relative = "games/../games/safe"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, relative, testNonce(3)))
        }
        val journal = File(library, ".gamenative-migrations/$id.properties").apply {
            requireNotNull(parentFile).mkdirs()
        }
        Properties().apply {
            setProperty("id", id)
            setProperty("target", relative)
            setProperty("state", "COPYING")
        }.also { properties -> journal.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(listOf(id), recovery.pendingTransactionIds)
        assertSourceComplete(source)
        assertSourceComplete(target)
    }

    @Test
    fun durableTemporaryJournalIsRecoveredWhenAtomicPublicationWasInterrupted() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library").apply { mkdirs() }
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb14"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/safe", testNonce(1)))
        }
        val journalRoot = File(library, ".gamenative-migrations").apply { mkdirs() }
        val temporary = File(journalRoot, "$id.properties.tmp")
        Properties().apply {
            setProperty("id", id)
            setProperty("target", "games/safe")
            setProperty("state", "COPYING")
        }.also { properties -> temporary.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertTrue(recovery.pendingTransactionIds.isEmpty())
        assertFalse(temporary.exists())
        assertSourceComplete(source)
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun concurrentMigrationsToSameTargetCannotDeleteSuccessfulTarget() = runBlocking {
        val firstSource = createSource("first/game")
        val secondSource = createSource("second/game")
        File(secondSource, "bin/game.exe").writeText("second-binary")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()

        val results = listOf(firstSource, secondSource).map { source ->
            async { LibraryFileTransactionImpl().migrate(source, target, library, protocol) }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertTrue(target.isDirectory)
        assertTrue(File(target, "bin/game.exe").readText() in setOf("binary-data", "second-binary"))
        assertEquals(1, listOf(firstSource, secondSource).count { it.exists() })
    }

    @Test
    fun recoveryDoesNotTouchMigrationHoldingTargetLock() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol()
        val copyReached = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        var paused = false
        val migration = async(Dispatchers.IO) {
            LibraryFileTransactionImpl().migrate(source, target, library, protocol) {
                if (!paused) {
                    paused = true
                    copyReached.countDown()
                    releaseCopy.await()
                }
            }
        }
        copyReached.await()

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(0, recovery.removedTransactions)
        assertEquals(1, recovery.pendingTransactionIds.size)
        assertSourceComplete(source)
        releaseCopy.countDown()
        assertTrue(migration.await().isSuccess)
        assertTrue(target.isDirectory)
    }

    @Test
    fun symbolicLinkEntryIsRejectedWithoutTouchingItsDestination() = runBlocking {
        val source = createSource("source/game")
        val outside = File(testRoot, "outside.txt").apply { writeText("protected") }
        try {
            Files.createSymbolicLink(File(source, "linked.txt").toPath(), outside.toPath())
        } catch (exception: Exception) {
            assumeNoException("Symbolic links are unavailable on this filesystem", exception)
        }
        val library = File(testRoot, "library")

        val result = LibraryFileTransactionImpl().migrate(
            source,
            File(library, "games/game"),
            library,
            InMemoryProtocol(),
        )

        assertTrue(result.isFailure)
        assertEquals("protected", outside.readText())
        assertTrue(source.isDirectory)
    }

    @Test
    fun targetOutsideLibraryIsRejectedBeforePrepare() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(testRoot, "outside/game")
        val protocol = InMemoryProtocol()

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertFalse(target.exists())
        assertTrue(protocol.transactions.isEmpty())
    }

    @Test
    fun committedDescriptorMismatchPreservesSourceAndJournal() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol(rewriteDescriptorAfterCommit = true)

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertTrue(target.isDirectory)
        assertEquals(1, File(library, ".gamenative-migrations")
            .listFiles { file -> file.extension == "properties" }.orEmpty().size)
    }

    @Test
    fun committedNonceMismatchPreservesSourceAndJournal() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library")
        val target = File(library, "games/game")
        val protocol = InMemoryProtocol(rewriteNonceAfterCommit = true)

        val result = LibraryFileTransactionImpl().migrate(source, target, library, protocol)

        assertTrue(result.isFailure)
        assertSourceComplete(source)
        assertTrue(target.isDirectory)
        assertEquals(1, File(library, ".gamenative-migrations")
            .listFiles { file -> file.extension == "properties" }.orEmpty().size)
    }

    @Test
    fun cleanupMarkerCannotForgetWhileJournalStillExists() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library").apply { mkdirs() }
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb18"
        val nonce = testNonce(5)
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/game", nonce))
            complete(id)
        }
        val root = File(library, ".gamenative-migrations").apply { mkdirs() }
        val journal = File(root, "$id.properties").apply { writeText("corrupt but still authoritative") }
        val marker = File(root, "$id.cleanup.tmp")
        Properties().apply {
            setProperty("id", id)
            setProperty("nonce", nonce)
        }.also { properties -> marker.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(listOf(id), recovery.pendingTransactionIds)
        assertTrue(protocol.transactions.containsKey(id))
        assertTrue(journal.exists())
        assertTrue(marker.exists())
    }

    @Test
    fun forgedCleanupMarkerCannotForgetKnownCompletedTransaction() = runBlocking {
        val source = createSource("source/game")
        val library = File(testRoot, "library").apply { mkdirs() }
        val id = "b067d710-61d4-4bd9-96ff-0c7c1b44fb19"
        val protocol = InMemoryProtocol().apply {
            prepare(LibraryFileTransactionDescriptor(id, source.canonicalFile, "games/game", testNonce(6)))
            complete(id)
        }
        val marker = File(library, ".gamenative-migrations/$id.cleanup").apply {
            requireNotNull(parentFile).mkdirs()
        }
        Properties().apply {
            setProperty("id", id)
            setProperty("nonce", testNonce(7))
        }.also { properties -> marker.outputStream().use { properties.store(it, null) } }

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(listOf(id), recovery.pendingTransactionIds)
        assertTrue(protocol.transactions.containsKey(id))
        assertTrue(marker.exists())
    }

    @Test
    fun transactionDirectoryCannotBeUsedAsSourceOrTarget() = runBlocking {
        val library = File(testRoot, "library").apply { mkdirs() }
        val transactionRoot = File(library, ".gamenative-migrations").apply { mkdirs() }
        val sourceInTransactions = createSource("library/.gamenative-migrations/source")
        val ordinarySource = createSource("ordinary/source")

        val sourceResult = LibraryFileTransactionImpl().migrate(
            sourceInTransactions,
            File(library, "games/game"),
            library,
            InMemoryProtocol(),
        )
        val targetResult = LibraryFileTransactionImpl().migrate(
            ordinarySource,
            File(transactionRoot, "target"),
            library,
            InMemoryProtocol(),
        )

        assertTrue(sourceResult.isFailure)
        assertTrue(targetResult.isFailure)
        assertSourceComplete(sourceInTransactions)
        assertSourceComplete(ordinarySource)
    }

    @Test
    fun resolveFailureLeavesJournalPendingAndContinuesRecovery() = runBlocking {
        val library = File(testRoot, "library")
        val protocol = InMemoryProtocol()
        val firstSource = createSource("first/game")
        val secondSource = createSource("second/game")
        LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMITTING))
            .migrate(firstSource, File(library, "games/first"), library, protocol)
        LibraryFileTransactionImpl(crashingAt(LibraryFileTransactionPoint.COMMITTING))
            .migrate(secondSource, File(library, "games/second"), library, protocol)
        protocol.failResolveId = protocol.transactions.keys.first()

        val recovery = LibraryFileTransactionImpl().recover(library, protocol)

        assertEquals(1, recovery.removedTransactions)
        assertEquals(listOf(protocol.failResolveId), recovery.pendingTransactionIds)
    }

    private fun createSource(path: String): File = File(testRoot, path).apply {
        check(File(this, "bin").mkdirs())
        File(this, "bin/game.exe").writeText("binary-data")
        File(this, "config.ini").writeText("enabled=true")
        check(File(this, "empty").mkdirs())
    }

    private fun assertSourceComplete(source: File) {
        assertTrue(File(source, "bin/game.exe").readText() in setOf("binary-data", "second-binary"))
        assertEquals("enabled=true", File(source, "config.ini").readText())
        assertTrue(File(source, "empty").isDirectory)
    }

    private fun assertNoJournals(library: File) {
        val journals = File(library, ".gamenative-migrations")
            .listFiles { file -> file.extension == "properties" }.orEmpty()
        assertTrue(journals.isEmpty())
    }

    private fun crashingAt(expected: LibraryFileTransactionPoint) = LibraryFileFaultInjector { actual ->
        if (actual == expected) throw SimulatedProcessDeath(expected)
    }

    private class SimulatedProcessDeath(point: LibraryFileTransactionPoint) : Error("Crash at $point")

    private fun testNonce(value: Int): String = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private class InMemoryProtocol(
        private val failCommit: Boolean = false,
        private val rewriteDescriptorAfterCommit: Boolean = false,
        private val rewriteNonceAfterCommit: Boolean = false,
        var failForget: Boolean = false,
    ) : LibraryFileCommitProtocol {
        val transactions = ConcurrentHashMap<String, LibraryFileTransactionResolution>()
        private val completed = ConcurrentHashMap.newKeySet<String>()
        var failResolveId: String? = null
        var completeCalls = 0

        override suspend fun prepare(transaction: LibraryFileTransactionDescriptor) {
            check(transactions.putIfAbsent(
                transaction.transactionId,
                LibraryFileTransactionResolution(transaction, LibraryFileCommitState.NOT_COMMITTED),
            ) == null)
        }

        override suspend fun markTargetFinalized(transactionId: String) {
            transactions.compute(transactionId) { _, resolution ->
                requireNotNull(resolution).copy(targetWasFinalized = true)
            }
        }

        override suspend fun commit(transactionId: String) {
            if (failCommit) throw IllegalStateException("database commit failed")
            transactions.compute(transactionId) { _, resolution ->
                val current = requireNotNull(resolution)
                current.copy(
                    descriptor = if (rewriteDescriptorAfterCommit) {
                        current.descriptor.copy(targetRelativePath = "games/other")
                    } else if (rewriteNonceAfterCommit) {
                        current.descriptor.copy(ownershipNonce = "ffffffff-ffff-ffff-ffff-ffffffffffff")
                    } else {
                        current.descriptor
                    },
                    commitState = LibraryFileCommitState.COMMITTED,
                )
            }
        }

        override suspend fun resolve(transactionId: String): LibraryFileTransactionResolution? {
            if (transactionId == failResolveId) throw IllegalStateException("database unavailable")
            return transactions[transactionId]
        }

        override suspend fun complete(transactionId: String) {
            check(transactions.containsKey(transactionId))
            completeCalls++
            completed += transactionId
            transactions.compute(transactionId) { _, resolution ->
                requireNotNull(resolution).copy(cleanupComplete = true)
            }
        }

        override suspend fun forget(transactionId: String) {
            if (failForget) throw IllegalStateException("metadata cleanup failed")
            if (!transactions.containsKey(transactionId)) return
            check(transactions.getValue(transactionId).cleanupComplete)
            check(completed.remove(transactionId))
            transactions.remove(transactionId)
        }

        fun onlyResolution(): LibraryFileTransactionResolution = transactions.values.single()
    }
}
