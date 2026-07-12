package app.gamenative.service.amazon

import app.gamenative.data.DownloadStore
import app.gamenative.data.GameSource
import app.gamenative.data.StoreDownloadTask
import app.gamenative.data.library.GameLibraryInstallation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Resolves Amazon library paths to their filesystem identity for containment checks. */
internal fun interface AmazonCanonicalPathResolver {
    /** Resolves [path], rejecting filesystem aliases that could bypass library ownership. */
    fun resolve(path: String): File
}

/** Production resolver that rejects symbolic links before resolving the real filesystem path. */
private object FileAmazonCanonicalPathResolver : AmazonCanonicalPathResolver {
    override fun resolve(path: String): File {
        val file = File(path)
        require(!containsSymbolicLink(file.toPath())) {
            "Amazon library paths must not contain symbolic links: $path"
        }
        return if (file.exists()) file.toPath().toRealPath().toFile() else file.canonicalFile
    }

    private fun containsSymbolicLink(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        var current = normalized.root
            ?: throw IllegalArgumentException("Amazon path has no filesystem root: $path")
        for (segment in normalized) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }
}

/** Validates durable Amazon locations before filesystem writes or recursive deletion are allowed. */
object AmazonDownloadTaskLocation {
    /**
     * Restores a persisted task only when all identity and location fields still describe the
     * exact, direct game directory of the freshly resolved registered Amazon library.
     */
    fun validateTask(
        task: StoreDownloadTask,
        installation: GameLibraryInstallation,
        productId: String,
        appId: Int,
        gameTitle: String,
    ): String {
        require(task.store == DownloadStore.AMAZON) { "Download task does not belong to Amazon" }
        require(task.gameKey == productId) { "Amazon task product id does not match the game" }
        require(task.appId == appId) { "Amazon task app id does not match the game" }
        require(task.libraryId == installation.library.id) {
            "Amazon task library id does not match registration"
        }
        require(File(task.libraryRoot).canonicalFile == File(installation.library.rootPath).canonicalFile) {
            "Amazon task library root does not match registration"
        }
        return validateInstallPath(installation, task.installPath, gameTitle)
    }

    /**
     * Returns the canonical path only for the expected direct child. Canonical parent comparison
     * also rejects a symbolic link whose apparent directory is inside the library but resolves out.
     */
    fun validateInstallPath(
        installation: GameLibraryInstallation,
        installPath: String,
        gameTitle: String,
    ): String = validateInstallPath(
        installation = installation,
        installPath = installPath,
        gameTitle = gameTitle,
        pathResolver = FileAmazonCanonicalPathResolver,
    )

    /** Validates path ownership using [pathResolver] so containment rules are testable in isolation. */
    internal fun validateInstallPath(
        installation: GameLibraryInstallation,
        installPath: String,
        gameTitle: String,
        pathResolver: AmazonCanonicalPathResolver,
    ): String {
        require(installation.library.source == GameSource.AMAZON) {
            "Resolved library does not belong to Amazon"
        }
        val canonicalRoot = pathResolver.resolve(installation.installRoot)
        val canonicalPath = pathResolver.resolve(installPath)
        require(canonicalPath.parentFile == canonicalRoot) {
            "Amazon install path is not a direct child of its registered library"
        }
        require(canonicalPath.name == AmazonConstants.gameDirectoryName(gameTitle)) {
            "Amazon install path does not match its game title"
        }
        return canonicalPath.path
    }
}
