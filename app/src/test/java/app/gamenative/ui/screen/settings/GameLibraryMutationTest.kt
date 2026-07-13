package app.gamenative.ui.screen.settings

import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GameLibrarySnapshot
import app.gamenative.data.library.InstalledLibrarySynchronizationResult
import app.gamenative.data.library.InstalledLibrarySynchronizer
import app.gamenative.data.library.InstalledStoreSynchronizationFailure
import app.gamenative.data.library.InstalledStoreSynchronizationSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GameLibraryMutationTest {
    @Test
    fun `adding a library waits for synchronization`() = runBlocking {
        val repository = mockk<GameLibraryRepository>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        coEvery { repository.addLibrary(GameSource.STEAM, "/new") } returns snapshot()
        coEvery { synchronizer.synchronizeAll() } returns InstalledLibrarySynchronizationResult(emptyList())

        addRegisteredLibrary(repository, synchronizer, GameSource.STEAM, "/new")

        coVerify(exactly = 1) { synchronizer.synchronizeAll() }
    }

    @Test
    fun `changing the default library does not synchronize installations`() = runBlocking {
        val repository = mockk<GameLibraryRepository>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        coEvery { repository.setDefaultLibrary(GameSource.STEAM, "other") } returns snapshot()

        setDefaultRegisteredLibrary(repository, GameSource.STEAM, "other")

        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    @Test
    fun `successful repository addition remains successful when synchronization fails`() = runBlocking {
        val repository = mockk<GameLibraryRepository>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        val persisted = snapshot()
        coEvery { repository.addLibrary(GameSource.GOG, "/new") } returns persisted
        coEvery { synchronizer.synchronizeAll() } throws IllegalStateException("snapshot unavailable")

        val result = addRegisteredLibrary(repository, synchronizer, GameSource.GOG, "/new")

        assertSame(persisted, result)
    }

    @Test
    fun `isolated store synchronization failure preserves successful addition`() = runBlocking {
        val repository = mockk<GameLibraryRepository>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        val persisted = snapshot()
        coEvery { repository.addLibrary(GameSource.AMAZON, "/new") } returns persisted
        coEvery { synchronizer.synchronizeAll() } returns InstalledLibrarySynchronizationResult(
            stores = listOf(
                InstalledStoreSynchronizationSummary(
                    source = GameSource.AMAZON,
                    libraryIds = emptyList(),
                    reconciledInstallCount = 0,
                    issueCount = 0,
                    failure = InstalledStoreSynchronizationFailure("java.lang.IllegalStateException", "catalog failed"),
                ),
            ),
        )

        val result = addRegisteredLibrary(repository, synchronizer, GameSource.AMAZON, "/new")

        assertSame(persisted, result)
    }

    @Test
    fun `repository addition failure propagates without starting synchronization`() {
        val repository = mockk<GameLibraryRepository>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        val failure = IllegalArgumentException("invalid library")
        coEvery { repository.addLibrary(GameSource.EPIC, "/bad") } throws failure

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { addRegisteredLibrary(repository, synchronizer, GameSource.EPIC, "/bad") }
        }

        assertSame(failure, thrown)
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    private fun snapshot() = GameLibrarySnapshot(libraries = emptyList(), defaultLibraryIds = emptyMap())
}
