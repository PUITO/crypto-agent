package com.puito.cryptoagent.net

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "请先在「设置」填写 LLM API Key 与 Base URL（OpenAI 兼容，如官方 API 或中转）。"
        }
        val root = baseUrl.trim().trimEnd('/')
        val url = if (root.endsWith("/v1")) "$root/chat/completions" else "$root/v1/chat/completions"
        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            "temperature" to 0.3,
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("LLM HTTP ${resp.code}: ${s.take(300)}")
            val o = JsonParser.parseString(s).asJsonObject
            o.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: s.take(500)
        }
    }
}
