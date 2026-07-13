package app.gamenative.service.gog

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GogLibraryLayout
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GogDownloadTaskPolicyTest {
    private val policy: GogDownloadTaskPolicy = GogDownloadTaskPolicyImpl(GogLibraryPathPolicyImpl())

    @Test
    fun validate_acceptsTaskOnlyWhenEveryIdentityAndLocationFieldMatches() {
        val fixture = fixture()

        val result = policy.validate(
            fixture.task,
            fixture.gameId,
            fixture.gameTitle,
            listOf(fixture.library),
            fixture.library,
        )

        assertEquals(fixture.installDirectory.canonicalPath, result)
    }

    @Test
    fun validate_rejectsMismatchedTaskIdentityLibraryRootAndInstallPath() {
        val fixture = fixture()
        val otherRoot = createTempDirectory("other-gog-library").toFile()
        val otherLibrary = library("other-library", otherRoot)
        val otherGameDirectory = File(GogLibraryLayout.installRoot(otherRoot.path), fixture.gameTitle).apply { mkdirs() }

        listOf(
            fixture.task.copy(store = DownloadStore.EPIC),
            fixture.task.copy(gameKey = "456"),
            fixture.task.copy(appId = 456),
            fixture.task.copy(libraryId = otherLibrary.id),
            fixture.task.copy(libraryRoot = otherRoot.path),
            fixture.task.copy(installPath = otherGameDirectory.path),
        ).forEach { task ->
            assertThrows(IllegalArgumentException::class.java) {
                policy.validate(task, fixture.gameId, fixture.gameTitle, listOf(fixture.library), fixture.library)
            }
        }
    }

    private fun fixture(): Fixture {
        val root = createTempDirectory("gog-library").toFile()
        val library = library("gog-library", root)
        val gameTitle = "Expected Game"
        val installDirectory = File(GogLibraryLayout.installRoot(root.path), gameTitle).apply { mkdirs() }
        val now = System.currentTimeMillis()
        return Fixture(
            gameId = "123",
            gameTitle = gameTitle,
            library = library,
            installDirectory = installDirectory,
            task = StoreDownloadTask(
                store = DownloadStore.GOG,
                gameKey = "123",
                appId = 123,
                libraryId = library.id,
                libraryRoot = library.rootPath,
                installPath = installDirectory.path,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun library(id: String, root: File): GameLibrary = GameLibrary(
        id = id,
        source = GameSource.GOG,
        rootPath = root.canonicalPath,
        builtIn = false,
    )

    private data class Fixture(
        val gameId: String,
        val gameTitle: String,
        val library: GameLibrary,
        val installDirectory: File,
        val task: StoreDownloadTask,
    )
}
