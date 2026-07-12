package app.gamenative.service.gog

import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibrary
import app.gamenative.data.library.GogLibraryLayout
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Test

class GogLibraryPathPolicyTest {
    private val policy: GogLibraryPathPolicy = GogLibraryPathPolicyImpl()

    @Test
    fun validate_acceptsOnlyExpectedDirectChildAndMatchingRecordedRoot() {
        val root = createTempDirectory("gog-library").toFile()
        val library = library(root)
        val gameDir = File(GogLibraryLayout.installRoot(root.path), "Expected Game").apply { mkdirs() }

        val result = policy.validate(listOf(library), root.path, "Expected Game", gameDir.path)

        assertEquals(library, result.library)
        assertEquals(gameDir.canonicalPath, result.installPath)
    }

    @Test
    fun validate_rejectsTamperedRecordedRootNestedPathAndWrongGameName() {
        val root = createTempDirectory("gog-library").toFile()
        val library = library(root)
        val installRoot = File(GogLibraryLayout.installRoot(root.path)).apply { mkdirs() }
        val expected = File(installRoot, "Expected Game").apply { mkdirs() }
        val nested = File(expected, "Expected Game").apply { mkdirs() }
        val wrongName = File(installRoot, "Different Game").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(listOf(library), File(root.parentFile, "tampered").path, "Expected Game", expected.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(listOf(library), root.path, "Expected Game", nested.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(listOf(library), root.path, "Expected Game", wrongName.path)
        }
    }

    @Test
    fun validate_rejectsSymlinkEscapingRegisteredInstallRoot() {
        val root = createTempDirectory("gog-library").toFile()
        val library = library(root)
        val installRoot = File(GogLibraryLayout.installRoot(root.path)).apply { mkdirs() }
        val outside = File(createTempDirectory("gog-outside").toFile(), "Expected Game").apply { mkdirs() }
        val link = File(installRoot, "Expected Game")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (exception: Exception) {
            assumeNoException("Symbolic links are unavailable in this environment", exception)
        }

        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(listOf(library), root.path, "Expected Game", link.path)
        }
    }

    @Test
    fun validate_rejectsSymlinkEvenWhenItTargetsDirectoryInsideRegisteredLibrary() {
        val root = createTempDirectory("gog-library").toFile()
        val library = library(root)
        val installRoot = File(GogLibraryLayout.installRoot(root.path)).apply { mkdirs() }
        val target = File(installRoot, "target").apply { mkdirs() }
        val link = File(installRoot, "Expected Game")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (exception: Exception) {
            assumeNoException("Symbolic links are unavailable in this environment", exception)
        }

        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(listOf(library), root.path, "Expected Game", link.path)
        }
    }

    private fun library(root: File): GameLibrary = GameLibrary(
        id = "gog-library",
        source = GameSource.GOG,
        rootPath = root.canonicalPath,
        builtIn = false,
    )
}
