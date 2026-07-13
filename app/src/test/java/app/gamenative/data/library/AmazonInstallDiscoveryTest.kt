package app.gamenative.data.library

import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.enums.Marker
import app.gamenative.service.amazon.AmazonConstants
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

class AmazonInstallDiscoveryTest {
    private lateinit var root: File
    private val discovery: AmazonInstallDiscovery = AmazonInstallDiscoveryImpl()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("amazon-install-discovery").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `discovers strict catalog marker paths in every library`() {
        val builtIn = library("built-in", true)
        val custom = library("custom", false)
        markerInstall(builtIn, "First Game")
        markerInstall(custom, "Second Game")

        val result = discovery.discover(
            listOf(custom, builtIn),
            listOf(
                AmazonInstallCandidate("product-1", "First Game"),
                AmazonInstallCandidate("product-2", "Second Game"),
            ),
        )

        assertEquals(listOf("product-1", "product-2"), result.installs.map(AmazonDiscoveredInstall::productId))
        assertEquals(setOf("built-in", "custom"), result.installs.map(AmazonDiscoveredInstall::libraryId).toSet())
    }

    @Test
    fun `directory without supported identity evidence is not guessed`() {
        val library = library("unsupported", false)
        val unknown = File(library.rootPath, "A Human Title").apply { mkdirs() }

        val result = discovery.discover(listOf(library), listOf(AmazonInstallCandidate("product", "A Human Title")))

        assertEquals(emptyList<AmazonDiscoveredInstall>(), result.installs)
        assertEquals(unknown.canonicalPath, result.unsupportedDirectories.single().directoryPath)
    }

    @Test
    fun `duplicate product marker in separate libraries is an explicit conflict`() {
        val first = library("first", false)
        val second = library("second", false)
        markerInstall(first, "Shared")
        markerInstall(second, "Shared")

        val result = discovery.discover(listOf(first, second), listOf(AmazonInstallCandidate("shared", "Shared")))

        assertEquals(emptyList<AmazonDiscoveredInstall>(), result.installs)
        assertEquals("shared", result.conflicts.single().productId)
    }

    @Test
    fun `reconciler updates existing products and never creates missing catalog rows`() = runBlocking {
        val dao = mockk<AmazonGameDao>(relaxed = true)
        coEvery { dao.getByProductId("known") } returns AmazonGame(
            appId = 9,
            productId = "known",
            title = "Rich title",
            artUrl = "https://example.invalid/cover.jpg",
            versionId = "preserved-version",
            playTimeMinutes = 52,
        )
        coEvery { dao.getByProductId("missing") } returns null
        val reconciler: AmazonInstallReconciler = AmazonInstallReconcilerImpl(dao)

        val result = reconciler.reconcile(
            listOf(
                AmazonDiscoveredInstall("known", "/known", "one"),
                AmazonDiscoveredInstall("missing", "/missing", "two"),
            ),
        )

        assertEquals(listOf("known"), result.updatedProductIds)
        assertEquals(listOf("missing"), result.missingCatalogProductIds)
        coVerify(exactly = 1) { dao.updateDiscoveredInstallation("known", "/known") }
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    private fun library(id: String, builtIn: Boolean) = GameLibrary(
        id = id,
        source = GameSource.AMAZON,
        rootPath = File(root, id).apply { mkdirs() }.canonicalPath,
        builtIn = builtIn,
    )

    private fun markerInstall(library: GameLibrary, title: String) {
        File(library.rootPath, AmazonConstants.gameDirectoryName(title)).apply {
            mkdirs()
            resolve(Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        }
    }
}
