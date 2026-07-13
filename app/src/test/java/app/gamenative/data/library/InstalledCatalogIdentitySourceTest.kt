package app.gamenative.data.library

import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
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
        val gogDao = mockk<GOGGameDao>()
        val epicDao = mockk<EpicGameDao>()
        val amazonDao = mockk<AmazonGameDao>()
        every { gogDao.getAll() } returns gogGames
        every { epicDao.getAll() } returns epicGames
        every { amazonDao.getAll() } returns amazonGames
        val source = InstalledCatalogIdentitySourceImpl(gogDao, epicDao, amazonDao)
        val emissions = async { source.observeIdentitySignatures().take(3).toList() }
        advanceUntilIdle()

        gogGames.value = listOf(gogGames.value.single().copy(isInstalled = true, installPath = "/installed"))
        advanceUntilIdle()
        gogGames.value = listOf(gogGames.value.single().copy(title = "Renamed Game"))
        advanceUntilIdle()
        val signatures = emissions.await()

        assertEquals(signatures[0], signatures[1])
        assertNotEquals(signatures[1], signatures[2])
    }
}
