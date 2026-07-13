package app.gamenative.ui.screen.library

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDetailNavigationStateTest {
    @Test
    fun `catalog remains open but hidden while its detail is active`() {
        val detail = LibraryDetailState(item = item(), origin = LibraryDetailOrigin.ADD_CATALOG)

        assertFalse(shouldShowAddGameCatalog(catalogOpen = true, detailState = detail))
        assertTrue(shouldShowAddGameCatalog(catalogOpen = true, detailState = null))
    }

    @Test
    fun `closed catalog stays hidden before and after main detail`() {
        val detail = LibraryDetailState(item = item(), origin = LibraryDetailOrigin.MAIN)

        assertFalse(shouldShowAddGameCatalog(catalogOpen = false, detailState = detail))
        assertFalse(shouldShowAddGameCatalog(catalogOpen = false, detailState = null))
    }

    private fun item() = LibraryItem(
        appId = "STEAM_10",
        name = "Test game",
        iconHash = "",
        gameSource = GameSource.STEAM,
    )
}
