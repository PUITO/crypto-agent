package com.puito.cryptoagent

import android.app.Application
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.net.HibtWebSession
import com.puito.cryptoagent.notify.Notify
import com.puito.cryptoagent.service.MonitorService

class AgentApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = runCatching { Repository(this) }.getOrElse {
            // 损坏的本地缓存不应导致进程起不来
            runCatching {
                getSharedPreferences("agent_local", MODE_PRIVATE).edit()
                    .remove("live_sim_trades")
                    .remove("live_sim_pending")
                    .apply()
            }
            Repository(this)
        }
        runCatching { Notify.channels(this) }
        HibtWebSession.settingsSync = { token, apiBase, origin ->
            runCatching {
                val s = repo.settings()
                val nh = s.hibt.copy(
                    xAuthToken = token,
                    authToken = token,
                    apiBase = apiBase.ifBlank { s.hibt.apiBase },
                    origin = origin.ifBlank { s.hibt.origin },
                    referer = if (origin.isNotBlank()) origin.trimEnd('/') + "/" else s.hibt.referer,
                )
                repo.saveSettings(s.copy(hibt = nh))
            }
        }
        runCatching {
            val s = repo.settings()
            if (s.backgroundEnabled && s.strategyRunning) {
                MonitorService.start(this)
            }
        }
    }
}
