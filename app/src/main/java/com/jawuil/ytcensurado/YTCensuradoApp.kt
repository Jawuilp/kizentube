package com.jawuil.ytcensurado

import android.app.Application
import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.server.PcControlServer

class YTCensuradoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigManager.init(this)
        PcControlServer.startServer(ConfigManager.serverPort)
    }
}
