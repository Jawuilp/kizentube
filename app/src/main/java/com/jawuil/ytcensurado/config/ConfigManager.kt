package com.jawuil.ytcensurado.config

import android.content.Context
import android.content.SharedPreferences
import com.jawuil.ytcensurado.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ConfigManager {
    private const val PREF_NAME = "yt_censurado_config_v1"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_CACHE_JSON = "llm_verdict_cache_json"
    private const val KEY_HISTORY_JSON = "watch_history_json"

    const val DEFAULT_LLM_PROMPT = """Eres un filtro de protección infantil estricto contra sobreestimulación visual y contenido basura.
Tu misión es rechazar al 100% cualquier video (corto o largo) que sea:
1. Brainrot, recopilaciones de memes, TikToks pegados ("Top memes", "Ultimate LIST", "Ranking funniest/insane moments", "Si te ríes pierdes").
2. Gaming gritón o clickbait hiperestimulante (ej. "Steal a Brainrot", Roblox tóxico, palabras en *ASTERISCOS*, "$1 vs $500,000", retos estilo MrBeast, gritos).
3. Videos subidos sin título real (fechas como "5 November 2026", códigos, sopa de hashtags #fyp #shorts #tiktokvideo).
4. Contenido extranjero aleatorio de granjas de clics o temas inapropiados/ansiosos.
Si un video tiene cualquiera de estas señales, márcalo como allowed=false."""

    private lateinit var prefs: SharedPreferences
    @Volatile var appContext: Context? = null

    // Core Security & TV Controls
    @Volatile var pin: String = "1234"
    @Volatile var serverPort: Int = 8484
    @Volatile var updateRepo: String = "Jawuilp/kizentube"
    @Volatile var whitelistOnlyMode: Boolean = false
    @Volatile var searchEnabledOnTv: Boolean = true
    @Volatile var blockAllShorts: Boolean = true
    @Volatile var blockLiveStreams: Boolean = false
    @Volatile var minDurationSeconds: Int = 65
    @Volatile var maxDurationSeconds: Int = 0
    @Volatile var spanishOnlyOutsideWhitelist: Boolean = true
    @Volatile var deArrowEnabled: Boolean = true
    @Volatile var lockSubscriptions: Boolean = true

    // Heuristic Layer 1
    @Volatile var heuristicEnabled: Boolean = true
    @Volatile var heuristicThreshold: Int = 40
    @Volatile var autoBanChannelStrikeLimit: Int = 2

    // Chained Autoplay Firewall
    @Volatile var autoplayPolicy: AutoplayPolicy = AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST

    // Multi-LLM Layer 2
    @Volatile var llmEnabled: Boolean = true
    @Volatile var llmProvider: LlmProvider = LlmProvider.GEMINI
    @Volatile var geminiApiKey: String = ""
    @Volatile var geminiModel: String = "gemini-2.0-flash"
    @Volatile var groqApiKey: String = ""
    @Volatile var groqModel: String = "llama-3.3-70b-versatile"
    @Volatile var customOpenAiEndpoint: String = "https://api.openai.com/v1/chat/completions"
    @Volatile var customOpenAiApiKey: String = ""
    @Volatile var customOpenAiModel: String = "gpt-4o-mini"
    @Volatile var llmSystemPrompt: String = DEFAULT_LLM_PROMPT

    // Dedicated YouTube Account Cookie / Session / OAuth TV Tokens & Safe Curated Feed Topics
    @Volatile var youtubeCookie: String = ""
    @Volatile var oauthAccessToken: String = ""
    @Volatile var oauthRefreshToken: String = ""
    @Volatile var oauthTokenExpiry: Long = 0L
    val safeHomeTopics = CopyOnWriteArrayList<String>()

    // Lists
    val blockedKeywords = CopyOnWriteArrayList<String>()
    val blockedRegexes = CopyOnWriteArrayList<String>()
    val blockedChannels = CopyOnWriteArrayList<ChannelEntry>()
    val whitelistedChannels = CopyOnWriteArrayList<ChannelEntry>()

    // Live Runtime State for PC Remote Control
    @Volatile var currentlyPlayingVideo: VideoItem? = null
    @Volatile var pendingRemoteCommand: String? = null
    @Volatile var pendingRemoteArg: String? = null
    @Volatile var configVersionCounter: Long = 1L

    // Audit Logs & Channel Strike Tracking & Persistent Verdict Cache & Watch History
    val auditLogs = CopyOnWriteArrayList<AuditLogEntry>()
    val channelStrikes = ConcurrentHashMap<String, Int>()
    val verdictCache = ConcurrentHashMap<String, FilterVerdict>()
    val watchHistory = CopyOnWriteArrayList<VideoItem>()

    // Instant in-memory feed caches for 0ms tab switching
    @Volatile var cachedHomeFeed: List<VideoItem> = emptyList()
    @Volatile var cachedSubsFeed: List<VideoItem> = emptyList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        if (raw.isNullOrBlank()) {
            loadDefaults()
            save()
        } else {
            try {
                fromJsonString(raw)
            } catch (e: Exception) {
                loadDefaults()
                save()
            }
        }
        loadCache()
        loadWatchHistory()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        configVersionCounter++
        listeners.forEach {
            try { it() } catch (_: Exception) {}
        }
    }

    private fun loadDefaults() {
        pin = "1234"
        serverPort = 8484
        whitelistOnlyMode = false
        searchEnabledOnTv = true
        blockAllShorts = true
        blockLiveStreams = false
        minDurationSeconds = 65
        maxDurationSeconds = 0
        spanishOnlyOutsideWhitelist = true
        deArrowEnabled = true
        lockSubscriptions = true
        heuristicEnabled = true
        heuristicThreshold = 40
        autoBanChannelStrikeLimit = 2
        autoplayPolicy = AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST
        llmEnabled = true
        llmProvider = LlmProvider.GEMINI

        blockedKeywords.clear()
        blockedKeywords.addAll(
            listOf(
                "steal a brainrot",
                "brainrot",
                "skibidi",
                "sprunki",
                "garten of banban",
                "poppy playtime",
                "huggy wuggy",
                "pomni",
                "digital circus",
                "tralalero",
                "bombardiro",
                "tung tung",
                "cocomelon",
                "lankybox",
                "elsagate",
                "gyatt",
                "sigma boy",
                "rizzler",
                "only in ohio",
                "chinese memes",
                "ultimate list",
                "ranking funniest",
                "ranking insane",
                "guess the animal",
                "islamicvlog",
                "#tiktokvideo",
                "#youtubeshorts",
                "#shorts",
                "#fyp",
                "#parati",
                "try not to laugh",
                "si te ries pierdes",
                "si te ríes pierdes",
                "bases olvidadas de youtubers"
            )
        )

        blockedRegexes.clear()
        blockedRegexes.addAll(
            listOf(
                // 1. Long-form meme / TikTok / Fails compilation slop
                "(?i)\\b(top|ranking|ultimate\\s+list|compilation|recopilaci[oó]n)\\b.*\\b(memes?|moments?|fails?|tiktoks?|shorts?|clips?|funniest|insane|viral)\\b",
                // 2. Words screaming inside *ASTERISKS* (e.g. *OLVIDADAS*, *SECRETO*)
                "\\*[A-ZÁÉÍÓÚÑ0-9\\s!¡?¿]{3,}\\*",
                // 3. Extreme money / MrBeast style comparison ($1 vs $500,000)
                "(?i)\\$\\s*\\d+[\\d,\\.]*\\s*(vs\\.?|contra|versus)\\s*\\$\\s*\\d+[\\d,\\.]*",
                // 4. Raw mobile farm uploads where title is just a Date (e.g. "5 November 2026")
                "(?i)^\\s*\\d{1,2}\\s+(de\\s+)?(january|february|march|april|may|june|july|august|september|october|november|december|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)(\\s+de)?\\s+202\\d\\s*$",
                // 5. Hashtag soup in title
                "(?i)(#shorts|#youtubeshorts|#tiktokvideo|#fyp|#parati|#viral)"
            )
        )

        blockedChannels.clear()
        blockedChannels.addAll(
            listOf(
                ChannelEntry(id = "@Gabino_ElChinooo", name = "Gabino El Chinoo", handle = "@Gabino_ElChinooo", reason = "Gaming brainrot / Clickbait"),
                ChannelEntry(id = "@monteroes", name = "monteroes", handle = "@monteroes", reason = "Recopilación larga de memes brainrot"),
                ChannelEntry(id = "@MrBeast", name = "MrBeast", handle = "@MrBeast", reason = "Hiperestimulación / cortes rápidos"),
                ChannelEntry(id = "@Twosister-p9e", name = "2twosister", handle = "@Twosister-p9e", reason = "Granja de Shorts sin título (fecha)"),
                ChannelEntry(id = "@AuraShorts67", name = "AuraShorts", handle = "@AuraShorts67", reason = "Granja Ranking Funniest Moments"),
                ChannelEntry(id = "@gabrielafloresv_", name = "Gabriela Flores Villar", handle = "@gabrielafloresv_", reason = "Repost TikTok / sopa de hashtags"),
                ChannelEntry(id = "@SadaQuizzen", name = "Sada Quizzen", handle = "@SadaQuizzen", reason = "Spam extranjero #youtubeshorts"),
                ChannelEntry(id = "@SunsetCroc", name = "SunsetCroc", handle = "@SunsetCroc", reason = "Granja Ranking Insane Fails")
            )
        )

        whitelistedChannels.clear()
        safeHomeTopics.clear()
        safeHomeTopics.addAll(
            listOf(
                "documentales de animales en español para niños",
                "cuentos infantiles tranquilos en español",
                "aprender a dibujar paso a paso para niños",
                "música relajante y naturaleza 4k"
            )
        )
    }

    fun isAccountConnected(): Boolean {
        return youtubeCookie.isNotBlank() || oauthRefreshToken.isNotBlank() || oauthAccessToken.isNotBlank()
    }

    @Synchronized
    fun save() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_CONFIG_JSON, toJsonString()).apply()
        notifyListeners()
    }

    fun saveCache() {
        if (!::prefs.isInitialized) return
        try {
            val obj = JSONObject()
            verdictCache.entries.take(1500).forEach { (videoId, verdict) ->
                val v = JSONObject()
                v.put("allowed", verdict.allowed)
                v.put("score", verdict.score)
                v.put("layer", verdict.layer)
                v.put("reason", verdict.reason)
                obj.put(videoId, v)
            }
            prefs.edit().putString(KEY_CACHE_JSON, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadCache() {
        if (!::prefs.isInitialized) return
        val raw = prefs.getString(KEY_CACHE_JSON, null) ?: return
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.getJSONObject(k)
                verdictCache[k] = FilterVerdict(
                    allowed = v.optBoolean("allowed", false),
                    score = v.optInt("score", 0),
                    layer = v.optString("layer", "CACHE"),
                    reason = v.optString("reason", "")
                )
            }
        } catch (_: Exception) {}
    }

    fun recordWatchHistory(video: VideoItem) {
        watchHistory.removeAll { it.videoId == video.videoId }
        watchHistory.add(0, video)
        while (watchHistory.size > 60) {
            watchHistory.removeAt(watchHistory.size - 1)
        }
        saveWatchHistory()
    }

    fun clearWatchHistory() {
        watchHistory.clear()
        saveWatchHistory()
    }

    private fun saveWatchHistory() {
        if (!::prefs.isInitialized) return
        try {
            val arr = JSONArray()
            watchHistory.forEach { v ->
                val o = JSONObject()
                o.put("videoId", v.videoId)
                o.put("title", v.title)
                o.put("channelName", v.channelName)
                o.put("channelId", v.channelId)
                o.put("channelHandle", v.channelHandle)
                o.put("thumbnailUrl", v.thumbnailUrl)
                o.put("durationSeconds", v.durationSeconds)
                o.put("durationText", v.durationText)
                o.put("viewCountText", v.viewCountText)
                arr.put(o)
            }
            prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadWatchHistory() {
        if (!::prefs.isInitialized) return
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return
        try {
            val arr = JSONArray(raw)
            watchHistory.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vid = o.optString("videoId")
                if (vid.isBlank()) continue
                watchHistory.add(
                    VideoItem(
                        videoId = vid,
                        title = o.optString("title"),
                        channelName = o.optString("channelName"),
                        channelId = o.optString("channelId"),
                        channelHandle = o.optString("channelHandle"),
                        thumbnailUrl = o.optString("thumbnailUrl").ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                        durationSeconds = o.optInt("durationSeconds", 0),
                        durationText = o.optString("durationText", "0:00"),
                        viewCountText = o.optString("viewCountText", "")
                    )
                )
            }
        } catch (_: Exception) {}
    }

    fun recordAudit(video: VideoItem, verdict: FilterVerdict) {
        val entry = AuditLogEntry(
            timestamp = System.currentTimeMillis(),
            videoId = video.videoId,
            title = video.title,
            channelName = video.channelName,
            channelId = video.channelId,
            channelHandle = video.channelHandle,
            durationText = video.durationText,
            thumbnailUrl = video.thumbnailUrl,
            allowed = verdict.allowed,
            layer = verdict.layer,
            reason = verdict.reason,
            score = verdict.score
        )
        // Avoid duplicate consecutive logs for the same videoId
        auditLogs.removeAll { it.videoId == video.videoId }
        auditLogs.add(0, entry)
        while (auditLogs.size > 250) {
            auditLogs.removeAt(auditLogs.size - 1)
        }
    }

    fun registerChannelStrike(video: VideoItem, reason: String) {
        if (autoBanChannelStrikeLimit <= 0) return
        val key = when {
            video.channelId.isNotBlank() -> video.channelId
            video.channelHandle.isNotBlank() -> video.channelHandle
            video.channelName.isNotBlank() -> video.channelName
            else -> return
        }
        if (isChannelWhitelisted(video.channelId, video.channelHandle, video.channelName)) return
        if (isChannelBlocked(video.channelId, video.channelHandle, video.channelName)) return

        val strikes = (channelStrikes[key] ?: 0) + 1
        channelStrikes[key] = strikes
        if (strikes >= autoBanChannelStrikeLimit) {
            blockChannel(
                id = video.channelId.ifBlank { video.channelHandle.ifBlank { video.channelName } },
                name = video.channelName.ifBlank { key },
                handle = video.channelHandle,
                autoBanned = true,
                reason = "Auto-baneado ($strikes videos brainrot detectados: $reason)"
            )
        }
    }

    fun isChannelBlocked(channelId: String, channelHandle: String, channelName: String): Boolean {
        return blockedChannels.any { entry ->
            matchesChannel(entry, channelId, channelHandle, channelName)
        }
    }

    fun isChannelWhitelisted(channelId: String, channelHandle: String, channelName: String): Boolean {
        return whitelistedChannels.any { entry ->
            matchesChannel(entry, channelId, channelHandle, channelName)
        }
    }

    private fun matchesChannel(
        entry: ChannelEntry,
        channelId: String,
        channelHandle: String,
        channelName: String
    ): Boolean {
        if (channelId.isNotBlank() && (entry.id.equals(channelId, true) || entry.handle.equals(channelId, true))) {
            return true
        }
        if (channelHandle.isNotBlank()) {
            val cleanHandle = channelHandle.trim().removePrefix("@")
            val entryHandle = entry.handle.trim().removePrefix("@")
            val entryId = entry.id.trim().removePrefix("@")
            if (cleanHandle.isNotEmpty() && (cleanHandle.equals(entryHandle, true) || cleanHandle.equals(entryId, true))) {
                return true
            }
        }
        if (channelName.isNotBlank() && entry.name.isNotBlank() && entry.name.equals(channelName.trim(), true)) {
            return true
        }
        return false
    }

    fun blockChannel(id: String, name: String, handle: String = "", autoBanned: Boolean = false, reason: String = "Bloqueo manual") {
        val cleanId = id.trim().ifBlank { handle.trim().ifBlank { name.trim() } }
        if (cleanId.isBlank()) return
        whitelistedChannels.removeAll { matchesChannel(it, cleanId, handle, name) }
        if (!isChannelBlocked(cleanId, handle, name)) {
            blockedChannels.add(0, ChannelEntry(cleanId, name.ifBlank { cleanId }, handle, autoBanned, reason))
            // Purge cached allowed verdicts from this channel
            verdictCache.clear()
            save()
        }
    }

    fun whitelistChannel(id: String, name: String, handle: String = "") {
        val cleanId = id.trim().ifBlank { handle.trim().ifBlank { name.trim() } }
        if (cleanId.isBlank()) return
        blockedChannels.removeAll { matchesChannel(it, cleanId, handle, name) }
        if (!isChannelWhitelisted(cleanId, handle, name)) {
            whitelistedChannels.add(0, ChannelEntry(cleanId, name.ifBlank { cleanId }, handle, false, "Aprobado en Lista Blanca"))
            verdictCache.clear()
            save()
        }
    }

    fun removeWhitelistedChannel(id: String, name: String, handle: String = "") {
        val cleanId = id.trim().ifBlank { handle.trim().ifBlank { name.trim() } }
        whitelistedChannels.removeAll { matchesChannel(it, cleanId, handle, name) }
        verdictCache.clear()
        save()
    }

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("pin", pin)
        root.put("serverPort", serverPort)
        root.put("updateRepo", updateRepo)
        root.put("whitelistOnlyMode", whitelistOnlyMode)
        root.put("searchEnabledOnTv", searchEnabledOnTv)
        root.put("blockAllShorts", blockAllShorts)
        root.put("blockLiveStreams", blockLiveStreams)
        root.put("minDurationSeconds", minDurationSeconds)
        root.put("maxDurationSeconds", maxDurationSeconds)
        root.put("spanishOnlyOutsideWhitelist", spanishOnlyOutsideWhitelist)
        root.put("deArrowEnabled", deArrowEnabled)
        root.put("lockSubscriptions", lockSubscriptions)
        root.put("heuristicEnabled", heuristicEnabled)
        root.put("heuristicThreshold", heuristicThreshold)
        root.put("autoBanChannelStrikeLimit", autoBanChannelStrikeLimit)
        root.put("autoplayPolicy", autoplayPolicy.name)
        root.put("llmEnabled", llmEnabled)
        root.put("llmProvider", llmProvider.name)
        root.put("geminiApiKey", geminiApiKey)
        root.put("geminiModel", geminiModel)
        root.put("groqApiKey", groqApiKey)
        root.put("groqModel", groqModel)
        root.put("customOpenAiEndpoint", customOpenAiEndpoint)
        root.put("customOpenAiApiKey", customOpenAiApiKey)
        root.put("customOpenAiModel", customOpenAiModel)
        root.put("llmSystemPrompt", llmSystemPrompt)
        root.put("youtubeCookie", youtubeCookie)
        root.put("oauthAccessToken", oauthAccessToken)
        root.put("oauthRefreshToken", oauthRefreshToken)
        root.put("oauthTokenExpiry", oauthTokenExpiry)

        root.put("safeHomeTopics", JSONArray(safeHomeTopics))
        root.put("blockedKeywords", JSONArray(blockedKeywords))
        root.put("blockedRegexes", JSONArray(blockedRegexes))

        val bcArr = JSONArray()
        blockedChannels.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("name", it.name)
            o.put("handle", it.handle)
            o.put("autoBanned", it.autoBanned)
            o.put("reason", it.reason)
            bcArr.put(o)
        }
        root.put("blockedChannels", bcArr)

        val wcArr = JSONArray()
        whitelistedChannels.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("name", it.name)
            o.put("handle", it.handle)
            wcArr.put(o)
        }
        root.put("whitelistedChannels", wcArr)

        return root.toString(2)
    }

    fun fromJsonString(json: String) {
        val root = JSONObject(json)
        pin = root.optString("pin", "1234").ifBlank { "1234" }
        serverPort = root.optInt("serverPort", 8484)
        updateRepo = root.optString("updateRepo", "Jawuilp/kizentube").ifBlank { "Jawuilp/kizentube" }
        whitelistOnlyMode = root.optBoolean("whitelistOnlyMode", false)
        searchEnabledOnTv = root.optBoolean("searchEnabledOnTv", true)
        blockAllShorts = root.optBoolean("blockAllShorts", true)
        blockLiveStreams = root.optBoolean("blockLiveStreams", false)
        minDurationSeconds = root.optInt("minDurationSeconds", 65)
        maxDurationSeconds = root.optInt("maxDurationSeconds", 0)
        spanishOnlyOutsideWhitelist = root.optBoolean("spanishOnlyOutsideWhitelist", true)
        deArrowEnabled = root.optBoolean("deArrowEnabled", true)
        lockSubscriptions = root.optBoolean("lockSubscriptions", true)
        heuristicEnabled = root.optBoolean("heuristicEnabled", true)
        heuristicThreshold = root.optInt("heuristicThreshold", 40)
        autoBanChannelStrikeLimit = root.optInt("autoBanChannelStrikeLimit", 2)

        autoplayPolicy = try {
            AutoplayPolicy.valueOf(root.optString("autoplayPolicy", AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST.name))
        } catch (_: Exception) {
            AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST
        }

        llmEnabled = root.optBoolean("llmEnabled", true)
        llmProvider = try {
            LlmProvider.valueOf(root.optString("llmProvider", LlmProvider.GEMINI.name))
        } catch (_: Exception) {
            LlmProvider.GEMINI
        }
        geminiApiKey = root.optString("geminiApiKey", "")
        geminiModel = root.optString("geminiModel", "gemini-2.0-flash").ifBlank { "gemini-2.0-flash" }
        groqApiKey = root.optString("groqApiKey", "")
        groqModel = root.optString("groqModel", "llama-3.3-70b-versatile").ifBlank { "llama-3.3-70b-versatile" }
        customOpenAiEndpoint = root.optString("customOpenAiEndpoint", "https://api.openai.com/v1/chat/completions")
        customOpenAiApiKey = root.optString("customOpenAiApiKey", "")
        customOpenAiModel = root.optString("customOpenAiModel", "gpt-4o-mini")
        llmSystemPrompt = root.optString("llmSystemPrompt", DEFAULT_LLM_PROMPT).ifBlank { DEFAULT_LLM_PROMPT }
        youtubeCookie = root.optString("youtubeCookie", "")
        oauthAccessToken = root.optString("oauthAccessToken", "")
        oauthRefreshToken = root.optString("oauthRefreshToken", "")
        oauthTokenExpiry = root.optLong("oauthTokenExpiry", 0L)

        root.optJSONArray("safeHomeTopics")?.let { arr ->
            safeHomeTopics.clear()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) safeHomeTopics.add(s)
            }
        }

        root.optJSONArray("blockedKeywords")?.let { arr ->
            blockedKeywords.clear()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) blockedKeywords.add(s)
            }
        }

        root.optJSONArray("blockedRegexes")?.let { arr ->
            blockedRegexes.clear()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) blockedRegexes.add(s)
            }
        }

        root.optJSONArray("blockedChannels")?.let { arr ->
            blockedChannels.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                blockedChannels.add(
                    ChannelEntry(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        handle = o.optString("handle"),
                        autoBanned = o.optBoolean("autoBanned", false),
                        reason = o.optString("reason", "Bloqueado")
                    )
                )
            }
        }

        root.optJSONArray("whitelistedChannels")?.let { arr ->
            whitelistedChannels.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                whitelistedChannels.add(
                    ChannelEntry(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        handle = o.optString("handle")
                    )
                )
            }
        }
    }
}
