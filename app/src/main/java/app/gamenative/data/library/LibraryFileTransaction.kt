package app.gamenative.data.library

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Performs recoverable game-directory migrations coordinated with durable installation metadata. */
interface LibraryFileTransaction {
    /**
     * Migrates [sourceDirectory] to [targetDirectory] below [targetRoot]. The caller must prevent
     * external writes to the source for the operation's duration. The protocol must make prepare
     * and commit durable and commit idempotent before returning.
     */
    suspend fun migrate(
        sourceDirectory: File,
        targetDirectory: File,
        targetRoot: File,
        protocol: LibraryFileCommitProtocol,
        onProgress: (LibraryFileProgress) -> Unit = {},
    ): Result<Unit>

    /** Recovers durable transactions below [targetRoot] using only protocol-owned metadata. */
    suspend fun recover(targetRoot: File, protocol: LibraryFileCommitProtocol): LibraryFileRecovery
}

/** Durable metadata boundary used to reconcile filesystem state after process death. */
interface LibraryFileCommitProtocol {
    /** Durably records the canonical source and target-relative path before filesystem mutation. */
    suspend fun prepare(transaction: LibraryFileTransactionDescriptor)

    /** Durably authorizes the prepared nonce to identify a finalized target before its atomic move. */
    suspend fun markTargetFinalized(transactionId: String)

    /** Atomically and idempotently changes installation metadata to the prepared target. */
    suspend fun commit(transactionId: String)

    /** Returns the durable transaction record and its authoritative commit state. */
    suspend fun resolve(transactionId: String): LibraryFileTransactionResolution?

    /**
     * Atomically and idempotently marks filesystem cleanup complete while keeping [resolve]
     * available for recovery. Recovery may repeat this call after an ambiguous process death.
     */
    suspend fun complete(transactionId: String)

    /**
     * Idempotently forgets a completed transaction after its journal is durably removed. The
     * implementation must atomically reject transactions whose cleanup has not completed.
     */
    suspend fun forget(transactionId: String)
}

/** Trusted transaction metadata persisted by the installation database. */
data class LibraryFileTransactionDescriptor(
    /** Globally unique identifier shared by metadata and the filesystem journal. */
    val transactionId: String,
    /** Canonical source directory retained until the commit is known to have succeeded. */
    val sourceDirectory: File,
    /** Target path relative to the library root, never an absolute path. */
    val targetRelativePath: String,
    /** Unpredictable ownership token persisted outside the externally writable journal. */
    val ownershipNonce: String,
)

/** Authoritative metadata state returned during recovery. */
data class LibraryFileTransactionResolution(
    /** Trusted descriptor loaded from application-owned metadata. */
    val descriptor: LibraryFileTransactionDescriptor,
    /** Whether installation metadata atomically points to the target. */
    val commitState: LibraryFileCommitState,
    /** Whether trusted metadata authorized the nonce-bearing copy to become the final target. */
    val targetWasFinalized: Boolean = false,
    /** Whether filesystem cleanup was durably completed and may be forgotten. */
    val cleanupComplete: Boolean = false,
)

/** Durable commit states; unknown state must preserve both copies for manual retry. */
enum class LibraryFileCommitState { NOT_COMMITTED, COMMITTED, UNKNOWN }

/** Byte progress emitted while a verified migration copy is in progress. */
data class LibraryFileProgress(
    /** Relative file currently copied. */
    val relativePath: String,
    /** Number of verified bytes copied so far. */
    val copiedBytes: Long,
    /** Total bytes observed in the immutable source snapshot. */
    val totalBytes: Long,
)

/** Summary of recovery work and transactions deliberately retained for retry. */
data class LibraryFileRecovery(
    /** Number of journals fully reconciled and removed. */
    val removedTransactions: Int,
    /** Transaction IDs whose metadata state or cleanup could not be resolved safely. */
    val pendingTransactionIds: List<String>,
)

