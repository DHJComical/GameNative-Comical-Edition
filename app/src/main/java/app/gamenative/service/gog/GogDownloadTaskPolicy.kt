package app.gamenative.service.gog

import app.gamenative.data.DownloadStore
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibrary
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Validates every persisted GOG task identity and location field before it can be resumed. */
interface GogDownloadTaskPolicy {
    /** Returns the canonical install path after all task and registered-library fields agree. */
    fun validate(
        task: StoreDownloadTask,
        gameId: String,
        gameTitle: String,
        libraries: List<GameLibrary>,
        selectedLibrary: GameLibrary,
    ): String
}

/** Filesystem-backed task validator that delegates exact path ownership to [GogLibraryPathPolicy]. */
@Singleton
class GogDownloadTaskPolicyImpl @Inject constructor(
    private val pathPolicy: GogLibraryPathPolicy,
) : GogDownloadTaskPolicy {
    override fun validate(
        task: StoreDownloadTask,
        gameId: String,
        gameTitle: String,
        libraries: List<GameLibrary>,
        selectedLibrary: GameLibrary,
    ): String {
        require(task.store == DownloadStore.GOG) { "Persisted task is not a GOG task" }
        require(task.gameKey == gameId) { "Persisted GOG task game key does not match the requested game" }
        require(task.appId == gameId.toIntOrNull()) { "Persisted GOG task app id does not match its game key" }
        require(task.libraryId == selectedLibrary.id) { "Persisted GOG task library id is not registered" }
        require(File(task.libraryRoot).canonicalFile == File(selectedLibrary.rootPath).canonicalFile) {
            "Persisted GOG task library root does not match its library id"
        }
        val validatedPath = pathPolicy.validate(libraries, task.libraryRoot, gameTitle, task.installPath)
        require(validatedPath.library.id == selectedLibrary.id) {
            "Persisted GOG task install path belongs to a different library"
        }
        return validatedPath.installPath
    }
}
