package app.gamenative

import app.gamenative.data.library.GameLibraryOperations
import app.gamenative.data.library.InstalledCatalogIdentitySignature
import app.gamenative.data.library.InstalledCatalogIdentitySource
import app.gamenative.data.library.InstalledLibrarySynchronizationResult
import app.gamenative.data.library.InstalledLibrarySynchronizer
import app.gamenative.data.library.LibraryFileRecovery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InstalledLibraryStartupTest {
    @Test
    fun `successful recovery starts installed library synchronization`() = runBlocking {
        val operations = mockk<GameLibraryOperations>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        val identitySource = identitySource()
        coEvery { operations.recoverMigrations() } returns LibraryFileRecovery(0, emptyList())
        coEvery { synchronizer.synchronizeAll() } returns InstalledLibrarySynchronizationResult(emptyList())

        val observer = recoverAndStartInstalledLibrarySynchronization(operations, synchronizer, identitySource, this)

        coVerify(exactly = 1) { synchronizer.synchronizeAll() }
        observer?.cancel()
        Unit
    }

    @Test
    fun `failed recovery skips installed library synchronization`() = runBlocking {
        val operations = mockk<GameLibraryOperations>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        val identitySource = identitySource()
        coEvery { operations.recoverMigrations() } throws IllegalStateException("recovery failed")

        recoverAndStartInstalledLibrarySynchronization(operations, synchronizer, identitySource, this)

        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    @Test
    fun `pending recovery skips installed library synchronization`() = runBlocking {
        val operations = mockk<GameLibraryOperations>()
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        coEvery { operations.recoverMigrations() } returns LibraryFileRecovery(1, listOf("pending"))

        recoverAndStartInstalledLibrarySynchronization(operations, synchronizer, identitySource(), this)

        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    private fun identitySource(): InstalledCatalogIdentitySource = mockk<InstalledCatalogIdentitySource>().also { source ->
        every { source.observeIdentitySignatures() } returns flowOf(
            InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList()),
        )
    }
}
