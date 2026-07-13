package app.gamenative.data.library

import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import timber.log.Timber

class InstalledLibrarySynchronizerTest {
    @Test
    fun `groups every explicit library by store and ignores default selection`() = runBlocking {
        val snapshot = AtomicReference(snapshot(defaultSteamId = "steam-one"))
        val repository = snapshotRepository(snapshot)
        val steam = mockk<SteamInstallReconciler>()
        val gog = mockk<GogInstallReconciler>()
        val epicDiscovery = mockk<EpicInstallDiscovery>()
        val epicReconciler = mockk<EpicInstallReconciler>()
        val amazonDiscovery = mockk<AmazonInstallDiscovery>()
        val amazonReconciler = mockk<AmazonInstallReconciler>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        val markerIssue = SteamMarkerInstallIssue(10, "../outside", "steam-one", "outside install root")
        coEvery { steam.reconcile(any()) } returns SteamInstallReconciliationResult(
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(markerIssue),
        )
        coEvery { gog.reconcile(any()) } returns GogInstallReconciliationResult(emptySet(), emptySet(), emptyList())
        coEvery { epicDao.getAllAsList() } returns listOf(EpicGame(catalogId = "epic-id", appName = "EpicApp"))
        every { epicDiscovery.discover(any(), any()) } returns
            EpicInstallDiscoveryResult(emptyList(), emptyList(), emptyList(), emptyList())
        coEvery { epicReconciler.reconcile(any()) } returns EpicInstallReconcileResult(emptyList(), emptyList())
        coEvery { amazonDao.getAllAsList() } returns listOf(AmazonGame(productId = "amazon-id", title = "Amazon"))
        every { amazonDiscovery.discover(any(), any()) } returns
            AmazonInstallDiscoveryResult(emptyList(), emptyList(), emptyList())
        coEvery { amazonReconciler.reconcile(any()) } returns AmazonInstallReconcileResult(emptyList(), emptyList())
        val synchronizer = InstalledLibrarySynchronizerImpl(
            repository,
            steam,
            gog,
            epicDiscovery,
            epicReconciler,
            amazonDiscovery,
            amazonReconciler,
            epicDao,
            amazonDao,
        )

        val first = synchronizer.synchronizeAll()
        snapshot.set(snapshot(defaultSteamId = "steam-two"))
        val second = synchronizer.synchronizeAll()

        val expectedIds = mapOf(
            GameSource.STEAM to listOf("steam-one", "steam-two"),
            GameSource.GOG to listOf("gog-one", "gog-two"),
            GameSource.EPIC to listOf("epic-one", "epic-two"),
            GameSource.AMAZON to listOf("amazon-one", "amazon-two"),
        )
        assertEquals(expectedIds, first.stores.associate { it.source to it.libraryIds })
        assertEquals(expectedIds, second.stores.associate { it.source to it.libraryIds })
        assertEquals(1, first.stores.single { it.source == GameSource.STEAM }.issueCount)
        assertEquals(1, second.stores.single { it.source == GameSource.STEAM }.issueCount)
        coVerify(exactly = 2) {
            steam.reconcile(match { libraries -> libraries.map(GameLibrary::id) == expectedIds.getValue(GameSource.STEAM) })
        }
        coVerify(exactly = 2) {
            gog.reconcile(match { libraries -> libraries.map(GameLibrary::id) == expectedIds.getValue(GameSource.GOG) })
        }
        verify(exactly = 2) {
            epicDiscovery.discover(
                match { libraries -> libraries.map(GameLibrary::id) == expectedIds.getValue(GameSource.EPIC) },
                listOf(EpicInstallCandidate("epic-id", "EpicApp")),
            )
        }
        verify(exactly = 2) {
            amazonDiscovery.discover(
                match { libraries -> libraries.map(GameLibrary::id) == expectedIds.getValue(GameSource.AMAZON) },
                listOf(AmazonInstallCandidate("amazon-id", "Amazon")),
            )
        }
        coVerify(exactly = 2) { epicReconciler.reconcile(emptyList()) }
        coVerify(exactly = 2) { amazonReconciler.reconcile(emptyList()) }
    }

