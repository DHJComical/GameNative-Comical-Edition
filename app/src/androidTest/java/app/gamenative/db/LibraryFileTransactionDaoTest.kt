package app.gamenative.db

import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.data.DownloadStore
import app.gamenative.data.AppInfo
import app.gamenative.data.GOGGame
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibraryEntryKind
import app.gamenative.data.library.LibraryFileCommitState
import app.gamenative.data.library.LibraryFileTransactionRecord
import app.gamenative.data.library.PersistentLibraryFileCommitProtocol
import app.gamenative.data.library.GameLibraryEntryStoreImpl
import app.gamenative.data.library.LibraryDeletionRecord
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GameLibrarySnapshot
import app.gamenative.data.library.GameLibraryInstallation
import app.gamenative.data.GameSource
import app.gamenative.service.GameRuntimeLifecycleRegistryImpl
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFileTransactionDaoTest {
    private lateinit var database: PluviaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PluviaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun commitInstalledLocationAndTaskIsAtomicAndIdempotent() = runBlocking {
        database.gogGameDao().insert(GOGGame(id = "42", title = "Game", isInstalled = true, installPath = "/old"))
        database.storeDownloadTaskDao().upsert(task("/old"))
        val dao = database.libraryFileTransactionDao()
        dao.prepare(record(GameLibraryEntryKind.INSTALLED))

        dao.commit(TRANSACTION_ID)
        dao.commit(TRANSACTION_ID)

        assertEquals("/new", database.gogGameDao().getById("42")?.installPath)
        assertEquals("/new", database.storeDownloadTaskDao().find(DownloadStore.GOG, "42")?.installPath)
        assertEquals(LibraryFileCommitState.COMMITTED.name, dao.find(TRANSACTION_ID)?.commitState)
    }

    @Test
    fun partialCommitRequiresOnlyTaskAndForgetIsIdempotent() = runBlocking {
        database.storeDownloadTaskDao().upsert(task("/old"))
        val dao = database.libraryFileTransactionDao()
        dao.prepare(record(GameLibraryEntryKind.PARTIAL))

        dao.commit(TRANSACTION_ID)
        dao.complete(TRANSACTION_ID)
        dao.forget(TRANSACTION_ID)
        dao.forget(TRANSACTION_ID)

        assertEquals("/new", database.storeDownloadTaskDao().find(DownloadStore.GOG, "42")?.installPath)
        assertNull(dao.find(TRANSACTION_ID))
    }

    @Test
    fun recoveryProtocolRejectsTransactionFromAnotherLibraryRoot() = runBlocking {
        val dao = database.libraryFileTransactionDao()
        dao.prepare(record(GameLibraryEntryKind.PARTIAL))
        val protocol = PersistentLibraryFileCommitProtocol(
            dao = dao,
            entry = null,
            target = null,
            trustedLibraryRoot = File("/different-root"),
        )

        try {
            protocol.resolve(TRANSACTION_ID)
            fail("Cross-root transaction must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: trusted recovery roots cannot consume another library's journal metadata.
        }
    }

    @Test
    fun steamCommitConvertsImportedPathToManagedPathAtomically() = runBlocking {
        database.appInfoDao().insert(AppInfo(id = 77, isDownloaded = true, customInstallPath = "/imported"))
        val dao = database.libraryFileTransactionDao()
        dao.prepare(
            record(GameLibraryEntryKind.INSTALLED).copy(
                store = DownloadStore.STEAM.name,
                gameKey = "77",
                appId = 77,
            ),
        )

        dao.commit(TRANSACTION_ID)

        val app = database.appInfoDao().get(77)
        assertEquals("/new", app?.managedInstallPath)
        assertEquals("", app?.customInstallPath)
        assertEquals(false, app?.isImported)
    }

    @Test
    fun startupRecoveryRestoresTrashWhenInstalledMetadataStillExists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "delete-source-${System.nanoTime()}")
        val trash = File(context.cacheDir, "delete-trash-${System.nanoTime()}").apply {
            mkdirs()
            resolve("game.bin").writeText("data")
        }
        database.gogGameDao().insert(GOGGame(id = "42", isInstalled = true, installPath = source.path))
        database.libraryDeletionTransactionDao().prepare(
            LibraryDeletionRecord(
                transactionId = "delete-1",
                store = DownloadStore.GOG.name,
                gameKey = "42",
                appId = 42,
                entryKind = "INSTALLED",
                sourcePath = source.path,
                sourceExisted = true,
                trashPath = trash.path,
                libraryId = "library",
                state = "MOVED",
            ),
        )
        val store = GameLibraryEntryStoreImpl(
            context,
            database.appInfoDao(),
            database.gogGameDao(),
            database.epicGameDao(),
            database.amazonGameDao(),
            database.storeDownloadTaskDao(),
            database.libraryFileTransactionDao(),
            database.libraryDeletionTransactionDao(),
            unusedRepository,
            GameRuntimeLifecycleRegistryImpl,
        )

        store.recoverDeletions()

        assertEquals("data", File(source, "game.bin").readText())
        assertEquals(emptyList<LibraryDeletionRecord>(), database.libraryDeletionTransactionDao().getAll())
        source.deleteRecursively()
    }

    @Test
    fun partialMetadataFailureKeepsDeletionPreCommit() = runBlocking {
        val dao = database.libraryDeletionTransactionDao()
        dao.prepare(deletionRecord("partial-failure", GameLibraryEntryKind.PARTIAL, "MOVED"))

        try {
            dao.commitMetadata("partial-failure")
            fail("Missing partial task must abort metadata commit")
        } catch (_: IllegalArgumentException) {
            assertEquals("MOVED", dao.getAll().single().state)
        }
    }

    @Test
    fun installedMetadataAndTaskEnterPostCommitCleanupAtomically() = runBlocking {
        database.gogGameDao().insert(GOGGame(id = "42", isInstalled = true, installPath = "/old"))
        database.storeDownloadTaskDao().upsert(task("/old"))
        val dao = database.libraryDeletionTransactionDao()
        dao.prepare(deletionRecord("delete-commit", GameLibraryEntryKind.INSTALLED, "MOVED"))

        dao.commitMetadata("delete-commit")

        assertEquals(false, database.gogGameDao().getById("42")?.isInstalled)
        assertNull(database.storeDownloadTaskDao().find(DownloadStore.GOG, "42"))
        assertEquals("METADATA_COMMITTED", dao.getAll().single().state)
    }

    private fun task(path: String) = StoreDownloadTask(
        store = DownloadStore.GOG,
        gameKey = "42",
        appId = 42,
        libraryId = "old-library",
        libraryRoot = "/old-root",
        installPath = path,
        state = StoreDownloadState.PAUSED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun record(kind: GameLibraryEntryKind) = LibraryFileTransactionRecord(
        transactionId = TRANSACTION_ID,
        store = DownloadStore.GOG.name,
        gameKey = "42",
        appId = 42,
        entryKind = kind.name,
        sourcePath = "/old",
        targetRelativePath = "games/common/Game",
        ownershipNonce = "trusted-nonce",
        targetLibraryId = "new-library",
        targetLibraryRoot = "/new-root",
        targetInstallPath = "/new",
        targetFinalized = true,
    )

    private fun deletionRecord(id: String, kind: GameLibraryEntryKind, state: String) = LibraryDeletionRecord(
        transactionId = id,
        store = DownloadStore.GOG.name,
        gameKey = "42",
        appId = 42,
        entryKind = kind.name,
        sourcePath = "/old",
        sourceExisted = false,
        trashPath = "/trash",
        libraryId = "library",
        state = state,
    )

    private companion object {
        const val TRANSACTION_ID = "11111111-1111-1111-1111-111111111111"

        val unusedRepository = object : GameLibraryRepository {
            override suspend fun getSnapshot(): GameLibrarySnapshot = error("Not used")
            override suspend fun addLibrary(source: GameSource, rootPath: String): GameLibrarySnapshot = error("Not used")
            override suspend fun setDefaultLibrary(source: GameSource, libraryId: String): GameLibrarySnapshot = error("Not used")
            override suspend fun removeLibrary(libraryId: String): GameLibrarySnapshot = error("Not used")
            override suspend fun resolveInstallation(source: GameSource, libraryId: String): GameLibraryInstallation = error("Not used")
        }
    }
}
