package app.gamenative.service.epic

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibraryInstallation
import java.io.File
import java.nio.file.Files

/** Validates durable Epic task locations before installers regain filesystem write access. */
object EpicDownloadTaskLocation {
    /**
     * Returns the canonical exact game path only when every persisted location field still agrees
     * with the freshly resolved registered library. This rejects independently tampered task rows.
     */
    fun validate(
        task: StoreDownloadTask,
        installation: GameLibraryInstallation,
        appId: Int,
        appName: String,
    ): String {
        require(task.store == DownloadStore.EPIC) { "Download task does not belong to Epic" }
        require(task.gameKey == appName) { "Download task game key does not match Epic app name" }
        require(task.appId == appId) { "Download task app id does not match Epic game" }
        require(installation.library.source == GameSource.EPIC) { "Resolved library does not belong to Epic" }
        require(task.libraryId == installation.library.id) { "Download task library id does not match registration" }
        require(File(task.libraryRoot).canonicalFile == File(installation.library.rootPath).canonicalFile) {
            "Download task library root does not match registration"
        }
        require(EpicConstants.isDirectGamePath(installation.installRoot, task.installPath)) {
            "Epic task path is not an exact child of its registered library"
        }
        require(!Files.isSymbolicLink(File(task.installPath).toPath())) {
            "Epic task path must not be a symbolic link"
        }
        val expectedPath = EpicConstants.getGameInstallPath(installation, appName)
        require(File(task.installPath).canonicalFile == File(expectedPath).canonicalFile) {
            "Epic task path does not match its app name"
        }
        return File(task.installPath).canonicalPath
    }
}
