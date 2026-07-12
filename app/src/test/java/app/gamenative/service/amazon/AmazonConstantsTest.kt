package app.gamenative.service.amazon

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies Amazon's direct-root library layout without Android filesystem dependencies. */
class AmazonConstantsTest {
    /** A selected library root must directly contain the sanitized game directory. */
    @Test
    fun getGameInstallPathUsesSelectedLibraryRoot() {
        val root = Path.of("libraries", "amazon").toString()

        val path = AmazonConstants.getGameInstallPath(root, "A Game: Deluxe!")

        assertEquals(Path.of(root, "A Game Deluxe").toString(), path)
    }

    /** Titles without usable ASCII characters still receive a stable non-empty directory. */
    @Test
    fun gameDirectoryNameFallsBackToStableHash() {
        val title = "游戏"

        assertEquals("game_${title.hashCode().toUInt()}", AmazonConstants.gameDirectoryName(title))
    }

    /** Recursive operations must reject the root itself and similarly prefixed sibling roots. */
    @Test
    fun isGameInstallPathEnforcesComponentBoundary() {
        val root = Path.of("libraries", "amazon").toString()

        assertEquals(true, AmazonConstants.isGameInstallPath(root, Path.of(root, "Game").toString()))
        assertEquals(false, AmazonConstants.isGameInstallPath(root, root))
        assertEquals(
            false,
            AmazonConstants.isGameInstallPath(root, Path.of("libraries", "amazon-backup", "Game").toString()),
        )
    }
}
