package app.gamenative.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationRepositoryTest {

    @Test
    fun parserAcceptsSingleRecommendation() {
        val recommendation = RecommendationRepository.parseRecommendation(recommendationJson(id = 11))

        assertEquals("11", recommendation?.id)
        assertEquals("Game 11", recommendation?.name)
    }

    @Test
    fun parserUsesFirstRecommendationFromArray() {
        val recommendation = RecommendationRepository.parseRecommendation(
            "[${recommendationJson(id = 21)},${recommendationJson(id = 22)}]",
        )

        assertEquals("21", recommendation?.id)
    }

    @Test
    fun parserReturnsNullForEmptyArray() {
        assertNull(RecommendationRepository.parseRecommendation("[]"))
    }

    @Test
    fun missingCacheRequiresBackgroundRefresh() {
        assertTrue(
            shouldRefreshRecommendationCache(
                cachedRecommendation = null,
                cacheTimestampMs = 0L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun freshCacheDoesNotRequireRefresh() {
        assertFalse(
            shouldRefreshRecommendationCache(
                cachedRecommendation = recommendation(id = 31),
                cacheTimestampMs = 1_000L,
                nowMs = 1_000L + RecommendationRepository.CACHE_TTL_MS,
            ),
        )
    }

    @Test
    fun expiredOrFutureDatedCacheRequiresRefresh() {
        val recommendation = recommendation(id = 41)

        assertTrue(
            shouldRefreshRecommendationCache(
                cachedRecommendation = recommendation,
                cacheTimestampMs = 1_000L,
                nowMs = 1_001L + RecommendationRepository.CACHE_TTL_MS,
            ),
        )
        assertTrue(
            shouldRefreshRecommendationCache(
                cachedRecommendation = recommendation,
                cacheTimestampMs = 2_000L,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun freshCacheDoesNotExecuteBackgroundRefresh() = runTest {
        var refreshCount = 0

        refreshRecommendationCacheIfStale(
            cachedRecommendation = recommendation(id = 51),
            cacheTimestampMs = 1_000L,
            nowMs = 2_000L,
        ) {
            refreshCount++
        }

        assertEquals(0, refreshCount)
    }

    @Test
    fun missingCacheExecutesOneBackgroundRefresh() = runTest {
        var refreshCount = 0

        refreshRecommendationCacheIfStale(
            cachedRecommendation = null,
            cacheTimestampMs = 0L,
            nowMs = 2_000L,
        ) {
            refreshCount++
        }

        assertEquals(1, refreshCount)
    }

    private fun recommendation(id: Int) = RecommendationRepository.parseRecommendation(
        recommendationJson(id),
    )!!

    private fun recommendationJson(id: Int): String =
        """{"id":"$id","name":"Game $id","developer":"Developer","description":"Description","heroImageUrl":"hero","capsuleImageUrl":"capsule","affiliateUrl":"https://example.com"}"""
}
