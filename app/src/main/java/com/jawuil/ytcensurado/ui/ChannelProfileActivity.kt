package com.jawuil.ytcensurado.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jawuil.ytcensurado.R
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.innertube.InnerTubeClient
import com.jawuil.ytcensurado.model.ChannelEntry
import com.jawuil.ytcensurado.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelProfileActivity : AppCompatActivity() {

    private lateinit var tvChannelAvatar: TextView
    private lateinit var tvChannelName: TextView
    private lateinit var tvChannelMeta: TextView
    private lateinit var btnChannelSubscribe: Button
    private lateinit var btnChannelParentalPin: Button
    private lateinit var rvChannelVideos: RecyclerView
    private lateinit var channelLoadingContainer: LinearLayout
    private lateinit var tvChannelLoading: TextView

    private lateinit var adapter: VideoCardAdapter
    private var channelId: String = ""
    private var channelName: String = ""
    private var channelHandle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_profile)

        channelId = intent.getStringExtra("channelId") ?: ""
        channelName = intent.getStringExtra("channelName") ?: "Canal"
        channelHandle = intent.getStringExtra("channelHandle") ?: ""

        // If channel is blocked, immediately close
        if (ConfigManager.isChannelBlocked(channelId, channelHandle, channelName)) {
            Toast.makeText(this, "Este canal está bloqueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvChannelAvatar = findViewById(R.id.tvChannelAvatar)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvChannelMeta = findViewById(R.id.tvChannelMeta)
        btnChannelSubscribe = findViewById(R.id.btnChannelSubscribe)
        btnChannelParentalPin = findViewById(R.id.btnChannelParentalPin)
        rvChannelVideos = findViewById(R.id.rvChannelVideos)
        channelLoadingContainer = findViewById(R.id.channelLoadingContainer)
        tvChannelLoading = findViewById(R.id.tvChannelLoading)

        findViewById<Button>(R.id.btnChannelBack).setOnClickListener { finish() }

        tvChannelName.text = channelName
        tvChannelAvatar.text = channelName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C"

        btnChannelParentalPin.setOnClickListener {
            val dummyVideo = VideoItem(
                videoId = "",
                title = channelName,
                channelName = channelName,
                channelId = channelId,
                channelHandle = channelHandle,
                thumbnailUrl = "",
                durationSeconds = 0,
                durationText = ""
            )
            PinDialogHelper.showVideoParentalMenu(this, dummyVideo) {
                if (ConfigManager.isChannelBlocked(channelId, channelHandle, channelName)) {
                    ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
                    finish()
                } else {
                    refreshSubscribeUi()
                }
            }
        }

        btnChannelSubscribe.setOnClickListener {
            // Defense-in-depth: never allow toggling if lockSubscriptions is enabled
            if (ConfigManager.lockSubscriptions) return@setOnClickListener
            val isSub = ConfigManager.isChannelWhitelisted(channelId, channelHandle, channelName)
            if (isSub) {
                ConfigManager.removeWhitelistedChannel(channelId, channelName, channelHandle)
                Toast.makeText(this, "Dejaste de seguir a $channelName", Toast.LENGTH_SHORT).show()
            } else {
                ConfigManager.whitelistChannel(channelId.ifBlank { channelHandle.ifBlank { channelName } }, channelName, channelHandle)
                Toast.makeText(this, "Suscrito a $channelName", Toast.LENGTH_SHORT).show()
            }
            ConfigManager.pendingRemoteCommand = "RELOAD_FEED"
            refreshSubscribeUi()
        }

        adapter = VideoCardAdapter(
            onVideoClick = { video -> openPlayer(video) },
            onVideoLongPressOrMenu = { video ->
                PinDialogHelper.showVideoParentalMenu(this, video) {
                    if (ConfigManager.isChannelBlocked(channelId, channelHandle, channelName)) {
                        finish()
                    } else {
                        loadChannelVideos()
                    }
                }
            }
        )

        val cfg = resources.configuration
        val spanCount = when {
            cfg.orientation == Configuration.ORIENTATION_PORTRAIT && cfg.screenWidthDp < 600 -> 1
            cfg.screenWidthDp >= 840 -> 3
            else -> 2
        }
        rvChannelVideos.layoutManager = GridLayoutManager(this, spanCount)
        rvChannelVideos.adapter = adapter

        refreshSubscribeUi()
        loadChannelVideos()
    }

    override fun onResume() {
        super.onResume()
        refreshSubscribeUi()
    }

    private fun refreshSubscribeUi() {
        val handlePart = if (channelHandle.isNotBlank()) "$channelHandle • " else ""
        if (ConfigManager.lockSubscriptions) {
            // Completely absent from screen and D-Pad focus tree
            btnChannelSubscribe.visibility = View.GONE
            btnChannelSubscribe.isFocusable = false
            btnChannelSubscribe.isClickable = false
            tvChannelMeta.text = "${handlePart}Solo lectura • Videos 100% filtrados"
        } else {
            btnChannelSubscribe.visibility = View.VISIBLE
            btnChannelSubscribe.isFocusable = true
            btnChannelSubscribe.isClickable = true
            val isSub = ConfigManager.isChannelWhitelisted(channelId, channelHandle, channelName)
            if (isSub) {
                btnChannelSubscribe.text = "Suscrito ✓"
                btnChannelSubscribe.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E2E2E"))
            } else {
                btnChannelSubscribe.text = "Suscribirse"
                btnChannelSubscribe.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF0033"))
            }
            tvChannelMeta.text = "${handlePart}Videos 100% filtrados"
        }
    }

    private fun loadChannelVideos() {
        channelLoadingContainer.visibility = View.VISIBLE
        tvChannelLoading.text = "Cargando y filtrando videos de $channelName..."
        val entry = ChannelEntry(
            id = channelId.ifBlank { channelHandle.ifBlank { channelName } },
            name = channelName,
            handle = channelHandle
        )
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchChannelVideos(entry)
            }
            adapter.submitList(videos)
            if (videos.isEmpty()) {
                tvChannelLoading.text = "No hay videos permitidos para este canal según tus filtros."
                channelLoadingContainer.visibility = View.VISIBLE
            } else {
                channelLoadingContainer.visibility = View.GONE
            }
        }
    }

    private fun openPlayer(video: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("videoId", video.videoId)
            putExtra("title", video.title)
            putExtra("channelName", video.channelName)
            putExtra("channelId", video.channelId)
            putExtra("channelHandle", video.channelHandle)
            putExtra("thumbnailUrl", video.thumbnailUrl)
            putExtra("durationSeconds", video.durationSeconds)
            putExtra("durationText", video.durationText)
        }
        startActivity(intent)
    }
}
