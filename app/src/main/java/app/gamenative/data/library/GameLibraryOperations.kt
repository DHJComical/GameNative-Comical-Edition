package app.gamenative.data.library

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryFileTransactionDao
import app.gamenative.db.dao.LibraryDeletionTransactionDao
import app.gamenative.db.dao.StoreDownloadTaskDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.GameRuntimeLifecycleRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Coordinates inspection, recoverable migration, and destructive removal of game libraries. */
interface GameLibraryOperations {
    /** Lists installed and partial entries owned by [libraryId]. */
    suspend fun getEntries(libraryId: String): List<GameLibraryEntry>

    /** Computes the confirmation counts and disk usage for [libraryId]. */
    suspend fun getRemovalSummary(libraryId: String): GameLibraryRemovalSummary

    /** Moves one entry to a library of the same store. */
    suspend fun migrateEntry(
        entry: GameLibraryEntry,
        targetLibraryId: String,
        onProgress: (LibraryFileProgress) -> Unit = {},
    ): Result<Unit>

    /** Deletes all games and unregisters the library only if every deletion succeeds. */
    suspend fun removeLibraryAndGames(libraryId: String): GameLibraryRemovalResult

    /** Unregisters the library without touching game files on disk. */
    suspend fun detachLibrary(libraryId: String)

    /** Recovers interrupted migrations under every registered library root. */
    suspend fun recoverMigrations(): LibraryFileRecovery
}

/** Filesystem content category displayed by the game-library manager. */
enum class GameLibraryEntryKind { INSTALLED, PARTIAL }

/** One store-scoped installed game or resumable partial download. */
data class GameLibraryEntry(
    val source: GameSource,
    val gameKey: String,
    val appId: Int,
    val title: String,
    val libraryId: String,
    val installPath: String,
    val kind: GameLibraryEntryKind,
    val sizeBytes: Long,
)

/** Destructive effect presented before a library is removed. */
data class GameLibraryRemovalSummary(
    val installedCount: Int,
    val partialCount: Int,
    val totalBytes: Long,
)

/** Removal result that identifies entries which kept the registration alive. */
data class GameLibraryRemovalResult(
    val removed: Boolean,
    val failedEntries: List<GameLibraryEntry>,
)

/** Store persistence and lifecycle boundary used by [GameLibraryOperationsImpl]. */
interface GameLibraryEntryStore {
    /** Loads installed and partial records for [source]. */
    suspend fun getEntries(source: GameSource): List<GameLibraryEntry>

    /** Returns why all filesystem mutation must currently be rejected, or null when idle. */
    fun getGlobalBlockingReason(): String?

    /** Creates a durable commit protocol bound to [entry] and [target]. */
    fun createCommitProtocol(
        entry: GameLibraryEntry,
        target: GameLibraryEntryLocation,
    ): LibraryFileCommitProtocol

    /** Creates the resolve-only persistent protocol used during startup journal recovery. */
    fun createRecoveryProtocol(libraryRoot: File): LibraryFileCommitProtocol

    /** Deletes a complete install or partial using existing store/container semantics. */
    suspend fun deleteEntry(entry: GameLibraryEntry): Result<Unit>

    /** Reconciles durable deletion trash records before library operations become ready. */
    suspend fun recoverDeletions()

    /** True while durable deletion or post-commit cleanup still owns library state. */
    suspend fun hasPendingDeletions(libraryId: String): Boolean

    /** Emits the existing library refresh event after a committed mutation. */
    fun notifyChanged(entry: GameLibraryEntry)
}

/** Validated target metadata persisted atomically at the filesystem commit boundary. */
data class GameLibraryEntryLocation(
    val libraryId: String,
    val libraryRoot: String,
    val installPath: String,
)

/** Snapshot of every runtime source that makes game-file mutation unsafe. */
internal data class LibraryOperationActivity(
    val runtimeActive: Boolean = false,
    val registryActive: Boolean = false,
    val steamActive: Boolean = false,
    val gogActive: Boolean = false,
    val epicActive: Boolean = false,
    val amazonActive: Boolean = false,
) {
    /** Returns a stable rejection reason when any store or runtime is active. */
    fun blockingReason(): String? = when {
        runtimeActive -> "A game runtime is currently active"
        registryActive -> "A game is currently running"
        steamActive -> "A Steam download or cloud sync is active"
        gogActive -> "A GOG download or sync is active"
        epicActive -> "An Epic download or sync is active"
        amazonActive -> "An Amazon download or sync is active"
        else -> null
    }
}

