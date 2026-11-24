package com.jawuil.ytcensurado.innertube

import com.jawuil.ytcensurado.censorship.CensorshipEngine
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.AudioTrackData
import com.jawuil.ytcensurado.model.ChannelEntry
import com.jawuil.ytcensurado.model.StreamPlaybackData
import com.jawuil.ytcensurado.model.VideoItem
import com.jawuil.ytcensurado.model.VideoQualityTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object InnerTubeClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val ANDROID_CLIENT_VERSION = "20.03.35"
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/$ANDROID_CLIENT_VERSION (Linux; U; Android 14) gzip"

    private const val IOS_CLIENT_VERSION = "20.03.02"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/$IOS_CLIENT_VERSION (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

    const val TV_OAUTH_CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val TV_OAUTH_SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"

    @Volatile
    private var cachedVisitorData: String = ""
    @Volatile
    private var visitorDataTimestamp: Long = 0L

    private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Fetches and caches a fresh YouTube VISITOR_DATA token required by /youtubei/v1/player in 2026.
     */
    @Synchronized
    fun ensureVisitorData(): String {
        val now = System.currentTimeMillis()
        if (cachedVisitorData.isNotBlank() && (now - visitorDataTimestamp) < 3600_000L) {
            return cachedVisitorData
        }
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", WEB_USER_AGENT)
                .header("Accept-Language", "es-ES,es;q=0.9")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                val match = VISITOR_DATA_REGEX.find(html)
                if (match != null) {
                    cachedVisitorData = match.groupValues[1]
                    visitorDataTimestamp = now
                }
            }
        } catch (_: Exception) {}
        return cachedVisitorData
    }

    private fun webContextJson(): JSONObject {
        val vData = cachedVisitorData
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20250115.00.00")
                put("hl", "es")
                put("gl", "ES")
                if (vData.isNotBlank()) {
                    put("visitorData", vData)
                }
            })
        }
    }

    /**
     * Generates YouTube's required SAPISIDHASH Authorization header from the user's logged-in cookies.
     */
    private fun buildSapisidHashHeader(cookieString: String): String? {
        if (cookieString.isBlank()) return null
        val sapisid = extractCookieValue(cookieString, "SAPISID")
            ?: extractCookieValue(cookieString, "__Secure-3PAPISID")
            ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val origin = "https://www.youtube.com"
        val input = "$timestamp $sapisid $origin"
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$sha1"
    }

    private fun extractCookieValue(cookieString: String, key: String): String? {
        val parts = cookieString.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$key=")) {
                return trimmed.substringAfter("$key=").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private val feedExecutor = Executors.newFixedThreadPool(6)

    private fun runParallelFetches(tasks: List<Callable<List<VideoItem>>>): List<VideoItem> {
        if (tasks.isEmpty()) return emptyList()
        return try {
            val futures = feedExecutor.invokeAll(tasks, 8, TimeUnit.SECONDS)
            val combined = mutableListOf<VideoItem>()
            for (f in futures) {
                if (f.isDone && !f.isCancelled) {
                    try {
                        combined.addAll(f.get())
                    } catch (_: Exception) {}
                }
            }
            combined
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Loads the Home Feed in parallel and synchronously purges all Shorts & Brainrot via CensorshipEngine
     * BEFORE returning a single item to the UI.
     */
    fun fetchHomeFeed(): List<VideoItem> {
        val tasks = mutableListOf<Callable<List<VideoItem>>>()

        // 1. Prioritize whitelisted channels (up to 6 in parallel)
        for (channel in ConfigManager.whitelistedChannels.take(6)) {
            val query = channel.handle.ifBlank { channel.name.ifBlank { channel.id } }
            tasks.add(Callable { fetchRawSearch(query, maxItems = 8) })
        }

        // 2. If not in strict whitelist-only mode (or if whitelist is still empty),
        // fetch from logged-in account browse + curated safe home topics in parallel
        if (!ConfigManager.whitelistOnlyMode || ConfigManager.whitelistedChannels.isEmpty()) {
            if (ConfigManager.isAccountConnected()) {
                tasks.add(Callable { fetchRawBrowse("FEwhat_to_watch") })
            }
            if (ConfigManager.whitelistedChannels.size < 3) {
                for (topic in ConfigManager.safeHomeTopics.take(3)) {
                    tasks.add(Callable { fetchRawSearch(topic, maxItems = 10) })
                }
            }
        }

        val rawCandidates = runParallelFetches(tasks)
        val filtered = CensorshipEngine.filterVideosPreRender(rawCandidates)
        if (filtered.isNotEmpty()) {
            ConfigManager.cachedHomeFeed = filtered
        }
        return filtered
    }

    /**
     * Loads the Subscriptions / Approved Whitelist Channels Feed in parallel, 100% filtered pre-render.
     */
    fun fetchSubscriptionsFeed(): List<VideoItem> {
        val tasks = mutableListOf<Callable<List<VideoItem>>>()

        // If subscriptions are locked in strict whitelist mode, ONLY obey adult-approved channels
        if (ConfigManager.isAccountConnected() && !(ConfigManager.lockSubscriptions && ConfigManager.whitelistOnlyMode)) {
            tasks.add(Callable { fetchRawBrowse("FEsubscriptions") })
        }

        for (channel in ConfigManager.whitelistedChannels.take(8)) {
            val query = channel.handle.ifBlank { channel.name.ifBlank { channel.id } }
            tasks.add(Callable { fetchRawSearch(query, maxItems = 10) })
        }

        val rawCandidates = runParallelFetches(tasks)
        val filtered = CensorshipEngine.filterVideosPreRender(rawCandidates)
        if (filtered.isNotEmpty()) {
            ConfigManager.cachedSubsFeed = filtered
        }
        return filtered
    }

    /**
     * Fetches filtered videos for a specific followed/whitelisted channel.
     */
    fun fetchChannelVideos(channel: ChannelEntry): List<VideoItem> {
        val query = channel.handle.ifBlank { channel.name.ifBlank { channel.id } }
        if (query.isBlank()) return emptyList()
        val raw = fetchRawSearch(query, maxItems = 25)
        return CensorshipEngine.filterVideosPreRender(raw)
    }

    /**
     * Imports subscribed channels from the logged-in YouTube account directly into the Whitelist.
     * Returns the number of newly imported channels.
     */
    fun importAccountSubscriptionsToWhitelist(): Int {
        if (!ConfigManager.isAccountConnected()) return 0
        var imported = 0
        try {
            // Try both FEchannels (channel list) and FEsubscriptions (videos from subscriptions)
            for (browseId in listOf("FEchannels", "FEsubscriptions", "FEwhat_to_watch")) {
                val payload = JSONObject().apply {
                    put("context", webContextJson())
                    put("browseId", browseId)
                }
                val json = executeInnerTubePost("browse", payload) ?: continue
                val channels = mutableListOf<Triple<String, String, String>>()
                extractChannelsRecursively(json, channels)
                for ((id, name, handle) in channels) {
                    if (name.isNotBlank() &&
                        !ConfigManager.isChannelBlocked(id, handle, name) &&
                        !ConfigManager.isChannelWhitelisted(id, handle, name)
                    ) {
                        ConfigManager.whitelistChannel(id.ifBlank { handle.ifBlank { name } }, name, handle)
                        imported++
                    }
                }
                if (imported > 0) break
            }
        } catch (_: Exception) {}
        return imported
    }

    private fun extractChannelsRecursively(node: Any?, out: MutableList<Triple<String, String, String>>) {
        if (node == null || out.size >= 80) return
        when (node) {
            is JSONObject -> {
                val cr = node.optJSONObject("channelRenderer")
                    ?: node.optJSONObject("gridChannelRenderer")
                if (cr != null) {
                    val channelId = cr.optString("channelId", "")
                    val title = extractText(cr.optJSONObject("title"))
                    val handle = cr.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("canonicalBaseUrl", "")
                        ?.removePrefix("/") ?: ""
                    if (title.isNotBlank()) {
                        out.add(Triple(channelId, title, handle))
                    }
                    return
                }
                val vr = node.optJSONObject("videoRenderer") ?: node.optJSONObject("gridVideoRenderer")
                if (vr != null) {
                    parseVideoRenderer(vr)?.let { v ->
                        if (v.channelName.isNotBlank()) {
                            out.add(Triple(v.channelId, v.channelName, v.channelHandle))
                        }
                    }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractChannelsRecursively(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractChannelsRecursively(node.opt(i), out)
                }
            }
        }
    }

    /**
     * Searches YouTube and synchronously passes every result through CensorshipEngine before returning.
     */
    fun searchFiltered(query: String): List<VideoItem> {
        if (query.isBlank()) return emptyList()
        val raw = fetchRawSearch(query.trim(), maxItems = 35)
        return CensorshipEngine.filterVideosPreRender(raw)
    }

    /**
     * Fetches related "Up Next" videos for a playing video and applies both the CensorshipEngine
     * and the Chained Autoplay Firewall.
     */
    fun fetchRelatedAndAutoplay(currentVideo: VideoItem): Pair<List<VideoItem>, VideoItem?> {
        val raw = mutableListOf<VideoItem>()
        raw.addAll(fetchRawNext(currentVideo.videoId))

        if (currentVideo.channelName.isNotBlank()) {
            raw.addAll(fetchRawSearch(currentVideo.channelName, maxItems = 12))
        }

        return CensorshipEngine.filterRelatedAndAutoplay(currentVideo, raw)
    }

    /**
     * Extracts direct hardware-playable stream URLs (MP4 muxed / DASH adaptive video+audio / HLS)
     * using a valid VISITOR_DATA token and matching User-Agent for Media3 ExoPlayer.
     */
    fun fetchStreamPlaybackData(videoId: String): StreamPlaybackData? {
        val visitorData = ensureVisitorData()

        // 1. Primary: ANDROID 20.03.35 with VISITOR_DATA (returns both direct muxed MP4 + 39 adaptive formats)
        fetchPlayerWithClient(
            videoId = videoId,
            clientName = "ANDROID",
            clientVersion = ANDROID_CLIENT_VERSION,
            userAgent = ANDROID_USER_AGENT,
            visitorData = visitorData,
            androidSdkVersion = 34,
            deviceMake = "Google",
            deviceModel = "Pixel 8",
            osName = "Android",
            osVersion = "14"
        )?.let { return it }

        // 2. Fallback: IOS 20.03.02 with VISITOR_DATA
        fetchPlayerWithClient(
            videoId = videoId,
            clientName = "IOS",
            clientVersion = IOS_CLIENT_VERSION,
            userAgent = IOS_USER_AGENT,
            visitorData = visitorData,
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.2.1.22C161"
        )?.let { return it }

        // 3. If visitorData expired, force refresh once and retry ANDROID
        cachedVisitorData = ""
        val freshVisitor = ensureVisitorData()
        return fetchPlayerWithClient(
            videoId = videoId,
            clientName = "ANDROID",
            clientVersion = ANDROID_CLIENT_VERSION,
            userAgent = ANDROID_USER_AGENT,
            visitorData = freshVisitor,
            androidSdkVersion = 34,
            deviceMake = "Google",
            deviceModel = "Pixel 8",
            osName = "Android",
            osVersion = "14"
        )
    }

    private fun fetchPlayerWithClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        visitorData: String,
        androidSdkVersion: Int? = null,
        deviceMake: String = "",
        deviceModel: String = "",
        osName: String = "",
        osVersion: String = ""
    ): StreamPlaybackData? {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "es")
                put("gl", "US")
                if (deviceMake.isNotBlank()) put("deviceMake", deviceMake)
                if (deviceModel.isNotBlank()) put("deviceModel", deviceModel)
                if (osName.isNotBlank()) put("osName", osName)
                if (osVersion.isNotBlank()) put("osVersion", osVersion)
                if (androidSdkVersion != null) put("androidSdkVersion", androidSdkVersion)
                if (visitorData.isNotBlank()) put("visitorData", visitorData)
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))

            if (visitorData.isNotBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                val json = JSONObject(body)
                val playability = json.optJSONObject("playabilityStatus")?.optString("status")
                if (playability != "OK") return null

                val videoDetails = json.optJSONObject("videoDetails") ?: JSONObject()
                val title = videoDetails.optString("title", "")
                val author = videoDetails.optString("author", "")
                val channelId = videoDetails.optString("channelId", "")
                val lengthSeconds = videoDetails.optString("lengthSeconds", "0").toIntOrNull() ?: 0

                val streamingData = json.optJSONObject("streamingData") ?: return null
                val hlsUrl = streamingData.optString("hlsManifestUrl", "").takeIf { it.isNotBlank() }

                // Best muxed format (contains both video + audio in a single stream)
                var bestMuxedUrl: String? = null
                var bestMuxedHeight = -1
                val formats = streamingData.optJSONArray("formats") ?: JSONArray()
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val url = f.optString("url", "")
                    if (url.isBlank()) continue
                    val height = f.optInt("height", 0)
                    if (height >= bestMuxedHeight) {
                        bestMuxedHeight = height
                        bestMuxedUrl = url
                    }
                }

                // Parse all adaptive MP4 video quality tracks (1080p, 720p, 480p, 360p) + Audio track with initRange/indexRange
                val videoTracksByHeight = mutableMapOf<Int, VideoQualityTrack>()
                var bestAudioTrack: AudioTrackData? = null

                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val url = f.optString("url", "")
                    if (url.isBlank()) continue
                    val itag = f.optInt("itag", 0)
                    val mime = f.optString("mimeType", "")
                    val codecs = mime.substringAfter("codecs=\"", "").substringBefore("\"", "")
                    val initObj = f.optJSONObject("initRange")
                    val indexObj = f.optJSONObject("indexRange")
                    val initRange = if (initObj != null) "${initObj.optString("start", "0")}-${initObj.optString("end", "0")}" else ""
                    val indexRange = if (indexObj != null) "${indexObj.optString("start", "0")}-${indexObj.optString("end", "0")}" else ""

                    if (initRange.isBlank() || indexRange.isBlank()) continue

                    if (mime.startsWith("video/mp4") && codecs.startsWith("avc1")) {
                        val h = f.optInt("height", 0)
                        val w = f.optInt("width", 0)
                        val fps = f.optInt("fps", 30)
                        val br = f.optInt("bitrate", 0)
                        if (h in 360..1080) {
                            val label = when {
                                h >= 1080 && fps > 30 -> "1080p60 Full HD"
                                h >= 1080 -> "1080p Full HD"
                                h >= 720 && fps > 30 -> "720p60 HD"
                                h >= 720 -> "720p HD"
                                h >= 480 -> "480p"
                                else -> "${h}p"
                            }
                            val existing = videoTracksByHeight[h]
                            if (existing == null || br > existing.bitrate) {
                                videoTracksByHeight[h] = VideoQualityTrack(
                                    itag = itag,
                                    label = label,
                                    width = w,
                                    height = h,
                                    fps = fps,
                                    bitrate = br,
                                    codecs = codecs.ifBlank { "avc1.640028" },
                                    url = url,
                                    initRange = initRange,
                                    indexRange = indexRange
                                )
                            }
                        }
                    } else if (mime.startsWith("audio/mp4") && codecs.startsWith("mp4a")) {
                        val br = f.optInt("bitrate", 0)
                        val sr = f.optString("audioSampleRate", "44100").ifBlank { "44100" }
                        // Prefer standard AAC-LC (itag 140) or highest bitrate mp4a
                        if (bestAudioTrack == null || itag == 140 || (bestAudioTrack.itag != 140 && br > bestAudioTrack.bitrate)) {
                            bestAudioTrack = AudioTrackData(
                                itag = itag,
                                bitrate = br,
                                codecs = codecs.ifBlank { "mp4a.40.2" },
                                sampleRate = sr,
                                url = url,
                                initRange = initRange,
                                indexRange = indexRange
                            )
                        }
                    }
                }

                val sortedVideoTracks = videoTracksByHeight.values.sortedByDescending { it.height }

                if (bestMuxedUrl == null && hlsUrl == null && (sortedVideoTracks.isEmpty() || bestAudioTrack == null)) {
                    return null
                }

                StreamPlaybackData(
                    videoId = videoId,
                    title = title,
                    channelName = author,
                    channelId = channelId,
                    hlsUrl = hlsUrl,
                    muxedUrl = bestMuxedUrl,
                    videoTracks = sortedVideoTracks,
                    audioTrack = bestAudioTrack,
                    durationSeconds = lengthSeconds,
                    userAgent = userAgent
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchRawBrowse(browseId: String): List<VideoItem> {
        return try {
            val payload = JSONObject().apply {
                put("context", webContextJson())
                put("browseId", browseId)
            }
            val json = executeInnerTubePost("browse", payload) ?: return emptyList()
            val out = mutableListOf<VideoItem>()
            extractVideosRecursively(json, out, maxItems = 40)
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchRawSearch(query: String, maxItems: Int = 25): List<VideoItem> {
        return try {
            val payload = JSONObject().apply {
                put("context", webContextJson())
                put("query", query)
            }
            val json = executeInnerTubePost("search", payload) ?: return emptyList()
            val out = mutableListOf<VideoItem>()
            extractVideosRecursively(json, out, maxItems = maxItems)
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchRawNext(videoId: String): List<VideoItem> {
        return try {
            val payload = JSONObject().apply {
                put("context", webContextJson())
                put("videoId", videoId)
            }
            val json = executeInnerTubePost("next", payload) ?: return emptyList()
            val out = mutableListOf<VideoItem>()
            extractVideosRecursively(json, out, maxItems = 25)
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ensureValidOAuthToken(): String? {
        val refresh = ConfigManager.oauthRefreshToken.trim()
        val access = ConfigManager.oauthAccessToken.trim()
        val now = System.currentTimeMillis()
        if (access.isNotBlank() && now < ConfigManager.oauthTokenExpiry - 60_000L) {
            return access
        }
        if (refresh.isBlank()) return access.takeIf { it.isNotBlank() }

        return try {
            val form = "client_id=$TV_OAUTH_CLIENT_ID&refresh_token=${java.net.URLEncoder.encode(refresh, "UTF-8")}&grant_type=refresh_token"
            val req = Request.Builder()
                .url("https://www.youtube.com/o/oauth2/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                val json = JSONObject(body)
                val newToken = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (newToken.isNotBlank()) {
                    ConfigManager.oauthAccessToken = newToken
                    ConfigManager.oauthTokenExpiry = System.currentTimeMillis() + expiresIn * 1000L
                    ConfigManager.save()
                    newToken
                } else null
            }
        } catch (_: Exception) {
            access.takeIf { it.isNotBlank() }
        }
    }

    private fun executeInnerTubePost(endpoint: String, payload: JSONObject): JSONObject? {
        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "es-ES,es;q=0.9")
            .header("Origin", "https://www.youtube.com")
            .header("X-Origin", "https://www.youtube.com")
            .post(payload.toString().toRequestBody(JSON_MEDIA))

        val cookie = ConfigManager.youtubeCookie.trim()
        val oauthToken = ensureValidOAuthToken()
        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
            reqBuilder.header("X-Goog-AuthUser", "0")
            buildSapisidHashHeader(cookie)?.let { auth ->
                reqBuilder.header("Authorization", auth)
            }
        } else if (!oauthToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $oauthToken")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null
            return JSONObject(body)
        }
    }

    private fun extractVideosRecursively(node: Any?, out: MutableList<VideoItem>, maxItems: Int) {
        if (node == null || out.size >= maxItems) return

        when (node) {
            is JSONObject -> {
                if (node.has("reelShelfRenderer") || node.has("reelItemRenderer") || node.has("shortsLockupViewModel")) {
                    return
                }

                val videoRenderer = node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("gridVideoRenderer")
                    ?: node.optJSONObject("compactVideoRenderer")

                if (videoRenderer != null) {
                    parseVideoRenderer(videoRenderer)?.let { out.add(it) }
                    return
                }

                val keys = node.keys()
                while (keys.hasNext() && out.size < maxItems) {
                    val k = keys.next()
                    if (k == "reelShelfRenderer" || k == "shortsLockupViewModel") continue
                    extractVideosRecursively(node.opt(k), out, maxItems)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= maxItems) break
                    extractVideosRecursively(node.opt(i), out, maxItems)
                }
            }
        }
    }

    private fun parseVideoRenderer(vr: JSONObject): VideoItem? {
        val videoId = vr.optString("videoId", "")
        if (videoId.isBlank()) return null

        val title = extractText(vr.optJSONObject("title"))
        if (title.isBlank()) return null

        val ownerTextObj = vr.optJSONObject("ownerText")
            ?: vr.optJSONObject("longBylineText")
            ?: vr.optJSONObject("shortBylineText")
        val channelName = extractText(ownerTextObj).ifBlank { "Canal" }

        var channelId = ""
        var channelHandle = ""
        val ownerRuns = ownerTextObj?.optJSONArray("runs")
        if (ownerRuns != null && ownerRuns.length() > 0) {
            val navEndpoint = ownerRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
            if (browseEndpoint != null) {
                channelId = browseEndpoint.optString("browseId", "")
                val canonical = browseEndpoint.optString("canonicalBaseUrl", "")
                if (canonical.startsWith("/@")) {
                    channelHandle = canonical.removePrefix("/")
                }
            }
        }

        var isShort = false
        val navEndpoint = vr.optJSONObject("navigationEndpoint")
        if (navEndpoint != null) {
            if (navEndpoint.has("reelWatchEndpoint")) {
                isShort = true
            }
            val webPageType = navEndpoint.optJSONObject("commandMetadata")
                ?.optJSONObject("webCommandMetadata")
                ?.optString("webPageType", "")
            val url = navEndpoint.optJSONObject("commandMetadata")
                ?.optJSONObject("webCommandMetadata")
                ?.optString("url", "") ?: ""
            if (webPageType == "WEB_PAGE_TYPE_SHORTS" || url.contains("/shorts/")) {
                isShort = true
            }
        }

        val badges = mutableListOf<String>()
        val overlays = vr.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val timeStatus = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                if (timeStatus != null) {
                    val style = timeStatus.optString("style", "")
                    if (style.equals("SHORTS", true)) {
                        isShort = true
                        badges.add("SHORTS")
                    }
                }
            }
        }

        val durationText = extractText(vr.optJSONObject("lengthText"))
        val durationSeconds = parseDurationSeconds(durationText)
        val isLive = durationText.isBlank() && vr.optJSONArray("badges")?.toString()?.contains("LIVE") == true

        val viewCountText = extractText(vr.optJSONObject("shortViewCountText"))
            .ifBlank { extractText(vr.optJSONObject("viewCountText")) }
        val publishedText = extractText(vr.optJSONObject("publishedTimeText"))

        val descSnippet = buildString {
            append(extractText(vr.optJSONObject("descriptionSnippet")))
            val detailedSnippets = vr.optJSONArray("detailedMetadataSnippets")
            if (detailedSnippets != null && detailedSnippets.length() > 0) {
                append(" ")
                append(extractText(detailedSnippets.optJSONObject(0)?.optJSONObject("snippetText")))
            }
        }.trim()

        val channelAvatar = vr.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.optJSONObject(0)
            ?.optString("url", "") ?: ""

        val thumbsArr = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val extractedThumb = if (thumbsArr != null && thumbsArr.length() > 0) {
            thumbsArr.optJSONObject(thumbsArr.length() - 1)?.optString("url", "") ?: ""
        } else ""
        val thumbnailUrl = extractedThumb.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

        return VideoItem(
            videoId = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            channelHandle = channelHandle,
            channelAvatarUrl = channelAvatar,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            durationText = durationText.ifBlank { if (isLive) "EN VIVO" else "0:00" },
            viewCountText = viewCountText,
            publishedText = publishedText,
            descriptionSnippet = descSnippet,
            isShort = isShort,
            isLive = isLive,
            badges = badges
        )
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText", "")
        if (simple.isNotEmpty()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString().trim()
    }

    private fun parseDurationSeconds(text: String): Int {
        val clean = text.trim()
        if (clean.isEmpty()) return 0
        val parts = clean.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }
}
