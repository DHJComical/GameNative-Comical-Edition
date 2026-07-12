package app.gamenative.service.epic

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryInstallation
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpicDownloadTaskLocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validate_acceptsTaskWhosePersistedLocationMatchesResolvedLibrary() {
        val fixture = fixture()

        val result = EpicDownloadTaskLocation.validate(fixture.task, fixture.installation, APP_ID, APP_NAME)

        assertEquals(File(fixture.task.installPath).canonicalPath, result)
    }

    @Test
    fun validate_rejectsTamperedLibraryRoot() {
        val fixture = fixture()
        val task = fixture.task.copy(libraryRoot = temporaryFolder.newFolder("other-root").path)

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(task, fixture.installation, APP_ID, APP_NAME)
        }
    }

    @Test
    fun validate_rejectsTamperedLibraryId() {
        val fixture = fixture()
        val task = fixture.task.copy(libraryId = "other-library")

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(task, fixture.installation, APP_ID, APP_NAME)
        }
    }

    @Test
    fun validate_rejectsTamperedGameIdentity() {
        val fixture = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(
                fixture.task.copy(gameKey = "OtherApp"),
                fixture.installation,
                APP_ID,
                APP_NAME,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(
                fixture.task.copy(appId = APP_ID + 1),
                fixture.installation,
                APP_ID,
                APP_NAME,
            )
        }
    }

    @Test
    fun validate_rejectsNestedOrRenamedInstallPath() {
        val fixture = fixture()
        val nested = fixture.task.copy(installPath = File(fixture.task.installPath, "nested").path)
        val renamed = fixture.task.copy(installPath = File(fixture.installation.installRoot, "OtherGame").path)

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(nested, fixture.installation, APP_ID, APP_NAME)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(renamed, fixture.installation, APP_ID, APP_NAME)
        }
    }

    @Test
    fun validate_rejectsSymbolicLinkGameDirectory() {
        val fixture = fixture()
        val linkTarget = File(fixture.installation.installRoot, "RealEpicApp").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(fixture.task.installPath).toPath(), linkTarget.toPath())
        } catch (exception: IOException) {
            assumeNoException("Symbolic links are unavailable on this test host", exception)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable on this test host", exception)
        } catch (exception: SecurityException) {
            assumeNoException("Symbolic links are unavailable on this test host", exception)
        }

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(fixture.task, fixture.installation, APP_ID, APP_NAME)
        }
    }

    @Test
    fun validate_rejectsNonEpicTaskAndLibrary() {
        val fixture = fixture()
        val wrongTask = fixture.task.copy(store = DownloadStore.GOG)
        val wrongLibrary = fixture.installation.copy(
            library = fixture.installation.library.copy(source = GameSource.GOG),
        )

        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(wrongTask, fixture.installation, APP_ID, APP_NAME)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpicDownloadTaskLocation.validate(fixture.task, wrongLibrary, APP_ID, APP_NAME)
        }
    }

    private fun fixture(): Fixture {
        val libraryRoot = temporaryFolder.newFolder("EpicLibrary").canonicalPath
        val installRoot = File(libraryRoot, "games").canonicalPath
        require(File(installRoot).mkdirs())
        val library = GameLibrary(
            id = "epic-library",
            source = GameSource.EPIC,
            rootPath = libraryRoot,
            builtIn = false,
        )
        val installation = GameLibraryInstallation(
            library = library,
            installRoot = installRoot,
            stagingRoot = File(libraryRoot, "staging").path,
        )
        val now = System.currentTimeMillis()
        val task = StoreDownloadTask(
            store = DownloadStore.EPIC,
            gameKey = APP_NAME,
            appId = APP_ID,
            libraryId = library.id,
            libraryRoot = library.rootPath,
            installPath = EpicConstants.getGameInstallPath(installation, APP_NAME),
            state = StoreDownloadState.PAUSED,
            createdAt = now,
            updatedAt = now,
        )
        return Fixture(installation, task)
    }

    private data class Fixture(
        val installation: GameLibraryInstallation,
        val task: StoreDownloadTask,
    )

    private companion object {
        const val APP_NAME = "EpicApp"
        const val APP_ID = 7
    }
}
