package app.gamenative.service.gog

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Deletes GOG installation artifacts before database state may be cleared. */
interface GogUninstallFiles {
    /** Deletes the game directory and manifest, returning failure if either deletion fails. */
    fun delete(gameDirectory: File, manifest: File): Result<Unit>
}

/** Filesystem implementation used by GOG uninstall operations. */
@Singleton
class GogUninstallFilesImpl @Inject constructor() : GogUninstallFiles {
    override fun delete(gameDirectory: File, manifest: File): Result<Unit> {
        if (gameDirectory.exists() && !gameDirectory.deleteRecursively()) {
            val error = IllegalStateException("Failed to fully delete GOG game at ${gameDirectory.path}")
            Timber.e(error)
            return Result.failure(error)
        }
        if (manifest.exists() && !manifest.delete()) {
            val error = IllegalStateException("Failed to delete GOG manifest at ${manifest.path}")
            Timber.e(error)
            return Result.failure(error)
        }
        return Result.success(Unit)
    }
}
