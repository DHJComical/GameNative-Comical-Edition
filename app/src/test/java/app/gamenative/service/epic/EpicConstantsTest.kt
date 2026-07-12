package app.gamenative.service.epic

import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpicConstantsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun gameInstallPath_placesSanitizedAppNameDirectlyUnderInstallRoot() {
        val installRoot = Path.of("library", "games").toString()

        val result = EpicConstants.getGameInstallPath(installRoot, "Epic App: Windows!")

        assertEquals(Path.of(installRoot, "Epic App Windows").normalize().toString(), result)
    }

    @Test
    fun gameInstallPath_rejectsAppNameWithoutFilesystemSafeCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            EpicConstants.getGameInstallPath(Path.of("library", "games").toString(), "!!!")
        }
    }

    @Test
    fun directGamePath_acceptsOnlyImmediateChildrenOfRegisteredInstallRoot() {
        val installRoot = temporaryFolder.newFolder("Epic", "games")
        val game = File(installRoot, "Game")
        val nested = File(game, "Binaries")
        val sibling = temporaryFolder.newFolder("Other", "Game")

        assertTrue(EpicConstants.isDirectGamePath(installRoot.path, game.path))
        assertFalse(EpicConstants.isDirectGamePath(installRoot.path, nested.path))
        assertFalse(EpicConstants.isDirectGamePath(installRoot.path, sibling.path))
    }
}
