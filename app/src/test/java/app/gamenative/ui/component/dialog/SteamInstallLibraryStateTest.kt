package app.gamenative.ui.component.dialog

import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibrary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamInstallLibraryStateTest {
    @Test
    fun installStatusLoadingOrFailedKeepsInstallDisabled() {
        assertEquals(false, canEnableSteamInstall(false, true, true))
    }

    @Test
    fun readyInstallStillRequiresLibraryAndSelectionConstraints() {
        assertEquals(false, canEnableSteamInstall(true, false, true))
        assertEquals(false, canEnableSteamInstall(true, true, false))
        assertEquals(true, canEnableSteamInstall(true, true, true))
    }

    @Test
    fun libraryLoadingDoesNotProduceAnInstallTarget() {
        assertNull(resolveSteamInstallTarget(null, 3_946_810))
    }

    @Test
    fun selectedLibraryProducesTheGameInstallTarget() {
        val installRoot = File("library", "steamapps/common").path
        val option = InstallLibraryOption(
            library = GameLibrary(
                id = "steam-library",
                source = GameSource.STEAM,
                rootPath = "library",
                builtIn = false,
            ),
            installRoot = installRoot,
        )

        assertEquals(
            File(installRoot, "3946810").path,
            resolveSteamInstallTarget(option, 3_946_810),
        )
    }
}
