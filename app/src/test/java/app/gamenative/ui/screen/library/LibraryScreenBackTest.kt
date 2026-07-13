package app.gamenative.ui.screen.library

import android.view.KeyEvent
import app.gamenative.data.LibraryItem
import app.gamenative.ui.model.AddGameCatalogState
import app.gamenative.ui.model.AddGameStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenBackTest {

    @Test
    fun rootLibraryHandlesBackAsExit() {
        assertTrue(libraryRootBackEnabled())
    }

    @Test
    fun detailConsumesBackBeforeRootExit() {
        assertFalse(libraryRootBackEnabled(hasDetail = true))
    }

    @Test
    fun overlaysConsumeBackBeforeRootExit() {
        assertFalse(libraryRootBackEnabled(isAddGameCatalogOpen = true))
        assertFalse(libraryRootBackEnabled(isSearching = true))
        assertFalse(libraryRootBackEnabled(isSystemMenuOpen = true))
        assertFalse(libraryRootBackEnabled(isSystemMenuNavigationTransitionInProgress = true))
        assertFalse(libraryRootBackEnabled(isOptionsPanelOpen = true))
        assertFalse(libraryRootBackEnabled(isDialogOpen = true))
    }

    @Test
    fun retainedLibraryDoesNotHandleBack() {
        assertFalse(libraryRootBackEnabled(isActive = false))
    }

    @Test
    fun inactiveLibraryIgnoresPreviewKeys() {
        assertEquals(
            LibraryPreviewKeyAction.IGNORE,
            libraryPreviewKeyAction(
                isActive = false,
                isSystemMenuNavigationTransitionInProgress = false,
                keyAction = KeyEvent.ACTION_DOWN,
            ),
        )
    }

    @Test
    fun systemMenuNavigationTransitionConsumesKeyDownWithoutProcessingIt() {
        assertEquals(
            LibraryPreviewKeyAction.CONSUME,
            libraryPreviewKeyAction(
                isActive = true,
                isSystemMenuNavigationTransitionInProgress = true,
                keyAction = KeyEvent.ACTION_DOWN,
            ),
        )
        assertEquals(
            LibraryPreviewKeyAction.IGNORE,
            libraryPreviewKeyAction(
                isActive = true,
                isSystemMenuNavigationTransitionInProgress = true,
                keyAction = KeyEvent.ACTION_UP,
            ),
        )
    }

    @Test
    fun activeLibraryProcessesPreviewKeysOutsideNavigationTransition() {
        assertEquals(
            LibraryPreviewKeyAction.PROCESS,
            libraryPreviewKeyAction(
                isActive = true,
                isSystemMenuNavigationTransitionInProgress = false,
                keyAction = KeyEvent.ACTION_DOWN,
            ),
        )
    }

    @Test
    fun ordinaryOverlayDismissalRestoresActiveLibraryFocus() {
        assertTrue(
            shouldRestoreLibraryFocus(
                isActive = true,
                isSystemMenuNavigationTransitionInProgress = false,
                systemMenuJustClosed = true,
                optionsPanelJustClosed = false,
                isSearching = false,
            ),
        )
    }

    @Test
    fun navigationTransitionDoesNotRestoreLibraryFocus() {
        assertFalse(
            shouldRestoreLibraryFocus(
                isActive = true,
                isSystemMenuNavigationTransitionInProgress = true,
                systemMenuJustClosed = true,
                optionsPanelJustClosed = false,
                isSearching = false,
            ),
        )
        assertFalse(
            shouldRestoreLibraryFocus(
                isActive = false,
                isSystemMenuNavigationTransitionInProgress = false,
                systemMenuJustClosed = true,
                optionsPanelJustClosed = false,
                isSearching = false,
            ),
        )
    }

    @Test
    fun catalogDetailCarriesItsItemWithoutDependingOnTheInstalledLibrary() {
        val catalogItem = LibraryItem(appId = "STEAM_10", name = "Catalog game")
        val installedItems = emptyList<LibraryItem>()

        val detail = LibraryDetailState(
            item = catalogItem,
            origin = LibraryDetailOrigin.ADD_CATALOG,
        )

        assertFalse(installedItems.contains(catalogItem))
        assertSame(catalogItem, detail.item)
        assertEquals(LibraryDetailOrigin.ADD_CATALOG, detail.origin)
    }

    @Test
    fun catalogSheetReturnsWithItsExistingStoreAfterDetailCloses() {
        val catalogItem = LibraryItem(appId = "EPIC_10", name = "Catalog game")
        val catalogState = AddGameCatalogState(
            isOpen = true,
            selectedStore = AddGameStore.EPIC,
            items = listOf(catalogItem),
        )
        val catalogDetail = LibraryDetailState(
            item = catalogItem,
            origin = LibraryDetailOrigin.ADD_CATALOG,
        )

        assertFalse(shouldShowAddGameCatalog(catalogState.isOpen, catalogDetail))
        assertTrue(shouldShowAddGameCatalog(catalogState.isOpen, detailState = null))
        assertEquals(AddGameStore.EPIC, catalogState.selectedStore)
        assertEquals(listOf(catalogItem), catalogState.items)
    }

    private fun libraryRootBackEnabled(
        isActive: Boolean = true,
        hasDetail: Boolean = false,
        isAddGameCatalogOpen: Boolean = false,
        isSearching: Boolean = false,
        isSystemMenuOpen: Boolean = false,
        isSystemMenuNavigationTransitionInProgress: Boolean = false,
        isOptionsPanelOpen: Boolean = false,
        isDialogOpen: Boolean = false,
    ): Boolean = isLibraryRootBackEnabled(
        isActive = isActive,
        hasDetail = hasDetail,
        isAddGameCatalogOpen = isAddGameCatalogOpen,
        isSearching = isSearching,
        isSystemMenuOpen = isSystemMenuOpen,
        isSystemMenuNavigationTransitionInProgress = isSystemMenuNavigationTransitionInProgress,
        isOptionsPanelOpen = isOptionsPanelOpen,
        isDialogOpen = isDialogOpen,
    )
}
