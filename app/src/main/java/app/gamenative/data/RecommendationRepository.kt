package app.gamenative.data

import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

object RecommendationRepository {

    private const val API_URL = "https://api.gamenative.app/api/games/recommendation"
    internal const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Captures the recommendation for the current library session without performing I/O that can
     * reorder the library after it becomes visible.
     */
    fun getCachedRecommendation(): RecommendedGame? {
        val cached = PrefManager.recommendationCacheJson
        if (cached.isEmpty()) return null

        return try {
            parseRecommendation(cached)
        } catch (e: SerializationException) {
            Timber.tag("RecommendationRepo").e(e, "Failed to parse cached recommendation")
            null
        } catch (e: IllegalArgumentException) {
            Timber.tag("RecommendationRepo").e(e, "Invalid cached recommendation")
            null
        }
    }

    /**
     * Refreshes an expired recommendation cache for a future library session. The fetched value is
     * deliberately not returned so callers cannot replace the current session's stable snapshot.
     */
    suspend fun refreshCacheIfStale(nowMs: Long = System.currentTimeMillis()) {
        val cached = getCachedRecommendation()
        refreshRecommendationCacheIfStale(
            cachedRecommendation = cached,
            cacheTimestampMs = PrefManager.recommendationCacheTimestamp,
            nowMs = nowMs,
        ) {
            withContext(Dispatchers.IO) {
                fetchRemoteToCache(nowMs)
            }
        }
    }

    private fun fetchRemoteToCache(nowMs: Long) {
        try {
            val mediaType = "application/json".toMediaType()
            val body = "{}".toRequestBody(mediaType)
            val request = Request.Builder()
                .url(API_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("RecommendationRepo").w(
                        "Recommendation refresh failed with HTTP %d",
                        response.code,
                    )
                    return
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    Timber.tag("RecommendationRepo").w("Recommendation refresh returned an empty response")
                    return
                }

                try {
                    val recommendation = parseRecommendation(responseBody)
                    if (recommendation == null) {
                        Timber.tag("RecommendationRepo").w("Recommendation refresh returned an empty list")
                        return
                    }
                } catch (e: SerializationException) {
                    Timber.tag("RecommendationRepo").e(e, "Recommendation refresh returned invalid JSON")
                    return
                } catch (e: IllegalArgumentException) {
                    Timber.tag("RecommendationRepo").e(e, "Recommendation refresh returned invalid data")
                    return
                }

                PrefManager.recommendationCacheJson = responseBody
                PrefManager.recommendationCacheTimestamp = nowMs
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("RecommendationRepo").e(e, "Remote recommendation refresh failed")
        }
    }

    internal fun parseRecommendation(body: String): RecommendedGame? {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("[")) {
            json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
        } else {
            json.decodeFromString<RecommendedGame>(body)
        }
    }
}

internal fun shouldRefreshRecommendationCache(
    cachedRecommendation: RecommendedGame?,
    cacheTimestampMs: Long,
    nowMs: Long,
): Boolean {
    if (cachedRecommendation == null) return true
    val cacheAgeMs = nowMs - cacheTimestampMs
    return cacheAgeMs !in 0..RecommendationRepository.CACHE_TTL_MS
}

internal suspend fun refreshRecommendationCacheIfStale(
    cachedRecommendation: RecommendedGame?,
    cacheTimestampMs: Long,
    nowMs: Long,
    refreshCache: suspend () -> Unit,
) {
    if (shouldRefreshRecommendationCache(cachedRecommendation, cacheTimestampMs, nowMs)) {
        refreshCache()
    }
}
