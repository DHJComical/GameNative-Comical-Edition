package app.gamenative.ui.screen.library.components

import app.gamenative.ui.enums.AppFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOptionsPanelTest {
    @Test
    fun `installed library status filters omit redundant installed and expired options`() {
        assertFalse(installedLibraryStatusFilters.contains(AppFilter.INSTALLED))
        assertFalse(installedLibraryStatusFilters.contains(AppFilter.EXPIRED))
        assertTrue(installedLibraryStatusFilters.contains(AppFilter.SHARED))
        assertTrue(installedLibraryStatusFilters.contains(AppFilter.COMPATIBLE))
    }
}