/** Default implementation that keeps registration changes outside partial-failure paths. */
@Singleton
class GameLibraryOperationsImpl @Inject constructor(
    private val repository: GameLibraryRepository,
    private val entryStore: GameLibraryEntryStore,
    private val fileTransaction: LibraryFileTransaction,
) : GameLibraryOperations {
    private val startupReady = CompletableDeferred<Unit>()
    private val operationMutex = Mutex()
    private val entryLocks = ConcurrentHashMap<String, Mutex>()
    private val rootLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun getEntries(libraryId: String): List<GameLibraryEntry> {
        startupReady.await()
        return getEntriesReady(libraryId)
    }

    private suspend fun getEntriesReady(libraryId: String): List<GameLibraryEntry> {
        val library = requireLibrary(libraryId)
        return entryStore.getEntries(library.source).filter { entry ->
            require(entry.source == library.source) { "Entry store returned a mismatched source" }
            isEntryOwnedBy(entry, library, rejectMatchingMetadataMismatch = true)
        }.map { it.copy(libraryId = library.id) }
            .sortedWith(compareBy(GameLibraryEntry::kind, GameLibraryEntry::title))
    }

    override suspend fun getRemovalSummary(libraryId: String): GameLibraryRemovalSummary {
        val entries = getEntries(libraryId)
        return GameLibraryRemovalSummary(
            installedCount = entries.count { it.kind == GameLibraryEntryKind.INSTALLED },
            partialCount = entries.count { it.kind == GameLibraryEntryKind.PARTIAL },
            totalBytes = entries.sumOf(GameLibraryEntry::sizeBytes),
        )
    }

    override suspend fun migrateEntry(
        entry: GameLibraryEntry,
        targetLibraryId: String,
        onProgress: (LibraryFileProgress) -> Unit,
    ): Result<Unit> {
        startupReady.await()
        return operationMutex.withLock {
            entryLock(entry).withLock {
                rootLocks.computeIfAbsent(targetLibraryId) { Mutex() }.withLock {
                    migrateEntryReady(entry, targetLibraryId, onProgress)
                }
            }
        }
    }

    private suspend fun migrateEntryReady(
        entry: GameLibraryEntry,
        targetLibraryId: String,
        onProgress: (LibraryFileProgress) -> Unit,
    ): Result<Unit> = runCatching {
        entryStore.getGlobalBlockingReason()?.let { throw IllegalStateException(it) }
        val sourceLibrary = requireLibrary(entry.libraryId)
        val targetLibrary = requireLibrary(targetLibraryId)
        require(sourceLibrary.source == entry.source) { "Entry source does not match its library" }
        require(isEntryOwnedBy(entry, sourceLibrary, rejectMatchingMetadataMismatch = true)) {
            "Migration source is not a direct child of its registered library"
        }
        require(targetLibrary.source == entry.source) { "Target library belongs to another store" }
        require(sourceLibrary.id != targetLibrary.id) { "Source and target libraries must differ" }
        val targetInstallation = repository.resolveInstallation(entry.source, targetLibrary.id)
        // Durable store tasks point at the eventual game directory, even while their contents are partial.
        val targetParent = targetInstallation.installRoot
        val source = File(entry.installPath).canonicalFile
        val target = File(targetParent, source.name).canonicalFile
        val requiredBytes = source.walkTopDown().filter(File::isFile).sumOf(File::length)
        val targetVolume = generateSequence(target.parentFile, File::getParentFile).firstOrNull(File::exists)
            ?: throw IllegalStateException("Target library has no accessible filesystem ancestor")
        require(hasEnoughLibrarySpace(requiredBytes, targetVolume.usableSpace)) {
            "Target library does not have enough free space"
        }
        val location = GameLibraryEntryLocation(targetLibrary.id, targetLibrary.rootPath, target.path)
        fileTransaction.migrate(
            sourceDirectory = source,
            targetDirectory = target,
            targetRoot = File(targetLibrary.rootPath),
            protocol = entryStore.createCommitProtocol(entry, location),
            onProgress = onProgress,
        ).getOrThrow()
        entryStore.notifyChanged(entry)
    }.onFailure { Timber.e(it, "Cannot migrate %s:%s", entry.source, entry.gameKey) }

    override suspend fun removeLibraryAndGames(libraryId: String): GameLibraryRemovalResult {
        startupReady.await()
        return operationMutex.withLock {
            rootLocks.computeIfAbsent(libraryId) { Mutex() }.withLock {
                removeLibraryAndGamesReady(libraryId)
            }
        }
    }

    override suspend fun detachLibrary(libraryId: String) {
        startupReady.await()
        operationMutex.withLock {
            rootLocks.computeIfAbsent(libraryId) { Mutex() }.withLock {
                val library = requireLibrary(libraryId)
                require(!library.builtIn) { "Built-in libraries cannot be removed" }
                entryStore.getGlobalBlockingReason()?.let { reason -> throw IllegalStateException(reason) }
                if (entryStore.hasPendingDeletions(libraryId)) {
                    entryStore.recoverDeletions()
                    require(!entryStore.hasPendingDeletions(libraryId)) {
                        "Library deletion cleanup is still pending"
                    }
                }
                val entries = getEntriesReady(libraryId)
                repository.removeLibrary(library.id)
                entries.forEach { entryStore.notifyChanged(it) }
            }
        }
    }

    private suspend fun removeLibraryAndGamesReady(libraryId: String): GameLibraryRemovalResult {
        val library = requireLibrary(libraryId)
        require(!library.builtIn) { "Built-in libraries cannot be removed" }
        entryStore.getGlobalBlockingReason()?.let { reason -> throw IllegalStateException(reason) }
        if (entryStore.hasPendingDeletions(libraryId)) {
            entryStore.recoverDeletions()
            require(!entryStore.hasPendingDeletions(libraryId)) {
                "Library deletion cleanup is still pending"
            }
        }
        val failures = mutableListOf<GameLibraryEntry>()
        getEntriesReady(libraryId).forEach { entry ->
            require(isEntryOwnedBy(entry, library, rejectMatchingMetadataMismatch = true)) {
                "Deletion target is not a direct child of its registered library"
            }
            entryStore.deleteEntry(entry)
                .onSuccess { entryStore.notifyChanged(entry) }
                .onFailure {
                    Timber.e(it, "Cannot delete %s:%s while removing library", entry.source, entry.gameKey)
                    failures += entry
                }
        }
        if (failures.isNotEmpty()) return GameLibraryRemovalResult(false, failures)
        repository.removeLibrary(library.id)
        return GameLibraryRemovalResult(true, emptyList())
    }

    override suspend fun recoverMigrations(): LibraryFileRecovery {
        try {
            val result = operationMutex.withLock {
                val results = repository.getSnapshot().libraries.map { library ->
                    fileTransaction.recover(
                        File(library.rootPath),
                        entryStore.createRecoveryProtocol(File(library.rootPath)),
                    )
                }
                entryStore.recoverDeletions()
                LibraryFileRecovery(
                    results.sumOf(LibraryFileRecovery::removedTransactions),
                    results.flatMap(LibraryFileRecovery::pendingTransactionIds).distinct(),
                )
            }
            startupReady.complete(Unit)
            return result
        } catch (exception: Exception) {
            startupReady.completeExceptionally(exception)
            throw exception
        }
    }

    private suspend fun requireLibrary(id: String): GameLibrary =
        repository.getSnapshot().libraries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown library: $id")

    private fun entryLock(entry: GameLibraryEntry): Mutex = entryLocks.computeIfAbsent(
        "${entry.source.name}:${entry.gameKey}:${File(entry.installPath).absolutePath}",
    ) { Mutex() }

    private fun isEntryOwnedBy(
        entry: GameLibraryEntry,
        library: GameLibrary,
        rejectMatchingMetadataMismatch: Boolean,
    ): Boolean {
        if (entry.source != library.source) return false
        val layout = storeLibraryLayout(library.source)
        val parent = File(entry.installPath).canonicalFile.parentFile
        val validParent = parent == File(layout.installRoot(library.rootPath)).canonicalFile ||
            parent == File(layout.stagingRoot(library.rootPath)).canonicalFile
        if (entry.libraryId.isNotBlank() && entry.libraryId != library.id) {
            require(!validParent) {
                "Entry path belongs to ${library.id} but metadata names ${entry.libraryId}"
            }
            return false
        }
        if (entry.libraryId == library.id && rejectMatchingMetadataMismatch) {
            require(validParent) { "Entry metadata points outside library ${library.id}: ${entry.installPath}" }
        }
        return validParent
    }
}