/** Filesystem implementation with durable journals, target locks, and content verification. */
class LibraryFileTransactionImpl internal constructor(
    private val faultInjector: LibraryFileFaultInjector = LibraryFileFaultInjector.NONE,
) : LibraryFileTransaction {
    override suspend fun migrate(
        sourceDirectory: File,
        targetDirectory: File,
        targetRoot: File,
        protocol: LibraryFileCommitProtocol,
        onProgress: (LibraryFileProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = targetRoot.canonicalFile
            val source = sourceDirectory.canonicalFile
            val target = targetDirectory.canonicalFile
            require(source.isDirectory) { "Migration source is not a directory: ${source.path}" }
            val relativeTarget = requireRelativeTarget(root, target)
            require(!isSameOrChild(source.toPath(), target.toPath())) { "Migration target is inside the source" }
            require(!isSameOrChild(target.toPath(), source.toPath())) { "Migration source is inside the target" }
            require(!isSameOrChild(source.toPath(), transactionRoot(root).toPath())) {
                "Migration transaction directory is inside the source"
            }
            require(!isSameOrChild(transactionRoot(root).toPath(), source.toPath())) {
                "Migration source is inside the transaction directory"
            }
            require(!isSameOrChild(transactionRoot(root).toPath(), target.toPath())) {
                "Migration target is inside the transaction directory"
            }
            prepareRoot(root)
            require(sameFileStore(root.toPath(), transactionRoot(root).toPath())) {
                "Migration transactions and target must be on the same filesystem"
            }

            withTargetLock(root, relativeTarget) {
                require(!target.exists()) { "Migration target already exists: ${target.path}" }
                val targetParent = requireNotNull(target.parentFile) { "Migration target has no parent" }
                require(targetParent.mkdirs() || targetParent.isDirectory) {
                    "Cannot create migration target parent: ${targetParent.path}"
                }
                require(sameFileStore(targetParent.toPath(), transactionRoot(root).toPath())) {
                    "Migration transactions and target must be on the same filesystem"
                }
                val id = UUID.randomUUID().toString()
                val descriptor = LibraryFileTransactionDescriptor(
                    transactionId = id,
                    sourceDirectory = source,
                    targetRelativePath = relativeTarget,
                    ownershipNonce = UUID.randomUUID().toString(),
                )
                protocol.prepare(descriptor)
                executeMigration(root, target, descriptor, protocol, onProgress)
            }
        }
    }

    override suspend fun recover(
        targetRoot: File,
        protocol: LibraryFileCommitProtocol,
    ): LibraryFileRecovery = withContext(Dispatchers.IO) {
        val root = targetRoot.canonicalFile
        val journalRoot = transactionRoot(root)
        if (!journalRoot.isDirectory) return@withContext LibraryFileRecovery(0, emptyList())
        val removed = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        val journalIds = journalRoot.listFiles { file ->
            file.isFile && (JOURNAL_NAME.matches(file.name) || JOURNAL_TEMP_NAME.matches(file.name))
        }.orEmpty().map { file ->
            file.name.removeSuffix(TEMP_SUFFIX).removeSuffix(JOURNAL_SUFFIX)
        }.distinct()
        journalIds.forEach { id ->
            val durableJournal = File(journalRoot, "$id$JOURNAL_SUFFIX")
            val temporaryJournal = File(journalRoot, "$id$JOURNAL_SUFFIX$TEMP_SUFFIX")
            val journal = durableJournal.takeIf(File::isFile) ?: temporaryJournal
            val record = try {
                readJournal(journal, id)
            } catch (exception: Exception) {
                Timber.e(exception, "Corrupt migration journal retained: %s", journal.path)
                pending += id
                return@forEach
            }
            val resolution = try {
                protocol.resolve(id)
            } catch (exception: Exception) {
                Timber.e(exception, "Cannot resolve migration journal: %s", id)
                pending += id
                return@forEach
            }
            if (resolution == null || resolution.descriptor.transactionId != id) {
                Timber.e("Trusted metadata is missing for migration journal: %s", id)
                pending += id
                return@forEach
            }
            val descriptor = resolution.descriptor
            val trustedTarget = try {
                root.resolveNormalized(descriptor.targetRelativePath)
            } catch (exception: Exception) {
                Timber.e(exception, "Invalid trusted migration target for %s", id)
                pending += id
                return@forEach
            }
            if (record.targetRelativePath != descriptor.targetRelativePath) {
                Timber.e("Journal target disagrees with trusted metadata for %s", id)
                pending += id
                return@forEach
            }
            val recovered = try {
                val lockTarget = root.toPath().relativize(trustedTarget.toPath()).toString()
                tryTargetLock(root, lockTarget) {
                    reconcile(root, journal, record, resolution, trustedTarget, protocol)
                }
            } catch (exception: Exception) {
                Timber.e(exception, "Migration recovery failed safely; files retained where possible: %s", id)
                false
            }
            if (recovered == true) {
                deleteJournalArtifact(temporaryJournal)
                removed += id
            } else {
                pending += id
            }
        }
        val cleanupIds = journalRoot.listFiles { file ->
            file.isFile && (CLEANUP_NAME.matches(file.name) || CLEANUP_TEMP_NAME.matches(file.name))
        }.orEmpty().map {
            it.name.removeSuffix(TEMP_SUFFIX).removeSuffix(CLEANUP_SUFFIX)
        }.distinct()
        cleanupIds.forEach { id ->
            try {
                require(!hasJournalArtifacts(root, id)) {
                    "Migration journal must be removed before metadata cleanup"
                }
                val marker = readCleanupMarker(root, id)
                val resolution = requireNotNull(protocol.resolve(id)) {
                    "Trusted metadata is missing for completed migration $id"
                }
                require(resolution.descriptor.transactionId == id &&
                    resolution.descriptor.ownershipNonce == marker.ownershipNonce
                ) { "Cleanup marker disagrees with trusted metadata" }
                require(resolution.cleanupComplete) { "Migration cleanup is not durably complete" }
                protocol.forget(id)
                deleteJournalArtifact(cleanupFile(root, id))
                deleteJournalArtifact(cleanupTemporaryFile(root, id))
                pending -= id
                removed += id
            } catch (exception: Exception) {
                Timber.e(exception, "Completed migration metadata cleanup remains pending: %s", id)
                pending += id
            }
        }
        LibraryFileRecovery(removed.size, pending.toList())
    }

    private suspend fun executeMigration(
        root: File,
        target: File,
        descriptor: LibraryFileTransactionDescriptor,
        protocol: LibraryFileCommitProtocol,
        onProgress: (LibraryFileProgress) -> Unit,
    ) {
        val id = descriptor.transactionId
        val journal = journalFile(root, id)
        val copy = copyDirectory(root, id)
        var record = JournalRecord(id, descriptor.targetRelativePath, JournalState.COPYING, null)
        try {
            writeJournal(journal, record)
        } catch (exception: Exception) {
            Timber.e(exception, "Cannot create migration journal for %s", id)
            completeAndForget(root, journal, protocol, id, descriptor.ownershipNonce)
            throw exception
        }
        try {
            val digest = copyAndVerify(descriptor.sourceDirectory.toPath(), copy.toPath(), onProgress)
            faultInjector.failAt(LibraryFileTransactionPoint.COPY_VERIFIED)
            record = record.copy(state = JournalState.READY, treeDigest = digest)
            writeJournal(journal, record)
            writeOwnershipMarker(copy, descriptor.ownershipNonce)
            moveToFinal(copy.toPath(), target.toPath())
            faultInjector.failAt(LibraryFileTransactionPoint.MOVED)
            protocol.markTargetFinalized(id)
            faultInjector.failAt(LibraryFileTransactionPoint.TARGET_FINALIZED)
            verifyTree(target.toPath(), digest)
            faultInjector.failAt(LibraryFileTransactionPoint.FINALIZED)
            verifyTree(descriptor.sourceDirectory.toPath(), digest)
            record = record.copy(state = JournalState.COMMITTING)
            writeJournal(journal, record)
            faultInjector.failAt(LibraryFileTransactionPoint.COMMITTING)
            protocol.commit(id)
            faultInjector.failAt(LibraryFileTransactionPoint.COMMIT_RETURNED)
            val resolution = requireNotNull(protocol.resolve(id)) { "Commit state is unavailable for $id" }
            check(descriptorsMatch(resolution.descriptor, descriptor)) {
                "Committed migration descriptor differs from prepared transaction $id"
            }
            check(resolution.commitState == LibraryFileCommitState.COMMITTED) { "Commit was not durable for $id" }
            record = record.copy(state = JournalState.COMMITTED)
            writeJournal(journal, record)
            faultInjector.failAt(LibraryFileTransactionPoint.COMMITTED)
            cleanupCommitted(descriptor.sourceDirectory, target, digest)
            removeOwnershipMarker(target, descriptor.ownershipNonce)
            completeAndForget(root, journal, protocol, id, descriptor.ownershipNonce)
        } catch (exception: Exception) {
            Timber.e(exception, "Game library migration failed for transaction %s", id)
            reconcileAfterFailure(root, journal, record, descriptor, protocol)
            throw exception
        }
    }

    private suspend fun reconcileAfterFailure(
        root: File,
        journal: File,
        inMemoryRecord: JournalRecord,
        descriptor: LibraryFileTransactionDescriptor,
        protocol: LibraryFileCommitProtocol,
    ) {
        val record = runCatching { readJournal(journal, descriptor.transactionId) }
            .onFailure { Timber.e(it, "Cannot read failed migration journal: %s", journal.path) }
            .getOrNull() ?: return
        val resolution = runCatching { protocol.resolve(descriptor.transactionId) }
            .onFailure { Timber.e(it, "Cannot resolve failed migration: %s", descriptor.transactionId) }
            .getOrNull() ?: return
        if (!descriptorsMatch(resolution.descriptor, descriptor) ||
            record.targetRelativePath != descriptor.targetRelativePath
        ) {
            Timber.e("Failed migration descriptor cannot be reconciled safely: %s", descriptor.transactionId)
            return
        }
        val target = root.resolveNormalized(descriptor.targetRelativePath)
        try {
            if (!reconcile(root, journal, record, resolution, target, protocol)) {
                Timber.w("Migration retained for recovery: %s (%s)", descriptor.transactionId, inMemoryRecord.state)
            }
        } catch (exception: Exception) {
            Timber.e(exception, "Failed migration remains available for later recovery: %s", descriptor.transactionId)
        }
    }

    private suspend fun reconcile(
        root: File,
        journal: File,
        record: JournalRecord,
        resolution: LibraryFileTransactionResolution,
        target: File,
        protocol: LibraryFileCommitProtocol,
    ): Boolean {
        val id = record.transactionId
        return when (resolution.commitState) {
            LibraryFileCommitState.UNKNOWN -> {
                Timber.e("Migration commit state is unknown; preserving files: %s", id)
                false
            }
            LibraryFileCommitState.COMMITTED -> {
                val digest = record.treeDigest ?: run {
                    Timber.e("Committed migration has no verified digest: %s", id)
                    return false
                }
                try {
                    verifyTree(target.toPath(), digest)
                } catch (exception: Exception) {
                    Timber.e(exception, "Committed target failed verification; preserving source: %s", id)
                    return false
                }
                cleanupCommitted(resolution.descriptor.sourceDirectory.canonicalFile, target, digest)
                removeOwnershipMarker(target, resolution.descriptor.ownershipNonce)
                deleteOwnedCopy(root, id)
                completeAndForget(root, journal, protocol, id, resolution.descriptor.ownershipNonce)
                true
            }
            LibraryFileCommitState.NOT_COMMITTED -> {
                deleteOwnedCopy(root, id)
                if (record.state != JournalState.COPYING) {
                    deleteOwnedTarget(target, record, resolution)
                }
                completeAndForget(root, journal, protocol, id, resolution.descriptor.ownershipNonce)
                true
            }
        }
    }

    private fun cleanupCommitted(source: File, target: File, digest: String) {
        verifyTree(target.toPath(), digest)
        if (source.exists()) verifyTree(source.toPath(), digest)
        if (!source.deleteRecursively() && source.exists()) throw SourceCleanupException(source)
        check(!source.exists()) { "Migration source still exists after cleanup: ${source.path}" }
    }

    private suspend fun completeAndForget(
        root: File,
        journal: File,
        protocol: LibraryFileCommitProtocol,
        transactionId: String,
        ownershipNonce: String,
    ) {
        protocol.complete(transactionId)
        faultInjector.failAt(LibraryFileTransactionPoint.COMPLETED)
        writeCleanupMarker(cleanupFile(root, transactionId), transactionId, ownershipNonce)
        deleteJournal(journal)
        deleteJournalArtifact(journalTemporaryFile(root, transactionId))
        protocol.forget(transactionId)
        deleteJournalArtifact(cleanupFile(root, transactionId))
    }

    private fun copyAndVerify(source: Path, destination: Path, onProgress: (LibraryFileProgress) -> Unit): String {
        require(!Files.exists(source.resolve(OWNERSHIP_MARKER_NAME))) {
            "Migration source contains reserved ownership marker"
        }
        val before = snapshot(source)
        val totalBytes = before.values.filter { it.type == EntryType.FILE }.sumOf { it.size }
        var copiedBytes = 0L
        before.filterValues { it.type == EntryType.DIRECTORY }.keys.forEach { Files.createDirectories(destination.resolve(it)) }
        before.filterValues { it.type == EntryType.FILE }.forEach { (relative, expected) ->
            val sourceFile = source.resolve(relative)
            val targetFile = destination.resolve(relative)
            Files.createDirectories(targetFile.parent)
            check(fileDigest(sourceFile) == expected.digest) { "Source changed before copy: $relative" }
            Files.copy(sourceFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES)
            forceFile(targetFile)
            check(fileDigest(targetFile) == expected.digest) { "Copied content differs: $relative" }
            check(fileDigest(sourceFile) == expected.digest) { "Source changed during copy: $relative" }
            copiedBytes += expected.size
            onProgress(LibraryFileProgress(relative.toString(), copiedBytes, totalBytes))
        }
        check(snapshot(source) == before) { "Migration source changed during copy" }
        val targetSnapshot = snapshot(destination)
        check(targetSnapshot == before) { "Migration content verification failed" }
        return snapshotDigest(before)
    }

    private fun verifyTree(directory: Path, expectedDigest: String) {
        require(Files.isDirectory(directory)) { "Migration target is missing: $directory" }
        check(snapshotDigest(snapshot(directory)) == expectedDigest) { "Migration target content differs: $directory" }
    }

    private fun snapshot(root: Path): Map<Path, EntrySnapshot> = Files.walk(root).use { paths ->
        paths.filter { path -> root.relativize(path).toString() != OWNERSHIP_MARKER_NAME }.map { path ->
            val relative = root.relativize(path)
            when (entryType(path)) {
                EntryType.DIRECTORY -> relative to EntrySnapshot(EntryType.DIRECTORY, 0, "")
                EntryType.FILE -> relative to EntrySnapshot(EntryType.FILE, Files.size(path), fileDigest(path))
            }
        }.toList().sortedBy { it.first.toString() }.toMap()
    }

    private fun snapshotDigest(entries: Map<Path, EntrySnapshot>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.entries.sortedBy { it.key.toString() }.forEach { (path, entry) ->
            digest.update(path.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.type.name.toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(entry.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(entry.digest.toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
        }
        return digest.digest().toHex()
    }

    private fun fileDigest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun moveToFinal(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            Timber.w(exception, "Atomic final migration rename is unavailable; using same-filesystem rename")
            Files.move(source, target)
        }
    }

    private fun writeJournal(file: File, record: JournalRecord) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val properties = Properties().apply {
            setProperty("id", record.transactionId)
            setProperty("target", record.targetRelativePath)
            setProperty("state", record.state.name)
            record.treeDigest?.let { setProperty("digest", it) }
        }
        temporary.outputStream().buffered().use { output ->
            properties.store(output, null)
            output.flush()
        }
        forceFile(temporary.toPath())
        faultInjector.failAt(LibraryFileTransactionPoint.JOURNAL_TEMP_DURABLE)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: AtomicMoveNotSupportedException) {
            Timber.e(exception, "Atomic journal replacement is required; durable temporary retained: %s", temporary.path)
            throw exception
        }
        forceDirectory(requireNotNull(file.parentFile).toPath())
    }

    private fun readJournal(file: File, expectedId: String): JournalRecord {
        require(file.isFile) { "Migration journal is missing" }
        val properties = Properties()
        file.inputStream().buffered().use(properties::load)
        val id = properties.getProperty("id") ?: error("Journal id is missing")
        require(id == expectedId && UUID.fromString(id).toString() == id) { "Journal id is invalid" }
        val target = properties.getProperty("target") ?: error("Journal target is missing")
        require(!File(target).isAbsolute && target.isNotBlank()) { "Journal target is not relative" }
        return JournalRecord(
            transactionId = id,
            targetRelativePath = target,
            state = JournalState.valueOf(properties.getProperty("state") ?: error("Journal state is missing")),
            treeDigest = properties.getProperty("digest"),
        )
    }

    private fun writeCleanupMarker(file: File, transactionId: String, ownershipNonce: String) {
        val temporary = File(file.parentFile, "${file.name}$TEMP_SUFFIX")
        val properties = Properties().apply {
            setProperty("id", transactionId)
            setProperty("nonce", ownershipNonce)
        }
        temporary.outputStream().buffered().use { output ->
            properties.store(output, null)
            output.flush()
        }
        forceFile(temporary.toPath())
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: AtomicMoveNotSupportedException) {
            Timber.e(exception, "Atomic cleanup marker publication is required: %s", temporary.path)
            throw exception
        }
        forceDirectory(requireNotNull(file.parentFile).toPath())
    }

    private fun readCleanupMarker(root: File, transactionId: String): CleanupMarker {
        require(UUID.fromString(transactionId).toString() == transactionId) { "Invalid cleanup transaction id" }
        val durable = cleanupFile(root, transactionId)
        val marker = durable.takeIf(File::isFile) ?: cleanupTemporaryFile(root, transactionId)
        require(marker.isFile) { "Invalid migration cleanup marker" }
        val properties = Properties()
        marker.inputStream().buffered().use(properties::load)
        val id = properties.getProperty("id") ?: error("Cleanup marker id is missing")
        val nonce = properties.getProperty("nonce") ?: error("Cleanup marker nonce is missing")
        require(id == transactionId && UUID.fromString(id).toString() == id) { "Invalid cleanup marker id" }
        require(UUID.fromString(nonce).toString() == nonce) { "Invalid cleanup marker nonce" }
        return CleanupMarker(id, nonce)
    }

    private fun hasJournalArtifacts(root: File, transactionId: String): Boolean =
        journalFile(root, transactionId).exists() || journalTemporaryFile(root, transactionId).exists()

    private fun writeOwnershipMarker(directory: File, ownershipNonce: String) {
        require(UUID.fromString(ownershipNonce).toString() == ownershipNonce) { "Invalid migration ownership nonce" }
        val marker = File(directory, OWNERSHIP_MARKER_NAME)
        require(!marker.exists()) { "Migration source contains reserved ownership marker" }
        marker.writeText(ownershipNonce, Charsets.UTF_8)
        forceFile(marker.toPath())
        forceDirectory(directory.toPath())
    }

    private fun removeOwnershipMarker(directory: File, ownershipNonce: String) {
        val marker = File(directory, OWNERSHIP_MARKER_NAME)
        if (!marker.exists()) return
        require(marker.isFile && marker.readText(Charsets.UTF_8) == ownershipNonce) {
            "Migration target ownership marker differs from trusted metadata"
        }
        check(marker.delete()) { "Cannot remove migration ownership marker: ${marker.path}" }
        forceDirectory(directory.toPath())
    }

    private fun prepareRoot(root: File) {
        require(root.mkdirs() || root.isDirectory) { "Cannot create library root: ${root.path}" }
        val transactions = transactionRoot(root)
        require(transactions.mkdirs() || transactions.isDirectory) { "Cannot create transaction directory: ${transactions.path}" }
    }

    private fun transactionRoot(root: File): File = File(root, TRANSACTION_DIRECTORY_NAME).canonicalFile.also {
        require(it.toPath().startsWith(root.toPath())) { "Invalid migration transaction directory" }
    }

    private fun requireRelativeTarget(root: File, target: File): String {
        require(target.toPath().startsWith(root.toPath()) && target != root) { "Migration target is outside library root" }
        require(!target.toPath().startsWith(transactionRoot(root).toPath())) {
            "Migration target is inside the transaction directory"
        }
        return root.toPath().relativize(target.toPath()).toString()
    }

    private fun descriptorsMatch(
        actual: LibraryFileTransactionDescriptor,
        expected: LibraryFileTransactionDescriptor,
    ): Boolean = actual.transactionId == expected.transactionId &&
        actual.sourceDirectory.canonicalFile == expected.sourceDirectory.canonicalFile &&
        actual.targetRelativePath == expected.targetRelativePath &&
        actual.ownershipNonce == expected.ownershipNonce

    private fun File.resolveNormalized(relative: String): File {
        require(!File(relative).isAbsolute && relative.isNotBlank()) { "Migration target must be relative" }
        val relativePath = File(relative).toPath()
        require(relativePath.normalize() == relativePath) { "Migration target path is not normalized" }
        val resolved = File(this, relative).canonicalFile
        require(resolved.toPath().startsWith(toPath()) && resolved != this) { "Migration target escapes library root" }
        return resolved
    }

    private fun journalFile(root: File, id: String) = File(transactionRoot(root), "$id$JOURNAL_SUFFIX")
    private fun journalTemporaryFile(root: File, id: String) = File(transactionRoot(root), "$id$JOURNAL_SUFFIX$TEMP_SUFFIX")
    private fun copyDirectory(root: File, id: String) = File(transactionRoot(root), "$id$COPY_SUFFIX")
    private fun cleanupFile(root: File, id: String) = File(transactionRoot(root), "$id$CLEANUP_SUFFIX")
    private fun cleanupTemporaryFile(root: File, id: String) = File(transactionRoot(root), "$id$CLEANUP_SUFFIX$TEMP_SUFFIX")

    private fun deleteOwnedCopy(root: File, id: String) {
        val copy = copyDirectory(root, id)
        if (!copy.deleteRecursively() && copy.exists()) throw IOException("Cannot remove migration copy: ${copy.path}")
    }

    private fun deleteOwnedTarget(
        target: File,
        record: JournalRecord,
        resolution: LibraryFileTransactionResolution,
    ) {
        if (!target.exists()) return
        require(resolution.targetWasFinalized) { "Trusted metadata does not authorize target rollback" }
        val marker = File(target, OWNERSHIP_MARKER_NAME)
        require(marker.isFile && marker.readText(Charsets.UTF_8) == resolution.descriptor.ownershipNonce) {
            "Migration target lacks its trusted ownership marker"
        }
        val digest = record.treeDigest ?: throw IOException("Cannot prove ownership of target without digest")
        verifyTree(target.toPath(), digest)
        if (!target.deleteRecursively() && target.exists()) throw IOException("Cannot roll back migration target: ${target.path}")
    }

    private fun deleteJournal(journal: File) {
        check(journal.delete() || !journal.exists()) { "Cannot remove migration journal: ${journal.path}" }
        forceDirectory(requireNotNull(journal.parentFile).toPath())
    }

    private fun deleteJournalArtifact(journal: File) {
        if (!journal.exists()) return
        deleteJournal(journal)
    }

    private fun sameFileStore(first: Path, second: Path): Boolean {
        val firstStore: FileStore = Files.getFileStore(first)
        val secondStore: FileStore = Files.getFileStore(second)
        return firstStore == secondStore
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun forceDirectory(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (exception: IOException) {
            Timber.w(exception, "Directory fsync is unavailable: %s", path)
        } catch (exception: UnsupportedOperationException) {
            Timber.w(exception, "Directory fsync is unsupported: %s", path)
        }
    }

    private suspend fun <T> withTargetLock(root: File, relativeTarget: String, block: suspend () -> T): T {
        val key = root.path + '\u0000' + relativeTarget
        val processLock = PROCESS_LOCKS.computeIfAbsent(key) { Mutex() }
        processLock.lock()
        try {
            val lockFile = lockFile(root, relativeTarget)
            return FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        } finally {
            processLock.unlock()
        }
    }

    private suspend fun <T> tryTargetLock(root: File, relativeTarget: String, block: suspend () -> T): T? {
        val key = root.path + '\u0000' + relativeTarget
        val processLock = PROCESS_LOCKS.computeIfAbsent(key) { Mutex() }
        if (!processLock.tryLock()) return null
        try {
            val lockFile = lockFile(root, relativeTarget)
            return FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val fileLock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return@use null
                fileLock.use { block() }
            }
        } finally {
            processLock.unlock()
        }
    }

    private fun lockFile(root: File, relativeTarget: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest(relativeTarget.toByteArray(Charsets.UTF_8)).toHex() + LOCK_SUFFIX
        return File(transactionRoot(root), name)
    }

    private fun isSameOrChild(parent: Path, candidate: Path): Boolean = candidate.startsWith(parent)

    private fun entryType(path: Path): EntryType = when {
        Files.isSymbolicLink(path) -> throw IllegalArgumentException("Symbolic links cannot be migrated: $path")
        Files.isDirectory(path) -> EntryType.DIRECTORY
        Files.isRegularFile(path) -> EntryType.FILE
        else -> throw IllegalArgumentException("Unsupported filesystem entry: $path")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class JournalRecord(
        val transactionId: String,
        val targetRelativePath: String,
        val state: JournalState,
        val treeDigest: String?,
    )

    private data class CleanupMarker(val transactionId: String, val ownershipNonce: String)

    private data class EntrySnapshot(val type: EntryType, val size: Long, val digest: String)
    private enum class JournalState { COPYING, READY, COMMITTING, COMMITTED }
    private enum class EntryType { DIRECTORY, FILE }

    private companion object {
        const val TRANSACTION_DIRECTORY_NAME = ".gamenative-migrations"
        const val JOURNAL_SUFFIX = ".properties"
        const val COPY_SUFFIX = ".copy"
        const val LOCK_SUFFIX = ".lock"
        const val TEMP_SUFFIX = ".tmp"
        const val CLEANUP_SUFFIX = ".cleanup"
        const val OWNERSHIP_MARKER_NAME = ".gamenative-migration-owner"
        val JOURNAL_NAME = Regex("[0-9a-f-]{36}\\.properties")
        val JOURNAL_TEMP_NAME = Regex("[0-9a-f-]{36}\\.properties\\.tmp")
        val CLEANUP_NAME = Regex("[0-9a-f-]{36}\\.cleanup")
        val CLEANUP_TEMP_NAME = Regex("[0-9a-f-]{36}\\.cleanup\\.tmp")
        val PROCESS_LOCKS = ConcurrentHashMap<String, Mutex>()
    }
}

/** Indicates that committed metadata is safe but the verified old directory still needs cleanup. */
class SourceCleanupException(source: File) : IOException(
    "Migration committed, but the old directory could not be removed: ${source.path}",
)

/** Stable failure boundaries used by direct crash-recovery tests. */
internal enum class LibraryFileTransactionPoint {
    JOURNAL_TEMP_DURABLE,
    COPY_VERIFIED,
    MOVED,
    TARGET_FINALIZED,
    FINALIZED,
    COMMITTING,
    COMMIT_RETURNED,
    COMMITTED,
    COMPLETED,
}

/** Injects deterministic failures into tests without reflection or source inspection. */
internal fun interface LibraryFileFaultInjector {
    /** Throws when a test requests failure at [point]. */
    fun failAt(point: LibraryFileTransactionPoint)

    companion object {
        /** Production injector that never fails. */
        val NONE = LibraryFileFaultInjector { }
    }
}
