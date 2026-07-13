package app.gamenative.service

import app.gamenative.enums.Marker
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamInstallStatusTest {
    @Test
    fun `resolves all completed installs from one library path snapshot`() {
        val library = Files.createTempDirectory("steam-install-status").toFile()
        val first = File(library, "First Game").apply { mkdirs() }
        val second = File(library, "Second Game").apply { mkdirs() }
        File(first, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        File(second, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()

        try {
            assertEquals(
                mapOf(10 to first.path, 20 to second.path),
                SteamService.resolveCompletedInstallPaths(
                    appIds = listOf(10, 20, 30),
                    installPaths = listOf(library.path),
                    recordedInstallPaths = emptyMap(),
                    directoryNames = mapOf(
                        10 to listOf("First Game"),
                        20 to listOf("Second Game"),
                        30 to listOf("Missing Game"),
                    ),
                ),
            )
        } finally {
            library.deleteRecursively()
        }
    }

    @Test
    fun `prefers an exact recorded install path`() {
        val library = Files.createTempDirectory("steam-recorded-status").toFile()
        val recorded = File(library, "Renamed Game").apply { mkdirs() }
        File(recorded, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()

        try {
            assertEquals(
                mapOf(42 to recorded.path),
                SteamService.resolveCompletedInstallPaths(
                    appIds = listOf(42),
                    installPaths = emptyList(),
                    recordedInstallPaths = mapOf(42 to recorded.path),
                    directoryNames = mapOf(42 to listOf("Old Name")),
                ),
            )
        } finally {
            library.deleteRecursively()
        }
    }
}
