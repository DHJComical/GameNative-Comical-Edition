package app.gamenative.ui.model

import app.gamenative.data.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AddGameCatalogStateTest {
    @Test
    fun `catalog emissions update items without clearing refresh failure`() {
        val failed = AddGameCatalogState(
            isOpen = true,
            selectedStore = AddGameStore.GOG,
            isLoading = false,
            error = "network failed",
        )
        val item = LibraryItem(index = 0, appId = "GOG_1", name = "Game")

        val updated = failed.withCatalogItems(AddGameStore.GOG, listOf(item))

        assertEquals(listOf(item), updated.items)
        assertEquals("network failed", updated.error)
        assertFalse(updated.isLoading)
    }

    @Test
    fun `catalog emissions do not own loading state`() {
        val loading = AddGameCatalogState(
            isOpen = true,
            selectedStore = AddGameStore.EPIC,
            isLoading = true,
        )

        val updated = loading.withCatalogItems(AddGameStore.EPIC, emptyList())

        assertEquals(loading, updated)
    }

    @Test
    fun `refresh completion only changes the selected open store`() {
        val state = AddGameCatalogState(
            isOpen = true,
            selectedStore = AddGameStore.AMAZON,
            isLoading = true,
        )

        assertEquals(state, state.withRefreshSuccess(AddGameStore.GOG))
        assertEquals(state, state.withRefreshFailure(AddGameStore.GOG, "failed"))
        assertFalse(state.withRefreshSuccess(AddGameStore.AMAZON).isLoading)
        assertEquals("failed", state.withRefreshFailure(AddGameStore.AMAZON, "failed").error)
    }
}
