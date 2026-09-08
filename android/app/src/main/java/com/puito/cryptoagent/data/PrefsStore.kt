package com.puito.cryptoagent.data

import android.content.Context
import com.google.gson.Gson

class PrefsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("crypto_agent_android", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): AppPrefs {
        val j = sp.getString("prefs", null) ?: return AppPrefs()
        return runCatching { gson.fromJson(j, AppPrefs::class.java) }.getOrDefault(AppPrefs())
    }

    fun save(p: AppPrefs) {
        sp.edit().putString("prefs", gson.toJson(p)).apply()
    }
}
