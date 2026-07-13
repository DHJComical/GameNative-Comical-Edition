package app.gamenative.service.gog

import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GogLibraryLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton

/** Validates that a GOG game path is the exact expected child of one registered library. */
interface GogLibraryPathPolicy {
    /**
     * Resolves and validates [installPath], optionally requiring the task's persisted
     * [recordedLibraryRoot] to identify the same library.
     */
    fun validate(
        libraries: List<GameLibrary>,
        recordedLibraryRoot: String?,
        gameTitle: String,
        installPath: String,
    ): ValidatedGogLibraryPath
}

/** A canonical GOG path together with the registered library that owns it. */
data class ValidatedGogLibraryPath(
    val library: GameLibrary,
    val installPath: String,
)

/** Filesystem implementation that resolves symlinks before enforcing direct-child ownership. */
@Singleton
class GogLibraryPathPolicyImpl @Inject constructor() : GogLibraryPathPolicy {
    override fun validate(
        libraries: List<GameLibrary>,
        recordedLibraryRoot: String?,
        gameTitle: String,
        installPath: String,
    ): ValidatedGogLibraryPath {
        require(libraries.isNotEmpty()) { "No registered GOG libraries are available" }
        val canonicalRecordedRoot = recordedLibraryRoot?.let(::resolvedFile)?.path
        val canonicalPath = resolvedFile(installPath)
        val expectedName = GOGConstants.gameDirectoryName(gameTitle)
        require(canonicalPath.name == expectedName) {
            "GOG install directory does not match game title: ${canonicalPath.name}"
        }
        val matches = libraries.filter { library ->
            val canonicalLibraryRoot = resolvedFile(library.rootPath).path
            val canonicalInstallRoot = resolvedFile(GogLibraryLayout.installRoot(library.rootPath))
            (canonicalRecordedRoot == null || canonicalRecordedRoot == canonicalLibraryRoot) &&
                canonicalPath.parentFile == canonicalInstallRoot
        }
        require(matches.size == 1) {
            "GOG install path is not a direct child of exactly one registered library: $installPath"
        }
        return ValidatedGogLibraryPath(matches.single(), canonicalPath.path)
    }

    /** Uses real-path resolution for existing entries so symlinks cannot escape a library. */
    private fun resolvedFile(path: String): File {
        val file = File(path)
        require(!containsSymbolicLink(file.toPath())) {
            "GOG library paths must not contain symbolic links: $path"
        }
        return if (file.exists()) file.toPath().toRealPath().toFile() else file.canonicalFile
    }

    private fun containsSymbolicLink(path: Path): Boolean {
        var current = path.toAbsolutePath().normalize().root
            ?: throw IllegalArgumentException("GOG path has no filesystem root: $path")
        for (segment in path.toAbsolutePath().normalize()) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }
}