    @Test
    fun `Steam failure is logged and isolated while later stores continue`() = runBlocking {
        val fixture = fixture()
        val failure = IllegalStateException("Steam database unavailable")
        coEvery { fixture.steam.reconcile(any()) } throws failure
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
                logs += message to throwable
            }
        }
        Timber.plant(tree)

        val result = try {
            fixture.synchronizer.synchronizeAll()
        } finally {
            Timber.uproot(tree)
        }

        val steamSummary = result.stores.single { it.source == GameSource.STEAM }
        assertNotNull(steamSummary.failure)
        assertEquals(IllegalStateException::class.java.name, steamSummary.failure?.exceptionType)
        assertEquals("Steam database unavailable", steamSummary.failure?.message)
        assertEquals(failure, logs.single { it.first.contains("STEAM installed-library") }.second)
        coVerify(exactly = 1) { fixture.gog.reconcile(any()) }
        verify(exactly = 1) { fixture.epicDiscovery.discover(any(), any()) }
        verify(exactly = 1) { fixture.amazonDiscovery.discover(any(), any()) }
    }

    @Test
    fun `cancellation propagates without starting later stores`() {
        val fixture = fixture()
        coEvery { fixture.steam.reconcile(any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { fixture.synchronizer.synchronizeAll() }
        }

        coVerify(exactly = 0) { fixture.gog.reconcile(any()) }
        verify(exactly = 0) { fixture.epicDiscovery.discover(any(), any()) }
        verify(exactly = 0) { fixture.amazonDiscovery.discover(any(), any()) }
    }

    @Test
    fun `library removal idle block waits for active synchronization`() = runBlocking {
        val fixture = fixture()
        val steamStarted = CompletableDeferred<Unit>()
        val releaseSteam = CompletableDeferred<Unit>()
        coEvery { fixture.steam.reconcile(any()) } coAnswers {
            steamStarted.complete(Unit)
            releaseSteam.await()
            SteamInstallReconciliationResult(emptyList(), emptyList(), emptyList())
        }
        val synchronization = async { fixture.synchronizer.synchronizeAll() }
        steamStarted.await()
        val removalEntered = CompletableDeferred<Unit>()
        val removal = async {
            fixture.synchronizer.withSynchronizationIdle {
                removalEntered.complete(Unit)
            }
        }

        yield()
        assertFalse(removalEntered.isCompleted)
        releaseSteam.complete(Unit)
        synchronization.await()
        removal.await()
        Unit
    }

    private fun snapshot(defaultSteamId: String): GameLibrarySnapshot {
        val libraries = listOf(
            library("steam-one", GameSource.STEAM),
            library("steam-two", GameSource.STEAM),
            library("gog-one", GameSource.GOG),
            library("gog-two", GameSource.GOG),
            library("epic-one", GameSource.EPIC),
            library("epic-two", GameSource.EPIC),
            library("amazon-one", GameSource.AMAZON),
            library("amazon-two", GameSource.AMAZON),
        )
        return GameLibrarySnapshot(
            libraries = libraries,
            defaultLibraryIds = mapOf(
                GameSource.STEAM to defaultSteamId,
                GameSource.GOG to "gog-one",
                GameSource.EPIC to "epic-one",
                GameSource.AMAZON to "amazon-one",
            ),
        )
    }

    private fun library(id: String, source: GameSource) = GameLibrary(id, source, "/$id", builtIn = false)

    private fun snapshotRepository(snapshot: AtomicReference<GameLibrarySnapshot>) = object : GameLibraryRepository {
        override suspend fun getSnapshot(): GameLibrarySnapshot = snapshot.get()
        override suspend fun addLibrary(source: GameSource, rootPath: String) = error("Not used")
        override suspend fun setDefaultLibrary(source: GameSource, libraryId: String) = error("Not used")
        override suspend fun removeLibrary(libraryId: String) = error("Not used")
        override suspend fun resolveInstallation(source: GameSource, libraryId: String) = error("Not used")
    }

    private fun fixture(): SynchronizerFixture {
        val repository = mockk<GameLibraryRepository>()
        val steam = mockk<SteamInstallReconciler>()
        val gog = mockk<GogInstallReconciler>()
        val epicDiscovery = mockk<EpicInstallDiscovery>()
        val epicReconciler = mockk<EpicInstallReconciler>()
        val amazonDiscovery = mockk<AmazonInstallDiscovery>()
        val amazonReconciler = mockk<AmazonInstallReconciler>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        coEvery { repository.getSnapshot() } returns snapshot("steam-one")
        coEvery { steam.reconcile(any()) } returns SteamInstallReconciliationResult(emptyList(), emptyList(), emptyList())
        coEvery { gog.reconcile(any()) } returns GogInstallReconciliationResult(emptySet(), emptySet(), emptyList())
        coEvery { epicDao.getAllAsList() } returns emptyList()
        every { epicDiscovery.discover(any(), any()) } returns
            EpicInstallDiscoveryResult(emptyList(), emptyList(), emptyList(), emptyList())
        coEvery { epicReconciler.reconcile(any()) } returns EpicInstallReconcileResult(emptyList(), emptyList())
        coEvery { amazonDao.getAllAsList() } returns emptyList()
        every { amazonDiscovery.discover(any(), any()) } returns
            AmazonInstallDiscoveryResult(emptyList(), emptyList(), emptyList())
        coEvery { amazonReconciler.reconcile(any()) } returns AmazonInstallReconcileResult(emptyList(), emptyList())
        return SynchronizerFixture(
            synchronizer = InstalledLibrarySynchronizerImpl(
                repository,
                steam,
                gog,
                epicDiscovery,
                epicReconciler,
                amazonDiscovery,
                amazonReconciler,
                epicDao,
                amazonDao,
            ),
            steam = steam,
            gog = gog,
            epicDiscovery = epicDiscovery,
            amazonDiscovery = amazonDiscovery,
        )
    }

    private data class SynchronizerFixture(
        val synchronizer: InstalledLibrarySynchronizer,
        val steam: SteamInstallReconciler,
        val gog: GogInstallReconciler,
        val epicDiscovery: EpicInstallDiscovery,
        val amazonDiscovery: AmazonInstallDiscovery,
    )
}
