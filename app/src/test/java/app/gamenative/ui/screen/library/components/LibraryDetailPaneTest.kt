package app.gamenative.ui.screen.library.components

import app.gamenative.data.LibraryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDetailPaneTest {

    @Test
    fun featuredItemIsMarkedFeatured() {
        val item = LibraryItem(
            appId = "FEATURED_mock",
            isRecommended = true,
            isFeatured = true,
            recommendedGameId = "mock",
        )

        assertTrue(item.isFeatured)
        assertTrue(item.isRecommended)
    }

    @Test
    fun teaserItemCarriesLoadingState() {
        val item = LibraryItem(
            appId = "RECOMMENDED_1",
            isRecommended = true,
            isRecTeaser = true,
            isRecLoading = true,
        )

        assertTrue(item.isRecTeaser)
        assertTrue(item.isRecLoading)
        assertFalse(item.isFeatured)
    }

    @Test
    fun normalLibraryItemHasNoRecommendationFlags() {
        val item = LibraryItem(appId = "STEAM_1")

        assertFalse(item.isRecommended)
        assertFalse(item.isFeatured)
        assertFalse(item.isRecTeaser)
        assertFalse(item.isRecLoading)
    }
}
