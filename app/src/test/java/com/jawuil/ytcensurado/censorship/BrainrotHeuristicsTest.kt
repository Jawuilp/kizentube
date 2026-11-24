package com.jawuil.ytcensurado.censorship

import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.innertube.InnerTubeClient
import com.jawuil.ytcensurado.model.VideoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrainrotHeuristicsTest {

    @Before
    fun setup() {
        // Test pure structural/heuristic detection even when the channels are NOT manually blacklisted
        ConfigManager.blockedChannels.clear()
        ConfigManager.blockedKeywords.clear()
        ConfigManager.blockedRegexes.clear()
        ConfigManager.whitelistedChannels.clear()
        ConfigManager.blockAllShorts = true
        ConfigManager.minDurationSeconds = 65
        ConfigManager.heuristicEnabled = true
        ConfigManager.heuristicThreshold = 40
        ConfigManager.spanishOnlyOutsideWhitelist = true
    }

    @Test
    fun `blocks all 9 user-reported brainrot and compilation videos purely via structural heuristics`() {
        val examples = listOf(
            // 1. Long gaming brainrot with *ASTERISKS* and emojis
            VideoItem(
                videoId = "ap7ieuJN750",
                title = "¡Las ABANDONARON! - 💰 Compré Bases *OLVIDADAS* De YOUTUBERS en Steal a Brainrot",
                channelName = "Gabino El Chinoo",
                channelHandle = "@Gabino_ElChinooo",
                thumbnailUrl = "",
                durationSeconds = 950,
                durationText = "15:50"
            ),
            // 2. Long compilation of Chinese/TikTok memes
            VideoItem(
                videoId = "nVpWSPDK_yE",
                title = "Top chinese memes of all time! Ultimate LIST",
                channelName = "monteroes",
                channelHandle = "@monteroes",
                thumbnailUrl = "",
                durationSeconds = 620,
                durationText = "10:20"
            ),
            // 3. Long MrBeast $1 vs $500,000 hyper-stimulating comparison
            VideoItem(
                videoId = "1WEAJ-DFkHE",
                title = "$1 vs $500,000 Plane Ticket!",
                channelName = "MrBeast",
                channelHandle = "@MrBeast",
                thumbnailUrl = "",
                durationSeconds = 1080,
                durationText = "18:00"
            ),
            // 4. Raw phone upload with date title & bot handle (-p9e) — even if uploaded as 3-min horizontal video
            VideoItem(
                videoId = "lHepv1Itwb4",
                title = "5 November 2026",
                channelName = "2twosister",
                channelHandle = "@Twosister-p9e",
                thumbnailUrl = "",
                durationSeconds = 180,
                durationText = "3:00"
            ),
            // 5. Ranking Funniest Moments compilation farm
            VideoItem(
                videoId = "IbNdx0qVO7A",
                title = "Ranking Funniest Hamster Moments",
                channelName = "AuraShorts",
                channelHandle = "@AuraShorts67",
                thumbnailUrl = "",
                durationSeconds = 240,
                durationText = "4:00"
            ),
            // 6. Guess The Animal challenge slop
            VideoItem(
                videoId = "rqsRdfdN4uI",
                title = "Guess The Animal",
                channelName = "MrBeast",
                channelHandle = "@MrBeast",
                thumbnailUrl = "",
                durationSeconds = 180,
                durationText = "3:00"
            ),
            // 7. Reposted TikTok with hashtag soup
            VideoItem(
                videoId = "-xXEseGOIho",
                title = "Los juegos de antes 🥹🥳 #shorts #humor #tiktokvideo #comedia #funny #fyp",
                channelName = "Gabriela Flores Villar",
                channelHandle = "@gabrielafloresv_",
                thumbnailUrl = "",
                durationSeconds = 180,
                durationText = "3:00"
            ),
            // 8. Random foreign hashtag spam
            VideoItem(
                videoId = "oXvokwAV25o",
                title = "Islamicvlog #Islamicvlog #youtubeshorts #islam",
                channelName = "Sada Quizzen",
                channelHandle = "@SadaQuizzen",
                thumbnailUrl = "",
                durationSeconds = 180,
                durationText = "3:00"
            ),
            // 9. Ranking Insane Bike Fails compilation
            VideoItem(
                videoId = "tGR_Pew1mMY",
                title = "Ranking Insane Bike Fails",
                channelName = "SunsetCroc",
                channelHandle = "@SunsetCroc",
                thumbnailUrl = "",
                durationSeconds = 300,
                durationText = "5:00"
            )
        )

        for (video in examples) {
            val verdict = BrainrotHeuristics.evaluate(video)
            println("TEST [${video.videoId}] '${video.title}' -> allowed=${verdict.allowed}, score=${verdict.score}, reason=${verdict.reason}")
            assertFalse(
                "Expected video '${video.title}' (${video.videoId}) to be BLOCKED by heuristics, but it was allowed!",
                verdict.allowed
            )
        }
    }

    @Test
    fun `allows calm educational Spanish video`() {
        val calmVideo = VideoItem(
            videoId = "calm123",
            title = "Los animales del océano: Documental educativo para niños en español",
            channelName = "NatGeo Educativo",
            channelHandle = "@NatGeoEducativo",
            thumbnailUrl = "",
            durationSeconds = 900,
            durationText = "15:00"
        )
        val verdict = BrainrotHeuristics.evaluate(calmVideo)
        assertTrue("Expected calm educational video to be allowed: ${verdict.reason}", verdict.allowed)
    }

    @Test
    fun `extracts real playable stream URLs via InnerTube with VISITOR_DATA`() {
        val stream = InnerTubeClient.fetchStreamPlaybackData("aqz-KE-bpKQ")
        assertNotNull("Expected stream playback data to be extracted", stream)
        val dashXml = stream?.buildDashManifestXml()
        println("EXTRACTED STREAM: tracks=${stream?.videoTracks?.map { it.label }} | audio=${stream?.audioTrack != null} | dashXmlLen=${dashXml?.length}")
        assertTrue(
            "Expected 1080p DASH tracks and valid MPD XML",
            !dashXml.isNullOrBlank() && stream.videoTracks.any { it.height >= 720 }
        )
    }
}
