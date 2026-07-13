package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPaginationTest {

    @Test
    fun displayedTotal_matchesInstalledGameCount() {
        assertEquals(21, displayedLibraryTotal(gameCount = 21))
    }
}
