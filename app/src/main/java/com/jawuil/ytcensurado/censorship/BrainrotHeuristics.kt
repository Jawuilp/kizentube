package com.jawuil.ytcensurado.censorship

import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.FilterVerdict
import com.jawuil.ytcensurado.model.VideoItem
import java.util.Locale

object BrainrotHeuristics {

    private val DATE_TITLE_REGEX = Regex(
        "(?i)^\\s*\\d{1,2}\\s+(de\\s+)?(january|february|march|april|may|june|july|august|september|october|november|december|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)(\\s+de)?\\s+202\\d\\s*$"
    )

    private val RAW_FILE_TITLE_REGEX = Regex(
        "(?i)^\\s*(IMG_\\d+|VID_\\d+|MOV_\\d+|CapCut.*|InShot.*|Untitled.*|Video\\s*\\d*)\\s*$"
    )

    private val COMPILATION_SLOP_REGEX = Regex(
        "(?i)\\b(top\\s+.*memes?|ultimate\\s+list|ranking\\s+(funniest|insane|best|worst|craziest|viral)|try\\s+not\\s+to\\s+laugh|si\\s+te\\s+r[ií]es\\s+pierdes|tiktok\\s+compilation|meme\\s+compilation|recopilaci[oó]n\\s+de\\s+(memes|tiktoks|shorts)|funniest\\s+.*moments|insane\\s+.*fails)\\b"
    )

    private val ASTERISK_CLICKBAIT_REGEX = Regex("\\*[A-ZÁÉÍÓÚÑa-záéíóúñ0-9\\s!¡?¿]{3,}\\*")

    private val MONEY_VS_CHALLENGE_REGEX = Regex(
        "(?i)(\\$\\s*\\d+[\\d,\\.]*\\s*(vs\\.?|contra|versus)\\s*\\$\\s*\\d+[\\d,\\.]*|\\bcompr[eé]\\s+.*\\b(youtubers?|bases?\\s+olvidadas?)\\b|\\b24\\s+horas\\s+(en|comiendo|sobreviviendo)\\b|\\bguess\\s+the\\s+(animal|logo|food|voice|character)\\b)"
    )

    private val HASHTAG_SOUP_REGEX = Regex(
        "(?i)(#shorts\\b|#youtubeshorts\\b|#tiktokvideo\\b|#tiktok\\b|#fyp\\b|#parati\\b|#viral\\b|#islamicvlog\\b)"
    )

    private val BOT_HANDLE_SUFFIX_REGEX = Regex("(?i)^@?[a-z0-9_]+-[a-z0-9]{3,4}$")

    private val FARM_CHANNEL_KEYWORDS_REGEX = Regex(
        "(?i)(shorts|clips|memes|quizzen|brainrot|tiktok|viral|ranking)"
    )

    private val ENGLISH_SLOP_MARKERS = Regex(
        "(?i)\\b(ranking|funniest|moments|insane|fails|guess\\s+the|ultimate\\s+list|of\\s+all\\s+time|plane\\s+ticket|islamicvlog|skibidi|brainrot|steal\\s+a)\\b"
    )

    private val CLICKBAIT_EMOJIS = listOf("💰", "😱", "🤯", "🚨", "🤑", "🔥", "💀", "😈", "🤡")

