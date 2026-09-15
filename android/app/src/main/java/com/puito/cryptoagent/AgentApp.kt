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
        repo = Repository(this)
        Notify.channels(this)
        // WebView 捕获 token/api 后覆盖写回原生设置（输入框同源 SharedPreferences）
        HibtWebSession.settingsSync = { token, apiBase, origin ->
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
        val s = repo.settings()
        if (s.backgroundEnabled && s.strategyRunning) {
            runCatching { MonitorService.start(this) }
        }
    }
}
