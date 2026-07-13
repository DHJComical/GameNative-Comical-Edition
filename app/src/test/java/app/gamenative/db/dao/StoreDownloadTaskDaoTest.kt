package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.DownloadStore
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.data.StoreDownloadOperation
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreDownloadTaskDaoTest {

    private lateinit var database: PluviaDatabase
    private lateinit var dao: StoreDownloadTaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.storeDownloadTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `same numeric app id in different stores remains independent`() = runBlocking {
        dao.upsert(task(DownloadStore.STEAM, "42", 42, "/games/steam/Game"))
        dao.upsert(task(DownloadStore.EPIC, "42", 42, "/games/epic/Game"))

        assertEquals("/games/steam/Game", dao.find(DownloadStore.STEAM, "42")?.installPath)
        assertEquals("/games/epic/Game", dao.find(DownloadStore.EPIC, "42")?.installPath)
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `task parameters and stable enums round trip`() = runBlocking {
        val expected = task(DownloadStore.GOG, "game-key", 7, "/games/gog/Game").copy(
            dlcAppIds = listOf(3, 5),
            branch = "beta",
            language = "german",
            operation = StoreDownloadOperation.VERIFY,
            state = StoreDownloadState.FAILED,
        )

        dao.upsert(expected)

        assertEquals(expected, dao.find(DownloadStore.GOG, "game-key"))
    }

    @Test
    fun `library query location update and delete use composite identity`() = runBlocking {
        dao.upsert(task(DownloadStore.AMAZON, "product", 9, "/games/amazon/Game"))

        assertEquals(1, dao.getByLibrary(DownloadStore.AMAZON, "/games/amazon").size)
        assertEquals(
            1,
            dao.updateLocation(
                DownloadStore.AMAZON,
                "product",
                "library-2",
                "/games/amazon-2",
                "/games/amazon-2/Game",
                300L,
            ),
        )
        assertEquals("library-2", dao.find(DownloadStore.AMAZON, "product")?.libraryId)
        assertEquals(1, dao.delete(DownloadStore.AMAZON, "product"))
        assertNull(dao.find(DownloadStore.AMAZON, "product"))
    }

    @Test
    fun `interrupted active tasks become paused only for requested store`() = runBlocking {
        dao.upsert(task(DownloadStore.STEAM, "1", 1, "/steam/One").copy(state = StoreDownloadState.PREPARING))
        dao.upsert(task(DownloadStore.GOG, "2", 2, "/gog/Two").copy(state = StoreDownloadState.RUNNING))
        dao.upsert(task(DownloadStore.EPIC, "3", 3, "/epic/Three").copy(state = StoreDownloadState.FAILED))

        assertEquals(1, dao.markInterruptedAsPaused(DownloadStore.STEAM, 500L))
        assertEquals(StoreDownloadState.PAUSED, dao.find(DownloadStore.STEAM, "1")?.state)
        assertEquals(StoreDownloadState.RUNNING, dao.find(DownloadStore.GOG, "2")?.state)
        assertEquals(200L, dao.find(DownloadStore.GOG, "2")?.updatedAt)
        assertEquals(StoreDownloadState.FAILED, dao.find(DownloadStore.EPIC, "3")?.state)
    }

    @Test
    fun `legacy Steam DAO writes through to unified task table`() = runBlocking {
        val legacyDao = database.downloadingAppInfoDao()

        legacyDao.insert(DownloadingAppInfo(appId = 77, dlcAppIds = listOf(88), branch = "preview"))

        val task = dao.find(DownloadStore.STEAM, "77")
        assertEquals(listOf(88), task?.dlcAppIds)
        assertEquals("preview", task?.branch)
        assertEquals("", task?.installPath)
        assertEquals(DownloadingAppInfo(77, listOf(88), "preview"), legacyDao.getDownloadingApp(77))
    }

    @Test
    fun `legacy Steam update preserves unified task ownership fields`() = runBlocking {
        val original = task(DownloadStore.STEAM, "77", 77, "/steam/library/Game").copy(
            dlcAppIds = listOf(1),
            branch = "public",
            language = "schinese",
            operation = StoreDownloadOperation.UPDATE,
            state = StoreDownloadState.RUNNING,
            createdAt = 123L,
            updatedAt = 456L,
        )
        dao.upsert(original)

        database.downloadingAppInfoDao().insert(
            DownloadingAppInfo(appId = 77, dlcAppIds = listOf(2, 3), branch = "beta"),
        )

        assertEquals(
            original.copy(dlcAppIds = listOf(2, 3), branch = "beta"),
            dao.find(DownloadStore.STEAM, "77"),
        )
    }

    private fun task(store: DownloadStore, gameKey: String, appId: Int, installPath: String) =
        StoreDownloadTask(
            store = store,
            gameKey = gameKey,
            appId = appId,
            libraryId = "library-1",
            libraryRoot = installPath.substringBeforeLast('/'),
            installPath = installPath,
            createdAt = 100L,
            updatedAt = 200L,
        )
}
