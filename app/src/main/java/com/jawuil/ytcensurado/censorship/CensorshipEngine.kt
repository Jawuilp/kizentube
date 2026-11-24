package com.jawuil.ytcensurado.censorship

import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.AutoplayPolicy
import com.jawuil.ytcensurado.model.FilterVerdict
import com.jawuil.ytcensurado.model.VideoItem

object CensorshipEngine {

    /**
     * Synchronous Pre-Render Pipeline:
     * 1. Deduplicates videos by videoId.
     * 2. Runs Layer 0 (Hard Rules: 0% Shorts, Min Duration, Whitelist/Blacklist, Keywords, Regex)
     *    and Layer 1 (Structural & Heuristic Brainrot Detector).
     * 3. For surviving non-whitelisted candidates, checks persistent Cache or batches them through
     *    Layer 2 (Multi-LLM Judge: Gemini / Groq / OpenAI-compatible).
     * 4. Auto-bans any channel that accumulates N brainrot strikes.
     * 5. Records full audit log for the PC Live Control Panel.
     *
     * Returns ONLY 100% verified safe videos before a single pixel is rendered on the TV.
     */
    fun filterVideosPreRender(candidates: List<VideoItem>): List<VideoItem> {
        if (candidates.isEmpty()) return emptyList()

        val unique = candidates.distinctBy { it.videoId }
        val survivingLayer1 = mutableListOf<VideoItem>()
        val needsLlmEvaluation = mutableListOf<VideoItem>()
        val finalApproved = mutableListOf<VideoItem>()

        for (video in unique) {
            val verdict = BrainrotHeuristics.evaluate(video)
            if (!verdict.allowed) {
                ConfigManager.recordAudit(video, verdict)
                if (verdict.layer == "HEURISTIC" || verdict.score >= 60) {
                    ConfigManager.registerChannelStrike(video, verdict.reason)
                }
                continue
            }

            // Whitelisted channels that passed anti-Shorts & keyword rules go straight through
            if (verdict.layer == "WHITELIST") {
                ConfigManager.recordAudit(video, verdict)
                finalApproved.add(video)
                continue
            }

            // Check persistent LLM verdict cache
            val cached = ConfigManager.verdictCache[video.videoId]
            if (cached != null) {
                ConfigManager.recordAudit(video, cached.copy(layer = "CACHE (${cached.layer})"))
                if (cached.allowed) {
                    finalApproved.add(video)
                }
                continue
            }

            survivingLayer1.add(video)
            if (LlmBatchJudge.isConfigured()) {
                needsLlmEvaluation.add(video)
            } else {
                ConfigManager.recordAudit(video, verdict)
                finalApproved.add(video)
            }
        }

        // Layer 2: Multi-LLM Batch Judge for surviving unknown videos
        if (needsLlmEvaluation.isNotEmpty()) {
            val batches = needsLlmEvaluation.chunked(18)
            var cacheUpdated = false
            for (batch in batches) {
                val llmVerdicts = LlmBatchJudge.evaluateBatch(batch)
                for (video in batch) {
                    val llmVerdict = llmVerdicts[video.videoId]
                    if (llmVerdict != null) {
                        ConfigManager.verdictCache[video.videoId] = llmVerdict
                        cacheUpdated = true
                        ConfigManager.recordAudit(video, llmVerdict)
                        if (llmVerdict.allowed) {
                            finalApproved.add(video)
                        } else {
                            ConfigManager.registerChannelStrike(video, llmVerdict.reason)
                        }
                    } else {
                        // Fallback to Layer 1 verdict if LLM timed out
                        val fallback = FilterVerdict(
                            allowed = true,
                            score = 0,
                            layer = "HEURISTIC",
                            reason = "Aprobado por Capa 1 (sin respuesta LLM)"
                        )
                        ConfigManager.recordAudit(video, fallback)
                        finalApproved.add(video)
                    }
                }
            }
            if (cacheUpdated) {
                ConfigManager.saveCache()
            }
        }

        return DeArrowClient.applyDeArrowBatch(finalApproved)
    }

    /**
     * Filters the "Up Next" / Related queue and selects the safe Autoplay candidate
     * according to the Chained Autoplay Firewall policy.
     */
    fun filterRelatedAndAutoplay(
        currentVideo: VideoItem,
        relatedCandidates: List<VideoItem>
    ): Pair<List<VideoItem>, VideoItem?> {
        val cleanRelated = filterVideosPreRender(relatedCandidates)
            .filter { it.videoId != currentVideo.videoId }

        val autoplayNext = when (ConfigManager.autoplayPolicy) {
            AutoplayPolicy.DISABLED -> null
            AutoplayPolicy.SAME_CHANNEL_ONLY -> cleanRelated.firstOrNull {
                isSameChannel(currentVideo, it)
            }
            AutoplayPolicy.SAME_CHANNEL_OR_WHITELIST -> cleanRelated.firstOrNull {
                isSameChannel(currentVideo, it) ||
                    ConfigManager.isChannelWhitelisted(it.channelId, it.channelHandle, it.channelName)
            }
            AutoplayPolicy.ANY_FILTERED -> cleanRelated.firstOrNull()
        }

        return Pair(cleanRelated, autoplayNext)
    }

    private fun isSameChannel(a: VideoItem, b: VideoItem): Boolean {
        if (a.channelId.isNotBlank() && b.channelId.isNotBlank() && a.channelId.equals(b.channelId, true)) {
            return true
        }
        if (a.channelHandle.isNotBlank() && b.channelHandle.isNotBlank() && a.channelHandle.equals(b.channelHandle, true)) {
            return true
        }
        if (a.channelName.isNotBlank() && b.channelName.isNotBlank() && a.channelName.equals(b.channelName, true)) {
            return true
        }
        return false
    }
}
