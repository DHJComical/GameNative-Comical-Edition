package app.gamenative.data.library

import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.db.dao.GOGGameDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GogInstallReconcilerTest {
    private lateinit var root: File
    private lateinit var dao: GOGGameDao

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gog-install-reconciler").toFile()
        dao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `reconciliation preserves rich catalog fields and changes only installation state`() = runBlocking {
        val library = library("catalog")
        val directory = installDirectory(library, "Rich Game")
        File(directory, ".download_complete").createNewFile()
        val original = GOGGame(
            id = "100",
            title = "Rich Game",
            slug = "rich-game",
            downloadSize = 111,
            installSize = 222,
            imageUrl = "image",
            description = "description",
            developer = "developer",
            genres = listOf("genre"),
            lastPlayed = 333,
            playTime = 444,
        )
        coEvery { dao.getAllAsList() } returns listOf(original)
        val updated = slot<GOGGame>()
        coEvery { dao.update(capture(updated)) } returns Unit

        val result = reconciler().reconcile(listOf(library))

        assertEquals(original.copy(isInstalled = true, installPath = directory.canonicalPath), updated.captured)
        assertEquals(setOf("100"), result.updatedGameIds)
        assertTrue(result.unknownGameIds.isEmpty())
    }

    @Test
    fun `unknown native game identity is reported without fabricating catalog row`() = runBlocking {
        val library = library("unknown")
        val directory = installDirectory(library, "Unknown Game")
        File(directory, "goggame-999.info").writeText("{\"gameId\":999}", Charsets.UTF_8)
        coEvery { dao.getAllAsList() } returns emptyList()

        val result = reconciler().reconcile(listOf(library))

        assertEquals(setOf("999"), result.unknownGameIds)
        assertTrue(result.updatedGameIds.isEmpty())
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `missing filesystem evidence never clears existing installation`() = runBlocking {
        val library = library("positive-only")
        val installed = GOGGame(id = "200", title = "Existing", isInstalled = true, installPath = "old-path")
        coEvery { dao.getAllAsList() } returns listOf(installed)

        val result = reconciler().reconcile(listOf(library))

        assertTrue(result.updatedGameIds.isEmpty())
        assertTrue(result.unknownGameIds.isEmpty())
        coVerify(exactly = 0) { dao.update(any()) }
    }

    private fun reconciler(): GogInstallReconciler = GogInstallReconcilerImpl(dao, GogInstallDiscoveryImpl())

    private fun library(id: String): GameLibrary {
        val directory = File(root, id).apply { mkdirs() }
        return GameLibrary(id, GameSource.GOG, directory.canonicalPath, builtIn = false)
    }

    private fun installDirectory(library: GameLibrary, name: String): File =
        File(GogLibraryLayout.installRoot(library.rootPath), name).apply { mkdirs() }
}
