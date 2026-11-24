package com.jawuil.ytcensurado.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.jawuil.ytcensurado.R
import com.jawuil.ytcensurado.model.VideoItem

class VideoCardAdapter(
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongPressOrMenu: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoCardAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvAvatarInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvChannelAndMeta: TextView = itemView.findViewById(R.id.tvChannelAndMeta)

        fun bind(video: VideoItem) {
            tvTitle.text = video.title
            tvDuration.text = video.durationText
            tvAvatarInitial.text = video.channelName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Y"

            val metaParts = mutableListOf<String>()
            if (video.channelName.isNotBlank()) metaParts.add(video.channelName)
            if (video.viewCountText.isNotBlank()) metaParts.add(video.viewCountText)
            if (video.publishedText.isNotBlank()) metaParts.add(video.publishedText)
            tvChannelAndMeta.text = metaParts.joinToString(" • ")

            ivThumbnail.load(video.thumbnailUrl) {
                crossfade(true)
                listener(
                    onError = { _, _ ->
                        val fallback = "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg"
                        if (video.thumbnailUrl != fallback) {
                            ivThumbnail.load(fallback) { crossfade(true) }
                        }
                    }
                )
            }

            // Smooth TV Focus Zoom effect identical to YouTube TV
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(130).start()
            }

            itemView.setOnClickListener {
                onVideoClick(video)
            }

            itemView.setOnLongClickListener {
                onVideoLongPressOrMenu(video)
                true
            }

            // Also intercept TV remote MENU key (KeyEvent.KEYCODE_MENU) to trigger PIN parental action
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onVideoLongPressOrMenu(video)
                    true
                } else {
                    false
                }
            }
        }
    }
}
