package app.gamenative.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.AppInfo
import app.gamenative.data.ConfigInfo
import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.Marker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamInstallReconcilerTest {
    private lateinit var root: File
    private lateinit var database: PluviaDatabase
    private lateinit var reconciler: SteamInstallReconciler

    @Before
    fun setUp() {
        root = Files.createTempDirectory("steam-install-reconciler").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reconciler = SteamInstallReconcilerImpl(database, SteamInstallDiscoveryImpl())
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `reconciles marker and native manifest across libraries while preserving app info`() = runBlocking {
        val markerLibrary = library("marker")
        val nativeLibrary = library("native")
        markerInstall(markerLibrary, "CatalogConfigDirectory")
        nativeInstall(nativeLibrary, 202, "NativeDirectory", "Native Game")
        database.steamAppDao().insert(
            SteamApp(
                id = 101,
                name = "Fallback Name",
                installDir = "HistoricalDirectory",
                config = ConfigInfo(installDir = "CatalogConfigDirectory"),
            ),
        )
        database.appInfoDao().insert(
            AppInfo(
                id = 101,
                downloadedDepots = listOf(11, 12),
                dlcDepots = listOf(13),
                branch = "beta",
                recoveredInstallSizeBytes = 42L,
            ),
        )

        val result = reconciler.reconcile(listOf(markerLibrary, nativeLibrary))

        assertEquals(listOf(101, 202), result.reconciledAppIds)
        val markerInfo = database.appInfoDao().get(101)!!
        assertTrue(markerInfo.isDownloaded)
        assertEquals(listOf(11, 12), markerInfo.downloadedDepots)
        assertEquals(listOf(13), markerInfo.dlcDepots)
        assertEquals("beta", markerInfo.branch)
        assertEquals(42L, markerInfo.recoveredInstallSizeBytes)
        assertEquals(
            File(SteamLibraryLayout.installRoot(markerLibrary.rootPath), "CatalogConfigDirectory").canonicalPath,
            markerInfo.managedInstallPath,
        )
        val nativeStub = database.steamAppDao().findApp(202)!!
        assertEquals("Native Game", nativeStub.name)
        assertEquals(listOf(101, 202), database.steamAppDao().getInstalledGames().map(SteamApp::id))
    }

    @Test
    fun `isolates malformed manifest and duplicate app conflict from valid install`() = runBlocking {
        val first = library("first")
        val second = library("second")
        nativeInstall(first, 301, "FirstDuplicate", "First Duplicate")
        nativeInstall(second, 301, "SecondDuplicate", "Second Duplicate")
        nativeInstall(second, 302, "ValidGame", "Valid Game")
        val brokenManifest = File(first.rootPath, "steamapps/appmanifest_broken.acf").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("\"AppState\"\n{\n\"appid\"", Charsets.UTF_8)
        }

        val result = reconciler.reconcile(listOf(first, second))

        assertEquals(listOf(302), result.reconciledAppIds)
        assertEquals(listOf(301), result.conflictedAppIds)
        assertEquals(listOf(brokenManifest.canonicalPath), result.malformedManifestPaths)
        assertFalse(database.appInfoDao().get(301)?.isDownloaded == true)
        assertTrue(database.appInfoDao().get(302)!!.isDownloaded)
    }

    @Test
    fun `reports unsafe marker candidate while reconciling a legal sibling`() = runBlocking {
        val library = library("marker-isolation")
        markerInstall(library, "LegalMarker")
        database.steamAppDao().insert(
            SteamApp(
                id = 303,
                name = "LegalMarker",
                config = ConfigInfo(installDir = "../outside"),
            ),
        )

        val result = reconciler.reconcile(listOf(library))

        assertEquals(listOf(303), result.reconciledAppIds)
        assertEquals(303, result.markerIssues.single().appId)
        assertEquals("../outside", result.markerIssues.single().directoryName)
        assertTrue(database.appInfoDao().get(303)!!.isDownloaded)
    }

    @Test
    fun `custom path conflict preserves custom and previous managed paths`() = runBlocking {
        val library = library("custom-conflict")
        nativeInstall(library, 401, "ManagedCandidate", "Managed Candidate")
        val customPath = File(root, "custom-copy").apply { mkdirs() }.canonicalPath
        val previousManagedPath = File(root, "previous-managed").canonicalPath
        database.appInfoDao().insert(
            AppInfo(
                id = 401,
                customInstallPath = customPath,
                managedInstallPath = previousManagedPath,
            ),
        )

        reconciler.reconcile(listOf(library))

        val result = database.appInfoDao().get(401)!!
        assertTrue(result.isDownloaded)
        assertEquals(customPath, result.customInstallPath)
        assertEquals(previousManagedPath, result.managedInstallPath)
    }

    @Test
    fun `missing filesystem evidence never clears existing installed state`() = runBlocking {
        val library = library("temporarily-empty")
        val existing = AppInfo(
            id = 501,
            isDownloaded = true,
            managedInstallPath = File(root, "offline-library/game").canonicalPath,
        )
        database.appInfoDao().insert(existing)

        val result = reconciler.reconcile(listOf(library))

        assertTrue(result.reconciledAppIds.isEmpty())
        assertEquals(existing, database.appInfoDao().get(501))
    }

    private fun library(id: String): GameLibrary {
        val directory = File(root, id).apply { mkdirs() }
        return GameLibrary(id, GameSource.STEAM, directory.canonicalPath, false)
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
        name: String,
    ) {
        File(SteamLibraryLayout.installRoot(library.rootPath), directoryName).mkdirs()
        val steamApps = File(library.rootPath, "steamapps").apply { mkdirs() }
        File(steamApps, "appmanifest_$appId.acf").writeText(
            """
                "AppState"
                {
                    "appid" "$appId"
                    "StateFlags" "4"
                    "installdir" "$directoryName"
                    "name" "$name"
                }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }
}
