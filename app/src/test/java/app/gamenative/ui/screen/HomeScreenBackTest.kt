package app.gamenative.ui.screen

import app.gamenative.ui.enums.HomeDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenBackTest {

    @Test
    fun libraryBackIsOwnedByLibraryScreen() {
        assertEquals(
            HomeBackAction.NOT_HANDLED,
            homeBackAction(HomeDestination.Library, gameLibraryOperationActive = false),
        )
    }

    @Test
    fun layeredPagesReturnToLibrary() {
        listOf(
            HomeDestination.Downloads,
            HomeDestination.Settings,
            HomeDestination.Storage,
        ).forEach { destination ->
            assertEquals(
                HomeBackAction.NAVIGATE_LIBRARY,
                homeBackAction(destination, gameLibraryOperationActive = false),
            )
        }
    }

    @Test
    fun gameLibrariesReturnsToStorageUnlessAnOperationIsActive() {
        assertEquals(
            HomeBackAction.NAVIGATE_STORAGE,
            homeBackAction(HomeDestination.GameLibraries, gameLibraryOperationActive = false),
        )
        assertEquals(
            HomeBackAction.CONSUME,
            homeBackAction(HomeDestination.GameLibraries, gameLibraryOperationActive = true),
        )
    }
}
