package app.gamenative.service.gog

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GOGConstantsTest {
    @Test
    fun gameInstallPath_placesSanitizedTitleUnderResolvedLibraryInstallRoot() {
        val installRoot = Path.of("library", "games", "common").toString()

        val result = GOGConstants.getGameInstallPath(installRoot, "The Witcher 3: Wild Hunt!")

        assertEquals(
            Path.of(installRoot, "The Witcher 3 Wild Hunt").normalize().toString(),
            result,
        )
    }

    @Test
    fun gameInstallPath_rejectsTitleWithoutFilesystemSafeCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            GOGConstants.getGameInstallPath(Path.of("library", "games", "common").toString(), "!!!")
        }
    }
}
