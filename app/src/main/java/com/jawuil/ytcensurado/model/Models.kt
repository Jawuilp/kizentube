package com.jawuil.ytcensurado.model

data class VideoItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String = "",
    val channelHandle: String = "",
    val channelAvatarUrl: String = "",
    val thumbnailUrl: String,
    val durationSeconds: Int,
    val durationText: String,
    val viewCountText: String = "",
    val publishedText: String = "",
    val descriptionSnippet: String = "",
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val badges: List<String> = emptyList()
)

data class ChannelEntry(
    val id: String,
    val name: String,
    val handle: String = "",
    val autoBanned: Boolean = false,
    val reason: String = ""
)

enum class LlmProvider {
    GEMINI,
    GROQ,
    OPENAI_COMPATIBLE
}

enum class AutoplayPolicy {
    SAME_CHANNEL_OR_WHITELIST,
    SAME_CHANNEL_ONLY,
    ANY_FILTERED,
    DISABLED
}

data class FilterVerdict(
    val allowed: Boolean,
    val score: Int = 0,
    val layer: String,
    val reason: String
)

data class AuditLogEntry(
    val timestamp: Long,
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val channelHandle: String,
    val durationText: String,
    val thumbnailUrl: String,
    val allowed: Boolean,
    val layer: String,
    val reason: String,
    val score: Int
)

data class VideoQualityTrack(
    val itag: Int,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codecs: String,
    val url: String,
    val initRange: String,
    val indexRange: String
)

data class AudioTrackData(
    val itag: Int,
    val bitrate: Int,
    val codecs: String,
    val sampleRate: String,
    val url: String,
    val initRange: String,
    val indexRange: String
)

data class StreamPlaybackData(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val hlsUrl: String? = null,
    val muxedUrl: String? = null,
    val videoTracks: List<VideoQualityTrack> = emptyList(),
    val audioTrack: AudioTrackData? = null,
    val durationSeconds: Int = 0,
    val userAgent: String = "com.google.android.youtube/20.03.35 (Linux; U; Android 14) gzip"
) {
    /**
     * Generates an in-memory MPEG-DASH MPD manifest using YouTube's exact initRange and indexRange.
     * ExoPlayer's DashMediaSource uses bounded Range headers for every segment, unlocking 1080p Full HD
     * without HTTP 403 errors.
     */
    fun buildDashManifestXml(targetHeight: Int? = null): String? {
        val audio = audioTrack ?: return null
        if (videoTracks.isEmpty()) return null

        val selectedTracks = if (targetHeight != null) {
            videoTracks.filter { it.height == targetHeight }.ifEmpty { listOf(videoTracks.first()) }
        } else {
            // Default to highest HD track (1080p) so it immediately starts in crisp 1080p HD
            listOf(videoTracks.first())
        }

        val dur = if (durationSeconds > 0) durationSeconds else 3600
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${dur}S">""").append("\n")
        sb.append("""  <Period duration="PT${dur}S">""").append("\n")

        // Video AdaptationSet
        sb.append("""    <AdaptationSet id="0" mimeType="video/mp4" subsegmentAlignment="true" startWithSAP="1">""").append("\n")
        for (v in selectedTracks) {
            val escapedUrl = xmlEscape(v.url)
            sb.append("""      <Representation id="${v.itag}" codecs="${v.codecs}" width="${v.width}" height="${v.height}" frameRate="${v.fps}" bandwidth="${v.bitrate}">""").append("\n")
            sb.append("""        <BaseURL>$escapedUrl</BaseURL>""").append("\n")
            sb.append("""        <SegmentBase indexRange="${v.indexRange}">""").append("\n")
            sb.append("""          <Initialization range="${v.initRange}"/>""").append("\n")
            sb.append("""        </SegmentBase>""").append("\n")
            sb.append("""      </Representation>""").append("\n")
        }
        sb.append("""    </AdaptationSet>""").append("\n")

        // Audio AdaptationSet
        val escapedAudioUrl = xmlEscape(audio.url)
        sb.append("""    <AdaptationSet id="1" mimeType="audio/mp4" subsegmentAlignment="true" startWithSAP="1">""").append("\n")
        sb.append("""      <Representation id="${audio.itag}" codecs="${audio.codecs}" audioSamplingRate="${audio.sampleRate}" bandwidth="${audio.bitrate}">""").append("\n")
        sb.append("""        <BaseURL>$escapedAudioUrl</BaseURL>""").append("\n")
        sb.append("""        <SegmentBase indexRange="${audio.indexRange}">""").append("\n")
        sb.append("""          <Initialization range="${audio.initRange}"/>""").append("\n")
        sb.append("""        </SegmentBase>""").append("\n")
        sb.append("""      </Representation>""").append("\n")
        sb.append("""    </AdaptationSet>""").append("\n")

        sb.append("""  </Period>""").append("\n")
        sb.append("""</MPD>""")
        return sb.toString()
    }

    private fun xmlEscape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
