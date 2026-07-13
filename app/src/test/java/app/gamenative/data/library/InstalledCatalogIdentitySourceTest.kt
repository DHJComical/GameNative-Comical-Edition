package app.gamenative.data.library

import app.gamenative.data.AmazonGame
import app.gamenative.data.ConfigInfo
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamCatalogInstallIdentity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InstalledCatalogIdentitySourceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `installation state changes keep signature stable while identity changes alter it`() = runTest {
        val gogGames = MutableStateFlow(listOf(GOGGame(id = "gog-id", title = "Game")))
        val epicGames = MutableStateFlow(listOf(EpicGame(catalogId = "epic-id", appName = "EpicApp")))
        val amazonGames = MutableStateFlow(listOf(AmazonGame(productId = "amazon-id", title = "Amazon Game")))
        val steamGames = MutableStateFlow(
            listOf(
                SteamCatalogInstallIdentity(
                    appId = 10,
                    name = "CurrentDirectory",
                    installDir = "HistoricalDirectory",
                    config = ConfigInfo(installDir = "CurrentDirectory"),
                ),
            ),
        )
        val gogDao = mockk<GOGGameDao>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        val steamDao = mockk<SteamAppDao>()
        every { gogDao.getAll() } returns gogGames
        every { epicDao.getAll() } returns epicGames
        every { amazonDao.getAll() } returns amazonGames
        every { steamDao.observeCatalogInstallIdentities() } returns steamGames
        val source = InstalledCatalogIdentitySourceImpl(steamDao, gogDao, epicDao, amazonDao)
        val emissions = async { source.observeIdentitySignatures().take(4).toList() }
        advanceUntilIdle()

        gogGames.value = listOf(gogGames.value.single().copy(isInstalled = true, installPath = "/installed"))
        advanceUntilIdle()
        gogGames.value = listOf(gogGames.value.single().copy(title = "Renamed Game"))
        advanceUntilIdle()
        steamGames.value = listOf(
            steamGames.value.single().copy(config = ConfigInfo(installDir = "ReplacementDirectory")),
        )
        advanceUntilIdle()
        val signatures = emissions.await()

        assertEquals(signatures[0], signatures[1])
        assertNotEquals(signatures[1], signatures[2])
        assertNotEquals(signatures[2], signatures[3])
        assertEquals(
            listOf(
                InstalledCatalogIdentity("10", "CurrentDirectory"),
                InstalledCatalogIdentity("10", "HistoricalDirectory"),
            ),
            signatures[0].steam,
        )
    }
}
