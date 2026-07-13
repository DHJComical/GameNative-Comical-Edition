package app.gamenative.ui.screen.library.components

import app.gamenative.data.LibraryItem
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryCarouselPaneTest {

    @Test
    fun completedViewportReset_immediatelySelectsFirstBackdrop() {
        val previous = LibraryItem(appId = "STEAM_previous")
        val first = LibraryItem(appId = "STEAM_first")

        val backdrop = backdropAfterViewportReset(
            completedResetToken = 1L,
            items = listOf(first),
            currentBackdrop = previous,
        )

        assertSame(first, backdrop)
    }

    @Test
    fun absentViewportReset_keepsSettledBackdrop() {
        val previous = LibraryItem(appId = "STEAM_previous")
        val first = LibraryItem(appId = "STEAM_first")

        val backdrop = backdropAfterViewportReset(
            completedResetToken = 0L,
            items = listOf(first),
            currentBackdrop = previous,
        )

        assertSame(previous, backdrop)
    }
}
