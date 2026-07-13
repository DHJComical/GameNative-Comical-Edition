package app.gamenative.data.library

import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.enums.Marker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EpicInstallDiscoveryTest {
    private lateinit var root: File
    private val discovery: EpicInstallDiscovery = EpicInstallDiscoveryImpl()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("epic-install-discovery").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `discovers markers and native manifests across every library`() {
        val builtIn = library("built-in", true)
        val custom = library("custom", false)
        markerInstall(builtIn, "MarkerApp")
        nativeInstall(custom, "NativeDirectory", "NativeApp")

        val result = discovery.discover(
            listOf(custom, builtIn),
            listOf(
                EpicInstallCandidate("catalog-marker", "MarkerApp"),
                EpicInstallCandidate("catalog-native", "NativeApp"),
            ),
        )

        assertEquals(listOf("MarkerApp", "NativeApp"), result.installs.map(EpicDiscoveredInstall::appName))
        assertEquals(setOf("built-in", "custom"), result.installs.map(EpicDiscoveredInstall::libraryId).toSet())
        assertEquals(emptyList<EpicManifestReadException>(), result.manifestErrors)
    }

    @Test
    fun `damaged manifest is isolated and reported with its path`() {
        val library = library("damaged", false)
        val game = gameDirectory(library, "Broken")
        val manifest = File(game, ".egstore/broken.manifest").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("not a manifest", Charsets.UTF_8)
        }
        markerInstall(library, "Healthy")

        val result = discovery.discover(listOf(library), listOf(EpicInstallCandidate("healthy", "Healthy")))

        assertEquals(listOf("Healthy"), result.installs.map(EpicDiscoveredInstall::appName))
        assertEquals(manifest.canonicalPath, result.manifestErrors.single().manifestPath)
    }

    @Test
    fun `duplicate app name in separate libraries is an explicit conflict`() {
        val first = library("first", false)
        val second = library("second", false)
        markerInstall(first, "SharedApp")
        markerInstall(second, "SharedApp")

        val result = discovery.discover(listOf(first, second), listOf(EpicInstallCandidate("shared", "SharedApp")))

        assertEquals(emptyList<EpicDiscoveredInstall>(), result.installs)
        assertEquals("SharedApp", result.conflicts.single().appName)
    }

    @Test
    fun `native manifest without matching catalog is not forged`() {
        val library = library("unknown", false)
        val manifest = nativeInstall(library, "UnknownDirectory", "UnknownApp")

        val result = discovery.discover(listOf(library), emptyList())

        assertEquals(emptyList<EpicDiscoveredInstall>(), result.installs)
        assertEquals(manifest.canonicalPath, result.unmatchedManifests.single().manifestPath)
        assertEquals("UnknownApp", result.unmatchedManifests.single().appName)
    }

    @Test
    fun `reconciler updates only existing matching catalog installation fields`() = runBlocking {
        val dao = mockk<EpicGameDao>(relaxed = true)
        val existing = EpicGame(
            id = 7,
            catalogId = "catalog",
            appName = "KnownApp",
            title = "Rich title",
            artCover = "https://example.invalid/cover.jpg",
            playTime = 42,
        )
        coEvery { dao.getByAppName("KnownApp") } returns existing
        coEvery { dao.getByAppName("MissingApp") } returns null
        val reconciler: EpicInstallReconciler = EpicInstallReconcilerImpl(dao)

        val result = reconciler.reconcile(
            listOf(
                EpicDiscoveredInstall("catalog", "KnownApp", "/known", "one", EpicInstallSource.GAMENATIVE_MARKER),
                EpicDiscoveredInstall("missing", "MissingApp", "/missing", "two", EpicInstallSource.EPIC_MANIFEST),
            ),
        )

        assertEquals(listOf("KnownApp"), result.updatedAppNames)
        assertEquals(listOf("MissingApp"), result.missingCatalogAppNames)
        coVerify(exactly = 1) { dao.updateDiscoveredInstallation("KnownApp", "/known") }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    private fun library(id: String, builtIn: Boolean) = GameLibrary(
        id = id,
        source = GameSource.EPIC,
        rootPath = File(root, id).apply { mkdirs() }.canonicalPath,
        builtIn = builtIn,
    )

    private fun gameDirectory(library: GameLibrary, name: String) =
        File(EpicLibraryLayout.installRoot(library.rootPath), name).apply { mkdirs() }

    private fun markerInstall(library: GameLibrary, appName: String) {
        gameDirectory(library, appName).resolve(Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
    }

    private fun nativeInstall(library: GameLibrary, directoryName: String, appName: String): File {
        val manifest = File(gameDirectory(library, directoryName), ".egstore/install.manifest")
        requireNotNull(manifest.parentFile).mkdirs()
        manifest.writeText(
            """
                {
                  "ManifestFileVersion":"013000000000",
                  "AppID":"001000000000",
                  "AppNameString":"$appName",
                  "BuildVersionString":"1"
                }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return manifest
    }
}