    fun evaluate(video: VideoItem): FilterVerdict {
        val isWhitelisted = ConfigManager.isChannelWhitelisted(
            video.channelId,
            video.channelHandle,
            video.channelName
        )

        // 1. Explicit Channel Blacklist always wins
        if (ConfigManager.isChannelBlocked(video.channelId, video.channelHandle, video.channelName)) {
            return FilterVerdict(
                allowed = false,
                score = 100,
                layer = "HARD_RULE",
                reason = "Canal bloqueado en Lista Negra (${video.channelName})"
            )
        }

        // 2. 100% Shorts Eradication (applies even to whitelisted channels!)
        if (ConfigManager.blockAllShorts) {
            if (video.isShort || video.badges.any { it.equals("SHORTS", true) }) {
                return FilterVerdict(
                    allowed = false,
                    score = 100,
                    layer = "HARD_RULE",
                    reason = "Short detectado y eliminado de raíz"
                )
            }
            if (!video.isLive && video.durationSeconds in 1 until ConfigManager.minDurationSeconds) {
                return FilterVerdict(
                    allowed = false,
                    score = 100,
                    layer = "HARD_RULE",
                    reason = "Duración corta (${video.durationSeconds}s < ${ConfigManager.minDurationSeconds}s) — Short disfrazado"
                )
            }
            if (!video.isLive && video.durationSeconds <= 0) {
                return FilterVerdict(
                    allowed = false,
                    score = 100,
                    layer = "HARD_RULE",
                    reason = "Video sin duración válida / posible Short"
                )
            }
        }

        if (ConfigManager.blockLiveStreams && video.isLive) {
            return FilterVerdict(
                allowed = false,
                score = 100,
                layer = "HARD_RULE",
                reason = "Transmisión en vivo bloqueada"
            )
        }

        if (ConfigManager.maxDurationSeconds > 0 && video.durationSeconds > ConfigManager.maxDurationSeconds) {
            return FilterVerdict(
                allowed = false,
                score = 100,
                layer = "HARD_RULE",
                reason = "Duración excesiva (${video.durationText})"
            )
        }

        // 3. Strict Whitelist Mode check
        if (ConfigManager.whitelistOnlyMode && !isWhitelisted) {
            return FilterVerdict(
                allowed = false,
                score = 100,
                layer = "WHITELIST",
                reason = "Canal fuera de Lista Blanca (${video.channelName})"
            )
        }

        // 4. Explicit Blocked Keywords (checked across title, channel, handle, description)
        val combinedText = "${video.title} ${video.channelName} ${video.channelHandle} ${video.descriptionSnippet}"
            .lowercase(Locale.ROOT)

        for (kw in ConfigManager.blockedKeywords) {
            val cleanKw = kw.trim().lowercase(Locale.ROOT)
            if (cleanKw.isNotEmpty() && combinedText.contains(cleanKw)) {
                return FilterVerdict(
                    allowed = false,
                    score = 100,
                    layer = "HARD_RULE",
                    reason = "Palabra prohibida detectada: '$kw'"
                )
            }
        }

        // 5. Explicit User Regexes
        val titleAndChannel = "${video.title} ${video.channelName} ${video.channelHandle}"
        for (pattern in ConfigManager.blockedRegexes) {
            try {
                if (Regex(pattern).containsMatchIn(titleAndChannel)) {
                    return FilterVerdict(
                        allowed = false,
                        score = 95,
                        layer = "HARD_RULE",
                        reason = "Regla Regex anti-brainrot activada"
                    )
                }
            } catch (_: Exception) {}
        }

        // If channel is explicitly whitelisted by the user and passed anti-Shorts & explicit keywords, allow it
        if (isWhitelisted) {
            return FilterVerdict(
                allowed = true,
                score = 0,
                layer = "WHITELIST",
                reason = "Canal aprobado en Lista Blanca"
            )
        }

        if (!ConfigManager.heuristicEnabled) {
            return FilterVerdict(
                allowed = true,
                score = 0,
                layer = "HEURISTIC",
                reason = "Heurística desactivada"
            )
        }

        // 6. Layer 1: Multi-Signal Brainrot Heuristic Scoring (0..100)
        var score = 0
        val reasons = mutableListOf<String>()
        val title = video.title.trim()

        // Signal A: Long-form Meme / TikTok / Fails compilation slop
        if (COMPILATION_SLOP_REGEX.containsMatchIn(title)) {
            score += 65
            reasons.add("Recopilación de memes/TikToks/Ranking")
        }

        // Signal B: Raw mobile farm upload with date as title (e.g. "5 November 2026")
        if (DATE_TITLE_REGEX.matches(title) || RAW_FILE_TITLE_REGEX.matches(title)) {
            score += 80
            reasons.add("Subida de granja sin título real (fecha/archivo)")
        }

        // Signal C: Hashtag soup (#shorts #tiktokvideo #fyp)
        val hashtagCount = title.count { it == '#' }
        if (HASHTAG_SOUP_REGEX.containsMatchIn(combinedText) || hashtagCount >= 3) {
            score += 65
            reasons.add("Sopa de hashtags / Repost de TikTok-Shorts")
        }

        // Signal D: Screaming words in *ASTERISKS* (*OLVIDADAS*)
        if (ASTERISK_CLICKBAIT_REGEX.containsMatchIn(title)) {
            score += 50
            reasons.add("Clickbait con *ASTERISCOS*")
        }

        // Signal E: Extreme money / YouTuber base / challenge clickbait ($1 vs $500,000)
        if (MONEY_VS_CHALLENGE_REGEX.containsMatchIn(title)) {
            score += 55
            reasons.add("Fórmula de reto hiperestimulante / clickbait")
        }

        // Signal F: Bot or Content-Farm channel handle (@Twosister-p9e, @AuraShorts67)
        val handleOrName = "${video.channelHandle} ${video.channelName}"
        if (video.channelHandle.isNotBlank() &&
            BOT_HANDLE_SUFFIX_REGEX.matches(video.channelHandle) &&
            video.channelHandle.substringAfterLast("-").any { it.isDigit() }
        ) {
            score += 45
            reasons.add("Handle de canal autogenerado de granja (${video.channelHandle})")
        }
        if (FARM_CHANNEL_KEYWORDS_REGEX.containsMatchIn(handleOrName)) {
            score += 40
            reasons.add("Canal de granja Shorts/Memes/Clips (${video.channelName})")
        }

        // Signal G: Screaming CAPS + Exclamation marks + Clickbait emojis
        val letters = title.filter { it.isLetter() }
        if (letters.length >= 10) {
            val upperRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length.toDouble()
            if (upperRatio >= 0.42) {
                score += 25
                reasons.add("Exceso de MAYÚSCULAS gritonas (${(upperRatio * 100).toInt()}%)")
            }
        }
        val exclamationCount = title.count { it == '!' || it == '¡' }
        if (exclamationCount >= 3) {
            score += 15
            reasons.add("Exceso de signos de exclamación")
        }
        val emojiHits = CLICKBAIT_EMOJIS.count { title.contains(it) }
        if (emojiHits >= 1) {
            score += 15 * emojiHits
            reasons.add("Emojis de clickbait")
        }

        // Signal H: Language & Script Coherence Filter (Spanish-only outside Whitelist)
        if (ConfigManager.spanishOnlyOutsideWhitelist) {
            if (ENGLISH_SLOP_MARKERS.containsMatchIn(title)) {
                score += 60
                reasons.add("Granja en inglés / extranjero fuera de Lista Blanca")
            }
            // Non-Latin scripts (Arabic, Cyrillic, CJK) in title when expecting Spanish
            val nonLatinCount = title.count { ch ->
                val block = Character.UnicodeBlock.of(ch)
                block == Character.UnicodeBlock.ARABIC ||
                    block == Character.UnicodeBlock.CYRILLIC ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.DEVANAGARI
            }
            if (nonLatinCount >= 2) {
                score += 75
                reasons.add("Alfabeto extranjero no permitido")
            }
        }

        val finalScore = score.coerceAtMost(100)
        val blocked = finalScore >= ConfigManager.heuristicThreshold
        return FilterVerdict(
            allowed = !blocked,
            score = finalScore,
            layer = "HEURISTIC",
            reason = if (reasons.isEmpty()) "Aprobado por heurística (${finalScore} pts)"
            else "${reasons.joinToString(" + ")} (${finalScore} pts)"
        )
    }
}
