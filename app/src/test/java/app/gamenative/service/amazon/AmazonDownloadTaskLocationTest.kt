package app.gamenative.service.amazon

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryInstallation
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises Amazon task restoration against persisted identity and path tampering. */
class AmazonDownloadTaskLocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validateTask_acceptsExactRegisteredGameDirectory() {
        val fixture = fixture()

        val result = AmazonDownloadTaskLocation.validateTask(
            fixture.task,
            fixture.installation,
            PRODUCT_ID,
            APP_ID,
            GAME_TITLE,
        )

        assertEquals(File(fixture.task.installPath).canonicalPath, result)
    }

    @Test
    fun validateTask_rejectsTamperedIdentityAndLibraryFields() {
        val fixture = fixture()
        val mutations = listOf(
            fixture.task.copy(store = DownloadStore.GOG),
            fixture.task.copy(gameKey = "other-product"),
            fixture.task.copy(appId = APP_ID + 1),
            fixture.task.copy(libraryId = "other-library"),
            fixture.task.copy(libraryRoot = temporaryFolder.newFolder("other-root").path),
        )

        mutations.forEach { task ->
            assertThrows(IllegalArgumentException::class.java) {
                AmazonDownloadTaskLocation.validateTask(
                    task,
                    fixture.installation,
                    PRODUCT_ID,
                    APP_ID,
                    GAME_TITLE,
                )
            }
        }
    }

    @Test
    fun validateTask_rejectsNestedAndOtherGameDirectories() {
        val fixture = fixture()
        val mutations = listOf(
            fixture.task.copy(installPath = File(fixture.task.installPath, "nested").path),
            fixture.task.copy(installPath = File(fixture.installation.installRoot, "Other Game").path),
        )

        mutations.forEach { task ->
            assertThrows(IllegalArgumentException::class.java) {
                AmazonDownloadTaskLocation.validateTask(
                    task,
                    fixture.installation,
                    PRODUCT_ID,
                    APP_ID,
                    GAME_TITLE,
                )
            }
        }
    }

    @Test
    fun validateInstallPath_rejectsSymlinkEscapingLibrary() {
        val fixture = fixture()
        val outside = temporaryFolder.newFolder("outside")
        val link = File(fixture.installation.installRoot, AmazonConstants.gameDirectoryName(GAME_TITLE)).toPath()
        val created = runCatching {
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, outside.toPath())
        }.isSuccess
        assumeTrue("Symbolic links are unavailable on this platform", created)

        assertThrows(IllegalArgumentException::class.java) {
            AmazonDownloadTaskLocation.validateInstallPath(fixture.installation, link.toString(), GAME_TITLE)
        }
    }

    @Test
    fun validateInstallPath_rejectsResolvedPathEscapingLibraryWithoutFilesystemLinks() {
        val fixture = fixture()
        val canonicalRoot = File(fixture.installation.installRoot).canonicalFile
        val escapedPath = File(temporaryFolder.newFolder("resolved-outside"), GAME_TITLE).canonicalFile
        val resolver = AmazonCanonicalPathResolver { path ->
            if (path == fixture.task.installPath) escapedPath else canonicalRoot
        }

        assertThrows(IllegalArgumentException::class.java) {
            AmazonDownloadTaskLocation.validateInstallPath(
                installation = fixture.installation,
                installPath = fixture.task.installPath,
                gameTitle = GAME_TITLE,
                pathResolver = resolver,
            )
        }
    }

    @Test
    fun validateInstallPath_rejectsNonAmazonLibrary() {
        val fixture = fixture()
        val installation = fixture.installation.copy(
            library = fixture.installation.library.copy(source = GameSource.EPIC),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AmazonDownloadTaskLocation.validateInstallPath(installation, fixture.task.installPath, GAME_TITLE)
        }
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder("AmazonLibrary").canonicalFile
        val library = GameLibrary(
            id = "amazon-library",
            source = GameSource.AMAZON,
            rootPath = root.path,
            builtIn = false,
        )
        val installation = GameLibraryInstallation(
            library = library,
            installRoot = root.path,
            stagingRoot = File(root, ".staging").path,
        )
        val installPath = File(root, AmazonConstants.gameDirectoryName(GAME_TITLE)).path
        val now = System.currentTimeMillis()
        val task = StoreDownloadTask(
            store = DownloadStore.AMAZON,
            gameKey = PRODUCT_ID,
            appId = APP_ID,
            libraryId = library.id,
            libraryRoot = library.rootPath,
            installPath = installPath,
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
        const val PRODUCT_ID = "amzn1.adg.product.example"
        const val APP_ID = 41
        const val GAME_TITLE = "Example Game"
    }
}
