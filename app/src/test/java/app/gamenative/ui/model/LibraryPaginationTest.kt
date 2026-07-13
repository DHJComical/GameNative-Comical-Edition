package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPaginationTest {

    @Test
    fun displayedTotal_includesRecommendationOutsideGamePagination() {
        assertEquals(22, displayedLibraryTotal(gameCount = 21, includesRecommendation = true))
    }

    @Test
    fun displayedTotal_matchesGameCountWithoutRecommendation() {
        assertEquals(21, displayedLibraryTotal(gameCount = 21, includesRecommendation = false))
    }
}
