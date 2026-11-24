package com.jawuil.ytcensurado.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
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
import com.jawuil.ytcensurado.server.PcControlServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var navHome: TextView
    private lateinit var navSubscriptions: TextView
    private lateinit var navSearch: TextView
    private lateinit var navProfile: TextView
    private lateinit var navParentalPin: TextView
    private lateinit var tvSectionTitle: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearchQuery: EditText
    private lateinit var btnRunSearch: Button
    private lateinit var profileHeaderContainer: LinearLayout
    private lateinit var tvProfileAccountSummary: TextView
    private lateinit var btnProfileSync: Button
    private lateinit var btnProfileClearHistory: Button
    private lateinit var followedChannelsRow: LinearLayout
    private lateinit var tvProfileHistoryTitle: TextView
    private lateinit var rvVideos: RecyclerView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var tvLoadingMessage: TextView

    private lateinit var adapter: VideoCardAdapter
    private var currentTab: String = "HOME"
    private var fetchJob: Job? = null

    private val remotePollHandler = Handler(Looper.getMainLooper())
    private val remotePollRunnable = object : Runnable {
        override fun run() {
            val cmd = ConfigManager.pendingRemoteCommand
            if (cmd == "RELOAD_FEED") {
                ConfigManager.pendingRemoteCommand = null
                ConfigManager.cachedHomeFeed = emptyList()
                ConfigManager.cachedSubsFeed = emptyList()
                refreshUiStateFromConfig()
                reloadCurrentTab()
            }
            remotePollHandler.postDelayed(this, 1800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        PcControlServer.startServer(ConfigManager.serverPort)

        setContentView(R.layout.activity_main)

        navHome = findViewById(R.id.navHome)
        navSubscriptions = findViewById(R.id.navSubscriptions)
        navSearch = findViewById(R.id.navSearch)
        navProfile = findViewById(R.id.navProfile)
        navParentalPin = findViewById(R.id.navParentalPin)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        searchBarContainer = findViewById(R.id.searchBarContainer)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnRunSearch = findViewById(R.id.btnRunSearch)
        profileHeaderContainer = findViewById(R.id.profileHeaderContainer)
        tvProfileAccountSummary = findViewById(R.id.tvProfileAccountSummary)
        btnProfileSync = findViewById(R.id.btnProfileSync)
        btnProfileClearHistory = findViewById(R.id.btnProfileClearHistory)
        followedChannelsRow = findViewById(R.id.followedChannelsRow)
        tvProfileHistoryTitle = findViewById(R.id.tvProfileHistoryTitle)
        rvVideos = findViewById(R.id.rvVideos)
        loadingContainer = findViewById(R.id.loadingContainer)
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage)

        adapter = VideoCardAdapter(
            onVideoClick = { video -> openPlayer(video) },
            onVideoLongPressOrMenu = { video ->
                PinDialogHelper.showVideoParentalMenu(this, video) {
                    ConfigManager.cachedHomeFeed = emptyList()
                    ConfigManager.cachedSubsFeed = emptyList()
                    reloadCurrentTab()
                }
            }
        )

        rvVideos.layoutManager = GridLayoutManager(this, calculateSpanCount())
        rvVideos.adapter = adapter

        setupSidebarNavigation()
        setupSearchBox()
        setupProfileActions()
        refreshUiStateFromConfig()
        val restoredTab = savedInstanceState?.getString("currentTab") ?: "HOME"
        switchTab(restoredTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentTab", currentTab)
    }

    private fun calculateSpanCount(): Int {
        val cfg = resources.configuration
        if (cfg.orientation == Configuration.ORIENTATION_PORTRAIT && cfg.screenWidthDp < 600) {
            return 1
        }
        return if (cfg.screenWidthDp >= 840) 3 else 2
    }

    override fun onResume() {
        super.onResume()
        PcControlServer.startServer(ConfigManager.serverPort)
        refreshUiStateFromConfig()
        if (currentTab == "PROFILE") {
            loadProfileSection()
        }
        remotePollHandler.post(remotePollRunnable)
    }

    override fun onPause() {
        super.onPause()
        remotePollHandler.removeCallbacks(remotePollRunnable)
    }

    private fun setupSidebarNavigation() {
        val navItems = listOf(navHome, navSubscriptions, navSearch, navProfile, navParentalPin)
        navItems.forEach { tv ->
            tv.setOnFocusChangeListener { _, hasFocus ->
                tv.setTextColor(if (hasFocus) Color.parseColor("#0F0F0F") else Color.parseColor("#F1F1F1"))
            }
        }

        navHome.setOnClickListener { switchTab("HOME") }
        navSubscriptions.setOnClickListener { switchTab("SUBS") }
        navSearch.setOnClickListener { switchTab("SEARCH") }
        navProfile.setOnClickListener { switchTab("PROFILE") }
        navParentalPin.setOnClickListener {
            PinDialogHelper.showTvParentalDashboard(this) {
                refreshUiStateFromConfig()
                reloadCurrentTab()
            }
        }
    }

    private fun setupSearchBox() {
        btnRunSearch.setOnClickListener {
            val q = etSearchQuery.text?.toString()?.trim() ?: ""
            if (q.isNotEmpty()) {
                loadSearchFeed(q)
            }
        }
        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val q = etSearchQuery.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) {
                    loadSearchFeed(q)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupProfileActions() {
        btnProfileSync.setOnClickListener {
            PinDialogHelper.requirePin(this, "Sincronizar Cuenta / Suscripciones") {
                if (!ConfigManager.isAccountConnected()) {
                    startActivity(Intent(this, YouTubeLoginActivity::class.java))
                } else {
                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity, "Sincronizando canales seguidos...", Toast.LENGTH_SHORT).show()
                        val count = withContext(Dispatchers.IO) {
                            InnerTubeClient.importAccountSubscriptionsToWhitelist()
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "Sincronizado ($count canales nuevos agregados)",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadProfileSection()
                    }
                }
            }
        }

        btnProfileClearHistory.setOnClickListener {
            PinDialogHelper.requirePin(this, "Limpiar Historial de Reproducción") {
                ConfigManager.clearWatchHistory()
                Toast.makeText(this, "Historial limpiado", Toast.LENGTH_SHORT).show()
                loadProfileSection()
            }
        }
    }

    private fun refreshUiStateFromConfig() {
        navSearch.visibility = if (ConfigManager.searchEnabledOnTv) View.VISIBLE else View.GONE
        val modeLabel = if (ConfigManager.whitelistOnlyMode) {
            "🛡️ Modo Lista Blanca Estricta"
        } else {
            "🛡️ 0% Shorts • Anti-Brainrot Activo"
        }
        tvStatusBadge.text = modeLabel
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        navHome.isSelected = (tab == "HOME")
        navSubscriptions.isSelected = (tab == "SUBS")
        navSearch.isSelected = (tab == "SEARCH")
        navProfile.isSelected = (tab == "PROFILE")

        searchBarContainer.visibility = if (tab == "SEARCH") View.VISIBLE else View.GONE
        profileHeaderContainer.visibility = if (tab == "PROFILE") View.VISIBLE else View.GONE

        when (tab) {
            "HOME" -> {
                tvSectionTitle.text = if (ConfigManager.whitelistOnlyMode) "Inicio (Solo Canales Aprobados)" else "Recomendados"
                loadHomeFeed()
            }
            "SUBS" -> {
                tvSectionTitle.text = "Suscripciones y Canales Seguros"
                loadSubscriptionsFeed()
            }
            "SEARCH" -> {
                tvSectionTitle.text = "Búsqueda Blindada"
                etSearchQuery.requestFocus()
            }
            "PROFILE" -> {
                tvSectionTitle.text = "Mi Perfil, Seguidos e Historial"
                loadProfileSection()
            }
        }
    }

    private fun reloadCurrentTab() {
        when (currentTab) {
            "HOME" -> loadHomeFeed()
            "SUBS" -> loadSubscriptionsFeed()
            "SEARCH" -> {
                val q = etSearchQuery.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) loadSearchFeed(q)
            }
            "PROFILE" -> loadProfileSection()
        }
    }

    private fun loadHomeFeed() {
        fetchJob?.cancel()
        val cached = ConfigManager.cachedHomeFeed
        if (cached.isNotEmpty()) {
            // 0ms instant display from cache while refreshing silently in background
            loadingContainer.visibility = View.GONE
            adapter.submitList(cached)
        } else {
            showLoading("Analizando y purgando Shorts/Brainrot antes de renderizar...")
        }

        fetchJob = lifecycleScope.launch {
            val cleanVideos = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchHomeFeed()
            }
            if (currentTab == "HOME") {
                displayVideos(cleanVideos)
            }
        }
    }

    private fun loadSubscriptionsFeed() {
        fetchJob?.cancel()
        val cached = ConfigManager.cachedSubsFeed
        if (cached.isNotEmpty()) {
            loadingContainer.visibility = View.GONE
            adapter.submitList(cached)
        } else {
            showLoading("Cargando canales seguros de Lista Blanca...")
        }

        fetchJob = lifecycleScope.launch {
            val cleanVideos = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchSubscriptionsFeed()
            }
            if (currentTab == "SUBS") {
                displayVideos(cleanVideos)
            }
        }
    }

    private fun loadSearchFeed(query: String) {
        fetchJob?.cancel()
        showLoading("Filtrando resultados para '$query'...")
        fetchJob = lifecycleScope.launch {
            val cleanVideos = withContext(Dispatchers.IO) {
                InnerTubeClient.searchFiltered(query)
            }
            if (currentTab == "SEARCH") {
                displayVideos(cleanVideos)
            }
        }
    }

    private fun loadProfileSection() {
        fetchJob?.cancel()
        loadingContainer.visibility = View.GONE

        val connected = ConfigManager.isAccountConnected()
        val channelCount = ConfigManager.whitelistedChannels.size
        tvProfileAccountSummary.text = if (connected) {
            "👤 Cuenta de YouTube: Conectada ✅  •  $channelCount canales seguidos"
        } else {
            "👤 Cuenta de YouTube: Invitado  •  $channelCount canales seguidos"
        }
        btnProfileSync.text = if (connected) "🔄 Sincronizar" else "🔑 Conectar"

        followedChannelsRow.removeAllViews()
        if (ConfigManager.whitelistedChannels.isEmpty()) {
            val emptyChip = TextView(this).apply {
                text = "Sin canales seguidos aún (conecta tu cuenta o aprueba canales)"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 13f
                setPadding(8, 8, 8, 8)
            }
            followedChannelsRow.addView(emptyChip)
        } else {
            for (channel in ConfigManager.whitelistedChannels) {
                val chip = Button(this).apply {
                    text = "📺 ${channel.name}"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.bg_settings_row_selector)
                    setPadding(24, 10, 24, 10)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        42.dpToPx()
                    ).apply { marginEnd = 10 }
                    setOnClickListener { loadFollowedChannelVideos(channel) }
                }
                followedChannelsRow.addView(chip)
            }
        }

        val history = ConfigManager.watchHistory.toList()
        tvProfileHistoryTitle.text = "Historial de Reproducción (${history.size})"
        adapter.submitList(history)
        if (history.isEmpty()) {
            tvLoadingMessage.text = "Aún no has visto videos en esta sesión. Toca cualquier canal seguido arriba o ve a Inicio."
            loadingContainer.visibility = View.VISIBLE
        }
    }

    private fun loadFollowedChannelVideos(channel: ChannelEntry) {
        fetchJob?.cancel()
        tvProfileHistoryTitle.text = "Videos de: ${channel.name} (100% filtrados)"
        showLoading("Cargando videos de ${channel.name}...")
        fetchJob = lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                InnerTubeClient.fetchChannelVideos(channel)
            }
            if (currentTab == "PROFILE") {
                displayVideos(videos)
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showLoading(msg: String) {
        adapter.submitList(emptyList())
        tvLoadingMessage.text = msg
        loadingContainer.visibility = View.VISIBLE
    }

    private fun displayVideos(videos: List<VideoItem>) {
        loadingContainer.visibility = View.GONE
        adapter.submitList(videos)
        if (videos.isEmpty()) {
            tvLoadingMessage.text = if (ConfigManager.whitelistOnlyMode && ConfigManager.whitelistedChannels.isEmpty()) {
                "Modo Lista Blanca activo: agrega canales aprobados desde tu PC en http://${PcControlServer.getLocalIpAddress()}:${ConfigManager.serverPort}"
            } else {
                "Todos los resultados fueron bloqueados por el filtro anti-brainrot."
            }
            loadingContainer.visibility = View.VISIBLE
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
