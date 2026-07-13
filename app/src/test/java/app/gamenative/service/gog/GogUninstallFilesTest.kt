package app.gamenative.service.gog

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GogUninstallFilesTest {
    private val files: GogUninstallFiles = GogUninstallFilesImpl()

    @Test
    fun delete_returnsFailureWhenManifestCannotBeDeleted() {
        val root = createTempDirectory("gog-uninstall").toFile()
        val gameDirectory = File(root, "Expected Game").apply { mkdirs() }
        File(gameDirectory, "game.exe").writeText("game")
        val manifest = File(root, "manifest").apply { mkdirs() }
        File(manifest, "child").writeText("prevents non-recursive deletion")

        val result = files.delete(gameDirectory, manifest)

        assertTrue(result.isFailure)
        assertTrue(manifest.exists())
    }

    @Test
    fun delete_succeedsOnlyAfterBothArtifactsAreGone() {
        val root = createTempDirectory("gog-uninstall").toFile()
        val gameDirectory = File(root, "Expected Game").apply { mkdirs() }
        val manifest = File(root, "manifest").apply { writeText("manifest") }

        val result = files.delete(gameDirectory, manifest)

        assertTrue(result.isSuccess)
        assertFalse(gameDirectory.exists())
        assertFalse(manifest.exists())
    }
}
