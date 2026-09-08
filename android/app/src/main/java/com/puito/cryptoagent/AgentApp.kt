package com.puito.cryptoagent

import android.app.Application
import com.puito.cryptoagent.data.PrefsStore
import com.puito.cryptoagent.net.AgentApi

class AgentApp : Application() {
    lateinit var prefs: PrefsStore
        private set
    lateinit var api: AgentApi
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsStore(this)
        val p = prefs.load()
        api = AgentApi(p.gatewayBaseUrl)
    }
}
