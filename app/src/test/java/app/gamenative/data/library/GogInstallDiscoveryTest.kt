package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GogInstallDiscoveryTest {
    private lateinit var root: File
    private lateinit var discovery: GogInstallDiscovery

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gog-install-discovery").toFile()
        discovery = GogInstallDiscoveryImpl()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `discovers GameNative marker installs from every explicit library`() {
        val first = library("first")
        val second = library("second")
        markerInstall(first, "First Game")
        markerInstall(second, "Second Game")

        val result = discovery.discover(
            listOf(second, first),
            listOf(
                GogMarkerInstallCandidate("10", listOf("First Game")),
                GogMarkerInstallCandidate("20", listOf("Second Game")),
            ),
        )

        assertEquals(listOf("10", "20"), result.installs.map(GogDiscoveredInstall::gameId))
        assertEquals(setOf("first", "second"), result.installs.map(GogDiscoveredInstall::libraryId).toSet())
        assertEquals(setOf(GogInstallSource.GAMENATIVE_MARKER), result.installs.map(GogDiscoveredInstall::source).toSet())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `discovers native info installs from every library with exact canonical paths`() {
        val first = library("native-first")
        val second = library("native-second")
        val firstDirectory = nativeInstall(first, "30", "Native One")
        val secondDirectory = nativeInstall(second, "40", "Native Two")

        val result = discovery.discover(listOf(second, first), emptyList())

        assertEquals(listOf("30", "40"), result.installs.map(GogDiscoveredInstall::gameId))
        assertEquals(
            listOf(firstDirectory.canonicalPath, secondDirectory.canonicalPath),
            result.installs.map(GogDiscoveredInstall::installPath),
        )
        assertEquals(setOf(GogInstallSource.GOG_INFO), result.installs.map(GogDiscoveredInstall::source).toSet())
    }

    @Test
    fun `does not expose or depend on a default library concept`() {
        val builtIn = library("built-in", builtIn = true)
        val custom = library("custom")
        nativeInstall(custom, "50", "Custom Game")

        val result = discovery.discover(listOf(builtIn, custom), emptyList())

        assertEquals("custom", result.installs.single().libraryId)
    }

    @Test
    fun `bad info is reported with path while valid sibling continues`() {
        val library = library("damaged")
        val brokenDirectory = installDirectory(library, "Broken")
        val brokenInfo = File(brokenDirectory, "goggame-broken.info").apply {
            writeText("{not-json", Charsets.UTF_8)
        }
        nativeInstall(library, "60", "Valid")

        val result = discovery.discover(listOf(library), emptyList())

        assertEquals(listOf("60"), result.installs.map(GogDiscoveredInstall::gameId))
        val issue = result.issues.single() as InvalidGogInfoIssue
        assertEquals(brokenInfo.canonicalPath, issue.metadataPath)
    }

    @Test
    fun `discovers V2 nested info and isolates a damaged nested sibling`() {
        val library = library("v2")
        val validDirectory = installDirectory(library, "Nested Valid")
        val validInfoDirectory = File(validDirectory, "game_61").apply { mkdirs() }
        writeInfo(validInfoDirectory, "61")
        val brokenDirectory = installDirectory(library, "Nested Broken")
        val brokenInfo = File(brokenDirectory, "game_62/goggame-62.info").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{not-json", Charsets.UTF_8)
        }

        val result = discovery.discover(listOf(library), emptyList())

        assertEquals(listOf("61"), result.installs.map(GogDiscoveredInstall::gameId))
        assertEquals(validDirectory.canonicalPath, result.installs.single().installPath)
        assertEquals(brokenInfo.canonicalPath, (result.issues.single() as InvalidGogInfoIssue).metadataPath)
    }

    @Test
    fun `searches through depth three but ignores deeper info`() {
        val library = library("depth-boundary")
        val acceptedDirectory = installDirectory(library, "Accepted")
        val acceptedInfoDirectory = File(acceptedDirectory, "one/two/three").apply { mkdirs() }
        writeInfo(acceptedInfoDirectory, "63")
        val ignoredDirectory = installDirectory(library, "Ignored")
        val ignoredInfoDirectory = File(ignoredDirectory, "one/two/three/four").apply { mkdirs() }
        writeInfo(ignoredInfoDirectory, "64")

        val result = discovery.discover(listOf(library), emptyList())

        assertEquals(listOf("63"), result.installs.map(GogDiscoveredInstall::gameId))
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `duplicate game id is reported and no location is selected`() {
        val first = library("duplicate-first")
        val second = library("duplicate-second")
        nativeInstall(first, "70", "First Copy")
        nativeInstall(second, "70", "Second Copy")

        val result = discovery.discover(listOf(first, second), emptyList())

        assertTrue(result.installs.isEmpty())
        val issue = result.issues.single() as DuplicateGogInstallIssue
        assertEquals("70", issue.gameId)
        assertEquals(setOf("duplicate-first", "duplicate-second"), issue.installs.map { it.libraryId }.toSet())
    }

    @Test
    fun `conflicting marker and info identities in one directory are rejected`() {
        val library = library("identity-conflict")
        val directory = markerInstall(library, "Conflicting Game")
        writeInfo(directory, "81")

        val result = discovery.discover(
            listOf(library),
            listOf(GogMarkerInstallCandidate("80", listOf("Conflicting Game"))),
        )

        assertTrue(result.installs.isEmpty())
        val issue = result.issues.single() as GogInstallIdentityConflictIssue
        assertEquals(setOf("80", "81"), issue.gameIds)
    }

    @Test
    fun `matching marker and info evidence is merged at one location`() {
        val library = library("merged")
        val directory = markerInstall(library, "Merged Game")
        writeInfo(directory, "90")

        val result = discovery.discover(
            listOf(library),
            listOf(GogMarkerInstallCandidate("90", listOf("Merged Game"))),
        )

        assertEquals(1, result.installs.size)
        assertEquals(GogInstallSource.GOG_INFO, result.installs.single().source)
        assertTrue(result.issues.isEmpty())
    }

    private fun library(id: String, builtIn: Boolean = false): GameLibrary {
        val directory = File(root, id).apply { mkdirs() }
        return GameLibrary(id, GameSource.GOG, directory.canonicalPath, builtIn)
    }

    private fun installDirectory(library: GameLibrary, name: String): File =
        File(GogLibraryLayout.installRoot(library.rootPath), name).apply { mkdirs() }

    private fun markerInstall(library: GameLibrary, name: String): File = installDirectory(library, name).apply {
        resolve(Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
    }

    private fun nativeInstall(library: GameLibrary, gameId: String, name: String): File =
        installDirectory(library, name).apply { writeInfo(this, gameId) }

    private fun writeInfo(directory: File, gameId: String) {
        File(directory, "goggame-$gameId.info").writeText("{\"gameId\":\"$gameId\"}", Charsets.UTF_8)
    }
}