/** Applies the exact free-space boundary used before a migration transaction is prepared. */
internal fun hasEnoughLibrarySpace(requiredBytes: Long, usableBytes: Long): Boolean {
    require(requiredBytes >= 0L && usableBytes >= 0L) { "Storage byte counts must not be negative" }
    return usableBytes >= requiredBytes
}

/** Room-backed four-store adapter used by production library operations. */
@Singleton
class GameLibraryEntryStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInfoDao: AppInfoDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    private val taskDao: StoreDownloadTaskDao,
    private val transactionDao: LibraryFileTransactionDao,
    private val deletionDao: LibraryDeletionTransactionDao,
    private val repository: GameLibraryRepository,
    private val runtimeRegistry: GameRuntimeLifecycleRegistry,
) : GameLibraryEntryStore {
    override suspend fun getEntries(source: GameSource): List<GameLibraryEntry> = withContext(Dispatchers.IO) {
        val installed = when (source) {
            GameSource.STEAM -> appInfoDao.getAll().filter { it.isDownloaded }.map { info ->
                val path = info.managedInstallPath.ifBlank { SteamService.getAppDirPath(info.id) }
                entry(source, info.id.toString(), info.id, info.id.toString(), path, GameLibraryEntryKind.INSTALLED)
            }
            GameSource.GOG -> gogGameDao.getInstalledGames().map { game ->
                entry(source, game.id, game.id.toIntOrNull() ?: 0, game.title, game.installPath, GameLibraryEntryKind.INSTALLED)
            }
            GameSource.EPIC -> epicGameDao.getInstalledGames().map { game ->
                entry(source, game.appName, game.id, game.title, game.installPath, GameLibraryEntryKind.INSTALLED)
            }
            GameSource.AMAZON -> amazonGameDao.getInstalledGames().map { game ->
                entry(source, game.productId, game.appId, game.title, game.installPath, GameLibraryEntryKind.INSTALLED)
            }
            GameSource.CUSTOM_GAME -> throw IllegalArgumentException("Custom games do not have managed libraries")
        }
        val store = source.toDownloadStore()
        val installedKeys = installed.map(GameLibraryEntry::gameKey).toSet()
        val installedPaths = installed.map { File(it.installPath).canonicalPath }.toSet()
        installed + taskDao.getAll().filter {
            it.store == store && it.libraryId.isNotBlank() && it.installPath.isNotBlank()
                && File(it.installPath).isDirectory
                && it.gameKey !in installedKeys
                && File(it.installPath).canonicalPath !in installedPaths
        }.map { task ->
            entry(source, task.gameKey, task.appId, task.gameKey, task.installPath, GameLibraryEntryKind.PARTIAL, task.libraryId)
        }
    }

    override fun getGlobalBlockingReason(): String? = LibraryOperationActivity(
        runtimeActive = runtimeRegistry.isActive(),
        registryActive = ActiveGameRegistry.get() != null,
        steamActive = SteamService.hasActiveOperations(),
        gogActive = GOGService.hasActiveDownload() || GOGService.isSyncInProgress(),
        epicActive = EpicService.hasActiveDownload() || EpicService.isSyncInProgress(),
        amazonActive = AmazonService.hasActiveDownload() || AmazonService.isSyncInProgress(),
    ).blockingReason()

    override fun createCommitProtocol(
        entry: GameLibraryEntry,
        target: GameLibraryEntryLocation,
    ): LibraryFileCommitProtocol = PersistentLibraryFileCommitProtocol(
        transactionDao,
        entry,
        target,
        File(target.libraryRoot).canonicalFile,
    )

    override fun createRecoveryProtocol(libraryRoot: File): LibraryFileCommitProtocol =
        PersistentLibraryFileCommitProtocol(transactionDao, null, null, libraryRoot.canonicalFile)

    override suspend fun deleteEntry(entry: GameLibraryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val library = repository.getSnapshot().libraries.single { it.id == entry.libraryId }
            val source = File(entry.installPath).canonicalFile
            require(!source.exists() || source.isDirectory) { "Deletion source is not a directory: ${source.path}" }
            val trashRoot = File(library.rootPath, ".gamenative-trash").canonicalFile
            require(trashRoot.mkdirs() || trashRoot.isDirectory) { "Cannot create deletion trash" }
            val trash = File(trashRoot, UUID.randomUUID().toString()).canonicalFile
            val record = LibraryDeletionRecord(
                transactionId = trash.name,
                store = entry.source.toDownloadStore().name,
                gameKey = entry.gameKey,
                appId = entry.appId,
                entryKind = entry.kind.name,
                sourcePath = source.path,
                sourceExisted = source.exists(),
                trashPath = trash.path,
                libraryId = entry.libraryId,
            )
            deletionDao.prepare(record)
            try {
                if (source.exists()) Files.move(source.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
                check(deletionDao.updateState(record.transactionId, "MOVED") == 1)
                deletionDao.commitMetadata(record.transactionId)
                cleanupPostCommit(entry, trash)
                check(deletionDao.delete(record.transactionId) == 1)
            } catch (exception: Exception) {
                val state = deletionDao.getAll().singleOrNull { it.transactionId == record.transactionId }?.state
                if (state != "METADATA_COMMITTED" && trash.exists() && !source.exists()) {
                    Files.move(trash.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    deletionDao.delete(record.transactionId)
                } else if (state != "METADATA_COMMITTED" && !trash.exists()) {
                    deletionDao.delete(record.transactionId)
                }
                if (state == "METADATA_COMMITTED") {
                    throw IllegalStateException("Game deleted; post-commit cleanup remains pending", exception)
                } else {
                    throw exception
                }
            }
        }.onFailure { Timber.e(it, "Recoverable deletion failed for %s:%s", entry.source, entry.gameKey) }
    }

    override suspend fun recoverDeletions() = withContext(Dispatchers.IO) {
        deletionDao.getAll().forEach { record ->
            val entry = GameLibraryEntry(
                source = GameSource.valueOf(record.store),
                gameKey = record.gameKey,
                appId = record.appId,
                title = record.gameKey,
                libraryId = record.libraryId,
                installPath = record.sourcePath,
                kind = GameLibraryEntryKind.valueOf(record.entryKind),
                sizeBytes = 0L,
            )
            val source = File(record.sourcePath)
            val trash = File(record.trashPath)
            when (record.state) {
                "PREPARED", "MOVED" -> {
                    require(!(source.exists() && trash.exists())) {
                        "Deletion recovery found both source and trash for ${record.transactionId}"
                    }
                    if (trash.exists()) {
                        source.parentFile?.mkdirs()
                        Files.move(trash.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                    require(!record.sourceExisted || source.exists()) { "Pre-commit deletion has no recoverable source" }
                    check(deletionDao.delete(record.transactionId) == 1)
                }

                "METADATA_COMMITTED" -> {
                    require(!source.exists()) { "Committed deletion unexpectedly has a live source directory" }
                    cleanupPostCommit(entry, trash)
                    check(deletionDao.delete(record.transactionId) == 1)
                }

                else -> throw IllegalStateException("Unknown deletion state: ${record.state}")
            }
        }
    }

    override suspend fun hasPendingDeletions(libraryId: String): Boolean =
        deletionDao.countByLibrary(libraryId) > 0

    private suspend fun cleanupPostCommit(entry: GameLibraryEntry, trash: File) {
        val manifest = when (entry.source) {
            GameSource.GOG -> File(context.filesDir, "manifests/${entry.gameKey}")
            GameSource.AMAZON -> File(context.filesDir, "manifests/amazon/${entry.gameKey}.proto")
            else -> null
        }
        if (manifest?.exists() == true) {
            check(manifest.deleteRecursively() && !manifest.exists()) {
                "Store manifest cleanup remains pending: ${manifest.path}"
            }
        }
        val containerId = "${entry.source.name}_${entry.appId}"
        val container = ContainerStorageManager.loadEntries(context).firstOrNull {
            ContainerStorageManager.normalizeContainerId(it.containerId) == containerId
        }
        if (container?.hasContainer == true) {
            check(ContainerStorageManager.removeContainer(context, container.containerId)) {
                "Container cleanup remains pending: ${container.containerId}"
            }
        }
        check(trash.deleteRecursively() || !trash.exists()) { "Deletion trash cleanup remains pending: ${trash.path}" }
    }

    override fun notifyChanged(entry: GameLibraryEntry) {
        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(entry.appId, entry.source))
    }

    private fun entry(
        source: GameSource,
        gameKey: String,
        appId: Int,
        title: String,
        path: String,
        kind: GameLibraryEntryKind,
        libraryId: String = "",
    ) = GameLibraryEntry(source, gameKey, appId, title, libraryId, path, kind, directorySize(File(path)))

    private fun directorySize(root: File): Long = if (!root.exists()) 0L else root.walkTopDown()
        .filter(File::isFile).sumOf(File::length)
}

/** Durable protocol adapter whose trusted metadata is held in Room. */
internal class PersistentLibraryFileCommitProtocol(
    private val dao: LibraryFileTransactionDao,
    private val entry: GameLibraryEntry?,
    private val target: GameLibraryEntryLocation?,
    private val trustedLibraryRoot: File,
) : LibraryFileCommitProtocol {
    override suspend fun prepare(transaction: LibraryFileTransactionDescriptor) {
        val preparedEntry = requireNotNull(entry) { "Recovery protocol cannot prepare transactions" }
        val preparedTarget = requireNotNull(target) { "Recovery protocol has no target" }
        val canonicalRoot = File(preparedTarget.libraryRoot).canonicalFile
        require(canonicalRoot == trustedLibraryRoot) { "Bound migration target belongs to another library root" }
        val descriptorTarget = File(canonicalRoot, transaction.targetRelativePath).canonicalFile
        require(descriptorTarget.toPath().startsWith(canonicalRoot.toPath()) && descriptorTarget != canonicalRoot) {
            "Migration descriptor target escapes its library"
        }
        require(descriptorTarget == File(preparedTarget.installPath).canonicalFile) {
            "Migration descriptor target differs from its bound installation location"
        }
        dao.prepare(
            LibraryFileTransactionRecord(
                transaction.transactionId,
                preparedEntry.source.toDownloadStore().name,
                preparedEntry.gameKey,
                preparedEntry.appId,
                preparedEntry.kind.name,
                transaction.sourceDirectory.canonicalPath,
                transaction.targetRelativePath,
                transaction.ownershipNonce,
                preparedTarget.libraryId,
                canonicalRoot.path,
                descriptorTarget.path,
            ),
        )
    }

    override suspend fun markTargetFinalized(transactionId: String) {
        check(dao.markTargetFinalized(transactionId) == 1) { "Unknown transaction: $transactionId" }
    }

    override suspend fun commit(transactionId: String) = dao.commit(transactionId)

    override suspend fun resolve(transactionId: String): LibraryFileTransactionResolution? =
        dao.find(transactionId)?.let { record ->
            require(File(record.targetLibraryRoot).canonicalFile == trustedLibraryRoot) {
                "Migration transaction belongs to another library root"
            }
            LibraryFileTransactionResolution(
                descriptor = LibraryFileTransactionDescriptor(
                    record.transactionId,
                    File(record.sourcePath),
                    record.targetRelativePath,
                    record.ownershipNonce,
                ),
                commitState = LibraryFileCommitState.valueOf(record.commitState),
                targetWasFinalized = record.targetFinalized,
                cleanupComplete = record.cleanupComplete,
            )
        }

    override suspend fun complete(transactionId: String) {
        check(dao.complete(transactionId) == 1) { "Unknown transaction: $transactionId" }
    }

    override suspend fun forget(transactionId: String) {
        dao.forget(transactionId)
    }

}

private fun GameSource.toDownloadStore(): DownloadStore = when (this) {
    GameSource.STEAM -> DownloadStore.STEAM
    GameSource.GOG -> DownloadStore.GOG
    GameSource.EPIC -> DownloadStore.EPIC
    GameSource.AMAZON -> DownloadStore.AMAZON
    GameSource.CUSTOM_GAME -> throw IllegalArgumentException("Custom games do not have downloads")
}
