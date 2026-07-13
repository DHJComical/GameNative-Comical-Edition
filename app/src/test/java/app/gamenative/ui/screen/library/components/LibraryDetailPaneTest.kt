package app.gamenative.ui.screen.library.components

import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendedGame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryDetailPaneTest {

    @Test
    fun recommendationUsesTheSnapshotCarriedByTheSelectedCard() {
        val snapshot = recommendation("session-game")
        val item = LibraryItem(
            appId = "RECOMMENDED_session-game",
            isRecommended = true,
            recommendedGameId = snapshot.id,
            recommendedGame = snapshot,
        )

        assertSame(snapshot, recommendationSnapshotFor(item))
    }

    @Test
    fun mismatchedSnapshotIsRejected() {
        val item = LibraryItem(
            appId = "RECOMMENDED_session-game",
            isRecommended = true,
            recommendedGameId = "session-game",
            recommendedGame = recommendation("refreshed-game"),
        )

        assertNull(recommendationSnapshotFor(item))
    }

    @Test
    fun normalLibraryItemDoesNotResolveRecommendationDetails() {
        val item = LibraryItem(
            appId = "STEAM_1",
            recommendedGame = recommendation("unexpected"),
        )

        assertNull(recommendationSnapshotFor(item))
    }

    private fun recommendation(id: String) = RecommendedGame(
        id = id,
        name = "Game $id",
        developer = "Developer",
        description = "Description",
        heroImageUrl = "hero",
        capsuleImageUrl = "capsule",
        affiliateUrl = "https://example.com",
    )
}
