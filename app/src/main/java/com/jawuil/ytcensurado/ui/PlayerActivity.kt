package com.jawuil.ytcensurado.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.jawuil.ytcensurado.R
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.innertube.InnerTubeClient
import com.jawuil.ytcensurado.model.StreamPlaybackData
import com.jawuil.ytcensurado.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topOverlay: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerChannel: TextView
    private lateinit var btnSpeedSelector: Button
    private lateinit var btnQualitySelector: Button
    private lateinit var btnToggleRelated: Button
    private lateinit var btnNextSafeVideo: Button
    private lateinit var btnPlayerParentalPin: Button
    private lateinit var relatedStripContainer: LinearLayout
    private lateinit var btnCloseRelated: Button
    private lateinit var rvRelatedVideos: RecyclerView
    private lateinit var playerLoading: ProgressBar

    private var exoPlayer: ExoPlayer? = null
    private lateinit var currentVideo: VideoItem
    private var currentStreamData: StreamPlaybackData? = null
    private var selectedQualityHeight: Int? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var triedMuxedFallback: Boolean = false
    private var verifiedAutoplayNext: VideoItem? = null
    private lateinit var relatedAdapter: RelatedVideoAdapter

    private val remotePollHandler = Handler(Looper.getMainLooper())
    private val remotePollRunnable = object : Runnable {
        override fun run() {
            val cmd = ConfigManager.pendingRemoteCommand
            if (cmd == "STOP_VIDEO") {
                ConfigManager.pendingRemoteCommand = null
                Toast.makeText(this@PlayerActivity, "Reproducción detenida desde Panel PC", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            remotePollHandler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        topOverlay = findViewById(R.id.topOverlay)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerChannel = findViewById(R.id.tvPlayerChannel)
        btnSpeedSelector = findViewById(R.id.btnSpeedSelector)
        btnQualitySelector = findViewById(R.id.btnQualitySelector)
        btnToggleRelated = findViewById(R.id.btnToggleRelated)
        btnNextSafeVideo = findViewById(R.id.btnNextSafeVideo)
        btnPlayerParentalPin = findViewById(R.id.btnPlayerParentalPin)
        relatedStripContainer = findViewById(R.id.relatedStripContainer)
        btnCloseRelated = findViewById(R.id.btnCloseRelated)
        rvRelatedVideos = findViewById(R.id.rvRelatedVideos)
        playerLoading = findViewById(R.id.playerLoading)

        currentVideo = VideoItem(
            videoId = intent.getStringExtra("videoId") ?: "",
            title = intent.getStringExtra("title") ?: "",
            channelName = intent.getStringExtra("channelName") ?: "",
            channelId = intent.getStringExtra("channelId") ?: "",
            channelHandle = intent.getStringExtra("channelHandle") ?: "",
            thumbnailUrl = intent.getStringExtra("thumbnailUrl") ?: "",
            durationSeconds = intent.getIntExtra("durationSeconds", 0),
            durationText = intent.getStringExtra("durationText") ?: ""
        )

        if (currentVideo.videoId.isBlank()) {
            finish()
            return
        }

        relatedAdapter = RelatedVideoAdapter { selected ->
            relatedStripContainer.visibility = View.GONE
            playVideoItem(selected)
        }
        rvRelatedVideos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRelatedVideos.adapter = relatedAdapter

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topOverlay.visibility = visibility
            }
        )

        btnSpeedSelector.setOnClickListener {
            showSpeedSelectorDialog()
        }

        btnQualitySelector.setOnClickListener {
            showQualitySelectorDialog()
        }

        btnToggleRelated.setOnClickListener {
            if (relatedStripContainer.visibility == View.VISIBLE) {
                relatedStripContainer.visibility = View.GONE
            } else {
                playerView.hideController()
                relatedStripContainer.visibility = View.VISIBLE
                rvRelatedVideos.requestFocus()
            }
        }

        btnCloseRelated.setOnClickListener {
            relatedStripContainer.visibility = View.GONE
            playerView.requestFocus()
        }

        btnPlayerParentalPin.setOnClickListener {
            exoPlayer?.pause()
            PinDialogHelper.showVideoParentalMenu(this, currentVideo) {
                if (ConfigManager.isChannelBlocked(currentVideo.channelId, currentVideo.channelHandle, currentVideo.channelName)) {
                    ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
                    finish()
                }
            }
        }

        tvPlayerChannel.setOnClickListener {
            exoPlayer?.pause()
            val intent = Intent(this, ChannelProfileActivity::class.java).apply {
                putExtra("channelId", currentVideo.channelId)
                putExtra("channelName", currentVideo.channelName)
                putExtra("channelHandle", currentVideo.channelHandle)
            }
            startActivity(intent)
        }

        btnNextSafeVideo.setOnClickListener {
            verifiedAutoplayNext?.let { next ->
                playVideoItem(next)
            }
        }

        initPlayer()
        playVideoItem(currentVideo)
    }

    private fun showSpeedSelectorDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { sp ->
            val label = if (sp == 1.0f) "1.0x (Normal)" else "${sp}x"
            val active = if (sp == currentPlaybackSpeed) " ✅" else ""
            "⏱️ $label$active"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Velocidad de Reproducción")
            .setItems(labels) { _, which ->
                currentPlaybackSpeed = speeds[which]
                exoPlayer?.setPlaybackSpeed(currentPlaybackSpeed)
                btnSpeedSelector.text = "⏱️ ${currentPlaybackSpeed}x"
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showQualitySelectorDialog() {
        val sd = currentStreamData ?: return
        val tracks = sd.videoTracks
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Solo calidad estándar disponible en este video", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = tracks.map { track ->
            val active = if (selectedQualityHeight == track.height || (selectedQualityHeight == null && track == tracks.first())) " ✅" else ""
            "🎬 ${track.label}$active"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar Calidad de Video")
            .setItems(labels) { _, which ->
                val chosen = tracks[which]
                selectedQualityHeight = chosen.height
                btnQualitySelector.text = "⚙️ ${chosen.label}"
                val currentPos = exoPlayer?.currentPosition ?: 0L
                startDashOrFallbackPlayback(sd, forceMuxed = false, resumePositionMs = currentPos)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    playerLoading.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (playbackState == Player.STATE_ENDED) {
                        val next = verifiedAutoplayNext
                        if (next != null) {
                            Toast.makeText(
                                this@PlayerActivity,
                                "Autoplay Seguro: ${next.title}",
                                Toast.LENGTH_SHORT
                            ).show()
                            playVideoItem(next)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("YTCensuradoPlayer", "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                    val sd = currentStreamData
                    if (!triedMuxedFallback && sd != null && !sd.muxedUrl.isNullOrBlank()) {
                        triedMuxedFallback = true
                        btnQualitySelector.text = "⚙️ 360p (Estándar)"
                        startDashOrFallbackPlayback(sd, forceMuxed = true, resumePositionMs = player.currentPosition)
                    } else {
                        playerLoading.visibility = View.GONE
                        Toast.makeText(
                            this@PlayerActivity,
                            "Error de reproducción (${error.errorCodeName})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        }
    }

    @OptIn(UnstableApi::class)
    private fun playVideoItem(video: VideoItem) {
        currentVideo = video
        currentStreamData = null
        triedMuxedFallback = false
        verifiedAutoplayNext = null
        btnNextSafeVideo.visibility = View.GONE
        btnToggleRelated.visibility = View.GONE
        relatedStripContainer.visibility = View.GONE
        relatedAdapter.submitList(emptyList())
        ConfigManager.currentlyPlayingVideo = video
        ConfigManager.recordWatchHistory(video)

        tvPlayerTitle.text = video.title
        tvPlayerChannel.text = "${video.channelName} • ${video.durationText}"
        playerLoading.visibility = View.VISIBLE
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        lifecycleScope.launch {
            val streamData = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchStreamPlaybackData(video.videoId)
            }

            if (streamData == null) {
                playerLoading.visibility = View.GONE
                Log.e("YTCensuradoPlayer", "fetchStreamPlaybackData returned null for videoId=${video.videoId}")
                Toast.makeText(this@PlayerActivity, "No se pudo extraer el stream del video", Toast.LENGTH_LONG).show()
                return@launch
            }

            currentStreamData = streamData
            val activeTrack = if (selectedQualityHeight != null) {
                streamData.videoTracks.firstOrNull { it.height == selectedQualityHeight }
                    ?: streamData.videoTracks.firstOrNull()
            } else {
                streamData.videoTracks.firstOrNull()
            }
            btnQualitySelector.text = if (activeTrack != null) "⚙️ ${activeTrack.label}" else "⚙️ Estándar"

            startDashOrFallbackPlayback(streamData, forceMuxed = false, resumePositionMs = 0L)

            val (cleanRelated, safeNext) = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchRelatedAndAutoplay(video)
            }
            if (cleanRelated.isNotEmpty()) {
                relatedAdapter.submitList(cleanRelated)
                btnToggleRelated.text = "📺 Más Videos (${cleanRelated.size})"
                btnToggleRelated.visibility = View.VISIBLE
            }
            if (safeNext != null) {
                verifiedAutoplayNext = safeNext
                btnNextSafeVideo.text = "⏭ Siguiente: ${safeNext.title.take(20)}..."
                btnNextSafeVideo.visibility = View.VISIBLE
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startDashOrFallbackPlayback(
        streamData: StreamPlaybackData,
        forceMuxed: Boolean,
        resumePositionMs: Long
    ) {
        val player = exoPlayer ?: return
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(streamData.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)

        val dashXml = if (!forceMuxed) streamData.buildDashManifestXml(selectedQualityHeight) else null

        if (dashXml != null) {
            try {
                val manifest = DashManifestParser().parse(
                    Uri.parse("https://www.youtube.com/dash.mpd"),
                    ByteArrayInputStream(dashXml.toByteArray(Charsets.UTF_8))
                )
                val dashSource = DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(manifest)
                player.setMediaSource(dashSource)
            } catch (e: Exception) {
                Log.e("YTCensuradoPlayer", "Failed parsing DASH manifest, falling back to muxed", e)
                setMuxedOrHlsSource(player, dataSourceFactory, streamData)
            }
        } else {
            setMuxedOrHlsSource(player, dataSourceFactory, streamData)
        }

        player.prepare()
        if (resumePositionMs > 0L) {
            player.seekTo(resumePositionMs)
        }
        player.setPlaybackSpeed(currentPlaybackSpeed)
        player.playWhenReady = true
    }

    @OptIn(UnstableApi::class)
    private fun setMuxedOrHlsSource(
        player: ExoPlayer,
        dataSourceFactory: DefaultHttpDataSource.Factory,
        streamData: StreamPlaybackData
    ) {
        triedMuxedFallback = true
        when {
            !streamData.muxedUrl.isNullOrBlank() -> {
                val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamData.muxedUrl)))
                player.setMediaSource(source)
            }
            !streamData.hlsUrl.isNullOrBlank() -> {
                val source = DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamData.hlsUrl)))
                player.setMediaSource(source)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            btnPlayerParentalPin.performClick()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && relatedStripContainer.visibility == View.VISIBLE) {
            relatedStripContainer.visibility = View.GONE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        remotePollHandler.post(remotePollRunnable)
    }

    override fun onPause() {
        super.onPause()
        remotePollHandler.removeCallbacks(remotePollRunnable)
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ConfigManager.currentlyPlayingVideo?.videoId == currentVideo.videoId) {
            ConfigManager.currentlyPlayingVideo = null
        }
        exoPlayer?.release()
        exoPlayer = null
    }

    inner class RelatedVideoAdapter(
        private val onClick: (VideoItem) -> Unit
    ) : RecyclerView.Adapter<RelatedVideoAdapter.Holder>() {

        private val items = mutableListOf<VideoItem>()

        fun submitList(list: List<VideoItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_related_video_card, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val tvDur: TextView = itemView.findViewById(R.id.tvDuration)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            private val tvChannel: TextView = itemView.findViewById(R.id.tvChannelAndMeta)

            fun bind(video: VideoItem) {
                tvTitle.text = video.title
                tvDur.text = video.durationText
                tvChannel.text = video.channelName
                ivThumb.load(video.thumbnailUrl) {
                    crossfade(true)
                    listener(onError = { _, _ ->
                        val fallback = "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg"
                        if (video.thumbnailUrl != fallback) {
                            ivThumb.load(fallback)
                        }
                    })
                }
                itemView.setOnClickListener { onClick(video) }
            }
        }
    }
}
