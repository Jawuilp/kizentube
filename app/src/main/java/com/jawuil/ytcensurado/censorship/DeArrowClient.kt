package com.jawuil.ytcensurado.censorship

import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.VideoItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DeArrowBranding(
    val cleanTitle: String? = null,
    val cleanThumbnailUrl: String? = null
)

object DeArrowClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(6)
    private val cache = ConcurrentHashMap<String, DeArrowBranding>()

    /**
     * Enriches approved videos with DeArrow crowd-sourced calm titles and non-clickbait
     * video frame thumbnails (reducing visual overstimulation and clickbait).
     */
    fun applyDeArrowBatch(videos: List<VideoItem>): List<VideoItem> {
        if (!ConfigManager.deArrowEnabled || videos.isEmpty()) return videos

        val toFetch = videos.map { it.videoId }.distinct().filter { !cache.containsKey(it) }.take(18)
        if (toFetch.isNotEmpty()) {
            val tasks = toFetch.map { id ->
                Callable {
                    cache[id] = fetchSingleBranding(id)
                }
            }
            try {
                executor.invokeAll(tasks, 2800, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }

        return videos.map { video ->
            val branding = cache[video.videoId]
            if (branding == null) {
                video
            } else {
                video.copy(
                    title = branding.cleanTitle?.takeIf { it.isNotBlank() } ?: video.title,
                    thumbnailUrl = branding.cleanThumbnailUrl?.takeIf { it.isNotBlank() } ?: video.thumbnailUrl
                )
            }
        }
    }

    private fun fetchSingleBranding(videoId: String): DeArrowBranding {
        return try {
            val req = Request.Builder()
                .url("https://sponsor.ajay.app/api/branding?videoID=$videoId")
                .header("User-Agent", "YTCensuradoTV/1.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return DeArrowBranding()
                val body = resp.body?.string() ?: return DeArrowBranding()
                val json = JSONObject(body)

                var cleanTitle: String? = null
                val titles = json.optJSONArray("titles")
                if (titles != null && titles.length() > 0) {
                    val top = titles.optJSONObject(0)
                    val votes = top?.optInt("votes", 0) ?: 0
                    val locked = top?.optBoolean("locked", false) ?: false
                    if (votes >= 0 || locked) {
                        val candidate = top?.optString("title", "")?.trim() ?: ""
                        // Remove DeArrow >formatting markers if any
                        if (candidate.isNotBlank()) {
                            cleanTitle = candidate.replace(">", "").trim()
                        }
                    }
                }

                var cleanThumb: String? = null
                val thumbs = json.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) {
                    val topThumb = thumbs.optJSONObject(0)
                    val original = topThumb?.optBoolean("original", false) ?: false
                    val timestamp = topThumb?.optDouble("timestamp", -1.0) ?: -1.0
                    if (!original && timestamp >= 0.0) {
                        cleanThumb = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$timestamp"
                    }
                }

                DeArrowBranding(cleanTitle = cleanTitle, cleanThumbnailUrl = cleanThumb)
            }
        } catch (_: Exception) {
            DeArrowBranding()
        }
    }
}
