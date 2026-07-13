package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SteamInstallDiscoveryTest {
    private lateinit var root: File
    private lateinit var discovery: SteamInstallDiscovery

    @Before
    fun setUp() {
        root = Files.createTempDirectory("steam-install-discovery").toFile()
        discovery = SteamInstallDiscoveryImpl()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `discovers marker installs from every library independent of input order`() {
        val first = library("first")
        val second = library("second")
        markerInstall(first, "FirstGame")
        markerInstall(second, "SecondGame")
        val candidates = listOf(
            SteamMarkerInstallCandidate(10, listOf("FirstGame")),
            SteamMarkerInstallCandidate(20, listOf("SecondGame")),
        )

        val forward = discovery.discover(listOf(first, second), candidates).installs
        val reverse = discovery.discover(listOf(second, first), candidates.reversed()).installs

        assertEquals(forward, reverse)
        assertEquals(listOf(10, 20), forward.map(SteamDiscoveredInstall::appId))
        assertEquals(setOf("first", "second"), forward.map(SteamDiscoveredInstall::libraryId).toSet())
        assertEquals(setOf(SteamInstallSource.GAMENATIVE_MARKER), forward.map(SteamDiscoveredInstall::source).toSet())
    }

    @Test
    fun `unsafe marker name does not hide legal sibling names or later libraries`() {
        val first = library("unsafe-first")
        val second = library("legal-second")
        markerInstall(first, "LegalSibling")
        markerInstall(second, "LaterGame")
        val candidates = listOf(
            SteamMarkerInstallCandidate(10, listOf("../outside", "LegalSibling")),
            SteamMarkerInstallCandidate(20, listOf("LaterGame")),
        )

        val result = discovery.discover(listOf(first, second), candidates)

        assertEquals(listOf(10, 20), result.installs.map(SteamDiscoveredInstall::appId))
        assertEquals(2, result.markerIssues.size)
        result.markerIssues.forEach { issue ->
            assertEquals(10, issue.appId)
            assertEquals("../outside", issue.directoryName)
            assertEquals("installdir must name a direct child", issue.reason.substringBefore(" of "))
        }
        assertEquals(setOf("unsafe-first", "legal-second"), result.markerIssues.map { it.libraryId }.toSet())
    }

    @Test
    fun `discovers native manifests from every library with canonical exact paths`() {
        val first = library("first")
        val second = library("second")
        nativeInstall(first, 30, "NativeOne", "Native One")
        nativeInstall(second, 40, "NativeTwo", "Native Two")

        val result = discovery.discover(listOf(second, first), emptyList()).installs

        assertEquals(listOf(30, 40), result.map(SteamDiscoveredInstall::appId))
        assertEquals(
            listOf("Native One", "Native Two"),
            result.map(SteamDiscoveredInstall::manifestName),
        )
        assertEquals(
            listOf(
                File(SteamLibraryLayout.installRoot(first.rootPath), "NativeOne").canonicalPath,
                File(SteamLibraryLayout.installRoot(second.rootPath), "NativeTwo").canonicalPath,
            ),
            result.map(SteamDiscoveredInstall::installPath),
        )
    }

    @Test
    fun `does not expose or depend on a default library concept`() {
        val builtIn = library("built-in", builtIn = true)
        val custom = library("custom")
        nativeInstall(custom, 50, "CustomGame", "Custom Game")

        val result = discovery.discover(listOf(builtIn, custom), emptyList()).installs

        assertEquals("custom", result.single().libraryId)
    }

    @Test
    fun `skips unfinished native manifest`() {
        val library = library("unfinished")
        nativeInstall(library, 60, "Downloading", "Downloading", stateFlags = 2)

        assertEquals(emptyList<SteamDiscoveredInstall>(), discovery.discover(listOf(library), emptyList()).installs)
    }

    @Test
    fun `skips native manifest when common directory is missing`() {
        val library = library("missing")
        writeManifest(library, 70, "MissingDirectory", "Missing Directory")

        assertEquals(emptyList<SteamDiscoveredInstall>(), discovery.discover(listOf(library), emptyList()).installs)
    }

    @Test
    fun `reports invalid appid with manifest context`() {
        val library = library("invalid-appid")
        val manifest = writeManifest(
            library,
            "not-a-number",
            "Broken",
            "Broken",
            fileAppId = "70",
        )

        val error = discovery.discover(listOf(library), emptyList()).manifestErrors.single()

        assertEquals(manifest.canonicalPath, error.manifestPath)
    }

    @Test
    fun `isolates filename identity errors while discovering a legal sibling manifest`() {
        val library = library("filename-identity")
        File(SteamLibraryLayout.installRoot(library.rootPath), "Mismatch").mkdirs()
        File(SteamLibraryLayout.installRoot(library.rootPath), "InvalidName").mkdirs()
        File(SteamLibraryLayout.installRoot(library.rootPath), "Overflow").mkdirs()
        val mismatch = writeManifest(library, "120", "Mismatch", "Mismatch", fileAppId = "121")
        val invalidName = writeManifest(library, "bad", "InvalidName", "Invalid", fileAppId = "bad")
        val overflow = writeManifest(
            library,
            "130",
            "Overflow",
            "Overflow",
            fileAppId = "999999999999999999999999",
        )
        nativeInstall(library, 140, "Legal", "Legal")

        val result = discovery.discover(listOf(library), emptyList())

        assertEquals(listOf(140), result.installs.map(SteamDiscoveredInstall::appId))
        assertEquals(
            setOf(mismatch.canonicalPath, invalidName.canonicalPath, overflow.canonicalPath),
            result.manifestErrors.map(SteamAppManifestException::manifestPath).toSet(),
        )
    }

    @Test
    fun `reports damaged VDF with manifest context`() {
        val library = library("damaged")
        val steamApps = File(library.rootPath, "steamapps").apply { mkdirs() }
        val manifest = File(steamApps, "appmanifest_80.acf").apply {
            writeText("\"AppState\"\n{\n\"appid\"", Charsets.UTF_8)
        }

        val error = discovery.discover(listOf(library), emptyList()).manifestErrors.single()

        assertEquals(manifest.canonicalPath, error.manifestPath)
    }

    @Test
    fun `reports duplicate appid across libraries`() {
        val first = library("duplicate-one")
        val second = library("duplicate-two")
        nativeInstall(first, 90, "FirstCopy", "First Copy")
        nativeInstall(second, 90, "SecondCopy", "Second Copy")

        val result = discovery.discover(listOf(first, second), emptyList())
        val error = result.conflicts.single()

        assertEquals(90, error.appId)
        assertEquals(setOf("duplicate-one", "duplicate-two"), error.installs.map { it.libraryId }.toSet())
        assertEquals(emptyList<SteamDiscoveredInstall>(), result.installs)
    }

    @Test
    fun `native manifest can omit name`() {
        val library = library("nameless")
        nativeInstall(library, 100, "Nameless", null)

        assertNull(discovery.discover(listOf(library), emptyList()).installs.single().manifestName)
    }

    @Test
    fun `merges marker and manifest evidence for the same exact installation`() {
        val library = library("shared-evidence")
        nativeInstall(library, 110, "SharedGame", "Shared Game")
        markerInstall(library, "SharedGame")

        val result = discovery.discover(
            listOf(library),
            listOf(SteamMarkerInstallCandidate(110, listOf("SharedGame"))),
        ).installs

        assertEquals(1, result.size)
        assertEquals(SteamInstallSource.STEAM_MANIFEST, result.single().source)
        assertEquals("Shared Game", result.single().manifestName)
    }

    private fun library(id: String, builtIn: Boolean = false): GameLibrary {
        val directory = File(root, id).apply { mkdirs() }
        return GameLibrary(id, GameSource.STEAM, directory.canonicalPath, builtIn)
    }

    private fun markerInstall(library: GameLibrary, directoryName: String) {
        File(SteamLibraryLayout.installRoot(library.rootPath), directoryName).apply {
            mkdirs()
            resolve(Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        }
    }

    private fun nativeInstall(
        library: GameLibrary,
        appId: Int,
        directoryName: String,
        name: String?,
        stateFlags: Int = 4,
    ) {
        File(SteamLibraryLayout.installRoot(library.rootPath), directoryName).mkdirs()
        writeManifest(library, appId.toString(), directoryName, name, stateFlags)
    }

    private fun writeManifest(
        library: GameLibrary,
        appId: Int,
        directoryName: String,
        name: String,
        stateFlags: Int = 4,
    ): File = writeManifest(library, appId.toString(), directoryName, name, stateFlags)

    private fun writeManifest(
        library: GameLibrary,
        appId: String,
        directoryName: String,
        name: String?,
        stateFlags: Int = 4,
        fileAppId: String = appId,
    ): File {
        val steamApps = File(library.rootPath, "steamapps").apply { mkdirs() }
        val nameEntry = name?.let { "    \"name\" \"$it\"\n" }.orEmpty()
        return File(steamApps, "appmanifest_$fileAppId.acf").apply {
            writeText(
                """
                    "AppState"
                    {
                        "appid" "$appId"
                        "StateFlags" "$stateFlags"
                        "installdir" "$directoryName"
                    $nameEntry}
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
    }
}
