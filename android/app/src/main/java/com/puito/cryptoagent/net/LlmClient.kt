package com.puito.cryptoagent.net

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmClient(
    private val gson: Gson = Gson(),
) {
    private fun clientFor(timeoutSec: Int): OkHttpClient {
        val t = timeoutSec.coerceIn(10, 300)
        return OkHttpClient.Builder()
            .connectTimeout(minOf(30, t).toLong(), TimeUnit.SECONDS)
            .readTimeout(t.toLong(), TimeUnit.SECONDS)
            .writeTimeout(t.toLong(), TimeUnit.SECONDS)
            .callTimeout((t + 5).toLong(), TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param maxTokens 限制输出，信号评估用小值省钱
     * @param thinkingEnabled DeepSeek V4：false 时传 thinking disabled
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        timeoutSec: Int = 60,
        maxTokens: Int = 512,
        temperature: Double = 0.3,
        thinkingEnabled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "请先在「设置」填写 LLM API Key 与 Base URL（OpenAI 兼容）。"
        }
        val root = baseUrl.trim().trimEnd('/')
        val url = if (root.endsWith("/v1")) "$root/chat/completions" else "$root/v1/chat/completions"
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            "temperature" to temperature.coerceIn(0.0, 2.0),
            "max_tokens" to maxTokens.coerceIn(16, 4096),
        )
        // DeepSeek：关闭思考链，避免 output 暴涨
        val m = model.lowercase()
        if (m.contains("deepseek") || root.contains("deepseek")) {
            payload["thinking"] = mapOf("type" to if (thinkingEnabled) "enabled" else "disabled")
            if (!thinkingEnabled) {
                // 兼容部分网关
                payload["reasoning_effort"] = "low"
            }
        }
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        clientFor(timeoutSec).newCall(req).execute().use { resp ->
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
