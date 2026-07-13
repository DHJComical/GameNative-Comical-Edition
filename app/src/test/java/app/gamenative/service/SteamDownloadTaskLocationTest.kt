package app.gamenative.service

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GameLibraryInstallation
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamDownloadTaskLocationTest {
    @Test
    fun `accepts exact registered library and direct game directory`() {
        val installation = installation("D:/Libraries/Steam")

        SteamService.validateSteamTaskLocation(
            task(
                libraryRoot = installation.library.rootPath,
                installPath = File(installation.installRoot, "Example Game").path,
            ),
            installation,
            listOf("Example Game"),
        )
    }

    @Test
    fun `rejects task whose persisted root no longer matches library id`() {
        val installation = installation("D:/Libraries/Steam")

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamTaskLocation(
                task(
                    libraryRoot = "D:/Libraries/Other",
                    installPath = File(installation.installRoot, "Example Game").path,
                ),
                installation,
                listOf("Example Game"),
            )
        }
    }

    @Test
    fun `rejects task whose persisted library id was replaced`() {
        val installation = installation("D:/Libraries/Steam")

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamTaskLocation(
                task(
                    libraryRoot = installation.library.rootPath,
                    installPath = File(installation.installRoot, "Example Game").path,
                ).copy(libraryId = "other-library"),
                installation,
                listOf("Example Game"),
            )
        }
    }

    @Test
    fun `rejects nested or outside task install directory`() {
        val installation = installation("D:/Libraries/Steam")

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamTaskLocation(
                task(
                    libraryRoot = installation.library.rootPath,
                    installPath = File(installation.installRoot, "Example Game/bin").path,
                ),
                installation,
                listOf("Example Game"),
            )
        }
    }

    @Test
    fun `rejects task directory name belonging to another app`() {
        val installation = installation("D:/Libraries/Steam")

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamTaskLocation(
                task(
                    libraryRoot = installation.library.rootPath,
                    installPath = File(installation.installRoot, "Other Game").path,
                ),
                installation,
                listOf("Example Game"),
            )
        }
    }

    @Test
    fun `accepts only direct expected directory in registered library for deletion`() {
        val installation = installation("D:/Libraries/Steam")
        val target = File(installation.installRoot, "Example Game").path

        SteamService.validateSteamDeletionTarget(
            targetPath = target,
            expectedDirectoryNames = listOf("Example Game"),
            libraries = listOf(installation.library),
            importedPath = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamDeletionTarget(
                targetPath = File(target, "bin").path,
                expectedDirectoryNames = listOf("Example Game"),
                libraries = listOf(installation.library),
                importedPath = null,
            )
        }
    }

    @Test
    fun `imported deletion requires exact persisted custom path`() {
        val recorded = File("D:/Imported/Exact Game").canonicalPath

        SteamService.validateSteamDeletionTarget(
            targetPath = recorded,
            expectedDirectoryNames = emptyList(),
            libraries = emptyList(),
            importedPath = recorded,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SteamService.validateSteamDeletionTarget(
                targetPath = File(recorded, "child").path,
                expectedDirectoryNames = emptyList(),
                libraries = emptyList(),
                importedPath = recorded,
            )
        }
    }

    @Test
    fun `database cleanup is allowed only after confirmed filesystem removal`() {
        assertFalse(SteamService.deletionRemovedTarget(deleteReturned = false, targetExistsAfter = true))
        assertFalse(SteamService.deletionRemovedTarget(deleteReturned = true, targetExistsAfter = true))
        assertTrue(SteamService.deletionRemovedTarget(deleteReturned = true, targetExistsAfter = false))
    }

    private fun installation(root: String): GameLibraryInstallation {
        val canonicalRoot = File(root).canonicalPath
        return GameLibraryInstallation(
            library = GameLibrary(
                id = "steam-library",
                source = GameSource.STEAM,
                rootPath = canonicalRoot,
                builtIn = false,
            ),
            installRoot = File(canonicalRoot, "steamapps/common").path,
            stagingRoot = File(canonicalRoot, "steamapps/staging").path,
        )
    }

    private fun task(libraryRoot: String, installPath: String): StoreDownloadTask = StoreDownloadTask(
        store = DownloadStore.STEAM,
        gameKey = "42",
        appId = 42,
        libraryId = "steam-library",
        libraryRoot = libraryRoot,
        installPath = installPath,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
