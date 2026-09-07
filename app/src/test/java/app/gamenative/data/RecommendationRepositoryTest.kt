package app.gamenative.data

import app.gamenative.data.gog.GogRecCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class RecommendationRepositoryTest {

    @Test
    fun heroPoolTracksLatestCards() {
        RecommendationRepository.setRecommendationPool(emptyList())
        assertEquals(emptyList<GogRecCard>(), RecommendationRepository.getRecommendationPool())
    }

    @Test
    fun currentHeroRecommendationRoundTrips() {
        val context = RuntimeEnvironment.getApplication()
        val rec = RecommendedGame(
            id = "11",
            name = "Game 11",
            developer = "Developer",
            description = "Description",
            heroImageUrl = "hero",
            capsuleImageUrl = "capsule",
            affiliateUrl = "https://example.com",
        )
        RecommendationRepository.setCurrentHeroRecommendation(rec)
        assertEquals("11", RecommendationRepository.getCurrentHeroRecommendation()?.id)
        RecommendationRepository.setCurrentHeroRecommendation(null)
        assertNull(RecommendationRepository.getCachedFeatured())
        context.cacheDir.mkdirs()
    }
}
