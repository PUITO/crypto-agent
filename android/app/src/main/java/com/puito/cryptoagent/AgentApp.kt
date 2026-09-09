package com.puito.cryptoagent

import android.app.Application
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.notify.Notify
import com.puito.cryptoagent.service.MonitorService

class AgentApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        Notify.channels(this)
        val s = repo.settings()
        if (s.backgroundEnabled && s.strategyRunning) {
            runCatching { MonitorService.start(this) }
        }
    }
}
